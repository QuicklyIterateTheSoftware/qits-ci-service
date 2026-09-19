package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.ExpectedStepDurations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>When the queue is expected to get to each run.</b> One pure function over what is {@code
 * RUNNING}, what is {@code QUEUED} in claim order, how many build slots there are and what time it
 * is: no I/O, no clock, no CDI, no state. {@code now} is a <em>parameter</em> and there is no {@code
 * Instant.now()} anywhere below this line.
 *
 * <p><b>That purity is what makes two doctrines true rather than hoped for.</b> The first is this
 * repository's standing one — a restart re-derives the same answer — which holds here for the same
 * reason it holds for {@link CiRunOrdering}: the same rows and the same instant produce the same
 * forecast in whichever process is asked, so a redeploy mid-queue does not move anybody's ETA. The
 * second is testability: every claim about the arithmetic below is made against rows built in
 * memory, with no database, no worker and no Quarkus, which is what lets the interesting cases (an
 * overrunning run, an unpredicted one, a shrunk slot count) be staged at all — none of them is a
 * state a live queue can be asked to enter on demand.
 *
 * <h2>Every millisecond here is RELATIVE to {@code now}</h2>
 *
 * <p>Never an absolute clock time, and that is a contract rather than a convenience. The feature
 * this serves requires approximate wording — "in about 48 min" — and forbids a clock time outright,
 * because a clock time is a promise: it is read in the reader's timezone, it is compared to a watch,
 * and it is wrong by however long the page has been open. A relative duration is the only form that
 * degrades honestly, it is the only form a client can re-render without asking again, and making it
 * the <em>only</em> thing this class can produce is what keeps a caller from inventing the other one
 * out of an absolute instant it was handed.
 *
 * <h2>The arithmetic</h2>
 *
 * <p><b>A run's expected total is the sum of its {@link CiRun#expectedStepDurations}, all or
 * nothing.</b> {@link ExpectedStepDurations#decode(String)} answers null both for "this run has no
 * history to predict from" and for "the stored value is unreadable", and this class does not tell
 * the two apart, because the column is deliberately written whole or not at all: a partial array is
 * not a shape that exists, so a partial sum would be a guess wearing a measurement's clothes. Null
 * in means {@link Unknown#RUN_HAS_NO_PREDICTION} out, every time.
 *
 * <p><b>A {@code RUNNING} run's remaining time is {@code max(0, expectedTotal - elapsed)}.</b>
 * Clamping at zero is the honest floor: a run that has overrun its p95 is a run about which the
 * prediction has been spent, and the alternative — a negative remaining, which would pull the whole
 * queue forward — would be the forecast arguing that the future has already happened. The price is
 * stated rather than hidden: for an overrunning run the forecast is <b>optimistic</b>, it says "any
 * moment now" for as long as the run lasts, and it does not pretend otherwise. A {@code RUNNING} run
 * whose {@link CiRun#startedAt} is null has elapsed <b>zero</b> — the stamp is written when the step
 * is handed over, so a null is the setup window (the container has still to be asked for, started
 * and dialled back) rather than a missing fact, and reading it as anything else would need a second
 * timeline this service deliberately does not keep.
 *
 * <p><b>The slots are the model and they are the whole of it.</b> {@code concurrentBuilds} slots,
 * each holding the instant it frees; seeded with the {@code RUNNING} runs' remaining times,
 * fewest-remaining first, and padded with zero for every idle slot. Then the queued runs in claim
 * order, each into the earliest-free slot: its start is that slot's free-at, its finish is that plus
 * its own expected total, and the slot frees at that finish. With {@code concurrentBuilds == 1} this
 * degenerates to plain addition — each run starts when the one before it ends — which is worth
 * saying out loud because it is the case that actually ships.
 *
 * <p><b>More {@code RUNNING} runs than slots is a real shape, not a contradiction to refuse.</b> A
 * shrunk {@code qits.ci.concurrent-builds}, or a leftover a predecessor was holding, both produce
 * it. The extras are <em>folded onto the earliest-free slot</em> rather than dropped: the remainings
 * are seeded smallest-first and each one is added to whichever slot frees soonest, so the surplus
 * work queues behind the rest exactly as a queued run would. Taking the {@code concurrentBuilds}
 * smallest and discarding the others was the alternative and it is worse in the direction that
 * matters — it would forecast a queue that starts sooner than any process could possibly start it,
 * by pretending work in flight is not in flight.
 *
 * <p><b>{@code concurrentBuilds < 1} is clamped to 1.</b> Zero slots is not a queue that never
 * moves, it is a configuration nothing here can usefully answer for, and a division of the queue by
 * a number nobody meant is not worth an exception on a read path.
 *
 * <h2>Unknown poisons forward, and only forward</h2>
 *
 * <p>An unpredicted run breaks the chain at itself and behind itself, never ahead of it:
 *
 * <ul>
 *   <li>Any {@code RUNNING} run with no prediction makes <em>every</em> queued run's start unknown
 *       with {@link Unknown#RUNNING_RUN_HAS_NO_PREDICTION} — nobody knows when the slot it holds
 *       frees, so nobody knows when the queue moves at all.
 *   <li>A queued run ahead of this one with no prediction makes this one's start unknown with {@link
 *       Unknown#RUN_AHEAD_HAS_NO_PREDICTION}, for the same reason one slot over.
 *   <li>This run's own total being unknown makes its <em>finish</em> unknown with {@link
 *       Unknown#RUN_HAS_NO_PREDICTION} even when its start is perfectly well known — it is about to
 *       start and nobody knows for how long.
 * </ul>
 *
 * <p>Runs <b>ahead</b> of an unpredicted one keep their ETAs untouched. That is the point of doing
 * this in claim order at all: the first run with no history would otherwise blank the whole listing,
 * which is exactly the platform's ordinary state on the day a pipeline grows a step.
 *
 * <p><b>Absence is always a reason, never a bare null</b> — see {@link Eta}. A client that cannot
 * say <em>why</em> it has no ETA has only the option of silently omitting the row, and a row that
 * disappears reads as "finished" to everyone who has not read this source.
 *
 * <p>Plain Java, like everything else in {@code ci/}: no JAX-RS, no websockets, no {@code
 * java.net.http}. What renders this is somebody else's problem and always over a seam.
 */
public final class CiQueueForecast {

  private CiQueueForecast() {}

  /**
   * Why a forecast has no number, in the three shapes the arithmetic can produce.
   *
   * <p>Each names <b>whose</b> prediction was missing, not merely that one was, because those are
   * three different sentences to the person waiting: "this build has never run before" is about
   * their own pipeline and is permanent until it has history, while "the build in front of you has
   * not" and "the build currently running has not" are about somebody else's and will heal on their
   * own.
   */
  public enum Unknown {
    /** This run's own {@link CiRun#expectedStepDurations} decoded to nothing. */
    RUN_HAS_NO_PREDICTION,

    /** A run earlier in the claim order had no prediction, so the slot's free-at is unknown. */
    RUN_AHEAD_HAS_NO_PREDICTION,

    /** A {@code RUNNING} run had no prediction, so no slot's free-at is known at all. */
    RUNNING_RUN_HAS_NO_PREDICTION
  }

  /**
   * A duration in milliseconds relative to {@code now}, or the reason there is none.
   *
   * <p><b>Exactly one of the two is set and the factories are the only way in</b>, which is the
   * whole reason this is a record rather than two loose fields on the forecast: a {@code (Long,
   * Unknown)} pair a caller can fill in freely has two incoherent states — both present, which
   * invites a reader to prefer one, and both absent, which is a bare null with extra steps. {@link
   * #known(long)} and {@link #unknown(Unknown)} make those unconstructible, so {@link #isKnown()} is
   * a total question with a total answer.
   */
  public record Eta(Long millis, Unknown reason) {

    /** A real forecast: {@code millis} from {@code now}. */
    public static Eta known(long millis) {
      return new Eta(millis, null);
    }

    /** No forecast, and the reason there is none. */
    public static Eta unknown(Unknown reason) {
      return new Eta(null, reason);
    }

    /** Whether this carries a number; false means {@link #reason()} says why it does not. */
    public boolean isKnown() {
      return millis != null;
    }
  }

  /**
   * One queued run's forecast: where it sits in the claim order, and the two instants it is about.
   *
   * <p>{@link #position} is {@link CiRunOrdering.OrderedRun#position()} carried through unchanged
   * rather than re-derived from this list's index — the ordering is the authority on where a run
   * sits, and a second answer computed here would be a second answer.
   */
  public record QueuedForecast(String runId, int position, Eta expectedStart, Eta expectedFinish) {

    /** The start in milliseconds from {@code now}, or null; {@link #expectedStart()} says why. */
    public Long expectedStartInMillis() {
      return expectedStart.millis();
    }

    /** The finish in milliseconds from {@code now}, or null; {@link #expectedFinish()} says why. */
    public Long expectedFinishInMillis() {
      return expectedFinish.millis();
    }
  }

  /** One {@code RUNNING} run's forecast: how much longer it is expected to hold its slot. */
  public record RunningForecast(String runId, Eta expectedFinish) {

    /** The finish in milliseconds from {@code now}, or null; {@link #expectedFinish()} says why. */
    public Long expectedFinishInMillis() {
      return expectedFinish.millis();
    }
  }

  /**
   * The whole answer: every queued run in claim order, and every {@code RUNNING} run in the order it
   * was handed over.
   *
   * <p>Both lists are total — a run that was passed in appears, with reasons rather than by being
   * omitted — because a forecast that drops its hard cases is a forecast whose length cannot be
   * compared to the queue's.
   */
  public record Forecast(List<QueuedForecast> queued, List<RunningForecast> running) {}

  /**
   * Forecast the queue.
   *
   * <p><b>The queued runs are taken as {@link CiRunOrdering.OrderedRun}s and that is a deliberate
   * refusal of the more convenient signature.</b> This arithmetic is only meaningful over the claim
   * order — "the run ahead of you" is the whole of what it computes with — and a {@code
   * List<CiRun>} parameter would accept the database's own row order, or a listing's, silently and
   * with a plausible-looking answer. Taking the ordering's own type makes "I forgot to order these"
   * a compile error instead of a forecast that is confidently wrong about which build is next.
   *
   * @param running the {@code RUNNING} runs, each with its {@link CiRun#startedAt} and {@link
   *     CiRun#expectedStepDurations}; null or empty is a perfectly ordinary idle estate and the
   *     queue then starts at zero
   * @param queuedInClaimOrder the queued runs <b>already in claim order</b>, as {@link
   *     CiRunOrdering#explain(List)} answers them; null or empty answers an empty list
   * @param concurrentBuilds how many runs this deployment executes at once, clamped up to 1
   * @param now the instant every millisecond in the answer is relative to; never read from a clock
   *     in here
   * @return one entry per input run on each side
   */
  public static Forecast forecast(
      List<CiRun> running,
      List<CiRunOrdering.OrderedRun> queuedInClaimOrder,
      int concurrentBuilds,
      Instant now) {
    List<CiRun> live = running == null ? List.of() : running;
    List<CiRunOrdering.OrderedRun> queue =
        queuedInClaimOrder == null ? List.of() : queuedInClaimOrder;
    int slotCount = Math.max(1, concurrentBuilds);

    // The running half, and the one fact the queued half needs from it: whether any slot's free-at
    // is unknowable. One unpredicted run in flight is enough — it holds a slot for an unknown time,
    // and there is no honest way to say when the queue behind it moves.
    List<RunningForecast> runningOut = new ArrayList<>(live.size());
    List<Long> remainings = new ArrayList<>(live.size());
    boolean aRunningRunHasNoPrediction = false;
    for (CiRun run : live) {
      Long total = expectedTotal(run);
      if (total == null) {
        aRunningRunHasNoPrediction = true;
        runningOut.add(
            new RunningForecast(run.id, Eta.unknown(Unknown.RUNNING_RUN_HAS_NO_PREDICTION)));
        continue;
      }
      long elapsed = run.startedAt == null ? 0L : now.toEpochMilli() - run.startedAt.toEpochMilli();
      long remaining = Math.max(0L, total - elapsed);
      remainings.add(remaining);
      runningOut.add(new RunningForecast(run.id, Eta.known(remaining)));
    }

    long[] freeAt = new long[slotCount];
    // Fewest-remaining first, so the queue's next run is forecast against the slot that really frees
    // first; with more runs than slots the surplus folds onto whichever slot frees soonest, which is
    // the same walk a queued run takes and is argued in the class javadoc.
    Collections.sort(remainings);
    for (long remaining : remainings) {
      freeAt[earliestSlot(freeAt)] += remaining;
    }

    List<QueuedForecast> queuedOut = new ArrayList<>(queue.size());
    // Null until something ahead breaks the chain; from then on it is this run's start's reason and
    // the slots are no longer advanced, because there is nothing left to advance them by.
    Unknown poison = aRunningRunHasNoPrediction ? Unknown.RUNNING_RUN_HAS_NO_PREDICTION : null;
    for (CiRunOrdering.OrderedRun ordered : queue) {
      CiRun run = ordered.run();
      Long total = expectedTotal(run);
      Eta start;
      Eta finish;
      if (poison != null) {
        start = Eta.unknown(poison);
        // Own-unknown wins the finish's reason: it is the more specific fact, and it is the one the
        // run's owner can do something about.
        finish = Eta.unknown(total == null ? Unknown.RUN_HAS_NO_PREDICTION : poison);
      } else {
        int slot = earliestSlot(freeAt);
        start = Eta.known(freeAt[slot]);
        if (total == null) {
          finish = Eta.unknown(Unknown.RUN_HAS_NO_PREDICTION);
          poison = Unknown.RUN_AHEAD_HAS_NO_PREDICTION;
        } else {
          long finishes = freeAt[slot] + total;
          finish = Eta.known(finishes);
          freeAt[slot] = finishes;
        }
      }
      queuedOut.add(new QueuedForecast(run.id, ordered.position(), start, finish));
    }

    return new Forecast(List.copyOf(queuedOut), List.copyOf(runningOut));
  }

  /**
   * What a run is expected to take in total, or null when it predicts nothing.
   *
   * <p>All or nothing, and the sum is of every declared step — see the class javadoc for why a
   * partial array is not a shape that exists.
   */
  private static Long expectedTotal(CiRun run) {
    List<Long> steps = ExpectedStepDurations.decode(run.expectedStepDurations);
    if (steps == null) {
      return null;
    }
    long total = 0L;
    for (Long step : steps) {
      total += step;
    }
    return total;
  }

  /**
   * The slot that frees soonest, ties going to the lowest index.
   *
   * <p>The tie-break is not decoration: every slot is zero on an idle estate, so without it the
   * answer would depend on the iteration order and two forecasts of one queue could disagree.
   */
  private static int earliestSlot(long[] freeAt) {
    int earliest = 0;
    for (int slot = 1; slot < freeAt.length; slot++) {
      if (freeAt[slot] < freeAt[earliest]) {
        earliest = slot;
      }
    }
    return earliest;
  }
}
