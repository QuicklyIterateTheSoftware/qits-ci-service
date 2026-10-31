package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The run's half of holding a slot for the release</b> (epic qits-732, qits-1788): which runs
 * {@link CiRunService} offers its seam to hold, when, and what a held run's close is.
 *
 * <p>A green release-request QA run composed from {@code release.yml} is offered — after its
 * terminal row is written and BEFORE its {@code BuildSuccessful} leaves, so a gate event answering
 * the verdict always finds the hold. A seam that holds it makes the run's close its own: {@code
 * runClosed} is not called. The run itself settles {@code SUCCESS}, is announced as ever, and has left
 * the active listing, because qits-projects holds a release request while {@code /runs/active} lists
 * a run of it. Every other run is never offered, and a seam that declines closes the run as before.
 * The seam's half — the switch, the daemon's capability, the lease — is {@code CiSlotHoldTest}'s.
 */
@QuarkusTest
public class CiSlotHoldOfferTest extends CiTestSupport {

  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: echo qa
      """;

  private static final String MERGED = "b".repeat(40);

  private static final String REQUEST_ID = "6c1f9a52-0000-4000-8000-000000000042";

  @Inject FakeRunAnnouncer announcer;

  private String repoId;

  @BeforeEach
  void freshRepository() {
    repoId = "held-" + UUID.randomUUID().toString().substring(0, 8);
    announcer.reset();
  }

  @Test
  public void aGreenComposedQaRunIsOfferedBeforeItsVerdictAndAHeldRunIsNotClosed() throws Exception {
    fakeRunner.holdsSlots(true);

    String runId = run(CiReleaseSlotParser.CONFIG_PATH, "ReleaseRequestChanged");

    assertEquals(1, fakeRunner.slotHolds().size(), fakeRunner.slotHolds().toString());
    FakeCiStepRunner.SlotHoldOffer offer = fakeRunner.slotHolds().get(0);
    assertEquals(0, offer.announcedBefore(), "the hold is asked for before BuildSuccessful leaves");
    assertEquals(runId, offer.hold().runId());
    assertEquals(repoId, offer.hold().repoId());
    assertEquals(REQUEST_ID, offer.hold().releaseRequestId());
    assertEquals("qits-target-rr-42", offer.hold().releaseRequestQualifiedId());
    assertEquals(MERGED, offer.hold().mergedSha(), "the fold the run built");

    assertFalse(fakeRunner.closed().contains(runId), "a held run's close is the hold's to make");
    CiRun row = runService.runRow(runId);
    assertEquals(CiRunStatus.SUCCESS, row.status, "the hold is no run status");
    assertEquals(CiRunPhase.RELEASE_REQUEST, row.phase);
    assertEquals(1, announcer.announced().size(), "and the verdict is announced as ever");
    assertEquals(runId, announcer.announced().get(0).runId());
    assertTrue(
        runService.activeRuns().stream().noneMatch(active -> active.id.equals(runId)),
        "/runs/active never lists a held slot: qits-projects would wait on it forever");
  }

  @Test
  public void aSeamThatDeclinesClosesTheRunAsBefore() throws Exception {
    String runId = run(CiReleaseSlotParser.CONFIG_PATH, "ReleaseRequestChanged");

    assertEquals(1, fakeRunner.slotHolds().size());
    assertTrue(fakeRunner.closed().contains(runId), "declined is today's close");
    assertEquals(1, announcer.announced().size());
  }

  @Test
  public void aRedQaRunIsNeverOffered() throws Exception {
    fakeRunner.holdsSlots(true);
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "red"));

    String runId = run(CiReleaseSlotParser.CONFIG_PATH, "ReleaseRequestChanged");

    assertEquals(List.of(), fakeRunner.slotHolds());
    assertTrue(fakeRunner.closed().contains(runId));
    assertEquals(CiRunStatus.FAILED, runService.runRow(runId).status);
  }

  @Test
  public void aQaRunFromAHandWrittenTriggerFileIsNeverOffered() throws Exception {
    // No composition, so no composed release half to continue in.
    fakeRunner.holdsSlots(true);

    String runId = run(".config/qits/ci-event-release-request.yml", "ReleaseRequestChanged");

    assertEquals(List.of(), fakeRunner.slotHolds());
    assertTrue(fakeRunner.closed().contains(runId));
  }

  @Test
  public void onlyAGreenReleaseRequestRunMayBeHeld() {
    CiRun qa = new CiRun();
    qa.id = "r-1";
    qa.repoId = "repo";
    qa.commitSha = MERGED;
    qa.phase = CiRunPhase.RELEASE_REQUEST;
    qa.releaseRequestId = REQUEST_ID;
    qa.configPath = CiReleaseSlotParser.CONFIG_PATH;
    assertTrue(CiRunService.slotHoldOf(qa, CiRunStatus.SUCCESS).isPresent());

    assertTrue(CiRunService.slotHoldOf(qa, CiRunStatus.TIMED_OUT).isEmpty());
    assertTrue(CiRunService.slotHoldOf(qa, CiRunStatus.CANCELLED).isEmpty());
    qa.phase = CiRunPhase.RELEASE;
    assertTrue(CiRunService.slotHoldOf(qa, CiRunStatus.SUCCESS).isEmpty(), "phase 2 holds nothing");
    qa.phase = CiRunPhase.RELEASE_REQUEST;
    qa.releaseRequestId = null;
    assertTrue(CiRunService.slotHoldOf(qa, CiRunStatus.SUCCESS).isEmpty());
  }

  /** One release-request run of {@code configPath}, executed to its end; its id. */
  private String run(String configPath, String eventName) throws Exception {
    String payload =
        "{\"projectId\":\"qits\",\"repoId\":\""
            + repoId
            + "\",\"repoName\":\"qits-target\",\"releaseRequestId\":\""
            + REQUEST_ID
            + "\",\"releaseRequestQualifiedId\":\"qits-target-rr-42\",\"backingBranch\":\"release/"
            + REQUEST_ID
            + "\",\"mergedSha\":\""
            + MERGED
            + "\"}";
    CiRunService.EventRun request =
        new CiRunService.EventRun(
            CiRepoRef.of(repoId, "qits", "qits-target"),
            "release/" + REQUEST_ID,
            MERGED,
            triggerParser.parse(configPath, QA_TRIGGER),
            UUID.randomUUID().toString(),
            eventName,
            Instant.parse("2026-10-31T09:07:06Z"),
            payload,
            QA_TRIGGER,
            null);
    String runId = runService.onEventTrigger(request);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    return runId;
  }
}
