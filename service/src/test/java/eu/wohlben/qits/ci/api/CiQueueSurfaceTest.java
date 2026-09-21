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
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The queue as qits-ci answers it: claim order, positions, and an ETA that is a duration and
 * says when it has none.</b> Everything here is about {@code GET /ci/api/runs/queue} and the two
 * fields the run reads gained with it, asserted against the wire rather than against the functions
 * behind it.
 *
 * <p><b>The arithmetic itself is the {@code ci} module's</b> — {@code CiQueueForecastTest} stages
 * overrunning runs, shrunk slot counts and every unknown against rows built in memory, with no
 * database and no worker. What can only be said <em>here</em> is that the answer reaches a client:
 * that the order on the wire is the claim loop's own suggested order and not the listing's, that
 * one response's durations are all relative to one stated instant, and that an absence arrives as a
 * reason rather than as a gap.
 *
 * <p><b>Staged against a genuinely occupied worker rather than a sleep</b>, which is the pattern
 * {@code CiPipelineBoundaryTest} established and the only way a queue is real at an instant a test
 * controls: {@code qits.ci.concurrent-builds} is 1 in this suite, so a run parked inside a step
 * really does hold everything behind it.
 *
 * <p><b>Every case begins by waiting the queue quiet, and that is load-bearing rather than
 * hygiene.</b> The suite shares one application, so a run another class left in flight would be a
 * real member of the queue under test — and if it happened to carry no prediction it would poison
 * every ETA behind it with {@code RUN_AHEAD_HAS_NO_PREDICTION}, which is correct behaviour and a
 * failed assertion. Waiting for an empty queue makes the positions this class asserts absolute
 * rather than relative, which is what lets the ETA chain be asserted <em>exactly</em> instead of
 * approximately.
 */
@QuarkusTest
public class CiQueueSurfaceTest {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-queue.yml";

  /** This class's own event name, so no other suite's trigger file is fired by one of these. */
  private static final String EVENT_NAME = "CiQueueEvent";

  /** No slash, so {@code CiStepImage} leaves it alone and the staged history names what runs. */
  private static final String IMAGE = "alpine:3";

