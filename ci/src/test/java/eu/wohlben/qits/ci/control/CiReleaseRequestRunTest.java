package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }
}
