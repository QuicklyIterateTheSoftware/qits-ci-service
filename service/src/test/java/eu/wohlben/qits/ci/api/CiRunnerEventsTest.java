package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.RecordingRunnerAnnouncer;
import eu.wohlben.qits.ci.control.RecordingRunnerAnnouncer.Announced;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.StubIdp;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runner surface's four row events, at the port: each write that changes what a runner is
 * announces its event <b>exactly once</b>, after it committed and carrying what was written — and a
 * refused request, whatever refused it, announces nothing at all.
 *
 * <p>Asserted against {@link RecordingRunnerAnnouncer}, which sits beside the bus announcer rather
 * than replacing it, so what is counted here is what every announcer is handed. What the bus
 * announcer makes of a call — the record, the canonical payload, the PUT — is {@code
 * RunnerLifecyclePublishTest}'s, and the payload bytes are {@code RunnerLifecycleEventsTest}'s in
 * {@code ci-events/}.
 *
 * <p>{@link MachineGuardTest.GateOn} and {@link StubIdp} for {@code CiRunnerControllerTest}'s
 * reasons, reused rather than copied: the register door reads its subject off a token, and one
 * profile is one Quarkus start.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class CiRunnerEventsTest {

  private static final String RUNNERS = "/ci/api/runners";

  private static final String ADMIN = "qits:admin";

  private static final String REGISTRATION = "qits:ci-runner-registration";

  private static final String SUBJECT = "tok-ci-runner-registration-events";

  private static final String REPO = "runner-events-repo";

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunRepository runs;

  @Inject RecordingRunnerAnnouncer announcer;

  private StubIdp idp;

  @BeforeEach
  void scriptTheIdp() {
    idp = new StubIdp();
    QuarkusMock.installMockForType(idp.commissioner(Duration.ofMillis(200)), IdpCommissioner.class);
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
    announcer.reset();
  }

  @AfterEach
  void stop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runs.delete("repoId = ?1", REPO);
              runnerRows.deleteAll();
            });
    idp.close();
  }

  private String create(String name) {
    return given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"" + name + "\",\"description\":\"a test runner\",\"slots\":2}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private void patch(String id, String body, int status) {
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(status);
  }

  private CiRunner row(String id) {
    return QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(UUID.fromString(id)));
  }

  // --- created --------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aCreateAnnouncesRunnerCreatedOnceWithTheRowAsWritten() {
    String id = create("events-created");

    List<Announced> announced = announcer.of(id);
    assertEquals(List.of("RunnerCreated"), announcer.eventsOf(id));
    Announced created = announced.get(0);
    assertEquals("events-created", created.fact("runnerName"));
    assertEquals(2, created.fact("slots"));
    // The suite knows no public domain, so the default plane is INTERNAL — as a plain word.
    assertEquals("INTERNAL", created.fact("plane"));
    assertEquals("a test runner", created.fact("description"));
    // The row's own createdAt, not a clock read at announce time.
    assertWithinAMicrosecond(row(id).createdAt, (Instant) created.fact("occurredAt"));
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRefusedCreateAnnouncesNothing() {
    create("events-taken");
    announcer.reset();

    // 409: the name is taken.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"events-taken\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(409);
    // 400: a malformed name, negative slots, an EDGE plane this qits-ci cannot compose.
    for (String body :
        List.of(
            "{\"name\":\"Not_A_Name\",\"slots\":1}",
            "{\"name\":\"events-negative\",\"slots\":-1}",
            "{\"name\":\"events-edge\",\"plane\":\"EDGE\"}")) {
      given()
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .when()
          .post(RUNNERS)
          .then()
          .statusCode(400);
    }
    // 502: qits-idp would not mint the registration token, so no row was written.
    idp.tokenMintStatus = 403;
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"events-unminted\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(502);

    assertEquals(List.of(), announcer.all());
  }

  @Test
  @TestSecurity(user = "watcher", roles = {"qits:agent"})
  void aForbiddenWriteAnnouncesNothing() {
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"events-forbidden\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(403);
    given().when().delete(RUNNERS + "/" + UUID.randomUUID()).then().statusCode(403);

    assertEquals(List.of(), announcer.all());
  }

  // --- changed --------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aPatchAnnouncesRunnerChangedOnceWithTheWholeStateAndWhatMoved() {
    String id = create("events-changed");
    announcer.reset();

    patch(id, "{\"slots\":0}", 200);

    assertEquals(List.of("RunnerChanged"), announcer.eventsOf(id));
    Announced drained = announcer.of(id).get(0);
    assertEquals(List.of("slots"), drained.fact("changed"));
    assertEquals(0, drained.fact("slots"));
    assertEquals("INTERNAL", drained.fact("plane"));
    assertEquals("a test runner", drained.fact("description"), "the whole state, not the delta");

    // Two settings in one PATCH are one event naming both, in RunnerChanged's order; a cleared
    // description is named and its value is gone.
    announcer.reset();
    patch(id, "{\"description\":\" \",\"slots\":3}", 200);
    assertEquals(List.of("RunnerChanged"), announcer.eventsOf(id));
    Announced both = announcer.of(id).get(0);
    assertEquals(List.of("slots", "description"), both.fact("changed"));
    assertEquals(3, both.fact("slots"));
    assertNull(both.fact("description"));
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aPatchThatMovesNothingOrIsRefusedAnnouncesNothing() {
    String id = create("events-unchanged");
    announcer.reset();

    // The row's own values again, and an empty body: nothing moved, so nothing is said.
    patch(id, "{\"slots\":2,\"description\":\"a test runner\",\"plane\":\"INTERNAL\"}", 200);
    patch(id, "{}", 200);
    // 400 and 404.
    patch(id, "{\"slots\":-1}", 400);
    patch(id, "{\"plane\":\"EDGE\"}", 400);
    patch(UUID.randomUUID().toString(), "{\"slots\":1}", 404);

    assertEquals(List.of(), announcer.all());
  }

  // --- deleted --------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aDeleteAnnouncesRunnerDeletedOnceAndARefusedOneNothing() {
    String id = create("events-deleted");
    String running = insertRun(UUID.fromString(id), CiRunStatus.RUNNING);
    announcer.reset();

    // 409: it holds a running run, and stays.
    given().when().delete(RUNNERS + "/" + id).then().statusCode(409);
    given().when().delete(RUNNERS + "/" + UUID.randomUUID()).then().statusCode(404);
    assertEquals(List.of(), announcer.all());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = runs.findById(running);
              run.status = CiRunStatus.SUCCESS;
              run.finishedAt = Instant.now();
            });
    given().when().delete(RUNNERS + "/" + id).then().statusCode(204);

    assertEquals(List.of("RunnerDeleted"), announcer.eventsOf(id));
    assertEquals("events-deleted", announcer.of(id).get(0).fact("runnerName"));
  }

  // --- registered -----------------------------------------------------------------------------

  /** A declared runner written straight to the table, so its creation announced nothing. */
  private UUID declaredRunner(String name) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRunner runner = new CiRunner();
              runner.id = id;
              runner.name = name;
              runner.slots = 1;
              runner.plane = CiRunnerPlane.INTERNAL;
              runner.registrationTokenId = "token-of-" + name;
              runner.registrationTokenSubject = SUBJECT;
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
            });
    return id;
  }

  private io.restassured.response.ValidatableResponse register(UUID id, String body) {
    return given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .when()
        .post(RUNNERS + "/" + id + "/register")
        .then();
  }

  @Test
  @TestSecurity(user = SUBJECT, roles = {REGISTRATION})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = "qits-platform"), @Claim(key = "sub", value = SUBJECT)})
  void aRegistrationAnnouncesRunnerRegisteredOnceAndItsReplayNothing() {
    UUID id = declaredRunner("events-registering");

    register(
            id,
            "{\"capabilities\":{\"docker\":true,\"arch\":\"arm64\",\"os\":\"linux\","
                + "\"labels\":{\"site\":\"attic\"}}}")
        .statusCode(200);

    assertEquals(List.of("RunnerRegistered"), announcer.eventsOf(id.toString()));
    Announced registered = announcer.of(id.toString()).get(0);
    assertEquals("events-registering", registered.fact("runnerName"));
    assertEquals("run-client-1", registered.fact("clientId"));
    assertEquals(Boolean.TRUE, registered.fact("docker"));
    assertEquals("arm64", registered.fact("arch"));
    assertEquals("linux", registered.fact("os"));
    assertWithinAMicrosecond(
        row(id.toString()).registeredAt, (Instant) registered.fact("occurredAt"));

    // 409: the same token again, for a runner that has registered.
    register(id, "{\"capabilities\":{}}").statusCode(409);
    assertEquals(1, announcer.of(id.toString()).size(), "a replay announces nothing");
  }

  @Test
  @TestSecurity(user = SUBJECT, roles = {REGISTRATION})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = "qits-platform"), @Claim(key = "sub", value = SUBJECT)})
  void aRefusedRegistrationAnnouncesNothingAndAnUnsaidHostFactIsNull() {
    UUID mine = declaredRunner("events-mine");
    UUID theirs = declaredRunner("events-theirs");
    QuarkusTransaction.requiringNew()
        .run(() -> runnerRows.findById(theirs).registrationTokenSubject = "tok-someone-else");

    register(theirs, "{\"capabilities\":{}}").statusCode(403);
    register(UUID.randomUUID(), "{\"capabilities\":{}}").statusCode(404);
    register(mine, "{\"capabilities\":[1,2]}").statusCode(400);
    assertEquals(List.of(), announcer.all());

    // A runner that says nothing about its host registers all the same; the facts are null.
    register(mine, "{\"capabilities\":{\"docker\":\"yes\"}}").statusCode(200);
    Announced registered = announcer.of(mine.toString()).get(0);
    assertNull(registered.fact("docker"), "said, but not as a boolean: not read");
    assertNull(registered.fact("arch"));
    assertNull(registered.fact("os"));
    assertTrue(announcer.of(theirs.toString()).isEmpty());
  }

  /**
   * The row's instant and the announced one, to within the microsecond the column rounds to — {@code
   * RunAnnounceSeamTest}'s comparison, for its reason: a clock read at announce time would be tens of
   * microseconds later at best.
   */
  private static void assertWithinAMicrosecond(Instant row, Instant announced) {
    assertTrue(
        Duration.between(row, announced).abs().toNanos() < 1_000,
        "expected the row's own instant (" + row + "), got " + announced);
  }

  private String insertRun(UUID runnerId, CiRunStatus status) {
    String runId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = REPO;
              run.branch = "main";
              run.commitSha = "e".repeat(40);
              run.status = status;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-runner-events.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = "CiRunnerEventsProbe";
              run.createdAt = Instant.now();
              run.startedAt = Instant.now();
              run.runnerId = runnerId;
              runs.persist(run);
            });
    return runId;
  }
}
