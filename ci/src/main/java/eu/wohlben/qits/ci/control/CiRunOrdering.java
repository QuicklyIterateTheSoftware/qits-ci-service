package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.entity.CiRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The order the run queue should be claimed in.</b> One pure function over the {@code QUEUED}
 * rows: no I/O, no clock, no CDI, no state. The claim loop reads the rows in one transaction, asks
 * this class for a suggested order, and walks it; a restart therefore re-derives the same order from
 * the same rows, which is what replaced the old "the worker is FIFO and a restart must not reorder a
 * backlog" guarantee with a stronger one.
 *
 * <p>Four criteria, in strict precedence:
 *
 * <ol>
 *   <li><b>Kind.</b> A run triggered by {@link ReleaseJoin#RELEASE_EVENT_NAME} is a <em>release</em>
 *       being built and goes ahead of everything else. Two tiers and not three: "release before
 *       release request" is the requirement, and a third tier separating release-request runs from
 *       ordinary event runs is an ordering nothing has asked for.
 *   <li><b>Topology.</b> A run whose repository appears in another queued run's {@link
 *       CiRun#downstreamRepos} runs <em>after</em> it — upstream first, so a library's release
 *       request is answered before the frontend that renovates onto it. Absent data is no
 *       constraint at all.
 *   <li><b>Priority.</b> Among runs nothing sequences, the more urgent one goes first.
 *   <li><b>Queue time.</b> {@code (createdAt, id)} — the final tie-break, and the whole answer when
 *       nothing above it says anything.
 * </ol>
 *
 * <p><b>Priority never outranks topology, and that is by construction rather than by care.</b> The
 * topology is applied as Kahn's algorithm and priority only chooses among the nodes whose in-degree
 * is already zero, so an upstream {@code LOW} necessarily precedes a downstream {@code BLOCKING}:
 * the downstream is not a candidate at all while its upstream is queued. Running the urgent one
 * first would be running it against a dependency it is about to be renovated onto.
 *
 * <h2>Three deliberate limits</h2>
 *
 * <p><b>Only {@code QUEUED} rows are sequenced.</b> There is no cross-state blocking: a downstream
 * run may start while its upstream is {@code RUNNING}. Strict chaining would idle workers behind a
 * long build and would stall outright on a run that never finishes, and the ordering is a
 * <em>suggestion</em> to a claim loop rather than a scheduler with a lock.
 *
 * <p><b>Starvation is accepted.</b> A steady stream of {@code BLOCKING} work can keep a {@code
 * LOWEST} run waiting indefinitely. That is the honest consequence of a priority queue with no
 * ageing, and the escape hatch is meant to be a manual reorder rather than a fudge factor nobody
 * can predict.
 *
 * <p><b>A cycle degrades, it never throws.</b> Two repositories each naming the other downstream is
 * a fact about the estate, not an error this class may raise on a run worker: the best-ranked
 * remaining run is emitted and the pass continues, so the answer is still total, still
 * deterministic, and merely no longer topological.
 *
 * <h2>The order explains itself</h2>
 *
 * <p>{@link #explain(List)} is the same pass, answering with its reasons attached; {@link
 * #suggestedOrder(List)} is that answer with the reasons dropped. <b>There is exactly one
 * implementation of the ordering logic and the claim loop's contract is the thin one</b> — the
 * alternative, a second pass that re-derives "why" beside the one that decides, is a copy that
 * drifts in the direction nobody notices: an explanation that disagrees with the order is worse
 * than no explanation, because it is believed.
 *
 * <p>What an operator gets out of it is the four criteria named per run rather than inferred from a
 * list: which tier the run was in, what its priority word ranked as, <em>which queued runs actually
 * held it back</em>, and whether the pass was still topological when it chose. None of that is
 * derivable from the ordered list itself — two runs may sit in that order because of an edge, a
 * priority or a timestamp, and the three have completely different answers to "why is my build not
 * starting".
 */
public final class CiRunOrdering {

  private CiRunOrdering() {}

  /**
   * The priority vocabulary, ranked. <b>This is a local copy of another context's words, and this
   * repository's standing rule is that it should not have one</b> — {@code ci_run.priority} is a
   * plain {@code varchar}, {@code ci_scm_release.priority} is one too, no check constraint
   * enumerates the values anywhere, and {@code SoftwareRelease} carries whatever arrived.
   *
   * <p>The tension is real and it is resolved here rather than hidden. To <em>order</em> by a word
   * is to interpret it: there is no ordering without a rank, and a rank is a local opinion about
   * another context's vocabulary. What makes it benign is what it costs to be wrong. A word this
   * table has never heard of — a new value added in qits-projects, a typo, {@code "high"} in the
   * wrong case — ranks as {@link #DEFAULT_RANK} and the run is ordered as if it had said nothing.
   * <b>It is never refused, never dropped, never failed.</b> The blast radius of a stale copy is
   * one misordered queue, which the next run corrects, and not one lost build.
   *
   * <p>So no enum enters the entity or the wire, the table stays private to this class, and matching
   * is exact rather than case-insensitive: {@code "high"} is not a word this platform issues, and
   * quietly accepting it would make the rank a guess about intent instead of a lookup.
   */
  private static final Map<String, Integer> PRIORITY_RANK =
      Map.of(
          "BLOCKING", 0,
          "HIGHER", 1,
          "HIGH", 2,
          "MEDIUM", 3,
          "LOW", 4,
          "LOWEST", 5);

  /**
   * What an absent, blank or unrecognised priority ranks as: the middle of the vocabulary, which is
   * the same rank {@code MEDIUM} carries.
   *
   * <p><b>Middle rather than last, and that is the whole decision.</b> Ranking "unknown" as {@code
   * LOWEST} would put every run of a repository whose publisher has not shipped the field behind
   * every run that states one — a rollout order deciding a build order. Ranking it as {@code
   * BLOCKING} would do the same in the other direction. The middle is the only rank that says
   * nothing.
   */
  private static final int DEFAULT_RANK = 3;

  /**
   * The suggested claim order over a set of queued runs.
   *
   * <p>Total, deterministic and permutation-stable: the same rows in any input order produce the
   * same output, because every choice is made with a comparator whose last key is the row id. The
   * input is not modified.
   *
   * @param queued the {@code QUEUED} rows, in any order — the claim loop passes {@code
   *     CiRunRepository.listQueuedOldestFirst}'s answer, whose base order is this function's final
   *     tie-break anyway
   * @return the same rows, reordered
   */
  public static List<CiRun> suggestedOrder(List<CiRun> queued) {
    if (queued == null || queued.size() < 2) {
      return queued == null ? List.of() : List.copyOf(queued);
    }
    return explain(queued).stream().map(OrderedRun::run).toList();
  }

  /**
   * One queued run, in claim order, with the four criteria's answers for <em>this</em> run attached.
   *
   * <p>Every component is a fact the pass already had and threw away. Nothing here is recomputed
   * afterwards and nothing here is a second opinion: an {@code OrderedRun} is emitted by the walk at
   * the moment it emits the run, which is the only instant at which "what was holding it" is still
   * knowable — one step later the edges have been decremented and the evidence is gone.
   *
   * @param run the row itself, untouched
   * @param position its 0-based index in the suggested order, counted across <em>both</em> tiers, so
   *     it is the number an operator can compare to "how far down the queue am I"
   * @param kindTier 0 for a release being built, 1 for everything else — {@link #kindRank(CiRun)}'s
   *     answer, and the outermost criterion
   * @param priority the priority word <b>as recorded on the row</b>: null, blank or a word this
   *     table has never heard of, kept verbatim and never normalised, because the point of showing
   *     it is to show what the run really states — a normalised {@code "MEDIUM"} here would hide the
   *     typo that is the whole reason somebody is reading this
   * @param priorityRank what that word resolved to, which is {@link #DEFAULT_RANK} — the rank {@code
   *     MEDIUM} carries — for all three of those cases. "Unknown ranks middle, not lowest" is argued
   *     on {@link #DEFAULT_RANK} and is not restated here; what this component adds is that the
   *     argument is now <em>visible</em>, so a queue ordered by a rank nobody expected can be read
   *     rather than guessed at
   * @param topologyBlockers the queued runs that really held this run's in-degree above zero — those
   *     emitted before it <em>because of</em> an edge into it — in emission order. <b>Empty, never
   *     null</b>, when topology said nothing about this run, which is the ordinary case on a
   *     platform where no event carries a closure
   * @param selection how the run was chosen out of the pass; see {@link Selection}
   */
  public record OrderedRun(
      CiRun run,
      int position,
      int kindTier,
      String priority,
      int priorityRank,
      List<Blocker> topologyBlockers,
      Selection selection) {}

  /**
   * One queued run that held another back, named the two ways a run is addressed here.
   *
   * <p>A record rather than a bare id, because an id alone answers the wrong question: the edge
   * exists because of a <em>repository name</em> appearing in a closure, so the name is the reason
   * and the id is the handle. {@code repoName} is nullable for the same reason {@link
   * CiRun#repoName} is — the id-addressed compatibility arm — but a run with no name is nobody's
   * blocker, so in practice a {@code Blocker} carries one.
   */
  public record Blocker(String runId, String repoName) {}

  /**
   * How a run was chosen out of the pass: the ordinary way, or the way a cycle forces.
   *
   * <p><b>An enum rather than a boolean, and surfaced rather than swallowed.</b> {@link
   * #CYCLE_DEGRADED} is exactly the {@code best(…, false)} arm — every remaining run in the tier was
   * still held by an edge, so the pass stopped being topological and emitted the best-ranked one
   * instead. The class javadoc argues why that must not throw; this says why it must not be silent
   * either. It is an honest fact about the <em>estate</em>: two repositories naming each other
   * downstream is a mistake in a pin or a genuine mutual dependency, and the only symptom it has
   * ever had is a queue that quietly stopped honouring an ordering somebody declared. A person
   * reading the queue should be able to see that the topology was abandoned, on which runs, without
   * reading this source.
   */
  public enum Selection {
    /** Chosen with an in-degree of zero: every upstream this run declares is already emitted. */
    UNBLOCKED,

    /**
     * Chosen while still held by an edge, because every remaining run in the tier was. The run's
     * place is the next criteria's answer — priority, then queue time — and not topology's.
     *
     * <p><b>It marks the run the pass gave up on, not every run in the cycle.</b> Emitting this one
     * decrements its own edges, so the run it was pointing at is genuinely unblocked by the time it
     * is chosen and is reported as {@link #UNBLOCKED} — which is the truth. Flagging a whole cycle
     * would report degradations that did not happen and would bury the one run whose place really
     * was decided by ignoring an edge.
     */
    CYCLE_DEGRADED
  }

  /**
   * The suggested claim order with its reasons: the same total, deterministic, permutation-stable
   * pass {@link #suggestedOrder(List)} answers with, one {@link OrderedRun} per queued row.
   *
   * <p>Pure in exactly the sense the class javadoc means it — <b>no I/O, no clock, no CDI, no
   * state</b> — and that is load-bearing here for a second reason on top of the restart doctrine: an
   * explanation of a queue must be derivable from the rows alone, or two readers of one queue get
   * two answers and neither is wrong.
   *
   * <p>It answers for the short cases too, which is what lets {@code suggestedOrder} be written in
   * terms of it: a null input is an empty list, and a single row is one {@code OrderedRun} at
   * position 0 with no blockers and {@link Selection#UNBLOCKED} — a queue of one has nothing to be
   * held back by.
   *
   * @param queued the {@code QUEUED} rows, in any order, exactly as {@link #suggestedOrder(List)}
   *     takes them; the input is not modified
   * @return one entry per input row, in claim order
   */
  public static List<OrderedRun> explain(List<CiRun> queued) {
    if (queued == null) {
      return List.of();
    }
    List<CiRun> releases = new ArrayList<>();
    List<CiRun> rest = new ArrayList<>();
    for (CiRun run : queued) {
      (kindRank(run) == 0 ? releases : rest).add(run);
    }
    List<OrderedRun> explained = new ArrayList<>(queued.size());
    explained.addAll(sequence(releases, 0));
    explained.addAll(sequence(rest, explained.size()));
    return List.copyOf(explained);
  }

  /**
   * Which tier a run is in: 0 for a release being built, 1 for everything else.
   *
   * <p>Read off {@link CiRun#triggerEventName}, which is the run's own record of what caused it —
   * not off the trigger file, which a run may no longer be able to parse, and not off a column
   * added for the purpose, which would be a third place the same fact lives.
   */
  private static int kindRank(CiRun run) {
    return ReleaseJoin.RELEASE_EVENT_NAME.equals(run.triggerEventName) ? 0 : 1;
  }

  /**
   * One tier, sequenced: the dependency digraph over these runs, walked by Kahn's algorithm with
   * {@link #ranking()} choosing among the currently unblocked nodes.
   *
   * <p>Edges are computed inside the tier only. A release run is never held back by a release
   * request's downstream list, and it does not have to be: the tier above it has already been
   * emitted in full.
   *
   * <p><b>The blockers are recorded here because here is the only place they exist.</b> When a node
   * is emitted, every edge out of it that is still standing is decremented — and each such
   * decrement is precisely one real contribution to a downstream's in-degree, so the emitted node is
   * recorded as a blocker of that downstream at the same statement. A node's list is therefore
   * complete and frozen at the instant it is itself emitted: everything after that moment held it
   * back no longer, and everything before it that is not in the list was ordered ahead of it by a
   * criterion other than topology.
   *
   * @param firstPosition the global position the first run of this tier takes, so positions run
   *     across both tiers rather than restarting at zero in the second — a run's position is what an
   *     operator compares to the length of the queue, and two zeroth runs would answer a question
   *     nobody asked
   */
  private static List<OrderedRun> sequence(List<CiRun> tier, int firstPosition) {
    int size = tier.size();
    if (size < 2) {
      List<OrderedRun> single = new ArrayList<>(size);
      for (int index = 0; index < size; index++) {
        single.add(describe(tier.get(index), firstPosition + index, List.of(), Selection.UNBLOCKED));
      }
      return single;
    }

    // Parsed ONCE per run per pass — the reason ci_run.downstream_repos holds the array text rather
    // than a normalised table, and the reason nothing below this line touches JSON.
    List<Set<String>> downstreams = new ArrayList<>(size);
    for (CiRun run : tier) {
      downstreams.add(downstreamsOf(run));
    }

    // A → B when B's repository is named downstream of A's: A is the upstream and must go first.
    // A run with no repoName is nobody's downstream (there is no name to match), which is the
    // id-addressed compatibility arm and needs no special case beyond the null check.
    boolean[][] edge = new boolean[size][size];
    int[] inDegree = new int[size];
    for (int from = 0; from < size; from++) {
      Set<String> named = downstreams.get(from);
      if (named.isEmpty()) {
        continue;
      }
      for (int to = 0; to < size; to++) {
        if (from == to) {
          continue;
        }
        String name = tier.get(to).repoName;
        if (name != null && named.contains(name)) {
          edge[from][to] = true;
          inDegree[to]++;
        }
      }
    }

    // One list per node, appended to as the edges into it are really decremented — so it holds the
    // runs that held THIS run, in emission order, and nothing that merely preceded it.
    List<List<Blocker>> heldBy = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      heldBy.add(new ArrayList<>());
    }

    Comparator<CiRun> ranking = ranking();
    boolean[] emitted = new boolean[size];
    List<OrderedRun> ordered = new ArrayList<>(size);
    for (int step = 0; step < size; step++) {
      Selection selection = Selection.UNBLOCKED;
      int chosen = best(tier, emitted, inDegree, ranking, true);
      if (chosen < 0) {
        // Every remaining node is inside a cycle (or downstream of one). Nothing here is allowed to
        // throw on a run worker over a fact about the estate, so the pass degrades to the next
        // criteria — the best-ranked remaining run — and continues. Deterministic, total, and no
        // longer topological, which is the honest answer.
        chosen = best(tier, emitted, inDegree, ranking, false);
        selection = Selection.CYCLE_DEGRADED;
      }
      emitted[chosen] = true;
      CiRun run = tier.get(chosen);
      ordered.add(describe(run, firstPosition + step, List.copyOf(heldBy.get(chosen)), selection));
      for (int to = 0; to < size; to++) {
        if (edge[chosen][to]) {
          edge[chosen][to] = false;
          inDegree[to]--;
          heldBy.get(to).add(new Blocker(run.id, run.repoName));
        }
      }
    }
    return ordered;
  }

  /** One emitted run, with the criteria's answers read off it; see {@link OrderedRun}. */
  private static OrderedRun describe(
      CiRun run, int position, List<Blocker> topologyBlockers, Selection selection) {
    return new OrderedRun(
        run, position, kindRank(run), run.priority, priorityRank(run), topologyBlockers, selection);
  }

  /**
   * The index of the best not-yet-emitted run, or -1 when {@code unblockedOnly} is asked for and
   * every remaining run is still held by an edge.
   */
  private static int best(
      List<CiRun> tier,
      boolean[] emitted,
      int[] inDegree,
      Comparator<CiRun> ranking,
      boolean unblockedOnly) {
    int chosen = -1;
    for (int candidate = 0; candidate < tier.size(); candidate++) {
      if (emitted[candidate] || (unblockedOnly && inDegree[candidate] > 0)) {
        continue;
      }
      if (chosen < 0 || ranking.compare(tier.get(candidate), tier.get(chosen)) < 0) {
        chosen = candidate;
      }
    }
    return chosen;
  }

  /**
   * The total order among runs nothing sequences: priority, then queue time, then row id.
   *
   * <p>The id is what makes it <em>total</em>, and that is not decoration: two runs accepted in the
   * same millisecond must still have one answer, or the suggested order would depend on which row
   * the database happened to hand back first.
   */
  private static Comparator<CiRun> ranking() {
    Comparator<Instant> byInstant = Comparator.nullsLast(Comparator.naturalOrder());
    Comparator<String> byText = Comparator.nullsLast(Comparator.naturalOrder());
    Comparator<CiRun> byPriority = Comparator.comparingInt(CiRunOrdering::priorityRank);
    return byPriority
        .thenComparing(run -> run.createdAt, byInstant)
        .thenComparing(run -> run.id, byText);
  }

  /** Where a run's stated priority sits in {@link #PRIORITY_RANK}; anything else is the middle. */
  private static int priorityRank(CiRun run) {
    String priority = run.priority;
    if (priority == null || priority.isBlank()) {
      return DEFAULT_RANK;
    }
    return PRIORITY_RANK.getOrDefault(priority, DEFAULT_RANK);
  }

  /**
   * The repository names one run declares downstream of itself, or an empty set when it declares
   * none.
   *
   * <p>Every failure is the empty set and none of them is logged: a null column is the ordinary
   * value, and text that will not parse is a payload another context wrote — reading it as "no
   * constraint" leaves the run ordered by everything else, which is exactly what a run whose
   * publisher never carried the field gets. A warning per pass per row would be a line per second
   * for as long as the backlog lasts.
   */
  private static Set<String> downstreamsOf(CiRun run) {
    JsonNode parsed = CiEventSelectionEvaluator.parsePayload(run.downstreamRepos);
    if (parsed == null || !parsed.isArray() || parsed.isEmpty()) {
      return Set.of();
    }
    Set<String> names = new HashSet<>();
    for (JsonNode element : parsed) {
      if (element.isTextual() && !element.textValue().isBlank()) {
        names.add(element.textValue());
      }
    }
    return names;
  }
}
