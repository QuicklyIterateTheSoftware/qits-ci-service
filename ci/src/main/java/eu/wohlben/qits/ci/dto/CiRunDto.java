package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiRunPhase;
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
 *
 * <p><b>{@code phase} is which half of a release pipeline the run is</b>, {@code RELEASE_REQUEST}
 * for the QA run at {@code release/<id>@mergedSha} and {@code RELEASE} for the publish run at
 * {@code <version>@commitSha}. The column has existed since {@code V17__run_phase.sql} and the
 * mapper simply never copied it, which left a real gap: a client holding a release request's runs
 * could not tell phase one from phase two except by matching {@code triggerEventName} against two
 * strings it had to know — an inference about another context's vocabulary, made in a client, to
 * recover a fact this service had already decided and stored.
 *
 * <p>It is <b>null</b> on every run that is not part of a release — every ordinary {@code
 * ci-event-*.yml} run, and every row recorded before the column existed. Null therefore means "no
 * phase", not "unknown phase": the decision is the trigger event's name and nothing else, so a run
 * whose event named no release request is definitively outside a release pipeline rather than
 * unclassified. There is no third value for the deploy phase, because qits-ci does not execute it.
 *
 * <h2>The queue fields, and why they are populated on some reads and not others</h2>
 *
 * <p><b>{@code queuePosition} is the run's 0-based index in the suggested claim order</b> — where
 * this service would really get to it, not where it sits in whatever order the listing happens to
 * be sorted in. It is computed by the same pure function the claim loop walks, so a client asking
 * "how far down the queue am I" gets qits-ci's own answer rather than a reconstruction. <b>A client
 * must not compute this for itself</b>: the criteria are kind, then dependency topology, then
 * priority, then queue time, and any second implementation of them is a second implementation that
 * drifts — silently, and in the direction where it is believed.
 *
 * <p><b>{@code expectedStartInMillis} and {@code expectedFinishInMillis} are durations from the
 * instant the response was generated, never clock times.</b> A client renders "in about 48 min". An
 * absolute predicted instant is deliberately not on the wire: it reads as a promise, it is compared
 * to a watch, it is rendered in a timezone this service knows nothing about, and it is wrong by
 * however long the page has been open. A relative duration is the only form that degrades honestly
 * and the only one a client can re-render without asking again. On {@code GET /ci/api/runs/queue}
 * the instant they are relative to is stated as {@code generatedAt}; on the other reads it is the
 * moment of the response, which is what "in about" is measured from anyway.
 *
 * <p>A {@code RUNNING} run carries an {@code expectedFinishInMillis} and <b>no</b> {@code
 * expectedStartInMillis}: it has started, so there is nothing to forecast about its start.
 *
 * <p><b>{@code predictionUnavailable} is the absence saying why, and it is the field most likely to
 * be dropped as redundant.</b> It is not. A prediction is an estimate and must never read as a
 * promise, which means a run with no ETA has to <em>say so</em> rather than merely lack one — and,
 * crucially, a run behind an unpredicted one in the queue has no knowable ETA either, however good
 * its own history is. Silently omitting those rows would read as "finished" to a client; showing
 * them blank would read as "instant". The three values name <b>whose</b> prediction was missing,
 * because those are three different sentences to the person waiting:
 *
 * <ul>
 *   <li>{@code RUN_HAS_NO_PREDICTION} — this run's own pipeline has never been measured. About
 *       their own repository, and it heals the first time the pipeline runs green.
 *   <li>{@code RUN_AHEAD_HAS_NO_PREDICTION} — a run earlier in the claim order has none, so nobody
 *       knows when the slot frees. About somebody else's build, and it heals on its own.
 *   <li>{@code RUNNING_RUN_HAS_NO_PREDICTION} — a run in flight has none, so the queue's whole
 *       timeline is unknown. Also somebody else's, and also self-healing.
 * </ul>
 *
 * <p>It is null exactly when both ETAs that apply to the run are known, so a client may treat it as
 * the one question to ask before rendering a duration.
 *
 * <p><b>{@code ordering} is the claim order explaining itself</b> — see {@link CiRunOrderingDto}.
 * It exists so that a UI can show <em>why</em> a run is where it is rather than leaving a person to
 * infer it from a priority field, which is the one criterion that is least often the decisive one.
 *
 * <p><b>All six are additive and nullable, and where they are null is a contract rather than an
 * omission. The rule is the run's status, not the route.</b> The forecast is attached to every
 * non-terminal row of a response and to no terminal one, whichever read produced it — so a {@code
 * QUEUED} or {@code RUNNING} run carries its position and its ETAs in a repository's own listing
 * exactly as it does on {@code /active}, {@code /queue} or its own single read. An ETA that
 * depended on which page asked for it would be a different number for the same fact.
 *
 * <p>A <b>finished</b> run carries none of them, on every route, and that is the deliberate half: it
 * has left the queue, so there is no position it could hold and nothing left to predict. It is also
 * what keeps the rule cheap — a response holding nothing in flight reads no queue at all, so a page
 * of history pays for a forecast it would have thrown away. A client therefore reads null as "not
 * answered here" and never as "zero".
 *
 * <p>{@code queuePosition} and {@code ordering} are narrower still, and only a {@code QUEUED} run
 * has them: a {@code RUNNING} run is past being ordered, and reporting its place in a queue it has
 * left would be a number about nothing.
 */
