package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.api.MachineGuardTest;
import eu.wohlben.qits.ci.control.CiEventTriggerParser;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiRunnerPresence;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.RunnerCapabilities;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Backlog;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.Heartbeat;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Nothing;
import eu.wohlben.qits.cirunner.protocol.Released;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import eu.wohlben.qits.cirunner.protocol.Take;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runner socket and the registry behind it, driven by a real WebSocket from a scripted {@link
 * FakeCiRunner} — {@code CiDaemonSocketTest}'s arrangement for the other socket.
 *
 * <p><b>The machine gate is on</b> ({@link MachineGuardTest.GateOn}, reused rather than copied: one
 * profile is one Quarkus start), because the socket's identity is a claim on a validated token and
 * with the gate off there is no token to read one from. The token is the test's {@code
 * @TestSecurity}/{@code @OidcSecurity}, which Quarkus applies at the upgrade where a real bearer
 * would land — so what is under test is this service's decision about the {@code sub}, and whether
 * a signature is checked stays quarkus-oidc's contract.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class CiRunnerSocketTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  private static final String RUNNER_ROLE = CiRunnerSocket.RUNNER_ROLE;

  /** The commissioned client the registered runner's cases present as their {@code sub}. */
  private static final String CLIENT = "ci-runner-client-under-test";

  private static final String AUDIENCE = "qits-platform";

  private static final String EVENT_NAME = "CiRunnerSocketEvent";

  private static final String TRIGGER_PATH = ".config/qits/ci-event-runner-socket.yml";

  private static final String TRIGGER_FILE =
      """
      event: CiRunnerSocketEvent
      steps:
        - image: alpine:3
          script: echo one
      """;

  @TestHTTPResource(RunnerAddresses.SOCKET_PATH)
  URI endpoint;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunRepository runs;

  @Inject CiRunnerRegistry registry;

  @Inject CiRunnerPresence presence;

  @Inject CiRunService runService;

  @Inject CiEventTriggerParser triggerParser;

  @Inject FakeCiStepRunner fakeSteps;

  private UUID runnerId;

  @BeforeEach
  void declareARegisteredRunner() {
    fakeSteps.reset();
    registry.seenInterval(Duration.ofMinutes(1));
    runnerId = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runnerRows.deleteAll();
              CiRunner runner = new CiRunner();
              runner.id = runnerId;
              runner.name = "socket-runner";
              runner.slots = 2;
              runner.plane = CiRunnerPlane.INTERNAL;
              runner.clientId = CLIENT;
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
            });
  }

  @AfterEach
  void forgetTheRunner() {
    registry.seenInterval(Duration.ofMinutes(1));
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  private static Hello hello(boolean docker) {
    return new Hello(
        "runner-test",
        CiRunnerProtocol.CAPABILITY_VERSION,
        1,
        new Capabilities(docker, "amd64", "linux", Map.of("site", "home")));
  }

  private CiRunner row() {
    return QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(runnerId));
  }

  // --- the subject rule ---------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "stranger", roles = RUNNER_ROLE)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = AUDIENCE),
        @Claim(key = "sub", value = "a-client-no-runner-owns")
      })
  void aSubjectNoRunnerOwnsIsClosedWithAPolicyViolation() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      assertEquals((Short) (short) 1008, runner.awaitClose(SOON));
      assertEquals(CiRunnerSocket.UNKNOWN_RUNNER, runner.closeReason());
    }
    assertFalse(presence.connected(runnerId));
  }

  @Test
  @TestSecurity(user = "forwarded", roles = RUNNER_ROLE)
  void aForwardedIdentityWithTheRoleButNoTokenIsNeverARunner() throws Exception {
    // The role opens the upgrade; the subject is read off a token and nowhere else, so an identity
    // that could have been asserted in two headers names no runner at all.
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      assertEquals((Short) (short) 1008, runner.awaitClose(SOON));
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void theRunnerIsAnsweredWithItsRowsSlotsAndTheBacklogAndItsCapabilitiesAreRecorded()
      throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(true));

      Ack ack = runner.next(Ack.class, SOON);
      assertNotNull(ack, "a Hello is answered with an Ack");
      assertEquals(CiRunnerProtocol.CAPABILITY_VERSION, ack.capabilityVersion());
      // The row's number, not the machine's: the Hello said 1 and the row grants 2.
      assertEquals(2, ack.slots());
      Backlog backlog = runner.next(Backlog.class, SOON);
      assertNotNull(backlog, "the Ack is followed by the queue's length");
      assertEquals(runService.queuedCount(), backlog.queued());

      assertTrue(presence.connected(runnerId), "the controller's DTO reads this seam");
      CiRunner row = row();
      assertNotNull(row.lastSeenAt);
      JsonNode capabilities = RunnerCapabilities.decode(row.capabilities);
      assertTrue(capabilities.path("docker").asBoolean());
      assertEquals("amd64", capabilities.path("arch").asText());
      assertEquals("home", capabilities.path("labels").path("site").asText());
    }
    awaitDisconnected();
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunnerSpeakingAnotherCapabilityVersionIsClosed() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(
          new Hello(
              "runner-test",
              CiRunnerProtocol.CAPABILITY_VERSION + 1,
              1,
              new Capabilities(true, "amd64", "linux", Map.of())));
      assertEquals((Short) (short) 1008, runner.awaitClose(SOON));
      assertEquals(CiRunnerSocket.CAPABILITY_MISMATCH, runner.closeReason());
    }
  }

  // --- one session per runner ---------------------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aSecondConnectionReplacesTheFirstWhichIsClosedAlreadyConnected() throws Exception {
    try (FakeCiRunner first = FakeCiRunner.dial(endpoint)) {
      first.send(hello(true));
      assertNotNull(first.next(Ack.class, SOON));
      try (FakeCiRunner second = FakeCiRunner.dial(endpoint)) {
        assertEquals((Short) (short) 1008, first.awaitClose(SOON));
        assertEquals(CiRunnerRegistry.ALREADY_CONNECTED, first.closeReason());

        // The newcomer is the runner now, and the old socket's late close did not take it away.
        second.send(hello(true));
        assertNotNull(second.next(Ack.class, SOON), "the replacing connection is served");
        assertTrue(second.isOpen());
        assertTrue(presence.connected(runnerId));
      }
    }
    awaitDisconnected();
  }

  // --- what the registry pushes and records -------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anAcceptedRunIsPushedToTheRunnerAsTheQueuesNewLength() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(true));
      Backlog initial = runner.next(Backlog.class, SOON);
      assertNotNull(initial);

      // Park the local worker inside its step so the accepted run is really queued behind it, and
      // the number pushed on accept is the queue with that run in it.
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      CompletableFuture<Void> parked = new CompletableFuture<>();
      fakeSteps.during(
          0,
          spec -> {
            parked.complete(null);
            try {
              release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      try {
        accept("runner-socket-holding");
        parked.get(30, TimeUnit.SECONDS);
        accept("runner-socket-queued");
        Backlog pushed =
            (Backlog)
                runner.nextMatching(
                    m -> m instanceof Backlog b && b.queued() >= 1, Duration.ofSeconds(10));
        assertNotNull(pushed, "an accept pushes the queue's length to a connected runner");
      } finally {
        release.countDown();
      }
      // And a finish pushes again, once the worker has worked the queue down.
      assertNotNull(
          runner.nextMatching(m -> m instanceof Backlog b && b.queued() == 0, Duration.ofSeconds(30)),
          "a finished run pushes the queue again");
    }
    awaitDisconnected();
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aHeartbeatStampsTheRowOncePerInterval() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(true));
      assertNotNull(runner.next(Ack.class, SOON));
      Instant old = Instant.now().minus(Duration.ofDays(1));
      QuarkusTransaction.requiringNew().run(() -> runnerRows.findById(runnerId).lastSeenAt = old);

      // Inside the interval the open socket is the signal and the row is left alone.
      runner.send(new Heartbeat());
      Thread.sleep(300);
      assertTrue(
          row().lastSeenAt.isBefore(Instant.now().minusSeconds(3600)),
          "a heartbeat inside the interval must not reach the row");

      registry.seenInterval(Duration.ZERO);
      runner.send(new Heartbeat());
      Instant deadline = Instant.now().plusSeconds(10);
      while (row().lastSeenAt.isBefore(Instant.now().minusSeconds(60))
          && Instant.now().isBefore(deadline)) {
        Thread.sleep(50);
      }
      assertTrue(
          row().lastSeenAt.isAfter(Instant.now().minusSeconds(60)),
          "a heartbeat past the interval stamps last_seen_at");
    }
    awaitDisconnected();
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aCloseCompletesALaunchStillWaitingOnItsAnswer() throws Exception {
    CompletableFuture<CiRunnerRegistry.LaunchAnswer> answer;
    Instant closedAt;
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(true));
      assertNotNull(runner.next(Ack.class, SOON));
      CiRunnerRegistry.Session session = registry.current(runnerId);
      assertNotNull(session);

      // A deadline far longer than the test: the answer must come from the close, not the clock.
      answer =
          CompletableFuture.supplyAsync(
              () ->
                  registry.launch(
                      session,
                      new Launch("run-close", 0, WorkloadSpec.of("alpine:3", "c")),
                      Duration.ofMinutes(5)));
      assertNotNull(runner.next(Launch.class, SOON), "the Launch reached the runner");
      closedAt = Instant.now();
    }
    CiRunnerRegistry.LaunchAnswer lost = answer.get(10, TimeUnit.SECONDS);
    assertEquals(CiRunnerRegistry.LaunchAnswer.Status.CONNECTION_LOST, lost.status());
    assertTrue(Duration.between(closedAt, Instant.now()).toSeconds() < 10);
    awaitDisconnected();
  }

  // --- Reserve is the claim -----------------------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aReserveIsAnsweredWithTheRunItClaimedAndTheClosedRunIsReleased() throws Exception {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    CompletableFuture<Void> parked = new CompletableFuture<>();
    fakeSteps.during(
        0,
        spec -> {
          if (parked.complete(null)) {
            try {
              release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(true));
      assertNotNull(runner.next(Ack.class, SOON));
      accept("runner-reserve-blocker");
      parked.get(30, TimeUnit.SECONDS);
      String runId = accept("runner-reserve-taken");

      runner.send(new Reserve());
      Take take = runner.next(Take.class, SOON);
      assertNotNull(take, "a Reserve with a run it can take is a Take");
      assertEquals(runId, take.runId());
      assertEquals("runner-reserve-taken", take.repoName());
      assertEquals(
          runnerId, QuarkusTransaction.requiringNew().call(() -> runs.findById(runId).runnerId));

      // Whatever the run's steps come to — a launch this fake refuses, here — it closes, and the
      // close is the one frame that gives the runner its slot back.
      Released released = null;
      Instant deadline = Instant.now().plusSeconds(30);
      while (released == null && Instant.now().isBefore(deadline)) {
        CiRunnerMessage frame = runner.next(Duration.ofSeconds(1));
        if (frame instanceof Launch launch) {
          runner.send(new LaunchFailed(launch.runId(), launch.stepIndex(), "refused by the test"));
        } else if (frame instanceof Released r && r.runId().equals(runId)) {
          released = r;
        }
      }
      assertNotNull(released, "a closed runner run is Released to the runner");
      assertEquals(
          CiRunStatus.FAILED,
          QuarkusTransaction.requiringNew().call(() -> runs.findById(runId).status));

      // And with nothing left it could take, the answer is Nothing.
      runner.send(new Reserve());
      assertNotNull(runner.next(Nothing.class, SOON), "a Reserve with nothing to take is Nothing");
    } finally {
      release.countDown();
    }
    awaitDisconnected();
  }

  @Test
  @TestSecurity(user = "runner", roles = {RUNNER_ROLE, "qits:admin"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void theQueueNamesEveryRunnerAndARunnersRunCarriesItsName() throws Exception {
    String runId = UUID.randomUUID().toString();
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(true));
      assertNotNull(runner.next(Ack.class, SOON));
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                CiRun run = new CiRun();
                run.id = runId;
                run.repoId = "runner-queue-repo";
                run.branch = "main";
                run.commitSha = "d".repeat(40);
                run.status = CiRunStatus.RUNNING;
                run.triggerType = CiTriggerType.EVENT;
                run.configPath = TRIGGER_PATH;
                run.triggerEventId = UUID.randomUUID().toString();
                run.triggerEventName = EVENT_NAME;
                run.createdAt = Instant.now();
                run.startedAt = Instant.now();
                run.runnerId = runnerId;
                runs.persist(run);
              });

      io.restassured.path.json.JsonPath queue =
          io.restassured.RestAssured.given()
              .get("/ci/api/runs/queue")
              .then()
              .statusCode(200)
              .extract()
              .jsonPath();
      assertEquals(List.of("socket-runner"), queue.getList("runners.name"));
      assertEquals(2, queue.getInt("runners[0].slots"));
      assertEquals(1, queue.getInt("runners[0].held"));
      assertTrue(queue.getBoolean("runners[0].connected"));
      assertEquals(
          "socket-runner", queue.getString("running.find { it.id == '" + runId + "' }.runnerName"));
      assertEquals(
          "socket-runner",
          io.restassured.RestAssured.given()
              .get("/ci/api/runs/active")
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getString("runs.find { it.id == '" + runId + "' }.runnerName"));
    } finally {
      QuarkusTransaction.requiringNew().run(() -> runs.deleteById(runId));
    }
    awaitDisconnected();
  }

  // ---------------------------------------------------------------------------------------------

  private String accept(String repoId) {
    String sha = String.format("%08x", repoId.hashCode()).repeat(5);
    return runService.onEventTrigger(
        new CiRunService.EventRun(
            CiRepoRef.of(repoId),
            "main",
            sha,
            triggerParser.parse(TRIGGER_PATH, TRIGGER_FILE),
            UUID.randomUUID().toString(),
            EVENT_NAME,
            Instant.now(),
            "{}",
            TRIGGER_FILE,
            null));
  }

  /** The close is asynchronous on the server; the next case must not start with this runner on. */
  private void awaitDisconnected() throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (presence.connected(runnerId) && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    assertFalse(presence.connected(runnerId), "the runner's session ended with its socket");
  }
}
