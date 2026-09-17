package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A CI run failed: this repository, at this commit, on this branch, finished red at this time —
 * and {@code outcome} says which kind of red.
 *
 * <p>Announced by qits-ci when a run reaches a terminal failure — {@code FAILED}, {@code
 * TIMED_OUT} or {@code CONFIG_ERROR}, and {@code outcome} carries that word verbatim. What it is
 * <b>not</b> announced for is as much of the contract: a {@code CANCELLED} run says nothing (a
 * person withdrew the question), and a run superseded by a newer push says nothing (its row is
 * bookkeeping about the queue, not a fact about the commit). So a subscriber keeping per-commit
 * build status — the reason this event exists — reads every {@code BuildFailed} as a build that
 * genuinely ran, or genuinely could not run, against exactly that commit.
 *
 * <p>The field conventions are {@link BuildSuccessful}'s, stated there at length and only named
 * here: {@code occurredAt} is {@code finishedAt}; {@code eventId} is generated when absent, final
 * once set, and travels in the envelope rather than the payload; {@code repoId} is the storage id
 * and always set, while {@code projectId} and {@code repoName} ride together when the announcing
 * push arrived name-addressed and are omitted from the canonical payload when it did not; {@code
 * gating} is <b>null for a gating run</b> and an explicit {@code false} only for a non-gating one,
 * so absent reads as gating. There is no {@code imageDigest}: a failed run published nothing worth
 * naming.
 *
 * <p><b>{@code phase} and {@code releaseRequestId} are {@link BuildSuccessful}'s too, including the
 * invariant that binds them: they are null together or set together</b> on every event qits-ci
 * publishes — the engine derives the phase from the id it has already read, returning null whenever
 * that is null — so a consumer may key a pipeline read model on the pair without a second lookup.
 * Both are null for a run that is no part of a release and both are omitted from the canonical
 * payload when null, so such a build's bytes are identical to what they were before either component
 * existed.
 *
 * <p><b>Carrying the request id does not make this a verdict about the release request.</b> A red P1
 * says this commit failed QA; what that is worth to the release is the release request's own
 * business in qits-projects. The id says which release the run belonged to, and nothing more.
 */
public record BuildFailed(
    UUID eventId,
    String runId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    Boolean gating,
    String phase,
    String releaseRequestId,
    String outcome,
    Instant finishedAt)
    implements QitsEvent {

  public BuildFailed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public BuildFailed(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      Boolean gating,
      String phase,
      String releaseRequestId,
      String outcome,
      Instant finishedAt) {
    this(
        null, runId, repoId, projectId, repoName, branch, commitSha, gating, phase, releaseRequestId,
        outcome, finishedAt);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
