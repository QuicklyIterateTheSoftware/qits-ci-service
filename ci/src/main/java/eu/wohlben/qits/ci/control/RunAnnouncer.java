package eu.wohlben.qits.ci.control;

import java.time.Instant;

/**
 * The port {@link CiRunService} announces a green run to the <b>platform at large</b> through — the
 * seam the event bus hangs off, and <b>the only announcement a green run makes</b>.
 *
 * <p>It used to be one of two, and the sibling is worth knowing about because the shape it left is
 * the shape of this one. {@code PdNotifier} was a <em>request</em> addressed to one named service:
 * qits-platform-deployments was asked to deploy, over HTTP, at a URL this repo configured, on every
 * green run. This is a <em>statement</em> addressed to nobody in particular — "a build passed" —
 * which qits-events records and anything on the platform may subscribe to, this service included.
 * Who acts on it has moved on twice: the deployer consumed it durably for a while, and now does not
 * — a green build stopped being a reason to put anything live, so the deployer subscribes to {@code
 * SoftwareRelease} and its {@code /events/build-succeeded} door is gone along with the POST that
 * addressed it. What reads this statement is qits-projects' release-request gate, which records the
 * verdict against the commit. Neither consumer is qits-ci's concern, which is the point of a
 * statement: this port did not change when they did.
 *
 * <p>The signature carries {@code finishedAt} because an event carries <b>when it happened</b> —
 * that is what an event log is for — and the value is the run's own terminal timestamp rather than
 * the moment the announcement was made. The two differ by however long the transition took, and it
 * is never null: the wire contract makes {@code occurredAt} mandatory.
 *
 * <p>An interface rather than a call so this module stays free of the bus and its transport: the
 * sole production implementation is {@code service/…/bus/BuildAnnouncer}. It is resolved
 * via {@code Instance} and absent is a supported configuration — a deployment with no qits-events
 * runs CI exactly as before, and announces nothing at all.
 *
 * <p><b>An implementation must not block the caller</b>, with one teeth-gritting caveat: this runs
 * on a run worker, between one run and the next.
 * The bus implementation's {@code publish()} is synchronous and never throws, but it is not free —
 * it is bounded by the publish timeout when qits-events is unreachable, after which the outbox owns
 * the event. A few seconds per green build, paid only while the far side is down, is the price that
 * was accepted for it; anything slower than that does not belong behind this port.
 */
public interface RunAnnouncer {

  /**
   * A run went green. {@code triggerEventId} is <b>the event that caused this run</b>, or null when
   * nothing did — which after the push retirement is only a historical push row, and one publishing
   * a chain root is correct.
   *
   * <p><b>{@code phase} is which phase of a release this run was</b> — {@code "RELEASE_REQUEST"} for
   * the QA run a {@code ReleaseRequestChanged} caused, {@code "RELEASE"} for the publish run an
   * {@code SCMRelease} caused — and <b>null for every run that is no part of a release</b>, which is
   * the ordinary one. A {@code CiRunPhase} word carried as a plain {@code String}, exactly as {@code
   * status} and {@code outcome} are and for their reason: a wire vocabulary that imported this
   * service's storage model would make another context's subscriber depend on it.
   *
   * <p><b>{@code releaseRequestId} is which release that was</b> — {@link
   * eu.wohlben.qits.ci.entity.CiRun#releaseRequestId}, the id qits-projects addresses a release
   * request by — and it rides beside the phase as a plain {@code String} for the identical reason:
   * this module names foreign things by their id and nothing else. It is here so the far side can
   * correlate a run to a release by reading a field rather than by parsing {@code release/<id>} out
   * of {@code branch} — a second spelling of one fact, on the half of the release qits-projects
   * deletes at tag time, and one a publish run does not carry at all since its branch is the version.
   *
   * <p><b>The two are null together or set together, and a caller must keep them that way.</b> The
   * engine reads the id off the triggering event and derives the phase from it, returning null
   * whenever the id is null, so no row can hold one without the other — and the published events say
   * so, which is what lets a consumer key a pipeline read model on the pair without a second lookup.
   *
   * <p><b>{@code retryOfRunId} is the run this one re-fires</b> — {@link
   * eu.wohlben.qits.ci.entity.CiRun#retryOfRunId}, off the row, and <b>null for every run that is
   * not a retry</b>, which is nearly all of them. It is the one piece of a retry's lineage that had
   * nowhere else to travel: {@code triggerEventId} above is the synthetic local token a re-fire
   * mints so the dedupe constraint can stay as it is, and the causation the bus stamps is the
   * original run's inherited cause, so neither of them says which <em>run</em> was re-asked.
   *
   * <p>It rides so a consumer can <b>supersede the verdict the earlier run left</b> rather than
   * stack a second one beside it. A retry is a second answer to one question, and a reader keeping
   * one verdict per run — qits-projects' release gate, which reads any-red-wins over a fold — would
   * otherwise hold the re-fire's green next to the original's red forever. Which is also the whole
   * of qits-ci's part in it: the lineage is a fact only this service has, so this service states it,
   * and what the far side does with it is the far side's business.
   *
   * <p><b>What none of them does is change what this announcement is.</b> A green run is still a
   * statement about a <em>commit</em>; the phase is an attribute of the run that made it and the
   * request id says which release the run belonged to, never a verdict about the release as a whole
   * — P1 going green says the fold passed QA, not that the release succeeded. A subscriber reading
   * this as a pipeline verdict is reading something the event does not say.
   *
   * <p>{@code repoId} is the storage id and is always set; {@code projectId} and {@code repoName}
   * are the public {@code (project, name)} pair off the run's own row, present when the candidate
   * the run was accepted for carried a public coordinate and null when it did not. They ride the event so a subscriber can
   * address the repository by name instead of falling back to the id.
   *
   * <p><b>A plain {@code String}, and that is the whole reason this parameter is here rather than an
   * ambient value.</b> It is a foreign id, which is exactly how this module names foreign things, and
   * it keeps {@code ci/} free of every eventstream type — the dependency this seam exists to
   * prevent. The bus stamps causation from a thread-local that the implementation could have read
   * instead; it would read null, because the engine consumed the frame on the socket's dispatch
   * thread and this call happens later on {@code ci-run-worker}. A thread-local does not follow work,
   * deliberately. So the id travels durably on {@code CiRun.triggerEventId} and arrives here as an
   * argument, and {@code BuildAnnouncer} hands it to {@code publish(event, parent)} — where
   * an explicit non-null argument outranks the ambient context by design, precisely for this case.
   */
  void onRunSucceeded(
      String runId,
      String retryOfRunId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String phase,
      String releaseRequestId,
      Instant finishedAt,
      String triggerEventId);

