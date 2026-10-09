package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPurpose;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A runner's quarantine and its health check (qits-466), against the real orchestrator, a real
 * database and the {@code ci} module's fakes: {@link FakeCiStepRunner} scripts what a runner's
 * steps answer, {@link FakeRunnerPresence} says which runners are connected, {@link
 * RecordingRunnerSignals} records what a connected runner would be told and {@link
 * RecordingRunnerEvents} what is announced.
 *
 * <p>A runner's run is driven exactly as its socket drives one — {@link CiRunService#reserveFor}
 * then {@link CiRunService#executeReserved} — so every count and every settled check below went
 * through {@code runSteps}, not through a helper that stood in for it.
 */
@QuarkusTest
public class CiRunnerHealthTest extends CiTestSupport {

  private static final String SHA = "d".repeat(40);

  private static final String HEAD = "e".repeat(40);

  private static final String PLAIN = "steps:\n  - image: alpine:3\n    script: echo plain\n";

  /** The shipped qits.ci.runner.healthcheck.repository. */
  private static final String HEALTH_REPO = "qits-ci-service";

  @Inject CiRunService service;

  @Inject CiRunnerHealth health;

  @Inject CiRunners runners;

  @Inject CiRunnerRepository runnerRows;

  @Inject FakeRunnerPresence presence;

  @Inject RecordingRunnerSignals signals;

  @Inject RecordingRunnerEvents events;

  @Inject FakeRunAnnouncer runAnnouncer;

  @BeforeEach
  void clean() {
    presence.reset();
    signals.reset();
    events.reset();
    runAnnouncer.reset();
    // Every streak here is staged from infra failures, and an automatic retry of each would be a
    // QUEUED run the next reservation takes instead of the build the test accepted. The retry is
    // CiAutoRetryTest's subject; off for this class, back on after it.
    service.autoRetryMax(0);
    // Every runner row, the SuiteRunner's included: the runners here are each case's own, and a run
    // is reserved by exactly the one the case names.
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
    fakeCandidates.setRefs(CiRepoRef.of("repo-" + HEALTH_REPO, "qits", HEALTH_REPO));
    fakeConfig.putTriggers("repo-" + HEALTH_REPO, "main", HEAD);
  }

  @AfterEach
  void settle() throws Exception {
    service.autoRetryMax(CiRunService.AUTO_RETRY_MAX);
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  // --- the streak ---------------------------------------------------------------------------------

  @Test
  public void aRunnerCausedFailureCountsAndAStepThatStartedResetsTheStreak() throws Exception {
    CiRunner runner = runner("flaky", 3);

    fakeRunner.answer(spec -> failed(StepOutcome.LAUNCH_FAILED, "pull access denied"));
    String first = reserveAndRun(runner, "streak-a");
    fakeRunner.answer(spec -> failed(StepOutcome.NEVER_STARTED, "never dialled back"));
    String second = reserveAndRun(runner, "streak-b");

    CiRunner counted = row(runner.id);
    assertEquals(2, counted.infraFailures);
    assertEquals(List.of(first, second), InfraFailureRuns.decode(counted.infraFailureRuns));
    assertFalse(counted.quarantined(), "two failures are short of the shipped three");

    // A step whose daemon dialled back ends the streak — even one whose script then failed.
    fakeRunner.answer(spec -> new StepResult(1, false, StepOutcome.OK, "tests failed\n"));
    reserveAndRun(runner, "streak-c");

    CiRunner reset = row(runner.id);
    assertEquals(0, reset.infraFailures);
    assertNull(reset.infraFailureRuns);
    assertFalse(reset.quarantined());
  }

  /** What buildkit says on a runner whose user namespace cannot map a layer's owner (qits-556). */
  static final String LCHOWN =
      "#7 extracting sha256:eb074df0155e\n"
          + "ERROR: failed to solve: failed to Lchown \"/tmp/containerd-mount1/opt/jdtls/bin\" for"
          + " UID 1001380000, GID 1001380000: lchown /tmp/containerd-mount1/opt/jdtls/bin: invalid"
          + " argument (Hint: try increasing the number of subordinate IDs in /etc/subuid and"
          + " /etc/subgid)\n";

  @Test
  public void aBuilderThatCannotMapAnIdIsTheRunnersFaultAndQuarantinesIt() throws Exception {
    CiRunner runner = runner("narrow-ids", 3);
    fakeRunner.answer(spec -> new StepResult(1, false, StepOutcome.OK, LCHOWN));

    reserveAndRun(runner, "narrow-a");
    CiRunner counted = row(runner.id);
    assertEquals(1, counted.infraFailures, "a step that ran and exited 1 still counts");
    reserveAndRun(runner, "narrow-b");
    String third = reserveAndRun(runner, "narrow-c");

    CiRunner quarantined = row(runner.id);
    assertTrue(quarantined.quarantined());
    assertEquals(
        "3 consecutive runner failures (\"failed to Lchown\" on run " + third + ")",
        quarantined.quarantineReason);
  }

  @Test
  public void anInfraSignatureCountsOnlyAtTheTailOfAStepThatRanAndFailed() {
    assertTrue(CiRunnerHealth.isInfra(new StepResult(1, false, StepOutcome.OK, LCHOWN)));
    assertEquals(
        "failed to Lchown",
        CiRunnerHealth.infraSignature(new StepResult(1, false, StepOutcome.OK, LCHOWN)));
    for (String signature : CiRunnerHealth.INFRA_SIGNATURES) {
      assertEquals(
          signature,
          CiRunnerHealth.infraSignature(
              new StepResult(2, false, StepOutcome.OK, "building\nerror: " + signature + "\n")));
    }
    // An outcome is judged as it always was.
    assertTrue(CiRunnerHealth.isInfra(failed(StepOutcome.CONNECTION_LOST, "")));
    assertFalse(CiRunnerHealth.isInfra(failed(StepOutcome.SHA_GONE, LCHOWN)));
    // A green step is green, a timeout is a timeout, and an ordinary red is the build's.
    assertFalse(CiRunnerHealth.isInfra(new StepResult(0, false, StepOutcome.OK, LCHOWN)));
    assertFalse(CiRunnerHealth.isInfra(new StepResult(143, true, StepOutcome.OK, LCHOWN)));
    assertFalse(CiRunnerHealth.isInfra(new StepResult(1, false, StepOutcome.OK, "tests failed\n")));
    assertFalse(CiRunnerHealth.isInfra(new StepResult(1, false, StepOutcome.OK, null)));
    // A signature the build printed and then survived, far above its own last words, is not one.
    String survived =
        LCHOWN + "retrying with --no-same-owner\n".repeat(CiRunnerHealth.SIGNATURE_TAIL_LINES + 5)
            + "[ERROR] Tests run: 3, Failures: 1\n";
    assertFalse(CiRunnerHealth.isInfra(new StepResult(1, false, StepOutcome.OK, survived)));
  }

  @Test
  public void theStreakMustSpanTwoRunsSoOneBadRecipeCannotQuarantineAHealthyRunner() {
    CiRunner runner = runner("one-recipe", 1);

    for (int i = 0; i < 4; i++) {
      assertFalse(
          runners.recordInfraFailure(runner.id, "the-same-run", "LAUNCH_FAILED", 3, 2).quarantined(),
          "four failures of ONE run are still one run's recipe");
    }
    CiRunners.StepRecorded second =
        runners.recordInfraFailure(runner.id, "another-run", "LAUNCH_FAILED", 3, 2);

    assertTrue(second.quarantined());
    assertEquals(
        "5 consecutive runner failures (LAUNCH_FAILED on run another-run)",
        row(runner.id).quarantineReason);
  }

  /**
   * qits-748: one edge redeploy ends every run a runner holds CONNECTION_LOST in the same instant.
   * That is one disconnect, and it is one failure — six held runs used to be six, and quarantined a
   * healthy 6-slot runner from a single blip.
   */
  @Test
  public void sixConnectionLossesOnSixRunsInsideTheWindowAreOneFailure() throws Exception {
    CiRunner runner = runner("six-slots", 6);
    fakeRunner.answer(spec -> failed(StepOutcome.CONNECTION_LOST, "[the connection was lost]"));

    String first = reserveAndRun(runner, "blip-0");
    for (int i = 1; i < 6; i++) {
      reserveAndRun(runner, "blip-" + i);
    }

    CiRunner counted = row(runner.id);
    assertEquals(1, counted.infraFailures, "one disconnect, one failure");
    assertEquals(List.of(first), InfraFailureRuns.decode(counted.infraFailureRuns));
    assertFalse(counted.quarantined());
    assertNotNull(counted.connectionLossWindowStart);
    assertEquals(List.of(), events.of(runner.id.toString()), "nothing quarantined, nothing said");

    // Any other runner-caused outcome still counts per step inside the window.
    fakeRunner.answer(spec -> failed(StepOutcome.NEVER_STARTED, "never dialled back"));
    reserveAndRun(runner, "blip-never");
    assertEquals(2, row(runner.id).infraFailures);

    // And a step that started resets the window with the streak.
    fakeRunner.answer(spec -> new StepResult(0, false, StepOutcome.OK, "ok\n"));
    reserveAndRun(runner, "blip-ok");
    assertNull(row(runner.id).connectionLossWindowStart);
  }

  @Test
  public void threeConnectionLossesSpacedBeyondTheWindowQuarantineAsBefore() throws Exception {
    CiRunner runner = runner("flapping", 1);
    fakeRunner.answer(spec -> failed(StepOutcome.CONNECTION_LOST, "[the connection was lost]"));

    String third = null;
    for (int i = 0; i < 3; i++) {
      third = reserveAndRun(runner, "flap-" + i);
      // The counted loss is three minutes old by the next one: outside the shipped two-minute window.
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                CiRunner row = runnerRows.findById(runner.id);
                if (row.connectionLossWindowStart != null) {
                  row.connectionLossWindowStart =
                      row.connectionLossWindowStart.minus(Duration.ofMinutes(3));
                }
              });
    }

    CiRunner quarantined = row(runner.id);
    assertEquals(3, quarantined.infraFailures);
    assertTrue(quarantined.quarantined());
    assertEquals(
        "3 consecutive runner failures (CONNECTION_LOST on run " + third + ")",
        quarantined.quarantineReason);
  }

  @Test
  public void aGreenlightClearsTheConnectionLossWindowWithTheStreak() {
    CiRunner runner = runner("window-reset", 1);
    runners.recordInfraFailure(runner.id, "r1", "CONNECTION_LOST", true, Duration.ofMinutes(2), 3, 2);
    assertNotNull(row(runner.id).connectionLossWindowStart);
    // Inside the window: collapsed.
    assertTrue(
        runners
            .recordInfraFailure(runner.id, "r2", "CONNECTION_LOST", true, Duration.ofMinutes(2), 3, 2)
            .collapsed());

    health.greenlight(runner.id);

    assertNull(row(runner.id).connectionLossWindowStart);
    assertFalse(
        runners
            .recordInfraFailure(runner.id, "r3", "CONNECTION_LOST", true, Duration.ofMinutes(2), 3, 2)
            .collapsed(),
        "a new streak counts its first loss");
  }

  @Test
  public void threeFailuresOverTwoRunsQuarantineAndAQuarantinedRunnerTakesNoWork()
      throws Exception {
    CiRunner runner = runner("broken", 2);
    fakeRunner.answer(spec -> failed(StepOutcome.NEVER_STARTED, "never dialled back"));

    reserveAndRun(runner, "broken-a");
    reserveAndRun(runner, "broken-b");
    String third = reserveAndRun(runner, "broken-c");

    CiRunner quarantined = row(runner.id);
    assertTrue(quarantined.quarantined());
    assertEquals(
        "3 consecutive runner failures (NEVER_STARTED on run " + third + ")",
        quarantined.quarantineReason);
    assertEquals(2, quarantined.slots, "the operator's number is kept for the reinstatement");
    assertEquals(0, health.effectiveSlots(runner.id), "its Ack carries 0");
    assertEquals(
        List.of("quarantined:" + quarantined.quarantineReason), signals.of(runner.id),
        "the connected runner is told once");
    assertEquals(
        List.of("RunnerQuarantined " + quarantined.quarantineReason),
        events.of(runner.id.toString()));

    // Work is queued and the runner has two free slots: every ordinary Reserve is a Nothing.
    String waiting = accept("broken-d");
    assertTrue(service.reserveFor(runner).isEmpty());
    assertEquals(CiRunStatus.QUEUED, run(waiting).status);

    // A fourth failure while out counts, and quarantines nothing a second time.
    runners.recordInfraFailure(runner.id, "late", "CONNECTION_LOST", 3, 2);
    assertEquals(1, events.of(runner.id.toString()).size());
    cancel(waiting);
  }

  @Test
  public void aGreenlightLiftsTheQuarantineResetsTheStreakAndAnnouncesOnce() {
    CiRunner runner = runner("to-greenlight", 2);
    runners.quarantine(runner.id, "for the test");
    runners.recordInfraFailure(runner.id, "r1", "LAUNCH_FAILED", 3, 2);
    signals.reset();
    events.reset();

    CiRunner lifted = health.greenlight(runner.id);

    assertFalse(lifted.quarantined());
    assertNull(lifted.quarantineReason);
    assertEquals(0, row(runner.id).infraFailures);
    assertEquals(2, health.effectiveSlots(runner.id));
    assertEquals(List.of("reinstated:admin"), signals.of(runner.id));
    assertEquals(List.of("RunnerReinstated admin"), events.of(runner.id.toString()));

    // In service already: nothing to lift, nothing to say.
    health.greenlight(runner.id);
    assertEquals(1, events.of(runner.id.toString()).size());
    assertEquals(1, signals.of(runner.id).size());
  }

  // --- a new runner -------------------------------------------------------------------------------

  @Test
  public void aNewlyRegisteredRunnerStartsQuarantinedAndIsQueuedAHealthCheck() {
    UUID id = UUID.randomUUID();
    runners.create(id, "fresh-host", null, 3, CiRunnerPlane.EDGE, "tok", "sub-fresh");
    runners.markRegistered(id, "client-fresh", "{\"docker\":true}");
    health.onRegistered(id);

    CiRunner registered = row(id);
    assertTrue(registered.quarantined());
    assertEquals(CiRunners.AWAITING_FIRST_HEALTH_CHECK, registered.quarantineReason);
    assertEquals(3, registered.slots);
    CiRun check = pendingCheck(id);
    assertEquals(CiRunPurpose.HEALTHCHECK, check.purpose);
    assertEquals(id, check.targetRunnerId);
    assertEquals(CiRunStatus.QUEUED, check.status);
    assertEquals("repo-" + HEALTH_REPO, check.repoId);
    assertEquals("main", check.branch);
    assertEquals(HEAD, check.commitSha, "main's head, resolved the way an event run resolves it");
    assertTrue(check.triggerConfig.contains("script: echo hello world"), check.triggerConfig);
    assertTrue(check.triggerConfig.contains("qits/build-images/ci-base:latest"), check.triggerConfig);
    assertTrue(check.triggerConfig.contains("timeout-seconds: 300"), check.triggerConfig);
    // Quarantined, and still able to take its check: one slot, for the one it will occupy.
    assertEquals(1, health.effectiveSlots(id));
    assertEquals(List.of("slotsChanged"), signals.of(id));
    assertEquals(
        List.of(
            "RunnerCreated fresh-host",
            "RunnerRegistered client-fresh",
            "RunnerQuarantined " + CiRunners.AWAITING_FIRST_HEALTH_CHECK),
        events.of(id.toString()));
  }

  // --- who may take a health check ----------------------------------------------------------------

  @Test
  public void aHealthCheckIsTakenByItsTargetEvenQuarantinedAndByNobodyElse() throws Exception {
    CiRunner target = runner("target", 1);
    CiRunner bystander = runner("bystander", 5);
    runners.quarantine(target.id, "for the test");
    CiRun check = health.requestHealthCheck(target.id);

    // The suite's runner — gone here — would have been told; no runner took it.
    suiteRunner.awaitIdle();
    assertEquals(CiRunStatus.QUEUED, run(check.id).status);
    assertTrue(fakeRunner.executed().stream().noneMatch(s -> s.runId().equals(check.id)));
    assertTrue(service.reserveFor(bystander).isEmpty(), "another runner is never handed it");

    CiRunService.Reservation taken = service.reserveFor(target).orElseThrow();
    assertEquals(check.id, taken.run().id);
    assertEquals(target.id, run(check.id).runnerId);
    assertTrue(service.reserveFor(target).isEmpty(), "and nothing else while it is quarantined");
    service.executeReserved(taken);
  }

  @Test
  public void aSecondHealthCheckWhileOneIsPendingIsA409() {
    CiRunner runner = runner("twice", 1);
    CiRun first = health.requestHealthCheck(runner.id);

    ConflictException refused =
        assertThrows(ConflictException.class, () -> health.requestHealthCheck(runner.id));
    assertTrue(refused.getMessage().contains(first.id), refused.getMessage());
  }

  // --- what a settled check does ------------------------------------------------------------------

  @Test
  public void aGreenHealthCheckLiftsTheQuarantineAndIsNotAnnouncedAsABuild() throws Exception {
    CiRunner runner = runner("recovering", 2);
    runners.quarantine(runner.id, "for the test");
    events.reset();
    signals.reset();
    CiRun check = health.requestHealthCheck(runner.id);

    service.executeReserved(service.reserveFor(runner).orElseThrow());

    CiRunner after = row(runner.id);
    assertFalse(after.quarantined());
    assertEquals(CiRunners.HEALTH_CHECK_PASSED, after.lastHealthcheckResult);
    assertEquals(check.id, after.lastHealthcheckRunId);
    assertNotNull(after.lastHealthcheckAt);
    assertNull(after.lastHealthcheckDetail);
    assertEquals(CiRunStatus.SUCCESS, run(check.id).status);
    assertEquals(
        List.of("RunnerHealthChecked PASSED", "RunnerReinstated healthcheck"),
        events.of(runner.id.toString()));
    assertEquals(List.of("slotsChanged", "reinstated:healthcheck"), signals.of(runner.id));
    // Not a build: no BuildSuccessful, no BuildStatusChanged, nothing a release request could read.
    assertTrue(runAnnouncer.announced().stream().noneMatch(a -> a.runId().equals(check.id)));
    assertEquals(List.of(), runAnnouncer.statusesOf(check.id));
  }

  @Test
  public void aRedHealthCheckKeepsTheQuarantineWithItsDetailAndCountsNoStreak()
      throws Exception {
    CiRunner runner = runner("still-broken", 1);
    runners.quarantine(runner.id, "for the test");
    events.reset();
    CiRun check = health.requestHealthCheck(runner.id);
    fakeRunner.answer(
        spec -> failed(StepOutcome.LAUNCH_FAILED, "runner still-broken could not start it"));

    service.executeReserved(service.reserveFor(runner).orElseThrow());

    CiRunner after = row(runner.id);
    assertTrue(after.quarantined());
    assertEquals("for the test", after.quarantineReason, "already out: its reason stands");
    assertEquals(CiRunners.HEALTH_CHECK_FAILED, after.lastHealthcheckResult);
    assertEquals(
        "LAUNCH_FAILED\nrunner still-broken could not start it", after.lastHealthcheckDetail);
    assertEquals(0, after.infraFailures, "a health check's own steps feed no streak");
    assertEquals(CiRunStatus.FAILED, run(check.id).status);
    assertEquals(
        List.of("RunnerHealthChecked FAILED LAUNCH_FAILED\nrunner still-broken could not start it"),
        events.of(runner.id.toString()));
    assertTrue(runAnnouncer.failed().stream().noneMatch(f -> f.runId().equals(check.id)));
  }

  @Test
  public void aRedHealthCheckOfARunnerInServiceQuarantinesIt() throws Exception {
    CiRunner runner = runner("was-fine", 1);
    health.requestHealthCheck(runner.id);
    fakeRunner.answer(spec -> failed(StepOutcome.NEVER_STARTED, "never dialled back"));

    service.executeReserved(service.reserveFor(runner).orElseThrow());

    assertEquals("health check failed: NEVER_STARTED", row(runner.id).quarantineReason);
    assertEquals(
        List.of(
            "RunnerHealthChecked FAILED NEVER_STARTED\nnever dialled back",
            "RunnerQuarantined health check failed: NEVER_STARTED"),
        events.of(runner.id.toString()));
  }

  // --- where a health check is not ----------------------------------------------------------------

  @Test
  public void aHealthCheckIsInNoListingButReadableByIdAndNotRetried() throws Exception {
    CiRunner runner = runner("listed", 1);
    CiRun check = health.requestHealthCheck(runner.id);

    assertTrue(service.activeRuns().stream().noneMatch(r -> r.id.equals(check.id)));
    assertTrue(service.queueSnapshot().queuedInClaimOrder().isEmpty());
    service.executeReserved(service.reserveFor(runner).orElseThrow());

    assertEquals(List.of(), service.runsFor("repo-" + HEALTH_REPO));
    assertTrue(service.finishedRuns(100).stream().noneMatch(r -> r.id.equals(check.id)));
    assertFalse(service.repositoryIds().contains("repo-" + HEALTH_REPO));
    assertEquals(CiRunPurpose.HEALTHCHECK, service.requireRun(check.id).purpose);
    assertThrows(ConflictException.class, () -> service.retry(check.id));
  }

  // --- the schedule -------------------------------------------------------------------------------

  @Test
  public void theSweepQueuesChecksOnlyForQuarantinedConnectedRunnersWithNonePending() {
    Instant now = Instant.now();
    Instant sweptAt = now.plus(Duration.ofMinutes(61));
    CiRunner due = runner("due", 1);
    CiRunner disconnected = runner("disconnected", 1);
    CiRunner pending = runner("pending", 1);
    CiRunner inService = runner("in-service", 1);
    CiRunner recent = runner("recent", 1);
    for (CiRunner runner : List.of(due, disconnected, pending, recent)) {
      runners.quarantine(runner.id, "for the test");
    }
    for (CiRunner runner : List.of(due, pending, inService, recent)) {
      presence.connect(runner.id);
    }
    CiRun alreadyQueued = health.requestHealthCheck(pending.id);
    // Queued a minute before the sweep, so the sweep's own queue-timeout does not settle it first.
    QuarkusTransaction.requiringNew()
        .run(() -> runs.findById(alreadyQueued.id).createdAt = sweptAt.minus(Duration.ofMinutes(1)));
    // "recent" was checked after its +60m slot (a settled check, so none is pending): its next slot
    // is +90m, which the sweep at +61m has not reached.
    CiRun recentCheck = health.requestHealthCheck(recent.id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun check = runs.findById(recentCheck.id);
              check.status = CiRunStatus.FAILED;
              check.createdAt = now.plus(Duration.ofMinutes(60)).plusSeconds(30);
            });

    health.sweep(sweptAt);

    assertEquals(CiRunPurpose.HEALTHCHECK, pendingCheck(due.id).purpose);
    assertNull(pendingOrNull(disconnected.id), "not connected: nothing could take it");
    assertNull(pendingOrNull(inService.id), "in service: nothing to prove");
    assertNull(pendingOrNull(recent.id), "checked since its last slot");
    assertEquals(alreadyQueued.id, pendingCheck(pending.id).id, "one pending check is enough");

    // qits-896: a check the sweep queues comes with a node health request, and only that one.
    assertEquals(1, signals.nodeHealthChecksOf(due.id), "asked for its node report beside it");
    assertEquals(0, signals.nodeHealthChecksOf(disconnected.id));
    assertEquals(0, signals.nodeHealthChecksOf(pending.id), "nothing queued, nothing asked");
    assertEquals(0, signals.nodeHealthChecksOf(inService.id));
    assertEquals(0, signals.nodeHealthChecksOf(recent.id));
  }

  /**
   * qits-748, the owner's request: the checks of a quarantined runner back off from its quarantine —
   * +1, +15, +30, then +60, +90, +120, then +180 and hourly after the schedule — exactly at each slot
   * and not a minute before, and a new quarantine starts the schedule over.
   */
  @Test
  public void theSweepQueuesChecksExactlyAtTheBackingOffSlotsAndRestartsOnANewQuarantine() {
    CiRunner runner = runner("backing-off", 1);
    presence.connect(runner.id);
    runners.quarantine(runner.id, "for the test");
    // Read back rather than taken off the returned entity: the column keeps microseconds.
    Instant quarantinedAt = row(runner.id).quarantinedAt;

    for (long minutes : List.of(1L, 15L, 30L, 60L, 90L, 120L, 180L, 240L, 300L)) {
      Instant slot = quarantinedAt.plus(Duration.ofMinutes(minutes));
      health.sweep(slot.minusSeconds(30));
      assertNull(pendingOrNull(runner.id), "not before the +" + minutes + "m slot");
      health.sweep(slot);
      settleAt(pendingCheck(runner.id), slot);
    }

    // The checks above were dated at simulated slots in the future; the next quarantine is real
    // time, so put them back in the quarantine they belonged to before starting a new one.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                runs.update(
                    "createdAt = ?1 where targetRunnerId = ?2", quarantinedAt, runner.id));
    // A passing check (here an operator) reinstates; the next quarantine is a new schedule.
    health.greenlight(runner.id);
    runners.quarantine(runner.id, "again");
    Instant again = row(runner.id).quarantinedAt;
    assertTrue(again.isAfter(quarantinedAt));
    health.sweep(again);
    assertNull(pendingOrNull(runner.id), "the schedule starts over at +1m, not where it stopped");
    health.sweep(again.plus(Duration.ofMinutes(1)));
    settleAt(pendingCheck(runner.id), again.plus(Duration.ofMinutes(1)));
    health.sweep(again.plus(Duration.ofMinutes(14)));
    assertNull(pendingOrNull(runner.id));
    health.sweep(again.plus(Duration.ofMinutes(15)));
    assertNotNull(pendingOrNull(runner.id));
  }

  @Test
  public void aRunnerAwayAtItsSlotsGetsOneCheckWhenItComesBackAndNotABacklog() {
    CiRunner runner = runner("came-back", 1);
    runners.quarantine(runner.id, "for the test");
    Instant quarantinedAt = row(runner.id).quarantinedAt;

    Instant back = quarantinedAt.plus(Duration.ofMinutes(100));
    health.sweep(back);
    assertNull(pendingOrNull(runner.id), "not connected: nothing could take it");

    presence.connect(runner.id);
    health.sweep(back);
    settleAt(pendingCheck(runner.id), back);

    // The slots it missed (+1, +15, +30, +60, +90) are not owed: the next is the first after +100m.
    health.sweep(back.plus(Duration.ofMinutes(1)));
    health.sweep(back.plus(Duration.ofMinutes(19)));
    assertNull(pendingOrNull(runner.id), "no backlog of missed slots");
    health.sweep(quarantinedAt.plus(Duration.ofMinutes(120)));
    assertNotNull(pendingOrNull(runner.id), "the +120m slot");
  }

  @Test
  public void aCheckNobodyTookIsSettledFailedAsRunnerNotConnected() {
    CiRunner runner = runner("gone-away", 1);
    CiRun check = health.requestHealthCheck(runner.id);
    events.reset();

    health.sweep(Instant.now().plus(Duration.ofMinutes(31)));

    assertEquals(CiRunStatus.FAILED, run(check.id).status);
    CiRunner after = row(runner.id);
    assertEquals(CiRunners.HEALTH_CHECK_FAILED, after.lastHealthcheckResult);
    assertEquals(CiRunnerHealth.NOT_CONNECTED, after.lastHealthcheckDetail);
    assertEquals("health check failed: runner not connected", after.quarantineReason);
    assertEquals(
        List.of(
            "RunnerHealthChecked FAILED runner not connected",
            "RunnerQuarantined health check failed: runner not connected"),
        events.of(runner.id.toString()));
  }

  // --- staging ------------------------------------------------------------------------------------

  /**
   * A red check, settled as if the sweep that queued it ran at {@code at}: accepted then, so the
   * schedule counts its next slot from there, and no longer pending. The runner stays quarantined.
   */
  private void settleAt(CiRun check, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun row = runs.findById(check.id);
              row.status = CiRunStatus.FAILED;
              row.createdAt = at;
              row.finishedAt = at;
            });
  }

  private static StepResult failed(StepOutcome outcome, String output) {
    return new StepResult(-1, false, outcome, output);
  }

  /** Accept one build, reserve it for {@code runner} and drive it to its end, as the socket does. */
  private String reserveAndRun(CiRunner runner, String repoName) {
    String runId = accept(repoName);
    CiRunService.Reservation reservation = service.reserveFor(runner).orElseThrow();
    assertEquals(runId, reservation.run().id);
    service.executeReserved(reservation);
    return runId;
  }

  /** One build, queued for whichever runner the case reserves for. */
  private String accept(String repoName) {
    return service.onEventTrigger(
        eventRun(CiRepoRef.of("health-" + UUID.randomUUID(), "qits", repoName), "main", SHA, PLAIN));
  }

  private void cancel(String runId) {
    service.cancel(runId);
  }

  private CiRunner runner(String name, int slots) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRunner runner = new CiRunner();
              runner.id = UUID.randomUUID();
              runner.name = name;
              runner.slots = slots;
              runner.plane = CiRunnerPlane.EDGE;
              runner.clientId = "client-" + name;
              runner.capabilities = "{\"docker\":true,\"arch\":\"amd64\"}";
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
              return runner;
            });
  }

  private CiRunner row(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(id));
  }

  private CiRun run(String runId) {
    return QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
  }

  private CiRun pendingOrNull(UUID runnerId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> runs.findPendingHealthCheck(runnerId).orElse(null));
  }

  private CiRun pendingCheck(UUID runnerId) {
    CiRun check = pendingOrNull(runnerId);
    assertNotNull(check, "a health check is pending for " + runnerId);
    return check;
  }
}
