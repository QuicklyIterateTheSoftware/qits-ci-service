package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import java.time.Instant;
import java.util.List;

/**
 * A CI run as returned to clients — the recorded green/red for one (push, branch). {@code steps} is
 * populated only on the single-run endpoint (with output), null in run listings.
 *
 * <p>{@code live} is the step currently executing and what it has printed so far, read from the
 * in-memory relay rather than from the database: non-null only on the single-run endpoint, and only
 * while {@code status} is {@code RUNNING}. Steps are persisted at their end, so mid-run {@code
 * steps} legitimately holds fewer entries than the pipeline declared, and {@code live} is what says
 * which step the gap belongs to instead of leaving it looking like a run with missing steps.
 *
 * <p><b>{@code QUEUED} is a run that has been accepted and not started</b>, and a client must treat
 * it as non-terminal: it has no {@code finishedAt}, no {@code daemonVersion}, no steps and no {@code
 * live} — there is nothing running to be live about — and it will move on its own, so keep polling.
 * The two active statuses are {@code QUEUED} and {@code RUNNING}; terminal statuses include the
 * distinct {@code CANCELLED} outcome.
 *
 * <p>{@code daemonVersion} is the {@code qits-ci-daemon} build every one of this run's containers
 * ran, pinned once at run creation — so the row records forever what produced its results.
 *
 * <p>{@code projectId} and {@code repoName} are the repository's <b>public</b> coordinate — the one
 * address the platform speaks, {@code /git/<projectId>/<repoName>} — and they are additive rather
 * than a replacement: {@code repoId} stays the storage key every existing client already binds and
 * every run row is found by. Both are <b>null</b> when the announcing push was id-addressed and on
 * every run recorded before the identity campaign, so a client labels by {@code repoName} when it is
 * there and falls back to {@code repoId} when it is not.
 *
 * <p>The four <b>provenance</b> fields say what caused the run. {@code triggerType} is {@code
 * POST_RECEIVE} or {@code EVENT}; {@code configPath} is the committed file that declared the
 * pipeline, which on an event-triggered run identifies <em>which</em> {@code
 * .config/qits/ci-event-*.yml} matched; {@code triggerEventId} and {@code triggerEventName} are the
 * event that caused it, null on every push. They are exposed here because the run API is where an
 * operator reads a run's provenance from outside — no client renders them yet, and that is a later,
 * small follow-up rather than a gap.
 *
 * <p>{@code releaseRequestId} is the fifth of them and the one that is not about a commit: the
 * release request whose backing branch this run built, null for every run that serves none. The
 * {@code commitSha} beside it is a fold nobody pushed and is replaced by the next re-fold, so this
 * is the handle that says which piece of work the run belongs to.
 *
 * <p>{@code retryOfRunId} is the sixth, and it is the only one that names another run: the run this
 * one was fired to re-do, null on everything a trigger produced. A client renders it as a link back
 * and reads it as "this row is a re-fire" — the {@code triggerEventId} beside it is then a synthetic
 * token rather than a foreign event id, so nothing should be matched against the event log by it.
 *
 * <p><b>{@code gating} on a FINISHED run is what the verdict was worth</b>, not only what the
 * pipeline declared: a gating pipeline whose failure happened in a step declaring {@code gating:
 * false} reads {@code false} here, which is the same value its build event carried.
 *
 * <p><b>{@code priority} is why the queue reorders</b>, and it is here for exactly that reason. It
 * is the triggering event's own word — the release request's effective priority, folded in
 * qits-projects — recorded verbatim at accept, and it is one of the two inputs the claim loop ranks
 * {@code QUEUED} runs by. An operator looking at {@code /active} and asking why the newest run was
 * claimed before the oldest must be able to read the answer off the rows rather than infer it, so
 * the value is exposed even though this service compares it to nothing outside its ordering.
 *
 * <p>It is <b>null</b> on every run whose event stated none — which is every run not triggered by a
 * {@code ReleaseRequestChanged} or an {@code SCMRelease}, and every row recorded before the ordering
 * campaign — and null means <b>unknown</b> rather than "lowest": such a run is ranked in the middle,
 * exactly where a run saying {@code MEDIUM} is. This service holds no enum for the vocabulary, so
 * the value is a plain string and a word a client has not heard of is a word qits-projects added.
 *
 * <p>The other ordering input, the downstream closure the run waits on, is deliberately <b>not</b>
 * here: it is a list of another context's repository names that explains a run's position only in
 * combination with every other queued run's list, which is a question this DTO cannot answer one row
 * at a time.
 *
 * <p><b>{@code expectedStepDurationsMillis} is how long this run's steps are expected to take</b>,
 * one entry per step of the pipeline it is running, in declaration order, every entry a positive
 * number of milliseconds. It is what lets a client draw a <em>segmented</em> progress bar for a
 * running job: the persisted {@code steps} say what is finished, {@code live} says which step is in
 * flight and (with its {@code startedAt}) how long it has been, and this says how wide each of the
 * remaining segments should be.
 *
 * <p>It is a <b>prediction</b> — the p95 of what the same step of the same pipeline really took over
 * its most recent successful runs — computed once when the run was accepted and never revised, so a
 * step that overruns it is a slow step rather than a stale field. It is <b>null</b> whenever this
 * service has nothing to predict from: a repository's first run, the first run after the pipeline
 * grew a step or a step changed its image, and every run recorded before the feature existed. Null
 * means <em>unknown</em> and a client draws what it drew before this field existed; it never means
 * "instant". When it is present it has exactly one entry per planned step, so a client may index it
 * by {@code stepIndex} — but it describes the pipeline as ACCEPTED, and comparing its length to the
 * number of {@code steps} rows mid-run is exactly the gap {@code live} explains.
 */
public record CiRunDto(
    String id,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    boolean gating,
    CiRunStatus status,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    String cancellationReason,
    String supersededByRunId,
    String daemonVersion,
    CiTriggerType triggerType,
    String triggerEventId,
    String triggerEventName,
    String releaseRequestId,
    String retryOfRunId,
    String configPath,
    String priority,
    List<Long> expectedStepDurationsMillis,
    List<CiStepDto> steps,
    CiLiveStepDto live) {}