  /**
   * A run went red: {@code outcome} is the terminal status's own word — {@code FAILED}, {@code
   * TIMED_OUT} or {@code CONFIG_ERROR} — carried as a plain {@code String} for the reason every
   * parameter here is one. What never reaches this method is as much of the contract as what does:
   * a {@code CANCELLED} run announces nothing (a person withdrew the question), and a run
   * superseded by a newer one announces nothing (its row is bookkeeping about the queue, not a
   * fact about the commit). Everything else — the field meanings, the causation argument, the
   * must-not-block caveat — is {@link #onRunSucceeded}'s, unchanged.
   */
  void onRunFailed(
      String runId,
      String retryOfRunId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String phase,
      String releaseRequestId,
      String outcome,
      Instant finishedAt,
      String triggerEventId);

  /**
   * A run's row changed status. <b>Every</b> transition reaches this method — {@code QUEUED} when
   * the accept commits, {@code RUNNING} when a worker claims the row, and whichever terminal state
   * it settles in, cancellations and supersedes included.
   *
   * <p><b>That "every" is the whole contract, and it is deliberately the opposite of the two methods
   * above.</b> Those are statements about a commit, so they are selective on purpose: a cancelled
   * run and a superseded one announce nothing, because a person withdrawing a question is not an
   * answer to it. This is a statement about the run row, and the reader it exists for is one
   * mirroring {@code GET /ci/api/runs/active} — a listing whose whole content is that column. A run
   * that leaves the listing by being cancelled leaves it exactly as completely as one that leaves it
   * green, so a selective announcement here would strand a mirror holding a run forever. Both edges
   * are owed: what enters the listing, and what leaves it, for every reason there is.
   *
   * <p>{@code status} is the {@code CiRunStatus} the row now holds and {@code previousStatus} the
   * one it left, both as that enum's own word — plain {@code String}s for {@link #onRunFailed}'s
   * reason, so no other context's subscriber depends on this service's storage model. {@code
   * previousStatus} is <b>null on a run's first announcement</b>, which is what says "this run
   * entered the listing" rather than moved within it.
   *
   * <p>{@code occurredAt} is the row's own timestamp for the state just written — {@code createdAt},
   * {@code startedAt} or {@code finishedAt}, whichever the new status is stamped by — never a fresh
   * clock read at announce time. Same reasoning as {@code finishedAt} above, applied to three
   * columns instead of one, and the same wire requirement behind it: a published event with no
   * {@code occurredAt} is a 400.
   *
   * <p>Everything else is {@link #onRunSucceeded}'s, unchanged: the field meanings, {@code
   * triggerEventId} as the causation argument that crosses a thread, the must-not-block caveat, and
   * zero implementations being a supported configuration. One difference in degree is worth naming —
   * this fires several times per run rather than once, so an implementation's cost is paid at every
   * transition and the bound on it matters correspondingly more.
   */
  void onRunStatusChanged(
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
      Instant occurredAt,
      String triggerEventId);
}
