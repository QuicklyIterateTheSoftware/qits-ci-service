package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.daemonhost.ScriptedLeaseDaemon;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.Close;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The run driver over one lease</b> (epic qits-1481, qits-1544): every step of a run is a {@code
 * RunStep} on the one connection of the lease's daemon, and the run's close is a {@code Close} and
 * the lease ended. Plain JUnit against a real {@link CiDaemonRegistry}, with a scripted daemon
 * ({@link ScriptedLeaseDaemon}) answering each step on its one connection, and the lease seam's own
 * bookkeeping ({@link CiLeases}) stood in for.
 */
class LeaseStepRunnerTest {

  private static final String RUN = "3c0d2f4e-7a5b-4c6d-8e9f-0a1b2c3d4e5f";

  private static final String SUBJECT = "tok-ci-run-" + RUN + "-1";

  private static final UUID LEASE = UUID.fromString("7d0c0f6e-2a1b-4c3d-9e8f-0a1b2c3d4e5f");

  private final CiDaemonRegistry registry = ScriptedLeaseDaemon.registry(Duration.ofSeconds(30));

  private final List<String> ended = new ArrayList<>();

  private final StandInLeases leases = new StandInLeases();

  private final LeaseStepRunner runner = runner();

  private ScriptedLeaseDaemon daemon;

  /** {@link CiLeases}, holding exactly one launch and recording the lease it is asked to end. */
  private final class StandInLeases extends CiLeases {
    volatile Launch launch;

    @Override
    public Optional<Launch> launchOf(String runId) {
      return RUN.equals(runId) ? Optional.ofNullable(launch) : Optional.empty();
    }

    @Override
    public void end(String runId) {
      ended.add(runId);
    }

    @Override
    public boolean drives(String runId) {
      return false;
    }
  }

  private LeaseStepRunner runner() {
    LeaseStepRunner runner = new LeaseStepRunner();
    runner.daemons = registry;
    runner.relay = new CiStepRelay();
    runner.commissions = new RunCommissions();
    runner.settings = SlotFixtures.shippedSettings();
    runner.leases = leases;
    runner.registerTimeoutSeconds = 5;
    runner.initTimeoutSeconds = 5;
    runner.stepTimeoutGraceSeconds = 5;
    return runner;
  }

  @AfterEach
  void hangUp() {
    if (daemon != null) {
      daemon.close();
    }
  }

  private static CiStepRunner.StepSpec step(int index, String script) {
    return new CiStepRunner.StepSpec(
        RUN,
        index,
        CiRepoRef.of("5ca0e7e9-repo", "qits", "qits-ci-service"),
        "main",
        "c".repeat(40),
        SlotFixtures.PINNED,
        script,
        "/artifacts/daemons/qits-ci-daemon/2026.1",
        60,
        false,
        index == 2,
        "",
        Map.of());
  }

  /** Records what the seam reports about one step, and the launch it was recorded under. */
  private static final class Listened implements CiStepRunner.StepListener {
    final List<String> chunks = new ArrayList<>();
    String launchedAs;
    boolean started;
    boolean finished;

    @Override
    public void onStarted() {
      started = true;
    }

    @Override
    public void onChunk(String text) {
      chunks.add(text);
    }

    @Override
    public void onFinished() {
      finished = true;
    }

    @Override
    public void onLaunched(String daemonId, String tokenSubject) {
      launchedAs = daemonId + "/" + tokenSubject;
    }
  }

  @Test
  void threeStepsRunOverOneDaemonConnectionAndTheCloseEndsTheLease() {
    String daemonId = registry.registerLaunch(RUN, SUBJECT);
    leases.launch = new CiLeases.Launch(LEASE, daemonId, SUBJECT);
    daemon = ScriptedLeaseDaemon.dial(registry, daemonId, SUBJECT);

    List<CiStepRunner.StepResult> results = new ArrayList<>();
    for (int index = 0; index < 3; index++) {
      Listened listened = new Listened();
      results.add(runner.run(step(index, "./step-" + index + ".sh"), listened));
      assertEquals(List.of("step " + index + " ran\n"), listened.chunks);
      assertTrue(listened.started && listened.finished);
      assertEquals(daemonId + "/" + SUBJECT, listened.launchedAs, "the run's one launch, every step");
    }

    for (CiStepRunner.StepResult result : results) {
      assertEquals(CiStepRunner.StepOutcome.OK, result.outcome());
      assertEquals(0, result.exitCode());
    }
    // ONE connection carried every step, in order, each naming its index and its own env.
    List<RunStep> sent =
        daemon.received().stream()
            .filter(RunStep.class::isInstance)
            .map(RunStep.class::cast)
            .toList();
    assertEquals(List.of(0, 1, 2), sent.stream().map(RunStep::stepIndex).toList());
    assertEquals(
        List.of("./step-0.sh", "./step-1.sh", "./step-2.sh"),
        sent.stream().map(RunStep::script).toList());
    for (RunStep each : sent) {
      assertEquals(
          Map.of("QITS_CI_STEP_INDEX", String.valueOf(each.stepIndex())), each.env());
    }
    assertEquals(1, registry.size(), "one launch for the whole run, not one per step");

    runner.runClosed(RUN);

    List<CiDaemonMessage> all = daemon.received();
    assertInstanceOf(Close.class, all.getLast(), "the run's close is a Close to its daemon");
    assertTrue(daemon.closed(), "and its connection is let go");
    assertEquals(0, registry.size());
    assertEquals(List.of(RUN), ended, "and the lease is ended");
  }

  @Test
  void aLeaseThatFailedBeforeItsDaemonDialledIsTheFirstStepsLaunchFailedWithTheLeasesWords() {
    String daemonId = registry.registerLaunch(RUN, SUBJECT);
    leases.launch = new CiLeases.Launch(LEASE, daemonId, SUBJECT);

    runner.leaseEnded(RUN, "lease " + LEASE + " FAILED: image not allowed");
    CiStepRunner.StepResult result = runner.run(step(0, "./verify.sh"), new Listened());

    assertEquals(CiStepRunner.StepOutcome.LAUNCH_FAILED, result.outcome());
    assertEquals("lease " + LEASE + " FAILED: image not allowed", result.output());
  }

  @Test
  void aRunWithNoLeaseLaunchIsLaunchFailedRatherThanAWait() {
    CiStepRunner.StepResult result = runner.run(step(0, "./verify.sh"), new Listened());
    assertEquals(CiStepRunner.StepOutcome.LAUNCH_FAILED, result.outcome());
    assertEquals(LeaseStepRunner.NO_LEASE, result.output());
  }
}
