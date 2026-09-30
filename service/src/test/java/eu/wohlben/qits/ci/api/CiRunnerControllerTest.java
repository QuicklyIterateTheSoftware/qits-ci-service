package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.dto.CiRunnerDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.StubIdp;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import eu.wohlben.qits.ci.runnerhost.RunnerAddresses;
import eu.wohlben.qits.ci.testdb.HermeticConfigSource;
import eu.wohlben.qits.ci.runnerhost.RunnerAddressesFixture;
import eu.wohlben.qits.ci.runnerhost.CiRunnerPins;
import eu.wohlben.qits.ci.runnerhost.CiRunnerSocket;
import eu.wohlben.qits.ci.runnerhost.FakeCiRunner;
import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Retire;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

  private static final String SYSTEM = "qits:system";

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
  void creatingARunnerCommissionsItsRegistrationTokenAndAnswersItOnceInsideTheInstallScript() {
    JsonPath created = create("build-host-1");

    // The runner's fields flat at the top, as the SPA reads them, beside the script.
    UUID id = UUID.fromString(created.getString("id"));
    assertEquals("build-host-1", created.getString("name"));
    assertEquals(2, created.getInt("slots"));
    assertEquals("EDGE", created.getString("plane"));
    assertFalse(created.getBoolean("registered"));
    assertFalse(created.getBoolean("connected"));
    assertEquals(0, created.getInt("heldRuns"));
    assertNotNull(created.getString("createdAt"));
    assertFalse(created.getMap("").containsKey("runner"), "the runner is not nested");
    // The token is in the install line — as the fetch's bearer and as the script's value — and in
    // no field of its own.
    assertFalse(created.getMap("").containsKey("registrationToken"));
    // The suite's own public domain (testdb/HermeticConfigSource): the line names the edge's name
    // for qits-ci, never a qits-net alias.
    String base = "https://ci.qits." + HermeticConfigSource.DOMAIN;
    assertEquals(
        "curl -fsSL -H 'Authorization: Bearer qits_tok_stub-1' "
            + base
            + "/ci/api/runners/install.sh | sudo env QITS_CI_RUNNER_URL='"
            + base
            + "' QITS_CI_RUNNER_ID='"
            + id
            + "' QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_stub-1' QITS_CI_RUNNER_SLOTS='2' sh",
        created.getString("installScript"));

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
    String id = create("tunable").getString("id");

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
  void aStepMemoryLimitIsSetAtCreateChangedAndClearedByPatchAndListed() {
    // Absent at create is the platform default, and the read says null rather than a number.
    String plain = create("default-memory").getString("id");
    given().when().get(RUNNERS + "/" + plain).then().statusCode(200)
        .body("$", hasKey("stepMemoryLimit"))
        .body("stepMemoryLimit", nullValue());

    // Set at create: on the 201 (the CiRunnerCreated shape) and on the row.
    String id =
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"name\":\"big-memory\",\"slots\":1,\"stepMemoryLimit\":\"6g\"}")
            .when()
            .post(RUNNERS)
            .then()
            .statusCode(201)
            .body("stepMemoryLimit", equalTo("6g"))
            .extract()
            .jsonPath()
            .getString("id");
    assertEquals("6g", row(UUID.fromString(id)).stepMemoryLimit);

    // The listing carries it for every runner.
    JsonPath listed = given().when().get(RUNNERS).then().statusCode(200).extract().jsonPath();
    assertEquals("6g", listed.getString("runners.find { it.name == 'big-memory' }.stepMemoryLimit"));
    assertNull(listed.getString("runners.find { it.name == 'default-memory' }.stepMemoryLimit"));

    // PATCH changes it, leaves it when absent, and a blank clears it back to the default.
    given().contentType(MediaType.APPLICATION_JSON).body("{\"stepMemoryLimit\":\"8192m\"}")
        .when().patch(RUNNERS + "/" + id).then().statusCode(200)
        .body("stepMemoryLimit", equalTo("8192m"));
    given().contentType(MediaType.APPLICATION_JSON).body("{\"slots\":3}")
        .when().patch(RUNNERS + "/" + id).then().statusCode(200)
        .body("stepMemoryLimit", equalTo("8192m"));
    given().contentType(MediaType.APPLICATION_JSON).body("{\"stepMemoryLimit\":\"\"}")
        .when().patch(RUNNERS + "/" + id).then().statusCode(200)
        .body("stepMemoryLimit", nullValue());
    assertNull(row(UUID.fromString(id)).stepMemoryLimit);

    // A value the runner could not apply is a 400 at both doors, and a create refused over it mints
    // nothing at qits-idp.
    given().contentType(MediaType.APPLICATION_JSON).body("{\"stepMemoryLimit\":\"6 gigs\"}")
        .when().patch(RUNNERS + "/" + id).then().statusCode(400);
    given().contentType(MediaType.APPLICATION_JSON).body("{\"stepMemoryLimit\":\"5m\"}")
        .when().patch(RUNNERS + "/" + id).then().statusCode(400);
    int minted = idp.postedTokens.size();
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"bad-memory\",\"slots\":1,\"stepMemoryLimit\":\"0\"}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(400);
    assertEquals(minted, idp.postedTokens.size(), "refused before a registration token");
    assertNull(row(UUID.fromString(id)).stepMemoryLimit);
  }

  @Test
  @TestSecurity(user = "agent", roles = {AGENT})
  void anAgentReadsTheStepMemoryLimitButCannotChangeIt() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRunner runner = new CiRunner();
              runner.id = id;
              runner.name = "agent-read-memory";
              runner.slots = 1;
              runner.plane = eu.wohlben.qits.ci.entity.CiRunnerPlane.EDGE;
              runner.stepMemoryLimit = "6g";
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
            });
    given().when().get(RUNNERS + "/" + id).then().statusCode(200)
        .body("stepMemoryLimit", equalTo("6g"));
    given().contentType(MediaType.APPLICATION_JSON).body("{\"stepMemoryLimit\":\"64g\"}")
        .when().patch(RUNNERS + "/" + id).then().statusCode(403);
    assertEquals("6g", row(id).stepMemoryLimit);
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRotationAnswersANewInstallScriptAndGivesTheOldTokenBack() {
    String id = create("rotating").getString("id");

    JsonPath rotated =
        given()
            .when()
            .post(RUNNERS + "/" + id + "/registration-token")
            .then()
            .statusCode(200)
            .body("name", equalTo("rotating"))
            .body("id", equalTo(id))
            .body("$", not(hasKey("registrationToken")))
            .body("$", not(hasKey("runner")))
            .extract()
            .jsonPath();
    String line = rotated.getString("installScript");
    assertTrue(line.contains("QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_stub-2' "), line);
    assertTrue(line.contains("Authorization: Bearer qits_tok_stub-2'"), line);
    assertFalse(line.contains("qits_tok_stub-1"), line);

    assertEquals(List.of("token-1"), idp.deletedTokens);
    assertEquals("token-2", row(UUID.fromString(id)).registrationTokenId);
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRunnerHoldingARunningRunIs409AndItsCredentialsGoBackWhenItIsDeleted() {
    UUID id = UUID.fromString(create("busy").getString("id"));
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

  @TestHTTPResource(RunnerAddresses.SOCKET_PATH)
  URI runnerSocket;

  @Inject CiRunnerPins pins;

  /**
   * The defect this closes (qits-440): a runner deleted while it was connected kept its container
   * running for good. Now its connection is sent {@code Retire} of kind {@code DELETED} — before
   * qits-idp is asked to revoke its client, which {@link StubIdp#onDecommission} proves by looking
   * for the frame at the moment the revocation arrives — and closed {@code RUNNER_DELETED}.
   */
  @Test
  @TestSecurity(user = "operator", roles = {ADMIN, CiRunnerSocket.RUNNER_ROLE})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = "sub", value = "dyn-connected")
      })
  void deletingAConnectedRunnerRetiresItAsDeletedBeforeItsClientIsRevoked() throws Exception {
    UUID id = UUID.fromString(create("connected").getString("id"));
    QuarkusTransaction.requiringNew().run(() -> runnerRows.findById(id).clientId = "dyn-connected");
    Duration soon = Duration.ofSeconds(10);
    try (FakeCiRunner runner = FakeCiRunner.dial(runnerSocket)) {
      runner.send(
          new Hello(
              pins.version(),
              CiRunnerProtocol.CAPABILITY_VERSION,
              1,
              new Capabilities(true, "amd64", "linux", Map.of())));
      assertNotNull(runner.next(Ack.class, soon));
      idp.onDecommission = () -> runner.holds(Retire.class);

      given().when().delete(RUNNERS + "/" + id).then().statusCode(204);

      Retire retire = runner.next(Retire.class, soon);
      assertNotNull(retire, "a connected runner is told it was deleted");
      assertEquals(Retire.Kind.DELETED, retire.kind());
      assertEquals((Short) (short) 1008, runner.awaitClose(soon));
      assertEquals(CiRunnerSocket.RUNNER_DELETED, runner.closeReason());
    }
    assertEquals(List.of("dyn-connected"), idp.deleted);
    assertEquals(List.of(true), idp.decommissionChecks, "the Retire left before the revocation");
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void theRunnersAdvertisedIdRangeIsListedWithItsCapabilities() {
    UUID id = UUID.fromString(create("narrow-ids").getString("id"));
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                runnerRows.findById(id).capabilities =
                    "{\"docker\":true,\"arch\":\"amd64\",\"idRange\":65536}");

    given()
        .when()
        .get(RUNNERS)
        .then()
        .statusCode(200)
        .body("runners[0].capabilities.idRange", equalTo(65536));
    given()
        .when()
        .get(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("capabilities.idRange", equalTo(65536));
  }

  @Test
  @TestSecurity(user = "watcher", roles = {AGENT})
  void anAgentReadsRunnersAndWritesNone() {
    // A real row, so a 403 below is the role refusing and never a 404 for a runner that is not there.
    UUID id = declaredRunner("agent-target");
    given().when().get(RUNNERS).then().statusCode(200).body("runners", hasSize(1));
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"nope\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(403);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"slots\":3}")
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(403);
    given().when().post(RUNNERS + "/" + id + "/registration-token").then().statusCode(403);
    given().when().delete(RUNNERS + "/" + id).then().statusCode(403);
    given().when().post(RUNNERS + "/" + id + "/greenlight").then().statusCode(403);
    given().when().post(RUNNERS + "/" + id + "/healthcheck").then().statusCode(403);
    assertNotNull(row(id), "every write was refused, so the runner is still there");
    assertEquals(1, row(id).slots);
    assertEquals(List.of(), idp.postedTokens, "and nothing was minted for it");
  }

  /**
   * qits-521: the bootstrap's own service client is the machine caller of the lifecycle writes — it
   * creates the {@code localhost} runner on a cold start, reads the registration token out of the
   * 201's install line and hands it to the deployer, with nobody at a keyboard.
   */
  @Test
  @TestSecurity(user = "dev-qits-bootstrap", roles = {SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = "sub", value = "dev-qits-bootstrap")
      })
  void aSystemBearerCreatesPatchesRotatesAndDeletesARunner() {
    JsonPath created = create("localhost");
    UUID id = UUID.fromString(created.getString("id"));
    assertTrue(
        created.getString("installScript").contains("QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_stub-1'"),
        "the registration token is readable from the 201 body");

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"slots\":3}")
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("slots", equalTo(3));
    given().when().post(RUNNERS + "/" + id + "/registration-token").then().statusCode(200);
    // Two runners, so deleting localhost is not refused as the last one (qits-503).
    create("elsewhere");
    given().when().delete(RUNNERS + "/" + id).then().statusCode(204);
    assertNull(row(id));
  }

  /** The machine arm asks MachineAuth: a system bearer addressed elsewhere writes nothing. */
  @Test
  @TestSecurity(user = "dev-qits-bootstrap", roles = {SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = "qits-elsewhere"),
        @Claim(key = "sub", value = "dev-qits-bootstrap")
      })
  void aSystemBearerAddressedElsewhereIs403AndMintsNothing() {
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"misaddressed\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(403);
    assertEquals(List.of(), idp.postedTokens);
  }

  // --- localhost (qits-503) ----------------------------------------------------------------------

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void localhostNamesOneRunnerAtMostAndIsListedFirst() {
    create("alpha");
    UUID localhost = UUID.fromString(create("localhost").getString("id"));
    create("zulu");

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"localhost\",\"slots\":1}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(409);
    assertEquals(3, idp.postedTokens.size(), "the second localhost commissioned nothing");

    given()
        .when()
        .get(RUNNERS)
        .then()
        .statusCode(200)
        .body("runners.name", equalTo(List.of("localhost", "alpha", "zulu")))
        .body("runners[0].id", equalTo(localhost.toString()));
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void deletingLocalhostWhileItIsTheOnlyRunnerIs409LastRunnerAndGivesNothingBack() {
    UUID localhost = UUID.fromString(create("localhost").getString("id"));

    given()
        .when()
        .delete(RUNNERS + "/" + localhost)
        .then()
        .statusCode(409)
        .body("code", equalTo("LAST_RUNNER"));
    assertNotNull(row(localhost), "the row stays");
    assertEquals(List.of(), idp.deletedTokens, "and its token is not given back");

    // Once another runner exists it is an ordinary runner to delete — and the other one, deleted
    // while localhost remains, never was the last.
    UUID other = UUID.fromString(create("build-host").getString("id"));
    given().when().delete(RUNNERS + "/" + localhost).then().statusCode(204);
    assertNull(row(localhost));
    given().when().delete(RUNNERS + "/" + other).then().statusCode(204);
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void theLastRunnerThatIsNotLocalhostIsDeletedAsEver() {
    UUID only = UUID.fromString(create("build-host").getString("id"));
    given().when().delete(RUNNERS + "/" + only).then().statusCode(204);
  }

  @Test
  void theCreatedShapeIsTheRunnersFieldsFlatPlusTheScript() {
    List<String> runner =
        Arrays.stream(CiRunnerDto.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    List<String> created =
        Arrays.stream(CiRunnerController.CiRunnerCreated.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    List<String> expected = new ArrayList<>(runner);
    expected.add("installScript");
    assertEquals(expected, created);
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
              runner.plane = eu.wohlben.qits.ci.entity.CiRunnerPlane.EDGE;
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
    // The edge's names, composed from the suite's domain: where the runner mints and where it dials.
    assertEquals(
        "https://idp.qits." + HermeticConfigSource.DOMAIN + "/idp/token",
        answer.getString("tokenUrl"));
    assertEquals(
        "wss://ci.qits." + HermeticConfigSource.DOMAIN + "/ci/runners/socket",
        answer.getString("socketUrl"));
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
  void theRegisterAnswerNamesTheLiveEstatesEdge() {
    // The live estate's domain: a runner outside the swarm is told the edge's names, never an alias.
    QuarkusMock.installMockForType(
        RunnerAddressesFixture.withDomain("wohlben.eu"), RunnerAddresses.class);
    UUID id = declaredRunner("remote");

    JsonPath answer =
        register(id, "{\"capabilities\":{\"docker\":true}}").statusCode(200).extract().jsonPath();

    assertEquals("https://idp.qits.wohlben.eu/idp/token", answer.getString("tokenUrl"));
    assertEquals("wss://ci.qits.wohlben.eu/ci/runners/socket", answer.getString("socketUrl"));
    assertEquals("qits-platform", answer.getString("audience"));
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void theInstallLineNamesTheLiveEstatesEdge() {
    QuarkusMock.installMockForType(
        RunnerAddressesFixture.withDomain("wohlben.eu"), RunnerAddresses.class);

    JsonPath created = create("remote-host");

    assertEquals(
        "curl -fsSL -H 'Authorization: Bearer qits_tok_stub-1'"
            + " https://ci.qits.wohlben.eu/ci/api/runners/install.sh"
            + " | sudo env QITS_CI_RUNNER_URL='https://ci.qits.wohlben.eu' QITS_CI_RUNNER_ID='"
            + created.getString("id")
            + "' QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_stub-1' QITS_CI_RUNNER_SLOTS='2' sh",
        created.getString("installScript"));
    String script =
        given().when().get(RUNNERS + "/install.sh").then().statusCode(200).extract().asString();
    assertTrue(
        script.contains(
            "image='registry.qits.wohlben.eu/qits/qits-ci-runner:" + CiRunnerBinary.VERSION + "'\n"),
        script);
  }

  // --- no public domain: nothing is handed out (qits-515) ------------------------------------------

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aQitsCiThatKnowsNoPublicDomainDeclaresNoRunnerAndRendersNoScript() {
    // There is no internal alias to fall back to: a runner is outside the swarm and could resolve
    // none. The refusal names the key, and it comes before anything is minted.
    QuarkusMock.installMockForType(RunnerAddressesFixture.withDomain(null), RunnerAddresses.class);

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"nowhere-to-go\"}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(503)
        .body("message", org.hamcrest.Matchers.containsString("QITS_DOMAIN"))
        .body("message", not(org.hamcrest.Matchers.containsString("-qits-")));
    given()
        .when()
        .get(RUNNERS + "/install.sh")
        .then()
        .statusCode(503)
        .body(org.hamcrest.Matchers.containsString("QITS_DOMAIN"));

    assertTrue(idp.postedTokens.isEmpty(), "a refused create commissions no registration token");
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> runnerRows.count()) == 0L, "and writes no row");
  }

  @Test
  @TestSecurity(user = SUBJECT, roles = {REGISTRATION})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = SUBJECT)})
  void theRegisterDoorMintsNoClientItCouldNotTellTheRunnerWhereToUse() {
    QuarkusMock.installMockForType(RunnerAddressesFixture.withDomain(null), RunnerAddresses.class);
    UUID id = declaredRunner("unaddressable");

    register(id, "{\"capabilities\":{}}")
        .statusCode(503)
        .body("message", org.hamcrest.Matchers.containsString("QITS_DOMAIN"));

    assertEquals(List.of(), idp.posted, "no client was commissioned");
    assertNull(row(id).clientId);
  }

  // --- the plane: EDGE and nothing else (qits-515) -------------------------------------------------

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void aRunnerIsCreatedOnTheEdgePlaneWhetherItSaysSoOrNot() {
    JsonPath unsaid = create("remote-by-default");
    JsonPath said =
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"name\":\"remote-by-name\",\"plane\":\"EDGE\"}")
            .when()
            .post(RUNNERS)
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();

    for (JsonPath created : List.of(unsaid, said)) {
      assertEquals("EDGE", created.getString("plane"));
      assertEquals(
          "EDGE", row(UUID.fromString(created.getString("id"))).plane.name(), "and the row says so");
    }
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void creatingARunnerOnTheInternalPlaneOrAnUnknownOneIs400AndMintsNothing() {
    // INTERNAL — a runner on qits-net — is deleted; the API refuses the word, and any other it
    // does not know, by name, before a token is commissioned.
    for (String plane : List.of("INTERNAL", "internal", "edge", "SIDEWAYS", "")) {
      given()
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"name\":\"on-the-swarm\",\"plane\":\"" + plane + "\"}")
          .when()
          .post(RUNNERS)
          .then()
          .statusCode(400)
          .body("code", equalTo("UNKNOWN_PLANE"))
          .body("message", org.hamcrest.Matchers.startsWith("UNKNOWN_PLANE: "));
    }
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"on-the-swarm\",\"plane\":\"INTERNAL\"}")
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("INTERNAL"));

    assertTrue(idp.postedTokens.isEmpty(), "a refused plane commissions no registration token");
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> runnerRows.count()) == 0L, "and writes no row");
  }

  @Test
  @TestSecurity(user = "operator", roles = {ADMIN})
  void patchingARunnerToTheInternalPlaneOrAnUnknownOneIs400AndChangesNothing() {
    String id = create("mover").getString("id");

    for (String plane : List.of("INTERNAL", "SIDEWAYS")) {
      given()
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"slots\":5,\"plane\":\"" + plane + "\"}")
          .when()
          .patch(RUNNERS + "/" + id)
          .then()
          .statusCode(400)
          .body("code", equalTo("UNKNOWN_PLANE"));
    }
    assertEquals("EDGE", row(UUID.fromString(id)).plane.name());
    assertEquals(2, row(UUID.fromString(id)).slots, "the refused patch moved nothing else either");

    // EDGE, and no plane at all — what the SPA sends — are both accepted and change no plane.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"plane\":\"EDGE\"}")
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("plane", equalTo("EDGE"))
        .body("slots", equalTo(2));
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"slots\":3}")
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("plane", equalTo("EDGE"))
        .body("slots", equalTo(3));
  }

  // --- the generic install script -----------------------------------------------------------------

  @Test
  @TestSecurity(user = SUBJECT, roles = {REGISTRATION})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = SUBJECT)})
  void theRegistrationTokenReadsTheGenericInstallScript() {
    String script =
        given()
            .when()
            .get(RUNNERS + "/install.sh")
            .then()
            .statusCode(200)
            .contentType(org.hamcrest.Matchers.startsWith(MediaType.TEXT_PLAIN))
            .extract()
            .asString();

    assertTrue(script.startsWith("#!/bin/sh\n"), script);
    assertFalse(script.contains("{{"), script);
    assertFalse(script.contains("qits_tok_"), script);
    // The registry's public name under the suite's domain — never a qits-net alias.
    String registry = "registry.qits." + HermeticConfigSource.DOMAIN;
    assertTrue(
        script.contains(
            "image='" + registry + "/qits/qits-ci-runner:" + CiRunnerBinary.VERSION + "'\n"),
        script);
  }

  @Test
  void anAnonymousReaderGetsNoInstallScript() {
    given().when().get(RUNNERS + "/install.sh").then().statusCode(401);
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

  // --- the raw token: this service no longer reads one (qits-515) ---------------------------------

  /**
   * The mechanism that let a runner on qits-net present a raw {@code qits_tok_} to qits-ci itself —
   * introspected here, at qits-idp — is deleted. A runner comes through the edge, which does that
   * and forwards a JWT (the cases above, whose identity is that JWT's). A raw value presented
   * directly is a bearer quarkus-oidc cannot verify: 401 on every route, the register door and the
   * install script included, and qits-idp is asked nothing.
   */
  @Test
  void aRawRegistrationTokenOpensNoRouteAtAllAndQitsIdpIsNeverAsked() {
    UUID id = declaredRunner("raw-confined");
    String bearer = "Bearer qits_tok_raw-registration";

    given()
        .header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"capabilities\":{\"docker\":true}}")
        .when()
        .post(RUNNERS + "/" + id + "/register")
        .then()
        .statusCode(401);
    given().header("Authorization", bearer).when().get(RUNNERS + "/install.sh").then().statusCode(401);
    given().header("Authorization", bearer).when().get(RUNNERS).then().statusCode(401);
    given().header("Authorization", bearer).when().get(RUNNERS + "/" + id).then().statusCode(401);
    given()
        .header("Authorization", bearer)
        .when()
        .post(RUNNERS + "/" + id + "/registration-token")
        .then()
        .statusCode(401);
    given().header("Authorization", bearer).when().get("/ci/api/runs/active").then().statusCode(401);

    assertEquals(List.of(), idp.posted, "no client was commissioned");
    assertEquals(List.of(), idp.postedTokens, "and the tokens door — introspection included — was not asked");
    assertEquals(List.of(), idp.authorizations, "qits-idp was not called at all");
    assertNull(row(id).clientId);
  }

  @Test
  @TestSecurity(user = SUBJECT, roles = {REGISTRATION})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = SUBJECT)})
  void theRegistrationRoleOpensTheRegisterDoorAndTheInstallScriptAndNothingElse() {
    UUID id = declaredRunner("confined");

    given().when().get(RUNNERS + "/install.sh").then().statusCode(200);

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
    UUID id = UUID.fromString(create("named-host").getString("id"));
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
