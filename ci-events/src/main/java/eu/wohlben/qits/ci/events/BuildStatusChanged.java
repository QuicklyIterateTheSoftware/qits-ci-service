package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A CI run's row changed status: it was {@code previousStatus}, it is {@code status} now, and that
 * happened at {@code occurredAt}.
 *
 * <p><b>A statement about the run, where {@link BuildSuccessful} and {@link BuildFailed} are
 * statements about the commit.</b> That line is the whole reason this is a third event rather than a
 * widening of either. A subscriber keeping per-commit build status wants a verdict and only a
 * verdict — a cancelled run and a superseded one must never reach it, which is exactly what those
 * two announce and exactly what they leave out. A subscriber mirroring {@code GET
 * /ci/api/runs/active} wants the opposite: every transition, verdict or not, because a run that
 * leaves the active listing by being cancelled leaves it just as completely as one that leaves it
 * green. The two readers ask different questions of the same run, so they are told different facts
 * and neither event has to compromise for the other.
 *
 * <p><b>One event for every transition, rather than one event type per state.</b> The listing is
 * made of the run row's {@code status} column, so the thing worth announcing is that the column
 * moved and what it moved between — {@code QUEUED} on accept, {@code RUNNING} when a worker claims
 * the row, and one of {@code SUCCESS}, {@code FAILED}, {@code CANCELLED}, {@code CONFIG_ERROR} or
 * {@code TIMED_OUT} when it settles. A per-state vocabulary would make a mirror subscribe to five
 * names to answer one question, and would have to grow a name the day this enum does.
 *
 * <p>{@code status} and {@code previousStatus} are {@code CiRunStatus} words carried as plain
 * strings, the way {@link BuildFailed#outcome} carries one: the enum lives in {@code ci/entity} and
 * a wire vocabulary that imported it would make another context's subscriber depend on this
 * service's storage model. {@code previousStatus} is <b>null on a run's first announcement</b> —
 * there was no state to leave — and a subscriber reads that as "this run entered the listing".
 *
 * <p><b>{@code occurredAt} is the row's own timestamp for the state it just reached</b>: {@code
 * createdAt} for {@code QUEUED}, {@code startedAt} for {@code RUNNING}, {@code finishedAt} for every
 * terminal one. Never the moment {@code publish()} was called — the two differ by however long the
 * transition and the commit after it took, and what belongs in an event log is when the thing
 * happened. That is {@link BuildSuccessful}'s rule; what differs here is only that the field is
 * named for the contract it satisfies rather than for one of the three columns it can come from, so
 * {@link QitsEvent#occurredAt()} is this record's own accessor rather than an override delegating to
 * a second field. The consequence is worth stating because it is visible on the wire: {@code
 * CanonicalJson} excludes everything {@link QitsEvent} declares, so the instant rides the envelope's
 * {@code occurredAt} <b>only</b> and appears nowhere in the payload. A mirror reads it off the
 * envelope, which is where an event's time has always been.
 *
 * <p><b>{@code phase} is the run's phase of a release</b> — {@code "RELEASE_REQUEST"} or {@code
 * "RELEASE"}, null for a run that is no part of one — carried as a plain string for {@link
 * BuildFailed#outcome}'s reason and omitted from the payload when null, so an ordinary run's
 * transitions stay byte-identical on the wire. <b>It does not turn this into a statement about a
 * release.</b> This event remains a statement about the RUN's own row: that a run of phase P1 moved
 * to {@code RUNNING} says nothing about the release request that caused it, and a mirror reads the
 * phase as an attribute of the row it is mirroring rather than as a pipeline's state. The pipeline
 * is the release request in qits-projects, and no event qits-ci publishes is a verdict about it.
 *
 * <p><b>{@code releaseRequestId} is which release that was</b> — the id qits-projects addresses a
 * release request by, carried so that correlation is a field rather than a derivation: parsing
 * {@code release/<id>} out of {@code branch} would be a second spelling of one fact, the branch is
 * the half qits-projects deletes at tag time, and a publish run's {@code branch} is the version
 * rather than a backing branch at all. It does not turn this into a statement about the release
 * request either; it says which release the row being mirrored belonged to.
 *
 * <p><b>{@code phase} and {@code releaseRequestId} are null together or set together</b> on every
 * row the engine writes — the phase is derived from the id and is null whenever that is — so a
 * consumer may key a pipeline read model on the pair without a second lookup. Both are omitted from
 * the canonical payload when null, so an ordinary run's transitions stay byte-identical on the wire.
 *
 * <p>The remaining field conventions are {@link BuildSuccessful}'s, argued there at length and only
 * named here: {@code eventId} is generated when absent, final once set, and travels in the envelope
 * rather than the payload; {@code repoId} is the storage id and is always set, while {@code
 * projectId} and {@code repoName} are the public {@code (project, name)} pair and ride together or
 * not at all; and every null field is omitted from the canonical payload rather than written as an
 * explicit null. There is no {@code gating}, and {@link BuildSuccessful} says why: every step of a
 * pipeline gates, so a run's verdict is its outcome (ticket 9441bc6e).
 */
public record BuildStatusChanged(
    UUID eventId,
    String runId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    String phase,
    String releaseRequestId,
    String status,
    String previousStatus,
    Instant occurredAt)
    implements QitsEvent {

  public BuildStatusChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public BuildStatusChanged(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String phase,
      String releaseRequestId,
      String status,
      String previousStatus,
      Instant occurredAt) {
    this(
        null, runId, repoId, projectId, repoName, branch, commitSha, phase, releaseRequestId,
        status, previousStatus, occurredAt);
  }
}
