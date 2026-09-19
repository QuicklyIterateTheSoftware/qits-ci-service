package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.ci.entity.CiRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>The suggested claim order, as a function.</b> {@link CiRunOrdering} is pure — no I/O, no clock,
 * no CDI — so every claim about it is made here, against rows built in memory, with no worker, no
 * database and no Quarkus. What the queue does with the answer is {@code CiRunClaimOrderTest}'s.
 *
 * <p>Each case is one precedence rule, and the interesting ones are the pairs: kind over priority,
 * topology over priority, and the two ways data can be absent. The last case is the property the
 * whole design rests on — the answer must not depend on the order the database handed the rows back.
 *
 * <p>A run is <b>named by its own id</b> throughout, and the id is spelled as the repository it is
 * about, so an assertion reads as the list of repositories the queue would build. The one case where
 * a run deliberately has no public name is why the assertions are on ids rather than on {@code
 * repoName}.
 */
public class CiRunOrderingTest {

  private static final Instant EPOCH = Instant.parse("2026-09-07T10:00:00Z");

  /** A queued release-request run for one repository, accepted {@code secondsIn} after the epoch. */
  private static CiRun run(String repoName, int secondsIn) {
    CiRun run = new CiRun();
    run.id = repoName;
    run.repoId = "id-" + repoName;
    run.repoName = repoName;
    run.branch = "main";
    run.commitSha = "0".repeat(40);
    run.createdAt = EPOCH.plusSeconds(secondsIn);
    run.triggerEventName = CiRunService.RELEASE_REQUEST_EVENT_NAME;
    return run;
  }

  private static CiRun withPriority(CiRun run, String priority) {
    run.priority = priority;
    return run;
  }

