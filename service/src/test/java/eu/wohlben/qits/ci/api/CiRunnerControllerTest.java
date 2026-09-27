package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
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
import io.restassured.path.json.JsonPath;
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
 * The runners' surface against a scripted qits-idp: the six operator verbs, and the register door's
 * rules — the subject decides, the answer is given once, and every refusal leaves nothing minted
 * behind.
 *
 * <p><b>The gate is on</b> ({@link MachineGuardTest.GateOn}, reused rather than copied: one profile
 * is one Quarkus start), because the register door is a machine door and its subject is a claim on a
 * token — with the gate off there is no token to read one from, and every case here would pass or
 * fail for the forward-auth {@code dev} identity's reasons instead.
 *
 * <p><b>qits-idp is {@link StubIdp}, a real server on a real socket</b>, and the commissioner is
 * installed over the bean with {@link QuarkusMock} — hand-wired to the stub, since the suite ships
 * the qits oidc client off and a commissioner reading that would commission nothing. What the stub
 * records (bodies posted, ids deleted) is what "the token went back" and "nothing was minted" are
 * asserted against.
 *
 * <p>The registration subject a runner's row holds is the stub's — {@code
 * tok-ci-runner-registration-<id>-<n>}, unknowable when an annotation is written — so the register
 * cases set the row's subject to {@link #SUBJECT} first. What is under test is the comparison, not
 * the value.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class CiRunnerControllerTest {

  private static final String RUNNERS = "/ci/api/runners";

  private static final String ADMIN = "qits:admin";

  private static final String AGENT = "qits:agent";

  private static final String REGISTRATION = "qits:ci-runner-registration";

  /** The subject the register cases' token carries, and which they write onto the row. */
  private static final String SUBJECT = "tok-ci-runner-registration-under-test";

  private static final String OWN_AUDIENCE = "qits-platform";

  /** Every run this class writes is under this repository, so it can take them all away again. */
  private static final String REPO = "runner-controller-repo";

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunRepository runs;

  private StubIdp idp;

  @BeforeEach
  void scriptTheIdp() {
    idp = new StubIdp();
    QuarkusMock.installMockForType(idp.commissioner(Duration.ofMillis(200)), IdpCommissioner.class);
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
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

  private JsonPath create(String name) {
    return given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"" + name + "\",\"description\":\"a test runner\",\"slots\":2}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(201)
        .extract()
        .jsonPath();
  }

  private void armSubject(UUID runnerId) {
    QuarkusTransaction.requiringNew()
        .run(() -> runnerRows.findById(runnerId).registrationTokenSubject = SUBJECT);
  }

  private CiRunner row(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(id));
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void creatingARunnerCommissionsItsRegistrationTokenAndAnswersTheValueOnce() {
    JsonPath created = create("build-host-1");

    UUID id = UUID.fromString(created.getString("runner.id"));
    assertEquals("build-host-1", created.getString("runner.name"));
    assertEquals(2, created.getInt("runner.slots"));
    assertEquals("INTERNAL", created.getString("runner.plane"));
    assertFalse(created.getBoolean("runner.registered"));
    assertFalse(created.getBoolean("runner.connected"));
    assertEquals("qits_tok_stub-1", created.getString("registrationToken"));

    // What qits-idp was asked for: a registration token for exactly this runner, pushing nothing.
    assertEquals(
        List.of(
            "{\"contextKind\":\"ci-runner-registration\",\"contextId\":\"" + id + "\",\"gitRefs\":[]}"),
        idp.postedTokens);
    // The row holds the handles, never the value.
    CiRunner row = row(id);
    assertEquals("token-1", row.registrationTokenId);
    assertEquals("tok-ci-runner-registration-" + id + "-1", row.registrationTokenSubject);

    // And no read carries the value, the id or the subject again.
    String listing = given().when().get(RUNNERS).then().statusCode(200).extract().asString();
    String single = given().when().get(RUNNERS + "/" + id).then().statusCode(200).extract().asString();
    for (String body : List.of(listing, single)) {
      assertFalse(body.contains("qits_tok_"), body);
      assertFalse(body.contains("token-1"), body);
      assertFalse(body.contains("tok-ci-runner-registration"), body);
    }
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aTakenNameIs409AndCommissionsNothing() {
    create("taken");

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"taken\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(409);

    assertEquals(1, idp.postedTokens.size(), "the second create was refused before qits-idp");
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"Not_A_Name\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(400);
    assertEquals(1, idp.postedTokens.size());
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRefusedTokenIs502AndRecordsNoRunner() {
    idp.tokenMintStatus = 403;

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"unminted\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(502);

    assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> runnerRows.count()));
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void patchTakesSlotsZeroAndRefusesNegativeSlots() {
    String id = create("tunable").getString("runner.id");

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"slots\":0}")
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("slots", equalTo(0))
        .body("description", equalTo("a test runner"));
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"slots\":-1}")
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(400);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"slots\":1}")
        .when()
        .patch(RUNNERS + "/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRotationAnswersANewTokenAndGivesTheOldOneBack() {
    String id = create("rotating").getString("runner.id");

    given()
        .when()
        .post(RUNNERS + "/" + id + "/registration-token")
        .then()
        .statusCode(200)
        .body("registrationToken", equalTo("qits_tok_stub-2"))
        .body("runner.name", equalTo("rotating"));

    assertEquals(List.of("token-1"), idp.deletedTokens);
    assertEquals("token-2", row(UUID.fromString(id)).registrationTokenId);
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRunnerHoldingARunningRunIs409AndItsCredentialsGoBackWhenItIsDeleted() {
    UUID id = UUID.fromString(create("busy").getString("runner.id"));
    QuarkusTransaction.requiringNew().run(() -> runnerRows.findById(id).clientId = "dyn-busy");
    String running = insertRun(id, CiRunStatus.RUNNING);

    given().when().delete(RUNNERS + "/" + id).then().statusCode(409);
    given().when().get(RUNNERS + "/" + id).then().statusCode(200).body("heldRuns", equalTo(1));
    assertEquals(List.of(), idp.deleted, "nothing is given back while the runner stays");

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = runs.findById(running);
              run.status = CiRunStatus.SUCCESS;
              run.finishedAt = Instant.now();
            });
    given().when().delete(RUNNERS + "/" + id).then().statusCode(204);

    assertNull(row(id));
    assertEquals(List.of("dyn-busy"), idp.deleted);
    assertEquals(List.of("token-1"), idp.deletedTokens);
    // The run is history, and still names the runner it ran on.
    assertEquals(id, QuarkusTransaction.requiringNew().call(() -> runs.findById(running)).runnerId);
    given().when().get(RUNNERS + "/" + id).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "watcher", roles = {AGENT})
  void anAgentReadsRunnersAndWritesNone() {
    given().when().get(RUNNERS).then().statusCode(200).body("runners", hasSize(0));
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"nope\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(403);
    given().when().delete(RUNNERS + "/" + UUID.randomUUID()).then().statusCode(403);
  }

  // --- the register door --------------------------------------------------------------------------

  /** A runner the operator declared, with its row's subject set to the one the cases present. */
  private UUID declaredRunner(String name) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRunner runner = new CiRunner();
              runner.id = id;
              runner.name = name;
              runner.slots = 1;
              runner.plane = eu.wohlben.qits.ci.entity.CiRunnerPlane.INTERNAL;
              runner.registrationTokenId = "token-of-" + name;
              runner.registrationTokenSubject = "unset";
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
            });
    armSubject(id);
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
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = SUBJECT)})
  void theRightTokenRegistersOnceAndIsAnsweredItsClientOnce() {
    UUID id = declaredRunner("registering");

    JsonPath answer =
        register(id, "{\"capabilities\":{\"arch\":\"amd64\",\"docker\":true}}")
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals("run-client-1", answer.getString("clientId"));
    assertEquals("run-s3cr3t-1", answer.getString("secret"));
    assertEquals("qits-platform", answer.getString("audience"));
    assertTrue(answer.getString("tokenUrl").endsWith("/idp/token"), answer.getString("tokenUrl"));
    // The shipped base: the internal alias, derived from the environment exactly as the step
    // daemons' own dial-back address is.
    String environment = System.getenv().getOrDefault("QITS_ENVIRONMENT", "dev");
    assertEquals(
        "ws://" + environment + "-qits-ci:8080/ci/runners/socket", answer.getString("socketUrl"));
    // What was commissioned: a ci-runner client for exactly this runner, pushing nothing.
    assertEquals(
        List.of("{\"contextKind\":\"ci-runner\",\"contextId\":\"" + id + "\",\"gitRefs\":[]}"),
        idp.posted);
    // The spent registration token went back.
    assertEquals(List.of("token-of-registering"), idp.deletedTokens);
    CiRunner row = row(id);
    assertEquals("run-client-1", row.clientId);
    assertNotNull(row.registeredAt);
    assertTrue(row.capabilities.contains("amd64"), row.capabilities);

    // Once: the same token again is the right token for a runner that has registered.
    register(id, "{\"capabilities\":{}}").statusCode(409).body("secret", nullValue());
    assertEquals(1, idp.posted.size(), "a replay commissions nothing");
  }

  @Test
  @TestSecurity(user = SUBJECT, roles = {REGISTRATION})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = SUBJECT)})
  void anotherRunnersTokenIs403AndCommissionsNothing() {
    UUID mine = declaredRunner("mine");
    UUID theirs = declaredRunner("theirs");
    QuarkusTransaction.requiringNew()
        .run(() -> runnerRows.findById(theirs).registrationTokenSubject = "tok-someone-else");

    register(theirs, "{\"capabilities\":{}}").statusCode(403);
    register(UUID.randomUUID(), "{\"capabilities\":{}}").statusCode(404);
    register(mine, "{\"capabilities\":[1,2]}").statusCode(400);

    assertEquals(List.of(), idp.posted);
    assertNull(row(theirs).clientId);
  }

  // --- the raw token, presented on qits-net where there is no edge ---------------------------------

  /** What qits-idp answers about a raw token: a live one of {@code kind} for {@code contextId}. */
  private void stageLiveToken(String value, String kind, String contextId, String subject) {
    String role =
        IdpCommissioner.RUNNER_REGISTRATION_KIND.equals(kind) ? REGISTRATION : "qits:" + kind;
    idp.introspection.put(
        value,
        "{\"tokenId\":\"token-" + value + "\",\"subject\":\"" + subject + "\",\"roles\":[\""
            + role + "\",\"clients/" + subject + "\"],\"claims\":{},\"gitRefs\":[],"
            + "\"contextKind\":\"" + kind + "\",\"contextId\":\"" + contextId + "\","
            + "\"accessToken\":\"eyJ.not.used\",\"expiresIn\":300}");
  }

  private io.restassured.response.ValidatableResponse registerRaw(UUID id, String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"capabilities\":{\"docker\":true}}")
        .when()
        .post(RUNNERS + "/" + id + "/register")
        .then();
  }

  @Test
  void theRawRegistrationTokenRegistersItsOwnRunnerWithNoEdgeInBetween() {
    UUID id = declaredRunner("on-qits-net");
    stageLiveToken(
        "qits_tok_raw-own", IdpCommissioner.RUNNER_REGISTRATION_KIND, id.toString(), SUBJECT);

    JsonPath answer =
        registerRaw(id, "qits_tok_raw-own").statusCode(200).extract().jsonPath();

    assertEquals("run-client-1", answer.getString("clientId"));
    assertEquals("run-client-1", row(id).clientId);
    // This service asked qits-idp itself, with its own pair — the edge's question, asked here.
    assertEquals(List.of("qits_tok_raw-own"), idp.introspected);
    assertTrue(idp.introspectionCallers.get(0).startsWith("Basic "));
    assertEquals(List.of("token-of-on-qits-net"), idp.deletedTokens);
  }

  @Test
  void aRawTokenForAnotherRunnerOrOfAnotherKindIs403AndCommissionsNothing() {
    UUID mine = declaredRunner("raw-mine");
    UUID theirs = declaredRunner("raw-theirs");
    // A live registration token — for the other runner, presented at mine.
    stageLiveToken(
        "qits_tok_raw-theirs", IdpCommissioner.RUNNER_REGISTRATION_KIND, theirs.toString(), SUBJECT);
    registerRaw(mine, "qits_tok_raw-theirs").statusCode(403);
    // Its context is mine, but its subject is not the one my row was issued.
    stageLiveToken(
        "qits_tok_raw-stale",
        IdpCommissioner.RUNNER_REGISTRATION_KIND,
        mine.toString(),
        "tok-an-older-token");
    registerRaw(mine, "qits_tok_raw-stale").statusCode(403);
    // A live token of another kind entirely — a runner's socket token, say — for this runner.
    stageLiveToken("qits_tok_raw-kind", IdpCommissioner.RUNNER_KIND, mine.toString(), SUBJECT);
    registerRaw(mine, "qits_tok_raw-kind").statusCode(403);

    assertEquals(List.of(), idp.posted);
    assertNull(row(mine).clientId);
  }

  @Test
  void aRawTokenQitsIdpDoesNotCallLiveIs401() {
    UUID id = declaredRunner("raw-deleted");
    // Nothing staged: qits-idp's 404, which is what a deleted token gets.
    registerRaw(id, "qits_tok_raw-deleted").statusCode(401);
    assertEquals(List.of("qits_tok_raw-deleted"), idp.introspected);
    assertEquals(List.of(), idp.posted);
  }

  @Test
  void aRawTokenOpensNoOtherRoute() {
    UUID id = declaredRunner("raw-confined");
    stageLiveToken(
        "qits_tok_raw-confined", IdpCommissioner.RUNNER_REGISTRATION_KIND, id.toString(), SUBJECT);
    String bearer = "Bearer qits_tok_raw-confined";

    given().header("Authorization", bearer).when().get(RUNNERS).then().statusCode(401);
    given().header("Authorization", bearer).when().get(RUNNERS + "/" + id).then().statusCode(401);
    given()
        .header("Authorization", bearer)
        .when()
        .post(RUNNERS + "/" + id + "/registration-token")
        .then()
        .statusCode(401);
    given().header("Authorization", bearer).when().get("/ci/api/runs/active").then().statusCode(401);
    given().header("Authorization", bearer).when().get("/ci/api/runs/queue").then().statusCode(401);
    // Not even the register door by another verb, and no other route ever asked qits-idp.
    given().header("Authorization", bearer).when().get(RUNNERS + "/" + id + "/register").then()
        .statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401),
            org.hamcrest.Matchers.is(405)));
    assertEquals(List.of(), idp.introspected);
  }

  @Test
  @TestSecurity(user = SUBJECT, roles = {REGISTRATION})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = SUBJECT)})
  void theRegistrationRoleOpensTheRegisterDoorAndNothingElse() {
    UUID id = declaredRunner("confined");

    given().when().get(RUNNERS).then().statusCode(403);
    given().when().get(RUNNERS + "/" + id).then().statusCode(403);
    given().when().post(RUNNERS + "/" + id + "/registration-token").then().statusCode(403);
    given().when().delete(RUNNERS + "/" + id).then().statusCode(403);
    given().when().get("/ci/api/runs/active").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void anOperatorCannotRegisterARunner() {
    UUID id = declaredRunner("operated");

    register(id, "{\"capabilities\":{}}").statusCode(403);

    assertEquals(List.of(), idp.posted);
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRunCarriesItsRunnersNameOnTheReadSurface() {
    UUID id = UUID.fromString(create("named-host").getString("runner.id"));
    String run = insertRun(id, CiRunStatus.SUCCESS);

    given()
        .when()
        .get("/ci/api/runs/" + run)
        .then()
        .statusCode(200)
        .body("runnerId", equalTo(id.toString()))
        .body("runnerName", equalTo("named-host"));
    given()
        .when()
        .get("/ci/api/runs?repositoryId=" + REPO)
        .then()
        .statusCode(200)
        .body("runs[0].runnerName", equalTo("named-host"))
        .body("runs[0].runnerId", not(nullValue()));
  }

  private String insertRun(UUID runnerId, CiRunStatus status) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = REPO;
              run.branch = "main";
              run.commitSha = "d".repeat(40);
              run.status = status;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-runner.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.createdAt = Instant.now();
              run.startedAt = Instant.now();
              if (status != CiRunStatus.RUNNING) {
                run.finishedAt = Instant.now();
              }
              run.runnerId = runnerId;
              runs.persist(run);
            });
    return id;
  }
}
