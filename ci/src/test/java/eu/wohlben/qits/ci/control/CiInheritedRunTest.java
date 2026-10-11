package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A qits-ci restart must not run a step a second time (qits-1192).
 *
 * <p>On 2026-10-11 two release runs of qits-workspace-daemon were mid-publish when qits-ci
 * redeployed itself. The dying process cancelled their steps, the successor's boot sweep re-queued
 * the runs with their steps deleted, and step 0 ran again: it rebuilt the daemon binary, and the
 * publish refused the second, different bytes under the version it had already stored. These tests
 * hold the engine's half of the fix, against the scripted seam:
 *
 * <ul>
 *   <li>a run a runner holds is <b>inherited</b> — it stays {@code RUNNING}, steps and all — and once
 *       the runner claims it the run continues: the in-flight step is taken back, never launched;
 *   <li>a run whose runner does not come back is settled — FAILED when it publishes, re-queued when
 *       it does not;
 *   <li>a step the stopping process gives up on settles nothing;
 *   <li>a launch is recorded on the run row before its container can exist;
 *   <li>a publishing run whose step lost its connection is not re-run by the automatic retry.
 * </ul>
 */
@QuarkusTest
public class CiInheritedRunTest extends CiTestSupport {

  private static final String THREE_STEPS =
      """
      steps:
        - image: alpine:3
          script: echo build
        - image: alpine:3
          script: echo publish
          timeout-seconds: 600
        - image: alpine:3
          script: echo announce
      """;

  @Inject CiRunService service;

  @Inject FakeRunAnnouncer announcer;

  private static final Instant STARTED = Instant.parse("2026-10-11T12:08:25Z");

  @BeforeEach
  void captured() {
    announcer.reset();
    fakeRunner.captureInheritance();
  }

  @Test
  public void aRunItsRunnerHoldsIsInheritedNotRestarted() throws Exception {
    String runId = insertRunning(null, true);

    service.sweepInterrupted();
    forgetLoadedEntities();

    CiRun row = service.requireRun(runId);
    assertEquals(CiRunStatus.RUNNING, row.status, "it waits for its runner; it is not re-queued");
    assertEquals(1, service.stepsFor(runId).size(), "the finished step keeps its row");
    assertEquals(1, fakeRunner.inheritances().size());
    CiStepRunner.InheritedRun inherited = fakeRunner.inheritances().get(0).run();
    assertEquals(runId, inherited.runId());
    assertEquals(suiteRunner.id(), inherited.runnerId());
    assertEquals(
        new CiStepRunner.InFlightStep(1, "daemon-1", "sub-run", STARTED, "echo publish", 600),
        inherited.inFlight(),
        "the in-flight step as the row recorded it, with its script for a daemon still owed it");
    assertTrue(fakeRunner.executed().isEmpty(), "nothing is launched before the runner is back");
  }

  @Test
  public void theInheritedRunContinuesAndTakesTheInFlightStepBack() throws Exception {
    String runId = insertRunning(null, true);
    service.sweepInterrupted();

    // The runner came back holding the run: its driver continues it.
    fakeRunner.inheritances().get(0).adopted().run();
    forgetLoadedEntities();

    assertEquals(1, fakeRunner.reattached().size());
    assertEquals(1, fakeRunner.reattached().get(0).stepIndex(), "the in-flight step is taken back");
    assertEquals(
        List.of(2),
        fakeRunner.executed().stream().map(CiStepRunner.StepSpec::stepIndex).toList(),
        "only the step after it is launched; neither step 0 nor step 1 runs again");
    CiRun row = service.requireRun(runId);
    assertEquals(CiRunStatus.SUCCESS, row.status);
    assertNull(row.inflightDaemonId, "a finished run carries no in-flight launch");
    List<CiStep> recorded = service.stepsFor(runId);
    assertEquals(3, recorded.size());
    assertEquals("built before the restart", recorded.get(0).output);
    assertEquals(CiStepStatus.SUCCESS, recorded.get(1).status);
    assertEquals(
        STARTED,
        recorded.get(1).startedAt.truncatedTo(ChronoUnit.SECONDS),
        "the taken-back step keeps the start the previous process recorded");
    assertEquals("fake-daemon-old", row.daemonVersion, "the run keeps the daemon it pinned");
  }