public record CiRunDto(
    String id,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
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
    CiLiveStepDto live,
    CiRunPhase phase,
    Integer queuePosition,
    Long expectedStartInMillis,
    Long expectedFinishInMillis,
    String predictionUnavailable,
    CiRunOrderingDto ordering) {

  /**
   * This run with its steps and its live step attached — the single-run shape.
   *
   * <p>A wither rather than a second constructor call at each call site, and that is the point: this
   * record has twenty-odd components and a hand-written positional copy of it is a bug waiting for
   * two adjacent fields of the same type. There is exactly one such copy and it is here.
   */
  public CiRunDto withSteps(List<CiStepDto> steps, CiLiveStepDto live) {
    return new CiRunDto(
        id,
        repoId,
        projectId,
        repoName,
        branch,
        commitSha,
        status,
        createdAt,
        startedAt,
        finishedAt,
        cancellationReason,
        supersededByRunId,
        daemonVersion,
        triggerType,
        triggerEventId,
        triggerEventName,
        releaseRequestId,
        retryOfRunId,
        configPath,
        priority,
        expectedStepDurationsMillis,
        steps,
        live,
        phase,
        queuePosition,
        expectedStartInMillis,
        expectedFinishInMillis,
        predictionUnavailable,
        ordering);
  }

  /**
   * This run with the queue's answers about it attached: where it sits, when it is expected to start
   * and finish relative to the response's own instant, why there is no answer where there is none,
   * and the ordering's account of its place.
   *
   * <p><b>A wither on the DTO rather than a mapper method, deliberately.</b> None of these five is a
   * column: they are facts about the <em>queue as a whole</em> at one instant, so the mapper stays a
   * pure entity→DTO map and the boundary — which is the only layer that knows what "this response"
   * means — attaches them. Doing it the other way round would put a clock and a second database read
   * inside a MapStruct interface.
   */
  public CiRunDto withQueueFacts(
      Integer queuePosition,
      Long expectedStartInMillis,
      Long expectedFinishInMillis,
      String predictionUnavailable,
      CiRunOrderingDto ordering) {
    return new CiRunDto(
        id,
        repoId,
        projectId,
        repoName,
        branch,
        commitSha,
        status,
        createdAt,
        startedAt,
        finishedAt,
        cancellationReason,
        supersededByRunId,
        daemonVersion,
        triggerType,
        triggerEventId,
        triggerEventName,
        releaseRequestId,
        retryOfRunId,
        configPath,
        priority,
        expectedStepDurationsMillis,
        steps,
        live,
        phase,
        queuePosition,
        expectedStartInMillis,
        expectedFinishInMillis,
        predictionUnavailable,
        ordering);
  }
}
