package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A CI run passed: this repository, at this commit, on this branch, finished green at this time —
 * and, if the pipeline published one, produced this image.
 *
 * <p>Announced by qits-ci when a run reaches {@code SUCCESS}, and the first thing anything on the
 * platform can listen for. It names things the way this platform names things across a boundary: a
 * repository is a String id and a run is a String id, never a reference into another context's
 * tables.
 *
 * <p><b>{@code occurredAt} is {@code finishedAt}</b>, not the moment {@code publish()} was called.
 * The two differ by however long the terminal transition took, and the one that belongs in an event
 * log is when the thing happened.
 *
 * <p><b>{@code eventId} is a component, and that is safe.</b> It is generated when absent and final
 * once set, which gives the stability the idempotent {@code PUT} rests on, and it is kept out of
 * the payload by the library rather than by anything spelled here — {@code CanonicalJson} excludes
 * everything {@link QitsEvent} declares, and this record's accessor is that declaration. So
 * identity travels in the envelope and the payload is the fields below, which is also why reading a
 * payload back yields a fresh id: a received event's identity is the envelope's, and the payload
 * never claimed to carry one.
 *
 * <p>{@code repoId} is the storage id and is always set; {@code projectId} and {@code repoName} are
 * the public {@code (project, name)} pair a subscriber addresses the repository by. They ride
 * together — a push that arrived name-addressed carries both, an id-addressed one carries neither —
 * so a subscriber that has them names the repository and one that does not falls back to the id
 * exactly as before. The pair was added for a deployer that named an image {@code
 * qits/<repoName>:<sha>} off this event; nothing deploys from a green build any longer, and the
 * fields stay because the reader that replaced it — qits-projects' commit ledger, which the release
 * gate reads — has the same addressing problem.
 *
 * <p>{@code imageDigest}, {@code projectId} and {@code repoName} are nullable — a pipeline that runs
 * tests and publishes nothing is an ordinary green build, and an id-addressed push announces no name
 * — and a null field is omitted from the canonical payload rather than written as an explicit null,
 * so an id-addressed push stays byte-identical on the wire.
 *
 * <p><b>{@code phase} says which PHASE of a release this run was</b> — {@code "RELEASE_REQUEST"} for
 * the QA run of a release request, {@code "RELEASE"} for the publish run of a released tag — and it
 * is null for every run that is no part of a release, which is the ordinary one. A null is omitted
 * from the canonical payload, so an ordinary build's bytes are identical to what they were before
 * this component existed.
 *
 * <p><b>{@code releaseRequestId} is which release the run belonged to</b>, the id qits-projects
 * addresses a release request by, and it is the other half of the pair a pipeline read model keys
 * on. It exists so that correlation is a field rather than a derivation: the alternative was parsing
 * {@code release/<id>} out of {@code branch}, which is a second spelling of one fact, is the half of
 * the release qits-projects deletes at tag time, and cannot work at all for the publish run — whose
 * {@code branch} is the version.
 *
 * <p><b>{@code phase} and {@code releaseRequestId} are null together or set together</b>, on every
 * event qits-ci publishes. The engine reads the id off the triggering event first and derives the
 * phase from it, returning null whenever the id is null, so the two cannot disagree — and a consumer
 * may rely on it, which is what lets a pipeline read model key on the pair without a second lookup.
 * Both are null for a run that is no part of a release, both omitted from the canonical payload when
 * null, so such a build's bytes are identical to what they were before either component existed.
 *
 * <p><b>It does not make this event a pipeline verdict, and that line is the contract.</b> This is
 * still a statement about a <em>commit</em>: this repository, at this sha, finished green. A green
 * P1 says the fold passed QA — not that the release succeeded, not that anything was published, not
 * that the next phase may start. Who decides that is the release request in qits-projects, which is
 * the pipeline; the phase is here so a subscriber can tell which of a release's two runs it is
 * being told about, and the request id so it can tell which release that was. Naming the request
 * does not turn a verdict about a commit into a statement about the release request.
 *
 * <p><b>A plain {@code String} and never an enum</b>, the rule {@code status} and {@code outcome}
 * already ride: the word is {@code CiRunPhase}'s, that enum lives in qits-ci's {@code ci/entity},
 * and a wire vocabulary that imported it would make every subscriber depend on this service's
 * storage model.
 *
 * <p><b>{@code retryOfRunId} is the run this run re-fires</b> — the id of the earlier run a {@code
 * qits ci retry} re-asked the question of — and it is <b>null for every run that is not a retry</b>,
 * which is nearly all of them. A run id, carried as a String like {@code runId} itself, because a
 * run is a String id across this boundary and never a reference into this service's tables.
 *
 * <p>It exists so that a consumer can <b>supersede the verdict the earlier run left</b> rather than
 * stack a second one beside it. A retry is a second answer to one question, not a second question:
 * a reader keeping one verdict per run — qits-projects' release gate is the one this was written
 * for, and it reads any-red-wins over a fold — would otherwise hold the retry's green next to the
 * original's red and keep the commit refused forever. The lineage is a fact qits-ci alone has, so
 * qits-ci is what has to say it; what a reader does with it is its own business, exactly as with
 * every other component here.
 *
 * <p>A null is omitted from the canonical payload rather than written as an explicit null, so an
 * ordinary build's bytes are identical to what they were before this component existed.
 *
 * <p><b>There is no {@code gating} component and there is nothing it could say.</b> It was a {@code
 * Boolean} riding a null-means-gating convention — absent for an ordinary run, an explicit {@code
 * false} for one whose trigger file said {@code gating: false} — and it went with the concept
 * (ticket 9441bc6e). Every step of a pipeline gates, so a green run is a green verdict and a red one
 * is a red verdict, full stop. The convention it rode is unchanged for the components that remain,
 * so the payload of a build that never declared the flag is what it always was.
 */
public record BuildSuccessful(
    UUID eventId,
    String runId,
    String retryOfRunId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    String imageDigest,
    String phase,
    String releaseRequestId,
    Instant finishedAt)
    implements QitsEvent {

  public BuildSuccessful {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public BuildSuccessful(
      String runId,
      String retryOfRunId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String imageDigest,
      String phase,
      String releaseRequestId,
      Instant finishedAt) {
    this(
        null, runId, retryOfRunId, repoId, projectId, repoName, branch, commitSha, imageDigest,
        phase, releaseRequestId, finishedAt);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
