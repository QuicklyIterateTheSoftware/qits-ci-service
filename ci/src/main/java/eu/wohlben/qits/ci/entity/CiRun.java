package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One CI pipeline execution: one domain event matched one repository's {@code
 * .config/qits/ci-event-*.yml}, and this is the row that pipeline ran under. (Rows recorded before
 * 2026-09-05 may instead be one (push, updated branch ref) whose commit carried {@code
 * .config/qits/ci-post-receive.yml} — that intake retired, its rows did not.) {@link #repoId} is a
 * plain string — ci lives in its own
 * physical DB with NO FK into qits' tables (a deleted repository leaves runs behind as dangling
 * history, the artifacts stance). Steps are {@link CiStep} rows keyed by {@link CiStep#runId}, not
 * a JPA relation.
 *
 * <p><b>A {@link CausedRow}, beside its own richer record.</b> {@link #causationId} is the
 * platform's generic trace column, and it is filled two ways because the two accept paths stand on
 * different threads. A bus-arrived event crosses the trigger queue before the row is written, and
 * an executor hop is exactly where the ambient {@code CausationScope} dies — so {@code
 * CiRunService.acceptEventRun} sets the cause <em>explicitly</em> from the event id, the same way
 * every other provenance column crosses that hop, and the {@code CausationStamp} listener yields to
 * the set value. A manual trigger evaluates on the request thread, where the REST filter's restored
 * scope still stands — there the stamp itself fills the column, from the {@code
 * X-Qits-Causation-Id} the caller sent. {@link #triggerEventId} stays what it is: domain data with
 * a unique constraint on it. For an event run the two agree; a historical post-receive run has
 * neither; a manual trigger over REST records a cause where {@code triggerEventId} records none.
 */
@Entity
@Table(name = "ci_run")
@EntityListeners(CausationStamp.class)
public class CiRun extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /**
   * The public coordinate of the same repository — the project it belongs to and its name within
   * that project, which together are the one address anything above the projects↔githost seam
   * speaks: {@code /git/<projectId>/<repoName>}.
   *
   * <p><b>Both are nullable, and that is the compatibility arm rather than an oversight.</b> The git
   * host fills them on an {@code SCM*} event from the address the push arrived on, so a push on the
   * internal id-addressed route announces without them and every row recorded before this campaign
   * has neither. A run with no pair builds id-addressed URLs and displays its id, which is exactly
   * what this service did before names existed. {@link #repoId} stays the storage-adjacent key: it
   * is what the dedupe constraint is built on and what every historical row is found by, and it is
   * never displayed once a name is there.
   */
  @Column(name = "project_id", length = 255)
  public String projectId;

  @Column(name = "repo_name", length = 255)
  public String repoName;

  @Column(nullable = false)
  public String branch;

  @Column(name = "commit_sha", nullable = false, length = 64)
  public String commitSha;

  /**
   * The release request this run serves, or null for every run that serves none — which is every
   * event run not triggered by a {@code ReleaseRequestChanged}, and every historical push row.
   *
   * <p>A release request is qits-projects' aggregate and this is its id as a plain string, the way
   * this module names every foreign thing. It is recorded because the run is <b>about</b> that
   * request rather than merely about a commit: the run's commit sha is a fold nobody pushed (the tip
   * of {@code release/<id>}, rewritten on every re-fold), so the request id is the only stable
   * handle a cancellation or a retry can address the work by. {@link #commitSha} is the merged sha
   * the event named, so the two together say exactly which fold this verdict is about.
   *
   * <p>Nullable and part of no constraint: the dedupe stays {@code (trigger_event_id, repo_id,
   * config_path)}, and the per-branch collapse a re-fold needs is already
   * {@code CiRunService.supersedeByCheckoutBranch}'s — the backing branch is stable per request, so
   * a burst of re-folds collapses to the newest tip with nothing added here.
   */
  @Column(name = "release_request_id", length = 255)
  public String releaseRequestId;

  /**
   * Which phase of that release this run is — {@link CiRunPhase#RELEASE_REQUEST} for the QA half,
   * {@link CiRunPhase#RELEASE} for the publish half — or null for every run that is no part of a
   * release.
   *
   * <p><b>Decided by the trigger event's NAME, never by {@link #configPath}.</b> A {@code
   * ReleaseRequestChanged} that names a release request writes {@code RELEASE_REQUEST}; an {@code
   * SCMRelease} that names one writes {@code RELEASE} <em>and</em> fills {@link #releaseRequestId},
   * which the QA half alone used to. That is {@code CiRunService.releaseRequestOf}'s rule with one
   * more name in it, and keeping it there is what decoupled this column from the trigger-file
   * migration: a repository writing its own {@code ci-event-*.yml} records both phases exactly as
   * one on a composed {@code release.yml} does.
   *
   * <p><b>Null is the ordinary value and it is permanent.</b> A dependency-bump run is not part of a
   * release; neither is any other run whose event names no request, which includes every {@code
   * SCMRelease} published before qits-projects grew the field. Such a row is written exactly as it
   * was written before this column existed, with no warning and no error — that arm is the live
   * traffic, not the exception.
   *
   * <p>Part of no constraint and read by nothing in the queue: no ordering rule, no priority, no
   * dedupe key and no supersede rule touches it. What reads it is the announcement (it rides {@code
   * BuildSuccessful}/{@code BuildFailed}/{@code BuildStatusChanged} as a plain string) and {@code
   * POST /ci/api/runs/rerun}, which addresses a run by {@code (repoId, releaseRequestId, phase)}
   * because that triple is the only identity qits-projects holds.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "phase", length = 32)
  public CiRunPhase phase;

  /**
   * The run this one re-fires, or null for every run that is not a manual retry — which is every run
   * a trigger produced.
   *
   * <p>A retry asks for the <b>same work</b> again: same repository, same trigger file, same
   * checkout, same {@link #releaseRequestId}, same {@link #commitSha}, so its verdict correlates
   * exactly as the original's would have. What it may not carry is the same {@link #triggerEventId},
   * because that column is one third of the dedupe constraint and a second row under the original
   * event id is the replay the constraint exists to refuse. A retry therefore mints its own
   * <b>synthetic</b> trigger identity, {@code CiRunService.RETRY_TRIGGER_PREFIX + id} — unique by
   * construction, and naming no event qits-events ever minted.
   *
   * <p>This column is the provenance that synthetic id cannot carry, and it is also what tells the
   * two apart at read time: a row with a value here is a re-fire, and its {@code triggerEventId} is
   * a local token rather than a foreign id. {@link #causationId} is copied from the run being
   * retried, so the events the retry publishes still name the domain event that started all of it.
   */
  @Column(name = "retry_of_run_id", length = 255)
  public String retryOfRunId;

  /**
   * Why this run was fired <b>automatically</b>, or null when it was not — every run a trigger
   * produced, and every retry a person asked for. Set only on a retry qits-ci fired itself because
   * the run it re-fires ({@link #retryOfRunId}) failed through the infrastructure rather than the
   * build (qits-440, {@code V25__run_auto_retry.sql}). Non-null is the whole of "automatic", and the
   * cap on automatic retries counts consecutive non-null rows along {@link #retryOfRunId}.
   */
  @Column(name = "retry_reason", length = 255)
  public String retryReason;

  /**
   * The runners that must not be handed this run, as a JSON array of runner ids, or null for none
   * (qits-556, {@code V27__run_avoid_runners.sql}). A runner joins it when a step it held failed by
   * the infrastructure ({@code CiRunnerHealth.isInfra}); every retry copies it whole, so neither an
   * automatic nor a person's re-fire goes back to a runner that already failed the work. Only a
   * runner's reservation reads it — a local worker, and any other runner, may take the run.
   */
  @Column(name = "avoid_runner_ids", columnDefinition = "text")
  public String avoidRunnerIds;

  /**
   * What the triggering event said this work was worth, verbatim, or null when it said nothing —
   * which is every run not triggered by a {@code ReleaseRequestChanged} or an {@code SCMRelease},
   * every run whose event stated no priority, and every row recorded before the ordering campaign.
   *
   * <p><b>An ordering input, and read by {@code CiRunOrdering} alone.</b> Nothing else in this
   * service compares it to anything: there is no enum for the vocabulary (it is qits-projects', and
   * it will grow there), so an unknown word rides onto the row untouched and is ranked as {@code
   * MEDIUM} rather than refused. Absent means <b>unknown</b>, which the ordering also reads as
   * {@code MEDIUM} — never as "lowest", because a publisher that has not been released yet must not
   * cost its runs their place in the queue.
   *
   * <p>It is deliberately not the same value as {@code ci_scm_release.priority}: that one is what the
   * RELEASE said and is resolved at announce time, while this one is what THIS RUN was accepted
   * knowing. A QA run of a release request has no release fact to resolve against and must still be
   * orderable.
   */
  @Column(length = 32)
  public String priority;

  /**
   * The repositories qits-projects named as downstream of this run's repository, as the canonical
   * JSON array text the event carried, or null when the event named none.
   *
   * <p><b>The second ordering input, and read by {@code CiRunOrdering} alone.</b> It is stored
   * verbatim rather than normalised into rows, {@code trigger_event_payload}'s precedent: this module
   * walks payloads rather than binding them, and the ordering parses this text once per pass rather
   * than once per comparison.
   *
   * <p>Null is the ordinary value and means <b>unknown</b> — a run whose event predates the field, a
   * qits-projects that could not ask qits-maintenance, or any event that is not a {@code
   * ReleaseRequestChanged}. Unknown is <em>unconstrained</em>: it never holds a run back and never
   * pushes one forward. An empty array is a different statement — "asked, this repository is a leaf"
   * — and orders identically, which is why nothing here has to tell the two apart.
   */
  @Column(name = "downstream_repos", columnDefinition = "text")
  public String downstreamRepos;

  /**
   * How long each of this run's planned steps is expected to take, as the JSON array of millisecond
   * longs {@link ExpectedStepDurations} writes — one entry per step of the pipeline the trigger file
   * declared, in declaration order — or null when there is nothing to predict from.
   *
   * <p><b>A prediction, computed once at accept and never revised.</b> It is the p95 of what the
   * <em>same step of the same pipeline</em> really took over its most recent successful runs, read
   * off {@link CiStep#startedAt}/{@link CiStep#finishedAt} — the host-stamped record — and a run that
   * then takes twice as long is not a row to correct. What it exists for is the one thing a client
   * cannot draw without it: a segmented progress bar for a run that is still executing, where the
   * persisted steps say what is done and this says how much of the rest each remaining step is.
   *
   * <p><b>Null is the ordinary value and means "no prediction", never "instant".</b> The first run a
   * repository records, the first run after the pipeline grew a step, the first run after a step
   * changed its image, a run whose trigger config would not parse, and every row recorded before the
   * feature existed all carry null — and so does a run whose stored value cannot be read back, since
   * the codec answers a malformed column with no prediction rather than an exception. A client with
   * no prediction draws exactly what it drew before this column existed.
   *
   * <p>Read whole and queried into by nothing, which is why it is one text column rather than a
   * table: {@code downstream_repos}' precedent and its reasoning.
   */
  @Column(name = "expected_step_durations", columnDefinition = "text")
  public String expectedStepDurations;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CiRunStatus status;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** When the worker claimed this queued run and pipeline execution actually began. */
  @Column(name = "started_at")
  public Instant startedAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  /** Why this run was cancelled, or null when it ended for any other reason. */
  @Column(name = "cancellation_reason", length = 255)
  public String cancellationReason;

  /**
   * The newer run that superseded this queued run, or null for every non-deduplication outcome.
   * A plain id rather than a JPA relation: runs are the aggregate and clients use this as a link.
   */
  @Column(name = "superseded_by_run_id", length = 255)
  public String supersededByRunId;

  /**
   * Which {@code qits-ci-daemon} build produced this run's results — resolved once when the run is
   * created and repeated into every one of its step containers, so a deploy landing mid-run cannot
   * make step 3 speak a different protocol than step 1, and the row records forever what ran it.
   *
   * <p>Null on a run that never launched a container — one still {@code QUEUED}, a {@code
   * CONFIG_ERROR}, one cancelled before it started — and on every run recorded before the daemon
   * existed. It is written when the first container is about to launch rather than when the row is
   * inserted, which is what keeps that true now that the row predates the pin.
   */
  @Column(name = "daemon_version", length = 64)
  public String daemonVersion;

  /**
   * Which trigger produced this run. Never null — rows recorded before event triggers existed were
   * backfilled {@link CiTriggerType#POST_RECEIVE}, which is what they were.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 32)
  public CiTriggerType triggerType;

  /**
   * The id of the domain event that caused this run. Null only on a historical {@code POST_RECEIVE}
   * row: a push was not caused by an event, and the intake that recorded one retired on 2026-09-05.
   *
   * <p><b>It is the carrier across a thread hop.</b> The engine consumes a frame on the bus's
   * dispatch thread and <em>enqueues</em> the run, which executes later on {@code ci-run-worker}; a
   * {@code CausationScope} thread-local is long gone by then, deliberately (it does not follow work).
   * This column is what survives that hop <em>and</em> a restart, and it is what {@code
   * RunAnnouncer.onRunSucceeded} passes to {@code publish(event, parent)} — so the events a triggered
   * run publishes name what caused them, and a release train is a chain in the log rather than a set
   * of rows distinguishable from coincidence only by their timestamps.
   *
   * <p>With {@link #repoId} and {@link #configPath} it carries a <b>unique constraint</b>, which is
   * the durable at-most-one-run-per-(event, trigger file) guarantee. A redelivery of the same event —
   * legal, and something a future catch-up feature will do on purpose — hits it and is dropped as
   * already-triggered rather than re-run. Rows with a null here are all distinct to that constraint —
   * plain SQL {@code unique}, where rows collide only when every column is non-null and equal —
   * which is what kept every post-receive run out of its way and now applies to nothing a live
   * deployment writes.
   */
  @Column(name = "trigger_event_id", length = 255)
  public String triggerEventId;

  /** The name of the event that caused this run, recorded beside its id so the row reads. */
  @Column(name = "trigger_event_name", length = 255)
  public String triggerEventName;

  /** The triggering event's original timestamp, needed to reconstruct its step environment. */
  @Column(name = "trigger_event_occurred_at")
  public Instant triggerEventOccurredAt;

  /** The triggering event's canonical JSON payload, preserved verbatim across a restart. */
  @Column(name = "trigger_event_payload", columnDefinition = "text")
  public String triggerEventPayload;

  /**
   * The exact trigger file that matched this event. Parsing this snapshot, rather than the current
   * branch head, makes a recovered run execute the pipeline it originally accepted.
   */
  @Column(name = "trigger_config", columnDefinition = "text")
  public String triggerConfig;

  /**
   * Which committed file declared this run's pipeline: the matching {@code
   * .config/qits/ci-event-*.yml}, or {@code .config/qits/ci-post-receive.yml} on a historical push
   * row. Never null — it is the third column
   * of the unique constraint, and identity rather than description: two trigger files in one
   * repository matching one event are two runs by design, because they are two declared pipelines.
   */
  @Column(name = "config_path", nullable = false, length = 512)
  public String configPath;

  /**
   * Which release archetype recipe this run's pipeline was composed from, and <b>whose copy of it</b>
   * — or all four null.
   *
   * <p>{@code archetypeName} is what the repository's {@code release.yml} asked for and {@code
   * archetypeConfigPath} is {@code .config/qits/release-archetypes/<name>.yml}. The other two say
   * which of the two places the recipe came from, and exactly one of them is set:
   *
   * <ul>
   *   <li><b>{@code archetypeRev} non-null — shadowed locally.</b> The repository the run is for
   *       carries its own recipe at that path, and this is the revision it was read at: the same
   *       revision its {@code release.yml} was, so it equals {@code commitSha}. {@code
   *       archetypeVersion} is null.
   *   <li><b>{@code archetypeVersion} non-null — packaged.</b> The repository carries none, so the
   *       recipe built into qits-ci composed the run, and this is <b>that qits-ci-service's own
   *       version</b>. The path is then the file's path in qits-ci-service. {@code archetypeRev} is
   *       null: the recipe was read from no revision of any repository.
   * </ul>
   *
   * <p>So two rows of one repository at one {@code commitSha} whose {@code archetypeVersion}
   * differs were composed by two qits-ci releases — which is the whole story of a recipe fix
   * healing an earlier failure by retry.
   *
   * <p><b>The columns are older than that meaning, and their migrations still describe the old
   * one.</b> Until qits-583 the recipe was read from the wrapper repository at its newest released
   * tag: {@code archetypeRev} was the wrapper commit and {@code archetypeVersion} the wrapper
   * release that commit was, both set together. {@code V20__run_archetype.sql} and {@code
   * V22__run_archetype_version.sql} say so in their headers and are not edited — an applied
   * migration's checksum covers its prose — so the change of meaning is stated here. A row with
   * both set predates it.
   *
   * <p><b>All four null is the ordinary value and it means three different things, none of them
   * "unknown for this run".</b> A run from a committed {@code ci-event-*.yml} or a platform pipeline
   * was composed from nothing. A composed run whose slot file names no {@code archetype:} declares
   * its slots itself. And every row recorded before V20 genuinely does not know — which is exactly
   * why there is no backfill: a default would assert a recipe nobody can stand behind.
   *
   * <p><b>A retry records its OWN composition, never the source row's.</b> A retry re-composes on
   * purpose (see {@code CiEventTriggerService#recomposedReleaseDocument}): a local recipe is read
   * again at the run's commit and so names the same revision, a packaged one is the retrying
   * qits-ci's and names its version. The one exception is a retry that fell back to the stored
   * pipeline, which copies the source's four values because those are what will really run.
   *
   * <p>Part of no constraint and carrying no index: nothing looks a run up by any of them.
   */
  @Column(name = "archetype_name", length = 64)
  public String archetypeName;

  @Column(name = "archetype_config_path", length = 512)
  public String archetypeConfigPath;

  @Column(name = "archetype_rev", length = 64)
  public String archetypeRev;

  @Column(name = "archetype_version", length = 64)
  public String archetypeVersion;

  /**
   * <b>Which bytes the tools that ran this pipeline really were</b>: a JSON object mapping each
   * distinct image reference this run's steps named to the immutable digest reference it was
   * pinned to when the run was accepted. Null for a run that pinned nothing.
   *
   * <p><b>It is {@link #archetypeRev}'s twin for the other half of a run's environment.</b> That
   * column says which wrapper commit wrote the prelude; this one says which {@code
   * qits/build-images/*} the prelude ran inside. Every recipe on the estate names those images by a
   * floating {@code :latest}, and a pipeline composed step by step from a floating tag can boot two
   * different toolchains in one build — verify against one, publish from another — with nothing on
   * the row to say so. The pin is resolved <b>once per (run, reference)</b> and spent by every step
   * of that run, so a build can no longer straddle two versions of one tool, and this column is
   * what makes that readable afterwards.
   *
   * <p><b>Null is the ordinary value and it means three things, none of them "unknown".</b> A
   * pipeline whose every step names an image this platform does not publish ({@code alpine:3},
   * {@code docker:28-dind}) pins nothing — qits-ci holds no credential for another registry and
   * says so by recording no pin rather than by inventing one. A deployment with {@code
   * qits.ci.resolve-platform-step-images=false} resolves no platform image at all, which is that
   * switch's whole point. And every row written before this column existed genuinely does not know,
   * which is why there is no backfill: a digest asserted for a run that happened is a claim about
   * bytes nobody can now check.
   *
   * <p><b>A retry re-pins rather than copying</b>, {@code expectedStepDurations}' arm rather than
   * {@code priority}'s: the pin says which tool this execution will use, and the honest answer for
   * an execution that is about to happen is the one the registry gives now. A differing pin beside
   * an identical {@code commitSha} is then the record of the toolchain having moved between two
   * attempts at one commit — the same question {@code archetypeRev} answers about the recipe.
   *
   * <p>Part of no constraint, carrying no index, read whole and queried into by nothing — {@code
   * downstream_repos}' storage decision, for its reason. See {@link StepImages}.
   */
  @Column(name = "step_images", columnDefinition = "text")
  public String stepImages;

  /**
   * The {@link CiRunner} that held this run, or null for every run a runner did not execute — which
   * is every run whose steps qits-ci launched through qits-containers, and every row recorded before
   * {@code V23__runners.sql}.
   *
   * <p><b>No foreign key, for {@link #repoId}'s reason.</b> A decommissioned runner leaves its runs
   * behind as history: a key would either refuse the decommission or cascade the history away. What
   * reads it is {@code CiRunners} — a runner holding a {@code RUNNING} run may not be deleted, and
   * how many it holds is on its listing — and the run's own DTO, which names the runner.
   */
  @Column(name = "runner_id")
  public UUID runnerId;

  /**
   * What this run is for — {@link CiRunPurpose#BUILD} on every row but a runner's health check, and
   * on every row written before {@code V24__runner_quarantine.sql}, whose default wrote it.
   *
   * <p>Initialised here so every insert path that predates health checks keeps writing a build
   * without naming one; the one writer of {@link CiRunPurpose#HEALTHCHECK} is {@code
   * CiRunService.acceptHealthCheck}.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CiRunPurpose purpose = CiRunPurpose.BUILD;

  /**
   * The one runner a health check may be reserved by, or null on every build. No foreign key, for
   * {@link #runnerId}'s reason. It is what a runner's {@code Reserve} is matched against — and the
   * only thing that lets a quarantined runner take anything at all.
   */
  @Column(name = "target_runner_id")
  public UUID targetRunnerId;

  /** Whether this run is a runner's health check rather than a build. */
  public boolean healthCheck() {
    return purpose == CiRunPurpose.HEALTHCHECK;
  }
}
