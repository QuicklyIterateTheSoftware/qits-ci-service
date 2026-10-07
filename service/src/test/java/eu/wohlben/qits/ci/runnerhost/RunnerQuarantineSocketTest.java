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
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Quarantined;
import eu.wohlben.qits.cirunner.protocol.Reinstated;
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

  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE)})
  void anAgentPressesNeitherButton() {
    declare(1, CiRunnerPlane.EDGE, null, null);

    given().post(RUNNERS + "/" + runnerId + "/greenlight").then().statusCode(403);
    given().post(RUNNERS + "/" + runnerId + "/healthcheck").then().statusCode(403);
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
