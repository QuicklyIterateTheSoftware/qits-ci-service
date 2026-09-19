package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.ExpectedStepDurations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>When the queue is expected to get to each run.</b> {@link CiQueueForecast} is pure — no I/O, no
 * clock, no CDI, and {@code now} is a parameter — so every claim about the arithmetic is made here,
 * against rows built in memory, with no database, no worker and no Quarkus. That is not a
 * convenience: an overrunning run, a run with no history and a slot count smaller than the number of
 * runs in flight are all states a live queue cannot be asked to enter on demand, and they are the
 * three the forecast is easiest to get wrong about.
 *
 * <p>Each case pins one decision. The serial case is first because {@code concurrentBuilds == 1} is
 * what actually ships, and the unknown cases are the bulk of the file because "no ETA" is a normal
 * answer here rather than a failure — a client has to be able to say <em>why</em> it has none.
 *
 * <p><b>Every expectation is milliseconds relative to {@code now}</b>, never a clock time, which is
 * the contract the class exists to make unbreakable: the epic requires approximate wording ("in
 * about 48 min") and a relative duration is the only form that renders as one.
 */
public class CiQueueForecastTest {

  private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

  private static final long SECOND = 1_000L;

  /** A run predicting exactly these step durations, or nothing at all when none are given. */
  private static CiRun run(String id, long... stepMillis) {
    CiRun run = new CiRun();
    run.id = id;
    run.repoId = "id-" + id;
    run.repoName = id;
    run.branch = "main";
    run.commitSha = "0".repeat(40);
    List<Long> steps = new ArrayList<>();
    for (long step : stepMillis) {
      steps.add(step);
    }
    run.expectedStepDurations = ExpectedStepDurations.encode(steps);
    return run;
  }

  /** A {@code RUNNING} run expecting {@code totalMillis}, {@code elapsedMillis} into it. */
  private static CiRun running(String id, long totalMillis, long elapsedMillis) {
    CiRun run = run(id, totalMillis);
    run.startedAt = NOW.minusMillis(elapsedMillis);
    return run;
  }

  /**
   * The queued runs as the claim order hands them over.
   *
   * <p>Built directly rather than run through {@link CiRunOrdering#explain(List)}, deliberately:
   * what is under test here is the arithmetic over a given order, and deriving that order from the
   * ordering's own criteria would make every case in this file also a case about priorities and
   * closures. That the ordering produces these is {@code CiRunOrderingTest}'s claim.
   */
  private static List<CiRunOrdering.OrderedRun> claimOrder(CiRun... runs) {
    List<CiRunOrdering.OrderedRun> ordered = new ArrayList<>(runs.length);
    for (int position = 0; position < runs.length; position++) {
      ordered.add(
          new CiRunOrdering.OrderedRun(
              runs[position], position, 1, null, 3, List.of(), CiRunOrdering.Selection.UNBLOCKED));
    }
    return ordered;
  }

  private static List<Long> starts(CiQueueForecast.Forecast forecast) {
    return forecast.queued().stream()
        .map(CiQueueForecast.QueuedForecast::expectedStartInMillis)
        .toList();
  }

  private static List<Long> finishes(CiQueueForecast.Forecast forecast) {
    return forecast.queued().stream()
        .map(CiQueueForecast.QueuedForecast::expectedFinishInMillis)
        .toList();
  }

  // --- the serial case, which is the one that ships -----------------------------------------------

  @Test
  public void withOneBuildSlotTheQueueIsPlainAdditionBehindWhatIsAlreadyRunning() {
    // `qits.ci.concurrent-builds` is 1 on every deployment today, so this is not a degenerate corner
    // — it is the arithmetic the platform actually reads. A run starts when the one before it ends,
    // and the first one starts when the slot the RUNNING run holds frees.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(running("in-flight", 30 * SECOND, 25 * SECOND)),
            claimOrder(run("a", 10 * SECOND), run("b", 20 * SECOND), run("c", 30 * SECOND)),
            1,
            NOW);

    assertEquals(Arrays.asList(5 * SECOND, 15 * SECOND, 35 * SECOND), starts(forecast));
    assertEquals(Arrays.asList(15 * SECOND, 35 * SECOND, 65 * SECOND), finishes(forecast));
    assertEquals(5 * SECOND, forecast.running().get(0).expectedFinishInMillis().longValue());
  }

  @Test
  public void anIdleEstateStartsTheQueueAtZeroRatherThanAtSomeUnknownFuture() {
    // No RUNNING run is not a missing fact, it is an empty estate: the slots are free NOW, so the
    // head of the queue starts at zero and the forecast is the queue's own durations stacked.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(), claimOrder(run("a", 10 * SECOND), run("b", 20 * SECOND)), 1, NOW);

    assertEquals(Arrays.asList(0L, 10 * SECOND), starts(forecast));
    assertEquals(Arrays.asList(10 * SECOND, 30 * SECOND), finishes(forecast));
  }

  @Test
  public void anEmptyQueueIsAnEmptyAnswerAndNotAnError() {
    // The ordinary state of a healthy platform. Both lists are total over their inputs, so nothing
    // is asserted about length here except that asking about nothing answers nothing.
    CiQueueForecast.Forecast forecast = CiQueueForecast.forecast(List.of(), List.of(), 1, NOW);

    assertEquals(List.of(), forecast.queued());
    assertEquals(List.of(), forecast.running());
  }

  // --- the slots ----------------------------------------------------------------------------------

  @Test
  public void withTwoBuildSlotsTheQueuePacksIntoWhicheverFreesFirst() {
    // Two idle slots, so the first two runs start at once and the third takes whichever of them
    // frees first — 10s, not 20s. This is the whole of the slot model: earliest-free wins, and the
    // answer is not the serial sum divided by anything.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(),
            claimOrder(run("a", 10 * SECOND), run("b", 20 * SECOND), run("c", 5 * SECOND)),
            2,
            NOW);

    assertEquals(Arrays.asList(0L, 0L, 10 * SECOND), starts(forecast));
    assertEquals(Arrays.asList(10 * SECOND, 20 * SECOND, 15 * SECOND), finishes(forecast));
  }

  @Test
  public void theSlotsAreSeededFewestRemainingFirstSoTheNextRunGetsTheSlotThatReallyFreesFirst() {
    // Two runs in flight with 3s and 40s left. The head of the queue waits 3s, not 40s — seeding in
    // arrival order instead would forecast against whichever container happened to be handed over
    // first, which is a fact about nothing.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(
                running("slow", 60 * SECOND, 20 * SECOND),
                running("quick", 10 * SECOND, 7 * SECOND)),
            claimOrder(run("next", 5 * SECOND)),
            2,
            NOW);

    assertEquals(List.of(3 * SECOND), starts(forecast));
    assertEquals(List.of(8 * SECOND), finishes(forecast));
  }

  @Test
  public void moreRunningRunsThanSlotsFoldOntoTheEarliestFreeSlotRatherThanBeingDropped() {
    // A shrunk `concurrent-builds`, or a leftover a predecessor was holding. Three runs in flight
    // and one slot: the surplus queues behind the rest exactly as a queued run would (2+4+9=15), so
    // the head of the queue waits for all of it. Taking the one smallest and discarding the others
    // would forecast a queue starting sooner than any process could start it.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(
                running("a", 10 * SECOND, 8 * SECOND),
                running("b", 10 * SECOND, 6 * SECOND),
                running("c", 10 * SECOND, SECOND)),
            claimOrder(run("next", SECOND)),
            1,
            NOW);

    assertEquals(List.of(15 * SECOND), starts(forecast));
  }

  @Test
  public void aSlotCountBelowOneIsClampedRatherThanRefused() {
    // Zero slots is not a queue that never moves, it is a configuration nothing here can usefully
    // answer for — and a read path is not where a misconfiguration should become an exception. It
    // reads as the serial case.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(), claimOrder(run("a", 10 * SECOND), run("b", 10 * SECOND)), 0, NOW);

    assertEquals(Arrays.asList(0L, 10 * SECOND), starts(forecast));
  }

  // --- what a RUNNING run contributes -------------------------------------------------------------

  @Test
  public void aRunPastItsPredictionContributesZeroRatherThanANegativeNumber() {
    // Clamping at zero is the honest floor. A negative remaining would pull the whole queue forward
    // — the forecast arguing that the future has already happened — so an overrun instead makes the
    // answer OPTIMISTIC, saying "any moment now" for as long as the run lasts. That price is stated
    // rather than hidden, and it is the right way round: the queue is never forecast to start
    // before a slot could possibly free.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(running("overrunning", 10 * SECOND, 90 * SECOND)),
            claimOrder(run("next", 5 * SECOND)),
            1,
            NOW);

    assertEquals(0L, forecast.running().get(0).expectedFinishInMillis().longValue());
    assertEquals(List.of(0L), starts(forecast));
    assertEquals(List.of(5 * SECOND), finishes(forecast));
  }

  @Test
  public void aRunningRunWithNoStartStampHasElapsedNothingYet() {
    // `started_at` is stamped at the hand-over, so a null is the setup window — the container has
    // still to be asked for, started and dialled back — rather than a missing fact. Reading it as
    // anything else would need a second timeline this service deliberately does not keep, so the
    // whole expected total is still ahead of it.
    CiRun unstamped = run("unstamped", 30 * SECOND);
    unstamped.startedAt = null;

    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(List.of(unstamped), claimOrder(run("next", 5 * SECOND)), 1, NOW);

    assertEquals(30 * SECOND, forecast.running().get(0).expectedFinishInMillis().longValue());
    assertEquals(List.of(30 * SECOND), starts(forecast));
  }

  // --- unknown poisons forward, and only forward --------------------------------------------------

  @Test
  public void aRunWithNoPredictionBlanksEverythingBehindItAndNothingAheadOfIt() {
    // The case the whole enum exists for, and it is the platform's ordinary state on the day a
    // pipeline grows a step. The run ahead keeps its ETAs; the unpredicted run knows when it starts
    // but not when it ends; everything behind it knows neither, and says so with a reason naming
    // whose prediction was missing rather than by disappearing from the listing.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(),
            claimOrder(run("ahead", 10 * SECOND), run("silent"), run("behind", 5 * SECOND)),
            1,
            NOW);

    assertEquals(Arrays.asList(0L, 10 * SECOND, null), starts(forecast));
    assertEquals(Arrays.asList(10 * SECOND, null, null), finishes(forecast));
    assertEquals(
        CiQueueForecast.Unknown.RUN_HAS_NO_PREDICTION,
        forecast.queued().get(1).expectedFinish().reason());
    assertEquals(
        CiQueueForecast.Unknown.RUN_AHEAD_HAS_NO_PREDICTION,
        forecast.queued().get(2).expectedStart().reason());
    assertEquals(
        CiQueueForecast.Unknown.RUN_AHEAD_HAS_NO_PREDICTION,
        forecast.queued().get(2).expectedFinish().reason());
    // The run ahead is untouched: an absence carries no reason, which is what makes the two states
    // impossible to confuse.
    assertNull(forecast.queued().get(0).expectedStart().reason());
  }

  @Test
  public void anUnpredictedRunningRunLeavesTheWholeQueueUnknown() {
    // Nobody knows when the slot it holds frees, so nobody knows when the queue moves at all — and
    // the reason says exactly that, rather than blaming the queued runs, whose own predictions are
    // perfectly good. Their FINISH is unknown for the same reason, since a finish nobody can place
    // is not made placeable by knowing how long the work takes.
    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(
            List.of(run("mystery")),
            claimOrder(run("a", 10 * SECOND), run("b", 20 * SECOND)),
            1,
            NOW);

    assertEquals(Arrays.asList(null, null), starts(forecast));
    assertEquals(Arrays.asList(null, null), finishes(forecast));
    assertEquals(
        CiQueueForecast.Unknown.RUNNING_RUN_HAS_NO_PREDICTION,
        forecast.queued().get(0).expectedStart().reason());
    assertEquals(
        CiQueueForecast.Unknown.RUNNING_RUN_HAS_NO_PREDICTION,
        forecast.running().get(0).expectedFinish().reason());
  }

  @Test
  public void anUnreadableStoredPredictionIsNoPredictionRatherThanAPartialOne() {
    // `ExpectedStepDurations.decode` answers null both for "no history" and for "unreadable", and
    // this class deliberately does not tell them apart: the column is written whole or not at all,
    // so a partial sum would be a guess wearing a measurement's clothes. A value nobody can fix
    // costs one field and not the listing.
    CiRun unreadable = run("unreadable");
    unreadable.expectedStepDurations = "{not an array}";

    CiQueueForecast.Forecast forecast =
        CiQueueForecast.forecast(List.of(), claimOrder(unreadable), 1, NOW);

    assertEquals(0L, forecast.queued().get(0).expectedStartInMillis().longValue());
    assertNull(forecast.queued().get(0).expectedFinishInMillis());
    assertEquals(
        CiQueueForecast.Unknown.RUN_HAS_NO_PREDICTION,
        forecast.queued().get(0).expectedFinish().reason());
  }

  // --- the claim order is carried, not re-derived -------------------------------------------------

  @Test
  public void eachForecastCarriesTheOrderingsOwnPositionRatherThanThisListsIndex() {
    // The ordering is the authority on where a run sits in the queue — a second answer computed here
    // would be a second answer — so a forecast over a slice of the queue still reports the position
    // the claim order gave it.
    CiRunOrdering.OrderedRun third =
        new CiRunOrdering.OrderedRun(
            run("third", 5 * SECOND), 2, 1, null, 3, List.of(), CiRunOrdering.Selection.UNBLOCKED);

    CiQueueForecast.Forecast forecast = CiQueueForecast.forecast(List.of(), List.of(third), 1, NOW);

    assertEquals(2, forecast.queued().get(0).position());
    assertEquals("third", forecast.queued().get(0).runId());
  }
}
