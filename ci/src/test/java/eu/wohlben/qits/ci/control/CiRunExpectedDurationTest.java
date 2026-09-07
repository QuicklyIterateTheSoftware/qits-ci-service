package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.entity.ExpectedStepDurations;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A run says how long it expects its steps to take, and it learns that from the steps it has
 * already run.</b> {@code ci_run.expected_step_durations} is written once, at accept, as the p95 of
 * what the same step of the same pipeline really took over its most recent successful runs — which
 * is what lets a client draw a segmented progress bar for a job that is still executing instead of a
 * spinner.
 *
 * <p><b>The history here is staged as rows rather than produced by running pipelines, and that is
 * deliberate rather than a shortcut.</b> What the prediction reads is persisted history — {@code
 * ci_step.started_at} and {@code ci_step.finished_at}, host-stamped at each step's end — so writing
 * those rows is supplying exactly the input the feature has, and it is the only way to say what the
 * p95 of a KNOWN sample must be. Driving the fake runner instead would stage durations of a few
 * microseconds each, which can prove that a number came out and nothing at all about which number.
 *
 * <p><b>The samples are chosen so the percentile lands on a data point.</b> {@code percentile_cont}
 * interpolates, and 0.95 of a small sample lands between two of them — so a sample whose top values
 * differ would pin a double's rounding rather than this service's arithmetic. Every case below
 * therefore repeats its top value, which also makes the assertion readable: the answer is the slow
 * end of what the step has been doing, and never the mean.
 *
 * <p>The all-or-nothing rule is the other half of the feature and it has three cases here — no
 * history at all, a pipeline that grew a step, and a step that changed its image. All three are one
 * answer, {@code null}, because a partial prediction would draw a segment that is a guess wearing a
 * measurement's clothes.
 */
@QuarkusTest
public class CiRunExpectedDurationTest extends CiTestSupport {

  /** The image every staged sample and most pipelines here name. No slash, so it resolves to itself. */
  private static final String IMAGE = "alpine:3";

