package eu.wohlben.qits.ci.runnerhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.api.MachineGuardTest;
import eu.wohlben.qits.ci.control.CiRunnerPresence;
import eu.wohlben.qits.ci.control.CiRunnerSignals;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.control.RecordingRunnerAnnouncer;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPurpose;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Backlog;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.HealthCheck;
import eu.wohlben.qits.cirunner.protocol.HealthChecked;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Quarantined;
import eu.wohlben.qits.cirunner.protocol.Reinstated;
import eu.wohlben.qits.ci.testdb.HermeticConfigSource;
import eu.wohlben.qits.runner.protocol.health.CheckResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a connected runner is TOLD about its standing (qits-466), over a real WebSocket from a
 * scripted {@link FakeCiRunner} — the {@code Ack} it holds its slots by, the {@code Quarantined} and
 * {@code Reinstated} frames, the {@code registryMirrors} a runner's builder applies — and the
 * operator's two doors that move it.
 *
 * <p><b>Every change of what a connected runner may hold is a fresh {@code Ack} and a {@code
 * Backlog}</b>, because a runner learns its slots from an {@code Ack} alone: before this, a runner
 * connected with 0 slots and raised to 1 by a {@code PATCH} sat idle beside a queued run (measured
 * live). The rules themselves — the streak, the health check — are {@code CiRunnerHealthTest}'s in
 * the {@code ci} module; this is the wire.
 *
 * <p>{@link MachineGuardTest.GateOn}, {@code CiRunnerSocketTest}'s profile and its reason: the socket
 * reads its runner off a validated token, and one profile is one Quarkus start.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class RunnerQuarantineSocketTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  private static final String RUNNER_ROLE = CiRunnerSocket.RUNNER_ROLE;

  private static final String CLIENT = "ci-runner-quarantine-under-test";

  private static final String AUDIENCE = "qits-platform";

  private static final String RUNNERS = "/ci/api/runners";

  @TestHTTPResource(RunnerAddresses.SOCKET_PATH)
  URI endpoint;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunRepository runs;

  @Inject CiRunnerPresence presence;

  @Inject CiRunners runners;

  @Inject CiRunnerPins pins;

  @Inject RecordingRunnerAnnouncer announcer;

  @Inject CiRunnerNodeHealth nodeHealth;

  @Inject CiRunnerRegistry registry;

  /** The seam CiRunnerHealth speaks through — the registry — called as CiRunnerHealth calls it. */
  @Inject CiRunnerSignals signals;

  private UUID runnerId;

  @BeforeEach
  void aRegisteredRunner() {
    runnerId = UUID.randomUUID();
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  @AfterEach
  void forgetIt() throws Exception {
    Instant deadline = Instant.now().plusSeconds(10);
    while (presence.connected(runnerId) && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runs.delete("targetRunnerId", runnerId);
              runnerRows.deleteAll();
            });
  }

  // --- the Ack ------------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aQuarantinedRunnerIsAckedNoSlotsAndToldWhy() throws Exception {
    declare(3, CiRunnerPlane.EDGE, Instant.parse("2026-09-28T10:00:00Z"), "for the test");

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());

      Ack ack = runner.next(Ack.class, SOON);
      assertEquals(0, ack.slots(), "out of service, whatever the row's 3 say");
      Quarantined quarantined = runner.next(Quarantined.class, SOON);
      assertNotNull(quarantined, "right after its Ack, so the person at the machine reads why");
      assertEquals("for the test", quarantined.reason());
      assertEquals("2026-09-28T10:00:00Z", quarantined.since());
      assertNotNull(runner.next(Backlog.class, SOON));
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {RUNNER_ROLE, "qits:admin"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aPatchOfAConnectedRunnersSlotsReAcksItAndPushesTheBacklog() throws Exception {
    declare(0, CiRunnerPlane.EDGE, null, null);

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());
      assertEquals(0, runner.next(Ack.class, SOON).slots());
      assertNotNull(runner.next(Backlog.class, SOON));

      given()
          .contentType("application/json")
          .body("{\"slots\":1}")
          .patch(RUNNERS + "/" + runnerId)
          .then()
          .statusCode(200);

      Ack raised = runner.next(Ack.class, SOON);
      assertNotNull(raised, "the new number reaches the connected runner");
      assertEquals(1, raised.slots());
      assertNotNull(
          runner.next(Backlog.class, SOON),
          "and a Backlog after it, so a runner answered Nothing earlier asks again at once");
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {RUNNER_ROLE, "qits:admin"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aQuarantineAcksZeroAndAGreenlightAcksTheConfiguredSlotsAgain() throws Exception {
    declare(2, CiRunnerPlane.EDGE, null, null);

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());
      assertEquals(2, runner.next(Ack.class, SOON).slots());
      assertNotNull(runner.next(Backlog.class, SOON));

      // The streak the ci module's suite drives through runSteps, reached here by its own write and
      // the signal CiRunnerHealth sends when that write quarantines.
      CiRunner out = runners.quarantine(runnerId, "3 consecutive runner failures (test)");
      signals.quarantined(out.id, out.quarantineReason, out.quarantinedAt);

      Quarantined quarantined = runner.next(Quarantined.class, SOON);
      assertEquals("3 consecutive runner failures (test)", quarantined.reason());
      assertEquals(0, runner.next(Ack.class, SOON).slots());
      assertNotNull(runner.next(Backlog.class, SOON));

      io.restassured.path.json.JsonPath read =
          given().get(RUNNERS + "/" + runnerId).then().statusCode(200).extract().jsonPath();
      assertTrue(read.getBoolean("quarantined"));
      assertEquals("3 consecutive runner failures (test)", read.getString("quarantineReason"));
      assertNotNull(read.getString("quarantinedAt"));
      assertEquals(2, read.getInt("slots"), "the operator's number stays on the row");

      io.restassured.path.json.JsonPath greenlit =
          given()
              .post(RUNNERS + "/" + runnerId + "/greenlight")
              .then()
              .statusCode(200)
              .extract()
              .jsonPath();
      assertFalse(greenlit.getBoolean("quarantined"));
      assertNull(greenlit.getString("quarantineReason"));

      Reinstated reinstated = runner.next(Reinstated.class, SOON);
      assertEquals("admin", reinstated.by());
      assertEquals(2, runner.next(Ack.class, SOON).slots(), "its configured slots, back");
      assertNotNull(runner.next(Backlog.class, SOON));
    }
    assertEquals(
        List.of("RunnerReinstated"),
        announcer.eventsOf(runnerId.toString()).stream()
            .filter(e -> e.equals("RunnerReinstated"))
            .toList(),
        "announced once");
  }

  // --- the registry mirrors -----------------------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunnersAckCarriesTheRegistryMirrorsItsBuilderApplies() throws Exception {
    QuarkusMock.installMockForType(
        RunnerAddressesFixture.withDomain("example.org"), RunnerAddresses.class);
    declare(1, CiRunnerPlane.EDGE, null, null);

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());
      Map<String, String> mirrors = runner.next(Ack.class, SOON).registryMirrors();

      assertNotNull(mirrors, "a runner is told what its builder must rewrite");
      // The machine spellings the estate's Dockerfiles commit — registry.<env>.localhost:8080 and
      // its mirror twin, with the tier derived — and the older host-published ports.
      Config config = ConfigProvider.getConfig();
      List<String> registryHosts =
          config.getValues("qits.ci.runner.registry-mirrors.registry-hosts", String.class);
      List<String> mirrorHosts =
          config.getValues("qits.ci.runner.registry-mirrors.mirror-hosts", String.class);
      assertTrue(registryHosts.stream().anyMatch(h -> h.matches("registry\\.[a-z0-9-]+\\.localhost:8080")));
      assertTrue(mirrorHosts.stream().anyMatch(h -> h.matches("mirror\\.[a-z0-9-]+\\.localhost:8080")));
      Map<String, String> expected = new LinkedHashMap<>();
      registryHosts.forEach(host -> expected.put(host, "registry.qits.example.org"));
      mirrorHosts.forEach(host -> expected.put(host, "mirror.qits.example.org"));
      expected.put("localhost:8081", "registry.qits.example.org");
      expected.put("localhost:8082", "mirror.qits.example.org");
      // Every spelling qits-ci itself reads for its registry is mapped too.
      expected.put(
          config.getValue("qits.artifacts.registry-host", String.class),
          "registry.qits.example.org");
      // And the two stores' qits-net aliases in this environment, which a committed file may name.
      String environment = config.getOptionalValue("QITS_ENVIRONMENT", String.class).orElse("dev");
      expected.put(environment + "-qits-artifacts:8080", "registry.qits.example.org");
      expected.put(environment + "-qits-mirror:8080", "mirror.qits.example.org");
      // The bare upstreams, through the public mirror's namespaces.
      expected.put("quay.io", "mirror.qits.example.org/quay");
      expected.put("registry.access.redhat.com", "mirror.qits.example.org/redhat");
      expected.put("docker.io", "mirror.qits.example.org/hub");
      for (Map.Entry<String, String> entry : expected.entrySet()) {
        assertEquals(entry.getValue(), mirrors.get(entry.getKey()), entry.getKey() + " in " + mirrors);
      }
      assertFalse(
          mirrors.containsKey("registry.qits.example.org"), "the public name maps to nothing");
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunnersAckCarriesNoneWhenThisQitsCiKnowsNoPublicDomain() throws Exception {
    // Nothing to map the spellings to: null, "not sent" — and such a runner's steps are refused
    // EDGE_PLANE_UNCONFIGURED anyway.
    QuarkusMock.installMockForType(RunnerAddressesFixture.withDomain(null), RunnerAddresses.class);
    declare(1, CiRunnerPlane.EDGE, null, null);

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());
      assertNull(runner.next(Ack.class, SOON).registryMirrors(), "no public name to rewrite to");
    }
  }

  // --- the health-check door ----------------------------------------------------------------------

  @Test
  @TestSecurity(user = "admin", roles = "qits:admin")
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE)})
  void theHealthCheckDoorRefusesASecondAndSaysWhenItCannotAsk() {
    declare(1, CiRunnerPlane.EDGE, null, null);

    // This suite's catalogue holds no health-check repository: the question cannot be asked.
    given().post(RUNNERS + "/" + runnerId + "/healthcheck").then().statusCode(503);

    String pending = pendingCheck();
    given()
        .post(RUNNERS + "/" + runnerId + "/healthcheck")
        .then()
        .statusCode(409)
        .body("message", org.hamcrest.Matchers.containsString(pending));
    given().post(RUNNERS + "/" + UUID.randomUUID() + "/healthcheck").then().statusCode(404);
    given().post(RUNNERS + "/" + UUID.randomUUID() + "/greenlight").then().statusCode(404);

    // A health check is readable by its id, and nowhere else.
    given().get("/ci/api/runs/" + pending).then().statusCode(200);
    assertTrue(
        given()
            .get("/ci/api/runs/active")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("runs.id", String.class)
            .stream()
            .noneMatch(pending::equals));
  }

  /**
   * qits-896: an agent may ask for a health check — it probes and changes nothing a person set — and
   * still may not greenlight. The health check reaches its handler (503 here: this suite's catalogue
   * holds no health-check repository), which is all a role test can say.
   */
  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE)})
  void anAgentAsksForAHealthCheckButCannotGreenlight() {
    declare(1, CiRunnerPlane.EDGE, null, null);

    given().post(RUNNERS + "/" + runnerId + "/greenlight").then().statusCode(403);
    given().post(RUNNERS + "/" + runnerId + "/healthcheck").then().statusCode(503);
  }

  // --- the node health report (qits-896) ----------------------------------------------------------

  /**
   * The door asks a connected runner for its node report beside the pseudo-build: the runner is
   * sent {@code healthCheck{requestId, image}} — the health check's step image, resolved with its
   * registry and moved to the public name, as a step's launch would pull it — the 202 carries that
   * requestId, a second press while it is pending answers the same one and sends nothing more, and
   * the runner's answer is what {@code GET /runners/{id}/health} reads. A failing report changes
   * nothing about the runner's standing.
   */
  @Test
  @TestSecurity(user = "agent", roles = {RUNNER_ROLE, "qits:agent"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aConnectedRunnerIsAskedForItsNodeReportAndItsAnswerIsReadable() throws Exception {
    declare(1, CiRunnerPlane.EDGE, null, null);
    QueuedHealthChecks queued = QueuedHealthChecks.install();

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());
      assertEquals(1, runner.next(Ack.class, SOON).slots());
      assertNotNull(runner.next(Backlog.class, SOON));
      awaitGreeted();
      given().get(RUNNERS + "/" + runnerId + "/health").then().statusCode(204);

      io.restassured.path.json.JsonPath asked =
          given()
              .post(RUNNERS + "/" + runnerId + "/healthcheck")
              .then()
              .statusCode(202)
              .extract()
              .jsonPath();
      String requestId = asked.getString("requestId");
      assertNotNull(requestId, "the runner is connected, so it was asked");
      assertEquals(queued.queued().get(0), asked.getString("runId"), "the pseudo-build too");

      HealthCheck frame = runner.next(HealthCheck.class, SOON);
      assertNotNull(frame, "the runner is sent the frame");
      assertEquals(requestId, frame.requestId());
      assertEquals(nodeHealth.image(), frame.image());
      assertEquals(
          "registry.qits." + HermeticConfigSource.DOMAIN + "/qits/build-images/ci-base:latest",
          frame.image(),
          "the health check's image, with the registry's public name, as a launch pulls it");

      assertEquals(
          requestId,
          given()
              .post(RUNNERS + "/" + runnerId + "/healthcheck")
              .then()
              .statusCode(202)
              .extract()
              .jsonPath()
              .getString("requestId"),
          "one pending request is enough");
      assertNull(runner.next(HealthCheck.class, Duration.ofMillis(300)), "and nothing more is sent");

      runner.send(
          new HealthChecked(
              false,
              "stepImage: pull access denied",
              requestId,
              List.of(
                  new CheckResult("docker", true, "server 27.3.1", Map.of("serverVersion", "27.3.1")),
                  new CheckResult(
                      "stepImage", false, "pull access denied", Map.of("image", frame.image())))));

      io.restassured.path.json.JsonPath report = awaitReport();
      assertFalse(report.getBoolean("ok"));
      assertEquals("stepImage: pull access denied", report.getString("detail"));
      assertEquals(requestId, report.getString("requestId"));
      assertFalse(report.getBoolean("dataOmitted"));
      assertNotNull(report.getString("at"));
      assertEquals(List.of("docker", "stepImage"), report.getList("checks.name", String.class));
      assertEquals("27.3.1", report.getString("checks[0].data.serverVersion"));
      assertTrue(report.getBoolean("checks[0].ok"));
      assertFalse(report.getBoolean("checks[1].ok"));

      // A diagnosis, never a decision: the runner stays in service and is told nothing.
      assertNull(row().quarantinedAt, "a failing node report quarantines nothing");
      assertNull(runner.next(Quarantined.class, Duration.ofMillis(300)));
      assertNull(nodeHealth.pendingRequest(runnerId), "the answer settled it");
    }
  }

  /**
   * A request nobody answers within {@code qits.ci.runner.node-healthcheck.timeout} is stored as a
   * report saying {@code NO_ANSWER}, carrying its requestId and no checks — and an answer arriving
   * after that names a request that is no longer pending, so it is dropped.
   */
  @Test
  @TestSecurity(user = "agent", roles = {RUNNER_ROLE, "qits:agent"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anUnansweredRequestIsStoredAsNoAnswerAndALateAnswerIsDropped() throws Exception {
    declare(1, CiRunnerPlane.EDGE, null, null);

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());
      assertNotNull(runner.next(Ack.class, SOON));
      awaitGreeted();

      String requestId = nodeHealth.request(runnerId);
      assertNotNull(requestId);
      assertEquals(requestId, runner.next(HealthCheck.class, SOON).requestId());

      Instant sent = Instant.now();
      nodeHealth.expire(sent.plus(Duration.ofMinutes(4)));
      given().get(RUNNERS + "/" + runnerId + "/health").then().statusCode(204);
      assertEquals(requestId, nodeHealth.pendingRequest(runnerId), "inside the timeout: still waited for");

      nodeHealth.expire(sent.plus(Duration.ofMinutes(6)));
      io.restassured.path.json.JsonPath report =
          given().get(RUNNERS + "/" + runnerId + "/health").then().statusCode(200).extract().jsonPath();
      assertFalse(report.getBoolean("ok"));
      assertEquals(CiRunnerNodeHealth.NO_ANSWER, report.getString("detail"));
      assertEquals(requestId, report.getString("requestId"));
      assertEquals(List.of(), report.getList("checks"));
      assertNull(nodeHealth.pendingRequest(runnerId));

      runner.send(new HealthChecked(true, "all 7 checks passed", requestId, List.of()));
      Thread.sleep(300);
      assertEquals(
          CiRunnerNodeHealth.NO_ANSWER,
          given().get(RUNNERS + "/" + runnerId + "/health").then().statusCode(200).extract()
              .jsonPath().getString("detail"),
          "a request no longer pending is not answered by a late frame");
      assertNull(row().quarantinedAt, "and none of it quarantined the runner");
    }
  }

  /**
   * A passing node report reinstates nothing: the quarantine is lifted by the pseudo-build or an
   * operator, never by the node's own word about itself. An answer naming no request (a runner older
   * than the field) settles the runner's pending one.
   */
  @Test
  @TestSecurity(user = "agent", roles = {RUNNER_ROLE, "qits:agent"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aPassingNodeReportDoesNotReinstateAQuarantinedRunner() throws Exception {
    Instant since = Instant.parse("2026-10-09T09:00:00Z");
    declare(2, CiRunnerPlane.EDGE, since, "3 consecutive runner failures (test)");

    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello());
      assertEquals(0, runner.next(Ack.class, SOON).slots());
      awaitGreeted();

      String requestId = nodeHealth.request(runnerId);
      assertNotNull(runner.next(HealthCheck.class, SOON));
      runner.send(new HealthChecked(true, "all 7 checks passed", null, List.of()));

      io.restassured.path.json.JsonPath report = awaitReport();
      assertTrue(report.getBoolean("ok"));
      assertEquals(requestId, report.getString("requestId"), "the pending request it settled");

      CiRunner after = row();
      assertEquals(since, after.quarantinedAt, "still quarantined, since the same instant");
      assertEquals("3 consecutive runner failures (test)", after.quarantineReason);
      assertNull(runner.next(Reinstated.class, Duration.ofMillis(300)), "and not told otherwise");
    }
  }

  /** A runner with no session is asked nothing, and its door answers 202 with a null requestId. */
  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE)})
  void aRunnerThatIsNotConnectedIsQueuedAPseudoBuildAndAskedNothing() {
    declare(1, CiRunnerPlane.EDGE, null, null);
    QueuedHealthChecks.install();

    io.restassured.path.json.JsonPath asked =
        given()
            .post(RUNNERS + "/" + runnerId + "/healthcheck")
            .then()
            .statusCode(202)
            .extract()
            .jsonPath();
    assertNotNull(asked.getString("runId"));
    assertNull(asked.getString("requestId"), "nobody to ask");
    assertNull(nodeHealth.pendingRequest(runnerId));
    given().get(RUNNERS + "/" + runnerId + "/health").then().statusCode(204);
    given().get(RUNNERS + "/" + UUID.randomUUID() + "/health").then().statusCode(404);
    given().post(RUNNERS + "/" + UUID.randomUUID() + "/healthcheck").then().statusCode(404);
  }

  /**
   * Until the runner's session is greeted: the registry marks it so only after the {@code Hello}'s
   * answer has gone out, so a test that read the {@code Ack} can still be a moment early.
   */
  private void awaitGreeted() throws InterruptedException {
    Instant deadline = Instant.now().plus(SOON);
    while (registry.serving(runnerId) == null && Instant.now().isBefore(deadline)) {
      Thread.sleep(10);
    }
    assertNotNull(registry.serving(runnerId), "the runner was greeted");
  }

  /** The node report, once the socket's frame has been handled; it is handled off this thread. */
  private io.restassured.path.json.JsonPath awaitReport() throws InterruptedException {
    Instant deadline = Instant.now().plus(SOON);
    while (Instant.now().isBefore(deadline)) {
      io.restassured.response.Response read = given().get(RUNNERS + "/" + runnerId + "/health");
      if (read.statusCode() == 200) {
        return read.jsonPath();
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no node health report was recorded");
  }

  private CiRunner row() {
    return QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(runnerId));
  }

  /**
   * qits-628 follow-up: an ADMIN workspace's coding agent carries {@code qits:admin-agent} alongside
   * {@code qits:agent}, and for now it may press everything {@code qits:admin} may — including the
   * greenlight button, which {@code anAgentPressesNeitherButton} just proved {@code qits:agent} alone
   * cannot. {@code qits:agent} alone must still be refused.
   */
  @Test
  @TestSecurity(user = "admin-agent", roles = {"qits:admin-agent", "qits:agent"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE)})
  void anAdminAgentPressesGreenlightAndAgentAloneStillCannot() {
    declare(1, CiRunnerPlane.EDGE, Instant.now(), "admin-agent greenlight coverage");

    given().post(RUNNERS + "/" + runnerId + "/greenlight").then().statusCode(200);
  }

  // --- staging ------------------------------------------------------------------------------------

  private void declare(int slots, CiRunnerPlane plane, Instant quarantinedAt, String reason) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRunner runner = new CiRunner();
              runner.id = runnerId;
              runner.name = "quarantine-runner";
              runner.slots = slots;
              runner.plane = plane;
              runner.clientId = CLIENT;
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runner.quarantinedAt = quarantinedAt;
              runner.quarantineReason = reason;
              runnerRows.persist(runner);
            });
  }

  /** A health check queued for the runner by hand, as the door's own accept writes one. */
  private String pendingCheck() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRun run = new CiRun();
              run.id = UUID.randomUUID().toString();
              run.repoId = "quarantine-health-repo";
              run.branch = "main";
              run.commitSha = "a".repeat(40);
              run.status = CiRunStatus.QUEUED;
              run.createdAt = Instant.now();
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-runner-healthcheck.yml";
              run.triggerEventId = "healthcheck:" + run.id;
              run.purpose = CiRunPurpose.HEALTHCHECK;
              run.targetRunnerId = runnerId;
              runs.persist(run);
              return run.id;
            });
  }

  private Hello hello() {
    return new Hello(
        pins.version(),
        CiRunnerProtocol.CAPABILITY_VERSION,
        1,
        new Capabilities(true, "amd64", "linux", Map.of()));
  }
}
