package eu.wohlben.qits.ci.control;

import java.util.List;
import java.util.Set;

/**
 * "Which release requests does this repository have open, and at which fold" — qits-projects'
 * per-repository listing, read by {@link CiPactParityInputs} for the pact parity check's
 * counterpart (qits-2604).
 *
 * <pre>
 *   GET {qits.ci.projects-url}/projects/api/repositories/{repoId}/release-requests
 *   200 {"requests":[{"id","qualifiedId","repoId","state","mergedSha", …}, …]}
 * </pre>
 *
 * <p>Asked with no {@code state}, the door answers the open requests plus the last ten released.
 * Which of those count as a fold the counterpart is offering is {@link #OPEN_STATES}, decided in
 * {@link CiPactParityInputs} rather than in the client, so the decision is tested where the check
 * is and the client stays a plain reader of the wire.
 *
 * <p>An interface rather than a call for the reason {@link CiRepositoryListing} is one: {@code ci}
 * stays free of {@code java.net.http} and of another service's wire shape, and the hand-rolled
 * client lives in {@code service/…/projects/}.
 *
 * <p><b>Three answers, and they mean different things.</b> {@link #configured()} false is a
 * deployment with no {@code qits.ci.projects-url} — the check then reads the counterpart at its
 * release alone, exactly as it did before qits-2604, and nothing fails for it. A configured
 * listing answers either the requests ({@link Listing#requests}) or that they could not be read
 * ({@link Listing#unreachable}), and the second is never an empty first: nothing was learned, so
 * the caller keeps the event owed rather than refusing a fold an open request would have let
 * through.
 */
public interface CiReleaseRequestListing {

  /**
   * The states whose fold is evidence of what the counterpart is about to make: every state a
   * request can still move out of towards a release, and {@code RELEASED} — a request that has cut
   * its tag but whose release this qits-ci may not have recorded yet. {@code FINALIZED} is already
   * the newest release; {@code WITHDRAWN}, {@code OBSOLETE} and {@code FAILED} offer nothing.
   */
  Set<String> OPEN_STATES = Set.of("PENDING", "READY", "REJECTED", "CONFLICTED", "RELEASED");

  /**
   * One release request as the listing answered it.
   *
   * @param id its storage id
   * @param qualifiedId its public name ({@code <repository>-rr-<n>}), or null when the answer
   *     carries none
   * @param state its state, as qits-projects spells it
   * @param mergedSha its fold, or null when it has none yet
   */
  record Request(String id, String qualifiedId, String state, String mergedSha) {

    /** What a person calls it: the qualified id, or the storage id without one. */
    public String label() {
      return qualifiedId == null || qualifiedId.isBlank() ? id : qualifiedId;
    }
  }

  /**
   * The answer of a configured listing.
   *
   * @param requests every request the door answered, newest first; empty when there are none
   * @param unreachable why the requests could not be read, or null when they could
   */
  record Listing(List<Request> requests, String unreachable) {

    public Listing {
      requests = requests == null ? List.of() : List.copyOf(requests);
    }

    public static Listing of(List<Request> requests) {
      return new Listing(requests, null);
    }

    public static Listing unreachable(String why) {
      return new Listing(List.of(), why);
    }
  }

  /**
   * Whether {@code qits.ci.projects-url} is set. False means "read the counterpart at its release
   * alone", never "it has no open requests".
   */
  boolean configured();

  /**
   * The repository's release requests. <b>Never throws</b>: a failed read is {@link
   * Listing#unreachable}, which the implementation logs as one WARN.
   *
   * @param repoId the repository's storage id, as its {@code CiScmRelease} row carries it
   */
  Listing releaseRequests(String repoId);
}