  private static CiRun withDownstream(CiRun run, String... repositories) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < repositories.length; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(repositories[i]).append('"');
    }
    run.downstreamRepos = json.append(']').toString();
    return run;
  }

  private static List<String> order(CiRun... queued) {
    return CiRunOrdering.suggestedOrder(List.of(queued)).stream().map(run -> run.id).toList();
  }

  // --- the base case: no signals at all -----------------------------------------------------------

  @Test
  public void withNoPrioritiesNoClosuresAndOneKindTheOrderIsTheOneTheQueueAlreadyHad() {
    // The whole degradation story in one assertion. A platform where qits-projects has not shipped
    // the enrichment, nothing declares a priority and no release is queued gets exactly the FIFO
    // this service ordered by before any of this existed — (createdAt, id) — so the feature is
    // invisible until something states something.
    assertEquals(
        List.of("first", "second", "third"),
        order(run("third", 30), run("first", 10), run("second", 20)));
  }

  @Test
  public void aRunWithNoStatedPriorityRanksWithMediumAndNotBehindEverything() {
    // Absent means UNKNOWN, and unknown is the middle of the vocabulary. Reading it as LOWEST would
    // make the rollout order of qits-projects decide the build order of every repository whose
    // events predate the field — which is the one thing an additive field must never do.
    assertEquals(
        List.of("blocking", "silent", "low"),
        order(
            withPriority(run("low", 10), "LOW"),
            run("silent", 20),
            withPriority(run("blocking", 30), "BLOCKING")));
  }

  // --- kind ---------------------------------------------------------------------------------------

  @Test
  public void aReleaseRunGoesFirstEvenWhenAReleaseRequestOutranksItOnEveryOtherCriterion() {
    // Kind is the outermost criterion, so it cannot be outvoted: the release request below is older
    // AND more urgent, and it still waits. A release is work whose artifacts everything else is
    // about to be renovated onto.
    CiRun release = withPriority(run("release", 90), "LOWEST");
    release.triggerEventName = ReleaseJoin.RELEASE_EVENT_NAME;

    assertEquals(
        List.of("release", "request"), order(withPriority(run("request", 10), "BLOCKING"), release));
  }

  // --- topology -----------------------------------------------------------------------------------

  @Test
  public void aRunWhoseRepositoryIsNamedDownstreamOfAQueuedRunWaitsForIt() {
    // The lib → frontend → service chain, accepted backwards. Nothing about acceptance order
    // survives: each edge holds its downstream back until the run that names it has been emitted.
    CiRun lib = withDownstream(run("qits-ui-components-jslib", 30), "qits-ci-frontend");
    CiRun frontend = withDownstream(run("qits-ci-frontend", 20), "qits-ci-service");
    CiRun service = run("qits-ci-service", 10);

    assertEquals(
        List.of("qits-ui-components-jslib", "qits-ci-frontend", "qits-ci-service"),
        order(service, frontend, lib));
  }

  @Test
  public void anUpstreamRunGoesFirstEvenWhenItsDownstreamIsTheMoreUrgentOne() {
    // Priority only chooses among runs nothing sequences, and this is what that means in practice.
    // The downstream is BLOCKING and the upstream is LOWEST, and the upstream still goes first —
    // because running the urgent one now would run it against a dependency it is about to be
    // renovated onto, which is a verdict about a state that is over.
    CiRun upstream = withDownstream(withPriority(run("lib", 20), "LOWEST"), "app");
    CiRun downstream = withPriority(run("app", 10), "BLOCKING");

    assertEquals(List.of("lib", "app"), order(downstream, upstream));
  }

  @Test
  public void aRunWithNoPublicNameIsNobodysDownstreamAndConstrainsNobody() {
    // The id-addressed compatibility arm. A closure names PUBLIC repository names, and a run whose
    // candidate carried none has nothing for an edge to match — so it is ordered by everything else
    // rather than being held back by a list it cannot appear in. Here the namer's list says
    // "unnamed", which matches nothing, and the LOW run therefore sorts behind the namer's unstated
    // MEDIUM: the opposite of what an edge would have produced.
    CiRun unnamed = withPriority(run("unnamed", 10), "LOW");
    unnamed.repoName = null;
    CiRun namer = withDownstream(run("namer", 20), "unnamed");

    assertEquals(List.of("namer", "unnamed"), order(unnamed, namer));
  }

  @Test
  public void aCycleDegradesToPriorityAndTimestampRatherThanThrowing() {
    // Two repositories each naming the other downstream is a fact about the estate — a mistake in a
    // pin, or a genuine mutual dependency — and it reaches this function on a run worker, where a
    // throw would cost every queued run its claim. So the pass emits the best-ranked remaining run
    // and carries on: still total, still deterministic, no longer topological.
    CiRun a = withDownstream(withPriority(run("a", 10), "LOW"), "b");
    CiRun b = withDownstream(withPriority(run("b", 20), "BLOCKING"), "a");
    CiRun free = run("free", 30);

    // `free` is unblocked and goes first on its unstated MEDIUM; the cycle then degrades to
    // priority, so BLOCKING precedes LOW inside it.
    assertEquals(List.of("free", "b", "a"), order(a, b, free));
  }

  // --- the priority table -------------------------------------------------------------------------

  @Test
  public void theSixKnownWordsRankFromBlockingDownToLowest() {
    // Accepted in exactly the reverse of the order they come out in, so nothing here could be the
    // timestamp tie-break agreeing with the table by accident.
    assertEquals(
        List.of("BLOCKING", "HIGHER", "HIGH", "MEDIUM", "LOW", "LOWEST"),
        order(
            withPriority(run("LOWEST", 10), "LOWEST"),
            withPriority(run("LOW", 20), "LOW"),
            withPriority(run("MEDIUM", 30), "MEDIUM"),
            withPriority(run("HIGH", 40), "HIGH"),
            withPriority(run("HIGHER", 50), "HIGHER"),
            withPriority(run("BLOCKING", 60), "BLOCKING")));
  }

  @Test
  public void aWordThisTableHasNeverHeardOfIsOrderedAsIfItSaidNothing() {
    // The doctrine tension, made harmless. qits-projects owns the vocabulary and may grow it; a
    // value this copy predates ranks in the middle and the run is claimed like any other. It is
    // never refused, never dropped and never failed — the cost of a stale table is one misordered
    // queue, not one lost build.
    assertEquals(
        List.of("blocking", "invented", "low"),
        order(
            withPriority(run("low", 10), "LOW"),
            withPriority(run("invented", 20), "SOMEDAY_MAYBE"),
            withPriority(run("blocking", 30), "BLOCKING")));
  }

  @Test
  public void theMatchIsExactSoAWordInTheWrongCaseIsAnUnknownWord() {
    // Deliberately not case-insensitive. "high" is not a word this platform issues, so accepting it
    // would be guessing at intent rather than looking a value up — and a guess that happens to be
    // right teaches a publisher that the wrong spelling works. The lower-case run therefore ranks
    // MEDIUM and sorts behind the real HIGH.
    assertEquals(
        List.of("upper", "lower"),
        order(withPriority(run("lower", 10), "high"), withPriority(run("upper", 20), "HIGH")));
  }

  // --- the order explains itself ------------------------------------------------------------------

  @Test
  public void theExplainedPassAndTheClaimOrderAreTheSameAnswerOnAQueueExercisingAllFourCriteria() {
    // The one claim the whole arrangement rests on. `suggestedOrder` is the claim loop's contract
    // and `explain` is what a person reads, and they are the same pass rather than two passes that
    // agree today — so this is asserted over a queue where every criterion says something, because
    // a set with no signals in it would agree whatever the second implementation was.
    CiRun release = run("release", 40);
    release.triggerEventName = ReleaseJoin.RELEASE_EVENT_NAME;
    CiRun lib = withDownstream(withPriority(run("lib", 30), "LOWEST"), "app");
    CiRun app = withPriority(run("app", 20), "BLOCKING");
    CiRun other = run("other", 10);
    List<CiRun> queued = List.of(app, lib, other, release);

    assertEquals(
        CiRunOrdering.suggestedOrder(queued).stream().map(run -> run.id).toList(),
        CiRunOrdering.explain(queued).stream().map(ordered -> ordered.run().id).toList());
  }

  @Test
  public void thePositionsRunAcrossBothTiersRatherThanRestartingInTheSecond() {
    // A position is what an operator compares to the length of the queue, so there is exactly one
    // zeroth run. The release tier is emitted first and the rest tier continues its numbering; two
    // runs at position 0 would answer a question nobody asked.
    CiRun release = run("release", 40);
    release.triggerEventName = ReleaseJoin.RELEASE_EVENT_NAME;

    assertEquals(
        List.of(0, 1, 2),
        CiRunOrdering.explain(List.of(run("second", 20), release, run("third", 30))).stream()
            .map(CiRunOrdering.OrderedRun::position)
            .toList());
  }

  @Test
  public void aHeldBackRunNamesTheQueuedRunThatReallyHeldIt() {
    // The component nothing else can supply. Given the ordered list alone, "the frontend is behind
    // the library" is indistinguishable from a priority or a timestamp saying so — and the three
    // have completely different answers to "why is my build not starting". The blocker is recorded
    // at the moment the edge into this run is decremented, so it names the run that really held it
    // rather than whatever happened to precede it.
    CiRun lib = withDownstream(run("qits-ui-components-jslib", 30), "qits-ci-frontend");
    CiRun frontend = run("qits-ci-frontend", 20);

    CiRunOrdering.OrderedRun held = CiRunOrdering.explain(List.of(frontend, lib)).get(1);

    assertEquals("qits-ci-frontend", held.run().id);
    assertEquals(
        List.of(new CiRunOrdering.Blocker("qits-ui-components-jslib", "qits-ui-components-jslib")),
        held.topologyBlockers());
  }

  @Test
  public void aRunNothingSequencesCarriesAnEmptyBlockerListAndNotANull() {
    // "Topology said nothing" is the ordinary case on a platform where no event carries a closure,
    // so it is the case that must not need a null check at every reader. An empty immutable list is
    // the same shape as a full one and reads the same way.
    CiRunOrdering.OrderedRun first =
        CiRunOrdering.explain(List.of(run("first", 10), run("second", 20))).get(0);

    assertEquals(List.of(), first.topologyBlockers());
    assertEquals(CiRunOrdering.Selection.UNBLOCKED, first.selection());
  }

  @Test
  public void aCycleIsReportedAsDegradedRatherThanLeftLookingLikeAnOrdinaryChoice() {
    // The degradation is a fact about the ESTATE — a mistake in a pin, or a genuine mutual
    // dependency — and until now its only symptom was a queue that had quietly stopped honouring an
    // ordering somebody declared. `free` is genuinely unblocked and says so.
    //
    // The flag marks the RUN THE PASS GAVE UP ON, not everything in the cycle, and that precision is
    // the point: emitting `b` decrements its edge into `a`, so by the time `a` is chosen it really
    // does have an in-degree of zero and really was chosen topologically. Flagging it too would
    // report a degradation that did not happen, and the one run whose place was decided by ignoring
    // an edge is exactly the one an operator has to be told about.
    CiRun a = withDownstream(withPriority(run("a", 10), "LOW"), "b");
    CiRun b = withDownstream(withPriority(run("b", 20), "BLOCKING"), "a");
    CiRun free = run("free", 30);

    assertEquals(
        List.of(
            CiRunOrdering.Selection.UNBLOCKED,
            CiRunOrdering.Selection.CYCLE_DEGRADED,
            CiRunOrdering.Selection.UNBLOCKED),
        CiRunOrdering.explain(List.of(a, b, free)).stream()
            .map(CiRunOrdering.OrderedRun::selection)
            .toList());
  }

  @Test
  public void thePriorityWordIsCarriedVerbatimBesideTheRankItResolvedTo() {
    // Both halves, because either alone hides the interesting case. The rank is what the queue was
    // really ordered by; the word is what the run states — and normalising an unknown word to
    // "MEDIUM" here would hide the typo that is the whole reason somebody is reading this. Absent
    // and unrecognised resolve to the same middle rank and keep their different words.
    List<CiRunOrdering.OrderedRun> explained =
        CiRunOrdering.explain(
            List.of(
                withPriority(run("stated", 10), "BLOCKING"),
                withPriority(run("invented", 20), "SOMEDAY_MAYBE"),
                run("silent", 30)));

    assertEquals(
        List.of("stated", "invented", "silent"),
        explained.stream().map(ordered -> ordered.run().id).toList());
    assertEquals(
        Arrays.asList("BLOCKING", "SOMEDAY_MAYBE", null),
        explained.stream().map(CiRunOrdering.OrderedRun::priority).toList());
    assertEquals(
        List.of(0, 3, 3), explained.stream().map(CiRunOrdering.OrderedRun::priorityRank).toList());
  }

  @Test
  public void theKindTierIsReportedPerRunSoTheOutermostCriterionIsReadableToo() {
    // Kind cannot be outvoted and it is also the criterion an operator is least able to infer: a
    // release run and a release-request run look identical in a list of ids.
    CiRun release = run("release", 90);
    release.triggerEventName = ReleaseJoin.RELEASE_EVENT_NAME;

    assertEquals(
        List.of(0, 1),
        CiRunOrdering.explain(List.of(run("request", 10), release)).stream()
            .map(CiRunOrdering.OrderedRun::kindTier)
            .toList());
  }

  @Test
  public void theShortCasesAnswerToo() {
    // `suggestedOrder` short-circuits null and a queue of one, and it is written in terms of this
    // method — so this method has to have an answer for both or the two would be one implementation
    // in name only. A queue of one has nothing to be held back by.
    assertEquals(List.of(), CiRunOrdering.explain(null));

    List<CiRunOrdering.OrderedRun> single = CiRunOrdering.explain(List.of(run("alone", 10)));
    assertEquals(1, single.size());
    assertEquals(0, single.get(0).position());
    assertEquals(List.of(), single.get(0).topologyBlockers());
    assertEquals(CiRunOrdering.Selection.UNBLOCKED, single.get(0).selection());
  }

  // --- determinism --------------------------------------------------------------------------------

  @Test
  public void theAnswerIsTheSameWhateverOrderTheRowsArriveIn() {
    // The claim loop re-derives this on every pass and a restart re-derives it in another process,
    // so the function must not depend on the order the database handed the rows back. Every
    // permutation of a queue that exercises all four criteria at once is asserted to agree.
    CiRun release = run("release", 40);
    release.triggerEventName = ReleaseJoin.RELEASE_EVENT_NAME;
    CiRun lib = withDownstream(withPriority(run("lib", 30), "LOWEST"), "app");
    CiRun app = withPriority(run("app", 20), "BLOCKING");
    CiRun other = run("other", 10);
    List<String> expected = List.of("release", "other", "lib", "app");

    for (List<CiRun> permutation : permutations(List.of(release, lib, app, other))) {
      assertEquals(
          expected,
          CiRunOrdering.suggestedOrder(permutation).stream().map(run -> run.id).toList(),
          "the suggested order moved with the input order: "
              + permutation.stream().map(run -> run.id).toList());
    }
  }

  private static List<List<CiRun>> permutations(List<CiRun> rows) {
    if (rows.size() <= 1) {
      return List.of(rows);
    }
    List<List<CiRun>> all = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      List<CiRun> rest = new ArrayList<>(rows);
      CiRun head = rest.remove(i);
      for (List<CiRun> tail : permutations(rest)) {
        List<CiRun> one = new ArrayList<>();
        one.add(head);
        one.addAll(tail);
        all.add(one);
      }
    }
    return all;
  }
}
