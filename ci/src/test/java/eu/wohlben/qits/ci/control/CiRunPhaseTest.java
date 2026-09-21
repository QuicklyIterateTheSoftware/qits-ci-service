package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A run knows which phase of a release it is, and the trigger event's NAME is what decides.</b>
 *
 * <p>A release is one pipeline of three phases and the release request in qits-projects is the
 * pipeline — there is no pipeline table here and no pipeline id. qits-ci's share is this column:
 * {@code ReleaseRequestChanged} naming a release request is {@link CiRunPhase#RELEASE_REQUEST} (P1,
 * QA, at the fold), {@code SCMRelease} naming one is {@link CiRunPhase#RELEASE} (P2, publish, at the
 * tag), and everything else is no phase at all.
 *
 * <p><b>The file this repository committed has no say in it, and that is the claim this class is
 * really here to state.</b> Every case below drives a repository whose trigger files are a
 * <em>hand-written</em> {@code .config/qits/ci-event-release-request.yml} / {@code
 * ci-event-release.yml} pair — the shape the whole estate was on when this class was written, and
 * which no repository is on any more — and both phases are recorded exactly as they are for a
 * repository on a composed {@code release.yml} (which {@code CiReleaseSlotTriggerTest} drives). So
 * the phase feature and the trigger-file migration were independent, and neither rollout waited on
 * the other. The fixture is kept on the retired shape deliberately: it is the generic trigger
 * grammar, which survives the split pipeline's retirement as the escape hatch it always was, and a
 * condition on {@code config_path} anywhere in the accept path would still make this false.
 *
 * <p><b>The third arm is the one live traffic takes.</b> An {@code SCMRelease} that names no release
 * request — every one published before qits-projects grew the field, and every replay of one out of
 * the durable log — records no phase, no warning and a row byte-identical to what it would have been
 * before this column existed. Null is permanent for a second reason as well: a dependency-bump run
 * is no part of a release and never will be.
 *
 * <p>What the column must NOT reach is asserted by the suites this class deliberately does not
 * touch: {@code CiRunOrderingTest}, {@code CiQueuedRunTest}, {@code CiRunClaimOrderTest} and {@code
 * CiEventTriggerDedupeTest} pass unchanged, because no queue rule, priority, dedupe key or supersede
 * rule reads a phase.
 */
@QuarkusTest
public class CiRunPhaseTest extends CiTestSupport {

  /** The hand-written QA file — what an unmigrated repository really commits. */
  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";

