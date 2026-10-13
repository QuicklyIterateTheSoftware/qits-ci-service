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
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A retry of a RELEASE run resumes at the first step that did not succeed in the run it retries
 * (qits-1222).
 *
 * <p>The incident: qits-artifacts-service's release run built its image, pushed the released tag and
 * submitted the SBOM in step 0, then failed in step 1. The retry ran step 0 again — a rebuild that
 * is not byte-identical — so it re-pushed the released tag with a different manifest, and
 * qits-publish refused the SBOM as different bytes under a published version. That retry could
 * never succeed. So the property every case here holds is <b>which steps were EXECUTED</b>, read off
 * {@link FakeCiStepRunner#executed()}: a carried step must never reach a runner.
 *
 * <p>Four boundaries are the point of the file, each a way the rule could be quietly wrong:
 *
 * <ul>
 *   <li>a release run's green prefix is carried and the retry can still finish green and announce;
 *   <li>a release run whose first step failed carries nothing, so a failed publish is published
 *       again exactly as before;
 *   <li>a QA run is re-asked whole — its retry asks for a verdict, and a verdict is every step's;
 *   <li>a re-composed pipeline that does not line up with the source's carries nothing.
 * </ul>
 */
@QuarkusTest
public class CiReleaseRetryResumeTest extends CiTestSupport {

  private static final String RELEASE_PATH = ".config/qits/ci-event-release.yml";

  /** Two steps, the incident's shape: the publish, then something after it. */
  private static final String RELEASE_TRIGGER =
      """
      event: SCMRelease
      checkout:
        branch: version
        sha: commitSha
      steps:
        - image: alpine:3
          script: ./publish-image.sh
        - image: alpine:3
          script: ./publish-docs.sh
      """;

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

  private static final String RELEASED_SHA = "f".repeat(40);

  private static final String MERGED_SHA = "e".repeat(40);

  private static final String VERSION = "2026.1011.223957";

  private static final StepResult RED = new StepResult(1, false, StepOutcome.OK, "boom");

  @Inject CiRunService service;
  @Inject CiEventTriggerService engine;
  @Inject FakeRunAnnouncer announcer;

  private String repoId;

  @BeforeEach
  void freshRepository() {
    repoId = "release-" + UUID.randomUUID().toString().substring(0, 8);
    fakeRunner.reset();
    announcer.reset();
  }

  @AfterEach
  void settle() throws Exception {
    suiteRunner.awaitIdle();
    fakeRunner.reset();
  }

  // --- a hand-written release pipeline -----------------------------------------------------------

  @Test
  public void aReleaseRetryRunsOnlyTheStepThatFailedAndFinishesGreen() throws Exception {
    fakeRunner.scriptSequence(1, RED);
    String original = service.onEventTrigger(releaseRun());
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.FAILED, service.requireRun(original).status);
    assertEquals(CiRunPhase.RELEASE, service.requireRun(original).phase);

    fakeRunner.executed().clear();
    CiRun retry = service.retry(original);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    // THE PROPERTY: the publish that already happened is never run again.
    assertEquals(List.of(1), executedSteps(retry.id), "only the step that did not succeed runs");

    CiRun refired = service.requireRun(retry.id);
    assertEquals(1, refired.carriedSteps);
    assertEquals(CiRunStatus.SUCCESS, refired.status, "carried steps count toward the verdict");

    List<CiStep> rows = service.stepsFor(retry.id);
    assertEquals(2, rows.size());
    CiStep carried = rows.get(0);
    assertEquals(CiStepStatus.SUCCESS, carried.status);
    assertTrue(
        carried.output.startsWith("[carried over from CI run " + original + ", where this step"),
        carried.output);
    assertTrue(carried.output.endsWith("ok step 0"), "and what it said there: " + carried.output);
    assertNull(carried.startedAt, "it did not run in this run, so it has no duration to sample");
    assertNull(carried.finishedAt);
    assertEquals(CiStepStatus.SUCCESS, rows.get(1).status);
    assertNotNull(rows.get(1).startedAt, "the executed step is measured as usual");

    // A green release run announces as one, naming the run whose red it supersedes.
    FakeRunAnnouncer.Announced green =
        announcer.announced().stream()
            .filter(a -> a.runId().equals(retry.id))
            .findFirst()
            .orElseThrow();
    assertEquals(original, green.retryOfRunId());
  }

  @Test
  public void aRetryOfTheResumedRunCarriesItsWholePrefix() throws Exception {
    // A chain, not a one-off: a carried row is a SUCCESS row like any other, so a retry of the
    // retry carries it again, plus whatever the retry itself finished green.
    fakeRunner.scriptSequence(1, RED, RED);
    String original = service.onEventTrigger(releaseRun());
    suiteRunner.awaitIdle();
    CiRun first = service.retry(original);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.FAILED, service.requireRun(first.id).status);

    fakeRunner.executed().clear();
    CiRun second = service.retry(first.id);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(1), executedSteps(second.id));
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(second.id).status);
    assertTrue(
        service.stepsFor(second.id).get(0).output.startsWith(
            "[carried over from CI run " + first.id),
        "it names the run it was carried from, which is the retry it re-fires");
  }

  @Test
  public void aReleaseRetryAfterAFailedFirstStepRunsEverything() throws Exception {
    // A publish that failed is published again — exactly what a retry did before this rule.
    fakeRunner.scriptSequence(0, RED);
    String original = service.onEventTrigger(releaseRun());
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.FAILED, service.requireRun(original).status);

    fakeRunner.executed().clear();
    CiRun retry = service.retry(original);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(0, 1), executedSteps(retry.id));
    CiRun refired = service.requireRun(retry.id);
    assertNull(refired.carriedSteps, "nothing carried, and the column says nothing");
    assertEquals(CiRunStatus.SUCCESS, refired.status);
    for (CiStep step : service.stepsFor(retry.id)) {
      assertNotNull(step.startedAt, "every row is an execution of this run");
    }
  }

  @Test
  public void anAutomaticRetryOfAnInfraFailureResumesTheSameWay() throws Exception {
    // autoRetry re-fires through refire, so a release run the host failed in its second step does not
    // publish its first step again either. LAUNCH_FAILED rather than CONNECTION_LOST: a publishing
    // run that lost its connection is never retried automatically (qits-1192).
    fakeRunner.scriptSequence(
        1, new StepResult(-1, false, StepOutcome.LAUNCH_FAILED, "docker refused"));
    // Set explicitly, CiAutoRetryTest's arrangement: another suite may have turned it off.
    int suiteMax = service.autoRetryMax();
    service.autoRetryMax(2);
    String original;
    try {
      original = service.onEventTrigger(releaseRun());
      suiteRunner.awaitIdle();
    } finally {
      service.autoRetryMax(suiteMax);
    }
    forgetLoadedEntities();

    List<CiRun> chain =
        service.runsFor(repoId).stream()
            .sorted(Comparator.comparing((CiRun r) -> r.createdAt))
            .toList();
    assertEquals(2, chain.size(), "one automatic retry");
    CiRun retry = chain.get(1);
    assertEquals(original, retry.retryOfRunId);
    assertNotNull(retry.retryReason, "fired automatically");
    assertEquals(List.of(0, 1), executedSteps(original));
    assertEquals(List.of(1), executedSteps(retry.id), "and it resumed at the step the host failed");
    assertEquals(CiRunStatus.SUCCESS, retry.status);
  }

  // --- what keeps today's behaviour --------------------------------------------------------------

  @Test
  public void aQaRunsRetryStillRunsEveryStep() throws Exception {
    // A release REQUEST's run is asked for a verdict, and a verdict is every step's: its green first
    // step is run again however the retry was asked for.
    fakeRunner.scriptSequence(1, RED);
    String original = service.onEventTrigger(qaRun());
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunPhase.RELEASE_REQUEST, service.requireRun(original).phase);
    assertEquals(CiStepStatus.SUCCESS, service.stepsFor(original).get(0).status);

    fakeRunner.executed().clear();
    CiRun retry = service.retry(original);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(0, 1), executedSteps(retry.id));
    assertNull(service.requireRun(retry.id).carriedSteps);
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(retry.id).status);
  }

  // --- a re-composed release pipeline ------------------------------------------------------------

  private static final String TWO_RELEASE_STEPS =
      """
      release:
        - image: alpine:3
          script: ./publish-image.sh
        - image: alpine:3
          script: ./publish-docs.sh
      """;

  @Test
  public void aReComposedPipelineOfTheSameShapeIsCarried() throws Exception {
    // The composed path, end to end: the retry's document is composed again and its images are
    // resolved as the source's rows recorded them, so the two line up and step 0 is carried.
    composeFrom(TWO_RELEASE_STEPS);
    fakeRunner.scriptSequence(1, RED);
    CiRun original = deliverRelease();
    assertEquals(CiRunStatus.FAILED, original.status);
    assertEquals(CiRunPhase.RELEASE, original.phase);

    fakeRunner.executed().clear();
    CiRun retry = service.retry(original.id);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(1), executedSteps(retry.id));
    assertEquals(1, service.requireRun(retry.id).carriedSteps);
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(retry.id).status);
  }

  @Test
  public void aReComposedPipelineThatNoLongerLinesUpRunsEverything() throws Exception {
    // The fixture changes the slot file at the released commit between the two runs, which no git
    // host can do; it is the only way to stage a retry whose re-composed pipeline differs in shape.
    // Steps that cannot be matched to the source's cannot be carried, so the retry runs them all.
    composeFrom(TWO_RELEASE_STEPS);
    fakeRunner.scriptSequence(1, RED);
    CiRun original = deliverRelease();
    assertEquals(CiRunStatus.FAILED, original.status);

    composeFrom(
        """
        release:
          - image: alpine:3
            script: ./publish-image.sh
          - image: alpine:3
            script: ./publish-docs.sh
          - image: alpine:3
            script: ./publish-more.sh
        """);
    fakeRunner.executed().clear();
    CiRun retry = service.retry(original.id);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(0, 1, 2), executedSteps(retry.id));
    assertNull(service.requireRun(retry.id).carriedSteps);
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(retry.id).status);
  }

  @Test
  public void aReComposedPipelineWhoseCarriedStepChangedImageRunsEverything() throws Exception {
    composeFrom(TWO_RELEASE_STEPS);
    fakeRunner.scriptSequence(1, RED);
    CiRun original = deliverRelease();

    composeFrom(
        """
        release:
          - image: alpine:3.20
            script: ./publish-image.sh
          - image: alpine:3
            script: ./publish-docs.sh
        """);
    fakeRunner.executed().clear();
    CiRun retry = service.retry(original.id);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(0, 1), executedSteps(retry.id));
    assertNull(service.requireRun(retry.id).carriedSteps);
  }

  // --- fixture -----------------------------------------------------------------------------------

  /** The step indices a runner was really asked to execute for one run, in order. */
  private List<Integer> executedSteps(String runId) {
    return fakeRunner.executed().stream()
        .filter(spec -> spec.runId().equals(runId))
        .map(CiStepRunner.StepSpec::stepIndex)
        .toList();
  }

  private String releasePayload() {
    return "{\"repository\":\"qits-target\",\"version\":\""
        + VERSION
        + "\",\"commitSha\":\""
        + RELEASED_SHA
        + "\",\"releaseRequestId\":\"rr-"
        + repoId
        + "\"}";
  }

  /** One matched {@code SCMRelease} for a hand-written two-step release pipeline. */
  private CiRunService.EventRun releaseRun() {
    return new CiRunService.EventRun(
        CiRepoRef.of(repoId, "qits", "qits-target"),
        VERSION,
        RELEASED_SHA,
        triggerParser.parse(RELEASE_PATH, RELEASE_TRIGGER),
        UUID.randomUUID().toString(),
        ReleaseJoin.RELEASE_EVENT_NAME,
        Instant.parse("2026-10-11T22:39:57Z"),
        releasePayload(),
        RELEASE_TRIGGER,
        null);
  }

  /** One matched {@code ReleaseRequestChanged}: the QA phase, same two-step shape. */
  private CiRunService.EventRun qaRun() {
    return new CiRunService.EventRun(
        CiRepoRef.of(repoId, "qits", "qits-target"),
        "release/rr-a",
        MERGED_SHA,
        triggerParser.parse(QA_PATH, QA_TRIGGER),
        UUID.randomUUID().toString(),
        CiRunService.RELEASE_REQUEST_EVENT_NAME,
        Instant.parse("2026-10-11T21:00:00Z"),
        "{\"releaseRequestId\":\"rr-a\",\"backingBranch\":\"release/rr-a\",\"mergedSha\":\""
            + MERGED_SHA
            + "\"}",
        QA_TRIGGER,
        null);
  }

  /** The repository's {@code release.yml} at the released commit, where a composed run reads it. */
  private void composeFrom(String slots) {
    fakeCandidates.setRefs(CiRepoRef.of(repoId, "qits", "qits-target"));
    fakeConfig.putTriggers(repoId, "main", "c".repeat(40));
    fakeConfig.putFile(repoId, RELEASED_SHA, CiReleaseSlotParser.CONFIG_PATH, slots);
  }

  /** Delivers the release event to the engine and answers the composed run it recorded. */
  private CiRun deliverRelease() throws Exception {
    engine.evaluate(
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(),
            CiReleaseComposer.RELEASE_EVENT,
            Instant.parse("2026-10-11T22:39:57Z"),
            releasePayload()));
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    List<CiRun> recorded = service.runsFor(repoId);
    assertEquals(1, recorded.size(), "one composed release run");
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, recorded.get(0).configPath);
    return recorded.get(0);
  }
}
