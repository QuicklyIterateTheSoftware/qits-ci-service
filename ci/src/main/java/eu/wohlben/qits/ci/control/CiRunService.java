package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiConfigSource.CommitHeld;
import eu.wohlben.qits.ci.control.CiStepRunner.DaemonPin;
import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.entity.ExpectedStepDurations;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

/**
 * The pipeline orchestrator: a domain event matched a repository's trigger file → run the pipeline
 * that file declared, sequentially → record per-step pass/fail. Runs execute on a bounded pool of
 * daemon workers (the intake returns immediately; {@code qits.ci.concurrent-builds} controls how
 * many runs may execute at once), with each DB transition in its own {@link
 * QuarkusTransaction#requiringNew()} bracket so the slow container work never holds a transaction
 * (worker threads have no request context; the {@code BlobService}/{@code GitHostRoutes} stance).
 *
 * <p>That worker now parks on a socket rather than on a process — {@link CiStepRunner} waits for a
 * step container's own daemon — but the shape is unchanged: one blocking call per step, in order.
 *
 * <p><b>There is ONE way in, and that is the whole of the 2026-09-05 change.</b> {@link
 * #onEventTrigger} takes a domain event that matched a {@code .config/qits/ci-event-*.yml}: it names
 * no commit of its own and arrives with its pipeline already parsed at the head of {@code main}
 * ({@code CiEventTriggerService} had to read the file to know there was anything to run).
 *
 * <p>There used to be a second, {@code onPostReceive} — one accepted run per pushed branch ref,
 * reading {@code .config/qits/ci-post-receive.yml} out of the pushed commit on this class's own
 * worker. <b>The platform runs no CI outside release requests</b>, so every repository's
 * {@code ci-post-receive.yml} was deleted; the engine arm outlived them and kept enqueueing a run per
 * push against a file none of them carried — thirteen phantom {@code QUEUED} rows on 2026-09-05,
 * each holding a runner slot to discover that nothing was declared. An ordinary push now triggers
 * nothing at all, and it does so because there is no code left that could: no listener, no accept, no
 * worker half, no parser. {@code SCMPublishCommit} still reaches the generic trigger engine like
 * every other event, so a repository that <em>declares</em> {@code event: SCMPublishCommit} is served
 * by the ordinary grammar — which is a capability rather than a special case, and is why the engine
 * arm stays.
 *
 * <p><b>Steps are persisted at their end.</b> A {@link CiStep} row is inserted once, already
 * terminal; while a step runs it has no row at all and the live output is the runner's in-memory
 * relay, exposed on the run read surface as {@code live}. The never-run remainder is written {@code
 * SKIPPED} when the run closes. So the database never holds a half-written step and there is no
 * insert-then-update anywhere in a run.
 *
 * <h2>Patience: the READ surface holds through a short outage, and so do two request-path writes</h2>
 *
 * <p>The pool opens its connections through {@code PatientPgDriver}, so a request that has executed
 * nothing waits for postgres to come back rather than failing. What that cannot help is a connection
 * severed <b>mid-flight</b>, after the statements ran. The seven caller-facing reads at the bottom of
 * this class ({@link #activeRuns}, {@link #finishedRuns}, {@link #repositorySummaries}, {@link
 * #runsFor}, {@link #requireRun}, {@link #stepsFor}, {@link #repositoryIds}) are wrapped in {@link
 * DbRetry#call}, which sits <b>outside</b> {@code requiringNew}, never inside: inside one it would
 * re-run statements in a transaction the severed connection already doomed.
 *
 * <p><b>A write needs the other method, and it is not the same trade.</b> {@link DbRetry#inNewTx}
 * owns the transaction boundary, so it can retry the attempts that certainly did not commit — a
 * connection-class failure thrown out of the <em>body</em> — and rethrows everything the transaction
 * manager reports, because a lost commit acknowledgement is undecidable. Each wrapped body ends with
 * {@code runs.flush()} on purpose: an ORM flushes at commit by default, which would put the write on
 * the undecidable side of that line.
 *
 * <p><b>Two writes are wrapped, and they are the two on a request-shaped thread</b> — {@link
 * #acceptEventRun} and both transactions of {@link #cancel}. Their bodies are database-only: no
 * container is launched, no event published, no HTTP call made inside one, because the retry re-runs
 * the whole body. {@code acceptEventRun}'s check-then-insert-then-supersede is wrapped <b>whole</b>,
 * which is the only legal shape for it — splitting the bracket would cost the dedupe its race — and
 * that is sound because every statement in it is derived from the current database state and from the
 * arriving request, so a rolled-back attempt leaves the next one deciding the same way.
 *
 * <p><b>What is deliberately NOT wrapped.</b> No worker-thread transition ({@link #startQueued},
 * {@link #finishRun}, {@code failIncompleteSteps}, {@link #discardRun}, the step inserts) is wrapped:
 * those have their own recovery in {@code sweepInterrupted} at boot, which is a stronger answer than
 * a fifteen-second wait on the run worker. The rule they follow was written for a third case that no
 * longer exists — {@code acceptPostReceive} ran inside the push listener's claiming transaction,
 * where a retry outliving that claim's connection would have committed a {@code QUEUED} row behind an
 * event the funnel then re-offered — and it is worth keeping in front of you the next time an accept
 * is called from inside somebody else's transaction.
 *
 * <p>The validation each listing does — a non-positive {@code limit} — happens <em>before</em> the
 * wrap, so a caller bug is still one immediate 400 rather than something retried for fifteen
 * seconds. {@link NotFoundException} is thrown inside the wrap and is not retried either, because
 * {@code DbRetry} waits on connection failures only; every other throwable propagates at once.
 *
 * <h2>The run row is born at accept, not at start</h2>
 *
 * <p><b>{@link #onEventTrigger} inserts a {@link CiRunStatus#QUEUED} row before it returns</b>, and
 * the worker flips it to {@code RUNNING} when it dequeues it. Before that, a queued run was a closure
 * on this class's executor and nothing else — invisible to every read surface, and gone with the
 * process. That was the lossy intake: a redeploy landing between acceptance and the build lost the
 * build with no row anywhere to say so.
 *
 * <p>So the recording rule is <b>revised, deliberately</b>. It used to read "a run is only ever
 * recorded when it says something true about a commit", which was a statement about when the INSERT
 * happens. It now reads: <b>a run row exists from the moment the work is accepted, and it is removed
 * again if it turns out to describe nothing that happened.</b> What a <em>finished</em> worker
 * leaves behind is unchanged, outcome for outcome — the difference is a transient {@code QUEUED} row
 * in between, visible to {@code GET /ci/api/runs/active} and, briefly, to a repository's listing.
 *
 * <p>Recording semantics, per outcome, with what became of the accept-time row:
 *
 * <ul>
 *   <li>the repository no longer holds the commit ⇒ <b>discarded</b>, discovered in a step
 *       container's own checkout and confirmed with {@link CiConfigSource#commitHeld} — the commit
 *       the run was accepted for no longer exists, so a red run would blame a commit whose build was
 *       never broken;
 *   <li>a pipeline with no steps ⇒ a trivially green run;
 *   <li>cancelled while still {@code QUEUED} ⇒ {@code CANCELLED}, with no steps — cancellation is a
 *       user decision rather than a failed pipeline verdict.
 * </ul>
 *
 * <p>Two outcomes left this list with the push path and are worth knowing were once here, because
 * both are still reachable states on a historical row: {@code CONFIG_ERROR} (the config was read on
 * this worker, so a broken one was a run) and the discard for a repository that declared no pipeline
 * at all. A trigger file is read and parsed by {@code CiEventTriggerService} <em>before</em> a row
 * exists now, so a broken or absent one is a WARN and no run rather than a row to settle.
 *
 * <h2>What a restart costs now</h2>
 *
 * <p>{@link #sweepInterrupted} restarts a {@code RUNNING} event pipeline: the complete input
 * snapshot is on the row, so its partial step records are cleared and the run is restarted. Event
 * pipelines must therefore be idempotent. Anything else left {@code RUNNING} is failed instead —
 * which after the push retirement means a leftover {@code POST_RECEIVE} row from a predecessor
 * deployment, since replaying repository-authored work that already began is not safe and this engine
 * could not replay it anyway. Every {@code QUEUED} row a successor can run is re-enqueued oldest
 * first; one it cannot is settled {@code CANCELLED} rather than left to sit in {@code
 * /ci/api/runs/active} forever.
 *
 * <p>An event-triggered row also stores its event envelope and exact trigger-file content. The
 * worker reparses that immutable snapshot, so a queued event run is recoverable without consulting
 * a branch that may have moved and without relying on an at-most-once event redelivery.
 *
 * <h2>The queue is the table, and the workers claim out of it</h2>
 *
 * <p><b>There is no in-memory queue left.</b> {@link #initializeWorkers} starts {@code
 * concurrent-builds} loops ({@link #workerLoop}); each one reads every {@code QUEUED} row in one
 * transaction, asks the pure {@link CiRunOrdering} which of them to take, and claims the first it
 * can with {@link #startQueued} — the CAS that was already there, re-reading the status and the
 * draining flag inside the writing transaction. {@link #enqueue} is a one-permit wake on a
 * semaphore and nothing else; an idle worker parks on it for {@code qits.ci.queue-poll-interval}.
 *
 * <p>Three things follow, and each of them was a defect of the executor queue it replaced. Ordering
 * is <b>dynamic</b>: a high-priority run accepted while ten others wait is claimed next rather than
 * eleventh, because the order is re-derived on every pass rather than fixed at submit time. A
 * restart is no longer a special path — the boot sweep says "there is work" and the same loop
 * re-derives the same order from the same rows, which is a stronger guarantee than the "a restart
 * must not reorder a backlog" it replaces. And a manual reorder, if it is ever wanted, is a change
 * to one pure function rather than a reach into an executor.
 *
 * <p>What is deliberately NOT here: no cross-state blocking (a run may start while the run it
 * depends on is {@code RUNNING} — see {@link CiRunOrdering}), and no lock. The ordering is a
 * suggestion; the claim is the decision, and two workers racing for one row is settled by the
 * database exactly as it always was.
 *
 * <h2>The claim loop survives its workers</h2>
 *
 * <p><b>A pool of loops that never return has one failure mode the executor queue it replaced did
 * not have: a loop can END.</b> Nothing resubmits it, nothing counts it, and a qits-ci with zero
 * live claim loops accepts runs, writes {@code QUEUED} rows and releases permits nobody consumes —
 * while every health check it declares stays green. That is not a hypothesis: it happened after a
 * redeploy, runs sat {@code QUEUED} indefinitely, and only a process restart healed it. Four things
 * are what keep it from happening again, and each one closes a different half of it.
 *
 * <p><b>A leaked interrupt flag no longer retires a worker.</b> At least five helpers on this
 * thread's own call path catch {@code InterruptedException}, <em>restore</em> the flag and return a
 * fallback — {@code CiDaemonRegistry.await}, {@code CiDaemonLauncher}'s sleep, three in {@code
 * IdpCommissioner}, {@code HttpGitConfigSource}, {@code DbRetry}. Every one of them is locally
 * correct and every one of them used to end this loop, because the flag it left behind was
 * indistinguishable from a real shutdown. So the loop asks the question the flag cannot answer:
 * {@link #stopping} is what a shutdown looks like, and a flag found raised while that is false is a
 * <b>leak</b> — cleared, named in a WARN, and the loop stays on the queue.
 *
 * <p><b>An {@code Error} no longer kills a thread the pool will never refill.</b> {@link
 * #claimLoop} catches {@code Throwable}, so an {@code OutOfMemoryError}, a {@code
 * NoClassDefFoundError} or an {@code ExceptionInInitializerError} — the last two are exactly what a
 * native image produces when something was never registered — costs one pass and one ERROR rather
 * than one twelfth of the platform's build capacity, permanently.
 *
 * <p><b>And a loop that ends anyway is REPLACED.</b> {@link #supervised} is the outer net: whatever
 * a claim loop returns or throws, a fresh one is submitted in its place for as long as this process
 * is not stopping. It is deliberately the belt to the two braces above rather than a substitute for
 * them — a loop that survives is a loop whose backlog never waited on a resubmission.
 *
 * <p><b>The count is a fact rather than an inference.</b> {@link #workerCensus} is how many loops
 * are live, and {@code CiRunWorkerReadinessCheck} is what turns "zero, and this process is not
 * stopping" into a DOWN a deployer can act on. Green-while-dead is what misled the incident, and
 * that pair is what ends it.
 *
 * <p><b>One poison row costs one row.</b> A {@code QUEUED} row whose snapshot will not reconstruct
 * used to abandon the scan, which held up every row behind it in {@link
 * CiRunOrdering#suggestedOrder} for every worker, forever — and the boot sweep re-enqueued it, so
 * the wedge outlived restarts. It is settled {@code CANCELLED}/{@link #TRIGGER_UNREADABLE} now and
 * the scan continues; a row that cannot even be settled is skipped for this pass rather than
 * abandoning it.
 */
@ApplicationScoped
public class CiRunService {

  private static final Logger LOG = Logger.getLogger(CiRunService.class);

  /**
   * Boot order, second half. This observer runs <b>after</b> {@code CiDaemonLauncher.onStart}, whose
   * matching {@code @Priority} is one step lower. <b>Move neither alone</b> — see {@link #onStart}
   * for what the order buys.
   */
  public static final int BOOT_SWEEP_PRIORITY = 2100;

  /**
   * Prefixed onto an output tail whose head was dropped. Public because the runner applies the
   * budget incrementally, as output arrives, and must be able to say so with the same words — one
   * marker, one spelling.
   */
  public static final String TRUNCATION_MARKER = "[... output truncated ...]\n";

  /**
   * The branch a repository summary reports separately from its newest run. The platform's one
   * tracked branch — the same convention {@code CiEventTriggerService.TRIGGER_BRANCH} names, and
   * spelled here rather than imported from it because these are two independent facts that happen to
   * agree: one is where an event trigger reads, the other is what "is this repository green" means.
   */
  public static final String MAIN_BRANCH = "main";

  @Inject CiConfigSource configSource;
  @Inject CiEventTriggerParser triggerParser;
  @Inject CiStepRunner runner;
  @Inject CiRunRepository runs;
  @Inject CiStepRepository steps;

  /** The green-run event port (see {@link RunAnnouncer}); zero implementations is fine. */
  @Inject Instance<RunAnnouncer> runAnnouncers;

  /**
   * The gate a published artifact goes through — see {@link ReleaseJoin}. This class decides that a
   * release pipeline finished and what it published; that one decides whether the platform is
   * allowed to hear about it.
   */
  @Inject ReleaseJoin releaseJoin;

  /**
   * The field a release pipeline's version comes out of. It is the triggering event's payload, read
   * by name — {@code SCMRelease} carries it, and a trigger file declaring artifacts against an event
   * that does not, and that is not the tag event either, was written for something this cannot feed.
   */
  private static final String VERSION_FIELD = "version";

  /**
   * The git host's tag event, by the name it rides the bus under, and the payload field holding the
   * tag it announces. Together they are what makes {@link #supersedeByVersion} possible: an event
   * name this engine can recognise, and one field in its payload that orders.
   *
   * <p><b>Spelled as strings on purpose.</b> {@code qits-githost-events} is the {@code service}
   * module's dependency — the listener that binds {@code SCMPublishCommit} is what pays for it —
   * and this module deliberately has no compile-time knowledge of another context at all. What
   * keeps the strings honest is {@code bus/ScmPublishTagContractTest}, over in the module where the
   * record IS on the classpath: it resolves both against {@code SCMPublishTag} itself, so a rename
   * there is a red suite here rather than a supersede that quietly stops firing.
   */
  public static final String TAG_EVENT_NAME = "SCMPublishTag";

  public static final String TAG_NAME_FIELD = "tagName";

  /**
   * qits-projects' release-request event, by the name it rides the bus under, and the payload field
   * naming the request it is about.
   *
   * <p>A release request's backing branch is an octopus merge of N sources that qits-projects
   * rewrites on every change, and it publishes this event per successful re-fold. A repository's QA
   * pipeline selects it and checks out the fold — {@code checkout: { branch: backingBranch, sha:
   * mergedSha }} — and the verdict returns on the ordinary build events keyed on {@code (repoId,
   * commitSha)}. What that key cannot carry is <em>which request</em>, so the id is read here and
   * lands on {@link CiRun#releaseRequestId}: the sha is a fold nobody pushed and the next re-fold
   * replaces it, which makes the request id the only stable handle a cancellation or a retry can
   * address the work by.
   *
   * <p><b>Spelled as strings, exactly like {@link #TAG_EVENT_NAME}, and for a sharper version of the
   * same reason.</b> {@code ci} has no compile-time knowledge of another context — and here there is
   * no jar to have one of: <b>qits-projects publishes no vocabulary jar</b>, deliberately, the way
   * qits-workspaces does not (see {@code bus/ScmReleaseContractTest}). What keeps the two strings
   * honest is {@code bus/ReleaseRequestChangedContractTest}, which holds a transcription of the
   * published record and drives it through the real canonical serializer.
   *
   * <p><b>The engine gains no other knowledge of this event.</b> Matching, selection and checkout
   * are the generic grammar; these two constants buy exactly one provenance column.
   */
  public static final String RELEASE_REQUEST_EVENT_NAME = "ReleaseRequestChanged";

  public static final String RELEASE_REQUEST_ID_FIELD = "releaseRequestId";