  /** And its hand-written publish twin. Two files, no {@code release.yml} anywhere. */
  private static final String PUBLISH_PATH = ".config/qits/ci-event-release.yml";

  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      when:
        - repoName: { exact: qits-phase-target }
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: ./mvnw verify
      """;

  private static final String PUBLISH_TRIGGER =
      """
      event: SCMRelease
      when:
        - repository: { exact: qits-phase-target }
      checkout:
        branch: version
        sha: commitSha
        optional: true
      steps:
        - image: alpine:3
          script: ./publish.sh
      """;

  private static final String HEAD = "a".repeat(40);
  private static final String MERGED = "b".repeat(40);
  private static final String RELEASED = "c".repeat(40);
  private static final String REQUEST_ID = "rr-phase-1";
  private static final String VERSION = "2026.916.101112";

  @Inject CiEventTriggerService engine;

  private String repoId;

  @BeforeEach
  void seedTheHandWrittenPair() {
    repoId = "phase-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(CiRepoRef.of(repoId, "qits", "qits-phase-target"));
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(QA_PATH, QA_TRIGGER),
        new EventTriggerFile(PUBLISH_PATH, PUBLISH_TRIGGER));
    announcer.reset();
  }

  @Inject FakeRunAnnouncer announcer;

  // --- the two phases, off a hand-written pair ----------------------------------------------------

  @Test
  public void aHandWrittenPairRecordsBothPhasesOfOneRelease() throws Exception {
    // P1 first, as the real sequence runs it: the request is folded and its QA pipeline is asked.
    deliver(releaseRequest(REQUEST_ID, MERGED));

    List<CiRun> afterQa = runService.runsFor(repoId);
    assertEquals(1, afterQa.size(), "the QA file matched, the publish file did not");
    CiRun qa = afterQa.get(0);
    assertEquals(QA_PATH, qa.configPath, "a hand-written file, not a composed release.yml");
    assertEquals(CiRunPhase.RELEASE_REQUEST, qa.phase);
    assertEquals(REQUEST_ID, qa.releaseRequestId);
    assertEquals("release/" + REQUEST_ID, qa.branch);
    assertEquals(MERGED, qa.commitSha);

    // P2: the tag is cut and its publish pipeline is asked. Same repository, same request, the other
    // half — and the id lands on the row from an SCMRelease, which only the QA half used to write.
    deliver(release(REQUEST_ID, VERSION, RELEASED));

    List<CiRun> both = runService.runsFor(repoId);
    assertEquals(2, both.size());
    CiRun publish = newestOf(both, PUBLISH_PATH);
    assertEquals(CiRunPhase.RELEASE, publish.phase);
    assertEquals(REQUEST_ID, publish.releaseRequestId, "the publish half writes the id too now");
    assertEquals(VERSION, publish.branch, "the tag is a ref, and that is the whole mechanism");
    assertEquals(RELEASED, publish.commitSha);

    // One release, two runs, two phases — and the pair is what a rerun is addressed by.
    assertNotEquals(qa.phase, publish.phase);
  }

  @Test
  public void aReleaseThatNamesNoRequestRecordsNoPhaseAndIsOtherwiseTheRunItAlwaysWas()
      throws Exception {
    // The arm every SCMRelease published before qits-projects grew the field takes, which on the day
    // this ships is all of them. No phase, no release request, no warning — the row is exactly what
    // it would have been before the column existed, and the run is green like any other.
    deliver(release(null, VERSION, RELEASED));

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(PUBLISH_PATH, run.configPath, "the same file, matched the same way");
    assertNull(run.phase, "no id on the event is no phase, permanently");
    assertNull(run.releaseRequestId);
    assertEquals(VERSION, run.branch);
    assertEquals(RELEASED, run.commitSha);
    assertEquals(CiRunStatus.SUCCESS, run.status, "and none of it is in the run's way");
  }

  @Test
  public void anEventOfAnotherKindCarryingAReleaseRequestIdStillHasNoPhase() throws Exception {
    // The gate is the event NAME. A releaseRequestId on some other payload is another context's
    // word, and a column that read any field of any payload would eventually record something
    // nobody meant — releaseRequestOf's rule, and the phase rides exactly it.
    String other = "other-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(CiRepoRef.of(other, "qits", "qits-phase-other"));
    fakeConfig.putTriggers(
        other,
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
            Instant.parse("2026-09-16T09:07:06Z"),
            "{\"releaseRequestId\":\"" + REQUEST_ID + "\"}"));

    CiRun run = runService.runsFor(other).get(0);
    assertNull(run.phase);
    assertNull(run.releaseRequestId);
  }

  @Test
  public void thePhaseAndTheReleaseRequestRideOutOnEveryAnnouncementAsPlainWords() throws Exception {
    deliver(releaseRequest(REQUEST_ID, MERGED));

    CiRun qa = runService.runsFor(repoId).get(0);
    assertEquals(1, announcer.announced().size());
    // The wire carries the word, not the enum: ci-events must not import this module's storage
    // model, which is the rule `status` and `outcome` already ride. The id beside it is already a
    // plain string and is qits-projects' own handle on the release.
    assertEquals("RELEASE_REQUEST", announcer.announced().get(0).phase());
    assertEquals(REQUEST_ID, announcer.announced().get(0).releaseRequestId());
    assertTrue(
        announcer.phasesOf(qa.id).stream().allMatch("RELEASE_REQUEST"::equals),
        "every transition of a phase's run says which phase it was: " + announcer.phasesOf(qa.id));
    assertTrue(
        announcer.releaseRequestsOf(qa.id).stream().allMatch(REQUEST_ID::equals),
        "and which release it was: " + announcer.releaseRequestsOf(qa.id));
    // The SET half of the invariant the three events state, on both announcements of this run and on
    // every transition of it: the pair is never one without the other, which is what lets a consumer
    // key a pipeline read model on it with no second lookup.
    assertTrue(
        announcer.statuses().stream()
            .filter(status -> status.runId().equals(qa.id))
            .allMatch(status -> status.phase() != null && status.releaseRequestId() != null),
        "phase and releaseRequestId are set together on every transition");
  }

  @Test
  public void thePublishHalfCorrelatesByTheIdBecauseItsBranchIsTheVersion() throws Exception {
    // The half the field was really added for. A P2 run's branch is the tag, so there is no
    // `release/<id>` in it to parse and a consumer deriving the correlation from the branch could
    // not close the pipeline at all — it would have to go through a second table. The id makes both
    // halves one read.
    deliver(release(REQUEST_ID, VERSION, RELEASED));

    CiRun publish = runService.runsFor(repoId).get(0);
    assertEquals(VERSION, publish.branch, "nothing here spells release/<id>");
    assertEquals(1, announcer.announced().size());
    assertEquals("RELEASE", announcer.announced().get(0).phase());
    assertEquals(REQUEST_ID, announcer.announced().get(0).releaseRequestId());
    assertTrue(
        announcer.releaseRequestsOf(publish.id).stream().allMatch(REQUEST_ID::equals),
        "on every transition too: " + announcer.releaseRequestsOf(publish.id));
  }

  @Test
  public void aReleaseThatNamesNoRequestAnnouncesNeitherHalfOfThePair() throws Exception {
    // The live arm, and the null half of the invariant reached through a release event rather than
    // through an ordinary run: no id on the event is no id and no phase on the row, so both keys are
    // omitted and the payload is what it was before either component existed.
    deliver(release(null, VERSION, RELEASED));

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(1, announcer.announced().size());
    assertNull(announcer.announced().get(0).phase());
    assertNull(announcer.announced().get(0).releaseRequestId());
    assertTrue(
        announcer.releaseRequestsOf(run.id).stream().allMatch(id -> id == null),
        "null together on every transition: " + announcer.releaseRequestsOf(run.id));
  }

  @Test
  public void anOrdinaryRunAnnouncesNeitherPhaseNorReleaseRequestAtAll() {
    // Null all the way out, which is what keeps an ordinary build's canonical payload byte-identical
    // to what it was before either component existed.
    String repo = UUID.randomUUID().toString();
    executePipeline(repo, "main", "d".repeat(40), "steps: []\n");

    assertNull(runService.runsFor(repo).get(0).phase);
    assertNull(runService.runsFor(repo).get(0).releaseRequestId);
    assertEquals(1, announcer.announced().size());
    assertNull(announcer.announced().get(0).phase());
    assertNull(announcer.announced().get(0).releaseRequestId());
  }

  // --- a retry asks for the same work, so it is the same phase ------------------------------------

  @Test
  public void aRetryCarriesThePhaseOfTheRunItReFires() throws Exception {
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    deliver(releaseRequest(REQUEST_ID, MERGED));
    CiRun failed = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.FAILED, failed.status);

    CiRun refired = runService.retry(failed.id);
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(CiRunPhase.RELEASE_REQUEST, runService.requireRun(refired.id).phase);
    assertEquals(REQUEST_ID, runService.requireRun(refired.id).releaseRequestId);
  }

  // --- the door, addressed by (repo, request, phase) -----------------------------------------------

  @Test
  public void aFailedPhaseIsReFiredByItsTripleAndTheNewRunIsTheSameWork() throws Exception {
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    deliver(releaseRequest(REQUEST_ID, MERGED));
    CiRun failed = runService.runsFor(repoId).get(0);

    CiRun refired =
        runService.retryReleaseRequestPhase(repoId, REQUEST_ID, CiRunPhase.RELEASE_REQUEST);
    runService.awaitIdle();
    forgetLoadedEntities();

    assertNotEquals(failed.id, refired.id);
    CiRun stored = runService.requireRun(refired.id);
    // The rerun checks out what that phase always checked out — nothing new resolved a ref here.
    assertEquals(failed.commitSha, stored.commitSha);
    assertEquals(failed.branch, stored.branch);
    assertEquals(failed.configPath, stored.configPath);
    assertEquals(CiRunPhase.RELEASE_REQUEST, stored.phase);
    assertEquals(failed.id, stored.retryOfRunId);
  }

  @Test
  public void theRerunDoorsVerdictNamesTheRunTheDoorChoseToReFire() throws Exception {
    // The row carrying `retryOfRunId` is asserted one case up; this is the ANNOUNCEMENT, and the two
    // are different claims. `qits ci retry` and this door are one code path today — the triple is
    // resolved to a run and handed to `retry(...)` — so "the rerun door behaves identically" is
    // argued rather than checked unless something on THIS side reads the verdict. The announce takes
    // the field off the row it re-loads, which is exactly the seam a refactor moves silently: a
    // `retry` that stopped copying the column, or an announcer that stopped reading it, leaves
    // qits-projects recording the re-fire's red beside the original's red as two repositories
    // failing, with every test of the by-id door still green.
    //
    // And the expectation is the run the DOOR resolved, never a run this test picked: asserting a
    // hard-coded id would pass just as happily if the triple had re-fired somebody else's phase.
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    deliver(releaseRequest(REQUEST_ID, MERGED));
    CiRun failed = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.FAILED, failed.status);

    CiRun refired =
        runService.retryReleaseRequestPhase(repoId, REQUEST_ID, CiRunPhase.RELEASE_REQUEST);
    runService.awaitIdle();
    forgetLoadedEntities();

    String chosen = runService.requireRun(refired.id).retryOfRunId;
    assertEquals(failed.id, chosen, "the door re-fired the phase's own failed run");
    assertEquals(
        chosen,
        failureFor(refired.id).retryOfRunId(),
        "and the re-fire's red verdict says whose verdict it supersedes");
    assertNull(
        failureFor(failed.id).retryOfRunId(),
        "while the run it re-fires supersedes nothing and carries nothing");
  }

  @Test
  public void aSucceededQaPhaseIsRefusedBecauseItsVerdictWasSpentOnTheTag() throws Exception {
    deliver(releaseRequest(REQUEST_ID, MERGED));
    assertEquals(CiRunStatus.SUCCESS, runService.runsFor(repoId).get(0).status);

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () ->
                runService.retryReleaseRequestPhase(
                    repoId, REQUEST_ID, CiRunPhase.RELEASE_REQUEST));

    // The message is the feature, not the status code: before this door existed the same press
    // reached the by-id retry, was accepted, and died in a step container cloning a branch the tag
    // creation had deleted.
    String message = refused.getMessage();
    assertTrue(message.contains("succeeded"), message);
    assertTrue(message.contains("nothing to ask again"), message);
    assertTrue(message.contains("spent on cutting the tag"), message);
    assertTrue(message.contains("release/" + REQUEST_ID), message);
  }

  @Test
  public void aSucceededPublishPhaseIsRefusedToo() throws Exception {
    deliver(release(REQUEST_ID, VERSION, RELEASED));
    assertEquals(CiRunPhase.RELEASE, runService.runsFor(repoId).get(0).phase);

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> runService.retryReleaseRequestPhase(repoId, REQUEST_ID, CiRunPhase.RELEASE));

    assertTrue(refused.getMessage().contains("published what the release names"),
        refused.getMessage());
  }

  @Test
  public void aPhaseThatHasNotRunIsAConflictRatherThanANotFound() throws Exception {
    // The repository is known — it has a QA run — and its publish phase simply has not happened.
    // That is a statement about the release's progress, not about the repository existing.
    deliver(releaseRequest(REQUEST_ID, MERGED));

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> runService.retryReleaseRequestPhase(repoId, REQUEST_ID, CiRunPhase.RELEASE));

    assertTrue(refused.getMessage().contains("has no RELEASE run"), refused.getMessage());
  }

  @Test
  public void aRepositoryThisInstanceHasNeverRecordedARunForIsANotFound() {
    assertThrows(
        NotFoundException.class,
        () ->
            runService.retryReleaseRequestPhase(
                "no-such-repo", REQUEST_ID, CiRunPhase.RELEASE_REQUEST));
  }

  // --- fixture -------------------------------------------------------------------------------------

  /** The {@code BuildFailed} announced for one run — {@code CiRunCancelAndRetryTest}'s helper. */
  private FakeRunAnnouncer.AnnouncedFailure failureFor(String runId) {
    return announcer.failed().stream()
        .filter(failure -> failure.runId().equals(runId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no failure announced for run " + runId));
  }

  private static CiRun newestOf(List<CiRun> runs, String configPath) {
    return runs.stream()
        .filter(run -> configPath.equals(run.configPath))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no run recorded against " + configPath));
  }

  private CiEventTriggerService.Arrival releaseRequest(String requestId, String mergedSha) {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(),
        CiRunService.RELEASE_REQUEST_EVENT_NAME,
        Instant.parse("2026-09-16T09:00:00Z"),
        "{\"repoName\":\"qits-phase-target\",\"releaseRequestId\":\""
            + requestId
            + "\",\"backingBranch\":\"release/"
            + requestId
            + "\",\"mergedSha\":\""
            + mergedSha
            + "\"}");
  }

  /** {@code requestId} null is the pre-cutover publisher: the key is simply not there. */
  private CiEventTriggerService.Arrival release(
      String requestId, String version, String commitSha) {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(),
        ReleaseJoin.RELEASE_EVENT_NAME,
        Instant.parse("2026-09-16T10:00:00Z"),
        "{\"repository\":\"qits-phase-target\",\"version\":\""
            + version
            + "\",\"commitSha\":\""
            + commitSha
            + "\""
            + (requestId == null ? "" : ",\"releaseRequestId\":\"" + requestId + "\"")
            + "}");
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }
}
