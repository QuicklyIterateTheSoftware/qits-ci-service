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
   * <p>{@code gating} is the run row's own flag: whether a red outcome of this pipeline should
   * stand in the way of releasing the commit. Carried on both announcements so the release
   * quality gate reads it as data; green-and-non-gating still announces, because a verdict is a
   * verdict whichever way a reader weighs it.
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
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
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
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
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
      boolean gating,
      String status,
      String previousStatus,
      Instant occurredAt,
      String triggerEventId);
}