  /**
   * The payload field naming what a piece of release work is worth, on <b>both</b> events that carry
   * one: {@code ReleaseRequestChanged} (the request's effective priority, folded in qits-projects
   * from its participating branches) and {@code SCMRelease} (the same value, carried down to the
   * release itself).
   *
   * <p>One spelling for two events because it is one field: qits-projects publishes both and names
   * it the same on each. The read is <b>gated on those two names</b>, exactly as {@link
   * #RELEASE_REQUEST_ID_FIELD}'s is gated on one — a {@code priority} elsewhere on the bus is some
   * other context's word about something else, and a column that reads any field of any payload
   * eventually records something nobody meant.
   *
   * <p>Spelled as a string, and guarded by {@code bus/ReleaseRequestChangedContractTest} and {@code
   * bus/ScmReleaseContractTest} over in the module where those records can be transcribed at all.
   */
  public static final String PRIORITY_FIELD = "priority";

  /**
   * The payload field naming what depends on a release request's repository, on {@code
   * ReleaseRequestChanged} alone.
   *
   * <p>qits-projects asks qits-maintenance for the downstream closure of the repository being folded
   * and carries the answer here, nearest first. qits-ci stores it verbatim on {@link
   * CiRun#downstreamRepos} and {@link CiRunOrdering} sequences the queue by it: a queued run whose
   * repository is named in another queued run's list waits for that one.
   *
   * <p><b>Absent is the ordinary value and never an error.</b> The field is additive, a qits-projects
   * that cannot reach qits-maintenance publishes none, and {@code CanonicalJson}'s NON_NULL
   * inclusion makes "stated none" and "predates the field" the same absent key. An absent list is
   * read as unknown, which constrains nothing — so this whole feature degrades to the ordering this
   * service had before it, which is {@code (createdAt, id)}.
   */
  public static final String RELEASE_REQUEST_DOWNSTREAM_FIELD = "downstreamTechnicalComponents";

  /** What {@code ci_run.release_request_id} can hold; a longer value is recorded as none. */
  static final int MAX_RELEASE_REQUEST_ID_LENGTH = 255;

  /**
   * What {@code ci_run.priority} can hold; a longer value is recorded as none, with a WARN.
   *
   * <p>Deliberately <b>the same number as {@link ReleaseJoin#MAX_PRIORITY_LENGTH}</b> and derived
   * from it rather than repeated: the two columns hold the same vocabulary's words about the same
   * piece of work, and a value one row could hold while the other could not would make one release
   * read two ways depending on which row was asked.
   */
  static final int MAX_PRIORITY_LENGTH = ReleaseJoin.MAX_PRIORITY_LENGTH;

  /**
   * How many recent successful runs of a step the expected-duration prediction is taken over.
   *
   * <p>Small on purpose. What the prediction is for is a progress bar drawn while a run executes, so
   * what it should reflect is how the pipeline behaves <em>now</em> — twenty-five runs is a few days
   * of a busy repository and a couple of weeks of a quiet one, which is recent enough that a
   * pipeline that got faster stops being predicted by the version that was slow, and long enough
   * that one unlucky run does not move the answer on its own.
   */
  static final int DURATION_SAMPLE_SIZE = 25;

  /**
   * Which percentile of that window a step's expected duration is.
   *
   * <p><b>p95 rather than a median, and the asymmetry is the point.</b> A bar that finishes early is
   * a bar that was right about the work and pessimistic about the machine; a bar that fills up and
   * then sits at 100% while the step keeps running is a bar nobody trusts again. So the prediction
   * is deliberately the slow end of what this step has really been doing.
   */
  static final double DURATION_PERCENTILE = 0.95;

  public static final String USER_CANCELLED = "USER_CANCELLED";
  public static final String DEDUPED = "DEDUPED";

  /**
   * What a run cancelled through {@code POST /ci/api/runs/cancellations} records: the release
   * request it served was withdrawn, closed or re-scoped, so nobody is waiting for the answer any
   * more. Distinct from {@link #USER_CANCELLED} because the two are read differently by whoever
   * looks at the row later — one person stopped one build, versus a whole piece of work went away.
   */
  public static final String RELEASE_REQUEST_CANCELLED = "RELEASE_REQUEST_CANCELLED";

  /**
   * What a row this engine cannot execute records when a boot sweep hands it back: a {@code
   * POST_RECEIVE} run left {@code QUEUED} by a deployment that predates the 2026-09-05 retirement of
   * per-push CI. Its own reason rather than {@link #USER_CANCELLED}, because nobody cancelled it —
   * the engine that would have run it is gone, and a person reading the row a year from now should
   * find that out from the row rather than from a changelog.
   */
  public static final String TRIGGER_RETIRED = "TRIGGER_RETIRED";

  /**
   * What a {@code QUEUED} row whose own snapshot will not reconstruct records: the trigger file
   * stored on the row no longer parses, so there is no pipeline to run and never will be.
   *
   * <p><b>Its own reason beside {@link #TRIGGER_RETIRED}, and for that constant's reason.</b>
   * Nobody cancelled such a run either — what happened to it is that the engine cannot read the
   * work it accepted, which a person reading the row a year from now should find out from the row
   * rather than from a log line that has long since rotated.
   *
   * <p>It is unreachable through the engine, since the snapshot parsed once at accept, and it is
   * settled anyway rather than left queued: <b>the alternative is what wedged a live queue.</b>
   * A row nobody can run and nobody settles is a row every worker walks up to and stops at, on
   * every pass, ahead of everything the ordering put behind it — and the boot sweep hands it
   * straight back, so a restart does not clear it either. Settled, it is out of {@code
   * listQueuedOldestFirst} and out of the sweep's way by construction.
   */
  public static final String TRIGGER_UNREADABLE = "TRIGGER_UNREADABLE";

  public static final int MAX_CANCELLATION_REASON_LENGTH = 255;

  /**
   * What a manually retried run records as its {@code trigger_event_id}, prefixed onto its own run
   * id.
   *
   * <p><b>This is the dedupe bypass, and the prefix is what keeps it honest.</b> A retry is the same
   * work as the run it re-fires — same repository, same trigger file — so every column of {@code
   * unique (trigger_event_id, repo_id, config_path)} would be identical and the insert would be
   * refused as the replay that constraint exists to refuse. Minting a fresh identity out of the new
   * run's own id makes the row unique by construction with the constraint left exactly as it is; the
   * prefix makes it unmistakably local, so nothing ever looks this value up in the event log. The
   * causation edge is not lost with it — {@link CiRun#causationId} is copied from the run being
   * retried, and {@link #causingEventId} is what reads it back for the announcers.
   */
  public static final String RETRY_TRIGGER_PREFIX = "retry:";

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  /** The deadline a step gets when its declaration does not name one. */
  @ConfigProperty(name = "qits.ci.step-timeout-seconds")
  int stepTimeoutSeconds;

  /** The instance-wide upper bound on runs executing at the same time. */
  @ConfigProperty(name = "qits.ci.concurrent-builds")
  int concurrentBuilds;

  /**
   * How long an idle worker waits for a wake before scanning the queue anyway.
   *
   * <p><b>A net under the wake, not the mechanism.</b> Every accept, retry and boot sweep releases a
   * permit, so the ordinary path is immediate; this is what covers the cases where no permit was
   * ever released for a row that is nevertheless {@code QUEUED} — a run accepted by a process that
   * died before it could wake anything, a row written by an operator, a permit consumed by a worker
   * that lost the race to claim it. Ten seconds is short enough that such a row is never stranded
   * and long enough that an idle instance is not polling its database for a living.
   *
   * <p>No {@code defaultValue} here: the shipped ten seconds is in the {@code ci} jar's {@code
   * META-INF/microprofile-config.properties} with its argument beside it, which is where {@code
   * qits.ci.concurrent-builds} keeps its own and the one place either can be changed.
   */
  @ConfigProperty(name = "qits.ci.queue-poll-interval")
  Duration queuePollInterval;