  @Test
  public void anInheritedRunWithoutAStepInFlightContinuesWithALaunch() throws Exception {
    // Between two steps when the process stopped: step 0 has its row, step 1 was never launched.
    String runId = insertRunning(null, false);
    service.sweepInterrupted();
    assertNull(fakeRunner.inheritances().get(0).run().inFlight());

    fakeRunner.inheritances().get(0).adopted().run();
    forgetLoadedEntities();

    assertTrue(fakeRunner.reattached().isEmpty());
    assertEquals(
        List.of(1, 2), fakeRunner.executed().stream().map(CiStepRunner.StepSpec::stepIndex).toList());
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(runId).status);
  }

  @Test
  public void aPublishingRunWhoseRunnerDoesNotComeBackFailsAndIsNotRunAgain() throws Exception {
    String runId = insertRunning(CiRunPhase.RELEASE, true);
    service.sweepInterrupted();

    fakeRunner.inheritances().get(0).lost().run();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    CiRun row = service.requireRun(runId);
    assertEquals(CiRunStatus.FAILED, row.status);
    assertNotNull(row.finishedAt);
    List<CiStep> recorded = service.stepsFor(runId);
    assertEquals(3, recorded.size());
    assertEquals(CiStepStatus.SUCCESS, recorded.get(0).status, "what ran before keeps its row");
    assertEquals(CiStepStatus.FAILED, recorded.get(1).status);
    assertTrue(
        recorded.get(1).output.contains("it is not run again"),
        "the step says why: " + recorded.get(1).output);
    assertTrue(recorded.get(1).output.contains("runner " + SuiteRunner.NAME), recorded.get(1).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(2).status);
    assertTrue(fakeRunner.executed().isEmpty(), "no step is launched again");
    assertEquals(1, runsOf(row.repoId).size(), "and nothing retries it behind a person's back");
    assertTrue(
        announcer.failed().stream().anyMatch(f -> f.runId().equals(runId)),
        "the gate hears the red");
  }

  @Test
  public void anOrdinaryRunWhoseRunnerDoesNotComeBackStartsOver() throws Exception {
    String runId = insertRunning(null, true);
    service.sweepInterrupted();

    fakeRunner.inheritances().get(0).lost().run();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    // Re-queued with its steps cleared, and a runner ran it again from the start, as every
    // interrupted run used to: it publishes nothing.
    CiRun row = service.requireRun(runId);
    assertEquals(CiRunStatus.SUCCESS, row.status);
    assertEquals(
        List.of(0, 1, 2), fakeRunner.executed().stream().map(CiStepRunner.StepSpec::stepIndex).toList());
    assertTrue(
        service.stepsFor(runId).stream().noneMatch(s -> "built before the restart".equals(s.output)),
        "the earlier attempt's rows are gone");
  }

  @Test
  public void aSeamThatCannotInheritSettlesAtOnce() throws Exception {
    // The default of the seam: nothing to take back, so the run is settled during the sweep.
    fakeRunner.reset();
    String runId = insertRunning(CiRunPhase.RELEASE, true);

    service.sweepInterrupted();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(CiRunStatus.FAILED, service.requireRun(runId).status);
    assertTrue(fakeRunner.executed().isEmpty());
  }

  @Test
  public void aStepTheStoppingProcessLeavesSettlesNothing() throws Exception {
    String repo = "stopping-" + UUID.randomUUID();
    fakeRunner.throwOn(1, new CiStepRunner.ProcessStopping("run", 1));

    executePipeline(repo, "main", "a".repeat(40), THREE_STEPS);
    forgetLoadedEntities();

    CiRun row = runsOf(repo).get(0);
    assertEquals(CiRunStatus.RUNNING, row.status, "left for the successor to take back");
    assertNull(row.finishedAt);
    assertEquals(1, service.stepsFor(row.id).size(), "no row for the step that is still running");
    assertTrue(fakeRunner.closed().isEmpty(), "nothing given back: the run is still going");
    assertTrue(announcer.failed().isEmpty(), "and no verdict");
    // Settled by hand, so no other suite's sweep finds it.
    QuarkusTransaction.requiringNew()
        .run(() -> runs.update("status = ?1 where id = ?2", CiRunStatus.CANCELLED, row.id));
  }

  @Test
  public void aLaunchIsRecordedOnTheRunRowWhileItsStepRuns() throws Exception {
    String repo = "launch-" + UUID.randomUUID();
    fakeRunner.launchAs("daemon", "sub-x");
    AtomicReference<CiRun> during = new AtomicReference<>();
    fakeRunner.during(
        1,
        spec ->
            during.set(QuarkusTransaction.requiringNew().call(() -> runs.findById(spec.runId()))));

    executePipeline(repo, "main", "b".repeat(40), THREE_STEPS);
    forgetLoadedEntities();

    CiRun seen = during.get();
    assertEquals(1, seen.inflightStepIndex);
    assertEquals("daemon-1", seen.inflightDaemonId);
    assertEquals("sub-x", seen.inflightTokenSubject);
    assertNotNull(seen.inflightStartedAt, "the step was sent, and the row says so");
    CiRun finished = runsOf(repo).get(0);
    assertEquals(CiRunStatus.SUCCESS, finished.status);
    assertNull(finished.inflightStepIndex);
    assertNull(finished.inflightDaemonId);
    assertNull(finished.inflightStartedAt);
  }

  @Test
  public void aPublishingRunWhoseStepLostItsConnectionIsNotRetriedAutomatically() throws Exception {
    String repo = "publish-lost-" + UUID.randomUUID();
    fakeRunner.scriptSequence(
        0, new StepResult(-1, false, StepOutcome.CONNECTION_LOST, "[runner gone]"));

    executePipeline(
        repo,
        "main",
        "c".repeat(40),
        """
        artifacts:
          - { type: docker, name: qits/qits-thing }
        steps:
          - image: alpine:3
            script: ./publish.sh
        """);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = runsOf(repo);
    assertEquals(1, all.size(), "no automatic retry: the step may have published part of it");
    assertEquals(CiRunStatus.FAILED, all.get(0).status);
    assertTrue(announcer.failed().stream().anyMatch(f -> f.runId().equals(all.get(0).id)));
  }

  // --- fixtures ---------------------------------------------------------------------------------

  private List<CiRun> runsOf(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> runs.list("repoId = ?1 order by createdAt", repoId));
  }

  /**
   * A three-step event run the previous process left {@code RUNNING} on the suite's runner: step 0
   * finished and has its row; step 1's launch is recorded when {@code inFlight}, as the previous
   * process records it before asking the runner for the container.
   */
  private String insertRunning(CiRunPhase phase, boolean inFlight) {
    String id = UUID.randomUUID().toString();
    String repoId = "inherited-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = repoId;
              run.branch = "main";
              run.commitSha = "9".repeat(40);
              run.status = CiRunStatus.RUNNING;
              run.createdAt = Instant.now().minusSeconds(600);
              run.startedAt = Instant.now().minusSeconds(590);
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = TEST_TRIGGER_PATH;
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = TEST_EVENT_NAME;
              run.triggerEventOccurredAt = Instant.parse("2026-10-11T12:08:00Z");
              run.triggerEventPayload = "{\"version\":\"2026.1011.115738\"}";
              run.triggerConfig = triggerFile(THREE_STEPS);
              run.phase = phase;
              run.runnerId = suiteRunner.id();
              run.daemonVersion = "fake-daemon-old";
              if (inFlight) {
                run.inflightStepIndex = 1;
                run.inflightDaemonId = "daemon-1";
                run.inflightTokenSubject = "sub-run";
                run.inflightStartedAt = STARTED;
              }
              runs.persist(run);

              CiStep done = new CiStep();
              done.id = UUID.randomUUID().toString();
              done.runId = id;
              done.stepIndex = 0;
              done.image = "alpine:3";
              done.status = CiStepStatus.SUCCESS;
              done.exitCode = 0;
              done.output = "built before the restart";
              done.startedAt = Instant.now().minusSeconds(580);
              done.finishedAt = Instant.now().minusSeconds(400);
              steps.persist(done);
            });
    return id;
  }
}
