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
 *   <li>a run that holds a lease is <b>inherited</b> — it stays {@code RUNNING}, steps and all — and
 *       once its daemon dials back the run continues on the same lease: the in-flight step is taken
 *       back, never sent again;
 *   <li>a run whose daemon does not come back is settled — FAILED when it publishes, re-queued when
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

  /** The run's launch id the previous process recorded; one this service mints, a UUID. */
  private static final String DAEMON_1 = "5b1f0c1e-6a43-4f3e-9d1a-0c8b2f7e1a11";

  /** The same lease's launch, as a row between two steps records it. */
  private static final String DAEMON_0 = DAEMON_1;

  /** The run's lease in qits-system. */
  private static final UUID LEASE = UUID.fromString("7d0c0f6e-2a1b-4c3d-9e8f-0a1b2c3d4e5f");

  @BeforeEach
  void captured() {
    announcer.reset();
    fakeRunner.captureInheritance();
  }

  @Test
  public void aRunItsLeaseHoldsIsInheritedNotRestarted() throws Exception {
    String runId = insertRunning(null, true);

    service.sweepInterrupted();
    forgetLoadedEntities();

    CiRun row = service.requireRun(runId);
    assertEquals(CiRunStatus.RUNNING, row.status, "it waits for its daemon; it is not re-queued");
    assertEquals(1, service.stepsFor(runId).size(), "the finished step keeps its row");
    assertEquals(1, fakeRunner.inheritances().size());
    CiStepRunner.InheritedRun inherited = fakeRunner.inheritances().get(0).run();
    assertEquals(runId, inherited.runId());
    assertEquals(LEASE, inherited.leaseId());
    assertEquals(DAEMON_1, inherited.daemonId(), "the run's one launch, to be admitted again");
    assertEquals("sub-run", inherited.tokenSubject());
    assertEquals(
        new CiStepRunner.InFlightStep(1, DAEMON_1, "sub-run", STARTED, "echo publish", 600),
        inherited.inFlight(),
        "the in-flight step as the row recorded it, with its script for a daemon still owed it");
    assertTrue(fakeRunner.executed().isEmpty(), "nothing is sent before the daemon is back");
    assertTrue(fakeRunner.revoked().isEmpty(), "its old token is still the running step's");
  }

  @Test
  public void theInheritedRunContinuesAndTakesTheInFlightStepBack() throws Exception {
    String runId = insertRunning(null, true);
    service.sweepInterrupted();

    // The daemon dialled back: the run's driver continues it.
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
    assertTrue(
        fakeRunner.revoked().isEmpty(),
        "the old token is the lease container's for every step left: the run's close deletes it");
    assertTrue(fakeRunner.closed().contains(runId));
  }

  @Test
  public void anInheritedRunWithoutAStepInFlightContinuesOnItsLease() throws Exception {
    // Between two steps when the process stopped: step 0 has its row, step 1 was never sent.
    String runId = insertRunning(null, false);
    service.sweepInterrupted();
    assertNull(fakeRunner.inheritances().get(0).run().inFlight());

    fakeRunner.inheritances().get(0).adopted().run();
    forgetLoadedEntities();

    assertTrue(fakeRunner.revoked().isEmpty(), "the lease container still holds the old token");
    assertTrue(fakeRunner.reattached().isEmpty());
    assertEquals(
        List.of(1, 2), fakeRunner.executed().stream().map(CiStepRunner.StepSpec::stepIndex).toList());
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(runId).status);
  }

  @Test
  public void aPublishingRunWhoseDaemonDoesNotComeBackFailsAndIsNotRunAgain() throws Exception {
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
    assertTrue(recorded.get(1).output.contains("did not come back"), recorded.get(1).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(2).status);
    assertTrue(fakeRunner.executed().isEmpty(), "no step is launched again");
    assertEquals(1, runsOf(row.repoId).size(), "and nothing retries it behind a person's back");
    assertTrue(
        announcer.failed().stream().anyMatch(f -> f.runId().equals(runId)),
        "the gate hears the red");
    assertEquals(List.of(runId), fakeRunner.revoked(), "and its old token is revoked");
  }

  @Test
  public void anOrdinaryRunWhoseDaemonDoesNotComeBackStartsOver() throws Exception {
    String runId = insertRunning(null, true);
    service.sweepInterrupted();

    fakeRunner.inheritances().get(0).lost().run();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    // Re-queued with its steps cleared, and a lease ran it again from the start, as every
    // interrupted run used to: it publishes nothing.
    CiRun row = service.requireRun(runId);
    assertEquals(CiRunStatus.SUCCESS, row.status);
    assertEquals(
        List.of(0, 1, 2), fakeRunner.executed().stream().map(CiStepRunner.StepSpec::stepIndex).toList());
    assertTrue(
        service.stepsFor(runId).stream().noneMatch(s -> "built before the restart".equals(s.output)),
        "the earlier attempt's rows are gone");
    assertTrue(fakeRunner.revoked().contains(runId), "and its old token is revoked");
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
    assertEquals(List.of(runId), fakeRunner.revoked());
  }

  @Test
  public void aRunCancelledWhileItsDaemonWasAwayIsOnlyClosedAndItsTokenRevoked() throws Exception {
    String runId = insertRunning(CiRunPhase.RELEASE, true);
    service.sweepInterrupted();
    service.cancel(runId);

    fakeRunner.inheritances().get(0).adopted().run();
    forgetLoadedEntities();

    assertEquals(CiRunStatus.CANCELLED, service.requireRun(runId).status);
    assertTrue(fakeRunner.reattached().isEmpty() && fakeRunner.executed().isEmpty());
    assertEquals(List.of(runId), fakeRunner.revoked());
    assertTrue(fakeRunner.closed().contains(runId));
  }

  // --- fail closed: a launch record that does not add up takes nothing back ---------------------

  @Test
  public void anIncompleteLaunchRecordIsSettledNotTakenBack() throws Exception {
    assertSettledAtBoot(row -> row.inflightTokenSubject = null, "incomplete");
    assertSettledAtBoot(row -> row.inflightDaemonId = null, "incomplete");
    assertSettledAtBoot(row -> row.inflightTokenSubject = " ", "incomplete");
  }

  @Test
  public void aLaunchIdThisServiceNeverMintsIsSettled() throws Exception {
    assertSettledAtBoot(row -> row.inflightDaemonId = "daemon-1", "launch id");
  }

  @Test
  public void aLaunchNamingAStepOutOfRangeIsSettled() throws Exception {
    assertSettledAtBoot(row -> row.inflightStepIndex = 2, "names step 2");
    assertSettledAtBoot(row -> row.inflightStepIndex = 7, "names step 7");
    assertSettledAtBoot(row -> row.inflightStepIndex = -1, "names step -1");
  }

  @Test
  public void aRunThatHoldsNoLeaseIsSettled() throws Exception {
    // What the cutover finds: a run a retired CI runner held (epic qits-1481).
    assertSettledAtBoot(row -> row.leaseId = null, "holds no lease");
  }

  @Test
  public void aResumingRetryThatLaunchedNothingYetIsInheritedAtItsFirstOwnStep() throws Exception {
    // A release retry carries its source's green prefix as rows written at accept (qits-1222), so
    // "before the first launch" is "only the carried rows", not "no rows at all". Such a run is taken
    // back and continues after them; refusing it would fail a release retry that ran nothing.
    String runId = insertRunning(CiRunPhase.RELEASE, false);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun row = runs.findById(runId);
              row.carriedSteps = 1;
              // The lease's launch is recorded when it is asked for; no step was sent yet.
              row.inflightStepIndex = null;
              row.inflightStartedAt = null;
            });

    service.sweepInterrupted();
    assertEquals(1, fakeRunner.inheritances().size(), "it is taken back, not settled");
    assertNull(fakeRunner.inheritances().get(0).run().inFlight());

    fakeRunner.inheritances().get(0).adopted().run();
    forgetLoadedEntities();

    assertEquals(
        List.of(1, 2), fakeRunner.executed().stream().map(CiStepRunner.StepSpec::stepIndex).toList(),
        "the carried step is never sent");
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(runId).status);
  }

  @Test
  public void aRunThatMayHaveLaunchedWithoutARecordIsSettled() throws Exception {
    // A finished step and a launch that names no step: one may have been sent and not recorded.
    assertSettledAtBoot(
        row -> {
          row.inflightStepIndex = null;
          row.inflightStartedAt = null;
        },
        "none is recorded");
  }

  @Test
  public void aRunWhoseStepRowsHoldARedOneIsSettled() throws Exception {
    // A run that had stopped on a red step: continuing it would announce a verdict it never reached.
    String runId = insertRunning(null, true);
    QuarkusTransaction.requiringNew()
        .run(() -> steps.update("status = ?1 where runId = ?2", CiStepStatus.FAILED, runId));
    service.sweepInterrupted();
    suiteRunner.awaitIdle();
    assertTrue(fakeRunner.inheritances().isEmpty());
    assertTrue(fakeRunner.revoked().contains(runId));
  }

  /**
   * One boot with a corrupted launch record, twice — a publishing run fails and an ordinary one is
   * re-queued — and in both nothing is handed to the seam to take back, and the old token is revoked.
   */
  private void assertSettledAtBoot(java.util.function.Consumer<CiRun> corrupt, String why)
      throws Exception {
    for (CiRunPhase phase : new CiRunPhase[] {CiRunPhase.RELEASE, null}) {
      fakeRunner.reset();
      fakeRunner.captureInheritance();
      String runId = insertRunning(phase, true);
      QuarkusTransaction.requiringNew().run(() -> corrupt.accept(runs.findById(runId)));
      CiRun row = QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
      CiRunService.Inheritance inheritance =
          QuarkusTransaction.requiringNew().call(() -> service.inheritanceOf(row));
      assertTrue(!inheritance.sound(), why + ": refused");
      assertTrue(inheritance.unsound().contains(why), inheritance.unsound());

      service.sweepInterrupted();
      suiteRunner.awaitIdle();
      forgetLoadedEntities();

      assertTrue(fakeRunner.inheritances().isEmpty(), why + ": nothing is taken back");
      assertTrue(fakeRunner.reattached().isEmpty(), why);
      assertTrue(fakeRunner.revoked().contains(runId), why + ": its old token is revoked");
      CiRun settled = service.requireRun(runId);
      if (phase == CiRunPhase.RELEASE) {
        assertEquals(CiRunStatus.FAILED, settled.status, why + ": a publishing run fails");
        assertTrue(
            fakeRunner.executed().stream().noneMatch(spec -> spec.runId().equals(runId)),
            why + ": and runs nothing");
      } else {
        assertEquals(CiRunStatus.SUCCESS, settled.status, why + ": an ordinary run starts over");
        assertEquals(
            List.of(0, 1, 2),
            fakeRunner.executed().stream()
                .filter(spec -> spec.runId().equals(runId))
                .map(CiStepRunner.StepSpec::stepIndex)
                .toList());
      }
    }
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
   * A three-step event run the previous process left {@code RUNNING} in a lease: step 0 finished and
   * has its row; the run's launch names step 1 when {@code inFlight}, as the previous process records
   * it before it sends the step, and step 0 otherwise.
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
              run.leaseId = LEASE;
              run.daemonVersion = "fake-daemon-old";
              if (inFlight) {
                run.inflightStepIndex = 1;
                run.inflightDaemonId = DAEMON_1;
                run.inflightTokenSubject = "sub-run";
                run.inflightStartedAt = STARTED;
              } else {
                // Between two steps: the launch of step 0, which already has its row.
                run.inflightStepIndex = 0;
                run.inflightDaemonId = DAEMON_0;
                run.inflightTokenSubject = "sub-run";
                run.inflightStartedAt = STARTED.minusSeconds(300);
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
