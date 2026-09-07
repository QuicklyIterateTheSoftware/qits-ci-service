package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.control.CiEventTriggerParser;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What a client needs in order to draw a running job's progress, read off the surface it reads
 * it from.</b> Two facts land together on {@code GET /ci/api/runs/{runId}} and one of them on every
 * listing: {@code expectedStepDurationsMillis} says how wide each of the pipeline's segments should
 * be, and {@code live.startedAt} says how far into the segment in flight the run is.
 *
 * <p>The {@code ci} module proves what the prediction IS — the p95 arithmetic, the all-or-nothing
 * rule, the history window — against known samples ({@code CiRunExpectedDurationTest}). What can
 * only be said here is that both reach the wire: the prediction comes off the entity, so a listing
 * and a lookup get it from one mapping, and the live instant comes out of the in-memory relay, which
 * exists nowhere but in this module.
 *
 * <p><b>Staged against a genuinely occupied worker rather than a sleep</b>, the pattern {@code
 * CiPipelineBoundaryTest} established: the run worker is single-threaded and the fake's {@code
 * during} hook parks it inside step 0, so "a step is executing right now" is an instant this test
 * controls and the endpoint is asked about that instant rather than hoping to catch it.
 */
@QuarkusTest
public class CiRunProgressSurfaceTest {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-progress.yml";

  /** This class's own event name, so no other suite's trigger file can be fired by one of these. */
  private static final String EVENT_NAME = "CiProgressEvent";

  /** No slash, so {@code CiStepImage} leaves it alone and the staged history names what runs. */
  private static final String IMAGE = "alpine:3";

  private static final String TRIGGER_FILE =
      """
      event: CiProgressEvent
      steps:
        - image: alpine:3
          script: echo one
        - image: alpine:3
          script: echo two
      """;

  @Inject FakeCiStepRunner fakeRunner;
  @Inject CiRunService runService;
  @Inject CiEventTriggerParser triggerParser;
  @Inject CiRunRepository runs;
  @Inject CiStepRepository steps;

  private Instant nextFinish;

  @BeforeEach
  void resetRunner() {
    fakeRunner.reset();
    nextFinish = Instant.now().minusSeconds(86_400);
  }

  @Test
  public void aRunningJobCarriesItsExpectedSegmentsAndWhenTheLiveOneBegan() throws Exception {
    String repoId = "progress-" + UUID.randomUUID();
    stage(repoId, 0, 2_000, 2_000, 2_000);
    stage(repoId, 1, 5_000, 5_000, 5_000);

    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    CountDownLatch release = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          inStepZero.complete(spec.runId());
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    Instant beforeTheStep = Instant.now();
    String runId = accept(repoId);
    assertEquals(runId, inStepZero.get(30, TimeUnit.SECONDS));

    try {
      // The listing gets the prediction for free, off the same entity mapping the lookup uses —
      // which is the whole reason the platform chrome can draw a bar without a second request.
      Map<String, Object> listed = activeRun(runId);
      assertEquals(
          List.of(2_000, 5_000),
          listed.get("expectedStepDurationsMillis"),
          "one entry per planned step, in declaration order");
      assertNull(listed.get("live"), "a listing carries no live step, prediction or not");

      Map<String, Object> detail = run(runId);
      assertEquals(List.of(2_000, 5_000), detail.get("expectedStepDurationsMillis"));
      @SuppressWarnings("unchecked")
      Map<String, Object> live = (Map<String, Object>) detail.get("live");
      assertNotNull(live, "a step is executing, so there is a live step to report");
      assertEquals(0, live.get("stepIndex"));
      assertNotNull(live.get("startedAt"), "and it says when the host handed it over");
      assertTrue(
          !Instant.parse((String) live.get("startedAt")).isBefore(beforeTheStep),
          "host-stamped at the hand-over, so never earlier than the accept that preceded it");
    } finally {
      release.countDown();
    }

    Map<String, Object> finished = awaitTerminal(runId);
    assertNull(finished.get("live"), "the live surface dies with the run, the rows are the record");
    assertEquals(
        List.of(2_000, 5_000),
        finished.get("expectedStepDurationsMillis"),
        "the prediction is written once and is still what the run was accepted expecting");
  }

  /** Accepts one run of this class's pipeline and answers its id. */
  private String accept(String repoId) {
    String sha = String.format("%08x", repoId.hashCode()).repeat(5);
    return runService.onEventTrigger(
        new CiRunService.EventRun(
            CiRepoRef.of(repoId),
            "main",
            sha,
            triggerParser.parse(TRIGGER_PATH, TRIGGER_FILE),
            UUID.randomUUID().toString(),
            EVENT_NAME,
            Instant.now(),
            "{}",
            TRIGGER_FILE));
  }

  private Map<String, Object> run(String runId) {
    return given().get("/ci/api/runs/" + runId).then().statusCode(200).extract().jsonPath().getMap("");
  }

  private Map<String, Object> activeRun(String runId) {
    List<Map<String, Object>> active =
        given().get("/ci/api/runs/active").then().statusCode(200).extract().jsonPath().getList("runs");
    return active.stream()
        .filter(run -> runId.equals(run.get("id")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("run " + runId + " is not in the active listing"));
  }

  private Map<String, Object> awaitTerminal(String runId) throws Exception {
    for (int attempt = 0; attempt < 300; attempt++) {
      Map<String, Object> run = run(runId);
      if (!List.of("QUEUED", "RUNNING").contains(run.get("status"))) {
        return run;
      }
      Thread.sleep(50);
    }
    return fail("run " + runId + " never finished");
  }

  /** One finished run per sample, each carrying one successful step of the given duration. */
  private void stage(String repoId, int stepIndex, long... millis) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (long duration : millis) {
                CiRun run = new CiRun();
                run.id = UUID.randomUUID().toString();
                run.repoId = repoId;
                run.branch = "main";
                run.commitSha = String.format("%08x", repoId.hashCode()).repeat(5);
                run.status = CiRunStatus.SUCCESS;
                run.triggerType = CiTriggerType.EVENT;
                run.configPath = TRIGGER_PATH;
                run.triggerEventId = UUID.randomUUID().toString();
                run.createdAt = nextFinish;
                run.startedAt = nextFinish;
                run.finishedAt = nextFinish;
                runs.persist(run);
                CiStep step = new CiStep();
                step.id = UUID.randomUUID().toString();
                step.runId = run.id;
                step.stepIndex = stepIndex;
                step.image = IMAGE;
                step.status = CiStepStatus.SUCCESS;
                step.exitCode = 0;
                step.finishedAt = nextFinish;
                step.startedAt = nextFinish.minusMillis(duration);
                steps.persist(step);
                nextFinish = nextFinish.plusSeconds(60);
              }
            });
  }
}