  private static final String TRIGGER_FILE =
      """
      event: CiQueueEvent
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
  private final List<String> seeded = new ArrayList<>();

  @BeforeEach
  void resetRunner() {
    fakeRunner.reset();
    nextFinish = Instant.now().minus(Duration.ofDays(400));
    seeded.clear();
  }

  @Test
  public void theQueueIsClaimOrderAndEveryEtaIsRelativeToTheOneStatedInstant() throws Exception {
    awaitQuietQueue();

    // A long run to hold the single slot, and two short ones behind it. Every repository has a
    // history, so nothing here is unpredicted and the whole chain is knowable — the unknown cases
    // are the next test's, deliberately kept apart from the arithmetic.
    String holding = predictedRepo(60_000, 60_000);
    String one = predictedRepo(10_000, 20_000);
    String two = predictedRepo(5_000, 5_000);

    CountDownLatch release = park();
    Instant before = Instant.now();
    String holdingRun = accept(holding);
    assertEquals(holdingRun, parkedRun.get(30, TimeUnit.SECONDS));
    String firstRun = accept(one);
    // Distinct accept instants, so `(createdAt, id)` — the ordering's final tie-break — is decided
    // by the timestamps rather than by two random UUIDs. Which of the two wins is still read off
    // the answer below rather than assumed.
    Thread.sleep(10);
    String secondRun = accept(two);

    try {
      Map<String, Object> body = queue();
      Instant generatedAt = Instant.parse((String) body.get("generatedAt"));
      assertTrue(
          !generatedAt.isBefore(before) && !generatedAt.isAfter(Instant.now()),
          "the instant the durations are relative to is this response's own, not a stored one");
      assertEquals(1, body.get("concurrentBuilds"), "the number the forecast really modelled with");

      // The running half: one run, holding the slot, with a remaining that is its predicted total
      // minus however long it has really been going. No position and no ordering — it is past being
      // ordered, and reporting a place in a queue it has left would be a number about nothing.
      List<Map<String, Object>> running = rows(body, "running");
      Map<String, Object> inFlight = row(running, holdingRun);
      long slotFrees = millis(inFlight, "expectedFinishInMillis");
      assertTrue(
          slotFrees > 0 && slotFrees <= 120_000,
          "a run 120s long that has just started has nearly all of it left, never more: " + slotFrees);
      assertNull(inFlight.get("expectedStartInMillis"), "it has started; there is no start to guess");
      assertNull(inFlight.get("queuePosition"));
      assertNull(inFlight.get("ordering"));
      assertNull(inFlight.get("predictionUnavailable"), "its pipeline has a history");

      // The queued half, in CLAIM order — which is the whole reason this route exists, since
      // /active answers the same rows newest-first and that is a different question.
      List<Map<String, Object>> queued = rows(body, "queued");
      assertEquals(2, queued.size(), "the queue was waited quiet, so it holds exactly these two");
      for (int index = 0; index < queued.size(); index++) {
        assertEquals(
            index,
            queued.get(index).get("queuePosition"),
            "a row's position is its index in this list, or the list is not the claim order");
      }

      Map<String, Object> earlier = row(queued, firstRun);
      Map<String, Object> later = row(queued, secondRun);
      if (position(earlier) > position(later)) {
        Map<String, Object> swap = earlier;
        earlier = later;
        later = swap;
      }
      assertEquals(position(earlier) + 1, position(later), "nothing else is between them");

      // THE CHAIN, asserted exactly rather than approximately. One slot, so the queue is plain
      // addition: the next run starts when the slot frees, and the one after it when that one ends.
      assertEquals(
          slotFrees,
          millis(earlier, "expectedStartInMillis"),
          "the next run starts when the slot the running one holds frees, to the millisecond");
      assertEquals(
          slotFrees + predictedTotal(earlier),
          millis(earlier, "expectedFinishInMillis"),
          "and finishes its own predicted total later");
      assertEquals(
          millis(earlier, "expectedFinishInMillis"),
          millis(later, "expectedStartInMillis"),
          "with one build slot the queue is addition; the second starts when the first ends");
      assertEquals(
          millis(later, "expectedStartInMillis") + predictedTotal(later),
          millis(later, "expectedFinishInMillis"));
      assertNull(earlier.get("predictionUnavailable"));
      assertNull(later.get("predictionUnavailable"));

      // Relative, never absolute. A predicted clock time is what this feature refuses to put on the
      // wire, so the refusal is asserted as an absence of the field rather than left to a reviewer.
      for (Map<String, Object> row : List.of(inFlight, earlier, later)) {
        assertFalseKey(row, "expectedStartAt");
        assertFalseKey(row, "expectedFinishAt");
      }

      // The ordering explains itself, which is what lets a UI say WHY a run is where it is rather
      // than leaving a person to infer it from the priority field — the criterion that is least
      // often the decisive one.
      @SuppressWarnings("unchecked")
      Map<String, Object> ordering = (Map<String, Object>) earlier.get("ordering");
      assertNotNull(ordering, "a queued run carries the ordering's own account of its place");
      assertEquals(position(earlier), ordering.get("position"), "and it names the place it explains");
      assertEquals(1, ordering.get("kindTier"), "an ordinary event run, not a release being built");
      assertEquals("UNBLOCKED", ordering.get("selection"), "no cycle, so the pass stayed topological");
      assertEquals(List.of(), ordering.get("topologyBlockers"), "nothing declared it downstream");
      assertNull(ordering.get("priority"), "these events state none");
      assertEquals(3, ordering.get("priorityRank"), "which ranks in the MIDDLE, never last");
    } finally {
      release.countDown();
    }
    awaitTerminal(holdingRun);
    awaitTerminal(firstRun);
    awaitTerminal(secondRun);
  }

  @Test
  public void aRunBehindAnUnpredictedOneHasNoEtaAndSaysWhichKindOfUnknownItIs() throws Exception {
    awaitQuietQueue();

    String holding = predictedRepo(60_000, 60_000);
    // No history at all: a repository's very first run, which is the platform's ordinary state on
    // the day a pipeline is added and the case most likely to be quietly dropped by a client.
    String never = "queue-unmeasured-" + UUID.randomUUID();
    String measured = predictedRepo(10_000, 10_000);

    CountDownLatch release = park();
    String holdingRun = accept(holding);
    assertEquals(holdingRun, parkedRun.get(30, TimeUnit.SECONDS));
    String unpredicted = accept(never);
    Thread.sleep(10);
    String behind = accept(measured);

    try {
      Map<String, Object> body = queue();
      List<Map<String, Object>> queued = rows(body, "queued");
      Map<String, Object> first = row(queued, unpredicted);
      Map<String, Object> second = row(queued, behind);
      assertTrue(
          position(first) < position(second),
          "the unmeasured run was accepted first, so it is claimed first");

      // The unpredicted run itself: WHEN it starts is perfectly well known — the slot frees when the
      // running run ends — and only how long it will take is not. Saying both are unknown would
      // throw away a fact this service has.
      assertNotNull(
          first.get("expectedStartInMillis"), "nothing ahead of it is unknown, so its start is not");
      assertNull(first.get("expectedFinishInMillis"));
      assertEquals(
          "RUN_HAS_NO_PREDICTION",
          first.get("predictionUnavailable"),
          "about its OWN pipeline — the sentence that heals the first time it runs green");
      assertNull(first.get("expectedStepDurationsMillis"), "which is the same fact one field over");

      // THE RULE THIS TEST EXISTS FOR. The run behind it has a perfectly good history of its own and
      // still has no ETA, because nobody knows when the slot in front of it frees. It must say so:
      // a row that silently omitted its ETA reads as "finished" to every client that has not read
      // the source, and a row showing a blank duration reads as "instant".
      assertNull(second.get("expectedStartInMillis"));
      assertNull(second.get("expectedFinishInMillis"));
      assertEquals(
          "RUN_AHEAD_HAS_NO_PREDICTION",
          second.get("predictionUnavailable"),
          "somebody ELSE's build is why — a different sentence, and one that heals on its own");
      assertNotNull(
          second.get("expectedStepDurationsMillis"),
          "and it is not that this run is unmeasured: it has its own prediction, unusable from here");

      // Nothing is skipped. Both lists are total, so the queue's length on the wire really is the
      // queue's — which is what makes a position comparable to it.
      assertEquals(2, queued.size());

      // The same two answers reach the single-run read, which is where a client that followed a link
      // from the queue lands.
      assertEquals("RUN_AHEAD_HAS_NO_PREDICTION", run(behind).get("predictionUnavailable"));
    } finally {
      release.countDown();
    }
    awaitTerminal(holdingRun);
    awaitTerminal(unpredicted);
    awaitTerminal(behind);
  }

  @Test
  public void theActiveListingCarriesStepBoundariesWithoutOutputAndTheRunReadStillCarriesIt()
      throws Exception {
    awaitQuietQueue();
    String repoId = predictedRepo(1_000, 1_000);

    // Parked in step ONE, so step ZERO has really ended and has a row — which is the only way to
    // assert that a LISTING drops a recorded step's output rather than that it had none to drop.
    CompletableFuture<String> inStepOne = new CompletableFuture<>();
    CountDownLatch release = new CountDownLatch(1);
    fakeRunner.during(
        1,
        spec -> {
          inStepOne.complete(spec.runId());
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });

    String runId = accept(repoId);
    assertEquals(runId, inStepOne.get(30, TimeUnit.SECONDS));

    try {
      Map<String, Object> listed = activeRun(runId);
      List<Map<String, Object>> listedSteps = rowsOf(listed, "steps");
      assertEquals(1, listedSteps.size(), "step 0 has ended, step 1 is still in flight");
      Map<String, Object> listedStep = listedSteps.get(0);
      assertEquals(0, listedStep.get("stepIndex"));
      // The three fields the consuming library reads off this listing, by these exact names.
      assertNotNull(listedStep.get("startedAt"), "a real boundary, host-stamped");
      assertNotNull(listedStep.get("finishedAt"), "and the other one");
      assertNull(listedStep.get("output"), "the only heavy part, and no listing renders it");

      @SuppressWarnings("unchecked")
      Map<String, Object> listedLive = (Map<String, Object>) listed.get("live");
      assertNotNull(listedLive, "the step in flight, so a bar knows which segment is filling");
      assertEquals(1, listedLive.get("stepIndex"));
      assertNotNull(listedLive.get("startedAt"));
      assertNull(listedLive.get("output"), "and here too");

      // The single-run read is unbroken: it is a person following one build, and the transcript is
      // the whole reason they opened it.
      Map<String, Object> detail = run(runId);
      List<Map<String, Object>> detailSteps = rowsOf(detail, "steps");
      assertEquals(1, detailSteps.size());
      assertEquals(
          "step 0 ran", detailSteps.get(0).get("output"), "the run read still carries the output");
      @SuppressWarnings("unchecked")
      Map<String, Object> detailLive = (Map<String, Object>) detail.get("live");
      assertNotNull(detailLive);
      assertEquals(1, detailLive.get("stepIndex"));
      assertNotNull(detailLive.get("output"), "and what the live step has printed so far");

      // A step is never handed over twice — once as a row and once as live — and the filter that
      // holds that is one implementation now, reached by both reads.
      assertEquals(
          0,
          listedSteps.stream().filter(step -> step.get("stepIndex").equals(1)).count(),
          "the live step has no row yet, so it must appear only as `live`");
    } finally {
      release.countDown();
    }
    awaitTerminal(runId);
  }

  @Test
  public void aReleaseRunSaysWhichPhaseItIsAndAnOrdinaryRunSaysItIsNoPhaseAtAll() {
    // Seeded terminal rather than triggered, and deliberately: what is under test is that the
    // mapper COPIES a column it never copied, which is a statement about the row reaching the wire.
    // Which trigger event decides the value is CiRunPhase's contract and is the ci module's to
    // prove. Terminal rows also mean no claim loop has any business with them.
    String qa = seedFinished(CiRunPhase.RELEASE_REQUEST);
    String publish = seedFinished(CiRunPhase.RELEASE);
    String ordinary = seedFinished(null);

    assertEquals("RELEASE_REQUEST", run(qa).get("phase"), "phase one, the QA run at release/<id>");
    assertEquals("RELEASE", run(publish).get("phase"), "phase two, the publish run at the tag");
    assertNull(
        run(ordinary).get("phase"),
        "null means NOT PART OF A RELEASE, never 'unknown' — the trigger event decides it, so a"
            + " run whose event named no release request is definitively outside a pipeline");

    // It rides every read, because it comes off the entity like every other column — so a client
    // holding a release request's runs can tell phase one from phase two without matching the
    // trigger event name against two strings it would have to know.
    List<String> listed =
        given()
            .get("/ci/api/runs?repositoryId=" + phaseRepo)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("runs.phase", String.class);
    assertEquals(3, listed.size(), "the three rows this case seeded, and nothing else");
    assertEquals(
        List.of("RELEASE", "RELEASE_REQUEST"),
        listed.stream().filter(phase -> phase != null).sorted().toList(),
        "both release runs state their phase in a listing, and the ordinary one states none");

    QuarkusTransaction.requiringNew().run(() -> seeded.forEach(runs::deleteById));
  }

  // ---------------------------------------------------------------------------------------------
  // Staging
  // ---------------------------------------------------------------------------------------------

  private CompletableFuture<String> parkedRun;

  /**
   * Occupies the sole build slot: the next run accepted parks inside its first step until the
   * returned latch is counted down, so everything behind it is genuinely {@code QUEUED} at an
   * instant this test controls.
   */
  private CountDownLatch park() {
    parkedRun = new CompletableFuture<>();
    CountDownLatch release = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          parkedRun.complete(spec.runId());
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    return release;
  }

  /** A fresh repository whose two steps have a history, so its runs predict exactly these totals. */
  private String predictedRepo(long stepZeroMillis, long stepOneMillis) {
    String repoId = "queue-" + UUID.randomUUID();
    stage(repoId, 0, stepZeroMillis, stepZeroMillis, stepZeroMillis);
    stage(repoId, 1, stepOneMillis, stepOneMillis, stepOneMillis);
    return repoId;
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

  /** The repository the phase case seeds into, so its listing holds exactly its three rows. */
  private String phaseRepo;

  /** A terminal run carrying a phase — no worker will ever look at it. */
  private String seedFinished(CiRunPhase phase) {
    if (phaseRepo == null) {
      phaseRepo = "queue-phase-" + UUID.randomUUID();
    }
    String id = UUID.randomUUID().toString();
    Instant at = Instant.now().minus(Duration.ofDays(500));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = phaseRepo;
              run.branch = phase == null ? "main" : "release/rr-1";
              run.commitSha = "b".repeat(40);
              run.status = CiRunStatus.SUCCESS;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = TRIGGER_PATH;
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName =
                  phase == CiRunPhase.RELEASE
                      ? "SCMRelease"
                      : phase == null ? EVENT_NAME : "ReleaseRequestChanged";
              run.releaseRequestId = phase == null ? null : "rr-queue-phase";
              run.phase = phase;
              run.createdAt = at;
              run.startedAt = at;
              run.finishedAt = at;
              runs.persist(run);
            });
    seeded.add(id);
    return id;
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
            TRIGGER_FILE,
            null));
  }

  // ---------------------------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------------------------

  private Map<String, Object> queue() {
    return given().get("/ci/api/runs/queue").then().statusCode(200).extract().jsonPath().getMap("");
  }

  private Map<String, Object> run(String runId) {
    return given()
        .get("/ci/api/runs/" + runId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getMap("");
  }

  private Map<String, Object> activeRun(String runId) {
    List<Map<String, Object>> active =
        given()
            .get("/ci/api/runs/active")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("runs");
    return active.stream()
        .filter(run -> runId.equals(run.get("id")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("run " + runId + " is not in the active listing"));
  }

  /**
   * Waits until nothing is running and nothing is queued.
   *
   * <p>See the class javadoc: the suite shares one application, so another class's run in flight is
   * a real member of the queue under test. Failing by name rather than quietly asserting against a
   * polluted queue is what keeps a red here about this change.
   */
  private void awaitQuietQueue() throws Exception {
    for (int attempt = 0; attempt < 600; attempt++) {
      Map<String, Object> body = queue();
      if (rows(body, "running").isEmpty() && rows(body, "queued").isEmpty()) {
        return;
      }
      Thread.sleep(50);
    }
    fail("the queue never went quiet: " + queue());
  }

  private Map<String, Object> awaitTerminal(String runId) throws Exception {
    for (int attempt = 0; attempt < 600; attempt++) {
      Map<String, Object> run = run(runId);
      if (!List.of("QUEUED", "RUNNING").contains(run.get("status"))) {
        return run;
      }
      Thread.sleep(50);
    }
    return fail("run " + runId + " never finished");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
    return (List<Map<String, Object>>) body.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rowsOf(Map<String, Object> row, String key) {
    return (List<Map<String, Object>>) row.get(key);
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String runId) {
    return rows.stream()
        .filter(run -> runId.equals(run.get("id")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("run " + runId + " is not in " + rows));
  }

  private static int position(Map<String, Object> row) {
    return ((Number) row.get("queuePosition")).intValue();
  }

  private static long millis(Map<String, Object> row, String field) {
    Object value = row.get(field);
    assertNotNull(value, field + " must be known here");
    return ((Number) value).longValue();
  }

  /** The sum of the row's own prediction — what the forecast adds to a start to get a finish. */
  @SuppressWarnings("unchecked")
  private static long predictedTotal(Map<String, Object> row) {
    List<Number> declared = (List<Number>) row.get("expectedStepDurationsMillis");
    assertNotNull(declared, "this row was staged with a history");
    return declared.stream().mapToLong(Number::longValue).sum();
  }

  /** No such key at all — the shape of "this service does not put a clock time on the wire". */
  private static void assertFalseKey(Map<String, Object> row, String key) {
    assertTrue(!row.containsKey(key), "the wire must carry no absolute predicted instant: " + key);
  }
}
