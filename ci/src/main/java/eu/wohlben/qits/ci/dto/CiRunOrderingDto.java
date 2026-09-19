package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.control.CiRunOrdering;
import java.util.List;

/**
 * <b>Why a queued run sits where it sits.</b> {@link CiRunOrdering#explain(List)}'s answer for one
 * run, carried to the wire so that a person reading a queue can see the ordering's reasoning rather
 * than infer it from a list.
 *
 * <p><b>Inferring it is not possible, and that is the whole reason this exists.</b> Two runs appear
 * in a given order because of a kind tier, a dependency edge, a priority word or a timestamp — four
 * completely different answers to "why is my build not starting" — and the ordered list itself
 * distinguishes none of them. A client that only had {@code priority} beside a position would
 * confidently show the wrong one: priority is the criterion that is <em>least</em> often decisive,
 * because it only chooses among runs that topology has already released.
 *
 * <p><b>Present only on a {@code QUEUED} run, and only where the answer was computed for the
 * response.</b> A run that is {@code RUNNING} is past being ordered, and a finished one was ordered
 * in a queue that no longer exists — see {@link CiRunDto} for where the boundary populates this and
 * where it deliberately does not.
 *
 * <p>It is a <b>snapshot of one read</b>. Every component was true of the queue as it stood at the
 * instant the response was generated, and every one of them can change when the next run is accepted
 * — so it is a diagnosis, never a commitment.
 *
 * @param position the run's 0-based index in the suggested claim order, counted across both kind
 *     tiers. It is the same number {@link CiRunDto#queuePosition()} carries; it is repeated here
 *     because an ordering that could not state its own position would be an explanation of a place
 *     it does not name
 * @param kindTier 0 for a release being built — a run an {@code SCMRelease} triggered, which goes
 *     ahead of everything else — and 1 for every other run. Two tiers and not three, for the reason
 *     {@link CiRunOrdering}'s javadoc gives: "a release before a release request" is the requirement
 *     and nothing has asked for more
 * @param priority the priority word <b>exactly as the row records it</b> — null, blank or a word
 *     this service has never heard of, never normalised. It duplicates {@link CiRunDto#priority()}
 *     on purpose: this record is meant to be readable on its own as the ordering's own account, and
 *     the pairing with {@link #priorityRank()} is what makes an unexpected rank legible
 * @param priorityRank what that word resolved to, 0 (most urgent) through 5. An unknown, absent or
 *     miscased word ranks in the <em>middle</em> rather than last, so a rank of 3 beside a priority
 *     of {@code "high"} is this service saying it did not recognise the word — which is precisely
 *     the sentence a normalised priority field would have hidden
 * @param topologyBlockers the queued runs that really held this one back: those emitted ahead of it
 *     <em>because of</em> a declared downstream edge, in emission order. <b>Empty, never null</b>,
 *     when topology said nothing — which is the ordinary case, and is why a client must not read an
 *     empty list as "no reason" but as "the reason is one of the other three"
 * @param selection how the run was chosen: {@link CiRunOrdering.Selection#UNBLOCKED} in the ordinary
 *     case, and {@link CiRunOrdering.Selection#CYCLE_DEGRADED} for the one run a cycle in the
 *     estate's declared dependencies forced the pass to give up on. The second is an honest fact
 *     about the estate rather than an error — the ordering may not throw on a run worker over a bad
 *     pin — and it is on the wire because the only symptom it has ever had is a queue that quietly
 *     stopped honouring an ordering somebody declared
 */
public record CiRunOrderingDto(
    int position,
    int kindTier,
    String priority,
    int priorityRank,
    List<Blocker> topologyBlockers,
    CiRunOrdering.Selection selection) {

  /**
   * One queued run that held another back, named both ways a run is addressed here.
   *
   * <p>A pair rather than a bare id, because the id alone answers the wrong question. The edge
   * exists because a <em>repository name</em> appeared in another run's downstream closure, so the
   * name is the reason a reader recognises and the id is the handle a client links by. {@code
   * repoName} is nullable for the same reason {@code CiRun.repoName} is — the id-addressed
   * compatibility arm — but a run with no name is nobody's blocker, so in practice it carries one.
   */
  public record Blocker(String runId, String repoName) {}

  /**
   * The ordering's own answer for one run, as the wire shape.
   *
   * <p>A straight carry: nothing is recomputed, rounded or re-derived here, because a second opinion
   * about an order that has already been decided is exactly the drift {@link
   * CiRunOrdering#explain(List)} exists to prevent. The only work is turning the control type's
   * {@code Blocker} into this package's, which is the module's standing rule that a control record
   * does not become a wire record by being convenient.
   */
  public static CiRunOrderingDto of(CiRunOrdering.OrderedRun ordered) {
    return new CiRunOrderingDto(
        ordered.position(),
        ordered.kindTier(),
        ordered.priority(),
        ordered.priorityRank(),
        ordered.topologyBlockers().stream()
            .map(blocker -> new Blocker(blocker.runId(), blocker.repoName()))
            .toList(),
        ordered.selection());
  }
}