  private static final String ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo one
      """;

  private static final String TWO_STEPS =
      """
      steps:
        - image: alpine:3
          script: echo one
        - image: alpine:3
          script: echo two
      """;

  private static final String ONE_STEP_OTHER_IMAGE =
      """
      steps:
        - image: busybox:1
          script: echo one
      """;

  @Inject CiRunMapper mapper;

  /**
   * Where the next staged sample finishes. It advances per sample, so history staged earlier in a
   * test is older than history staged later — which is what makes the recency window assertable at
   * all, since the query takes the newest rows by {@code finished_at}.
   */
  private Instant nextFinish;

  @BeforeEach
  void resetTheHistoryClock() {
    nextFinish = Instant.now().minusSeconds(86_400);
  }

  @Test
  public void aPipelineWithNoHistoryPredictsNothing() throws Exception {
    String repoId = repo();

    CiRun accepted = accept(repoId, ONE_STEP);

    assertNull(
        accepted.expectedStepDurations,
        "a repository's first run has nothing to predict from and must say so with null");
  }

  @Test
  public void thePredictionIsTheP95OfWhatEachStepReallyTook() throws Exception {
    String repoId = repo();
    // Sorted [1, 1, 9, 9, 9] seconds: p95 lands inside the repeated top value, so the answer is 9s
    // exactly. The mean would be 5.8s and the median 9s — this is the slow end on purpose.
    stage(repoId, 0, IMAGE, 1_000, 9_000, 1_000, 9_000, 9_000);
    stage(repoId, 1, IMAGE, 2_000, 2_000, 2_000);

    CiRun accepted = accept(repoId, TWO_STEPS);

    assertEquals(
        List.of(9_000L, 2_000L),
        ExpectedStepDurations.decode(accepted.expectedStepDurations),
        "one entry per planned step, in declaration order, each the p95 of that step's own history");
  }

  @Test
  public void onlyTheMostRecentSamplesCount() throws Exception {
    String repoId = repo();
    // Five very slow runs, then a full window of fast ones. Unwindowed, the slow five are the top
    // 17% of thirty samples and the p95 is 60s; windowed to the newest twenty-five they are not in
    // the sample at all.
    stage(repoId, 0, IMAGE, 60_000, 60_000, 60_000, 60_000, 60_000);
    long[] recent = new long[CiRunService.DURATION_SAMPLE_SIZE];
    java.util.Arrays.fill(recent, 1_000L);
    stage(repoId, 0, IMAGE, recent);

    CiRun accepted = accept(repoId, ONE_STEP);

    assertEquals(
        List.of(1_000L),
        ExpectedStepDurations.decode(accepted.expectedStepDurations),
        "the window is the recent past, so a pipeline that got faster stops being predicted slow");
  }

  @Test
  public void aPipelineThatGrewAStepPredictsNothing() throws Exception {
    String repoId = repo();
    stage(repoId, 0, IMAGE, 3_000, 3_000, 3_000);

    CiRun accepted = accept(repoId, TWO_STEPS);

    assertNull(
        accepted.expectedStepDurations,
        "step 1 has never run, so the run has no prediction at all — never a partial one");
  }

  @Test
  public void aStepThatChangedItsImagePredictsNothing() throws Exception {
    String repoId = repo();
    stage(repoId, 0, IMAGE, 3_000, 3_000, 3_000);

    CiRun accepted = accept(repoId, ONE_STEP_OTHER_IMAGE);

    assertNull(
        accepted.expectedStepDurations,
        "a step that changed its container is doing different work; its old rows are not evidence");
  }

  @Test
  public void anotherPipelineOfTheSameRepositoryIsNotHistoryForThisOne() throws Exception {
    String repoId = repo();
    stageAt(repoId, ".config/qits/ci-event-elsewhere.yml", 0, IMAGE, 3_000, 3_000, 3_000);

    CiRun accepted = accept(repoId, ONE_STEP);

    assertNull(
        accepted.expectedStepDurations,
        "a pipeline is (repository, trigger file); another file's steps say nothing about this one");
  }

  @Test
  public void aRetryPredictsFromTheHistoryItFindsRatherThanFromTheRunItRefires() throws Exception {
    String repoId = repo();
    stage(repoId, 0, IMAGE, 4_000, 4_000, 4_000);
    // The source predates its own history — its column is null — so a retry that carried the value
    // forward the way it carries priority forward would predict nothing.
    String sourceId = stageFinishedRun(repoId, triggerFile(ONE_STEP));

    CiRun retry = runService.retry(sourceId);
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(
        List.of(4_000L),
        ExpectedStepDurations.decode(reread(retry.id).expectedStepDurations),
        "a re-fire is fresh work and is predicted from what the pipeline has been doing since");
  }

  @Test
  public void aTriggerConfigThatWillNotParseCostsThePredictionAndNothingElse() throws Exception {
    String repoId = repo();
    stage(repoId, 0, IMAGE, 5_000, 5_000, 5_000);

    // The snapshot on the row is nonsense while the parsed trigger beside it is fine — a state the
    // engine cannot produce, staged here because the rule under test is what happens when the
    // prediction throws: one WARN, no prediction, and a run that is accepted exactly as it was.
    CiRunService.EventRun request = eventRun(repoId, "main", shaOf(repoId), ONE_STEP);
    CiRun accepted =
        accept(
            new CiRunService.EventRun(
                request.repo(),
                request.branch(),
                request.sha(),
                request.trigger(),
                request.eventId(),
                request.eventName(),
                request.occurredAt(),
                request.payload(),
                "steps: [ this is not a pipeline"));

    assertNotNull(accepted, "the run is accepted whatever the prediction could not do");
    assertEquals(CiRunStatus.SUCCESS, accepted.status, "and it runs to its ordinary verdict");
    assertNull(accepted.expectedStepDurations);
  }

  @Test
  public void theDtoCarriesThePredictionOnEveryRunItReturns() throws Exception {
    String repoId = repo();
    stage(repoId, 0, IMAGE, 7_000, 7_000, 7_000);

    CiRun accepted = accept(repoId, ONE_STEP);

    // The listing mappers get it off the entity — one mapping, so a listing and a lookup can never
    // disagree about it — and the hand-listed three-argument overload has to carry it through too.
    assertEquals(List.of(7_000L), mapper.toDto(accepted).expectedStepDurationsMillis());
    assertEquals(
        List.of(7_000L),
        mapper.toDto(accepted, List.of(), null).expectedStepDurationsMillis(),
        "the overload that re-lists every component by hand must not drop it");
  }

  @Test
  public void aStoredValueThatCannotBeReadIsNoPredictionRatherThanAnError() throws Exception {
    String repoId = repo();
    CiRun accepted = accept(repoId, ONE_STEP);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun stored = runs.findById(accepted.id);
              stored.expectedStepDurations = "[10000,\"ninety\"]";
            });
    forgetLoadedEntities();

    assertNull(
        mapper.toDto(reread(accepted.id)).expectedStepDurationsMillis(),
        "a column nobody can fix must cost the field and never the whole DTO");
  }

  /** A repository id nothing else in the suite shares, so no other test's rows are its history. */
  private static String repo() {
    return "durations-" + UUID.randomUUID();
  }

  /** A valid 40-character hex sha derived from the repository id. */
  private static String shaOf(String repoId) {
    return String.format("%08x", repoId.hashCode()).repeat(5);
  }

  /** Accepts and runs one pipeline, and answers the row as it was written. */
  private CiRun accept(String repoId, String stepsYaml) throws Exception {
    return accept(eventRun(repoId, "main", shaOf(repoId), stepsYaml));
  }

  private CiRun accept(CiRunService.EventRun request) throws Exception {
    runService.executeEventRun(request);
    runService.awaitIdle();
    forgetLoadedEntities();
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                runs.find("repoId = ?1 order by createdAt desc, id desc", request.repo().repoId())
                    .firstResult());
  }

  private CiRun reread(String runId) {
    return QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
  }

  /** Successful history for one step of this suite's own trigger file. */
  private void stage(String repoId, int stepIndex, String image, long... millis) {
    stageAt(repoId, TEST_TRIGGER_PATH, stepIndex, image, millis);
  }

  /**
   * One finished run per sample, each carrying one successful step of the given duration — which is
   * the shape the history really has, since a pipeline runs its step once per run.
   */
  private void stageAt(
      String repoId, String configPath, int stepIndex, String image, long... millis) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (long duration : millis) {
                CiRun run = historicalRun(repoId, configPath);
                runs.persist(run);
                CiStep step = new CiStep();
                step.id = UUID.randomUUID().toString();
                step.runId = run.id;
                step.stepIndex = stepIndex;
                step.image = image;
                step.status = CiStepStatus.SUCCESS;
                step.exitCode = 0;
                step.finishedAt = nextFinish;
                step.startedAt = nextFinish.minusMillis(duration);
                steps.persist(step);
                nextFinish = nextFinish.plusSeconds(60);
              }
            });
  }

  /** A terminal source row a retry can be fired off, carrying the trigger file it would reparse. */
  private String stageFinishedRun(String repoId, String triggerConfig) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRun run = historicalRun(repoId, TEST_TRIGGER_PATH);
              run.triggerConfig = triggerConfig;
              run.triggerEventName = TEST_EVENT_NAME;
              run.triggerEventOccurredAt = run.createdAt;
              run.triggerEventPayload = "{}";
              runs.persist(run);
              return run.id;
            });
  }

  private CiRun historicalRun(String repoId, String configPath) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repoId;
    run.branch = "main";
    run.commitSha = shaOf(repoId);
    run.status = CiRunStatus.SUCCESS;
    run.triggerType = CiTriggerType.EVENT;
    run.configPath = configPath;
    // A fresh id per row: two rows under one event id collide on the dedupe constraint, which is a
    // property of the schema this fixture must respect rather than work around.
    run.triggerEventId = UUID.randomUUID().toString();
    run.createdAt = nextFinish;
    run.startedAt = nextFinish;
    run.finishedAt = nextFinish;
    return run;
  }
}