  /**
   * How long a <b>read</b> holds while the datasource is gone — see "Patience" in this class's
   * javadoc. The shipped 15s covers a postgres cutover; the suite shortens it, because a test
   * proving the give-up must not pay for it.
   */
  @ConfigProperty(name = "qits.ci.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /**
   * qits-platform-artifacts' coordinates, for resolving a step image a recipe named without a
   * registry — see {@link CiStepImage} for the rule and for what it is worth. The same two keys the
   * publish scripts compose as {@code $QITS_REGISTRY} and {@code $QITS_IMAGE_REPOSITORY}, read here
   * so a recipe keeps naming no deployment fact of its own. RECEIVER-NAMED, like every other reader
   * of this pair.
   */
  @ConfigProperty(name = "qits.artifacts.registry-host")
  String artifactsRegistryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String artifactsImageRepository;

  /**
   * The kill switch for that resolution, and it exists because of what it costs to be wrong.
   *
   * <p>Resolving points every step at the registry, so a registry that does not hold these images
   * yet breaks <b>every build in every repository at once</b> — and the pipeline that would publish
   * them runs on one of them, so there is no run left that could fix it. That is not a hypothetical:
   * on 2026-08-20 all five were absent from the registry <i>and</i> from the host, and an artifacts
   * store has been re-seeded without them before. Off, a deployment falls back to the local store
   * exactly as it always behaved, without rebuilding or redeploying this service.
   *
   * <p>Shipped ON, because the recoverable state is the one worth defaulting to and the failure is
   * loud, immediate and reversible by one variable.
   */
  @ConfigProperty(name = "qits.ci.resolve-platform-step-images", defaultValue = "true")
  boolean resolvePlatformStepImages;

  /**
   * Runs a user asked to stop. In memory and deliberately so: a cancellation is only meaningful
   * while the run it addresses is executing in <em>this</em> process, and a restart fails every
   * in-flight run anyway.
   */
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

  /**
   * Raised once this process is on its way out, and read by everything that would otherwise take
   * new work: {@link #enqueue} and the claim in {@link #startQueued}.
   *
   * <p><b>A dying process must claim nothing.</b> On 2026-08-23 a start-first successor booted, ran
   * its sweep, and only then did the predecessor claim a {@code QUEUED} row and die holding it
   * {@code RUNNING} — a row past every sweep, owned by no worker anywhere, which had to be flipped
   * by hand. Leaving the row {@code QUEUED} instead costs nothing: the successor's boot sweep
   * re-enqueues it.
   *
   * <p><b>The order is what makes the flag work.</b> {@link #onStop} observes {@code ShutdownEvent},
   * which Quarkus fires before it destroys beans — so the flag is up before {@link #shutdown}
   * stops the worker pool, and no worker can start a claim after it. A claim already past the read
   * commits and runs as normal in-flight work; that window is one transaction wide, and the run it
   * produces is an owned one.
   */
  private volatile boolean draining;

  /**
   * Raised by {@link #shutdown} so a worker parked on {@link #work} stops looping once it is woken.
   *
   * <p>Distinct from {@link #draining}, and both are needed. {@code draining} is a <b>policy</b> a
   * test raises and lowers and a {@code ShutdownEvent} raises for real: a draining worker keeps
   * running, it simply claims nothing. This one is the <b>end of the thread</b>, set once and never
   * cleared, so a worker that wakes during bean destruction returns instead of touching a datasource
   * Quarkus is closing.
   */
  private volatile boolean stopping;

  /**
   * The wake. One permit is released per accepted run, per retry and per swept backlog; a worker
   * with nothing to do parks on it for {@link #queuePollInterval}.
   *
   * <p><b>Permits accumulate and that is harmless — never drain them.</b> A surplus permit costs one
   * scan of the queued rows, which is one indexed read; a drained one costs a run its wake, and the
   * only thing that would then start it is the poll interval. The asymmetry is the whole argument.
   */
  private final Semaphore work = new Semaphore(0);

  /**
   * How many workers are inside {@link #claimAndRunOne} right now — <b>incremented before the
   * candidate read, not after the claim</b>.
   *
   * <p>That order is the point. It makes "a worker holds a row nobody can see it holding" impossible:
   * between the scan and the {@code RUNNING} flip a row is still {@code QUEUED} and a reader asking
   * "is the queue drained" would otherwise be told yes about work that is one statement from
   * starting. {@link #awaitIdle} is that reader and the suite is built on its answer.
   */
  private final AtomicInteger busyWorkers = new AtomicInteger();

  /**
   * How many claim loops are alive right now — the number {@code CiRunWorkerReadinessCheck} turns
   * into a health verdict, and the one fact the live incident had no way of stating.
   *
   * <p><b>Distinct from {@link #busyWorkers}, and the difference is the whole point.</b> That one
   * counts loops that are <em>inside</em> a claim; this one counts loops that exist at all. A
   * process whose every worker has quietly died reads zero here and zero there, and only this one
   * says anything is wrong: an idle instance is legitimately zero-busy forever.
   */
  private final AtomicInteger liveWorkers = new AtomicInteger();

  private ExecutorService worker;

  /**
   * Starts the claim loops: {@code concurrent-builds} threads, each one claiming and running one run
   * at a time for the life of the process.
   *
   * <p><b>The pool is exactly saturated on purpose.</b> Every thread it has is occupied by a loop
   * that never returns, so nothing else may ever be submitted to it — the queue's shape lives in the
   * database now, not in an executor. The one exception is {@link #supervised}'s own resubmission,
   * which is a <em>replacement</em> for a loop that has already ended and therefore never adds a
   * task to a saturated pool.
   */
  @PostConstruct
  void initializeWorkers() {
    worker = createWorkerPool(concurrentBuilds);
    for (int i = 0; i < concurrentBuilds; i++) {
      worker.submit(supervised(worker, () -> !stopping, this::workerLoop));
    }
  }

  /**
   * Keeps one claim loop on the pool for as long as {@code stillWanted} says so: whatever the loop
   * returns or throws, a fresh one is submitted in its place.
   *
   * <p><b>The resubmission is inside the {@code finally} and that is deadlock-free rather than
   * lucky.</b> {@link ExecutorService#execute} on a saturated fixed pool <em>queues</em> the task
   * rather than running it, and the thread that queued it is the one about to become free — so the
   * replacement starts the moment this task ends and no thread ever waits for another.
   *
   * <p><b>A shutdown is the one thing it does not race.</b> {@code stopping} is raised before {@code
   * shutdownNow}, so a loop ending during a shutdown is not replaced; and a submission that slips
   * past that check anyway is refused by the stopped pool, which is a {@code
   * RejectedExecutionException} and an INFO rather than a failure — the shutdown is what was asked
   * for.
   *
   * <p>Static and parameterised so the mechanism itself is unit-testable ({@code
   * CiRunWorkerPoolTest}) with no database, no CDI and no run: a supervisor nothing exercises is a
   * supervisor nobody knows resubmits.
   */
  static Runnable supervised(ExecutorService pool, BooleanSupplier stillWanted, Runnable loop) {
    return new Runnable() {
      @Override
      public void run() {
        try {
          loop.run();
        } catch (Throwable fatal) {
          // Nothing above this catches an Error out of the loop's own frame, and a fixed pool never
          // refills a thread that died of one. Say it, then replace the loop below.
          LOG.errorf(
              fatal,
              "A CI run worker left its claim loop on %s",
              fatal.getClass().getName());
        } finally {
          if (stillWanted.getAsBoolean()) {
            try {
              pool.execute(this);
            } catch (RejectedExecutionException stopped) {
              LOG.infof(
                  "A CI run worker was not replaced: the worker pool is shut down (%s)",
                  stopped.getMessage());
            }
          }
        }
      }
    };
  }

  /**
   * One worker's whole life, with the census around it: claim the best queued run and run it, or
   * park until something says there may be work.
   *
   * <p>The count is kept here rather than in {@link #supervised} because it is this class's fact
   * about its own queue, and because the two log lines it makes possible are the ones the live
   * incident needed and did not have — a worker starting, and a worker leaving while the queue is
   * still live.
   */
  private void workerLoop() {
    int live = liveWorkers.incrementAndGet();
    LOG.debugf("A CI run worker is claiming — %d of %d claim loop(s) live", live, concurrentBuilds);
    try {
      claimLoop();
    } finally {
      int remaining = liveWorkers.decrementAndGet();
      if (stopping) {
        LOG.debugf("A CI run worker stopped — %d claim loop(s) left", remaining);
      } else if (remaining == 0) {
        LOG.errorf(
            "The LAST CI run worker left its claim loop while this process is not stopping —"
                + " nothing would claim a QUEUED row until it is replaced; the readiness check"
                + " reports this instance DOWN until one is");
      } else {
        LOG.errorf(
            "A CI run worker left its claim loop while this process is not stopping — %d of %d"
                + " left, and a replacement is being started",
            remaining, concurrentBuilds);
      }
    }
  }

  /**
   * The claim loop proper: re-derive the queue's order, take the first row this process can have,
   * and park on the wake when there was nothing.
   *
   * <p>It re-scans immediately after a successful claim rather than parking, because a backlog is
   * exactly the case where the next run is already there — and it re-derives the order every time,
   * which is what makes a late high-priority arrival jump a queue this loop had already looked at.
   *
   * <p><b>{@link #stopping} is the only thing that ends it, and that is the 2026-09-08 change.</b>
   * It used to end on {@code Thread.currentThread().isInterrupted()} as well, which reads like
   * ordinary hygiene and was the defect: half a dozen helpers on this thread's call path catch
   * {@code InterruptedException} and <em>restore</em> the flag before returning a fallback, so one
   * step failure could retire a worker for the life of the process — silently, and until all of
   * them had gone and the queue was dead. A flag raised while {@code stopping} is false is now read
   * as what it is, a leak: cleared, named, and the loop stays.
   *
   * <p><b>And it catches {@code Throwable}, not {@code RuntimeException}.</b> An {@code Error} on
   * this thread is a permanently missing build slot, and in a native image {@code
   * NoClassDefFoundError}/{@code ExceptionInInitializerError} are the ordinary shape of a missing
   * reflection registration. Whatever it was, one pass and one ERROR is the honest price.
   */
  private void claimLoop() {
    while (!stopping) {
      if (Thread.interrupted()) {
        if (stopping) {
          // A real shutdown: shutdownNow interrupted us and the flag is the messenger.
          Thread.currentThread().interrupt();
          return;
        }
        LOG.warnf(
            "A CI run worker found its interrupt flag raised while this process is not stopping —"
                + " a helper on the run path restored it after catching an InterruptedException."
                + " Clearing it; this worker stays on the queue.");
      }
      try {
        if (!draining && claimAndRunOne()) {
          continue;
        }
        work.tryAcquire(queuePollInterval.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        if (stopping) {
          Thread.currentThread().interrupt();
          return;
        }
        // The same leak, arriving while parked instead of before the park. Catching it already
        // cleared the flag; the loop simply goes round again.
        LOG.warnf(
            "A CI run worker was interrupted while parked and this process is not stopping —"
                + " treating it as a restored flag rather than as a shutdown");
      } catch (Throwable failure) {
        // The datasource is gone, something in the scan is broken, or a class the image never
        // registered was touched. None of them is a reason to lose a worker for the life of the
        // process, and none is a reason to spin: say it once and wait out the poll interval like an
        // idle worker does.
        LOG.errorf(
            failure,
            "A CI run worker could not claim work (%s) — retrying after the poll interval",
            failure.getClass().getName());
        if (!park()) {
          return;
        }
      }
    }
  }

  /**
   * Waits out one poll interval after a failed pass.
   *
   * @return whether the loop should keep going — false only for a genuine shutdown.
   */
  private boolean park() {
    try {
      work.tryAcquire(queuePollInterval.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException interrupted) {
      if (stopping) {
        Thread.currentThread().interrupt();
        return false;
      }
      return true;
    }
  }

  /**
   * How many claim loops exist right now, how many this instance is configured for, and whether it
   * is on its way out.
   *
   * <p>All three, because the interesting answer is a conjunction: <b>zero live loops while the
   * process is not stopping</b> is the state a healthy-looking qits-ci with a dead queue is in, and
   * zero live loops during a shutdown is what a shutdown is.
   */
  public record WorkerCensus(int live, int configured, boolean stopping) {}

  /** @see WorkerCensus */
  public WorkerCensus workerCensus() {
    return new WorkerCensus(liveWorkers.get(), concurrentBuilds, stopping);
  }

  /**
   * One pass of the claim loop: read every {@code QUEUED} row, ask {@link CiRunOrdering} what order
   * they should be claimed in, and take the first one this process can actually have.
   *
   * <p><b>No candidate ends the scan except the one that is claimed.</b> That is the 2026-09-08
   * change and it closes the regression the queue's own notes recorded as "the follow-up": a row the
   * loop could neither run nor settle used to {@code return false} here, so every row the ordering
   * put behind it was unreachable by <em>every</em> worker, on every pass, until somebody edited the
   * database — and the boot sweep handed the same row back, so a restart did not clear it either.
   * A row that cannot be reconstructed is settled terminally and the walk goes on; a row that cannot
   * even be settled is skipped for this pass, which costs it one pass instead of costing the queue
   * everything behind it.
   *
   * @return whether this pass did something — a run executed, or a row settled. False means the
   *     queue held nothing for this worker and it should park.
   */
  private boolean claimAndRunOne() {
    // Incremented BEFORE the read, and see the field's javadoc for why: from here until the finally,
    // this worker is holding a claim somebody else may not conclude is absent.
    busyWorkers.incrementAndGet();
    try {
      List<CiRun> candidates =
          QuarkusTransaction.requiringNew()
              .call(() -> CiRunOrdering.suggestedOrder(runs.listQueuedOldestFirst()));
      boolean settledSomething = false;
      for (CiRun candidate : candidates) {
        String runId = candidate.id;
        if (!runnable(candidate)) {
          settledSomething |= retireUnrunnable(candidate);
          continue;
        }
        EventRun request;
        try {
          request = reconstructEventRun(candidate);
        } catch (RuntimeException unparseable) {
          // The snapshot on the row parsed once, at accept, so this is unreachable through the
          // engine. If it ever is not, the row is one this process has learned it can never run —
          // which is a settleable fact rather than an unknown one — so it is settled with a reason
          // of its own and the scan continues to the rows behind it.
          LOG.errorf(
              unparseable,
              "CI run %s cannot be reconstructed from its own row (%s) — settling it CANCELLED/%s"
                  + " and continuing the scan",
              runId,
              candidate.configPath,
              TRIGGER_UNREADABLE);
          settledSomething |= settleUnreadable(candidate);
          continue;
        }
        CiRun claimed = startQueued(runId);
        if (claimed == null) {
          // Cancelled, superseded, already taken by another worker, or this process is draining.
          // All four are ordinary and none of them is this pass's business.
          LOG.debugf("CI run %s is no longer queued — moving on to the next candidate", runId);
          cancelled.remove(runId);
          continue;
        }
        executeClaimed(claimed, request);
        return true;
      }
      return settledSomething;
    } finally {
      busyWorkers.decrementAndGet();
    }
  }

  static ExecutorService createWorkerPool(int concurrentBuilds) {
    if (concurrentBuilds < 1) {
      throw new IllegalArgumentException("qits.ci.concurrent-builds must be at least 1");
    }
    AtomicInteger workerNumber = new AtomicInteger();
    return Executors.newFixedThreadPool(
        concurrentBuilds,
        r -> {
          Thread t = new Thread(r, "ci-run-worker-" + workerNumber.incrementAndGet());
          t.setDaemon(true);
          return t;
        });
  }

  /**
   * Stops this process taking new work, and runs <b>before</b> {@link #shutdown} — see {@link
   * #draining} for why the pair has to be in that order.
   */
  void onStop(@Observes ShutdownEvent event) {
    draining(true);
  }

  /**
   * A method rather than a bare field, and that is load-bearing: this bean is normal-scoped, so a
   * test holds a client proxy and a field write would land on the proxy. It is also how a suite puts
   * the flag back down — a real process never does.
   */
  void draining(boolean draining) {
    this.draining = draining;
  }

  /**
   * Ends the claim loops. Three statements and the order is the whole of it: raise {@link #stopping}
   * so a woken worker returns rather than scanning, hand out one permit per worker so a worker
   * parked on the poll interval wakes at once instead of holding the shutdown for it, and only then
   * interrupt whatever is left — a worker inside a step, which is exactly what {@code draining}
   * plus the successor's boot sweep exist to make survivable.
   *
   * <p><b>The first statement is also what switches the supervisor off</b>, and it has to come
   * first for that reason as well: {@link #supervised} replaces a loop that ended only while {@code
   * stopping} is false, so raising it before the interrupt is what makes a shutdown a shutdown
   * rather than a stream of replacements. The {@code shutdownNow} is the second net — a task
   * submitted into the gap is refused rather than run.
   */
  @PreDestroy
  void shutdown() {
    stopping = true;
    work.release(concurrentBuilds);
    worker.shutdownNow();
    LOG.debugf("The CI run worker pool is stopping — %d claim loop(s) still live", liveWorkers.get());
  }

  /**
   * Reconciles what a previous process left behind, and the two halves pull in opposite directions
   * on purpose.
   *
   * <p>An event run left {@code RUNNING} is reset and restarted from its durable snapshot, because
   * everything it needs is on the row; trigger scripts are consequently an at-least-once/idempotent
   * boundary. Anything else left {@code RUNNING} cannot make progress and is failed once here —
   * after the push retirement that is a {@code POST_RECEIVE} row a predecessor deployment left, work
   * this engine has no worker for and could not safely replay if it had one.
   *
   * <p>A run left {@code QUEUED} never started. Its row is the whole of it, so it is <b>put back on
   * the worker</b> in {@code createdAt} order rather than failed: this is the point of the status
   * existing, and it is what closes the cutover loss for builds that were accepted while qits-ci was
   * redeploying itself. Event-triggered rows contain their envelope and trigger snapshot too, so
   * every runnable row takes the same recovery path. One this engine cannot run is settled {@code
   * CANCELLED} instead — see {@link #enqueue}, which is where that decision lives.
   *
   * <p>The container half of the same reconciliation is {@code CiDaemonLauncher.onStart}, which
   * reaps what the {@code RUNNING} runs left behind; it is a second observer because it needs docker
   * and this module has no business knowing about it.
   *
   * <p><b>That half runs first, and the {@code @Priority} pair is what says so.</b> This one does
   * not only write rows: it hands work straight back to the run worker, which starts labelled
   * containers of its own. The reap filters on the label alone and cannot tell a container this boot
   * just started from one the previous life left, so running it second would let it remove a
   * restarted run's first container. {@code CiDaemonLauncher.onStart} therefore carries the lower
   * priority and this one the higher; <b>neither moves alone</b>, and {@code
   * BootReconciliationOrderTest} holds the pair.
   */
  void onStart(@Observes @Priority(BOOT_SWEEP_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    sweepInterrupted();
  }

  /**
   * What one sweep found: how many rows are queued for the claim loop to take, and the runs it
   * failed — announced after the commit.
   *
   * <p>{@code requeued} is a <b>count</b> rather than a list of ids, and that is the claim loop
   * showing through: a row is claimed because it is {@code QUEUED} in the table, never because a
   * particular id was handed to a particular thread. The sweep's whole remaining job is to say
   * "there is work" once.
   *
   * <p><b>{@code restarted} is a list where {@code requeued} is a count, and the asymmetry is the
   * same distinction pointed at two different readers.</b> The claim loop wants a wake and nothing
   * else, so a count is the whole of what it is owed. A mirror of the active listing is owed the
   * rows: a run this sweep moved from {@code RUNNING} back to {@code QUEUED} is a transition like any
   * other, and one nobody else will ever announce — the run's next announcement is the {@code
   * RUNNING} a worker's claim makes, minutes later and from a state the mirror was never told about.
   */
  private record Sweep(int requeued, List<FailedOrphan> failed, List<CiRun> restarted) {}

  /** One orphaned run the sweep marked FAILED, with the instant the row was stamped with. */
  private record FailedOrphan(CiRun run, Instant finishedAt) {}

  /**
   * The sweep itself — package-private because {@link #onStart} skips test mode, so this is what a
   * suite drives to make a claim about a restart.
   *
   * <p>The re-enqueue happens <b>after</b> the transaction commits: the worker's first act on a run
   * is to read its row back and check it is still {@code QUEUED}, which it cannot do against a write
   * this thread has not committed yet.
   */
  void sweepInterrupted() {
    Sweep sweep;
    try {
      sweep = QuarkusTransaction.requiringNew().call(this::reconcile);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted CI runs at startup");
      return;
    }
    if (!sweep.failed().isEmpty()) {
      LOG.infof(
          "Marked %d CI run(s) left RUNNING by a previous shutdown as FAILED",
          sweep.failed().size());
      // After the commit, like the re-enqueue below and for the same reason turned outward: a
      // consumer told the run failed must be able to read the terminal row back. An interrupted
      // run is a real failure of a real commit, so the per-commit ledger hears about it too.
      for (FailedOrphan orphan : sweep.failed()) {
        announceStatus(
            orphan.run(), CiRunStatus.FAILED, CiRunStatus.RUNNING, orphan.finishedAt());
        announceFailedRun(orphan.run(), orphan.finishedAt(), CiRunStatus.FAILED);
      }
    }
    if (!sweep.restarted().isEmpty()) {
      LOG.infof(
          "Restarting %d event-triggered CI run(s) interrupted by the previous shutdown",
          sweep.restarted().size());
      // The one transition on this path that goes BACKWARDS, and the one nothing else would ever
      // say: the run was RUNNING in a process that is gone and is queued again here. Its next
      // announcement is a worker's RUNNING claim, so a mirror not told about this one would be
      // holding a run it believes is still executing on a dead instance.
      for (CiRun restarted : sweep.restarted()) {
        announceStatus(restarted, CiRunStatus.QUEUED, CiRunStatus.RUNNING, restarted.createdAt);
      }
    }
    if (sweep.requeued() > 0) {
      LOG.infof("Re-enqueued %d CI run(s) left QUEUED by a previous shutdown", sweep.requeued());
      // One permit per worker rather than one per row: the rows are in the table and the claim loop
      // re-derives their order for itself, so what a sweep owes is "wake up", once, to everybody.
      work.release(concurrentBuilds);
    }
  }

  private Sweep reconcile() {
    List<CiRun> orphans = runs.list("status = ?1", CiRunStatus.RUNNING);
    List<FailedOrphan> failed = new ArrayList<>();
    List<CiRun> restarted = new ArrayList<>();
    for (CiRun orphan : orphans) {
      // The restartable case, and after the push retirement the only one a live deployment
      // produces. The `else` covers what a predecessor left: a POST_RECEIVE row whose in-flight
      // step died with its process, which this engine has no worker for and could not safely
      // replay if it had one.
      if (orphan.triggerType == CiTriggerType.EVENT && orphan.triggerConfig != null) {
        steps.delete("runId = ?1", orphan.id);
        orphan.status = CiRunStatus.QUEUED;
        orphan.finishedAt = null;
        orphan.daemonVersion = null;
        restarted.add(orphan);
      } else {
        failIncompleteSteps(orphan.id);
        orphan.status = CiRunStatus.FAILED;
        orphan.finishedAt = Instant.now();
        failed.add(new FailedOrphan(orphan, orphan.finishedAt));
      }
    }

    // Every QUEUED row is the claim loop's, including one this engine can no longer run — a
    // POST_RECEIVE leftover from a predecessor deployment. The loop is the single place that decides
    // what a row is worth (it has to be: a row can also become unrunnable between here and a worker
    // reaching it), and it settles such a row CANCELLED rather than leaving it QUEUED for a
    // successor that will make the same discovery. The count includes the rows this method just
    // moved back to QUEUED — the query flushes them first, which is what makes an interrupted event
    // run restart on this boot rather than on the next one.
    return new Sweep(runs.listQueuedOldestFirst().size(), failed, restarted);
  }

  /**
   * How a recorded run addresses its own repository: the storage id always, and the public
   * {@code (projectId, repoName)} pair when the catalogue could name one for it.
   *
   * <p>A method here rather than on the entity, because {@code ci/entity} is Panache rows and this
   * is the control layer's vocabulary — and because every reader of a run row wants exactly this
   * one question answered.
   */
  static CiRepoRef repoOf(CiRun run) {
    return new CiRepoRef(run.repoId, run.projectId, run.repoName);
  }

  /** What a run left behind for the successor is told, in one wording, from both places. */
  private static void logLeftQueuedWhileDraining(String runId) {
    LOG.infof(
        "CI run %s left QUEUED: this process is shutting down, the successor's boot sweep"
            + " re-enqueues it",
        runId);
  }

  /**
   * Tells the claim loop there is work.
   *
   * <p><b>It hands nothing to anybody, and that is the 2026-09-07 change.</b> This method used to
   * submit a closure per run to the worker pool, which made the order runs executed in the order
   * they were submitted — an ordering decided at accept time, by the thread that happened to accept
   * first, and unchangeable afterwards. The row is the queue now: a worker reads every {@code
   * QUEUED} row, asks {@link CiRunOrdering} which one to take, and takes it. So a high-priority run
   * accepted while ten others wait is claimed next rather than eleventh, a restart re-derives the
   * same order from the same rows, and a future manual reorder has one function to change instead of
   * an executor to reach into.
   *
   * <p>What is left here is the <b>wake</b>, and the draining refusal, which is unchanged: a dying
   * process must not start work, so it does not even say there is any. The row stays {@code QUEUED}
   * for the successor's boot sweep — see {@link #draining}.
   */
  private void enqueue(String runId) {
    if (draining) {
      logLeftQueuedWhileDraining(runId);
      return;
    }
    work.release();
  }

  /**
   * Whether this process can execute a row at all.
   *
   * <p><b>Every runnable row is an event run</b>, since per-push CI retired on 2026-09-05 and the
   * only entry left is {@link #onEventTrigger}. A row that is not one is therefore a row this
   * process cannot execute: a {@code POST_RECEIVE} leftover from a predecessor deployment, or an
   * {@code EVENT} row whose trigger snapshot is missing. Neither is a defect to throw over and
   * neither may be silently dropped either — a {@code QUEUED} row nothing will ever run sits in
   * {@code GET /ci/api/runs/active} forever, which is exactly the phantom the retirement is about.
   * So {@link #retireUnrunnable} settles it {@code CANCELLED}, which is the truthful terminal state
   * for accepted work that will not happen, and says so out loud once.
   */
  private static boolean runnable(CiRun run) {
    return run.triggerType == CiTriggerType.EVENT && run.triggerConfig != null;
  }

  /**
   * Settles a row no engine here can run, with the one line and the one reason that say why.
   *
   * <p><b>Only a row that is still {@code QUEUED}</b>, and the status is re-read inside the writing
   * transaction — {@code startQueued}'s discipline, for {@code startQueued}'s reason. A person can
   * cancel such a row through the API between the sweep reading it and this running, and settling it
   * a second time would overwrite their reason with this one.
   *
   * @return whether this pass settled it — false for a row somebody else settled first, and for one
   *     the write could not reach at all. Either way the caller carries on to the next candidate.
   */
  private boolean retireUnrunnable(CiRun run) {
    boolean settled = settleQueued(run.id, TRIGGER_RETIRED);
    if (settled) {
      LOG.infof(
          "CI run %s (%s, %s@%s) cannot be executed by this engine — an ordinary push triggers"
              + " nothing since 2026-09-05, so it is settled CANCELLED/%s",
          run.id, run.triggerType, run.repoId, run.branch, TRIGGER_RETIRED);
    }
    return settled;
  }

  /**
   * Settles a row whose own trigger snapshot will not parse — see {@link #TRIGGER_UNREADABLE} for
   * why such a row must not be left {@code QUEUED}.
   *
   * @return whether this pass settled it.
   */
  private boolean settleUnreadable(CiRun run) {
    boolean settled = settleQueued(run.id, TRIGGER_UNREADABLE);
    if (settled) {
      LOG.warnf(
          "CI run %s (%s@%s, %s) is settled CANCELLED/%s: its trigger snapshot no longer parses, so"
              + " there is no pipeline for any worker to run",
          run.id, run.repoId, run.branch, run.configPath, TRIGGER_UNREADABLE);
    }
    return settled;
  }

  /**
   * The one write both settlements share: {@code QUEUED} becomes {@code CANCELLED} with the reason
   * given, and only while the row really is still queued.
   *
   * <p><b>A write that throws is answered {@code false} rather than propagated</b>, and that is the
   * scan's whole safety net: this runs on the claim loop, over a row the loop cannot run, and a
   * database blip while settling one poison row must not cost every row behind it its pass. The row
   * stays {@code QUEUED} and the next wake tries again.
   *
   * <p><b>The announcement is made here rather than by either caller</b>, for the reason {@link
   * #startQueued} makes the {@code RUNNING} one: this is the single write, so it is the one place
   * that cannot be forgotten by a third settlement. It is made from the row the transaction settled
   * and after that transaction has committed, and the {@code false} arm — a row that had already
   * moved, or a write that did not land — announces nothing, because nothing happened. Note the run
   * leaves the active listing here without ever announcing a verdict, which is exactly the departure
   * {@link #announceFailedRun} is contractually unable to report.
   */
  private boolean settleQueued(String runId, String reason) {
    CiRun settled;
    try {
      settled =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    CiRun row = runs.findById(runId);
                    if (row == null || row.status != CiRunStatus.QUEUED) {
                      return null;
                    }
                    row.status = CiRunStatus.CANCELLED;
                    row.finishedAt = Instant.now();
                    row.cancellationReason = reason;
                    return row;
                  });
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "CI run %s could not be settled %s — skipping it for this pass", runId, reason);
      return false;
    }
    if (settled == null) {
      return false;
    }
    announceStatus(settled, CiRunStatus.CANCELLED, CiRunStatus.QUEUED, settled.finishedAt);
    return true;
  }

  /**
   * One matched event trigger, resolved: the repository, the head the trigger was read at, the
   * pipeline that file declared, and the event that caused all of it.
   *
   * <p>The pipeline travels here <b>already parsed</b>, and that is the shape the trigger forces: an
   * event has no commit of its own, so {@code CiEventTriggerService} must resolve the head and read
   * the file to know whether there is anything to run at all — by the time it knows, it has parsed.
   * Re-reading here would be a second fetch against a branch that may have moved, and the run would
   * then record a sha it did not build. (The retired push path was the other shape: it named its own
   * commit and parsed the file on the run worker, which is why a broken config was a {@code
   * CONFIG_ERROR} row there and is a WARN with no row here.)
   */
  public record EventRun(
      CiRepoRef repo,
      String branch,
      String sha,
      CiEventTrigger trigger,
      String eventId,
      String eventName,
      Instant occurredAt,
      String payload,
      String triggerConfig) {}

  /**
   * The async entry the trigger engine calls. Like the intake's, the row is written before this
   * returns — which moves the <b>dedupe</b> to accept time along with it.
   *
   * <p>That relocation is the whole of what changed for this path, and it changed nothing about the
   * semantics: a redelivered event still hits the unique constraint on {@code (trigger_event_id,
   * repo_id, config_path)} and is still dropped as already-triggered rather than re-run, just on the
   * trigger worker rather than on the run worker, and before a queue slot is spent rather than after.
   * A duplicate frame therefore never reaches the queue at all.
   *
   * <p><b>There is a second collapse on this path, and it is not the same one.</b> The constraint
   * refuses a second run for one event; {@link #supersedeByVersion} refuses a second <em>queued</em>
   * run for one trigger file when both are tag events, keeping the newest tag of a multi-tag push
   * and no other. Distinct events, distinct rows, one build.
   *
   * @return the id of the run this recorded, or {@code null} when the dedupe dropped it. The manual
   *     trigger endpoint answers with these ids, which is what lets a 2xx there mean "these rows
   *     exist" rather than "something was handed to a queue".
   */
  public String onEventTrigger(EventRun request) {
    CiIdentifiers.requireRepo(request.repo());
    CiIdentifiers.requireBranch(request.branch());
    CiIdentifiers.requireSha(request.sha());
    CiRun run = acceptEventRun(request);
    if (run == null) {
      return null;
    }
    enqueue(run.id);
    return run.id;
  }

  /** Rebuilds an accepted event run solely from its durable row. */
  private EventRun reconstructEventRun(CiRun run) {
    CiEventTrigger trigger = triggerParser.parse(run.configPath, run.triggerConfig);
    return new EventRun(
        repoOf(run),
        run.branch,
        run.commitSha,
        trigger,
        // The event, not the row's trigger identity: on a retry those differ, and what a step
        // container's $QITS_EVENT_ID must name is the domain event whose payload sits beside it in
        // $QITS_EVENT_PAYLOAD. A synthetic retry token there would be an id nothing can resolve.
        causingEventId(run),
        run.triggerEventName,
        run.triggerEventOccurredAt,
        run.triggerEventPayload,
        run.triggerConfig);
  }

  /**
   * Accept and run in one call — package-private so tests drive the whole state machine without the
   * worker's timing, which is what {@link #onEventTrigger} plus the queue hop otherwise costs them.
   *
   * <p>It is the only synchronous entry left. There was a second, {@code execute(repoId, branch,
   * sha)}, which accepted and ran a push; it went with the push arm on 2026-09-05 and the suites that
   * used it as a cheap way into {@link #runSteps} drive an event run through here instead.
   */
  void executeEventRun(EventRun request) {
    CiRun run = acceptEventRun(request);
    if (run == null) {
      return;
    }
    runQueuedEventRun(run.id, request);
  }

  /**
   * The worker half of an event-triggered run: claim the queued row, then run the pipeline that
   * reconstructed from the durable row.
   *
   * <p>No config lookup: {@code CiEventTriggerService} had to read and parse the trigger file to know
   * there was anything to run at all, so the pipeline arrives parsed. The row stores that file's
   * exact content so a restart parses the same declaration again.
   */
  private void runQueuedEventRun(String runId, EventRun request) {
    CiRun run = startQueued(runId);
    if (run == null) {
      cancelled.remove(runId);
      return;
    }
    executeClaimed(run, request);
  }

  /**
   * The half of {@link #runQueuedEventRun} that runs a run this thread has <b>already claimed</b>.
   *
   * <p>It is split out for the claim loop, which does its own {@link #startQueued} — it has to, since
   * "could this row be claimed" is how it decides whether to move on to the next candidate. What is
   * left above is the synchronous entry {@link #executeEventRun} uses, unchanged, so no test's path
   * through this class moved.
   *
   * <p><b>Everything a claimed run does is inside the try, and the daemon pin is why that had to be
   * said out loud.</b> {@code pinDaemon()} and {@link #pinDaemonVersion} used to sit above it —
   * two statements between a row that {@link #startQueued} has already flipped {@code RUNNING} and
   * the handler that settles it. Neither is free: the first resolves a pin ladder whose {@code
   * answer()} is deliberately not {@code DbRetry}-wrapped, the second is a write. A throw out of
   * either left a row {@code RUNNING} with no steps, no {@code finishedAt} and no owner, settleable
   * by nothing short of the next process's boot sweep — the 2026-08-23 shape of failure, arrived at
   * from the other direction.
   *
   * <p><b>An {@code Error} settles the row too, and is then rethrown.</b> The two facts are
   * independent: the run is over either way and its row must say so, while the loop above is what
   * decides what to do about a JVM that has just thrown an {@code Error} — logging it, parking, and
   * (if the loop itself dies of it) being replaced.
   */
  private void executeClaimed(CiRun run, EventRun request) {
    try {
      // Resolved once, here: every step container of this run downloads the same daemon build.
      DaemonPin pin = runner.pinDaemon();
      pinDaemonVersion(run.id, pin.version());
      run.daemonVersion = pin.version();
      runSteps(run, request.trigger().pipeline(), pin, eventEnv(request), declaredRelease(request));
    } catch (RuntimeException e) {
      settleUnexpectedly(run, e);
    } catch (Error fatal) {
      settleUnexpectedly(run, fatal);
      throw fatal;
    } finally {
      cancelled.remove(run.id);
      runner.runClosed(run.id);
    }
  }

  /**
   * Writes the terminal row for a claimed run that blew up, and announces it if that was a failure
   * rather than a cancellation. {@link #executeClaimed}'s recovery, split out because it now serves
   * two catches.
   */
  private void settleUnexpectedly(CiRun run, Throwable cause) {
    LOG.errorf(cause, "CI run %s failed unexpectedly", run.id);
    QuarkusTransaction.requiringNew().run(() -> failIncompleteSteps(run.id));
    CiRunStatus outcome = cancelled.contains(run.id) ? CiRunStatus.CANCELLED : CiRunStatus.FAILED;
    Instant finishedAt = finishRun(run.id, outcome);
    // Unconditional, unlike the verdict below: a run that blew up has left the active listing on
    // both arms, and the cancelled one is exactly the arm a mirror would otherwise never hear about.
    announceStatus(run, outcome, CiRunStatus.RUNNING, finishedAt);
    if (outcome == CiRunStatus.FAILED) {
      announceFailedRun(run, finishedAt, outcome);
    }
  }

  /**
   * What an event-triggered run's step containers see, and the whole of it.
   *
   * <p>The payload goes in <b>verbatim</b>, as the canonical JSON qits-events stored — no per-field
   * flattening. Env names derived from payload paths invite collisions and quoting bugs, and {@code
   * jq} is already the platform's answer inside a step; a step that wants one field asks for it.
   *
   * <p><b>{@code QITS_VERSION} is the one exception, and it is a measured one.</b> Thirty files in
   * the estate re-parse the released version out of {@code $QITS_EVENT_PAYLOAD}, in three mutually
   * inconsistent grammars, to do the one thing every release pipeline has to do. That parse is
   * {@link #releaseVersionOf} — the method the release join already depends on to be the single
   * place the two spellings ({@code SCMRelease}'s {@code version}, {@code SCMPublishTag}'s {@code
   * tagName}) are chosen between — so seeding it here makes the platform's own answer the one every
   * step reads, and does it for legacy recipes and composed ones alike.
   *
   * <p><b>Empty, never absent</b>, the convention every other injected value here follows: an event
   * that carries no version (which is most of them) leaves the variable set and blank, so a recipe
   * reads one shape and a {@code set -u} guard fires where the recipe put it rather than at an
   * unrelated line.
   */
  private static Map<String, String> eventEnv(EventRun request) {
    Map<String, String> env = new TreeMap<>();
    env.put("QITS_EVENT_ID", request.eventId());
    env.put("QITS_EVENT_NAME", request.eventName());
    env.put(
        "QITS_EVENT_OCCURRED_AT",
        request.occurredAt() == null ? "" : request.occurredAt().toString());
    env.put("QITS_EVENT_PAYLOAD", request.payload() == null ? "" : request.payload());
    String version = releaseVersionOf(request.eventName(), request.payload());
    env.put("QITS_VERSION", version == null ? "" : version);
    return Map.copyOf(env);
  }

  /**
   * What a green run of this trigger file will announce beyond {@code BuildSuccessful}: the
   * artifacts it declared, and the payload the version is read out of. Null when the file declared
   * none, which is every ordinary event pipeline.
   *
   * <p>The payload is reconstructed from the run row and travels through to {@link #runSteps}.
   * Reading it here rather than at accept time remains deliberate: a red run must announce nothing
   * and warn about nothing.
   */
  private static DeclaredRelease declaredRelease(EventRun request) {
    List<CiArtifact> artifacts = request.trigger().artifacts();
    return artifacts.isEmpty() ? null : new DeclaredRelease(artifacts, request.payload());
  }

  /** The artifacts a run's trigger file declared, and the event payload they take their version from. */
  private record DeclaredRelease(List<CiArtifact> artifacts, String eventPayload) {}

  /**
   * Where a declared step's image is really pulled from — see {@link CiStepImage}.
   *
   * <p><b>The resolved reference is what gets RECORDED as well as what gets started</b>, and the two
   * being one value is the point: a launch that fails on the image says which reference it could not
   * pull, rather than showing the recipe's shorthand and leaving the registry to be inferred.
   */
  private String stepImage(CiPipeline.CiStepDecl decl) {
    return resolvePlatformStepImages
        ? CiStepImage.resolve(decl.image(), artifactsRegistryHost, artifactsImageRepository)
        : decl.image();
  }

  /**
   * The sequential loop. Each iteration blocks on one container's whole lifetime and then writes
   * exactly one terminal row; whatever the loop did not reach is written {@code SKIPPED} at the end.
   *
   * <p><b>{@code SKIPPED} means one thing again.</b> A step could also be skipped because the run's
   * branch did not bind its {@code branches:} filter — written before any container existed, with a
   * bracketed note in the output to keep the two kinds of skip apart. That key was a pipeline
   * feature of {@code ci-post-receive.yml}, always a parse error in a trigger file, and it went with
   * per-push CI on 2026-09-05; the only skip left is the one this loop's remainder writes, so a
   * {@code SKIPPED} row is "an earlier step failed" and its null output says so.
   *
   * <h2>Which half the run died in decides what the verdict is worth</h2>
   *
   * <p>A step may declare {@code gating: false} ({@code CiPipeline.CiStepDecl}), and the run's
   * announced {@code gating} is <b>the file's flag ANDed with the failing step's</b>. That is the
   * whole mechanism behind putting a repository's gating build and its non-gating userflow publish
   * in ONE file: ordering does the rest, because the gating half runs first and has published
   * whatever it publishes before a non-gating step can fail. "A red verify must not cost the image"
   * therefore survives the merge of the two files it used to require.
   *
   * <p>Nothing else about failure moved. A non-gating step that fails <b>still stops the run</b> and
   * still leaves it {@code FAILED}, so a person sees the red exactly as before; what changes is only
   * what a release gate reads off the event.
   */
  private void runSteps(
      CiRun run,
      CiPipeline pipeline,
      DaemonPin pin,
      Map<String, String> env,
      DeclaredRelease release) {
    List<CiPipeline.CiStepDecl> declared = pipeline.steps();
    int index = 0;
    boolean failed = false;
    boolean timedOut = false;
    // Which half the run died in, and it is a Boolean because "no step failed" is a third answer:
    // a green run's verdict is worth what the FILE says and there is no step to ask.
    Boolean failedStepGating = null;

    try {
      while (index < declared.size() && !failed && !cancelled.contains(run.id)) {
        CiPipeline.CiStepDecl decl = declared.get(index);
        Stamps stamps = new Stamps();
        StepResult result =
            runner.run(
                new CiStepRunner.StepSpec(
                    run.id,
                    index,
                    repoOf(run),
                    run.branch,
                    run.commitSha,
                    stepImage(decl),
                    decl.script(),
                    pin.binaryUrl(),
                    decl.timeoutSeconds() == null ? stepTimeoutSeconds : decl.timeoutSeconds(),
                    decl.docker(),
                    decl.build(),
                    decl.user(),
                    env),
                stamps);

        // A cancellation completes the await NORMALLY — the daemon answers a Cancel with a terminal
        // frame — so cancelledness is read from the flag rather than inferred from how run() came
        // back.
        boolean wasCancelled = cancelled.contains(run.id);

        // The daemon's checkout could not find the run's sha. Two very different causes, so ask the
        // git host which it was rather than guessing: the commit may have been force-pushed away
        // since the run was accepted (this run describes work on a commit that no longer exists ⇒
        // discard it), or the repository may still hold it and something else went wrong with the
        // clone (a real, user-visible failure that must stay on the record). The daemon's structured
        // outcome is the probe; commitHeld is the confirmation.
        //
        // UNKNOWN is deliberately NOT read as gone: a git host that could not be asked has said
        // nothing about the commit, and discarding over that would erase a verdict on evidence
        // nobody has.
        if (result.outcome() == StepOutcome.SHA_GONE && !wasCancelled) {
          boolean commitGone =
              configSource.commitHeld(repoOf(run), run.commitSha) == CommitHeld.GONE;
          if (commitGone) {
            LOG.infof(
                "CI run %s: %s is no longer reachable — discarding the run", run.id, run.commitSha);
            discardRun(run.id);
            return;
          }
          LOG.infof(
              "CI run %s: step %d could not check out %s though the commit is still reachable: %s",
              run.id, index, run.commitSha, firstLine(result.output()));
        }

        // A deadline is not a verdict, so it is recorded apart from FAILED — on the step and, below,
        // on the run. It still stops the loop exactly as a failure does: the remaining steps are
        // SKIPPED. Cancellation wins over it, because a cancelled step's deadline is beside the
        // point.
        boolean stepTimedOut = !wasCancelled && result.timedOut();
        boolean ok =
            !wasCancelled
                && !result.timedOut()
                && result.outcome() == StepOutcome.OK
                && result.exitCode() == 0;
        insertStep(
            run.id,
            index,
            stepImage(decl),
            ok
                ? CiStepStatus.SUCCESS
                : stepTimedOut ? CiStepStatus.TIMED_OUT : CiStepStatus.FAILED,
            result.exitCode(),
            annotate(result, wasCancelled),
            stamps.startedAt(),
            stamps.finishedAt());
        failed = !ok;
        if (failed) {
          failedStepGating = decl.gating();
        }
        timedOut = stepTimedOut;
        index++;
      }
    } catch (RuntimeException e) {
      // The step blew up instead of answering — an infrastructure error, not a pipeline verdict.
      // Record it against the step it happened on so no declared step vanishes from the run.
      LOG.errorf(e, "CI run %s: step %d failed unexpectedly", run.id, index);
      if (index < declared.size()) {
        // An infrastructure failure OF a non-gating step is still the non-gating half's: the run
        // died where the declaration says a death costs no gate.
        failedStepGating = declared.get(index).gating();
        Instant now = Instant.now();
        insertStep(
            run.id,
            index,
            stepImage(declared.get(index)),
            CiStepStatus.FAILED,
            null,
            "[the step could not be executed: " + e + "]",
            now,
            now);
        index++;
      }
      failed = true;
      timedOut = false;
    }

    for (int skipped = index; skipped < declared.size(); skipped++) {
      insertStep(
          run.id,
          skipped,
          stepImage(declared.get(skipped)),
          CiStepStatus.SKIPPED,
          null,
          null,
          null,
          null);
    }
    boolean wasCancelled = cancelled.contains(run.id);
    boolean red = failed || wasCancelled;
    CiRunStatus outcome =
        wasCancelled
            ? CiRunStatus.CANCELLED
            : timedOut
                ? CiRunStatus.TIMED_OUT
                : red ? CiRunStatus.FAILED : CiRunStatus.SUCCESS;
    // The verdict's own worth: the FILE's flag ANDed with the failing step's. A non-gating file
    // cannot be made gating by a step, and a gating file's non-gating half produces a red that no
    // release gate holds a commit for. It is written to the row as well as announced, so the two can
    // never disagree — and the detached copy is updated because the announcers read it off there.
    boolean verdictGating = run.gating && (failedStepGating == null || failedStepGating);
    Instant finishedAt = finishRun(run.id, outcome, verdictGating);
    run.gating = verdictGating;
    // First, and unconditionally: the run has left the active listing whichever way it ended, and
    // that is true of the CANCELLED outcome the two verdict announcements below deliberately skip.
    announceStatus(run, outcome, CiRunStatus.RUNNING, finishedAt);
    if (outcome == CiRunStatus.SUCCESS) {
      announceRun(run, finishedAt);
      announceRelease(run, finishedAt, release);
    } else if (outcome != CiRunStatus.CANCELLED) {
      announceFailedRun(run, finishedAt, outcome);
    }
  }

  /**
   * Announces a green run through the {@link RunAnnouncer} port — after the terminal row is
   * committed, so a consumer that reads the run back sees {@code SUCCESS}, and carrying the {@code
   * finishedAt} that was just written rather than a fresh {@code Instant.now()}: the two are minutes
   * apart in a slow transition and the event log wants the one on the row.
   *
   * <p><b>This is the only announcement a green run makes about itself</b>, and it used to be one of
   * two. The other was a direct POST to qits-platform-deployments' intake, sent from here on every
   * green run; the deployer subscribes off the bus durably instead, so a deploy follows from an
   * event rather than from a second call of this service's. It follows {@code SoftwareRelease} and
   * no longer this announcement — a green build is not a reason to put anything live — and what
   * reads this one is qits-projects' release-request gate. The deployer's intake is still there,
   * at {@code /events/software-released}, and is still the manual door a replay knocks on; what
   * went is qits-ci knocking on any of it.
   *
   * <p>{@code finishedAt} comes back from {@link #finishRun} instead of being read off {@code run}
   * because it is not there — {@link #finishRun} mutates a freshly loaded entity in its own
   * transaction, so this detached instance never sees the value. Reading it back would be a second
   * query for something this method already knows.
   *
   * <p>Failures are the port's, not the run's: a green run stays green whatever an announcement
   * does.
   *
   * <p><b>{@code triggerEventId} rides along, and it is how causation crosses a thread.</b> On an
   * event-triggered run it is the event that caused the run; the announcer hands it to the bus as the
   * published event's parent, so the run's own {@code BuildSuccessful} names what caused it and a
   * release train is a chain in the event log. It comes off the row rather than out of an ambient
   * context because there is none to read here: the engine consumed the frame on the bus's dispatch
   * thread and this is {@code ci-run-worker}, minutes later. Null only on a historical push row,
   * which publishes a root — correctly, since a push was not caused by an event.
   */
  private void announceRun(CiRun run, Instant finishedAt) {
    for (RunAnnouncer announcer : runAnnouncers) {
      try {
        announcer.onRunSucceeded(
            run.id,
            run.repoId,
            run.projectId,
            run.repoName,
            run.branch,
            run.commitSha,
            run.gating,
            finishedAt,
            causingEventId(run));
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing run %s failed", run.id);
      }
    }
  }

  /**
   * The event that caused a run, as the announcers' parent — {@link CiRun#triggerEventId} for
   * everything a trigger produced, and the retried run's inherited cause for a manual re-fire.
   *
   * <p>The two columns normally agree, and on a retry they deliberately do not: the trigger identity
   * is a synthetic local token (see {@link #RETRY_TRIGGER_PREFIX}) so the dedupe constraint can stay
   * exactly as it is, while {@code causationId} still carries the domain event the original run was
   * fired by. Reading the synthetic value here would cost every retry its causation edge and log a
   * warning about a value this class minted on purpose — so the re-fired run's {@code
   * BuildSuccessful} hangs off the same event as the original's, which is what makes a retried
   * release request one chain in the log rather than an unexplained root.
   */
  private static String causingEventId(CiRun run) {
    if (run.retryOfRunId == null) {
      return run.triggerEventId;
    }
    return run.causationId == null ? null : run.causationId.toString();
  }

  /**
   * {@link #announceRun}'s failure twin, called at every terminal write that says something true
   * about a commit: red and timed-out runs, config errors, a crash the worker caught, and the runs
   * a boot sweep found interrupted. What it is <b>not</b> called for is the contract's other half —
   * a cancelled run (a person withdrew the question) and a deduped-superseded row (bookkeeping
   * about the queue, not a fact about the commit) announce nothing, which is what lets a subscriber
   * keeping per-commit build status read every {@code BuildFailed} as a verdict.
   *
   * <p>Everything else — announce after the terminal row commits, the row's own {@code finishedAt},
   * the causation id off the row, failures being the port's and never the run's — is {@link
   * #announceRun}'s reasoning, unchanged.
   */
  private void announceFailedRun(CiRun run, Instant finishedAt, CiRunStatus outcome) {
    for (RunAnnouncer announcer : runAnnouncers) {
      try {
        announcer.onRunFailed(
            run.id,
            run.repoId,
            run.projectId,
            run.repoName,
            run.branch,
            run.commitSha,
            run.gating,
            outcome.name(),
            finishedAt,
            causingEventId(run));
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing run %s failed", run.id);
      }
    }
  }

  /**
   * Announces that a run's row moved from one status to the next, through {@link
   * RunAnnouncer#onRunStatusChanged} — the third announcement this class makes, and the only one
   * that fires more than once per run.
   *
   * <p><b>It is called at every transition, and "every" is the contract rather than an ambition.</b>
   * {@link #announceRun} and {@link #announceFailedRun} are selective on purpose — they say
   * something about a commit, so a cancelled or superseded run says nothing at all — and a mirror of
   * {@code GET /ci/api/runs/active} needs precisely what that leaves out. A listing is two edges: a
   * run enters it (accepted {@code QUEUED}, or handed back by a boot sweep) and a run leaves it (any
   * terminal status, cancellations and supersedes included). Announce only the flattering half and a
   * mirror holds a cancelled run forever, which is the failure this method exists to make
   * impossible. So every writer of {@code ci_run.status} in this class calls it, and a new one that
   * does not is a bug in the new writer.
   *
   * <p><b>{@code status} is passed rather than read off {@code run}</b>, and that is not
   * belt-and-braces. The writes go through {@link #finishRun} and friends, which mutate a
   * <em>freshly loaded</em> entity inside their own transaction; the instance the caller is holding
   * is detached and still says whatever it said before. Reading the status off it would announce the
   * old state at half these call sites, silently.
   *
   * <p>{@code occurredAt} is the column the new status is stamped by — {@code createdAt} for {@code
   * QUEUED}, {@code startedAt} for {@code RUNNING}, {@code finishedAt} for a terminal one — and comes
   * from the transaction that wrote it, so the row and the event cannot disagree about when the run
   * moved. {@code previous} is null for a run's first announcement, which is what says it entered the
   * listing rather than moved within it.
   *
   * <p>Everything else is {@link #announceRun}'s, unchanged: called after the write commits so a
   * consumer reading the run back sees the status it was just told about, the causation id off the
   * row, and failures being the port's rather than the run's — an announcement that throws must not
   * cost a run its next transition.
   */
  private void announceStatus(
      CiRun run, CiRunStatus status, CiRunStatus previous, Instant occurredAt) {
    for (RunAnnouncer announcer : runAnnouncers) {
      try {
        announcer.onRunStatusChanged(
            run.id,
            run.repoId,
            run.projectId,
            run.repoName,
            run.branch,
            run.commitSha,
            run.gating,
            status.name(),
            previous == null ? null : previous.name(),
            occurredAt,
            causingEventId(run));
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing run %s as %s failed", run.id, status);
      }
    }
  }

  /**
   * Hands what a green <b>release pipeline</b> published to {@link ReleaseJoin} — after {@link
   * #announceRun}, because "this build passed" is the more general statement of the two.
   *
   * <p><b>It is additional and never a replacement.</b> Every green run still announces itself
   * exactly as before; a declaration adds N events, it removes none. A run with no declaration —
   * every ordinary event pipeline — reaches this method with a null and does nothing.
   *
   * <p><b>Whether the platform hears about it is not decided here.</b> A green run says what it
   * published; {@link ReleaseJoin} says whether that was a release or a bootstrap replay restoring a
   * tag. This method's job ends at the key, and the key is where the two halves have to agree — see
   * {@link #releaseVersionOf}.
   *
   * <p><b>A declaration whose trigger carries no version publishes nothing, loudly.</b> The version
   * is not qits-ci's to invent: it belongs to the release the pipeline built, and the only place it
   * exists is the payload of the event that triggered the run. So a file declaring artifacts against
   * an event that carries neither a {@code version} nor a tag name is a file written for a trigger
   * that cannot feed it, and the honest answer is a WARN naming the run and the event rather than an
   * announcement with a guessed or blank version — which downstream would install.
   */
  private void announceRelease(CiRun run, Instant finishedAt, DeclaredRelease release) {
    if (release == null) {
      return;
    }
    String version = releaseVersionOf(run.triggerEventName, release.eventPayload());
    if (version == null) {
      LOG.warnf(
          "Run %s: %s declares %d artifact(s), but the %s event that triggered it carries neither"
              + " '%s' nor '%s' — nothing published",
          run.id,
          run.configPath,
          release.artifacts().size(),
          run.triggerEventName,
          VERSION_FIELD,
          TAG_NAME_FIELD);
      return;
    }
    releaseJoin.onGreenReleaseRun(
        new ReleaseJoin.Published(
            run.id,
            run.repoId,
            run.repoName,
            run.projectId,
            version,
            run.triggerEventName,
            causingEventId(run),
            finishedAt,
            release.artifacts()));
  }

  /**
   * The version a green release pipeline published under, taken from the event that triggered it, or
   * null when there is none to read.
   *
   * <p><b>Two events feed it and they spell it differently, which is why this is one method rather
   * than one field name.</b> An {@code SCMRelease} carries {@code version}. An {@code
   * SCMPublishTag} carries {@code tagName}, and that IS the version string: a release stamp is the
   * name of the tag the release push created, so a tag-triggered release pipeline and the {@code
   * SCMRelease} for the same release land on the same key. Both spellings had to be readable here
   * for {@link ReleaseJoin} to have anything to join — bootstrap-replay-plan.md's WP1 is what moves
   * the release recipes onto the tag event.
   *
   * <p>The payload is walked rather than bound, which is the trigger engine's rule and the same one
   * that keeps this path free of native-image reflection metadata. A non-string value is read as its
   * JSON literal exactly as a selection would read it, so a version that arrives as a number is
   * announced as the digits it was written with rather than refused.
   */
  private static String releaseVersionOf(String eventName, String payload) {
    return TAG_EVENT_NAME.equals(eventName) ? tagOf(payload) : versionOf(payload);
  }

  /** The triggering event's {@code version}, or null when there is none to read. */
  private static String versionOf(String payload) {
    JsonNode version =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(payload), VERSION_FIELD);
    if (version == null) {
      return null;
    }
    String text = CiEventSelectionEvaluator.asString(version);
    return text.isBlank() ? null : text;
  }

