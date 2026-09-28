package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.api.MachineGuardTest;
import eu.wohlben.qits.ci.control.CiEventTriggerParser;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiStepRunner.StepListener;
import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.control.CiStepRunner.StepSpec;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher;
import eu.wohlben.qits.ci.daemonhost.FakeCiDaemon;
import eu.wohlben.qits.ci.daemonhost.StepWorkloadSpecs;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import eu.wohlben.qits.cidaemon.protocol.Ack;
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
 * <p>The identity is the test's {@code @TestSecurity}, carrying both roles, because Quarkus applies
 * it to every upgrade in the method — the runner's and the daemon's. The gate is on for the runner
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

  @Inject CiDaemonLauncher launcher;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunRepository runs;

  @Inject CiStepRepository stepRows;

  @Inject CiRunService runService;

  @Inject CiEventTriggerParser triggerParser;

  @Inject FakeCiStepRunner localSteps;

  private UUID runnerId;

  @BeforeEach
  void declareARegisteredRunner() {
    // Create the bean HERE, on the test thread. The cases call steps.run from supplyAsync, and a
    // ForkJoin common-pool thread carries the system class loader as its TCCL: a bean first created
    // there has its @ConfigProperty fields resolved against a Config that holds none of the ci jar's
    // keys, so the register, init and grace deadlines all read 0 and a step fails NEVER_STARTED
    // before its daemon has dialled. Measured 2026-09-28 in a narrow run (-Dtest=RunnerStepRunnerTest).
    steps.owns("warm-up");
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
              runner.plane = CiRunnerPlane.INTERNAL;
              runner.clientId = CLIENT;
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
            });
  }

  @AfterEach
  void forgetTheRunner() {
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  // --- the step, through the seam -----------------------------------------------------------------

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:system"})
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
      assertEquals(CiDaemonLauncher.containerName(runId, 0), workload.name());
      assertEquals(Map.of("qits.ci.run", runId), workload.labels());
      assertTrue(
          workload.labels().keySet().stream().noneMatch(k -> k.startsWith("qits.ci.runner")),
          "the runner refuses its own namespace in a spec, and stamps it itself");
      // The local composition, field for field: a step on a runner is the same step.
      assertEquals(
          RunnerStepRunner.workloadSpec(
              StepWorkloadSpecs.compose(
                  launcher.workloadSettings(), launcher.internalPlane(), launchSpec(runId, workload), null),
              false),
          workload);
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
        assertEquals(List.of("started", "chunk:on the runner\n", "finished"), listener.events);
      }
    } finally {
      steps.runClosed(runId);
    }
  }

  @Test
  @TestSecurity(user = "runner", roles = {CiRunnerSocket.RUNNER_ROLE, "qits:system"})
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

  // --- a runner that vanishes mid-step, end to end ------------------------------------------------

  @Test
  @TestSecurity(
      user = "runner",
      roles = {CiRunnerSocket.RUNNER_ROLE, "qits:system", "qits:admin"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRunnerVanishingMidStepFailsTheRunNamingItAndTheRunIsRetryable() throws Exception {
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
      // The local worker is parked in a run of its own, so the one accepted next is the runner's.
      accept("runner-vanish-blocker");
      parked.get(30, TimeUnit.SECONDS);
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
            "a vanished runner ends the step at once, not at a deadline");
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
      releaseLocal.countDown();
    }
  }

  // ---------------------------------------------------------------------------------------------

  private FakeCiRunner greeted() throws Exception {
    FakeCiRunner runner = FakeCiRunner.dial(runnerEndpoint);
    runner.send(
        new Hello(
            "runner-test",
            CiRunnerProtocol.CAPABILITY_VERSION,
            1,
            new Capabilities(true, "amd64", "linux", Map.of())));
    assertNotNull(runner.next(eu.wohlben.qits.cirunner.protocol.Ack.class, SOON));
    return runner;
  }

  /** What the runner host's container does first: dial back with the credentials it was handed. */
  private FakeCiDaemon dialAsTheContainer(WorkloadSpec workload) throws Exception {
    return FakeCiDaemon.dial(
        daemonEndpoint,
        workload.env().get("QITS_CI_DAEMON_ID"),
        workload.env().get("QITS_CI_DAEMON_SECRET"));
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
        "http://daemon.invalid/qits-ci-daemon",
        60,
        false,
        false,
        "",
        Map.of());
  }

  /** {@link #step}'s launch, with the identity the runner was actually handed. */
  private static CiDaemonLauncher.LaunchSpec launchSpec(String runId, WorkloadSpec workload) {
    StepSpec step = step(runId);
    return new CiDaemonLauncher.LaunchSpec(
        runId,
        0,
        step.repo(),
        step.branch(),
        step.sha(),
        step.image(),
        workload.env().get("QITS_CI_DAEMON_ID"),
        workload.env().get("QITS_CI_DAEMON_SECRET"),
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
  private static final class Recorder implements StepListener {

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
  }
}
