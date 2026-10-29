package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release-request QA pipeline, end to end: a {@code ReleaseRequestChanged} builds the fold it
 * names, the run records which request it serves, and the run's verdict is its outcome.
 *
 * <p><b>The trigger needs no engine knowledge and this file is where that is pinned.</b> {@code
 * event:} is matched against the frame's name as a string and {@code checkout:} resolves two dot
 * paths out of the payload, so a release-request pipeline is the existing grammar pointed at a new
 * event — "decide at main, build at the merged sha" comes out unchanged. What IS event-specific is
 * exactly one provenance column, and the two strings behind it are guarded by {@code
 * bus/ReleaseRequestChangedContractTest} in the service module.
 */
@QuarkusTest
public class CiReleaseRequestRunTest extends CiTestSupport {

  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";

  /** The reference file's shape: two steps, both of which gate, because every step gates. */
  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      when:
        - repoName: { exact: qits-ci-service }
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: qits/build-images/maven-base:latest
          script: ./mvnw verify
        - image: qits/build-images/maven-base:latest
          script: ./publish-userflows.sh
      """;

  private static final String HEAD = "a".repeat(40);
  private static final String MERGED = "b".repeat(40);
  private static final String REQUEST_ID = "rr-42";

  /** An unrelated, one-step pipeline for occupying the suite's one slot — see {@code occupyTheWorker}. */
  private static final String BLOCKER_STEPS =
      """
      steps:
        - image: alpine:3
          script: echo one
      """;

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer announcer;

  private String repoId;

  @BeforeEach
  void resetTriggerState() {
    repoId = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(CiRepoRef.of(repoId, "qits", "qits-ci-service"));
    announcer.reset();
  }

  // --- the trigger -------------------------------------------------------------------------------

  @Test
  public void aReleaseRequestChangedBuildsTheFoldAndRecordsTheRequestItServes() throws Exception {
    seedQa();
    String eventId = UUID.randomUUID().toString();

    deliver(arrival(eventId, payload(REQUEST_ID, MERGED)));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "one QA pipeline is one run");
    CiRun run = recorded.get(0);
    assertEquals(CiTriggerType.EVENT, run.triggerType);
    assertEquals(QA_PATH, run.configPath);
    assertEquals(eventId, run.triggerEventId);
    assertEquals("ReleaseRequestChanged", run.triggerEventName);
    // Decided at main, built at the fold: the trigger file was READ at main's head and the row
    // names the branch and sha the payload carried.
    assertTrue(
        fakeConfig.triggerReads().stream().anyMatch(read -> read.contains("@main#")),
        "the trigger file is discovered at main, never at the fold: " + fakeConfig.triggerReads());
    assertEquals("release/" + REQUEST_ID, run.branch);
    assertEquals(MERGED, run.commitSha);
    // The handle a cancellation and a retry address the work by. The sha above cannot be it: the
    // next re-fold replaces it.
    assertEquals(REQUEST_ID, run.releaseRequestId);
    assertEquals(CiRunStatus.SUCCESS, run.status);

    // The verdict returns keyed on the merged sha it received.
    assertEquals(1, announcer.announced().size());
    assertEquals(MERGED, announcer.announced().get(0).commitSha());
    assertEquals("release/" + REQUEST_ID, announcer.announced().get(0).branch());
  }

  @Test
  public void anEventOfAnotherKindRecordsNoReleaseRequest() throws Exception {
    // The column is written only for the event that names one. A `releaseRequestId` on any other
    // payload is some other context's word, and a provenance column that reads any field of any
    // payload eventually records something nobody meant.
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            ".config/qits/ci-event-other.yml",
            """
            event: SCMPublishCommit
            steps:
              - image: alpine:3
                script: "true"
            """));

    deliver(
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.parse("2026-09-03T09:07:06Z"),
            "{\"releaseRequestId\":\"" + REQUEST_ID + "\"}"));

    assertNull(runService.runsFor(repoId).get(0).releaseRequestId);
  }

  @Test
  public void aReleaseRequestChangedCarriesBothOrderingInputsOntoTheRow() throws Exception {
    // The whole path, at the seam a frame really arrives on: the engine matches the trigger file,
    // resolves the checkout out of the payload, and the accept reads the two fields the queue orders
    // by off that same payload. Nothing about the trigger file mentions either of them — this is the
    // generic grammar, and the ordering inputs ride along on the event that was going to arrive
    // anyway.
    seedQa();

    deliver(
        arrival(
            UUID.randomUUID().toString(),
            "{\"projectId\":\"qits\",\"repoId\":\"r-1\",\"repoName\":\"qits-ci-service\","
                + "\"releaseRequestId\":\""
                + REQUEST_ID
                + "\",\"backingBranch\":\"release/"
                + REQUEST_ID
                + "\",\"mergedSha\":\""
                + MERGED
                + "\",\"priority\":\"HIGHER\","
                + "\"downstreamTechnicalComponents\":[\"qits-ci-frontend\",\"qits-spa-ci\"]}"));

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(REQUEST_ID, run.releaseRequestId, "the provenance column is unaffected");
    assertEquals("HIGHER", run.priority);
    // Verbatim, as the canonical array text arrived — CiRunOrdering parses it once per pass and
    // nothing else reads it at all.
    assertEquals("[\"qits-ci-frontend\",\"qits-spa-ci\"]", run.downstreamRepos);
    assertEquals(CiRunStatus.SUCCESS, run.status, "and none of it is in the run's way");
  }

  @Test
  public void aReFoldThatStatesNeitherRecordsNeitherAndBuildsExactlyTheSame() throws Exception {
    // The compatibility arm and the live state of the rollout: a qits-projects that has not shipped
    // the enrichment publishes neither key, which is indistinguishable from stating none. Both
    // columns are null, the run is identical, and CiRunOrdering reads null as "unknown" — which
    // constrains nothing and ranks in the middle.
    seedQa();

    deliver(arrival(UUID.randomUUID().toString(), payload(REQUEST_ID, MERGED)));

    CiRun run = runService.runsFor(repoId).get(0);
    assertNull(run.priority);
    assertNull(run.downstreamRepos);
    assertEquals(CiRunStatus.SUCCESS, run.status);
  }

  // --- a failing step is a failed run, and that is the whole verdict ----------------------------

  @Test
  public void anyFailingStepFailsTheRunAndAnnouncesBuildFailed() throws Exception {
    // What the four cases here used to pin was an AND: the file's `gating:` flag and the failing
    // step's, and which half the run died in decided what the verdict was worth. The concept is gone
    // (ticket 9441bc6e) and the fact that remains is the one worth pinning — ANY failing step fails
    // the run, and a red run announces BuildFailed. The second step is the one that goes red here
    // precisely because it is the one that used to be exempt.
    seedQa();
    fakeRunner.script(1, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));

    deliver(arrival(UUID.randomUUID().toString(), payload(REQUEST_ID, MERGED)));

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.FAILED, run.status, "a red step is a red run");
    assertEquals(REQUEST_ID, run.releaseRequestId);

    assertEquals(List.of(), announcer.announced(), "a red run announces no BuildSuccessful");
    assertEquals(1, announcer.failed().size());
    FakeRunAnnouncer.AnnouncedFailure failure = announcer.failed().get(0);
    assertEquals("FAILED", failure.outcome());
    assertEquals(MERGED, failure.commitSha());
  }

  @Test
  public void aFailureInTheFirstStepFailsTheRunIdentically() throws Exception {
    // The mirror of the case above, and it is here to say that the two are the SAME case now. Which
    // step died decided the verdict's worth while `gating:` existed; it decides nothing any more.
    seedQa();
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));

    deliver(arrival(UUID.randomUUID().toString(), payload(REQUEST_ID, MERGED)));

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.FAILED, run.status);
    assertEquals(List.of(), announcer.announced());
    assertEquals(1, announcer.failed().size());
    assertEquals("FAILED", announcer.failed().get(0).outcome());
  }

  @Test
  public void aGreenRunAnnouncesBuildSuccessfulAndNothingElse() throws Exception {
    seedQa();

    deliver(arrival(UUID.randomUUID().toString(), payload(REQUEST_ID, MERGED)));

    assertEquals(CiRunStatus.SUCCESS, runService.runsFor(repoId).get(0).status);
    assertEquals(1, announcer.announced().size());
    assertEquals(MERGED, announcer.announced().get(0).commitSha());
    assertEquals(List.of(), announcer.failed());
  }

  // --- one fold, one gating run, whatever the event id (qits-1250) -----------------------------

  @Test
  public void aRepeatedFoldUnderANewEventIdAnswersItsRunningGatingRun() throws Exception {
    // The trigger door mints a fresh id for a call that names none, so the event constraint cannot
    // see this is the question already asked. The fold can: same repository, request, sha and file.
    String first = deliverFold(REQUEST_ID, MERGED);
    settle(first, CiRunStatus.RUNNING);

    assertEquals(
        first,
        triggerDirectly(REQUEST_ID, MERGED),
        "the second event is answered with the first run");
    assertEquals(1, runService.runsFor(repoId).size(), "and nothing is recorded");
  }

  @Test
  public void aRepeatedFoldAnswersItsGatingRunOnceThatRunIsGreen() throws Exception {
    String first = deliverFold(REQUEST_ID, MERGED);
    assertEquals(CiRunStatus.SUCCESS, runService.requireRun(first).status);

    assertEquals(first, triggerDirectly(REQUEST_ID, MERGED));
    assertEquals(1, runService.runsFor(repoId).size());
  }

  @Test
  public void aRepeatedFoldAnswersItsGatingRunOnceThatRunIsRed() throws Exception {
    // The harm this guards: a lineage-less duplicate beside a verdict is a second verdict, and
    // qits-projects rejects on any red one at the merged sha. A red fold is asked again by the rerun
    // door, which records lineage — not by firing the event again.
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    String first = deliverFold(REQUEST_ID, MERGED);
    assertEquals(CiRunStatus.FAILED, runService.requireRun(first).status);

    assertEquals(first, triggerDirectly(REQUEST_ID, MERGED));
    assertEquals(1, runService.runsFor(repoId).size());
  }

  @Test
  public void aCancelledGatingRunDoesNotStandInTheWayOfTheFold() throws Exception {
    String first = deliverFold(REQUEST_ID, MERGED);
    settle(first, CiRunStatus.CANCELLED);

    assertNotEquals(
        first, triggerDirectly(REQUEST_ID, MERGED), "a withdrawn question answers nothing");
    assertEquals(2, runService.runsFor(repoId).size());
  }

  @Test
  public void aReFoldAtANewShaIsANewGatingRun() throws Exception {
    String first = deliverFold(REQUEST_ID, MERGED);

    assertNotEquals(first, triggerDirectly(REQUEST_ID, "c".repeat(40)));
    assertEquals(2, runService.runsFor(repoId).size());
  }

  @Test
  public void anotherRequestAtTheSameShaIsItsOwnGatingRun() throws Exception {
    String first = deliverFold(REQUEST_ID, MERGED);

    String other = triggerDirectly("rr-43", MERGED);

    assertNotEquals(first, other);
    assertEquals("rr-43", runService.requireRun(other).releaseRequestId);
  }

  @Test
  public void anEventOfAnotherKindUnderANewIdIsStillARerun() throws Exception {
    // The fold rule reads the phase, so an event that is no RELEASE_REQUEST run keeps the door's
    // ordinary meaning: no id, no dedupe — even when its payload names a request.
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            ".config/qits/ci-event-other.yml",
            """
            event: SCMPublishCommit
            steps:
              - image: alpine:3
                script: "true"
            """));
    String payload = "{\"releaseRequestId\":\"" + REQUEST_ID + "\"}";

    for (int i = 0; i < 2; i++) {
      deliver(
          new CiEventTriggerService.Arrival(
              UUID.randomUUID().toString(),
              "SCMPublishCommit",
              Instant.parse("2026-09-03T09:07:06Z"),
              payload));
    }

    assertEquals(2, runService.runsFor(repoId).size());
  }

  @Test
  public void theRerunDoorStillBuildsARedFoldAgain() throws Exception {
    // retryReleaseRequestPhase goes through insertRetry, never onEventTrigger, so the fold rule
    // cannot reach it — pinned here beside the rule rather than trusted.
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    String first = deliverFold(REQUEST_ID, MERGED);
    assertEquals(CiRunStatus.FAILED, runService.requireRun(first).status);

    CiRun refired =
        runService.retryReleaseRequestPhase(repoId, REQUEST_ID, CiRunPhase.RELEASE_REQUEST);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertNotEquals(first, refired.id);
    assertEquals(first, runService.requireRun(refired.id).retryOfRunId);
    assertEquals(2, runService.runsFor(repoId).size());
  }

  @Test
  public void aCancelledRetryOfARedFoldDoesNotLeaveTheRedRunAnsweringIt() throws Exception {
    // qits-1656 / qits-system-service-rr-103: A failed, its automatic retry B carried the same
    // fold forward (same repo, request, sha and trigger file — see CiRunService#insertRetry), and B
    // was cancelled HELD_BY_PRE_RUN_GATE before it ever started. The old "newest-not-cancelled"
    // query skipped the cancelled B and answered a re-announced fold with the red A underneath it —
    // forever, since nothing about that answer ever changes. Only the NEWEST run may answer; if
    // that newest run is cancelled, the fold must have no answer at all.
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    String first = deliverFold(REQUEST_ID, MERGED);
    assertEquals(CiRunStatus.FAILED, runService.requireRun(first).status);

    // Occupy the suite's one slot with an unrelated repository's run, so the retry this test mints
    // next stays QUEUED long enough to be cancelled through the real door rather than having to
    // fake its status directly.
    CountDownLatch release = occupyTheWorker();

    CiRun retry = runService.retryReleaseRequestPhase(repoId, REQUEST_ID, CiRunPhase.RELEASE_REQUEST);
    assertEquals(CiRunStatus.QUEUED, runService.requireRun(retry.id).status, "the worker is busy");

    List<String> stopped =
        runService.cancelReleaseRequestRuns(
            repoId, REQUEST_ID, CiRunPhase.RELEASE_REQUEST, CiRunService.HELD_BY_PRE_RUN_GATE);
    assertEquals(List.of(retry.id), stopped);
    forgetLoadedEntities();
    assertEquals(CiRunStatus.CANCELLED, runService.requireRun(retry.id).status);

    // Free the worker before asking the fold again — triggerDirectly waits for the suite to go
    // idle, which it cannot do while the blocker still holds the one slot.
    release.countDown();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    String third = triggerDirectly(REQUEST_ID, MERGED);
    assertNotEquals(first, third, "the red run the cancelled retry replaced must not answer");
    assertNotEquals(retry.id, third, "the cancelled retry itself has no answer to give");
    assertNotNull(runService.requireRun(third).status, "a recorded run always has a status");
    assertEquals(3, runService.runsFor(repoId).size(), "the repeat is accepted as a fresh run");
  }

  @Test
  public void aRepeatedFoldAnswersARunningRetryRatherThanTheRedRunItReplaced() throws Exception {
    // The mirror of the case above: the newest run — the retry — is still live (or finished, red or
    // green), so IT is the answer, exactly as qits-1250 always meant for a non-retried run.
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    String first = deliverFold(REQUEST_ID, MERGED);
    assertEquals(CiRunStatus.FAILED, runService.requireRun(first).status);

    CiRun retried =
        runService.retryReleaseRequestPhase(repoId, REQUEST_ID, CiRunPhase.RELEASE_REQUEST);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    settle(retried.id, CiRunStatus.RUNNING);

    assertEquals(
        retried.id,
        triggerDirectly(REQUEST_ID, MERGED),
        "the retry is the newest, uncancelled run, so it answers the repeat rather than the red run"
            + " it replaced");
    assertEquals(2, runService.runsFor(repoId).size(), "nothing new is recorded");
  }

  /**
   * Parks the suite's one slot inside an unrelated repository's first step, which is what makes the
   * next run genuinely QUEUED rather than relying on a sleep — the {@code CiRunCancelAndRetryTest}
   * arrangement. Returns the latch the caller opens to let it finish; the caller must do so (and
   * then {@code suiteRunner.awaitIdle()}) before asking the suite to run anything else.
   */
  private CountDownLatch occupyTheWorker() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    fakeRunner.during(
        0,
        spec -> {
          if (!inStepZero.isDone()) {
            inStepZero.complete(spec.runId());
            try {
              release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    String blockerRepo = "blocker-" + UUID.randomUUID();
    runService.onEventTrigger(eventRun(blockerRepo, "main", "d".repeat(40), BLOCKER_STEPS));
    inStepZero.get(20, TimeUnit.SECONDS);
    return release;
  }

  // --- fixture -----------------------------------------------------------------------------------

  private void seedQa() {
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(QA_PATH, QA_TRIGGER));
  }

  private static String payload(String requestId, String mergedSha) {
    return "{\"projectId\":\"qits\",\"repoId\":\"r-1\",\"repoName\":\"qits-ci-service\","
        + "\"releaseRequestId\":\""
        + requestId
        + "\",\"backingBranch\":\"release/"
        + requestId
        + "\",\"mergedSha\":\""
        + mergedSha
        + "\"}";
  }

  private CiEventTriggerService.Arrival arrival(String eventId, String payload) {
    return new CiEventTriggerService.Arrival(
        eventId, "ReleaseRequestChanged", Instant.parse("2026-09-03T09:07:06Z"), payload);
  }

  /** Seeds the QA file, builds one fold through the engine and answers the run it recorded. */
  private String deliverFold(String requestId, String mergedSha) throws Exception {
    seedQa();
    deliver(arrival(UUID.randomUUID().toString(), payload(requestId, mergedSha)));
    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    return recorded.get(0).id;
  }

  /**
   * The same fold offered again under a fresh event id, straight at the accept seam — what the
   * trigger door does with a call that names no id — answering the run id it was given.
   */
  private String triggerDirectly(String requestId, String mergedSha) throws Exception {
    String eventId = UUID.randomUUID().toString();
    String payload = payload(requestId, mergedSha);
    CiRunService.EventRun request =
        new CiRunService.EventRun(
            CiRepoRef.of(repoId, "qits", "qits-ci-service"),
            "release/" + requestId,
            mergedSha,
            triggerParser.parse(QA_PATH, QA_TRIGGER),
            eventId,
            "ReleaseRequestChanged",
            Instant.parse("2026-09-03T09:07:06Z"),
            payload,
            QA_TRIGGER,
            null);
    String runId = runService.onEventTrigger(request);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    return runId;
  }

  /** Puts a run in a status directly — the fold rule reads nothing but the row. */
  private void settle(String runId, CiRunStatus status) {
    QuarkusTransaction.requiringNew().run(() -> runs.findById(runId).status = status);
    forgetLoadedEntities();
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
  }
}