  /**
   * The step's own tail plus one bracketed line naming anything that is not "the script ran and
   * exited". The runner's output is already bounded to {@code outputMaxChars} as it arrived; {@link
   * #tail} stays over it as the guard that keeps that a property of this class rather than a promise
   * made elsewhere.
   */
  private String annotate(StepResult result, boolean wasCancelled) {
    String output = tail(result.output(), outputMaxChars);
    String note = note(result, wasCancelled);
    if (note == null) {
      return output;
    }
    return (output == null || output.isEmpty() ? "" : output + "\n") + note;
  }

  private static String note(StepResult result, boolean wasCancelled) {
    if (wasCancelled) {
      return "[step cancelled]";
    }
    if (result.timedOut()) {
      return "[step timed out]";
    }
    return switch (result.outcome()) {
      case OK -> null;
      case SHA_GONE -> "[the step container could not check out this commit]";
      case INIT_FAILED -> "[the step container could not prepare its workspace]";
      case NEVER_INITIALIZED -> "[the step container never reported its checkout done]";
      case LAUNCH_FAILED -> "[the step container could not be started]";
      case NEVER_STARTED -> "[the step container never started its ci daemon]";
      case CONNECTION_LOST -> "[the connection to the step container was lost]";
    };
  }

  /**
   * Claims a queued run for this worker: {@code QUEUED} becomes {@code RUNNING} and the row comes
   * back, or <b>null when the row is no longer queued</b> and there is nothing to run.
   *
   * <p>The null case is a cancellation that reached the row first, and reading the status inside the
   * claiming transaction is what makes "the worker must never pick up a run that was cancelled while
   * it waited" a property of the database rather than of the order two threads happened to run in.
   *
   * <p>It is also <b>null while this process is {@link #draining}</b>, and then the row is left
   * {@code QUEUED} rather than finished: a shutting-down process must hand its backlog on, not claim
   * it.
   *
   * <p>Flipping here rather than at accept is what keeps {@code QUEUED} honest: the config read
   * below is an HTTP read against a host that can take seconds, and a run doing that has
   * started. It also fixes what a crash during it costs — a {@code RUNNING} row, swept to {@code
   * FAILED}, which is the truthful answer to "did this run begin".
   *
   * <p><b>The {@code RUNNING} announcement is made here rather than by the callers</b>, and there are
   * two of them — the claim loop and the synchronous entry — which is exactly the reason. This is the
   * one place {@code QUEUED} becomes {@code RUNNING}, so it is the one place that can say so without
   * a second caller ever being able to forget; and the two ways it answers null (cancelled first,
   * draining) announce nothing, correctly, because neither wrote anything. See {@link
   * #announceStatus} for what a mirror of the active listing does with it. The announcement is after
   * the claiming transaction has committed, so a consumer that reads the run back sees {@code
   * RUNNING}.
   */
  private CiRun startQueued(String runId) {
    CiRun claimed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  CiRun run = runs.findById(runId);
                  if (run == null || run.status != CiRunStatus.QUEUED) {
                    return null;
                  }
                  // Read as late as it can be — after the row, immediately before the flip, inside
                  // the claiming transaction. A dying process leaves the row QUEUED for the
                  // successor's boot sweep rather than claiming it and dying holding it RUNNING.
                  if (draining) {
                    logLeftQueuedWhileDraining(runId);
                    return null;
                  }
                  run.status = CiRunStatus.RUNNING;
                  run.startedAt = Instant.now();
                  return run;
                });
    if (claimed != null) {
      announceStatus(claimed, CiRunStatus.RUNNING, CiRunStatus.QUEUED, claimed.startedAt);
    }
    return claimed;
  }

  /** Writes the daemon build this run pinned, once, when the first container is about to launch. */
  private void pinDaemonVersion(String runId, String daemonVersion) {
    QuarkusTransaction.requiringNew()
        .run(() -> runs.findById(runId).daemonVersion = daemonVersion);
  }

  /**
   * Records an accepted event trigger as a {@code QUEUED} run, or <b>null when this (event,
   * repository, trigger file) already has one</b>.
   *
   * <p>Both halves of that are here on purpose. The {@link CiRunRepository#alreadyTriggered} query is
   * the cheap one and catches the ordinary case — a redelivery, which the bus is allowed to do and
   * which a future catch-up feature will do deliberately. The caught constraint violation is the one
   * that matters: it is the guarantee, it holds across a race and a restart in a way no read-then-write
   * can, and reaching it is not an error to report but the answer to a question. Anything that is
   * <em>not</em> a unique violation is rethrown, because a run that failed to insert for some other
   * reason is a defect and must not look like a duplicate.
   *
   * <p>Both run in <b>one</b> {@code requiringNew} bracket, which they have to for two reasons: the
   * caller is the trigger worker and a worker thread has no request context, so an unwrapped read
   * has no session at all; and a check in its own transaction would be answering about a moment that
   * has already passed by the time the insert happens.
   */
  /** The event id as a cause, or null for one that is not a UUID — never a reason to drop a run. */
  private static UUID parseCause(String eventId) {
    if (eventId == null) {
      return null;
    }
    try {
      return UUID.fromString(eventId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * What one accept transaction did: the run it inserted, and the queued runs that transaction
   * superseded on the way.
   *
   * <p><b>The losers travel out of the transaction because the announcement cannot happen inside
   * it.</b> {@link #dedupe} settles them {@code CANCELLED} beside the insert, so they leave the
   * active listing at the accept — and a mirror is owed that departure exactly as it is owed the
   * arrival. Collecting them here is what lets {@link #announceAccepted} say so after the commit,
   * with no second query for rows this transaction already had in hand.
   *
   * <p>Built fresh inside {@link #insertEventRun} on every attempt, which it has to be: {@link
   * DbRetry#inNewTx} re-runs the whole body, and a list surviving a rolled-back attempt would
   * announce a cancellation the database never took.
   */
  private record Accepted(CiRun run, List<CiRun> superseded) {}

  private CiRun acceptEventRun(EventRun request) {
    String configPath = request.trigger().configPath();
    // Computed BEFORE the insert bracket rather than inside it, and that placement is the whole of
    // how "the prediction never costs a run" is made true. It is a read against ci_step, so a broken
    // one throws a database exception — inside the insert's transaction that would poison the
    // session and roll the accepted run back, however carefully it was caught.
    String expected =
        predictedStepDurations(
            request.repo().repoId(), configPath, request.triggerConfig());
    Accepted accepted;
    try {
      accepted =
          DbRetry.inNewTx(
              "event run accept",
              () -> insertEventRun(request, configPath, expected),
              retryDeadline());
    } catch (RuntimeException e) {
      if (!isUniqueViolation(e)) {
        throw e;
      }
      LOG.infof(
          "Event %s reached %s in %s twice — the first run stands",
          request.eventId(), configPath, request.repo().display());
      return null;
    }
    if (accepted == null) {
      LOG.debugf(
          "Event %s already triggered %s in %s — no second run",
          request.eventId(), configPath, request.repo().display());
      return null;
    }
    announceAccepted(accepted);
    return accepted.run();
  }

  /**
   * Announces what the accept transaction did, after it has committed — the accepted run's own
   * status first, then every queued run it displaced.
   *
   * <p><b>The accepted run is announced with the status its row REALLY holds, not with {@code
   * QUEUED}.</b> That distinction is the whole of this method, and it is what keeps a superseded
   * arrival from being announced twice or wrongly. {@link #supersedeByVersion} can settle the run
   * being accepted — an out-of-order tag burst, where the row is inserted and beaten inside one
   * transaction — so it commits {@code CANCELLED} having never been {@code QUEUED} in any state any
   * reader could observe. Announcing {@code QUEUED} here on the strength of "we just accepted it"
   * would put a run into a mirror's listing that was over before the transaction ended, and nothing
   * afterwards would ever take it out again: no worker claims it, no terminal write happens, its row
   * is already final. The status is carried out of the transaction on the entity rather than re-read,
   * because the transaction that decided it is the one that has the answer.
   *
   * <p>{@code previousStatus} is null for both halves' own first announcement and {@code QUEUED} for
   * a displaced run, which really did sit in the listing before this accept took it out. {@code
   * occurredAt} is the row's own column either way: {@code createdAt} for a run that is queued,
   * {@code finishedAt} — the winner's acceptance instant, which is what {@link #dedupe} stamps — for
   * one that is settled.
   */
  private void announceAccepted(Accepted accepted) {
    CiRun run = accepted.run();
    announceStatus(
        run,
        run.status,
        null,
        run.status == CiRunStatus.QUEUED ? run.createdAt : run.finishedAt);
    for (CiRun loser : accepted.superseded()) {
      announceStatus(loser, loser.status, CiRunStatus.QUEUED, loser.finishedAt);
    }
  }

  /**
   * The whole bracket as one database-only unit: the check, the insert and the supersede, returning
   * the accepted run or null for one this event already triggered.
   *
   * <p><b>The row is built HERE rather than by the caller</b>, and that is what makes a second
   * attempt correct. A rolled-back attempt leaves its entity carrying whatever {@link
   * #supersedeByVersion} wrote on it — a run that lost to a queued tag is marked {@code FAILED} in
   * memory — so re-persisting that same instance against a database that has since changed would
   * commit a decision the second attempt never made. A fresh instance per attempt has no memory of
   * one.
   *
   * <p>Nothing in here is anything but a database statement, because {@link DbRetry#inNewTx} re-runs
   * the whole body. The trailing {@code flush} is the second half of that contract: it moves the
   * supersede's updates into the statement phase, where a lost connection is a certain no-commit.
   *
   * <p><b>The losers list is created here, per attempt</b>, and handed down to both supersedes to
   * fill. Same reasoning as the fresh entity above pointed at the announcement instead of at the
   * insert: a list carried in from outside would survive a rolled-back attempt and have this method
   * report a cancellation the database never took. See {@link Accepted}.
   */
  private Accepted insertEventRun(EventRun request, String configPath, String expectedStepDurations) {
    if (runs.alreadyTriggered(request.eventId(), request.repo().repoId(), configPath)) {
      return null;
    }
    List<CiRun> superseded = new ArrayList<>();
    CiRun run = newRun(request.repo(), request.branch(), request.sha());
    run.triggerType = CiTriggerType.EVENT;
    run.configPath = configPath;
    run.triggerEventId = request.eventId();
    // The generic causation column gets the same value EXPLICITLY, because the ambient scope died
    // at the queue hop behind this call — CausationScope does not follow work, so the CausationStamp
    // listener would read null here and record a decision nobody made. An author-set value is what
    // the stamp yields to. Defensive parse: an id that is not a UUID costs the row its causation
    // edge and nothing else, the same trade CausingEvent.parentOf makes on the announce side.
    run.causationId = parseCause(request.eventId());
    run.triggerEventName = request.eventName();
    run.triggerEventOccurredAt = request.occurredAt();
    run.triggerEventPayload = request.payload();
    run.triggerConfig = request.triggerConfig();
    run.gating = request.trigger().gating();
    run.releaseRequestId = releaseRequestOf(request);
    // The two ordering inputs, read here and nowhere else: what this run is worth, and what waits on
    // it. Accept time is the only moment they can be read — the payload is on the row afterwards,
    // but a queue that had to parse every candidate's payload on every scan would be paying the
    // ordering's cost per pass instead of per run.
    run.priority = priorityOf(request);
    run.downstreamRepos = downstreamReposOf(request);
    // Handed in already computed, and null far more often than not — see predictedStepDurations.
    run.expectedStepDurations = expectedStepDurations;
    runs.persist(run);
    runs.flush();
    supersedeByVersion(run, request, superseded);
    supersedeByCheckoutBranch(run, request, superseded);
    runs.flush();
    return new Accepted(run, superseded);
  }

  /**
   * <b>A burst of events naming one branch is one build, of the newest tip.</b> The checkout
   * feature's collapse: a trigger following the event's own branch gets one run per event, so N
   * re-folds of a release request in a burst would queue N builds behind the single run worker where
   * only the newest matters.
   *
   * <p>It is modelled on the per-branch supersede the push intake used to do at accept, which
   * retired with that intake — the rules below are that one's, kept because the shape of the problem
   * did not go anywhere: what pushed a burst then is a re-fold now.
   *
   * <p><b>The {@code checkout != null} gate is correctness, not tidiness.</b> Without {@code
   * checkout:} every event run's branch is {@code main} by convention, so a branch-keyed collapse
   * would dedupe runs of <em>distinct events</em> that merely share the convention — which the
   * {@code (trigger_event_id, …)} contract forbids.
   *
   * <p>Accepted always wins, {@code QUEUED} rows only, convergence not minimality, and the
   * out-of-order-redelivery exposure that comes with all three. Called inside {@link
   * #acceptEventRun}'s transaction, after the flush, beside
   * {@link #supersedeByVersion} (the two cannot both fire: one is gated on {@code SCMPublishTag},
   * whose payload names no branch and therefore parses into no checkout).
   */
  private void supersedeByCheckoutBranch(
      CiRun accepted, EventRun request, List<CiRun> superseded) {
    if (request.trigger().checkout() == null) {
      return;
    }
    for (CiRun queued :
        runs.listQueuedEventRunsOnBranch(
            accepted.repoId,
            accepted.configPath,
            request.eventName(),
            accepted.branch,
            accepted.id)) {
      LOG.infof(
          "Run %s at %s@%s supersedes queued %s — a burst builds the newest tip only",
          accepted.id, accepted.repoId, accepted.branch, queued.id);
      dedupe(queued, accepted);
      superseded.add(queued);
    }
  }

  /**
   * <b>One push carrying many tags is one build, of the newest tag.</b> Every accepted tag-triggered
   * run collapses against the other queued runs of the same trigger file: the lower tag by {@link
   * VersionSort} is marked {@code DEDUPED}, which may be the run just accepted.
   *
   * <p>It is {@link #supersedeByCheckoutBranch}'s sibling, down to the columns it writes, and it
   * exists for a version of the same reason. A release push writes N tag refs and
   * the git host announces <b>every one of them</b> — deliberately, because {@code SCMPublishTag} is
   * a fact about the repository and qits-projects' backup consumer needs them all. So the collapse
   * cannot live in the publisher, and it lives here, in the consumer that turns a fact into work.
   *
   * <p><b>Best-effort by design, exactly like the checkout-branch supersede.</b> Only {@code QUEUED} rows are
   * touched: a lower tag whose run has already started keeps running, because cancelling a running
   * build to save the time it has already spent is a worse trade than letting it finish. What is
   * guaranteed is convergence, not minimality — N tags in one push leave one run to do.
   *
   * <p>Three things are deliberately left alone. A payload neither side can be read a tag out of
   * supersedes nothing, since "no tag" is not a lower version — it is a failure to compare, and a
   * failure to compare must not cancel a build. A tag equal to a queued one <em>does</em> supersede
   * it, because that is a tag that moved and the newer announcement is the current one, which is the
   * same rule the checkout-branch supersede applies to a re-fold. And a non-tag event never enters here
   * at all: no other event on this bus carries a field this can order by.
   *
   * <p><b>A row names what beat it at the time, so out-of-order tags leave a chain rather than a
   * star.</b> Only queued rows are candidates, so a run superseded three tags ago is no longer one —
   * its {@code supersededByRunId} keeps pointing at the run that beat it then, which is a true
   * statement about that moment and reaches the surviving run by following it. Rewriting the older
   * rows to name the latest winner would be tidier and would say something no thread ever decided.
   *
   * <p>Called inside {@link #acceptEventRun}'s transaction, after the flush, so a row it supersedes
   * and the row that superseded it commit together or not at all.
   */
  private void supersedeByVersion(CiRun accepted, EventRun request, List<CiRun> superseded) {
    if (!TAG_EVENT_NAME.equals(request.eventName())) {
      return;
    }
    String tag = tagOf(request.payload());
    if (tag == null) {
      return;
    }
    CiRun newer = null;
    String newerTag = null;
    for (CiRun queued :
        runs.listQueuedEventRuns(
            accepted.repoId, accepted.configPath, TAG_EVENT_NAME, accepted.id)) {
      String queuedTag = tagOf(queued.triggerEventPayload);
      if (queuedTag == null) {
        continue;
      }
      if (VersionSort.compare(tag, queuedTag) >= 0) {
        dedupe(queued, accepted);
        superseded.add(queued);
      } else if (newerTag == null || VersionSort.compare(queuedTag, newerTag) > 0) {
        newer = queued;
        newerTag = queuedTag;
      }
    }
    if (newer != null) {
      // The push's tags arrived out of order and this one is not the newest. Its row stays as the
      // record that the tag was announced; the worker's claim reads the status back and drops it,
      // which is the same path a cancellation of a queued run takes.
      LOG.infof(
          "Tag %s in %s is older than queued %s — run %s stands for %s",
          tag, accepted.repoId, newerTag, newer.id, accepted.configPath);
      dedupe(accepted, newer);
    }
  }

  /**
   * Marks one queued run superseded by another. The idiom all three supersedes write, spelled once.
   *
   * <p><b>{@code CANCELLED}, not {@code FAILED}.</b> The row used to settle red, and the word was
   * wrong in the only place a word matters — a reader's. A superseded run answered no question and
   * published no verdict: it is bookkeeping about the queue, exactly the statement {@link
   * CiRunStatus#CANCELLED} already exists to make, and {@code cancellationReason = }{@link #DEDUPED}
   * is what separates it from the two cancellations a person asks for. Settling it red made every
   * re-fold of a release request look like a build that broke, in {@code GET /ci/api/runs/finished}
   * and in the SPA, and false alarms are what got this changed.
   *
   * <p><b>Nothing downstream reads it as a new failure class</b>, because nothing downstream reads
   * it at all: a deduped row is written here, at accept time, inside the accepting transaction, and
   * never reaches {@link #announceRun} or {@link #announceFailedRun}. It published no {@code
   * BuildFailed} before this change and publishes none after, so qits-projects' build gate — which
   * matches verdicts on {@code (repoId, commitSha)} — sees exactly what it saw. The status is a read
   * surface, and this is a correction to what that surface says.
   *
   * <p><b>It does reach {@link #announceStatus}, and that is the same sentence rather than an
   * exception to it.</b> A deduped row is not a verdict, so it says nothing about its commit; it IS
   * a row that was in the active listing and is not any more, so it says exactly that much about
   * itself. The announcement is made by {@link #announceAccepted} after the accepting transaction
   * commits — never from in here, which runs inside it — so a mirror is told about a cancellation
   * the database has really taken.
   *
   * <p>The other columns are unchanged and load-bearing: {@code finishedAt} is the winner's
   * acceptance rather than {@code now}, so the row is finished at the moment it was beaten; {@code
   * supersededByRunId} is the link a reader follows to the run that stands. And {@code CANCELLED} is
   * terminal, so the row leaves the active list and appears in the finished one exactly as before —
   * {@code CiRunRepository.listFinished} names the two ACTIVE statuses rather than the terminal ones,
   * for that reason.
   */
  private static void dedupe(CiRun loser, CiRun winner) {
    loser.status = CiRunStatus.CANCELLED;
    loser.finishedAt = winner.createdAt;
    loser.cancellationReason = DEDUPED;
    loser.supersededByRunId = winner.id;
  }

  /**
   * The release request this event run serves, or null when it serves none.
   *
   * <p><b>Gated on the event NAME</b>, the way {@link #supersedeByVersion} is gated on the tag
   * event's. A {@code releaseRequestId} elsewhere on the bus would be some other context's word,
   * and a provenance column that reads any field of any payload is one that eventually records
   * something nobody meant.
   *
   * <p>Walked rather than bound, the trigger engine's rule and the one that keeps this path free of
   * native-image reflection metadata. A value too long for the column is recorded as <b>none</b>
   * rather than truncated or thrown: the run is the point and a payload that cannot name a request
   * within 255 characters is not naming one this platform issued.
   */
  private static String releaseRequestOf(EventRun request) {
    if (!RELEASE_REQUEST_EVENT_NAME.equals(request.eventName())) {
      return null;
    }
    JsonNode id =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(request.payload()), RELEASE_REQUEST_ID_FIELD);
    if (id == null) {
      return null;
    }
    String text = CiEventSelectionEvaluator.asString(id);
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > MAX_RELEASE_REQUEST_ID_LENGTH) {
      LOG.warnf(
          "Event %s (%s) names a '%s' of %d characters — too long to record, the run keeps none",
          request.eventId(), request.eventName(), RELEASE_REQUEST_ID_FIELD, text.length());
      return null;
    }
    return text;
  }

  /**
   * What the triggering event said this run's work is worth, or null when it said nothing.
   *
   * <p><b>Gated on the two event names that carry the field</b>, {@link
   * #RELEASE_REQUEST_EVENT_NAME} and {@link ReleaseJoin#RELEASE_EVENT_NAME} — {@link
   * #releaseRequestOf}'s rule with one more name in it, for its reason exactly: a {@code priority}
   * elsewhere on the bus is some other context's word about some other thing, and a column that
   * reads any field of any payload eventually records something nobody meant.
   *
   * <p><b>Nothing here judges the VALUE</b>, which is {@code ReleaseJoin.priorityToRecord}'s stance
   * carried onto this row: there is no enum, no accepted-word list, and an unknown priority is
   * recorded verbatim — {@link CiRunOrdering} is where a word becomes a rank, and an unrecognised
   * one is ranked in the middle rather than refused. The one rule is the column's own width, and it
   * is {@link #MAX_RELEASE_REQUEST_ID_LENGTH}'s rule verbatim: <b>recorded as none, with a WARN,
   * rather than truncated or thrown</b>. The run is the point, and a payload that cannot name a
   * priority within {@link #MAX_PRIORITY_LENGTH} characters is not naming one this platform issued.
   *
   * <p>Walked rather than bound, like every other payload read on this path, and for the
   * native-image reason {@code EventWireReflection}'s javadoc states.
   */
  private static String priorityOf(EventRun request) {
    if (!RELEASE_REQUEST_EVENT_NAME.equals(request.eventName())
        && !ReleaseJoin.RELEASE_EVENT_NAME.equals(request.eventName())) {
      return null;
    }
    JsonNode priority =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(request.payload()), PRIORITY_FIELD);
    if (priority == null) {
      return null;
    }
    String text = CiEventSelectionEvaluator.asString(priority);
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > MAX_PRIORITY_LENGTH) {
      LOG.warnf(
          "Event %s (%s) names a '%s' of %d characters — too long to record, the run keeps none",
          request.eventId(), request.eventName(), PRIORITY_FIELD, text.length());
      return null;
    }
    return text;
  }

  /**
   * The repositories qits-projects named as downstream of this run's repository, as the canonical
   * JSON array text it arrived as, or null when the event named none.
   *
   * <p><b>Gated on {@link #RELEASE_REQUEST_EVENT_NAME} alone</b>, unlike {@link #priorityOf}: it is
   * the only event that carries a closure. {@code SCMRelease} deliberately does not — a release run
   * already ranks ahead of every release-request run categorically, so sequencing releases among
   * themselves would buy an ordering nothing has asked for, and the field can be added there later
   * at no migration cost.
   *
   * <p><b>An absent field, a null and a non-array are all null, silently.</b> No WARN: absent is the
   * ordinary value for as long as qits-projects has not shipped the enrichment (and for every
   * deployment of it that cannot reach qits-maintenance), so warning here would be a line per
   * release request forever — the same argument the trigger listing's DEBUG makes one section over.
   *
   * <p>The array is stored <b>verbatim</b> rather than validated element by element. The names are
   * another context's words, matched against {@link CiRun#repoName} as strings and against nothing
   * else, and they reach no filesystem path, no URL and no argv — so there is nothing here for
   * {@code CiIdentifiers} to protect. What a nonsense element costs is one edge that matches no
   * queued run, which is what "unknown means unconstrained" already covers.
   */
  private static String downstreamReposOf(EventRun request) {
    if (!RELEASE_REQUEST_EVENT_NAME.equals(request.eventName())) {
      return null;
    }
    JsonNode downstream =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(request.payload()),
            RELEASE_REQUEST_DOWNSTREAM_FIELD);
    if (downstream == null || !downstream.isArray()) {
      return null;
    }
    return downstream.toString();
  }

  /**
   * How long this run's steps are expected to take, as the column text {@link
   * ExpectedStepDurations} writes, or null when there is nothing to say.
   *
   * <p><b>It is a convenience and it is treated like one, which is the only rule here that matters.</b>
   * Everything below is wrapped: an unparseable trigger config, a database that answers the sample
   * query with an exception, an arithmetic surprise — all of it is one WARN and no prediction, and
   * the run is accepted exactly as it would have been before this method existed. A prediction that
   * cost a build would be worse than no prediction in every direction, and the only way to make that
   * true rather than intended is to catch here and hand the caller a value it does not have to think
   * about.
   *
   * <p><b>The whole run's prediction is all-or-nothing.</b> A pipeline where step 2 has history and
   * step 3 does not is a pipeline that CHANGED — it grew a step, or a step changed image — and a
   * partial answer would draw a progress bar whose remaining segment is a guess pretending to be a
   * measurement. So one step with no sample is no prediction for the run, and the next successful
   * run of the new shape is what starts predicting it again. Same for a pipeline declaring no steps
   * at all: nothing to draw.
   *
   * <p><b>The parse is of the SNAPSHOT and the image is the RESOLVED one.</b> The trigger file on
   * the row is what this run will execute, and {@link #stepImage} is the reference its containers
   * will really be started from and the one {@code ci_step.image} records — so asking history about
   * the resolved reference is asking about the same rows the run is about to write.
   *
   * <p>The read runs in its own transaction because the callers stand on threads that have none: the
   * trigger worker has no request context at all, and a retry arrives on a request thread on the way
   * to the queue. Its own bracket also means it commits and releases before the insert opens.
   */
  private String predictedStepDurations(String repoId, String configPath, String triggerConfig) {
    if (triggerConfig == null) {
      return null;
    }
    try {
      return QuarkusTransaction.requiringNew()
          .call(() -> predictFromHistory(repoId, configPath, triggerConfig));
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Could not predict step durations for %s in %s — the run is accepted without one",
          configPath,
          repoId);
      return null;
    }
  }

  /** {@link #predictedStepDurations}' body, inside a transaction and free to throw. */
  private String predictFromHistory(String repoId, String configPath, String triggerConfig) {
    List<CiPipeline.CiStepDecl> declared =
        triggerParser.parse(configPath, triggerConfig).pipeline().steps();
    List<Long> millis = new ArrayList<>(declared.size());
    for (int index = 0; index < declared.size(); index++) {
      String image = stepImage(declared.get(index));
      if (image == null || image.isBlank()) {
        return null;
      }
      OptionalDouble seconds =
          steps.percentileSuccessfulDurationSeconds(
              repoId, configPath, index, image, DURATION_PERCENTILE, DURATION_SAMPLE_SIZE);
      if (seconds.isEmpty()) {
        return null;
      }
      // Rounded UP, and never below one millisecond: a step really does take some time, and a zero
      // would be a segment of no width that a client cannot draw and would read as "already done".
      millis.add(Math.max(1L, (long) Math.ceil(seconds.getAsDouble() * 1000.0)));
    }
    return ExpectedStepDurations.encode(millis);
  }

  /**
   * The tag an {@code SCMPublishTag} payload announces, or null when there is none to read — an
   * unparseable payload, a missing field, or a blank one. Walked rather than bound, the same rule
   * {@link #versionOf} follows and for the same native-image reason.
   */
  private static String tagOf(String payload) {
    JsonNode tag =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(payload), TAG_NAME_FIELD);
    if (tag == null) {
      return null;
    }
    String text = CiEventSelectionEvaluator.asString(tag);
    return text.isBlank() ? null : text;
  }

  /**
   * Whether a failed insert was the unique constraint rather than something else. Walked rather than
   * matched on a message: the exception a Panache persist wraps a constraint violation in depends on
   * the transaction boundary that flushed it, so the cause chain is the only stable place to look.
   */
  private static boolean isUniqueViolation(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException
          || cause instanceof SQLIntegrityConstraintViolationException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  /**
   * A newly accepted run. Always {@code QUEUED}, never finished, and pinning no daemon: this class
   * writes exactly one kind of row now, and every state after it is a transition the worker makes.
   */
  private static CiRun newRun(CiRepoRef repo, String branch, String sha) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repo.repoId();
    // Null when the candidate carries no public coordinate — the row then reads exactly as every
    // row recorded before this campaign, and every URL built from it is the id-addressed one.
    run.projectId = repo.projectId();
    run.repoName = repo.name();
    run.branch = branch;
    run.commitSha = sha;
    run.status = CiRunStatus.QUEUED;
    run.createdAt = Instant.now();
    return run;
  }

  /** Writes one step row. Every row this class writes is already in a terminal state. */
  private void insertStep(
      String runId,
      int stepIndex,
      String image,
      CiStepStatus status,
      Integer exitCode,
      String output,
      Instant startedAt,
      Instant finishedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiStep step = new CiStep();
              step.id = UUID.randomUUID().toString();
              step.runId = runId;
              step.stepIndex = stepIndex;
              step.image = image;
              step.status = status;
              step.exitCode = exitCode;
              step.output = output;
              step.startedAt = startedAt;
              step.finishedAt = finishedAt;
              steps.persist(step);
            });
  }

  /**
   * Moves a run's non-terminal steps to terminal states (RUNNING ⇒ FAILED, PENDING ⇒ SKIPPED).
   *
   * <p>Nothing this class writes is ever non-terminal any more, so this only ever finds <b>legacy</b>
   * rows — steps persisted upfront by a version of this service that predates persist-at-finish. It
   * stays for exactly that, and for the startup sweep that is its only remaining caller of substance.
   */
  private void failIncompleteSteps(String runId) {
    for (CiStep step : steps.listByRunIdOrdered(runId)) {
      if (step.status == CiStepStatus.RUNNING) {
        step.status = CiStepStatus.FAILED;
      } else if (step.status == CiStepStatus.PENDING) {
        step.status = CiStepStatus.SKIPPED;
      }
    }
  }

  /**
   * Writes the run's terminal row and hands back the instant it stamped, which is the one thing
   * about a finished run that is not already in the caller's hand — {@link RunAnnouncer} needs it,
   * and taking it from the transaction that wrote it is what keeps the row and the event agreeing
   * on when the run ended.
   */
  private Instant finishRun(String runId, CiRunStatus status) {
    return finishRun(runId, status, null);
  }

  /**
   * The same, also writing what the <b>verdict</b> is worth to a release gate. Null leaves the
   * column alone, which is every terminal transition that is not the step loop's own: a config
   * error, a cancellation and a swept orphan have no failing step to classify, so the file's own
   * flag stands.
   */
  private Instant finishRun(String runId, CiRunStatus status, Boolean gating) {
    Instant finishedAt = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = runs.findById(runId);
              run.status = status;
              run.finishedAt = finishedAt;
              if (gating != null) {
                run.gating = gating;
              }
            });
    return finishedAt;
  }

  /** Removes a run that turned out to describe a commit that no longer exists. */
  private void discardRun(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              steps.delete("runId = ?1", runId);
              runs.deleteById(runId);
            });
  }

  /**
   * Stop a run that has not finished. A {@code RUNNING} one is flagged and its in-flight step's
   * container is asked to die; this returns as soon as both are done, which is well before the run
   * is actually finished — the caller answers 202.
   *
   * <p>A {@code QUEUED} one is <b>finished here</b>, {@code CANCELLED}, without the worker ever picking
   * it up. There is no container to ask and no step to stop, so the row is the whole of the
   * cancellation; the worker's own {@link #startQueued} then finds a row that is no longer queued
   * and drops it. That is the queue-visible form of what this class did before, when a cancellation
   * arriving before the first step tore the launch down instead.
   *
   * <p>The flag is raised in <b>both</b> cases and on purpose. Cancel runs on the request thread and
   * the claim runs on the worker; if the worker won the race and turned the row {@code RUNNING}
   * between the read and the write, the flag is what stops the run before its first container — so
   * neither thread has to win for the answer to be right.
   *
   * <p><b>A {@code RUNNING} row no worker of this process owns is settled here, in one write.</b>
   * That row is what a dead predecessor leaves: its worker went with its container, so
   * {@link CiStepRunner#cancel} would ask nothing of nobody and nothing would ever write the
   * terminal row. So the incomplete steps are failed and the run is finished {@code CANCELLED} with
   * the caller's reason, and the runner is not called at all. Measured 2026-08-23, when a
   * start-first successor swept and the dying predecessor then claimed a queued row: the survivor
   * had to be flipped by hand in SQL, because cancel only ever recorded a reason on it.
   *
   * <p>Cancelling anything already terminal is a 409 rather than a quiet success: a finished run has
   * nothing to stop, and telling the caller it does would be a lie it cannot check.
   *
   * <p><b>Two of the three arms write a terminal row and both announce it</b> through {@link
   * #announceStatus}, after their own write commits — the {@code QUEUED} one and the ownerless
   * {@code RUNNING} one. The third writes only a reason: the run is still going and the worker that
   * owns it settles it through {@link #settleUnexpectedly} or the step loop, which is where its
   * terminal announcement is made. So the run announces exactly once for exactly the write that
   * finished it, and never for the request that asked. That the announcement happens at all is the
   * difference between this port and the verdict ones: a cancellation is not a statement about the
   * commit, and it is very much a statement about the listing the run has just left.
   */
  public void cancel(String runId) {
    cancel(runId, null);
  }

  public void cancel(String runId, String requestedReason) {
    String reason = cancellationReason(requestedReason);
    CiRun run = requireRun(runId);
    if (run.status != CiRunStatus.RUNNING && run.status != CiRunStatus.QUEUED) {
      throw new ConflictException(
          "CI run " + runId + " is not running (" + run.status + ") — nothing to cancel");
    }
    cancelled.add(runId);
    // Both writes are held through a short outage: this runs on the request thread, outside any
    // transaction of its own, and each body is nothing but statements — the flag above and the
    // runner below are outside the retry precisely because they are not.
    // The settled row rather than a boolean, so the announcement below can carry the timestamp and
    // the coordinates the transaction itself wrote. Null is "it was no longer QUEUED", which is the
    // ordinary race and not a settlement.
    CiRun neverStarted =
        DbRetry.inNewTx(
            "cancel a queued run",
            () -> {
              CiRun current = runs.findById(runId);
              if (current == null || current.status != CiRunStatus.QUEUED) {
                return null;
              }
              current.status = CiRunStatus.CANCELLED;
              current.finishedAt = Instant.now();
              current.cancellationReason = reason;
              current.supersededByRunId = null;
              runs.flush();
              return current;
            },
            retryDeadline());
    if (neverStarted != null) {
      LOG.infof("CI run %s cancelled on request before it started (%s)", runId, reason);
      announceStatus(
          neverStarted, CiRunStatus.CANCELLED, CiRunStatus.QUEUED, neverStarted.finishedAt);
      return;
    }
    if (!runner.owns(runId)) {
      // Nobody here is running it, so there is nothing to ask to stop and nothing that will ever
      // write the terminal row. Settle it in one write instead of recording a reason on a row that
      // would stay RUNNING forever.
      CiRun settled =
          DbRetry.inNewTx(
              "settle a running run no worker owns",
              () -> {
                CiRun current = runs.findById(runId);
                if (current == null) {
                  return null;
                }
                failIncompleteSteps(runId);
                current.status = CiRunStatus.CANCELLED;
                current.finishedAt = Instant.now();
                current.cancellationReason = reason;
                current.supersededByRunId = null;
                runs.flush();
                return current;
              },
              retryDeadline());
      LOG.infof(
          "CI run %s was RUNNING with no worker in this process — settled as CANCELLED on request",
          runId);
      if (settled != null) {
        announceStatus(settled, CiRunStatus.CANCELLED, CiRunStatus.RUNNING, settled.finishedAt);
      }
      return;
    }
    DbRetry.runInNewTx(
        "record a running run's cancellation reason",
        () -> {
          CiRun current = runs.findById(runId);
          if (current != null) {
            current.cancellationReason = reason;
            current.supersededByRunId = null;
            runs.flush();
          }
        },
        retryDeadline());
    runner.cancel(runId);
    LOG.infof("CI run %s cancelled on request (%s)", runId, reason);
  }

  /**
   * Stop every unfinished run one repository has for one release request, and answer which ones were
   * stopped.
   *
   * <p><b>The caller is qits-projects and the reason it calls is that the question went away.</b> A
   * request is withdrawn, closed or re-scoped; the fold it was being built at is no longer worth
   * building, and the runs still queued or running for it are work nobody will read. Addressing them
   * by {@code (repoId, releaseRequestId)} rather than by run id is the whole point of {@link
   * CiRun#releaseRequestId} existing: the merged sha those runs carry is a fold nobody pushed and the
   * next re-fold replaces it, so the caller has no run id to hold and no sha that stays true.
   *
   * <p><b>Both halves of the key are required, and a sibling request survives.</b> One request folds
   * N repositories and one repository can have N open requests against it, so either half alone
   * would reach work that belongs to something else — cancelling another request's build is exactly
   * the failure this endpoint must not have.
   *
   * <p><b>Nothing it cancels publishes a gating verdict.</b> That is not arranged here: a {@code
   * CANCELLED} run announces nothing at all, which is {@link #announceFailedRun}'s contract and the
   * property qits-projects' release gate depends on — a cancelled run must never be read as a
   * failure, because a person withdrawing a question is not an answer to it.
   *
   * <p>A run that finishes between the listing and the cancel is a race the caller is right not to
   * care about: it is skipped rather than reported, since the outcome asked for — that run is not
   * still going — is the outcome that happened. So this is <b>idempotent</b>: calling it twice
   * cancels nothing the second time and still answers 202.
   */
  public List<String> cancelReleaseRequestRuns(String repoId, String releaseRequestId) {
    List<CiRun> unfinished =
        DbRetry.call(
            "release request run listing",
            () ->
                QuarkusTransaction.requiringNew()
                    .call(() -> runs.listUnfinishedForReleaseRequest(repoId, releaseRequestId)),
            retryDeadline());
    List<String> stopped = new ArrayList<>();
    for (CiRun run : unfinished) {
      try {
        cancel(run.id, RELEASE_REQUEST_CANCELLED);
        stopped.add(run.id);
      } catch (ConflictException | NotFoundException raced) {
        LOG.debugf(
            "CI run %s of release request %s was already over when the cancellation reached it",
            run.id, releaseRequestId);
      }
    }
    LOG.infof(
        "Release request %s in %s: cancelled %d of %d unfinished run(s)",
        releaseRequestId, repoId, stopped.size(), unfinished.size());
    return stopped;
  }

  /**
   * Re-fire a finished run's pipeline, unchanged: a new run for the same repository, the same
   * trigger file, the same checkout and the same release request.
   *
   * <p><b>What it is for.</b> A QA pipeline can go red for a reason that is not the code — a flaked
   * container, a registry that was down, a step that timed out on a slow host — and the release
   * request it gates has not changed, so there is nothing to re-fold and no new event to wait for.
   * Re-asking the same question is the operation, and it is deliberately <em>not</em> "run it at the
   * current head": the retry builds the exact {@link CiRun#commitSha} the original built, so its
   * verdict lands on the same commit and the gate reads it exactly as it would have read the first
   * one.
   *
   * <p><b>Only a finished run is retryable.</b> A queued or running one is a 409: the question is
   * still being answered, and a second run of the same work would race the first for the same
   * verdict. Everything terminal is fair game, cancelled runs included — re-fire is precisely what a
   * cancellation invites.
   *
   * <p><b>The dedupe is bypassed rather than weakened</b>, by minting a synthetic trigger identity
   * for the new row — see {@link #RETRY_TRIGGER_PREFIX} and {@link #insertRetry}. Nothing about
   * {@code unique (trigger_event_id, repo_id, config_path)} changes, and no replay of a real event
   * becomes possible.
   *
   * @return the new run, already {@code QUEUED} and on the worker
   */
  public CiRun retry(String runId) {
    CiRun source = requireRun(runId);
    if (source.status == CiRunStatus.QUEUED || source.status == CiRunStatus.RUNNING) {
      throw new ConflictException(
          "CI run " + runId + " has not finished (" + source.status + ") — nothing to retry yet");
    }
    // Predicted rather than copied, unlike priority and the downstream closure beside it: those are
    // what the run is WORTH and a re-fire must be worth what it re-fires, while this is how long the
    // work TAKES and the honest answer is the one the history gives now. The source's own value may
    // be months old, and every run it has had since is evidence the source row cannot carry.
    String expected =
        predictedStepDurations(source.repoId, source.configPath, source.triggerConfig);
    CiRun retry =
        DbRetry.inNewTx(
            "run retry accept", () -> insertRetry(source.id, expected), retryDeadline());
    if (retry == null) {
      throw new NotFoundException("No such CI run: " + runId);
    }
    LOG.infof(
        "CI run %s retried as %s — same %s at %s", runId, retry.id, retry.configPath,
        retry.commitSha);
    // After the insert's transaction and before the wake, the accept path's arrangement exactly: a
    // retry is a new row entering the active listing, and the only thing that separates it from an
    // event-triggered arrival is which method wrote it. `previousStatus` is null for that reason —
    // this run has no earlier state, whatever the run it re-fires ended as.
    announceStatus(retry, CiRunStatus.QUEUED, null, retry.createdAt);
    enqueue(retry.id);
    return retry;
  }

  /**
   * The retry row, built inside its own transaction from a freshly read source row.
   *
   * <p>The source is re-read here rather than taken from the caller's detached copy for {@link
   * #insertEventRun}'s reason: {@link DbRetry#inNewTx} re-runs the whole body, so everything it
   * touches has to come from the database on each attempt and the row it persists has to be a fresh
   * instance with no memory of a rolled-back one.
   *
   * <p><b>{@code gating} is re-derived from the trigger file rather than copied.</b> The column on a
   * finished run is what that run's <em>verdict</em> was worth — the file's flag ANDed with whichever
   * step failed — so copying it would start a green retry of a gating pipeline off as non-gating and
   * publish a verdict no release gate holds a commit for. The declaration is on the row as {@code
   * triggerConfig}, so the answer is one parse away; a historical push row carries none and is
   * gating, which is what every push run was.
   */
  private CiRun insertRetry(String sourceRunId, String expectedStepDurations) {
    CiRun source = runs.findById(sourceRunId);
    if (source == null) {
      return null;
    }
    CiRun retry = new CiRun();
    retry.id = UUID.randomUUID().toString();
    retry.repoId = source.repoId;
    retry.projectId = source.projectId;
    retry.repoName = source.repoName;
    retry.branch = source.branch;
    retry.commitSha = source.commitSha;
    retry.status = CiRunStatus.QUEUED;
    retry.createdAt = Instant.now();
    retry.gating = declaredGating(source);
    retry.releaseRequestId = source.releaseRequestId;
    // Copied rather than re-read: a retry asks for the SAME work, so it is worth what the original
    // was worth and waits on what the original waited on. The payload is on the row and would answer
    // identically, but a re-fire whose queue position differed from the run it re-fires would be a
    // second decision nobody asked for.
    retry.priority = source.priority;
    retry.downstreamRepos = source.downstreamRepos;
    // Handed in from a fresh read of the history rather than copied off the source — see retry().
    retry.expectedStepDurations = expectedStepDurations;
    retry.retryOfRunId = source.id;
    retry.triggerType = source.triggerType;
    retry.configPath = source.configPath;
    // The bypass. Unique by construction, unmistakably local, and the constraint is untouched.
    retry.triggerEventId = RETRY_TRIGGER_PREFIX + retry.id;
    // The cause is inherited, so the events this run publishes name the same domain event the
    // original's did. Set explicitly for insertEventRun's reason: this runs on a request thread on
    // the way to the worker, and the ambient scope has nothing to say about a run being re-fired.
    retry.causationId = source.causationId;
    retry.triggerEventName = source.triggerEventName;
    retry.triggerEventOccurredAt = source.triggerEventOccurredAt;
    retry.triggerEventPayload = source.triggerEventPayload;
    retry.triggerConfig = source.triggerConfig;
    runs.persist(retry);
    runs.flush();
    return retry;
  }

  /** What a run's trigger file declared the pipeline to be worth, before any step narrowed it. */
  private boolean declaredGating(CiRun source) {
    if (source.triggerConfig == null) {
      return true;
    }
    try {
      return triggerParser.parse(source.configPath, source.triggerConfig).gating();
    } catch (RuntimeException unparseable) {
      // The snapshot parsed once, at accept, so this is unreachable through the engine. If it ever
      // is not, the run's own recorded value is the closest true statement available — never a
      // widening to gating, which would hold a commit for a verdict nobody declared gating.
      LOG.warnf(
          unparseable,
          "Retry of run %s could not re-read %s — keeping the recorded gating flag",
          source.id,
          source.configPath);
      return source.gating;
    }
  }

  private static String cancellationReason(String requestedReason) {
    if (requestedReason == null || requestedReason.isBlank()) {
      return USER_CANCELLED;
    }
    String reason = requestedReason.trim();
    if (reason.length() > MAX_CANCELLATION_REASON_LENGTH) {
      throw new eu.wohlben.qits.ci.error.BadRequestException(
          "Cancellation reason must be at most " + MAX_CANCELLATION_REASON_LENGTH + " characters");
    }
    return reason;
  }

  /**
   * Every accepted-but-unfinished run on this instance — {@code QUEUED} or {@code RUNNING} — newest
   * first, across all repositories. The read behind {@code GET /ci/api/runs/active}.
   *
   * <p>Unscoped, unlike everything else on this surface, because the question is "what is CI doing
   * right now" and that has no repository to scope to. It only became answerable when {@code QUEUED}
   * became a row: before, half of it lived in an executor's queue.
   */
  public List<CiRun> activeRuns() {
    return DbRetry.call(
        "active run listing",
        () -> QuarkusTransaction.requiringNew().call(runs::listActiveNewestFirst),
        retryDeadline());
  }

  /**
   * The configured deadline, or the library's default for an instance nobody injected — which is how
   * a hand-built test subclass arrives, constructed with {@code new} and calling {@code super} for
   * the part it does not fake.
   */
  private Duration retryDeadline() {
    return dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline;
  }

  /**
   * How many finished runs {@code GET /ci/api/runs/finished} answers with when the caller asks for no
   * particular number. Five, because the endpoint exists for a client that draws a short stack of
   * "what just happened" beside the runs in flight, and a default that has to be overridden to be
   * useful is not a default.
   */
  public static final int DEFAULT_FINISHED_LIMIT = 5;

  /**
   * The most finished runs one call will answer with, whatever it asks for.
   *
   * <p>This listing is the only one on the surface that is <b>both</b> unscoped by repository and
   * unbounded by anything else — the active list is bounded by what a single worker has accepted, and
   * a repository's own listing is bounded by that repository. Without a cap, {@code ?limit=} is an
   * unscoped listing of every run on the instance, which is precisely what {@code
   * CiRunController#listRuns} refuses to offer.
   *
   * <p>A larger ask is <b>clamped, not rejected</b>. The parameter has always been a bound rather
   * than a promise of n rows — {@code limit=50} over three runs answers with three and is not an
   * error — so answering an over-large ask with the most this endpoint will give is the same
   * contract, and a client that wants more history has a repository to scope to.
   */
  public static final int MAX_FINISHED_LIMIT = 100;

  /**
   * The newest finished runs across every repository, newest first — the read behind {@code GET
   * /ci/api/runs/finished}.
   *
   * @param limit how many to answer with, or null for {@link #DEFAULT_FINISHED_LIMIT}. Clamped to
   *     {@link #MAX_FINISHED_LIMIT}.
   * @throws BadRequestException if a limit is given and is not positive — the same rule {@link
   *     #runsFor(String, Integer)} applies, and for the same reason: zero rows is a question nobody
   *     asks and a negative bound is a caller bug rather than an empty answer
   */
  public List<CiRun> finishedRuns(Integer limit) {
    int asked = limit == null ? DEFAULT_FINISHED_LIMIT : limit;
    if (asked <= 0) {
      throw new BadRequestException("Invalid limit");
    }
    int bounded = Math.min(asked, MAX_FINISHED_LIMIT);
    return DbRetry.call(
        "finished run listing",
        () -> QuarkusTransaction.requiringNew().call(() -> runs.listFinishedNewestFirst(bounded)),
        retryDeadline());
  }

  /**
   * One row per repository this instance has recorded a run for, ascending by id: its newest run on
   * any branch, and its newest run on {@code main}.
   *
   * @param repositoryId the repository, exactly as {@link #repositoryIds} spells it — the storage
   *     id, which is what the runs are grouped by and what stays stable across a rename
   * @param projectId the owning project, or null when no run of this repository carries the public
   *     coordinate. Read off the newest run rather than stored anywhere: qits-ci owns no repository
   *     row, so what it knows about a name is whatever the last run's candidate reference carried.
   * @param repoName the repository's public name under the same rule, or null
   * @param lastRun the newest run on any branch — never null, since a repository is only listed
   *     because it has one
   * @param lastMainRun the newest run on {@code main}, or null when every run it has is on another
   *     branch. It is frequently the same row as {@code lastRun}, and that is not a duplicate to
   *     collapse: a client asking "is main green" and a client asking "what happened last" are
   *     asking two questions that usually have one answer.
   */
  public record RepositorySummary(
      String repositoryId, String projectId, String repoName, CiRun lastRun, CiRun lastMainRun) {}

  /**
   * The summary behind {@code GET /ci/api/repositories/summary} — {@link #repositoryIds} with the
   * two runs a client would otherwise fetch a listing per repository to find.
   *
   * <p>Two queries per repository rather than one grouped query over everything. Both are index-hit
   * top-1 reads, the repository count is the number of repositories on the platform, and the
   * alternative — a window function or a fetch of every run — is either an ordering the entity
   * mapping would have to be taught or exactly the unbounded read this endpoint exists to spare the
   * client.
   *
   * <p><b>The whole fan-out is one transaction and one retry.</b> The listing and the 2N reads used
   * to be 2N+1 unbracketed queries; holding them together is what lets a connection lost halfway
   * through be answered by re-reading the lot rather than by half a summary, and it costs nothing —
   * every one of them is an index-hit read.
   */
  public List<RepositorySummary> repositorySummaries() {
    return DbRetry.call(
        "repository summary listing",
        () -> QuarkusTransaction.requiringNew().call(this::readSummaries),
        retryDeadline());
  }

  private List<RepositorySummary> readSummaries() {
    return sortedRepoIds().stream()
        .map(
            repoId -> {
              CiRun last = runs.newestFor(repoId).orElse(null);
              return new RepositorySummary(
                  repoId,
                  last == null ? null : last.projectId,
                  last == null ? null : last.repoName,
                  last,
                  runs.newestForBranch(repoId, MAIN_BRANCH).orElse(null));
            })
        // A repository is listed because it has runs, but the listing and these reads are separate
        // queries: a deletion in between must drop the entry rather than answer with a null lastRun.
        .filter(summary -> summary.lastRun() != null)
        .toList();
  }

  /** All runs recorded for a repository, newest-first. */
  public List<CiRun> runsFor(String repoId) {
    return DbRetry.call(
        "run listing for repository " + repoId,
        () -> QuarkusTransaction.requiringNew().call(() -> runs.listByRepoIdNewestFirst(repoId)),
        retryDeadline());
  }

  /**
   * The newest {@code limit} runs recorded for a repository, or all of them when {@code limit} is
   * null.
   *
   * @throws BadRequestException if a limit is given and is not positive — zero rows is a question
   *     nobody asks, and a negative bound is a caller bug rather than an empty answer
   */
  public List<CiRun> runsFor(String repoId, Integer limit) {
    if (limit == null) {
      return runsFor(repoId);
    }
    if (limit <= 0) {
      throw new BadRequestException("Invalid limit");
    }
    return DbRetry.call(
        "run listing for repository " + repoId,
        () ->
            QuarkusTransaction.requiringNew()
                .call(() -> runs.listByRepoIdNewestFirst(repoId, limit)),
        retryDeadline());
  }

  /**
   * Every repository this instance has recorded a run for, ascending — the read surface behind
   * {@code GET /ci/api/repositories}.
   *
   * <p>Sorted here rather than left to the caller so the response is stable across calls and across
   * instances: a client diffing "which repositories have CI activity" against the projects registry
   * must not see the set reorder because the query planner did.
   *
   * <p>These are ids qits-ci <b>observed</b> on its own runs, not repositories it owns. It is
   * deliberately narrower than {@link CiCandidateRepos#candidates()}, which also counts the bare
   * caches on disk: a repository ci once fetched for but never recorded a run against has no CI
   * history to explore, and listing it here would promise one.
   */
  public List<String> repositoryIds() {
    return DbRetry.call(
        "repository id listing",
        () -> QuarkusTransaction.requiringNew().call(this::sortedRepoIds),
        retryDeadline());
  }

  /**
   * {@link #repositoryIds} without the patience, so {@link #repositorySummaries} can read the ids
   * inside its own transaction and its own retry rather than nesting one retry in another.
   */
  private List<String> sortedRepoIds() {
    return runs.distinctRepoIds().stream().sorted().toList();
  }

  /**
   * The run, or 404.
   *
   * <p>The {@link NotFoundException} is raised inside the retry and is not retried: {@code DbRetry}
   * waits on connection failures only, so an absent run is still one immediate 404.
   */
  public CiRun requireRun(String runId) {
    return DbRetry.call(
        "run lookup " + runId,
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () ->
                        runs.findByIdOptional(runId)
                            .orElseThrow(() -> new NotFoundException("No such CI run: " + runId))),
        retryDeadline());
  }

  /** A run's steps in declaration order. */
  public List<CiStep> stepsFor(String runId) {
    return DbRetry.call(
        "step listing for run " + runId,
        () -> QuarkusTransaction.requiringNew().call(() -> steps.listByRunIdOrdered(runId)),
        retryDeadline());
  }

  /** Keeps the LAST {@code maxChars} chars (a step's tail is where the failure is), marked. */
  public static String tail(String output, int maxChars) {
    if (output == null || output.length() <= maxChars) {
      return output;
    }
    return TRUNCATION_MARKER + output.substring(output.length() - maxChars);
  }

  private static String firstLine(String output) {
    if (output == null || output.isBlank()) {
      return "(no output)";
    }
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }

  /**
   * The listener a step is run with: it exists to take the two host-side timestamps at the moments
   * the plan pins them to — {@code RunStep} sent, terminal frame received — rather than around the
   * blocking call, which would fold an image pull and a clone into "the step started".
   *
   * <p>Chunks are ignored here on purpose. The live surface is the runner's own relay, which lives
   * beside the socket the chunks arrive on; this module has no business holding a second copy of an
   * unbounded stream. Both fall back to a sane instant so a step that failed before it ever started
   * still gets an honest row.
   */
  private static final class Stamps implements CiStepRunner.StepListener {

    private final Instant began = Instant.now();
    private volatile Instant started;
    private volatile Instant finished;

    @Override
    public void onStarted() {
      started = Instant.now();
    }

    @Override
    public void onChunk(String text) {
      // The relay is the live surface; the row carries the tail the runner accumulated.
    }

    @Override
    public void onFinished() {
      finished = Instant.now();
    }

    Instant startedAt() {
      return started != null ? started : began;
    }

    Instant finishedAt() {
      return finished != null ? finished : Instant.now();
    }
  }

  /**
   * Test hook: waits until no worker is running anything and the queue holds nothing this process
   * would claim.
   *
   * <p><b>It cannot be a submit-and-wait any more, and the reason is the claim loop.</b> The pool's
   * threads are all inside {@link #workerLoop} for the life of the process, so a task submitted to
   * it would never run at all. What replaced it is a poll over the two facts that together mean
   * "idle": no worker is inside {@link #claimAndRunOne}, and no {@code QUEUED} row is waiting.
   *
   * <p><b>Two things make it sound and both are easy to get backwards.</b> {@link #busyWorkers} is
   * incremented <em>before</em> the candidate read, so a worker that has chosen a row but not yet
   * flipped it is already counted. And the two halves are read <b>queue first, workers second</b>,
   * because the transition that would otherwise slip between them is exactly {@code QUEUED →
   * RUNNING}: reading the workers first can see zero, let a worker claim and start a run, and then
   * see an empty queue — reporting idle over a run that has just begun. Measured, as a suite-only
   * flake in which a retried run's announcement had not happened yet. Reading the queue first cannot
   * be wrong: an empty queue means every accepted row is already {@code RUNNING} or over, and a
   * {@code RUNNING} one is held by a worker that has not decremented yet.
   *
   * <p>The {@code draining} arm is not an optimisation either: a draining process deliberately leaves
   * its backlog {@code QUEUED} for the successor, so waiting for the queue to empty would be waiting
   * for something that must never happen. The nudge is for the mirror case — a row accepted while
   * draining released no permit, so once a test lowers the flag there is nothing to wake the worker
   * but this.
   */
  void awaitIdle() throws Exception {
    try {
      while ((!draining && hasQueuedRows()) || busyWorkers.get() > 0) {
        work.release();
        Thread.sleep(25);
      }
    } finally {
      // The nudges above are the one place permits are released for something other than real work,
      // so they are the one place they are taken back: a permit left over here would let an idle
      // worker rescan into rows a later test has staged and expects nobody to touch. Outside this
      // hook, permits accumulate and are never drained — see the field.
      work.drainPermits();
    }
  }

  /** Whether anything is waiting to be claimed at all — {@link #awaitIdle}'s other half. */
  private boolean hasQueuedRows() {
    return QuarkusTransaction.requiringNew()
        .call(() -> runs.count("status = ?1", CiRunStatus.QUEUED) > 0);
  }
}
