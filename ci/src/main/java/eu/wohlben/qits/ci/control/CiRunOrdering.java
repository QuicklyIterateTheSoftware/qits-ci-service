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
    List<CiRun> releases = new ArrayList<>();
    List<CiRun> rest = new ArrayList<>();
    for (CiRun run : queued) {
      (kindRank(run) == 0 ? releases : rest).add(run);
    }
    List<CiRun> ordered = new ArrayList<>(queued.size());
    ordered.addAll(sequence(releases));
    ordered.addAll(sequence(rest));
    return List.copyOf(ordered);
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
   */
  private static List<CiRun> sequence(List<CiRun> tier) {
    int size = tier.size();
    if (size < 2) {
      return tier;
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

    Comparator<CiRun> ranking = ranking();
    boolean[] emitted = new boolean[size];
    List<CiRun> ordered = new ArrayList<>(size);
    for (int step = 0; step < size; step++) {
      int chosen = best(tier, emitted, inDegree, ranking, true);
      if (chosen < 0) {
        // Every remaining node is inside a cycle (or downstream of one). Nothing here is allowed to
        // throw on a run worker over a fact about the estate, so the pass degrades to the next
        // criteria — the best-ranked remaining run — and continues. Deterministic, total, and no
        // longer topological, which is the honest answer.
        chosen = best(tier, emitted, inDegree, ranking, false);
      }
      emitted[chosen] = true;
      ordered.add(tier.get(chosen));
      for (int to = 0; to < size; to++) {
        if (edge[chosen][to]) {
          edge[chosen][to] = false;
          inDegree[to]--;
        }
      }
    }
    return ordered;
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
