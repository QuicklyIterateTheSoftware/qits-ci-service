package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A run the infrastructure failed is re-fired at once and announces no red verdict (qits-440).
 *
 * <p>The property the release gate depends on is the absence of a {@code BuildFailed}: qits-projects
 * rejects a release request on the first one it hears, and it hears verdicts through nothing else.
 * So every case below asserts on {@link FakeRunAnnouncer#failed()} as well as on the rows — a retry
 * that was queued AFTER a {@code BuildFailed} went out would pass a row-only test and still reject
 * the request.
 *
 * <p>The cap is {@link CiRunService#AUTO_RETRY_MAX}; each case sets it explicitly, since a suite
 * staging infra failures for another purpose may have turned it off, and puts the old value back.
 */
@QuarkusTest
public class CiAutoRetryTest extends CiTestSupport {

  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";

  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: ./mvnw verify
        - image: alpine:3
          script: ./publish-userflows.sh
      """;

  private static final String MERGED = "c".repeat(40);

  private static final StepResult LOST =
      new StepResult(-1, false, StepOutcome.CONNECTION_LOST, "socket closed");

  @Inject CiRunService service;
  @Inject FakeRunAnnouncer announcer;
  @Inject CiRunMapper mapper;

  private final CountDownLatch release = new CountDownLatch(1);

  private int suiteMax;

  @BeforeEach
  void shippedCap() {
    announcer.reset();
    suiteMax = service.autoRetryMax();
    service.autoRetryMax(2);
  }

  @AfterEach
  void restore() throws Exception {
    release.countDown();
    suiteRunner.awaitIdle();
    service.autoRetryMax(suiteMax);
  }

  @Test
  public void aConnectionLostFailureIsRetriedOnceAndTheGateHearsNoRed() throws Exception {
    // The retry re-enters the ordinary queue as a lease of its own, and the suite's one target runs
    // it like any other.
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.scriptSequence(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "exactly one retry");
    CiRun failed = all.get(0);
    CiRun retry = all.get(1);
    assertEquals(original, failed.id);
    assertEquals(CiRunStatus.FAILED, failed.status, "the failed run's own record is kept");
    assertNull(failed.retryReason);

    assertEquals(original, retry.retryOfRunId);
    assertEquals(CiRunStatus.SUCCESS, retry.status);
    assertEquals(MERGED, retry.commitSha, "the same commit");
    assertEquals("rr-a", retry.releaseRequestId, "the same release request");
    assertEquals(QA_PATH, retry.configPath, "the same pipeline");
    // A lost connection is the lease container's, and the reason names the target it ran on.
    assertEquals(
        "infra failure (the connection to the run's lease container was lost on target "
            + suiteRunner.id()
            + ") on run "
            + original
            + " — automatic retry 1 of 2",
        retry.retryReason);

    // THE GATE: no red verdict for the infra failure, and the retry's green names what it supersedes.
    assertTrue(
        announcer.failed().stream().noneMatch(f -> f.runId().equals(original)),
        "an auto-retried infra failure must not announce BuildFailed");
    assertTrue(announcer.failed().isEmpty());
    FakeRunAnnouncer.Announced green =
        announcer.announced().stream()
            .filter(a -> a.runId().equals(retry.id))
            .findFirst()
            .orElseThrow();
    assertEquals(original, green.retryOfRunId());
    assertEquals("rr-a", green.releaseRequestId());
    // The row mirror still hears the failed row settle — it is about rows, not verdicts.
    List<String> statuses = announcer.statusesOf(original);
    assertTrue(statuses.contains("FAILED"), statuses.toString());

    CiStep step = service.stepsFor(original).get(0);
    assertTrue(
        step.output.endsWith(
            "[infra failure (the connection to the run's lease container was lost on target "
                + suiteRunner.id()
                + ") — retried automatically as run " + retry.id + "]"),
        step.output);
  }

  @Test
  public void anAutomationRunsInfraFailureIsStillRetriedAutomatically() throws Exception {
    // qits-1816 refuses a PERSON's retry of an automation run; qits-ci's own re-fire of an infra
    // failure is one of the run ids qits-maintenance follows, and must keep happening.
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.scriptSequence(0, LOST);

    String original =
        service.onEventTrigger(CiRunCancelAndRetryTest.automationRun(repo));
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "exactly one automatic retry");
    assertEquals(original, all.get(0).id);
    assertEquals(CiRunStatus.FAILED, all.get(0).status);
    CiRun retry = all.get(1);
    assertEquals(original, retry.retryOfRunId);
    assertEquals(CiAutomationComposer.EVENT, retry.triggerEventName);
    assertTrue(retry.retryReason.endsWith("automatic retry 1 of 2"), retry.retryReason);
    assertEquals(CiRunStatus.SUCCESS, retry.status);
  }

  @Test
  public void aStepExitCodeFailureIsNotRetriedOom137Included() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.script(0, new StepResult(137, false, StepOutcome.OK, "Killed"));

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, service.runsFor(repo).size(), "a build's own exit code is a verdict");
    assertEquals(CiRunStatus.FAILED, service.requireRun(original).status);
    assertEquals(
        List.of(original), announcer.failed().stream().map(f -> f.runId()).toList(),
        "and it is announced as one");
  }

  @Test
  public void atMostTwoAutomaticRetriesThenTheFailureSettles() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.script(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(3, all.size(), "the original and two automatic retries");
    assertTrue(all.stream().allMatch(r -> r.status == CiRunStatus.FAILED));
    assertEquals(original, all.get(0).id);
    assertEquals(original, all.get(1).retryOfRunId);
    assertEquals(
        all.get(1).id, all.get(2).retryOfRunId, "a retry of a retry keeps the chain count");
    assertTrue(all.get(1).retryReason.endsWith("automatic retry 1 of 2"), all.get(1).retryReason);
    assertTrue(all.get(2).retryReason.endsWith("automatic retry 2 of 2"), all.get(2).retryReason);

    // Only the last one settles as a verdict, and it carries the lineage the gate walks.
    assertEquals(1, announcer.failed().size(), announcer.failed().toString());
    FakeRunAnnouncer.AnnouncedFailure red = announcer.failed().get(0);
    assertEquals(all.get(2).id, red.runId());
    assertEquals(all.get(1).id, red.retryOfRunId());
    String settled = service.stepsFor(all.get(2).id).get(0).output;
    assertTrue(
        settled.endsWith("[the connection to the run's lease container was lost]"),
        "the settled run names no retry: " + settled);

    // A person re-asking is a new question with its own budget, and is not marked automatic.
    fakeRunner.reset();
    CiRun manual = service.retry(all.get(2).id);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertNull(service.requireRun(manual.id).retryReason);
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(manual.id).status);
  }

  @Test
  public void aCancelledRunIsNotRetriedWhateverItsStepSaid() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    CompletableFuture<String> reached = new CompletableFuture<>();
    CountDownLatch cancelled = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          reached.complete(spec.runId());
          try {
            cancelled.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    fakeRunner.script(0, LOST);

    accept(repo, "rr-a");
    String runId = reached.get(10, TimeUnit.SECONDS);
    service.cancel(runId);
    cancelled.countDown();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, service.runsFor(repo).size());
    assertEquals(CiRunStatus.CANCELLED, service.requireRun(runId).status);
    assertEquals(CiRunService.USER_CANCELLED, service.requireRun(runId).cancellationReason);
    assertTrue(announcer.failed().isEmpty());
  }

  @Test
  public void anIdMappingFailureIsRetriedAndTheGateHearsNoRed() throws Exception {
    // A step that ran and exited 1 is still retried when its tail says the builder could not map a
    // file owner (qits-556): the host failed it, not the build.
    fakeRunner.scriptSequence(
        0,
        new StepResult(
            1,
            false,
            StepOutcome.OK,
            "#5 ERROR: failed to Lchown \"/usr/lib/jvm\" for UID 1001380000, GID 0"));
    String repo = "auto-" + UUID.randomUUID();

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "a step that ran and exited 1 is still retried when the host failed it");
    CiRun failed = all.get(0);
    CiRun retry = all.get(1);
    assertEquals(CiRunStatus.FAILED, failed.status);
    assertEquals(CiRunStatus.SUCCESS, retry.status);
    assertEquals(original, retry.retryOfRunId);
    assertEquals(
        "infra failure (the builder could not map a file owner (\"failed to Lchown\") on target "
            + suiteRunner.id()
            + " — its uid/gid range is too narrow) on run " + original
            + " — automatic retry 1 of 2",
        retry.retryReason);
    assertEquals(
        List.of(), announcer.failed(), "no BuildFailed while an automatic retry follows");
    assertTrue(
        service.stepsFor(original).get(0).output.endsWith(
            " — retried automatically as run " + retry.id + "]"),
        service.stepsFor(original).get(0).output);
  }

  @Test
  public void theRunDtoSaysWhetherARunWasRetriedAutomaticallyAndWhy() {
    CiRun ordinary = new CiRun();
    ordinary.id = "plain";
    CiRunDto plain = mapper.toDto(ordinary);
    assertFalse(plain.autoRetry());
    assertNull(plain.retryReason());

    CiRun automatic = new CiRun();
    automatic.id = "auto";
    automatic.retryOfRunId = "plain";
    automatic.retryReason = "infra failure (the connection to the run's lease container was lost) on run plain";
    CiRunDto dto = mapper.toDto(automatic);
    assertTrue(dto.autoRetry());
    assertEquals(automatic.retryReason, dto.retryReason());
    assertEquals("plain", dto.retryOfRunId());
    // And the withers carry both across.
    assertNotNull(dto.withSteps(List.of(), null).retryReason());
  }

  // --- qits-2163: a retry the environment refused waits for it ------------------------------------

  private static final String DIGEST = "sha256:" + "d".repeat(64);

  @Test
  public void aRetryTheRegistryRefusesIsDeferredAndTheGateHearsNoRed() throws Exception {
    // 2026-11-12: the edge was dark, so the retry's re-pin could not reach the registry. The run is
    // accepted with the ci-slot image answering; the registry goes dark while its step is lost.
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.during(0, spec -> fakeImagePins.unresolvable(CiSlot.IMAGE_TAG));
    fakeRunner.scriptSequence(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, chain(repo).size(), "no retry could be made yet");
    CiRun waiting = service.requireRun(original);
    assertEquals(CiRunStatus.FAILED, waiting.status, "the row says what happened");
    assertNotNull(waiting.autoRetryDeferredUntil, "and that it awaits its automatic retry");
    assertEquals(
        waiting.autoRetryDeferredUntil,
        mapper.toDto(waiting).autoRetryDeferredUntil(),
        "and the run DTO says so too (qits-2165)");
    assertTrue(waiting.autoRetryDeferredReason.endsWith("automatic retry 1 of 2"));
    assertTrue(announcer.failed().isEmpty(), "no BuildFailed while the retry is deferred");
    assertTrue(
        service.stepsFor(original).get(0).output.contains("its automatic retry is deferred until"),
        service.stepsFor(original).get(0).output);

    // Still dark at the next tick: nothing changes.
    fakeRunner.reset();
    assertEquals(0, service.sweepDeferredRetries(Instant.now()));
    forgetLoadedEntities();
    assertEquals(1, chain(repo).size());
    assertNotNull(service.requireRun(original).autoRetryDeferredUntil);

    // The registry comes back after one more blip; the tick after that makes the retry.
    fakeImagePins.unresolvableThenPins(CiSlot.IMAGE_TAG, 3, DIGEST);
    assertEquals(0, service.sweepDeferredRetries(Instant.now()), "the blip (three tries) refuses");
    assertEquals(1, service.sweepDeferredRetries(Instant.now()));
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "exactly one retry");
    CiRun retry = all.get(1);
    assertEquals(original, retry.retryOfRunId);
    assertEquals(
        "infra failure (the connection to the run's lease container was lost on target "
            + suiteRunner.id()
            + ") on run "
            + original
            + " — automatic retry 1 of 2",
        retry.retryReason);
    assertEquals(CiRunStatus.SUCCESS, retry.status);
    assertNull(service.requireRun(original).autoRetryDeferredUntil, "the deferral is taken");
    assertNull(
        mapper.toDto(service.requireRun(original)).autoRetryDeferredUntil(),
        "and the run DTO stops saying so");
    assertTrue(announcer.failed().isEmpty(), "the gate never heard a red");
    assertTrue(
        service.stepsFor(original).get(0).output.endsWith(
            " — retried automatically as run " + retry.id + "]"),
        service.stepsFor(original).get(0).output);

    // A second pass finds nothing: the deferral fires once.
    assertEquals(0, service.sweepDeferredRetries(Instant.now()));
    forgetLoadedEntities();
    assertEquals(2, chain(repo).size());
  }

  @Test
  public void aDeferralPastItsDeadlineSettlesFailedAndAnnouncesTheRed() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.during(0, spec -> fakeImagePins.unresolvable(CiSlot.IMAGE_TAG));
    fakeRunner.scriptSequence(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    Instant until = service.requireRun(original).autoRetryDeferredUntil;
    assertNotNull(until);
    assertTrue(announcer.failed().isEmpty());

    assertEquals(0, service.sweepDeferredRetries(until.plusSeconds(1)));
    forgetLoadedEntities();

    assertEquals(1, chain(repo).size(), "no retry was made");
    assertNull(service.requireRun(original).autoRetryDeferredUntil);
    assertEquals(
        List.of(original), announcer.failed().stream().map(f -> f.runId()).toList(),
        "the red is announced once the wait gives up");
    assertTrue(
        service.stepsFor(original).get(0).output.endsWith("— the run settles FAILED]"),
        service.stepsFor(original).get(0).output);

    // Settled once: a later pass announces nothing more.
    service.sweepDeferredRetries(until.plusSeconds(120));
    assertEquals(1, announcer.failed().size());
  }

  @Test
  public void aPersonsRetryDuringTheDeferralTakesItOver() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.during(0, spec -> fakeImagePins.unresolvable(CiSlot.IMAGE_TAG));
    fakeRunner.scriptSequence(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertNotNull(service.requireRun(original).autoRetryDeferredUntil);

    fakeRunner.reset();
    fakeImagePins.reset();
    CiRun manual = service.retry(original);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertNull(service.requireRun(manual.id).retryReason, "a person's retry, not an automatic one");
    assertNull(service.requireRun(original).autoRetryDeferredUntil, "the deferral is cancelled");

    // The sweep finds nothing to re-fire and nothing to settle, even past the deadline.
    assertEquals(0, service.sweepDeferredRetries(Instant.now()));
    assertEquals(0, service.sweepDeferredRetries(Instant.now().plusSeconds(86_400)));
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(2, chain(repo).size(), "one retry, the person's");
    assertTrue(announcer.failed().isEmpty());
  }

  @Test
  public void aSweepThatDiesHoldingTheLeaseStrandsNothing() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.during(0, spec -> fakeImagePins.unresolvable(CiSlot.IMAGE_TAG));
    fakeRunner.scriptSequence(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertNotNull(service.requireRun(original).autoRetryDeferredUntil);

    // A pass leases the run and dies before its re-fire: no retry row, and no clear either.
    Instant now = Instant.now();
    assertEquals(
        1,
        (int)
            QuarkusTransaction.requiringNew()
                .call(() -> runs.leaseDeferredAutoRetry(original, now, now.plusSeconds(120))));
    fakeRunner.reset();
    fakeImagePins.reset();

    assertEquals(0, service.sweepDeferredRetries(now.plusSeconds(60)), "the lease still holds");
    forgetLoadedEntities();
    assertEquals(1, chain(repo).size());
    assertNotNull(service.requireRun(original).autoRetryDeferredUntil, "the wait is not lost");

    assertEquals(1, service.sweepDeferredRetries(now.plusSeconds(180)), "taken over after it");
    assertEquals(0, service.sweepDeferredRetries(now.plusSeconds(600)), "and only once");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "exactly one retry");
    assertEquals(original, all.get(1).retryOfRunId);
    assertTrue(all.get(1).retryReason.endsWith("automatic retry 1 of 2"), all.get(1).retryReason);
    assertNull(service.requireRun(original).autoRetryDeferredUntil);
    assertNull(service.requireRun(original).autoRetryNextAttemptAt);
    assertTrue(announcer.failed().isEmpty());
  }

  @Test
  public void aCancelledRequestDropsItsDeferredRetryAndAnnouncesNothing() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.during(0, spec -> fakeImagePins.unresolvable(CiSlot.IMAGE_TAG));
    fakeRunner.scriptSequence(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertNotNull(service.requireRun(original).autoRetryDeferredUntil);

    service.cancelReleaseRequestRuns(repo, "rr-a", null);
    forgetLoadedEntities();
    assertNull(service.requireRun(original).autoRetryDeferredUntil);

    fakeImagePins.reset();
    assertEquals(0, service.sweepDeferredRetries(Instant.now()));
    assertEquals(0, service.sweepDeferredRetries(Instant.now().plusSeconds(86_400)));
    forgetLoadedEntities();
    assertEquals(1, chain(repo).size(), "the withdrawn question is not re-asked");
    assertTrue(announcer.failed().isEmpty(), "nor answered red");
  }

  @Test
  public void aRefusalThatIsNotTransientStillSettlesAtOnce() {
    // The classification: an environment that may come back defers, everything else settles.
    assertTrue(CiRunService.transientRefusal(new eu.wohlben.qits.ci.error.UnavailableException("x")));
    assertTrue(
        CiRunService.transientRefusal(
            new io.quarkus.narayana.jta.QuarkusTransactionException(
                new jakarta.transaction.RollbackException(
                    "ARJUNA016053: Could not commit transaction."))));
    assertTrue(
        CiRunService.transientRefusal(
            new IllegalStateException(new java.sql.SQLException("gone", "08006"))));
    assertFalse(
        CiRunService.transientRefusal(new eu.wohlben.qits.ci.error.ConflictException("x")));
    assertFalse(CiRunService.transientRefusal(new IllegalArgumentException("unparseable")));
    assertFalse(
        CiRunService.transientRefusal(
            new IllegalStateException(new java.sql.SQLException("duplicate key", "23505"))));
  }

  // --- qits-2163: a step's row waits for its database ---------------------------------------------

  @Test
  public void aStepRecordWriteRefusedOnceIsAskedAgainAndTheRealResultIsRecorded() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    service.failStepWrites(0, 1, CiAutoRetryTest::refusedCommit);
    try {
      accept(repo, "rr-a");
      suiteRunner.awaitIdle();
    } finally {
      service.clearStepWriteFaults();
    }
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(1, all.size(), "no retry: nothing failed");
    assertEquals(CiRunStatus.SUCCESS, all.get(0).status);
    List<CiStep> recorded = service.stepsFor(all.get(0).id);
    assertEquals(2, recorded.size(), "one row per step, the refused attempt left none behind");
    assertEquals(eu.wohlben.qits.ci.entity.CiStepStatus.SUCCESS, recorded.get(0).status);
    assertEquals(0, recorded.get(0).exitCode);
    assertTrue(announcer.failed().isEmpty());
  }

  @Test
  public void aDatabaseThatStaysGoneBeforeTheStepLaunchedIsAnInfraFailure() throws Exception {
    // Nothing of the step ran, so it is NEVER_STARTED and retried automatically.
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.throwOnceOn(0, refusedCommit());

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "retried automatically");
    assertEquals(CiRunStatus.FAILED, all.get(0).status);
    assertTrue(
        service.stepsFor(original).get(0).output.startsWith(
            CiRunService.STEP_NOT_STARTED_DATABASE),
        service.stepsFor(original).get(0).output);
    CiRun retry = all.get(1);
    assertEquals(original, retry.retryOfRunId);
    assertTrue(
        retry.retryReason.startsWith(
            "infra failure (the step could not be recorded before it started"),
        retry.retryReason);
    assertEquals(CiRunStatus.SUCCESS, retry.status);
    assertTrue(announcer.failed().isEmpty(), "no BuildFailed for an infra failure");
  }

  @Test
  public void aRunsRecordPatienceIsSpentOnceNotPerWrite() throws Exception {
    // The launch record waits the deadline out; the step's fallback row after it must not wait it
    // out again (nor would the SKIPPED rows behind it).
    String repo = "auto-" + UUID.randomUUID();
    java.time.Duration shipped = service.stepRecordRetryDeadline();
    service.stepRecordRetryDeadline(java.time.Duration.ofMillis(1500));
    fakeRunner.launchAs("daemon-" + UUID.randomUUID(), "subject");
    service.failStepWrites(-1, Integer.MAX_VALUE, CiAutoRetryTest::refusedCommit);
    long began = System.nanoTime();
    String original;
    try {
      original = accept(repo, "rr-a");
      suiteRunner.awaitIdle();
    } finally {
      service.clearStepWriteFaults();
      service.stepRecordRetryDeadline(shipped);
    }
    long tookMillis = (System.nanoTime() - began) / 1_000_000;
    forgetLoadedEntities();

    assertTrue(tookMillis >= 1500, "the first write waited: " + tookMillis + " ms");
    assertTrue(tookMillis < 2700, "the later writes did not wait again: " + tookMillis + " ms");
    assertEquals(CiRunStatus.FAILED, service.requireRun(original).status, "and the run settled");
  }

  @Test
  public void aNonTransientThrowStillFailsTheStepAsBefore() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.throwOn(0, new IllegalStateException("a bug"));

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, chain(repo).size());
    assertTrue(
        service.stepsFor(original).get(0).output.startsWith("[the step could not be executed:"),
        service.stepsFor(original).get(0).output);
    assertEquals(List.of(original), announcer.failed().stream().map(f -> f.runId()).toList());
  }

  private static RuntimeException refusedCommit() {
    return new io.quarkus.narayana.jta.QuarkusTransactionException(
        new jakarta.transaction.RollbackException("ARJUNA016053: Could not commit transaction."));
  }

  // --- fixture -----------------------------------------------------------------------------------

  /** The repository's runs, oldest first. */
  private List<CiRun> chain(String repo) {
    return service.runsFor(repo).stream()
        .sorted(Comparator.comparing((CiRun r) -> r.createdAt))
        .toList();
  }

  private void occupyTheWorker() throws Exception {
    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    fakeRunner.during(
        0,
        spec -> {
          if (inStepZero.complete(spec.runId())) {
            try {
              release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    accept("blocker-" + UUID.randomUUID(), "rr-blocker");
    inStepZero.get(20, TimeUnit.SECONDS);
  }

  private String accept(String repoId, String requestId) {
    return service.onEventTrigger(
        new CiRunService.EventRun(
            CiRepoRef.of(repoId, "qits", "qits-ci-service"),
            "release/" + requestId,
            MERGED,
            new CiEventTrigger(
                QA_PATH,
                CiRunService.RELEASE_REQUEST_EVENT_NAME,
                null,
                new CiPipeline(
                    List.of(
                        new CiPipeline.CiStepDecl(
                            "alpine:3", "./mvnw verify", null, false, false, ""),
                        new CiPipeline.CiStepDecl(
                            "alpine:3", "./publish-userflows.sh", null, false, false, ""))),
                List.of(),
                new CiEventTrigger.Checkout("backingBranch", "mergedSha", false)),
            UUID.randomUUID().toString(),
            CiRunService.RELEASE_REQUEST_EVENT_NAME,
            Instant.parse("2026-09-29T09:07:06Z"),
            "{\"releaseRequestId\":\""
                + requestId
                + "\",\"backingBranch\":\"release/"
                + requestId
                + "\",\"mergedSha\":\""
                + MERGED
                + "\"}",
            QA_TRIGGER,
            null));
  }
}
