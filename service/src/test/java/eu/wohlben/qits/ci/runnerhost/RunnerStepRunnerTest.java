package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.api.MachineGuardTest;
import eu.wohlben.qits.ci.control.CiEventTriggerParser;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiStepRunner;
import eu.wohlben.qits.ci.control.CiStepRunner.StepListener;
import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.control.CiStepRunner.StepSpec;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.FakeCiDaemon;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.ScriptedRunTokens;
import eu.wohlben.qits.ci.testdb.HermeticConfigSource;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.Cancel;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import eu.wohlben.qits.cirunner.protocol.Released;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import eu.wohlben.qits.cirunner.protocol.Take;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RunnerStepRunner} against a scripted runner ({@link FakeCiRunner}) on the real runner
 * socket and a scripted step daemon ({@link FakeCiDaemon}) on the real daemon socket: the step the
 * runner is asked to start is the step whose daemon then dials this host, exactly as a runner host
 * would arrange it, so the whole sequence is real except the two containers.
 *
 * <p>The identity is the test's {@code @TestSecurity}, carrying both roles — the runner's and
 * {@code qits:ci-run}, the only role the daemon socket's upgrade admits (qits-516) — because Quarkus
 * applies it to every upgrade in the method, the runner's and the daemon's. So the daemon arrives
 * as the {@code sub} {@link #CLIENT}, and every run's {@code ci-run} token is scripted to carry
 * that subject ({@link ScriptedRunTokens}): a step's daemon is admitted only as its run's token. The gate is on for the runner
 * socket's reason ({@link CiRunnerSocketTest}); the profile is the same class, so the same start.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class RunnerStepRunnerTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  private static final String CLIENT = "ci-runner-client-under-step-test";

  private static final String AUDIENCE = "qits-platform";

  private static final String RUNNER_NAME = "step-runner";

  private static final String EVENT_NAME = "RunnerStepEvent";

  private static final String TRIGGER_PATH = ".config/qits/ci-event-runner-step.yml";

  private static final String TRIGGER_FILE =
      """
      event: RunnerStepEvent
      steps:
        - image: alpine:3
          script: echo on the runner
      """;

  @TestHTTPResource(RunnerAddresses.SOCKET_PATH)
  URI runnerEndpoint;

  @TestHTTPResource("/ci/daemon")
  URI daemonEndpoint;

  @Inject RunnerStepRunner steps;

  @Inject CiRunnerRegistry registry;

  @Inject CiRunnerPins pins;

  @Inject StepContainerSettings launcher;

  @Inject RunnerAddresses addresses;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunRepository runs;

  @Inject CiStepRepository stepRows;

  @Inject CiRunService runService;

  @Inject CiEventTriggerParser triggerParser;

  @Inject FakeCiStepRunner localSteps;

  @Inject CiDaemonRegistry daemons;

  private UUID runnerId;

  private ScriptedRunTokens runTokens;

  @BeforeEach
  void declareARegisteredRunner() {
    // Create the bean HERE, on the test thread. The cases call steps.run from supplyAsync, and a
    // ForkJoin common-pool thread carries the system class loader as its TCCL: a bean first created
    // there has its @ConfigProperty fields resolved against a Config that holds none of the ci jar's
    // keys, so the register, init and grace deadlines all read 0 and a step fails NEVER_STARTED
    // before its daemon has dialled. Measured 2026-09-28 in a narrow run (-Dtest=RunnerStepRunnerTest).
    steps.owns("warm-up");
    // The suite ships the qits oidc client off, and a step with no token is not launched.
    runTokens = new ScriptedRunTokens(CLIENT);
    QuarkusMock.installMockForType(runTokens, RunCommissions.class);
    localSteps.reset();
    runnerId = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runnerRows.deleteAll();
              CiRunner runner = new CiRunner();
              runner.id = runnerId;
              runner.name = RUNNER_NAME;
              runner.slots = 1;
              runner.plane = CiRunnerPlane.EDGE;
              runner.clientId = CLIENT;
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
            });
  }

  @AfterEach
  void forgetTheRunner() {
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
    settleLeftovers();
  }
  /**
   * Nothing runs a run this suite leaves QUEUED — there is no claim loop since qits-506, and no
   * runner is connected after the case — so it is settled here rather than left for a later case's
   * runner to be handed.
   */
  private void settleLeftovers() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                runs.update(
                    "status = ?1, finishedAt = ?2 where status = ?3",
                    CiRunStatus.CANCELLED,
                    Instant.now(),
                    CiRunStatus.QUEUED));
  }


  // --- the step, through the seam -----------------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aGreenStepIsLaunchedByTheRunnerAndRunByTheDaemonThatDialsBack() throws Exception {
    String runId = "runner-green-" + UUID.randomUUID();
    Recorder listener = new Recorder();
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), listener));

      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch, "the runner is asked to start the step's container");
      assertEquals(runId, launch.runId());
      WorkloadSpec workload = launch.workloadSpec();
      assertEquals(StepContainerSettings.containerName(runId, 0), workload.name());
      assertEquals(Map.of("qits.ci.run", runId), workload.labels());
      assertTrue(
          workload.labels().keySet().stream().noneMatch(k -> k.startsWith("qits.ci.runner")),
          "the runner refuses its own namespace in a spec, and stamps it itself");
      // The composition, field for field: the suite's public names and the run's token.
      assertEquals(
          StepWorkloadSpecs.compose(
              launcher.workloadSettings(),
              launcher.plane(addresses.edgeOrigins().orElseThrow()),
              launchSpec(runId, workload),
              runTokens.forRun(runId, Map.of()),
              null),
          workload);
      assertEquals(
          "wss://ci.qits." + HermeticConfigSource.DOMAIN + "/ci/daemon",
          workload.env().get("QITS_CI_DAEMON_URL"));
      assertEquals(CLIENT, workload.env().get("QITS_TOKEN_SUBJECT"));
      assertNull(workload.env().get("QITS_CI_DAEMON_SECRET"), "there is no per-container secret");
      runner.send(new Launched(runId, 0, "c0ffee"));

      try (FakeCiDaemon daemon = dialAsTheContainer(workload)) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(
                workload.env().get("QITS_CI_DAEMON_ID"), CiDaemonProtocol.CAPABILITY_VERSION));
        assertInstanceOf(Ack.class, daemon.next(SOON));
        daemon.send(new Initialized());
        RunStep runStep = assertInstanceOf(RunStep.class, daemon.next(SOON));
        assertEquals("echo on the runner", runStep.script());
        daemon.send(new StepChunk(runStep.correlationId(), 0, Stream.OUT, "on the runner\n"));
        daemon.send(new StepFinished(runStep.correlationId(), 0, false));

        Reap reap = runner.next(Reap.class, SOON);
        assertNotNull(reap, "every step ends with its container reaped on the runner");
        assertEquals(workload.name(), reap.containerName());
        runner.send(new Reaped(runId, 0));

        StepResult green = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
        assertEquals(StepOutcome.OK, green.outcome());
        assertEquals(0, green.exitCode());
        assertTrue(green.output().contains("on the runner"));
        assertEquals(
            List.of(
                "launched:" + workload.env().get("QITS_CI_DAEMON_ID") + "@" + CLIENT,
                "started",
                "chunk:on the runner\n",
                "finished"),
            listener.events);
      }
    } finally {
      steps.runClosed(runId);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aLaunchTheRunnerCouldNotMakeIsLaunchFailedInDockersWords() throws Exception {
    String runId = "runner-refused-" + UUID.randomUUID();
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

      Launch launch = runner.next(Launch.class, SOON);
      runner.send(
          new LaunchFailed(runId, 0, "docker pull alpine:3 failed: pull access denied"));

      Reap reap = runner.next(Reap.class, SOON);
      assertNotNull(reap, "a refused launch is still reaped — the runner may have half-made it");
      assertEquals(launch.workloadSpec().name(), reap.containerName());
      runner.send(new Reaped(runId, 0));

      StepResult refused = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
      assertEquals(StepOutcome.LAUNCH_FAILED, refused.outcome());
      assertTrue(refused.output().contains("pull access denied"), refused.output());
      assertTrue(refused.output().contains(RUNNER_NAME), refused.output());
    } finally {
      steps.runClosed(runId);
    }
  }

  // --- the runner's step memory limit ------------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void theLaunchCarriesTheRunnersStepMemoryLimitReadAtEachStepElseThePlatformDefault()
      throws Exception {
    String platformDefault = launcher.workloadSettings().memoryLimit();
    assertNotNull(platformDefault);
    try (FakeCiRunner runner = greeted()) {
      // No limit on the row: exactly the platform's cap, as before the column existed.
      WorkloadSpec none = launchedAndRefused(runner, "runner-mem-none-");
      assertEquals(platformDefault, none.memory());
      assertEquals(platformDefault, none.memorySwap());

      // Set on the row of a runner that is already connected: the very next step has it, memory and
      // memory-swap alike, with no reconnect.
      setStepMemoryLimit("6g");
      WorkloadSpec six = launchedAndRefused(runner, "runner-mem-six-");
      assertEquals("6g", six.memory());
      assertEquals("6g", six.memorySwap());
      // Nothing else about the spec moves with it.
      assertEquals(none.pidsLimit(), six.pidsLimit());
      assertEquals(none.cpus(), six.cpus());

      setStepMemoryLimit("8192m");
      WorkloadSpec eight = launchedAndRefused(runner, "runner-mem-eight-");
      assertEquals("8192m", eight.memory());
      assertEquals("8192m", eight.memorySwap());

      // Cleared: back to the platform default.
      setStepMemoryLimit(null);
      WorkloadSpec cleared = launchedAndRefused(runner, "runner-mem-cleared-");
      assertEquals(platformDefault, cleared.memory());
      assertEquals(platformDefault, cleared.memorySwap());
    }
  }

  private void setStepMemoryLimit(String limit) {
    QuarkusTransaction.requiringNew()
        .run(() -> runnerRows.findById(runnerId).stepMemoryLimit = limit);
  }

  /** One step on {@code runner}, answered LaunchFailed and reaped; the spec it was asked to start. */
  private WorkloadSpec launchedAndRefused(FakeCiRunner runner, String prefix) throws Exception {
    String runId = prefix + UUID.randomUUID();
    try {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));
      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch, "the runner is asked to start the step's container");
      runner.send(new LaunchFailed(runId, 0, "refused by the test"));
      Reap reap = runner.next(Reap.class, SOON);
      assertNotNull(reap);
      runner.send(new Reaped(runId, 0));
      assertEquals(
          StepOutcome.LAUNCH_FAILED, result.get(SOON.toSeconds(), TimeUnit.SECONDS).outcome());
      return launch.workloadSpec();
    } finally {
      steps.runClosed(runId);
    }
  }

  // --- the runner's own container log (qits-467) --------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aGreenStepIgnoresTheRunnersContainerLog() throws Exception {
    String runId = "runner-green-logtail-" + UUID.randomUUID();
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch);
      runner.send(new Launched(runId, 0, "c0ffee"));

      try (FakeCiDaemon daemon = dialAsTheContainer(launch.workloadSpec())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(
                launch.workloadSpec().env().get("QITS_CI_DAEMON_ID"), CiDaemonProtocol.CAPABILITY_VERSION));
        assertInstanceOf(Ack.class, daemon.next(SOON));
        daemon.send(new Initialized());
        RunStep runStep = assertInstanceOf(RunStep.class, daemon.next(SOON));
        daemon.send(new StepFinished(runStep.correlationId(), 0, false));

        Reap reap = runner.next(Reap.class, SOON);
        assertNotNull(reap);
        // The container's own log arrives with the Reaped, exactly as a failed step's would — a
        // green step must ignore it: its own captured output already said everything worth saying.
        runner.send(new Reaped(runId, 0, "[container exited 0]\nshould never appear"));

        StepResult green = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
        assertEquals(StepOutcome.OK, green.outcome());
        assertFalse(green.output().contains("its own log"), green.output());
        assertFalse(green.output().contains("should never appear"), green.output());
      }
    } finally {
      steps.runClosed(runId);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aNeverDialledStepCarriesTheRunnersContainerLogBelowAPointer() throws Exception {
    String runId = "runner-never-dialled-logtail-" + UUID.randomUUID();
    // The real register deadline is 180s (qits.ci.daemon-register-timeout-seconds); a suite cannot
    // wait that out, so it is shortened here and put back in the finally below.
    steps.registerTimeoutSeconds(2);
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch);
      runner.send(new Launched(runId, 0, "c0ffee"));
      // No daemon ever dials back — the container started and nothing spoke, NEVER_STARTED's case.

      Reap reap = runner.next(Reap.class, SOON);
      assertNotNull(reap, "a never-started container is still reaped");
      runner.send(new Reaped(runId, 0, "[container exited 1]\nboom: something failed"));

      StepResult never = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
      assertEquals(StepOutcome.NEVER_STARTED, never.outcome());
      assertFalse(
          never.output().contains("its own log is on the runner's host"),
          "the stale pointer is replaced once the log is really here: " + never.output());
      assertTrue(never.output().contains("its own log is appended below"), never.output());
      assertTrue(
          never.output()
              .contains("--- the step container's own log (from runner " + RUNNER_NAME + ") ---"),
          never.output());
      assertTrue(never.output().contains("boom: something failed"), never.output());
    } finally {
      steps.registerTimeoutSeconds(180);
      steps.runClosed(runId);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aNeverDialledStepWithNoLogTailKeepsTodaysMessage() throws Exception {
    String runId = "runner-never-dialled-notail-" + UUID.randomUUID();
    steps.registerTimeoutSeconds(2);
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch);
      runner.send(new Launched(runId, 0, "c0ffee"));

      Reap reap = runner.next(Reap.class, SOON);
      assertNotNull(reap);
      // The two-argument constructor an older runner still speaks — logTail is null.
      runner.send(new Reaped(runId, 0));

      StepResult never = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
      assertEquals(StepOutcome.NEVER_STARTED, never.outcome());
      assertTrue(
          never.output()
              .contains(
                  "its own log is on the runner's host ("
                      + StepContainerSettings.containerName(runId, 0)
                      + ")"),
          never.output());
      assertFalse(never.output().contains("--- the step container's own log"), never.output());
    } finally {
      steps.registerTimeoutSeconds(180);
      steps.runClosed(runId);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aReapedArrivingAfterTheBoundIsNotAppendedAndNothingHangs() throws Exception {
    String runId = "runner-late-reaped-" + UUID.randomUUID();
    steps.reapTimeout(Duration.ofMillis(300));
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch);
      runner.send(new LaunchFailed(runId, 0, "not today"));

      Reap reap = runner.next(Reap.class, SOON);
      assertNotNull(reap);
      // Deliberately answer nothing: run() must return once its own bound elapses, never hang.
      StepResult refused = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
      assertEquals(StepOutcome.LAUNCH_FAILED, refused.outcome());
      assertFalse(refused.output().contains("--- the step container's own log"), refused.output());

      // The late answer must land nowhere: its key was already forgotten when the bound elapsed.
      runner.send(new Reaped(runId, 0, "far too late to matter"));
      Thread.sleep(200);
    } finally {
      steps.reapTimeout(RunnerStepRunner.REAP_TIMEOUT);
      steps.runClosed(runId);
    }
  }

  // --- a self-updating runner (qits-465) ---------------------------------------------------------

  /**
   * A run held by a connection that was since told to upgrade, with the pinned successor already
   * beside it: the step's {@code Launch} and {@code Reap} go to the connection that took the run,
   * never to the runner's current one — the successor never counted that run against a slot.
   */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunHeldByTheDrainingConnectionIsLaunchedAndReapedThere() throws Exception {
    String runId = "runner-draining-" + UUID.randomUUID();
    try (FakeCiRunner old = FakeCiRunner.dial(runnerEndpoint)) {
      old.send(
          new Hello(
              "0.0.1-old",
              CiRunnerProtocol.CAPABILITY_VERSION,
              1,
              new Capabilities(true, "amd64", "linux", Map.of())));
      assertNotNull(old.next(eu.wohlben.qits.cirunner.protocol.Upgrade.class, SOON));
      assertNotNull(old.next(eu.wohlben.qits.cirunner.protocol.Ack.class, SOON));
      registry.hold(registry.current(runnerId), runId);
      try (FakeCiRunner successor = greeted()) {
        assertNotNull(old.next(eu.wohlben.qits.cirunner.protocol.Retire.class, SOON));
        assertTrue(registry.holding(runId).draining());
        assertFalse(registry.current(runnerId).draining(), "the successor is the runner now");

        CompletableFuture<StepResult> result =
            CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

        Launch launch = old.next(Launch.class, SOON);
        assertNotNull(launch, "the draining connection is asked for its own run's container");
        old.send(new LaunchFailed(runId, 0, "refused on the old connection"));
        Reap reap = old.next(Reap.class, SOON);
        assertNotNull(reap, "and reaps it");
        old.send(new Reaped(runId, 0));

        StepResult refused = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
        assertEquals(StepOutcome.LAUNCH_FAILED, refused.outcome());
        assertTrue(refused.output().contains("refused on the old connection"), refused.output());
        assertNull(successor.next(Launch.class, Duration.ofMillis(300)));
      }
    } finally {
      steps.runClosed(runId);
    }
  }

  // --- the run's token, and the two things without which no step is launched ----------------------

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aStepIsToldThePublicNamesAndCarriesTheRunsTokenCommissionedAtQitsIdp() throws Exception {
    // qits-idp is a stub behind the REAL RunCommissions: the Launch the runner is asked for carries
    // a ci-run token and no client, and the run's close gives the token back.
    QuarkusMock.installMockForType(
        RunnerAddressesFixture.withDomain("example.org"), RunnerAddresses.class);
    try (eu.wohlben.qits.ci.idp.StubIdp idp = new eu.wohlben.qits.ci.idp.StubIdp()) {
      QuarkusMock.installMockForType(idp.runCommissions(Duration.ofMillis(200)), RunCommissions.class);
      String runId = "runner-edge-" + UUID.randomUUID();
      try (FakeCiRunner runner = greeted()) {
        registry.hold(registry.current(runnerId), runId);
        CompletableFuture<StepResult> result =
            CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

        Launch launch = runner.next(Launch.class, SOON);
        assertNotNull(launch);
        WorkloadSpec workload = launch.workloadSpec();
        assertEquals("wss://ci.qits.example.org/ci/daemon", workload.env().get("QITS_CI_DAEMON_URL"));
        assertEquals(
            "https://githost.qits.example.org/git/runner-step-repo",
            workload.env().get("QITS_CI_REPOSITORY_URL"));
        assertEquals("qits_tok_stub-1", workload.env().get("QITS_TOKEN"));
        assertNull(workload.env().get("QITS_COMMISSIONED_CLIENT_ID"));
        assertNull(workload.env().get("QITS_CI_DAEMON_SECRET"));
        assertNull(workload.network(), "no docker network on a host outside the swarm");
        assertTrue(workload.extraHosts() == null || workload.extraHosts().isEmpty());
        assertEquals(1, idp.postedTokens.size(), "one ci-run token for the run");
        assertTrue(idp.posted.isEmpty(), "and no client");

        runner.send(new LaunchFailed(runId, 0, "not today"));
        Reap reap = runner.next(Reap.class, SOON);
        assertNotNull(reap);
        runner.send(new Reaped(runId, 0));
        assertEquals(
            StepOutcome.LAUNCH_FAILED, result.get(SOON.toSeconds(), TimeUnit.SECONDS).outcome());
      } finally {
        steps.runClosed(runId);
      }
      // The run's close gave the token back.
      assertEquals(List.of("token-1"), idp.deletedTokens);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aQitsCiThatCommissionsNothingLaunchesNoStepAndSaysWhy() throws Exception {
    // The shipped posture: the qits oidc client is off. A step's only credential is its run's
    // token, so none is launched — recorded at once, naming the key, rather than sitting out the
    // register deadline as a container that can reach nothing.
    QuarkusMock.installMockForType(
        eu.wohlben.qits.ci.idp.StubIdp.disabledCommissions(), RunCommissions.class);
    String runId = "runner-uncommissioned-" + UUID.randomUUID();
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);

      StepResult refused = steps.run(step(runId), new Recorder());

      assertEquals(StepOutcome.LAUNCH_FAILED, refused.outcome());
      assertTrue(refused.output().contains("quarkus.oidc-client.qits.client-enabled"), refused.output());
      assertNull(runner.next(Launch.class, Duration.ofMillis(300)), "the runner was asked for nothing");
    } finally {
      steps.runClosed(runId);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aQitsCiThatKnowsNoPublicDomainLaunchesNoStepAndSaysWhy() throws Exception {
    // No address to tell the step, and no qits-net alias to fall back to (qits-515).
    QuarkusMock.installMockForType(RunnerAddressesFixture.withDomain(null), RunnerAddresses.class);
    String runId = "runner-undomained-" + UUID.randomUUID();
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);

      StepResult refused = steps.run(step(runId), new Recorder());

      assertEquals(StepOutcome.LAUNCH_FAILED, refused.outcome());
      assertTrue(refused.output().startsWith("EDGE_PLANE_UNCONFIGURED: "), refused.output());
      assertTrue(refused.output().contains("QITS_DOMAIN"), refused.output());
      assertNull(runner.next(Launch.class, Duration.ofMillis(300)), "the runner was asked for nothing");
      assertTrue(runTokens.released.isEmpty(), "and no token was commissioned for it");
    } finally {
      steps.runClosed(runId);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aDaemonThatIsNotTheRunsTokenIsNotAdmittedToTheRunsLaunch() throws Exception {
    // The launch is bound to ITS run's token subject. This suite's daemon arrives as CLIENT, so a
    // run whose token carries another subject refuses it WRONG_RUN — and the step never registers.
    QuarkusMock.installMockForType(
        new ScriptedRunTokens("tok-ci-run-somebody-else"), RunCommissions.class);
    String runId = "runner-wrong-token-" + UUID.randomUUID();
    steps.registerTimeoutSeconds(2);
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));

      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch);
      runner.send(new Launched(runId, 0, "c0ffee"));
      try (FakeCiDaemon daemon = FakeCiDaemon.dial(daemonEndpoint, Map.of())) {
        daemon.hello(launch.workloadSpec().env().get("QITS_CI_DAEMON_ID"));
        assertEquals((Short) (short) 1008, daemon.awaitClose(SOON));
        assertEquals("WRONG_RUN", daemon.closeReason());
      }

      Reap reap = runner.next(Reap.class, SOON);
      assertNotNull(reap);
      runner.send(new Reaped(runId, 0));
      assertEquals(
          StepOutcome.NEVER_STARTED, result.get(SOON.toSeconds(), TimeUnit.SECONDS).outcome());
    } finally {
      steps.registerTimeoutSeconds(180);
      steps.runClosed(runId);
    }
  }

  // --- a runner that vanishes mid-step, end to end ------------------------------------------------

  @Test
  @TestSecurity(
      user = "runner",
      roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run", "qits:admin"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunnerVanishingMidStepFailsTheRunNamingItAndTheRunIsRetryable() throws Exception {
    // Nobody comes back for it here, so the grace (a minute shipped) is what the run waits out.
    registry.reconnectGrace(Duration.ofSeconds(1));
    CountDownLatch releaseLocal = new CountDownLatch(1);
    CompletableFuture<Void> parked = new CompletableFuture<>();
    localSteps.during(
        0,
        spec -> {
          if (parked.complete(null)) {
            try {
              releaseLocal.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    String runId;
    try {
      // Nothing else claims since qits-506, so the one accepted is the runner's.
      runId = accept("runner-vanish-target");

      Instant vanishedAt;
      FakeCiDaemon daemon = null;
      try {
        try (FakeCiRunner runner = greeted()) {
          runner.send(new Reserve());
          Take take = runner.next(Take.class, SOON);
          assertNotNull(take);
          assertEquals(runId, take.runId());
          Launch launch = runner.next(Launch.class, SOON);
          assertNotNull(launch, "the reserved run's first step is launched on the runner");
          runner.send(new Launched(runId, 0, "c0ffee"));

          daemon = dialAsTheContainer(launch.workloadSpec());
          daemon.send(
              new eu.wohlben.qits.cidaemon.protocol.Hello(
                  launch.workloadSpec().env().get("QITS_CI_DAEMON_ID"),
                  CiDaemonProtocol.CAPABILITY_VERSION));
          assertInstanceOf(Ack.class, daemon.next(SOON));
          daemon.send(new Initialized());
          RunStep runStep = assertInstanceOf(RunStep.class, daemon.next(SOON));
          daemon.send(new StepChunk(runStep.correlationId(), 0, Stream.OUT, "halfway\n"));
          // The step is running and its daemon stays connected — and the runner goes away.
          Thread.sleep(200);
          vanishedAt = Instant.now();
        }
        CiRun failed = awaitTerminal(runId);
        assertEquals(CiRunStatus.FAILED, failed.status);
        assertTrue(
            Duration.between(vanishedAt, failed.finishedAt).toSeconds() < 10,
            "a vanished runner ends the step when its grace runs out, not at a deadline");
      } finally {
        if (daemon != null) {
          daemon.close();
        }
      }
      CiStep step =
          QuarkusTransaction.requiringNew()
              .call(() -> stepRows.find("runId = ?1 and stepIndex = 0", runId).firstResult());
      assertTrue(step.output.contains("halfway"), step.output);
      assertTrue(step.output.contains("[runner " + RUNNER_NAME + " disconnected]"), step.output);
      assertTrue(
          step.output.contains("[the connection to the step container was lost]"), step.output);

      // An ordinary failed run: retried, it is queued again with no runner attached.
      CiRun retried = runService.retry(runId);
      assertEquals(runId, retried.retryOfRunId);
      assertNull(retried.runnerId);
    } finally {
      registry.reconnectGrace(null);
      releaseLocal.countDown();
    }
  }

  // --- a runner that blinks (qits-545) -------------------------------------------------------------

  /**
   * The 2026-09-29 failure, turned around: the runner's socket drops mid-step and the runner is back
   * a moment later claiming the run. The step's daemon never noticed, the step finishes green, and
   * its teardown reaps on the connection that came back.
   */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunnerThatComesBackForItsRunInsideTheGraceCarriesTheStepOn() throws Exception {
    String runId = "runner-blip-" + UUID.randomUUID();
    Recorder listener = new Recorder();
    try {
      FakeCiRunner first = greeted();
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), listener));
      Launch launch = first.next(Launch.class, SOON);
      assertNotNull(launch);
      first.send(new Launched(runId, 0, "c0ffee"));

      try (FakeCiDaemon daemon = dialAsTheContainer(launch.workloadSpec())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(
                launch.workloadSpec().env().get("QITS_CI_DAEMON_ID"),
                CiDaemonProtocol.CAPABILITY_VERSION));
        assertInstanceOf(Ack.class, daemon.next(SOON));
        daemon.send(new Initialized());
        RunStep runStep = assertInstanceOf(RunStep.class, daemon.next(SOON));
        daemon.send(new StepChunk(runStep.correlationId(), 0, Stream.OUT, "before\n"));

        first.close();
        awaitRunOrphaned(runId);
        assertFalse(registry.lost(runId), "orphaned, not lost: the grace is running");

        try (FakeCiRunner back = FakeCiRunner.dial(runnerEndpoint)) {
          back.send(
              new Hello(
                  pins.version(),
                  CiRunnerProtocol.CAPABILITY_VERSION,
                  1,
                  new Capabilities(true, "amd64", "linux", Map.of()),
                  List.of(runId, "a-run-this-host-never-held")));
          eu.wohlben.qits.cirunner.protocol.Ack ack =
              back.next(eu.wohlben.qits.cirunner.protocol.Ack.class, SOON);
          assertEquals(List.of(runId), ack.adoptedRuns(), "only what this host was driving");

          daemon.send(new StepChunk(runStep.correlationId(), 1, Stream.OUT, "after\n"));
          daemon.send(new StepFinished(runStep.correlationId(), 0, false));
          Reap reap = back.next(Reap.class, SOON);
          assertNotNull(reap, "the step is reaped on the connection that came back");
          back.send(new Reaped(runId, 0));

          StepResult green = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
          assertEquals(StepOutcome.OK, green.outcome(), green.output());
          assertEquals(0, green.exitCode());
          assertTrue(green.output().contains("before"), green.output());
          assertTrue(green.output().contains("after"), green.output());
          assertFalse(green.output().contains("disconnected"), green.output());
        }
      }
    } finally {
      steps.runClosed(runId);
    }
  }

  /**
   * A runner that comes back WITHOUT the run — a restarted process, or a runner older than the claim
   * — did not keep its container, so the step is lost at that {@code Hello}, not a grace later.
   */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunnerThatComesBackWithoutTheRunLosesItAtItsHello() throws Exception {
    String runId = "runner-back-empty-" + UUID.randomUUID();
    try {
      FakeCiRunner first = greeted();
      registry.hold(registry.current(runnerId), runId);
      CompletableFuture<StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), new Recorder()));
      Launch launch = first.next(Launch.class, SOON);
      assertNotNull(launch);
      first.send(new Launched(runId, 0, "c0ffee"));

      try (FakeCiDaemon daemon = dialAsTheContainer(launch.workloadSpec())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(
                launch.workloadSpec().env().get("QITS_CI_DAEMON_ID"),
                CiDaemonProtocol.CAPABILITY_VERSION));
        assertInstanceOf(Ack.class, daemon.next(SOON));
        daemon.send(new Initialized());
        assertInstanceOf(RunStep.class, daemon.next(SOON));

        first.close();
        awaitRunOrphaned(runId);
        Instant backAt = Instant.now();
        try (FakeCiRunner back = greeted()) {
          StepResult lost = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
          assertEquals(StepOutcome.CONNECTION_LOST, lost.outcome());
          assertTrue(lost.output().contains("[runner " + RUNNER_NAME + " disconnected]"), lost.output());
          assertTrue(
              Duration.between(backAt, Instant.now()).toSeconds() < 10,
              "lost at the Hello, not when the minute's grace runs out");
          assertTrue(registry.lost(runId));
          assertNull(back.next(Reap.class, Duration.ofMillis(300)), "nothing of it is on this runner");
        }
      }
    } finally {
      steps.runClosed(runId);
    }
  }


  // --- a restart of this process (qits-1192) ------------------------------------------------------

  /**
   * The process is stopping while a step runs: its driver is interrupted, and the step must be left
   * exactly as it is — no Cancel to its daemon, no Reap to its runner — for the successor to take
   * back. Before qits-1192 the interrupted await read as "the daemon stopped answering", and the
   * dying process cancelled the step and removed its container.
   */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aStoppingProcessLeavesARunningStepAloneForItsSuccessor() throws Exception {
    String runId = "runner-stopping-" + UUID.randomUUID();
    Recorder listener = new Recorder();
    CompletableFuture<StepResult> result = new CompletableFuture<>();
    String daemonId = null;
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      Thread driver =
          new Thread(
              () -> {
                try {
                  result.complete(steps.run(step(runId), listener));
                } catch (Throwable t) {
                  result.completeExceptionally(t);
                }
              },
              "stopping-driver");
      driver.start();
      Launch launch = runner.next(Launch.class, SOON);
      assertNotNull(launch);
      daemonId = launch.workloadSpec().env().get("QITS_CI_DAEMON_ID");
      assertEquals(
          "launched:" + daemonId + "@" + CLIENT,
          listener.events.get(0),
          "the launch is reported before the runner is asked for the container");
      assertTrue(
          listener.events.stream().noneMatch(e -> e.contains("qits_tok_")),
          "what is recorded is the token's subject, never the token");
      runner.send(new Launched(runId, 0, "c0ffee"));

      try (FakeCiDaemon daemon = dialAsTheContainer(launch.workloadSpec())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
        assertInstanceOf(Ack.class, daemon.next(SOON));
        daemon.send(new Initialized());
        assertInstanceOf(RunStep.class, daemon.next(SOON));

        // What a shutdown does: ShutdownEvent raises the flag, then the drivers are interrupted.
        steps.stopping(true);
        driver.interrupt();

        Throwable thrown =
            org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> result.get(SOON.toSeconds(), TimeUnit.SECONDS));
        assertInstanceOf(CiStepRunner.ProcessStopping.class, thrown.getCause());
        assertNull(
            runner.nextMatching(m -> m instanceof Reap || m instanceof Released, Duration.ofMillis(500)),
            "the container and the run stay the runner's");
        assertFalse(
            daemon.next(Duration.ofMillis(300)) instanceof Cancel, "the step is not cancelled");
        assertEquals(
            CiDaemonRegistry.Phase.RUNNING,
            daemons.phaseOf(daemonId),
            "the launch is not reaped: the step is still running");
      }
    } finally {
      steps.stopping(false);
      if (daemonId != null) {
        daemons.reap(daemonId);
      }
      steps.runClosed(runId);
    }
  }

  /**
   * The successor's half: a run the previous process left on a runner is inherited, the runner
   * claims it in its Hello, and the step's daemon — still running, re-dialling — is admitted under
   * its old launch id and sent its step again. The result is collected; nothing is launched.
   */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anInheritedStepIsTakenBackWhenItsRunnerClaimsTheRun() throws Exception {
    String runId = "runner-inherited-" + UUID.randomUUID();
    String daemonId = UUID.randomUUID().toString();
    Recorder listener = new Recorder();
    CompletableFuture<StepResult> result = new CompletableFuture<>();
    CountDownLatch lost = new CountDownLatch(1);
    runTokens.liveSubjects.put(runId, java.util.Set.of(CLIENT));
    try {
      steps.inherit(
          new CiStepRunner.InheritedRun(
              runId,
              runnerId,
              new CiStepRunner.InFlightStep(
                  0, daemonId, CLIENT, Instant.now().minusSeconds(30), "echo on the runner", 60)),
          () -> {
            try {
              result.complete(steps.reattach(step(runId), listener));
            } catch (Throwable t) {
              result.completeExceptionally(t);
            }
          },
          lost::countDown);

      // The daemon re-dials before its runner is back, and is admitted as the launch it names —
      // once its recorded subject is confirmed as one of the run's live tokens.
      awaitPhase(daemonId);
      try (FakeCiDaemon daemon = FakeCiDaemon.dial(daemonEndpoint, Map.of())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
        assertInstanceOf(Ack.class, daemon.next(SOON));
        RunStep again = assertInstanceOf(RunStep.class, daemon.next(SOON));
        assertEquals("echo on the runner", again.script(), "sent again; a daemon that has it ignores it");

        try (FakeCiRunner back = FakeCiRunner.dial(runnerEndpoint)) {
          back.send(
              new Hello(
                  pins.version(),
                  CiRunnerProtocol.CAPABILITY_VERSION,
                  1,
                  new Capabilities(true, "amd64", "linux", Map.of()),
                  List.of(runId)));
          eu.wohlben.qits.cirunner.protocol.Ack ack =
              back.next(eu.wohlben.qits.cirunner.protocol.Ack.class, SOON);
          assertEquals(List.of(runId), ack.adoptedRuns(), "the run the previous process left");

          daemon.send(new StepChunk(again.correlationId(), 7, Stream.OUT, "published\n"));
          daemon.send(new StepFinished(again.correlationId(), 0, false));

          Reap reap = back.next(Reap.class, SOON);
          assertNotNull(reap, "the taken-back container is reaped as any step's is");
          back.send(new Reaped(runId, 0));
          StepResult green = result.get(SOON.toSeconds(), TimeUnit.SECONDS);
          assertEquals(StepOutcome.OK, green.outcome(), green.output());
          assertEquals(0, green.exitCode());
          assertTrue(green.output().contains("published"), green.output());
          assertTrue(green.output().contains("taken back"), green.output());
          assertFalse(listener.events.contains("started"), "the step was started by the predecessor");
          assertFalse(back.holds(Launch.class), "nothing is launched again");
          assertNotNull(back.next(Released.class, SOON), "the driver's end releases the run");
          assertEquals(1, lost.getCount(), "adopted, so never lost");
        }
      }
    } finally {
      steps.runClosed(runId);
    }
  }

  /** A runner that does not claim an inherited run within the grace: the run is settled, once. */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anInheritedRunNobodyClaimsIsLostWhenTheGraceEnds() throws Exception {
    String runId = "runner-inherited-lost-" + UUID.randomUUID();
    String daemonId = UUID.randomUUID().toString();
    CountDownLatch lost = new CountDownLatch(1);
    CountDownLatch adopted = new CountDownLatch(1);
    registry.reconnectGrace(Duration.ofMillis(300));
    runTokens.liveSubjects.put(runId, java.util.Set.of(CLIENT));
    try {
      steps.inherit(
          new CiStepRunner.InheritedRun(
              runId,
              runnerId,
              new CiStepRunner.InFlightStep(0, daemonId, CLIENT, null, "echo on the runner", 60)),
          adopted::countDown,
          lost::countDown);
      awaitPhase(daemonId);
      // A Hello that does not claim the run does not adopt it.
      try (FakeCiRunner other = greeted()) {
        assertTrue(lost.await(SOON.toSeconds(), TimeUnit.SECONDS), "lost when the grace ends");
        assertEquals(1, adopted.getCount());
        assertNull(daemons.phaseOf(daemonId), "and its launch record is dropped with it");
      }
    } finally {
      registry.reconnectGrace(null);
      steps.runClosed(runId);
    }
  }


  /** Fail closed: a recorded subject that is not one of the run's live tokens re-admits nothing. */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anInheritedLaunchWhoseSubjectIsNotLiveIsGivenUpAtOnce() throws Exception {
    assertGivenUpAtOnce(false);
  }

  /** Fail closed: a token listing that cannot be read re-admits nothing either. */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anInheritedLaunchIsGivenUpWhenTheTokenListingCannotBeRead() throws Exception {
    assertGivenUpAtOnce(true);
  }

  private void assertGivenUpAtOnce(boolean unreadable) throws Exception {
    String runId = "runner-inherited-untrusted-" + UUID.randomUUID();
    String daemonId = UUID.randomUUID().toString();
    CountDownLatch lost = new CountDownLatch(1);
    CountDownLatch adopted = new CountDownLatch(1);
    runTokens.liveSubjects.put(runId, java.util.Set.of("another-run's-subject"));
    runTokens.listingUnreadable = unreadable;
    try {
      steps.inherit(
          new CiStepRunner.InheritedRun(
              runId,
              runnerId,
              new CiStepRunner.InFlightStep(0, daemonId, CLIENT, Instant.now(), "echo x", 60)),
          adopted::countDown,
          lost::countDown);
      assertTrue(
          lost.await(SOON.toSeconds(), TimeUnit.SECONDS),
          "given up at once, not a minute's grace later");
      assertNull(daemons.phaseOf(daemonId), "no launch is registered on an unconfirmed record");
      try (FakeCiDaemon daemon = FakeCiDaemon.dial(daemonEndpoint, Map.of())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
        assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
        assertEquals("UNKNOWN_DAEMON", daemon.closeReason());
      }
      // A runner that claims it afterwards adopts nothing.
      try (FakeCiRunner back = FakeCiRunner.dial(runnerEndpoint)) {
        back.send(
            new Hello(
                pins.version(),
                CiRunnerProtocol.CAPABILITY_VERSION,
                1,
                new Capabilities(true, "amd64", "linux", Map.of()),
                List.of(runId)));
        assertEquals(
            List.of(),
            back.next(eu.wohlben.qits.cirunner.protocol.Ack.class, SOON).adoptedRuns());
      }
      assertEquals(1, adopted.getCount());
    } finally {
      runTokens.listingUnreadable = false;
      steps.runClosed(runId);
    }
  }

  /**
   * The re-admitted daemon is authenticated as its launch: a dial whose token subject is not the
   * recorded one is refused WRONG_RUN, and a Hello naming another launch id than the one recorded is
   * an unknown daemon — knowing the run is not enough.
   */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anInheritedLaunchAdmitsOnlyItsOwnSubjectAndLaunchId() throws Exception {
    String runId = "runner-inherited-auth-" + UUID.randomUUID();
    String daemonId = UUID.randomUUID().toString();
    String recordedSubject = "tok-ci-run-" + runId;
    runTokens.liveSubjects.put(runId, java.util.Set.of(recordedSubject));
    try {
      steps.inherit(
          new CiStepRunner.InheritedRun(
              runId,
              runnerId,
              new CiStepRunner.InFlightStep(
                  0, daemonId, recordedSubject, Instant.now(), "echo x", 60)),
          () -> {},
          () -> {});
      awaitPhase(daemonId);
      // This suite's dial arrives as CLIENT, which is not the recorded subject.
      try (FakeCiDaemon daemon = FakeCiDaemon.dial(daemonEndpoint, Map.of())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
        assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
        assertEquals("WRONG_RUN", daemon.closeReason());
      }
      try (FakeCiDaemon daemon = FakeCiDaemon.dial(daemonEndpoint, Map.of())) {
        daemon.send(
            new eu.wohlben.qits.cidaemon.protocol.Hello(
                UUID.randomUUID().toString(), CiDaemonProtocol.CAPABILITY_VERSION));
        assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
        assertEquals("UNKNOWN_DAEMON", daemon.closeReason());
      }
      assertFalse(daemons.connected(daemonId), "neither dial was bound to the launch");
    } finally {
      daemons.reap(daemonId);
      registry.loseInherited(runId);
      steps.runClosed(runId);
    }
  }

  /** Fail closed at launch: a launch that cannot be recorded is not made. */
  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:ci-run"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aLaunchThatCannotBeRecordedIsNotMade() throws Exception {
    String runId = "runner-unrecorded-" + UUID.randomUUID();
    Recorder refusing =
        new Recorder() {
          @Override
          public void onLaunched(String daemonId, String tokenSubject) {
            throw new IllegalStateException("the database is away");
          }
        };
    int launchesBefore = daemons.size();
    try (FakeCiRunner runner = greeted()) {
      registry.hold(registry.current(runnerId), runId);
      StepResult result =
          CompletableFuture.supplyAsync(() -> steps.run(step(runId), refusing))
              .get(SOON.toSeconds(), TimeUnit.SECONDS);
      assertEquals(StepOutcome.LAUNCH_FAILED, result.outcome());
      assertTrue(result.output().contains("could not be recorded"), result.output());
      assertNull(runner.next(Launch.class, Duration.ofMillis(500)), "the runner is asked for nothing");
      assertEquals(launchesBefore, daemons.size(), "and the minted launch is dropped");
    } finally {
      steps.runClosed(runId);
    }
  }

  private void awaitPhase(String daemonId) throws InterruptedException {
    Instant deadline = Instant.now().plus(SOON);
    while (daemons.phaseOf(daemonId) == null && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    assertNotNull(daemons.phaseOf(daemonId), "the launch is registered once its subject is confirmed");
  }

  /** Until the host has seen the close: the run's connection is gone and its grace is running. */
  private void awaitRunOrphaned(String runId) throws InterruptedException {
    Instant deadline = Instant.now().plus(SOON);
    while (Instant.now().isBefore(deadline) && registry.holding(runId).isOpen()) {
      Thread.sleep(20);
    }
    assertFalse(registry.holding(runId).isOpen(), "the host saw the runner's socket close");
  }

  // ---------------------------------------------------------------------------------------------

  private FakeCiRunner greeted() throws Exception {
    FakeCiRunner runner = FakeCiRunner.dial(runnerEndpoint);
    runner.send(
        new Hello(
            pins.version(),
            CiRunnerProtocol.CAPABILITY_VERSION,
            1,
            new Capabilities(true, "amd64", "linux", Map.of())));
    assertNotNull(runner.next(eu.wohlben.qits.cirunner.protocol.Ack.class, SOON));
    return runner;
  }

  /**
   * What the runner host's container does first: dial back. The upgrade arrives as this method's
   * {@code @TestSecurity} identity — what the edge would have forwarded for the run's token — and
   * the launch is named in the Hello each case sends next.
   */
  private FakeCiDaemon dialAsTheContainer(WorkloadSpec workload) throws Exception {
    assertEquals(CLIENT, workload.env().get("QITS_TOKEN_SUBJECT"), "the token this suite arrives as");
    return FakeCiDaemon.dial(daemonEndpoint, Map.of());
  }

  private static StepSpec step(String runId) {
    return new StepSpec(
        runId,
        0,
        CiRepoRef.of("runner-step-repo"),
        "main",
        "e".repeat(40),
        "alpine:3",
        "echo on the runner",
        "/artifacts/daemons/qits-ci-daemon/0.0.0-suite",
        60,
        false,
        false,
        "",
        Map.of());
  }

  /** {@link #step}'s launch, with the identity the runner was actually handed. */
  private static StepContainerSettings.LaunchSpec launchSpec(String runId, WorkloadSpec workload) {
    StepSpec step = step(runId);
    return new StepContainerSettings.LaunchSpec(
        runId,
        0,
        step.repo(),
        step.branch(),
        step.sha(),
        step.image(),
        workload.env().get("QITS_CI_DAEMON_ID"),
        step.daemonBinaryUrl(),
        step.timeoutSeconds(),
        false,
        false,
        "",
        Map.of());
  }

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

  private CiRun awaitTerminal(String runId) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(20);
    while (Instant.now().isBefore(deadline)) {
      CiRun run = QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
      if (run.status != CiRunStatus.RUNNING && run.status != CiRunStatus.QUEUED) {
        return run;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("run " + runId + " did not finish");
  }

  /** The step's events, in the order the seam fired them. */
  private static class Recorder implements StepListener {

    final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onStarted() {
      events.add("started");
    }

    @Override
    public void onChunk(String text) {
      events.add("chunk:" + text);
    }

    @Override
    public void onFinished() {
      events.add("finished");
    }

    @Override
    public void onLaunched(String daemonId, String tokenSubject) {
      events.add("launched:" + daemonId + "@" + tokenSubject);
    }
  }
}
