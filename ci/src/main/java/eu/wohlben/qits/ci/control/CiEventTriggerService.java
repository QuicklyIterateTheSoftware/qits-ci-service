package eu.wohlben.qits.ci.control;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import eu.wohlben.qits.ci.entity.CiOwedEvent;
import eu.wohlben.qits.ci.persistence.CiOwedEventRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The trigger engine: one arriving domain event → which repositories declare a pipeline for it →
 * enqueue the runs.
 *
 * <p>It sits behind the bus rather than in it. The raw-listener bean that receives frames is {@code
 * service/…/bus/CiEventTriggerListener}, and it hands one over as an {@link Arrival} — a record of
 * four strings — so this module keeps knowing nothing about {@code eu.wohlben.qits.eventstream},
 * exactly as {@link RunAnnouncer} keeps it from knowing about the publishing half.
 *
 * <h2>The executor, and why it is not the dispatch thread and not the run worker</h2>
 *
 * <p><b>Not the dispatch thread.</b> {@code onFrame} runs on the bus's websocket worker, one frame at
 * a time for the whole subscription. Evaluation reads the git host <em>per candidate repository</em>,
 * so doing it inline would hold up every other consumer's frames for however long a git host takes —
 * and the typed {@code BuildSuccessfulListener} is on that same thread.
 *
 * <p><b>Not {@code ci-run-worker} either</b>, though it is the obvious reuse. That thread is occupied
 * by a running pipeline for minutes at a time; queueing evaluation behind it would mean an event that
 * arrived during a long build gets evaluated when the build ends, against a {@code main} that has
 * moved since. Evaluation-before-enqueue is a different latency class from run execution and gets its
 * own thread.
 *
 * <p><b>Single-threaded, though.</b> It was once a correctness rule — two evaluations of one
 * repository raced for the same bare cache on disk — and the caches are gone, so what is left is a
 * budget: one evaluation reads the git host once per candidate repository, and a thread per arriving
 * frame would point that fan-out at the host all at once. It matches the run worker's own shape.
 *
 * <p>The queue is <b>bounded</b>. An unbounded one turns a burst on the bus into heap; a bounded one
 * turns it into a WARN naming the event that was dropped, which is a thing a person can act on. At
 * {@link #QUEUE_CAPACITY} deep that is a backlog no healthy platform reaches.
 *
 * <h2>The manual trigger does not use that worker at all, and the reason was measured</h2>
 *
 * <p>{@link #evaluateNow} evaluates on the <b>caller's own thread</b> and answers what it did. Every
 * other way in is redeliverable — a bus frame that is not evaluated stays owed and the next catch-up
 * sweep offers it again — and a caller-supplied event is not: it is on no log, has no claim row and
 * nothing anywhere will ever offer it a second time. So for that one caller, "handed to a queue" and
 * "lost" are the same outcome, and the 2026-08-10 bootstrap measured it: an accepted trigger whose
 * evaluation never happened, answered 2xx, never run, with no line at any level to say so.
 *
 * <p>Two things could produce that and this closes both. The queue can be full, which {@link
 * #onEvent} reports as {@code false} — a signal the endpoint discarded. And the single worker can be
 * slow or stuck inside a git-host read, which nothing reports at all: the task simply waits in the
 * queue. Neither can reach a caller that runs the evaluation itself.
 *
 * <p><b>The cost is stated rather than hidden.</b> "One git-host fan-out at a time" is now a
 * statement about bus traffic only; a manual call fans out beside the worker. That is the right way
 * round — the budget exists to keep a burst of machine events from storming the git host, and a
 * manual trigger is one request from one person, already bounded by the HTTP worker pool.
 *
 * <h2>An accepted event is DURABLE, and that is what the enqueue used to cost</h2>
 *
 * <p>The residual window this class used to state plainly — "the claim commits when the event is
 * ACCEPTED, not when the run row exists; a crash in the gap loses that event" — is <b>closed</b>.
 * It was measured on 2026-09-04: three release requests created within a second of a qits-ci
 * redeploy were consumed without effect, their {@code ReleaseRequestChanged} claimed by the dying
 * instance and their QA runs never recorded, and with the release gate strictly requiring verdicts
 * they hung PENDING until they were withdrawn and recreated. Milliseconds are exactly as wide as a
 * cutover chooses to make them.
 *
 * <p>{@link #onEvent} now writes a {@link CiOwedEvent} row on ci's own datasource, in its own
 * transaction, <b>before</b> it reports the acceptance — and {@link #evaluateQuietly} deletes it the
 * moment the evaluation returns. The claim cannot join that transaction (it lives on the eventstream
 * datasource, and one JTA transaction does not take both), so what is made atomic is the pair that
 * can be: the acceptance is durable before it is claimable.
 *
 * <ul>
 *   <li>dying before the row commits: the accept answers {@code false}, the listener throws, the
 *       claim rolls back and the bus offers the event again — the existing retryable path.
 *   <li>dying after it and before the run row: the claim stands, and the row is the record that the
 *       event was never evaluated. {@link #sweepOwed} re-evaluates it at boot and on a schedule.
 * </ul>
 *
 * <p><b>Re-evaluating is safe by construction rather than by care</b>: {@code unique
 * (trigger_event_id, repo_id, config_path)} makes a second evaluation of an event that already
 * recorded its runs a no-op. That constraint is what lets this ledger be at-least-once.
 *
 * <p><b>What the ledger does NOT do is retry an evaluation that happened.</b> A sweep settles a row
 * whenever the evaluation returns, including one that reached no readable repository — that case is
 * the git host's, it behaves exactly as a live frame's evaluation does, and making it retryable here
 * would keep rows for a platform that simply has no candidates yet. Only a <em>throw</em> leaves the
 * row owed.
 *
 * <h2>Platform pipelines</h2>
 *
 * <p>There is a <b>second source of trigger files</b>: {@code .config/qits/ci-platform-event-*.yml}
 * in the one repository {@code qits.ci.platform-pipelines-repository} names, at its {@code main}
 * head. Such a file is parsed and selected exactly like a repository's own — same grammar, same
 * schema, same per-file containment — but the run it records is about the repository the
 * <b>payload</b> names, so one file serves the whole catalogue. See {@link #evaluatePlatform}.
 *
 * <p>It costs <b>one</b> extra listing per arriving event and no extra read per candidate: the head
 * a platform run is recorded at is the one the candidate loop already resolved for that repository.
 * A blank config key means the feature is off and nothing is read at all.
 */
@ApplicationScoped
public class CiEventTriggerService {

  private static final Logger LOG = Logger.getLogger(CiEventTriggerService.class);

  /**
   * The branch an event trigger reads, and — unless the file declares {@code checkout:} — the one
   * its run builds. The platform's one tracked branch, supplied by convention because most events
   * name no ref. A trigger with {@code checkout:} still <b>decides</b> here ("decide at main, build
   * at the event's commit"): discovery, parsing and selection read this branch's head, so a pushed
   * branch cannot alter the CI that gates it; only the recorded run's branch/sha come from the
   * payload.
   */
  public static final String TRIGGER_BRANCH = "main";

  /** Deep enough that reaching it means something is wrong rather than something is busy. */
  static final int QUEUE_CAPACITY = 256;

  /**
   * The payload field a platform pipeline's run is about. A platform trigger file names no
   * repository — it is one file for the catalogue — so the event has to, and this is the one word
   * that contract is spelled in.
   */
  static final String PAYLOAD_REPOSITORY_FIELD = "repository";

  /**
   * The two events the release cycle is made of, and the whole of what makes {@code release.yml}
   * visible at all: the extra blob read is gated on this pair, so an ordinary event costs exactly
   * what it cost before this feature existed.
   */
  private static final Set<String> RELEASE_EVENTS =
      Set.of(CiReleaseComposer.RELEASE_REQUEST_EVENT, CiReleaseComposer.RELEASE_EVENT);

  /**
   * The hand-written QA pipeline a {@code release.yml}'s {@code release-request:} slot replaces —
   * one half of the pair every unmigrated repository still commits.
   *
   * <p>Public because it is the subject of a read as well as of a supersession: {@link
   * #releaseCompositionAt} reports what this file commits today beside what a candidate slot file
   * would compose, and a second spelling of the path there would be a second thing to keep in step
   * with the supersession below.
   */
  public static final String LEGACY_RELEASE_REQUEST_PATH =
      CiEventTriggerParser.CONFIG_DIR + "ci-event-release-request.yml";

  /** The other half: the hand-written release pipeline a {@code release:} slot replaces. */
  public static final String LEGACY_RELEASE_PATH =
      CiEventTriggerParser.CONFIG_DIR + "ci-event-release.yml";

  /**
   * The two trigger files a {@code release.yml} replaces. Matched by PATH rather than by declared
   * event: these two names are the platform's own convention across all 47 repositories, and a
   * repository that keeps a bespoke {@code ci-event-*.yml} for one of the release events is
   * declaring a second pipeline on purpose — two files, two runs, exactly as the dedupe already says.
   */
  private static final Set<String> LEGACY_RELEASE_PATHS =
      Set.of(LEGACY_RELEASE_REQUEST_PATH, LEGACY_RELEASE_PATH);

  @Inject CiConfigSource configSource;
  @Inject CiEventTriggerParser triggerParser;
  @Inject CiReleaseSlotParser slotParser;
  @Inject CiReleaseArchetypes archetypes;
  @Inject CiCandidateRepos candidateRepos;
  @Inject CiRunService runService;
  @Inject CiOwedEventRepository owed;

  /**
   * How long {@link #evaluateNow} keeps asking repositories. It runs on a request thread and reads
   * the git host once per candidate, so an unanswering host would otherwise hold the caller for
   * candidates times the read timeout. Past the deadline the repositories not yet asked are reported
   * as skipped, which is the truth about them.
   */
  @ConfigProperty(name = "qits.ci.trigger-deadline-seconds")
  int triggerDeadlineSeconds;

  /**
   * How long an accepted event may sit unevaluated before the periodic sweep treats it as a
   * process's leftovers rather than as work in flight.
   *
   * <p>Generous on purpose: the sweep costs one git-host fan-out per row it picks up, and picking up
   * an evaluation this process is still running is only a duplicate rather than a defect (the dedupe
   * constraint refuses the second run). Wide enough that the ordinary case never happens, short
   * enough that a lost event is recovered in minutes rather than at the next deployment. The boot
   * sweep ignores it entirely — see {@link #onStart}.
   */
  @ConfigProperty(name = "qits.ci.trigger-owed-grace")
  Duration owedGrace;

  /**
   * The repository whose {@code ci-platform-event-*.yml} files are platform pipelines — the wrapper
   * repository by default, because that is the one repository the whole catalogue is described in.
   *
   * <p><b>Blank turns the feature off and reads nothing.</b> A deployment that declares no platform
   * repository must not pay a listing per event for a file it has decided not to have.
   */
  @ConfigProperty(name = "qits.ci.platform-pipelines-repository")
  Optional<String> configuredPlatformPipelinesRepository;

  /**
   * The repository this instance reads platform pipelines from — the config's value, normalised
   * once, and whatever a test armed after that.
   *
   * <p>{@code Optional} above and a plain string here for one reason: a property spelled as the
   * empty string reaches this process as <b>absent</b>, not as {@code ""}, so an unwrapped
   * {@code String} injection point fails the whole deployment on the very value that means "off".
   */
  private String platformPipelinesRepository = "";

  @PostConstruct
  void readPlatformPipelinesRepository() {
    platformPipelinesRepository = normalise(configuredPlatformPipelinesRepository.orElse(""));
  }

  /**
   * Arms the platform-pipelines repository for one test. A method rather than a field write, and
   * that is load-bearing: this bean is normal-scoped, so a test holds a client proxy and a field
   * write would land on the proxy and change nothing.
   */
  void platformPipelinesRepository(String repository) {
    platformPipelinesRepository = normalise(repository);
  }

  private static String normalise(String repository) {
    return repository == null ? "" : repository.trim();
  }

  /**
   * One arriving event, in this module's own vocabulary: plain strings, no bus types. {@code
   * payload} is the canonical JSON qits-events stored, verbatim — it is both what the selection is
   * evaluated against and what reaches the step containers as {@code $QITS_EVENT_PAYLOAD}.
   */
  public record Arrival(String eventId, String eventName, Instant occurredAt, String payload) {}

  /**
   * What one finished evaluation did, which is what a caller who waited for it is owed.
   *
   * @param runIds the runs this evaluation recorded. Every id is a row that exists now.
   * @param repositoriesRead how many candidate repositories answered and were evaluated
   * @param repositoriesSkipped the candidates that did not answer — the git host did not reply, the
   *     repository is gone, it has no {@code main}, or the deadline arrived before its turn. {@code
   *     EventTriggerLookup} cannot tell those apart and neither can this.
   */
  public record Evaluation(
      List<String> runIds, int repositoriesRead, List<String> repositoriesSkipped) {

    public Evaluation {
      runIds = List.copyOf(runIds);
      repositoriesSkipped = List.copyOf(repositoriesSkipped);
    }

    /**
     * Whether the engine got to ask anybody at all. {@code false} means no candidate answered — the
     * git host is unreachable, or qits-ci knows of no repository — so an empty {@link #runIds()}
     * says nothing about the event, and a caller must read it as a failure to evaluate rather than
     * as "nothing matched".
     */
    public boolean answered() {
      return repositoriesRead > 0;
    }
  }

  private final ThreadPoolExecutor evaluator =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          r -> {
            Thread t = new Thread(r, "ci-trigger-worker");
            t.setDaemon(true);
            return t;
          });

  /**
   * How long a shutdown waits for the evaluations it has already accepted.
   *
   * <p>Not the durability mechanism — {@link CiOwedEvent} is — but the difference between a cutover
   * that finishes its work and one that hands it to the successor's boot sweep. An evaluation that
   * completes here still records its {@code QUEUED} rows, and {@code CiRunService} is already
   * draining by then, so those rows are left for the successor to enqueue exactly as an
   * interrupted run's are. A fan-out slower than this is abandoned and its row stays owed, which is
   * the case the ledger exists for.
   */
  private static final int SHUTDOWN_DRAIN_SECONDS = 5;

  @PreDestroy
  void shutdown() {
    evaluator.shutdown();
    try {
      if (evaluator.awaitTermination(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    List<Runnable> abandoned = evaluator.shutdownNow();
    if (!abandoned.isEmpty()) {
      LOG.warnf(
          "%d accepted event(s) were not evaluated before shutdown; their owed rows stand and the"
              + " next sweep re-evaluates them",
          abandoned.size());
    }
  }

  /**
   * The entry the <b>bus listener</b> calls, and only it. <b>Returns immediately and never
   * throws</b>: the caller is a socket callback that is delivering to other consumers too.
   *
   * <p><b>The answer is whether the event was accepted for evaluation</b>, and it is a return value
   * rather than a swallowed WARN because the durable seam can act on it. A full queue means this
   * event was <em>not</em> evaluated, and that is a retryable condition rather than a verdict about
   * the event: {@code CiEventTriggerListener} turns a {@code false} into a failure, which leaves the
   * event owed for the next catch-up sweep instead of dropping it.
   *
   * <p><b>The manual trigger used to come through here too and must never do so again.</b> Its
   * event is on no log and has no claim, so "accepted" is a promise nothing can keep for it — see
   * {@link #evaluateNow} and the class javadoc. Everything that reaches this method has a
   * redelivery channel behind it.
   *
   * <p>A malformed arrival — no id, no name — also answers {@code false}, and it is the caller's job
   * to tell the two apart. The listener does, by checking the frame before it gets here.
   *
   * <p><b>The acceptance is written down before it is reported</b>, which is what makes a {@code
   * true} here worth what the funnel takes it for. A {@link CiOwedEvent} row commits on ci's own
   * datasource first; only then is the event queued and only then is {@code true} answered. An event
   * that could not be recorded is <em>not accepted</em> — {@code false}, so the claim rolls back and
   * the bus offers it again — because an acceptance nothing durable knows about is exactly the
   * promise this method used to break across a cutover.
   */
  public boolean onEvent(Arrival arrival) {
    if (arrival == null || arrival.eventId() == null || arrival.eventName() == null) {
      return false;
    }
    if (!recordOwed(arrival)) {
      return false;
    }
    try {
      evaluator.execute(() -> evaluateQuietly(arrival));
      return true;
    } catch (RejectedExecutionException full) {
      // Either the queue is genuinely backed up or the process is shutting down. Both are worth a
      // line naming the event, because the event is simply not evaluated and nothing else will say so.
      // The owed row goes with the refusal: this event stays the BUS's to redeliver, and leaving the
      // row would make the sweep evaluate what the next offer is going to evaluate anyway.
      settle(arrival.eventId());
      LOG.warnf(
          "Trigger evaluation queue is full — event %s (%s) was not evaluated",
          arrival.eventId(), arrival.eventName());
      return false;
    }
  }

  /**
   * Writes the acceptance down, or answers {@code false} so the caller refuses the event.
   *
   * <p>Its own transaction, because the caller's is the durable funnel's claim on another
   * datasource, and one JTA transaction does not take both ({@code Enlisted connection used without
   * active transaction} — measured; {@code ScmReleaseListener} runs the same arrangement for the same
   * reason). An existing row is success: a redelivery that reached the accept again is owed once, not
   * twice.
   */
  private boolean recordOwed(Arrival arrival) {
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  owed.record(
                      arrival.eventId(),
                      arrival.eventName(),
                      arrival.occurredAt(),
                      arrival.payload()));
      return true;
    } catch (RuntimeException notRecorded) {
      // Loud and refused. Accepting an event this process cannot say it accepted is the failure the
      // ledger exists to prevent, so the honest answer is to leave it owed on the bus instead.
      LOG.errorf(
          notRecorded,
          "Event %s (%s) could not be recorded as owed; refusing it so the bus offers it again",
          arrival.eventId(),
          arrival.eventName());
      return false;
    }
  }

  /**
   * Clears one owed row: this event has been evaluated and nothing is owed for it.
   *
   * <p>A failure here is a WARN and nothing else. The row stays, the next sweep re-evaluates the
   * event, and the dedupe constraint makes that a no-op — an unclearable row costs a fan-out, where
   * throwing would cost the evaluation that already happened.
   */
  private void settle(String eventId) {
    try {
      QuarkusTransaction.requiringNew().run(() -> owed.forget(eventId));
    } catch (RuntimeException notSettled) {
      LOG.warnf(
          "Event %s was evaluated but its owed row could not be cleared; a sweep will re-evaluate"
              + " it, which the dedupe makes a no-op: %s",
          eventId, notSettled.toString());
    }
  }

  /**
   * The entry the manual-trigger endpoint calls: <b>the same evaluation, on this thread</b>, with
   * what it did handed back.
   *
   * <p>It shares no queue and no worker with {@link #onEvent}, which is the whole point — see the
   * class javadoc. It throws whatever the evaluation throws; the caller owes its own caller a
   * non-2xx for that, because an event nobody can redeliver must never be answered "accepted" by a
   * process that did not evaluate it.
   */
  public Evaluation evaluateNow(Arrival arrival) {
    return evaluateNow(arrival, null);
  }

  /**
   * The same evaluation, narrowed to <b>one project's</b> repositories.
   *
   * <p>{@code projectScope} null is every project, which is what an operator's forwarded session and
   * a token granted {@code project=*} get. A non-null value is a machine caller's own project, and
   * the narrowing is the whole of what admits it: the candidates are the ones the catalogue says
   * belong to that project, so a repository of another project is not evaluated, cannot match and
   * cannot be made to run — refused <em>by construction</em> rather than by a check that has to be
   * remembered. See {@link #inProject} for what "the catalogue says" is worth, and {@code
   * CiEventController} for the guard that decides the value.
   *
   * @throws NoRepositoriesInProject when the catalogue holds repositories but none of them is that
   *     project's — a definite refusal for the caller, and never confused with "nothing could be
   *     read", which stays an unanswered evaluation
   */
  public Evaluation evaluateNow(Arrival arrival, String projectScope) {
    return evaluate(
        arrival, System.nanoTime() + SECONDS.toNanos(triggerDeadlineSeconds), projectScope);
  }

  /**
   * A scoped evaluation whose scope names no repository this instance can place in it.
   *
   * <p>A plain {@link RuntimeException} rather than anything web-shaped, for the reason every other
   * type in this module is: the adapter turns it into a status code (403 — the token covers nothing
   * here), and {@code ci} stays free of JAX-RS.
   */
  public static class NoRepositoriesInProject extends RuntimeException {

    private final transient String project;

    NoRepositoriesInProject(String project) {
      super("no repository qits-ci can name belongs to project " + project);
      this.project = project;
    }

    /** The scope that matched nothing — what a caller is told about its own token. */
    public String project() {
      return project;
    }
  }

  private void evaluateQuietly(Arrival arrival) {
    try {
      evaluate(arrival);
    } catch (RuntimeException e) {
      // The owed row is deliberately NOT settled here: a throw is the one outcome a later sweep can
      // improve on, and the dedupe makes re-evaluating whatever did get recorded a no-op.
      LOG.errorf(
          e,
          "Evaluating triggers for event %s failed unexpectedly; it stays owed for the next sweep",
          arrival.eventId());
      return;
    }
    settle(arrival.eventId());
  }

  /**
   * The synchronous evaluation with no deadline — package-private so tests drive it without the
   * executor, and what the trigger worker runs. The worker has all the time it needs: nobody is
   * holding a request open for it, and a candidate it gives up on is a run that never happens.
   */
  Evaluation evaluate(Arrival arrival) {
    return evaluate(arrival, null, null);
  }

  private Evaluation evaluate(Arrival arrival, Long deadlineNanos, String projectScope) {
    JsonNode payload = CiEventSelectionEvaluator.parsePayload(arrival.payload());
    List<CiRepoRef> catalogue = candidateRepos.candidates();
    if (catalogue.isEmpty()) {
      LOG.debugf("No candidate repositories for event %s — nothing to evaluate", arrival.eventName());
      return new Evaluation(List.of(), 0, List.of());
    }
    List<CiRepoRef> candidates = inProject(catalogue, projectScope);
    if (candidates.isEmpty()) {
      // Only reachable with a scope: an empty catalogue answered above. This instance holds
      // repositories and none of them is the caller's, which is an answer about the caller rather
      // than about the git host — so it is a refusal and not an unanswered evaluation.
      throw new NoRepositoriesInProject(projectScope);
    }
    List<String> runIds = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    // The head each candidate answered with, kept for the platform pass: a platform run is recorded
    // against the repository the payload names, at the commit that repository's main was on for THIS
    // evaluation. Reading it again would be a second read of a branch that may have moved.
    Map<String, String> heads = new HashMap<>();
    // Resolved once for the whole evaluation and used twice: the platform pass reads its trigger
    // files out of it, and a candidate's release.yml reads its archetype recipe out of it. One
    // catalogue lookup, no extra listing.
    CiRepoRef platformRepo = platformRepo(candidates);
    for (CiRepoRef repo : candidates) {
      if (deadlineNanos != null && System.nanoTime() - deadlineNanos >= 0) {
        // Out of time rather than out of answers, and the two must not look alike to the caller —
        // so the repository goes on the skipped list like any other one that could not be asked.
        skipped.add(repo.repoId());
        continue;
      }
      try {
        if (!evaluateRepo(repo, arrival, payload, runIds, heads, platformRepo)) {
          skipped.add(repo.repoId());
        }
      } catch (RuntimeException e) {
        // One repository's failure never costs the others theirs. Same containment the per-file
        // parse below has, one level up.
        LOG.warnf(e, "Could not evaluate event triggers for %s", repo.display());
        skipped.add(repo.repoId());
      }
    }
    if (projectScope == null) {
      try {
        evaluatePlatform(arrival, payload, candidates, platformRepo, heads, runIds);
      } catch (RuntimeException e) {
        // Never out of the evaluation: the candidates' own runs are already recorded and a platform
        // pipeline's failure is not theirs.
        LOG.warnf(e, "Could not evaluate platform triggers for event %s", arrival.eventId());
      }
    } else {
      // A platform pipeline is ONE repository's file acting on the whole catalogue, so firing one is
      // a platform-wide act and the honest grant for it is every project. A project-scoped caller
      // gets its own repositories' triggers and nothing else. Widening this later is additive; a
      // scope that could reach the platform files would not be.
      LOG.debugf(
          "Event %s was evaluated for project %s only — platform pipelines are not part of a scoped"
              + " evaluation",
          arrival.eventId(), projectScope);
    }
    if (!skipped.isEmpty() && skipped.size() == candidates.size()) {
      // Every candidate silent means the answer is about the git host, not about the event. WARN
      // once for the whole evaluation, where the per-repository lines stay at DEBUG on purpose.
      LOG.warnf(
          "No candidate repository could be read for event %s (%s) — nothing was evaluated",
          arrival.eventId(), arrival.eventName());
    }
    return new Evaluation(runIds, candidates.size() - skipped.size(), skipped);
  }

  /**
   * The candidates one scope covers: everything when the scope is null, and otherwise the ones the
   * catalogue places in that project.
   *
   * <p><b>A candidate whose project qits-ci cannot name is in no scope at all</b>, and that is the
   * fail-closed half of the rule rather than an oversight. Two kinds of candidate have no {@code
   * projectId}: one qits-ci knows only from its own run rows ({@code KnownCiRepos}), and every one of
   * them when the qits-projects listing is unreachable and the git host's id-only listing is all
   * there is. In both cases the honest answer is "this instance cannot prove that repository is
   * yours", so a scoped caller does not reach it — an unreachable listing narrows a scoped
   * evaluation to nothing (and its caller is told so) rather than quietly widening it to everybody.
   * An unscoped evaluation is unaffected: a read failure must never shrink the candidate set.
   */
  private static List<CiRepoRef> inProject(List<CiRepoRef> catalogue, String projectScope) {
    if (projectScope == null) {
      return catalogue;
    }
    List<CiRepoRef> scoped = new ArrayList<>();
    for (CiRepoRef repo : catalogue) {
      if (projectScope.equals(repo.projectId())) {
        scoped.add(repo);
      }
    }
    return scoped;
  }

  /**
   * Evaluates one repository. {@code false} means it could not be read, which is not "no match".
   *
   * <p>The reference travels rather than an id: the trigger files are read name-addressed when the
   * candidate carries a public coordinate, and id-addressed when it does not.
   */
  private boolean evaluateRepo(
      CiRepoRef repo,
      Arrival arrival,
      JsonNode payload,
      List<String> runIds,
      Map<String, String> heads,
      CiRepoRef platformRepo) {
    String repoId = repo.display();
    EventTriggerLookup lookup =
        configSource.readEventTriggers(repo, TRIGGER_BRANCH, CiTriggerScope.REPOSITORY);
    if (lookup.status() != EventTriggerLookup.Status.FOUND) {
      // DEBUG rather than WARN: the candidate list is "every repository ci has ever heard of", so a
      // deleted repository or one with no main would otherwise warn once per repo per event forever.
      LOG.debugf("Could not read %s@%s for triggers", repoId, TRIGGER_BRANCH);
      return false;
    }
    heads.put(repo.repoId(), lookup.headSha());
    ReleaseSlots slots = releaseSlots(repo, repoId, arrival, lookup.headSha(), platformRepo);
    List<String> superseded = new ArrayList<>();
    for (EventTriggerFile file : lookup.files()) {
      if (slots.present() && LEGACY_RELEASE_PATHS.contains(file.path())) {
        superseded.add(file.path());
        continue;
      }
      evaluateTrigger(repo, repoId, file.path(), file.content(), arrival, payload, lookup, runIds);
    }
    if (!superseded.isEmpty()) {
      // WARN and never a parse error: a repository mid-migration legitimately carries both for one
      // release, and the one thing that must not happen is the legacy file firing BESIDE the composed
      // one — two runs for one release, only one of which anybody meant. Naming both paths is what
      // makes the window readable from a log rather than from this source file.
      LOG.warnf(
          "%s: %s is the release pipeline for %s — the legacy trigger file(s) %s were not evaluated",
          repoId, CiReleaseSlotParser.CONFIG_PATH, arrival.eventName(), superseded);
    }
    if (slots.document() != null) {
      evaluateTrigger(
          repo,
          repoId,
          CiReleaseSlotParser.CONFIG_PATH,
          slots.document(),
          arrival,
          payload,
          lookup,
          runIds);
    }
    return true;
  }

  /**
   * One trigger document — a file the repository committed, or one composed from its release slots —
   * matched, selected, resolved and enqueued.
   *
   * <p>Extracted so a composed document goes through <b>exactly</b> the path a committed one does:
   * the same parser, the same {@code when:} evaluation, the same checkout resolution with its
   * validation and its optional-checkout fallback, the same run row. A composed pipeline that took a
   * shortcut anywhere in here would be a second engine to keep in step with this one.
   */
  private void evaluateTrigger(
      CiRepoRef repo,
      String repoId,
      String configPath,
      String content,
      Arrival arrival,
      JsonNode payload,
      EventTriggerLookup lookup,
      List<String> runIds) {
    EventTriggerFile file = new EventTriggerFile(configPath, content);
    {
      CiEventTrigger trigger;
      try {
        trigger = triggerParser.parse(file.path(), file.content());
      } catch (CiConfigException e) {
        // Loud, naming repository and file — a trigger that cannot be parsed must not silently never
        // fire — and per file: the repository's OTHER trigger files are evaluated regardless.
        LOG.warnf("%s: %s is not a usable event trigger: %s", repoId, file.path(), e.getMessage());
        return;
      }
      if (!trigger.eventName().equals(arrival.eventName())) {
        return;
      }
      if (!CiEventSelectionEvaluator.matches(trigger.selection(), payload)) {
        LOG.debugf(
            "%s: %s declares %s but its selection did not match event %s",
            repoId, file.path(), trigger.eventName(), arrival.eventId());
        return;
      }
      // Absent checkout: today's behavior byte-for-byte — the run builds main's head. Declared,
      // the ref and sha come out of the payload instead; the trigger DECIDED at main above.
      String branch = TRIGGER_BRANCH;
      String sha = lookup.headSha();
      // The trigger AS THIS RUN IS ACCEPTED UNDER, which is the declared one except on the
      // compatibility arm below — see there for why the difference has to be carried rather than
      // merely logged.
      CiEventTrigger accepted = trigger;
      if (trigger.checkout() != null) {
        String declaredBranch = checkoutField(payload, trigger.checkout().branchPath());
        String declaredSha = checkoutField(payload, trigger.checkout().shaPath());
        if (declaredBranch != null && declaredSha != null) {
          // The payload is attacker-shaped (the untrusted-input doctrine): both values reach a
          // clone URL and an argv, so they are validated HERE, inside the per-file containment —
          // letting the refusal escape would trip the per-repo catch and mark the whole repository
          // skipped. A ref name is what requireBranch checks, and a TAG name is a ref name: a
          // release recipe pointing `branch` at the event's `version` passes this gate for the same
          // reason `git clone --branch` takes a tag, and the engine needs to know nothing about tags
          // for that to be true.
          try {
            CiIdentifiers.requireBranch(declaredBranch);
            CiIdentifiers.requireSha(declaredSha);
          } catch (RuntimeException refused) {
            LOG.warnf(
                "%s: %s checkout refused for event %s: %s",
                repoId, file.path(), arrival.eventId(), refused.getMessage());
            return;
          }
          branch = declaredBranch;
          sha = declaredSha;
        } else if (trigger.checkout().optional()) {
          // THE COMPATIBILITY ARM, and the whole reason `optional:` exists. The event does not carry
          // the coordinate this file would rather build — an SCMRelease published before `commitSha`
          // existed, a replay of one, an older publisher — so the run falls back to exactly what
          // this file did before it declared a checkout at all: main's head, with the step script
          // left to find the released tree itself. INFO rather than WARN: this is a supported shape
          // of the event, not a fault, and it stops happening on its own.
          //
          // The trigger is handed on WITHOUT its checkout, which is the point and not bookkeeping.
          // Everything downstream that asks "does this run follow the event's own ref?" must get the
          // pre-checkout answer here, because that is the run this is — above all the per-ref burst
          // collapse in CiRunService, which is correct for payload-resolved refs and WRONG for the
          // "main" convention: two distinct release events falling back would share the ref and the
          // older one would be deduped away, publishing no image for a version that really released.
          // Rewriting the value is how that stays true of every such question, including ones added
          // later, rather than of the one we remembered.
          accepted = trigger.withoutCheckout();
          LOG.infof(
              "%s: %s declares an optional checkout { %s, %s } and event %s (%s) does not carry it"
                  + " — the run is recorded at %s's head, as it was before the checkout",
              repoId,
              file.path(),
              trigger.checkout().branchPath(),
              trigger.checkout().shaPath(),
              arrival.eventId(),
              arrival.eventName(),
              TRIGGER_BRANCH);
        } else {
          // One WARN and no run: there is no truthful (ref, sha) pair to record a row against, and
          // a read failure is not a run. Per file — the repository's other triggers still evaluate.
          LOG.warnf(
              "%s: %s declares checkout { %s, %s } but event %s (%s) does not carry both — no run",
              repoId,
              file.path(),
              trigger.checkout().branchPath(),
              trigger.checkout().shaPath(),
              arrival.eventId(),
              arrival.eventName());
          return;
        }
      }
      LOG.infof(
          "Event %s (%s) matched %s in %s — enqueuing a run at %s@%s",
          arrival.eventId(), arrival.eventName(), file.path(), repoId, branch, sha);
      String runId =
          runService.onEventTrigger(
              new CiRunService.EventRun(
                  repo,
                  branch,
                  sha,
                  accepted,
                  arrival.eventId(),
                  arrival.eventName(),
                  arrival.occurredAt(),
                  arrival.payload(),
                  file.content()));
      if (runId != null) {
        runIds.add(runId);
      }
    }
  }

  // --- the release slot file, and what it supersedes ----------------------------------------------

  /**
   * What {@code .config/qits/release.yml} means for ONE candidate and ONE arriving event.
   *
   * @param present the file is there, so the legacy release trigger files are superseded whatever
   *     else happened. It is a separate fact from the document on purpose: a slot file that names an
   *     unreadable archetype, or that will not parse, still supersedes — a repository that has
   *     migrated must not silently fall back to files it has already stopped maintaining.
   * @param document the composed trigger document for this event, or null when there is none to run
   */
  private record ReleaseSlots(boolean present, String document) {

    static final ReleaseSlots NONE = new ReleaseSlots(false, null);

    static final ReleaseSlots NO_RUN = new ReleaseSlots(true, null);
  }

  /**
   * Reads, resolves and compiles a candidate's release slots.
   *
   * <p><b>Gated on the two release event names</b>, which is the whole of what this feature costs an
   * ordinary event: nothing. A {@code BuildSuccessful} evaluates exactly the reads it always did.
   *
   * <p><b>Read at the head the trigger listing just resolved</b>, never at the branch again — the
   * listing's own discipline, for the listing's own reason: a run must never be recorded against one
   * commit with a declaration from another.
   *
   * <p><b>An unreadable read falls back to the legacy files, and a MISSING one is not the same
   * thing.</b> {@code ABSENT} is a 404 at a rev the host has already resolved, so it is the honest
   * "this repository has not migrated" and is every repository today. {@code UNREACHABLE} is a blip,
   * and treating it as "release.yml exists" would cost a release request its QA verdict — the
   * failure the owed-event ledger exists to end — whereas treating it as absent costs a migrated
   * repository nothing at all, since it has no legacy file left for the fallback to find.
   */
  private ReleaseSlots releaseSlots(
      CiRepoRef repo, String repoId, Arrival arrival, String headSha, CiRepoRef platformRepo) {
    if (!RELEASE_EVENTS.contains(arrival.eventName())) {
      return ReleaseSlots.NONE;
    }
    CiConfigSource.FileLookup found =
        configSource.readFile(repo, headSha, CiReleaseSlotParser.CONFIG_PATH);
    if (found.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
      LOG.warnf(
          "%s: %s could not be read at %s — this evaluation falls back to the legacy release trigger"
              + " files",
          repoId, CiReleaseSlotParser.CONFIG_PATH, headSha);
      return ReleaseSlots.NONE;
    }
    if (found.status() != CiConfigSource.FileLookup.Status.FOUND) {
      return ReleaseSlots.NONE;
    }
    CiReleaseComposer.Composed composed =
        compose(repo, repoId, found.content(), platformRepo, "no release run");
    return composed == null
        ? ReleaseSlots.NO_RUN
        : new ReleaseSlots(true, documentFor(composed, arrival.eventName()));
  }

  /**
   * Parses one repository's slot file, reads whatever archetype it names at the wrapper's {@code
   * main}, and compiles the pair — or answers null, having already said which of the three ways it
   * failed.
   *
   * <p><b>The consequence is the caller's to state and travels in as a word</b>, because the two
   * callers do different things with a null: an evaluation records no run at all, while a retry
   * replays the pipeline stored on the run it re-fires. A shared WARN that named only one of them
   * would be a log line that is wrong half the time.
   */
  private CiReleaseComposer.Composed compose(
      CiRepoRef repo,
      String repoId,
      String slotFile,
      CiRepoRef platformRepo,
      String consequence) {
    return attemptCompose(repo, repoId, slotFile, platformRepo, consequence).composed();
  }

  /**
   * Which of the four ways a composition attempt ended. The three failures are one {@code null} to
   * {@link #compose}, and they are told apart here for the two callers that must not collapse them —
   * see {@link #releasePhaseAt} and {@link #releaseCompositionAt}.
   *
   * <p><b>Public rather than private, and that is a choice against duplication.</b> The read surface
   * has to say which of the three failures it hit — "your file is broken" and "qits-ci could not
   * read the wrapper" are a 200 and a 503 — and the alternative to widening this enum was a second
   * vocabulary in the adapter that means the same four things. Two vocabularies for one outcome is
   * the drift this class spends a paragraph avoiding everywhere else.
   */
  public enum ComposeOutcome {
    /** Parsed, resolved and compiled. The {@code Composed} pair is there, either half may be null. */
    COMPOSED,
    /** The repository's own {@code release.yml} is not a usable slot file. */
    UNPARSEABLE,
    /** The archetype it names could not be read from the wrapper repository. */
    ARCHETYPE_UNREADABLE,
    /** Parsed and resolved, but the pair cannot be compiled into a pipeline. */
    UNCOMPOSABLE
  }

  /**
   * One composition attempt: what came of it, the pair when there is one, every artifact either
   * document declared, and the sentence a caller can put in front of a person.
   *
   * <p>The detail is built here rather than at the call site because it is the same fact the WARN
   * already names — the file, the archetype, the parser's own message — and a second spelling of it
   * would be a second thing to keep in step with the log.
   *
   * <p><b>Public for {@link ComposeOutcome}'s reason</b>, and {@code declaredArtifacts} exists for
   * exactly one reader. A composed {@code artifacts:} block carries {@code {type, name}} and never
   * the {@code sbom:} path — the trigger schema is strict about unknown keys, so the path is spent
   * on a postlude line instead — and {@link #releaseCompositionAt} has to report the path a person
   * wrote down. It is a <b>lookup table by coordinate</b> rather than a resolved list: the slot
   * file's declarations followed by the archetype's, with the whole-slot override deliberately not
   * restated here, because restating it would be a second copy of a rule {@link CiReleaseComposer}
   * owns. Reading an sbom path out of it can only ever name the document that really declared it.
   *
   * @param declaredArtifacts empty on every failing outcome, since nothing was parsed to declare
   */
  public record ComposeAttempt(
      ComposeOutcome outcome,
      CiReleaseComposer.Composed composed,
      List<CiReleaseSlots.SlotArtifact> declaredArtifacts,
      String detail) {

    public ComposeAttempt {
      declaredArtifacts = List.copyOf(declaredArtifacts);
    }
  }

  /**
   * Parses one repository's slot file, reads whatever archetype it names at the wrapper's {@code
   * main}, and compiles the pair — saying which of the three ways it failed rather than only that it
   * did.
   *
   * <p><b>Extracted from {@link #compose} rather than copied.</b> The two evaluation callers want a
   * null and a WARN; the release-phase read wants the distinction, because "this repository's
   * pipeline is broken" and "qits-ci could not read the wrapper" are opposite answers there — one is
   * a pipeline somebody must fix, the other is a question this instance could not ask at all. A
   * second copy of the parse/read/compile sequence would be a second place for the archetype branch
   * to drift.
   */
  private ComposeAttempt attemptCompose(
      CiRepoRef repo,
      String repoId,
      String slotFile,
      CiRepoRef platformRepo,
      String consequence) {
    CiReleaseSlots slots;
    try {
      slots = slotParser.parse(CiReleaseSlotParser.CONFIG_PATH, slotFile);
    } catch (CiConfigException e) {
      LOG.warnf(
          "%s: %s is not a usable release slot file: %s — %s",
          repoId, CiReleaseSlotParser.CONFIG_PATH, e.getMessage(), consequence);
      return new ComposeAttempt(
          ComposeOutcome.UNPARSEABLE,
          null,
          List.of(),
          CiReleaseSlotParser.CONFIG_PATH + " is not a usable release slot file: " + e.getMessage());
    }
    CiReleaseSlots archetype = null;
    if (slots.namesArchetype()) {
      Optional<CiReleaseArchetypes.Archetype> recipe =
          archetypes.read(platformRepo, TRIGGER_BRANCH, slots.archetype());
      if (recipe.isEmpty()) {
        // CiReleaseArchetypes has already said which of the four ways it failed; this line is what
        // names the repository that asked, which that class deliberately does not hold.
        LOG.warnf(
            "%s: %s names release archetype '%s', which could not be read — %s",
            repoId, CiReleaseSlotParser.CONFIG_PATH, slots.archetype(), consequence);
        return new ComposeAttempt(
            ComposeOutcome.ARCHETYPE_UNREADABLE,
            null,
            List.of(),
            CiReleaseSlotParser.CONFIG_PATH
                + " names release archetype '"
                + slots.archetype()
                + "', which could not be read from the platform-pipelines repository");
      }
      archetype = recipe.get().slots();
    }
    try {
      return new ComposeAttempt(
          ComposeOutcome.COMPOSED,
          CiReleaseComposer.compose(repo, slots, archetype),
          declarations(slots, archetype),
          null);
    } catch (CiConfigException e) {
      LOG.warnf(
          "%s: %s could not be composed into a release pipeline: %s — %s",
          repoId, CiReleaseSlotParser.CONFIG_PATH, e.getMessage(), consequence);
      return new ComposeAttempt(
          ComposeOutcome.UNCOMPOSABLE,
          null,
          List.of(),
          CiReleaseSlotParser.CONFIG_PATH
              + " could not be composed into a release pipeline: "
              + e.getMessage());
    }
  }

  /**
   * Every {@code artifacts:} entry either document declared, the slot file's first.
   *
   * <p>Not the <em>effective</em> list — see {@link ComposeAttempt#declaredArtifacts()}. It is read
   * by coordinate and never by position, so a repository whose archetype declares the same artifact
   * it does finds its own {@code sbom:} path first, which is the same precedence the override has
   * without this method having to know that the override exists.
   */
  private static List<CiReleaseSlots.SlotArtifact> declarations(
      CiReleaseSlots slots, CiReleaseSlots archetype) {
    if (archetype == null) {
      return slots.artifacts();
    }
    List<CiReleaseSlots.SlotArtifact> all = new ArrayList<>(slots.artifacts());
    all.addAll(archetype.artifacts());
    return all;
  }

  /**
   * Which half of a composed pair one event runs. Extracted rather than copied, because the retry
   * path has to make the same choice and a second spelling of it would be a second place for the two
   * release events to drift apart.
   */
  private static String documentFor(CiReleaseComposer.Composed composed, String eventName) {
    return CiReleaseComposer.RELEASE_REQUEST_EVENT.equals(eventName)
        ? composed.releaseRequestDocument()
        : composed.releaseDocument();
  }

  /**
   * Re-composes a composed release run's pipeline with <b>today's</b> platform prelude and postlude,
   * or answers null when it cannot be had.
   *
   * <p><b>Why a retry does not simply replay its snapshot.</b> A run whose {@code config_path} is
   * {@link CiReleaseSlotParser#CONFIG_PATH} carries a stored document that is half the repository's
   * and half the platform's: the repository declared the script, and qits-ci wrapped it in a prelude
   * and a postlude. The wrapper is <em>environment</em>, not content — a fix to it is a fix to 47
   * repositories at once — so a retry composed before that fix would re-run the broken prelude and
   * fail again for a reason nobody can act on. Measured 2026-09-13 on qits-coding-agents, whose
   * failed publish would have failed identically on retry hours after the prelude was fixed. The
   * repository's half does not move: the slot file is read at the <b>ref the source run built</b>,
   * whose bytes are part of the released commit, so a re-composition changes exactly the platform's
   * share of the document and nothing the repository wrote.
   *
   * <p><b>Every failure answers null and the caller falls back to the stored snapshot.</b> The ref
   * is unreadable, {@code release.yml} is gone from it, the archetype cannot be read, the
   * composition throws — in each case a retry of the old document is a worse answer than no retry at
   * all, since the run being re-fired is the one thing the caller definitely has. Each is a WARN
   * naming the reason, so a retry that quietly kept the old prelude is readable from the log.
   *
   * @param repo the repository the run was recorded against
   * @param rev the run's own commit — never a branch name, which moves
   * @param eventName the run's triggering event, which picks the QA half or the release half
   */
  public String recomposedReleaseDocument(CiRepoRef repo, String rev, String eventName) {
    if (!RELEASE_EVENTS.contains(eventName)) {
      // A composed document exists only for the two release events; anything else on this path is a
      // row nobody composed, so there is nothing to re-derive.
      return null;
    }
    String repoId = repo.display();
    CiConfigSource.FileLookup found =
        configSource.readFile(repo, rev, CiReleaseSlotParser.CONFIG_PATH);
    if (found.status() != CiConfigSource.FileLookup.Status.FOUND) {
      LOG.warnf(
          "%s: %s could not be read at %s (%s) — this retry replays the pipeline stored on the run"
              + " it re-fires",
          repoId, CiReleaseSlotParser.CONFIG_PATH, rev, found.status());
      return null;
    }
    CiReleaseComposer.Composed composed =
        compose(
            repo,
            repoId,
            found.content(),
            platformRepo(candidateRepos.candidates()),
            "this retry replays the pipeline stored on the run it re-fires");
    return composed == null ? null : documentFor(composed, eventName);
  }

  /**
   * Whether a repository's composed release cycle has a <b>release phase</b> at one rev — three
   * answers, never two.
   *
   * <h2>Why a caller cannot work this out for itself</h2>
   *
   * <p>qits-projects decides whether a released tag is publish-gated by reading that tag's {@code
   * .config/qits/release.yml} and asking whether it names an {@code archetype:}. That question is
   * answerable from the file; the one it stands in for is not. A repository may declare its own
   * {@code release:} slot on top of an archetype that has none, and {@link CiReleaseComposer}'s
   * whole-slot override means the composition — not the file — is what says whether a release run
   * exists. qits-ci is the composer, so qits-ci is the only component that can answer.
   *
   * <h2>Why three answers and not two</h2>
   *
   * <p>A boolean would have to give a failure a side, and both sides are wrong. Answering "declared"
   * for a read that did not happen hangs a release request behind a PUBLISH gate whose run nobody
   * will ever record — which is the failure this read exists to end, in the other direction.
   * Answering "not declared" waves a release through whose pipeline was never composed, so nothing
   * checks the publish that was supposed to happen. {@link Verdict#UNKNOWN} is what the caller
   * retries on, and it is reserved for the question not having been asked at all: the repository is
   * not in this instance's candidate catalogue, the slot file's read came back {@code UNREACHABLE},
   * or the archetype it names could not be read from the wrapper repository.
   *
   * <h2>Why a broken pipeline is DECLARED</h2>
   *
   * <p>A slot file that will not parse, and a pair that will not compile, both answer {@link
   * Verdict#DECLARED}. The asymmetry with the reads above is deliberate: those are facts about the
   * repository's own committed bytes, and waiting on a pipeline somebody has to fix is recoverable —
   * the fix is a commit, the request finalizes afterwards. Waving through a release whose pipeline
   * was never checked is not recoverable, because nothing downstream re-asks. {@code detail} says
   * which of the two it was, so a person looking at a stuck gate is told the file is broken rather
   * than left to infer it.
   *
   * <h2>Two things the answer is NOT about</h2>
   *
   * <p><b>The repository's half is read at {@code rev}, the platform's at the wrapper's {@code
   * main}</b> — {@link #recomposedReleaseDocument}'s split, for its reason. So this is an answer
   * about the pipeline <em>as it composes now</em>, not as it composed when the tag was cut: an
   * archetype that gains or loses its {@code release:} slot changes what this read says about a tag
   * whose own bytes never moved. That is the wanted direction, since the run that would satisfy the
   * gate would be composed now too.
   *
   * <p><b>{@code false} is a real answer and not an absence.</b> {@code spa-frontend} and {@code
   * cli} declare no {@code release:} slot on purpose, and a rev with no {@code release.yml} at all
   * composes nothing — at an immutable tag that is honest rather than provisional. Both are {@link
   * Verdict#NOT_DECLARED}, and neither is ever reached from a read that failed.
   *
   * <p><b>One edge of that is worth knowing rather than guarding.</b> {@code HttpGitConfigSource}
   * maps every 404 to {@code ABSENT} and nothing else — it reads at revs its callers have already
   * had resolved — so a {@code rev} that does not resolve at all comes back as "no {@code
   * release.yml} here" rather than as a failure. The catalogue check above is what keeps that from
   * mattering: the repository is known, and the caller asks about a tag it has just released.
   *
   * @param repositoryId the repository, by public name or by storage id — {@link #find}'s two arms
   * @param rev a git rev the host can resolve, in practice {@code refs/tags/<version>}
   */
  public ReleasePhase releasePhaseAt(String repositoryId, String rev) {
    if (repositoryId == null || repositoryId.isBlank()) {
      return new ReleasePhase(Verdict.UNKNOWN, "No repository was named");
    }
    // One listing, read once and used for both lookups — the evaluation path's own rule, and here it
    // is also what keeps the repository and the wrapper resolved against the same catalogue.
    List<CiRepoRef> candidates = candidateRepos.candidates();
    CiRepoRef repo = find(candidates, repositoryId);
    if (repo == null) {
      // Not a NOT_DECLARED: an empty or unreachable catalogue looks exactly like this, and the
      // candidate list's standing rule is that a read failure never shrinks the set observably.
      return new ReleasePhase(
          Verdict.UNKNOWN,
          "Repository " + repositoryId + " is not in this qits-ci's candidate catalogue");
    }
    String repoId = repo.display();
    CiConfigSource.FileLookup found =
        configSource.readFile(repo, rev, CiReleaseSlotParser.CONFIG_PATH);
    if (found.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
      return new ReleasePhase(
          Verdict.UNKNOWN,
          repoId + ": " + CiReleaseSlotParser.CONFIG_PATH + " could not be read at " + rev);
    }
    if (found.status() != CiConfigSource.FileLookup.Status.FOUND) {
      return new ReleasePhase(
          Verdict.NOT_DECLARED,
          repoId
              + " declares no "
              + CiReleaseSlotParser.CONFIG_PATH
              + " at "
              + rev
              + ", so nothing composes and there is no release run to wait for");
    }
    ComposeAttempt attempt =
        attemptCompose(
            repo,
            repoId,
            found.content(),
            platformRepo(candidates),
            "this release-phase read answers on which failure it was");
    return switch (attempt.outcome()) {
      case UNPARSEABLE, UNCOMPOSABLE ->
          new ReleasePhase(
              Verdict.DECLARED,
              repoId
                  + ": "
                  + attempt.detail()
                  + " — reported as declared, because a pipeline that must be fixed is recoverable"
                  + " and a release waved through unchecked is not");
      case ARCHETYPE_UNREADABLE -> new ReleasePhase(Verdict.UNKNOWN, repoId + ": " + attempt.detail());
      case COMPOSED ->
          attempt.composed().releaseDocument() != null
              ? new ReleasePhase(
                  Verdict.DECLARED,
                  repoId
                      + ": "
                      + CiReleaseSlotParser.CONFIG_PATH
                      + " at "
                      + rev
                      + " composes a release pipeline")
              : new ReleasePhase(
                  Verdict.NOT_DECLARED,
                  repoId
                      + ": "
                      + CiReleaseSlotParser.CONFIG_PATH
                      + " at "
                      + rev
                      + " composes no release phase — neither it nor its archetype declares any"
                      + " 'release' step");
    };
  }

  /**
   * What {@link #releasePhaseAt} answered, and the sentence behind it.
   *
   * <p>The detail is part of the contract rather than a log line: the caller puts it in front of a
   * person who is looking at a release request that is either gated or not, and "why" is the only
   * thing the verdict itself cannot carry.
   */
  public record ReleasePhase(Verdict verdict, String detail) {}

  /** The three answers. Collapsing {@link #UNKNOWN} into either of the others is the bug. */
  public enum Verdict {
    /** The composition has a release half, or has one that must be fixed before it can run. */
    DECLARED,
    /** The composition succeeded and has no release half, or there is nothing to compose. */
    NOT_DECLARED,
    /** The question could not be asked. Never an answer about the repository; always a retry. */
    UNKNOWN
  }

  // --- composed versus committed: what a candidate release.yml WOULD do, beside what is there -----

  /**
   * What a {@code release.yml} composes at one rev, side by side with the two hand-written trigger
   * files that rev really commits — <b>read-only, and it runs nothing.</b>
   *
   * <h2>What it is for, and why it has to exist before the migration rather than after</h2>
   *
   * <p>46 repositories are about to delete their hand-written {@code
   * ci-event-release-request.yml}/{@code ci-event-release.yml} pair and commit a {@code release.yml}
   * in its place. The pair is what gates and publishes them today; the slot file is a promise about
   * what would happen instead, and the promise is only redeemable by <em>composing</em> it — {@link
   * CiReleaseComposer}'s whole-slot override means the file alone does not say what the pipeline is,
   * which is the same reason {@link #releasePhaseAt} exists. So the composer is asked, on a candidate
   * that is <b>not committed yet</b>, and it reports both sides for a person to read.
   *
   * <p><b>It reports and it does not judge.</b> There is no textual diff here and no boolean saying
   * the two agree, and both absences are deliberate. A composed document carries a platform prelude
   * and a postlude no hand-written file ever had, so the two texts are <em>never</em> byte-equal and
   * an equality check over them could only ever answer "different" — a signal with no information in
   * it, which is worse than none because somebody would come to trust it. What is comparable is what
   * <em>decides behaviour</em>: the event, the selection, the checkout, each step's image and flags,
   * and what the pipeline declares it publishes. Those are what the two summaries carry, and a
   * person compares them.
   *
   * <h2>What a script publishes is the DECLARATION, never the script</h2>
   *
   * <p>qits-ci never learns how to publish anything and cannot see what a step pushed — README's
   * "What it declares is not what it observed" — so "what does this pipeline publish" is answered by
   * the {@code artifacts:} block on each side and by nothing else. Reading the script for an {@code
   * npm publish} would be a grep inside a shell script, which is exactly the mechanism {@code
   * userflows:} was invented to retire, and it would stop working the moment the script is composed.
   *
   * <p>What each step's script gets instead is a <b>sha-256 digest and a line count</b>. That is
   * enough to see at a glance that two scripts are not the same text, and it is deliberately not
   * enough to see how they differ: a diff algorithm here would be a second thing to maintain for a
   * door whose whole answer is "read these two and decide".
   *
   * <h2>Which slot file is composed, and the one request that has no answer</h2>
   *
   * <p><b>The ref's own {@code release.yml} wins when it is there.</b> A repository that has already
   * migrated is asking about itself, and composing a candidate over the top of committed bytes would
   * answer a question nobody asked. A blank or null candidate means "use the repository's own", and
   * {@link ReleaseComposition#slotFileSource()} reports which it really was, so a caller never has to
   * infer it.
   *
   * <p><b>Neither of the two is {@link CompositionVerdict#NOTHING_TO_COMPARE}, and that is a 400
   * rather than an empty 200.</b> The subject of this read is a candidate measured against a
   * committed pair; with no slot file at the ref and no candidate supplied there is nothing composed
   * to report at all. A 200 carrying two absences would read as "this repository composes nothing" —
   * a statement about the repository — when the truth is that the request was empty, and the missing
   * input is the caller's own to supply.
   *
   * <h2>Which failures are answers and which are not, and it is {@link #releasePhaseAt}'s rule</h2>
   *
   * <p>{@link CompositionVerdict#UNAVAILABLE} is reserved for the question not having been asked:
   * the repository is in no catalogue here, the slot file's read at {@code rev} came back {@code
   * UNREACHABLE}, either legacy file's read did, or the archetype could not be read from the wrapper
   * ({@link ComposeOutcome#ARCHETYPE_UNREADABLE}). None of those says anything about the
   * repository's bytes, so none of them may be reported as an absence on either side.
   *
   * <p><b>A slot file that will not parse, and a pair that will not compose, are ANSWERS.</b> They
   * are facts about the bytes that were handed in — the ref's, or the caller's own candidate — and
   * reporting them is the single most useful thing this door does before a migration commit: the
   * composed side comes back absent <em>with the parser's own message</em>, which is precisely what a
   * person about to commit that file needs to read.
   *
   * @param repositoryId the repository, by public name or by storage id — {@link #find}'s two arms
   * @param rev a git rev the host can resolve. In practice {@code main} or a branch tip while a
   *     migration is being written, rather than the released tag {@link #releasePhaseAt} is asked
   *     about — this read is about a file somebody is still editing.
   * @param candidateSlotFile the {@code release.yml} text to compose, or null/blank for "use the
   *     repository's own at that rev". Ignored outright when the ref commits one, because the ref's
   *     own bytes are the better answer to the question that was asked.
   */
  public ReleaseComposition releaseCompositionAt(
      String repositoryId, String rev, String candidateSlotFile) {
    if (repositoryId == null || repositoryId.isBlank()) {
      return ReleaseComposition.unanswered(
          CompositionVerdict.UNAVAILABLE, repositoryId, rev, "No repository was named");
    }
    // One listing for both lookups — the evaluation path's rule, and here also what keeps the
    // repository and the wrapper resolved against the same catalogue.
    List<CiRepoRef> candidates = candidateRepos.candidates();
    CiRepoRef repo = find(candidates, repositoryId);
    if (repo == null) {
      // Never an absence: an empty or unreachable catalogue looks exactly like this, and the
      // candidate list's standing rule is that a read failure never shrinks the set observably.
      return ReleaseComposition.unanswered(
          CompositionVerdict.UNAVAILABLE,
          repositoryId,
          rev,
          "Repository " + repositoryId + " is not in this qits-ci's candidate catalogue");
    }
    String repoId = repo.display();
    CiConfigSource.FileLookup own =
        configSource.readFile(repo, rev, CiReleaseSlotParser.CONFIG_PATH);
    if (own.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
      return ReleaseComposition.unanswered(
          CompositionVerdict.UNAVAILABLE,
          repositoryId,
          rev,
          repoId + ": " + CiReleaseSlotParser.CONFIG_PATH + " could not be read at " + rev);
    }
    String candidate =
        candidateSlotFile == null || candidateSlotFile.isBlank() ? null : candidateSlotFile;
    String slotFile;
    SlotFileSource source;
    if (own.status() == CiConfigSource.FileLookup.Status.FOUND) {
      slotFile = own.content();
      source = SlotFileSource.COMMITTED;
    } else if (candidate != null) {
      slotFile = candidate;
      source = SlotFileSource.CANDIDATE;
    } else {
      return ReleaseComposition.unanswered(
          CompositionVerdict.NOTHING_TO_COMPARE,
          repositoryId,
          rev,
          repoId
              + " declares no "
              + CiReleaseSlotParser.CONFIG_PATH
              + " at "
              + rev
              + " and no candidate was supplied — there is nothing composed to compare, and the"
              + " missing half is the caller's to send");
    }
    // Both legacy reads BEFORE the composition, so that "nothing was learned" outranks every answer
    // about bytes: a committed side reported absent on the strength of an unreachable git host would
    // be read as "this repository has already stopped committing that file", which is the one
    // sentence this door must never say by accident.
    CiConfigSource.FileLookup committedQa =
        configSource.readFile(repo, rev, LEGACY_RELEASE_REQUEST_PATH);
    if (committedQa.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
      return ReleaseComposition.unanswered(
          CompositionVerdict.UNAVAILABLE,
          repositoryId,
          rev,
          repoId + ": " + LEGACY_RELEASE_REQUEST_PATH + " could not be read at " + rev);
    }
    CiConfigSource.FileLookup committedRelease = configSource.readFile(repo, rev, LEGACY_RELEASE_PATH);
    if (committedRelease.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
      return ReleaseComposition.unanswered(
          CompositionVerdict.UNAVAILABLE,
          repositoryId,
          rev,
          repoId + ": " + LEGACY_RELEASE_PATH + " could not be read at " + rev);
    }
    ComposeAttempt attempt =
        attemptCompose(
            repo,
            repoId,
            slotFile,
            platformRepo(candidates),
            "this composed-versus-committed read reports which failure it was");
    if (attempt.outcome() == ComposeOutcome.ARCHETYPE_UNREADABLE) {
      // The repository's own bytes are fine and the wrapper's could not be read. That is a statement
      // about qits-ci, so it is the one composition failure that is not an answer about the file.
      return ReleaseComposition.unanswered(
          CompositionVerdict.UNAVAILABLE, repositoryId, rev, repoId + ": " + attempt.detail());
    }
    return new ReleaseComposition(
        CompositionVerdict.ANSWERED,
        repoId
            + ": composed "
            + (source == SlotFileSource.CANDIDATE ? "the supplied candidate " : "")
            + CiReleaseSlotParser.CONFIG_PATH
            + " against what "
            + rev
            + " commits",
        repositoryId,
        rev,
        source,
        CiReleaseSlotParser.CONFIG_PATH,
        phase(
            RELEASE_REQUEST_PHASE,
            CiReleaseComposer.RELEASE_REQUEST_EVENT,
            attempt,
            attempt.composed() == null ? null : attempt.composed().releaseRequestDocument(),
            RELEASE_REQUEST_SLOT,
            LEGACY_RELEASE_REQUEST_PATH,
            committedQa,
            rev),
        phase(
            RELEASE_PHASE,
            CiReleaseComposer.RELEASE_EVENT,
            attempt,
            attempt.composed() == null ? null : attempt.composed().releaseDocument(),
            RELEASE_SLOT,
            LEGACY_RELEASE_PATH,
            committedRelease,
            rev));
  }

  /** The QA phase as this read names it — the slot file's {@code release-request:} half. */
  private static final String RELEASE_REQUEST_PHASE = "release-request";

  /** The publish phase as this read names it — the slot file's {@code release:} half. */
  private static final String RELEASE_PHASE = "release";

  /** The slot key behind {@link #RELEASE_REQUEST_PHASE}, for a detail a person can act on. */
  private static final String RELEASE_REQUEST_SLOT = "release-request";

  /** The slot key behind {@link #RELEASE_PHASE}. */
  private static final String RELEASE_SLOT = "release";

  /**
   * One phase, both sides.
   *
   * <p>The two sides are built by <b>one</b> code path — {@link #described} — because both are
   * ordinary trigger documents in the same grammar, and a composed document that were summarised by
   * a second reader would be a second reader to keep in step with {@link CiEventTriggerParser}. That
   * is also the whole reason the composer emits a trigger document rather than a private shape.
   */
  private PhaseComparison phase(
      String phase,
      String event,
      ComposeAttempt attempt,
      String composedDocument,
      String slotKey,
      String legacyPath,
      CiConfigSource.FileLookup committed,
      String rev) {
    PhaseDocument composed;
    if (attempt.outcome() != ComposeOutcome.COMPOSED) {
      composed = PhaseDocument.absent(CiReleaseSlotParser.CONFIG_PATH, attempt.detail());
    } else if (composedDocument == null) {
      composed =
          PhaseDocument.absent(
              CiReleaseSlotParser.CONFIG_PATH,
              "composes no "
                  + phase
                  + " phase — neither the slot file nor its archetype declares any '"
                  + slotKey
                  + "' step, so no run of this phase would ever be recorded");
    } else {
      composed =
          described(
              CiReleaseSlotParser.CONFIG_PATH,
              composedDocument,
              "composed from "
                  + CiReleaseSlotParser.CONFIG_PATH
                  + ", platform prelude and postlude included",
              attempt.declaredArtifacts());
    }
    PhaseDocument onRef =
        committed.status() == CiConfigSource.FileLookup.Status.FOUND
            ? described(legacyPath, committed.content(), legacyPath + " at " + rev, List.of())
            : PhaseDocument.absent(
                legacyPath,
                "no "
                    + legacyPath
                    + " at "
                    + rev
                    + " — this rev commits no hand-written pipeline for "
                    + event);
    return new PhaseComparison(phase, event, composed, onRef);
  }

  /**
   * One trigger document read back: its text, and the structured summary of what decides its
   * behaviour.
   *
   * <p><b>A document that will not parse is still reported with its text.</b> It is a fact about
   * bytes somebody wrote, and the parser's own message is the most useful sentence this read can
   * hand back — the same argument {@link #releasePhaseAt} makes for answering rather than retrying.
   * On the composed side it should be unreachable, since the composer's output round-trips through
   * this parser by construction; the arm is here because "should be unreachable" is not a reason to
   * turn a surprise into a 500 on a read.
   */
  private PhaseDocument described(
      String path, String document, String detail, List<CiReleaseSlots.SlotArtifact> declared) {
    try {
      return new PhaseDocument(
          path, document, detail, summarise(triggerParser.parse(path, document), declared));
    } catch (CiConfigException e) {
      return new PhaseDocument(
          path, document, path + " is not a usable event trigger: " + e.getMessage(), null);
    }
  }

  /** What a parsed trigger document decides, flattened into something a person can read side by side. */
  private static DocumentSummary summarise(
      CiEventTrigger trigger, List<CiReleaseSlots.SlotArtifact> declared) {
    List<StepSummary> steps = new ArrayList<>();
    List<CiPipeline.CiStepDecl> declaredSteps = trigger.pipeline().steps();
    for (int i = 0; i < declaredSteps.size(); i++) {
      CiPipeline.CiStepDecl step = declaredSteps.get(i);
      steps.add(
          new StepSummary(
              i,
              step.image(),
              step.build(),
              step.docker(),
              step.user(),
              step.timeoutSeconds(),
              sha256(step.script()),
              lineCount(step.script())));
    }
    List<ArtifactSummary> artifacts = new ArrayList<>();
    for (CiArtifact artifact : trigger.artifacts()) {
      artifacts.add(
          new ArtifactSummary(
              artifact.type().declared(), artifact.name(), sbomPath(declared, artifact)));
    }
    return new DocumentSummary(
        trigger.eventName(),
        rendered(trigger.selection()),
        trigger.checkout() == null
            ? null
            : new CheckoutSummary(
                trigger.checkout().branchPath(),
                trigger.checkout().shaPath(),
                trigger.checkout().optional()),
        List.copyOf(steps),
        List.copyOf(artifacts));
  }

  /**
   * The {@code sbom:} path declared for one artifact, or {@code ""} when none was.
   *
   * <p>Matched by the coordinate rather than by position, for {@link
   * ComposeAttempt#declaredArtifacts()}'s reason: the composed {@code artifacts:} block is the
   * effective list and the lookup table is every declaration either document made, so the join has
   * to be on the thing both spell the same way.
   */
  private static String sbomPath(List<CiReleaseSlots.SlotArtifact> declared, CiArtifact artifact) {
    for (CiReleaseSlots.SlotArtifact candidate : declared) {
      if (candidate.artifact().equals(artifact)) {
        return candidate.sbomPath();
      }
    }
    return "";
  }

  /**
   * A {@code when:} as one line: groups OR'd, conditions within a group AND'd — the grammar {@link
   * CiEventSelection} defines, written out rather than left for a reader to reconstruct from nested
   * JSON.
   *
   * <p>Rendered rather than echoed, because the two sides of a phase are compared by eye and a
   * selection is the single most consequential line in either document: an absent {@code when:}
   * means <b>unconditional</b>, so a candidate that lost its selection would fire for every release
   * of every repository on the platform, and that has to be readable at a glance rather than
   * inferred from an empty list.
   */
  private static String rendered(CiEventSelection selection) {
    if (selection.isUnconditional()) {
      return "unconditional — every event of this name matches";
    }
    List<String> groups = new ArrayList<>();
    for (CiEventSelection.Group group : selection.groups()) {
      List<String> conditions = new ArrayList<>();
      for (CiEventSelection.PathCondition condition : group.conditions()) {
        for (CiEventSelection.Matcher matcher : condition.matchers()) {
          conditions.add(condition.path() + " " + rendered(matcher));
        }
      }
      String joined = String.join(" AND ", conditions);
      groups.add(selection.groups().size() > 1 ? "(" + joined + ")" : joined);
    }
    return String.join(" OR ", groups);
  }

  private static String rendered(CiEventSelection.Matcher matcher) {
    return switch (matcher.kind()) {
      case EXACT -> "exact '" + matcher.value() + "'";
      case PREFIX -> "prefix '" + matcher.value() + "'";
      case EXISTS -> matcher.expected() ? "exists" : "does not exist";
    };
  }

  /**
   * A script's sha-256, hex.
   *
   * <p>The whole of what this read says about a script's content, and the bound is the point: two
   * digests that differ prove the texts differ, and nothing here tells a reader how — see the {@link
   * #releaseCompositionAt} javadoc for why a diff algorithm is not what this door is.
   */
  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      // Every JDK is required to carry SHA-256; if this one does not, the read is broken rather than
      // the repository, and pretending the digest was something would be worse than saying so.
      throw new IllegalStateException("SHA-256 is not available on this JVM", impossible);
    }
  }

  /** How many lines a script is — the cheap half of the digest, and the one a person reads first. */
  private static int lineCount(String script) {
    return script.isEmpty() ? 0 : (int) script.lines().count();
  }

  /**
   * What {@link #releaseCompositionAt} answered, and — when it answered at all — both phases.
   *
   * <p>The detail is contract rather than a log line, {@link ReleasePhase}'s rule: on the two
   * unanswered verdicts it is the only thing the caller can put in front of a person, and on the
   * answered one it names which slot file was composed against which rev.
   *
   * @param slotFileSource which slot file was composed — null on an unanswered verdict, because none
   *     was
   * @param slotFilePath the path a slot file lives at, reported even when the candidate came in over
   *     the wire: a person editing the candidate is editing the file that will sit there
   */
  public record ReleaseComposition(
      CompositionVerdict verdict,
      String detail,
      String repositoryId,
      String rev,
      SlotFileSource slotFileSource,
      String slotFilePath,
      PhaseComparison releaseRequestPhase,
      PhaseComparison releasePhase) {

    static ReleaseComposition unanswered(
        CompositionVerdict verdict, String repositoryId, String rev, String detail) {
      return new ReleaseComposition(verdict, detail, repositoryId, rev, null, null, null, null);
    }
  }

  /**
   * The three answers this read has, and the two that are not answers about a repository at all.
   *
   * <p>Collapsing {@link #UNAVAILABLE} into {@link #ANSWERED} would report a git host's blip as an
   * absence on one side, which reads as a statement about what the repository commits.
   */
  public enum CompositionVerdict {
    /** Both sides were read and reported. Every failure of bytes is inside the answer. */
    ANSWERED,
    /** The question could not be asked — a catalogue miss or an unreachable read. Retry. */
    UNAVAILABLE,
    /** No slot file at the rev and no candidate supplied: an empty request, not an empty answer. */
    NOTHING_TO_COMPARE
  }

  /** Which slot file was composed. Reported rather than inferred — see {@link #releaseCompositionAt}. */
  public enum SlotFileSource {
    /** The one the ref itself commits, which always wins when it is there. */
    COMMITTED,
    /** The one the caller supplied, used because the ref commits none. */
    CANDIDATE
  }

  /**
   * One phase of the release cycle, from both sides.
   *
   * @param phase {@code release-request} or {@code release} — the slot key, which is also what the
   *     platform calls the phase
   * @param event the domain event a run of this phase is triggered by, which is what makes the two
   *     sides comparable at all: they are two declarations about one event
   */
  public record PhaseComparison(
      String phase, String event, PhaseDocument composed, PhaseDocument committed) {}

  /**
   * One side of one phase: where it lives, its text, the sentence about it, and the summary.
   *
   * @param document the text, or null when this side declares nothing — {@code detail} then says why,
   *     which is the explicit absence marker rather than an empty string that could be mistaken for
   *     an empty file
   * @param summary null when there is nothing to summarise: the side is absent, or its document is
   *     present and will not parse
   */
  public record PhaseDocument(
      String path, String document, String detail, DocumentSummary summary) {

    static PhaseDocument absent(String path, String detail) {
      return new PhaseDocument(path, null, detail, null);
    }
  }

  /**
   * Everything about a trigger document that decides what a run of it does — and deliberately
   * nothing about what its scripts say beyond their digests.
   *
   * @param selection the {@code when:} as one line; see {@link #rendered(CiEventSelection)}
   * @param checkout null when the document declares none, which means the run builds {@code main}'s
   *     head — a difference between the two sides worth seeing rather than deducing
   */
  public record DocumentSummary(
      String event,
      String selection,
      CheckoutSummary checkout,
      List<StepSummary> steps,
      List<ArtifactSummary> artifacts) {}

  /** The two payload dot-paths a run of this document is anchored at, and the compatibility arm. */
  public record CheckoutSummary(String branchPath, String shaPath, boolean optional) {}

  /**
   * One step as this read reports it.
   *
   * @param user the container user, {@code ""} when the document declares none — the image's default
   * @param timeoutSeconds null when the document declares none, which is the deployment-wide default
   * @param scriptSha256 the script's digest, which is the whole of what is said about its content
   * @param scriptLines how long it is, so a reader sees a script that grew or shrank without opening
   *     either document
   */
  public record StepSummary(
      int index,
      String image,
      boolean build,
      boolean docker,
      String user,
      Integer timeoutSeconds,
      String scriptSha256,
      int scriptLines) {}

  /**
   * One declared artifact.
   *
   * @param sbomPath the {@code sbom:} path the slot file carried for it, {@code ""} when none — and
   *     always {@code ""} on the committed side, since a trigger file's {@code artifacts:} grammar
   *     has no such key and never did
   */
  public record ArtifactSummary(String type, String name, String sbomPath) {}

  /**
   * The platform-pipelines repository as a candidate, or null when the feature is off or the
   * catalogue does not hold it. No logging: both callers say what a null means in their own terms,
   * and one of them (the archetype read) only cares when a repository actually asked for a recipe.
   */
  private CiRepoRef platformRepo(List<CiRepoRef> candidates) {
    String configured = platformPipelinesRepository;
    return configured.isEmpty() ? null : find(candidates, configured);
  }

  /** A checkout path resolved against the payload; null when the path leads nowhere or to blank. */
  private static String checkoutField(JsonNode payload, String path) {
    JsonNode node = CiEventSelectionEvaluator.resolve(payload, path);
    if (node == null) {
      return null;
    }
    String value = CiEventSelectionEvaluator.asString(node);
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * The platform pass: the files one configured repository declares for the whole catalogue.
   *
   * <p>Read at that repository's {@code main} head, parsed by the same parser and selected by the
   * same grammar as a repository's own trigger — but the run is recorded against, and cloned from,
   * the repository the <b>payload</b> names. That is the whole of the difference, and it is what
   * lets one file bump every repository instead of 71 files bumping one dependency each.
   *
   * <p><b>Three ways it records nothing, and each is one WARN naming the event and the
   * repository.</b> The payload carries no {@code repository}; it names one the catalogue does not
   * hold; or that repository could not be read for this evaluation, so there is no head to record a
   * run at. None of them is a run and none of them is silent: a platform pipeline that matched and
   * then did nothing is exactly the failure a maintenance service cannot see from the outside.
   *
   * <p><b>A repository with both a local and a platform trigger for one event gets two runs.</b>
   * Two files, two declared pipelines, two rows — the dedupe is on {@code (event, repository, config
   * path)} and the two paths differ, so nothing here collapses them. That is by design.
   */
  private void evaluatePlatform(
      Arrival arrival,
      JsonNode payload,
      List<CiRepoRef> candidates,
      CiRepoRef platformRepo,
      Map<String, String> heads,
      List<String> runIds) {
    String configured = platformPipelinesRepository;
    if (configured.isEmpty()) {
      // Off, and off means no read at all.
      return;
    }
    if (platformRepo == null) {
      // WARN rather than DEBUG, unlike the per-candidate reads: this repository is named in this
      // deployment's own config, so a missing one is a misconfiguration that silently disables every
      // platform pipeline, and it can be acted on.
      LOG.warnf(
          "The platform-pipelines repository %s is not in the catalogue — no platform pipeline was"
              + " evaluated for event %s",
          configured, arrival.eventId());
      return;
    }
    EventTriggerLookup lookup =
        configSource.readEventTriggers(platformRepo, TRIGGER_BRANCH, CiTriggerScope.PLATFORM);
    if (lookup.status() != EventTriggerLookup.Status.FOUND) {
      LOG.warnf(
          "Could not read %s@%s for platform triggers — no platform pipeline was evaluated for"
              + " event %s",
          configured, TRIGGER_BRANCH, arrival.eventId());
      return;
    }
    for (EventTriggerFile file : lookup.files()) {
      CiEventTrigger trigger;
      try {
        trigger = triggerParser.parse(file.path(), file.content());
      } catch (CiConfigException e) {
        // Per file, exactly as a repository's own: one broken platform pipeline never disables the
        // ones beside it.
        LOG.warnf(
            "%s: %s is not a usable platform event trigger: %s",
            configured, file.path(), e.getMessage());
        continue;
      }
      if (!trigger.eventName().equals(arrival.eventName())) {
        continue;
      }
      if (!CiEventSelectionEvaluator.matches(trigger.selection(), payload)) {
        LOG.debugf(
            "%s: %s declares %s but its selection did not match event %s",
            configured, file.path(), trigger.eventName(), arrival.eventId());
        continue;
      }
      if (trigger.checkout() != null) {
        // Refused, for now: the platform pass's contract is "the head comes from the candidate
        // pass; no head, no run", and checkout: would build an arbitrary sha of a repository a
        // THIRD repository's file named, with no current use case. Loud, per file, reversible —
        // granting symmetry later is additive.
        LOG.warnf(
            "%s: %s declares 'checkout:', which is not supported in platform pipelines — the run"
                + " builds the named repository's %s head; no run for event %s",
            configured, file.path(), TRIGGER_BRANCH, arrival.eventId());
        continue;
      }
      String named = payloadRepository(payload);
      CiRepoRef target = named == null ? null : find(candidates, named);
      if (target == null) {
        LOG.warnf(
            "Event %s (%s) matched %s in %s, but the repository it names (%s) is not one this"
                + " platform holds — no run",
            arrival.eventId(),
            arrival.eventName(),
            file.path(),
            configured,
            named == null ? "none" : named);
        continue;
      }
      String head = heads.get(target.repoId());
      if (head == null) {
        LOG.warnf(
            "Event %s (%s) matched %s in %s, but %s@%s could not be read — no run",
            arrival.eventId(),
            arrival.eventName(),
            file.path(),
            configured,
            target.display(),
            TRIGGER_BRANCH);
        continue;
      }
      LOG.infof(
          "Event %s (%s) matched platform pipeline %s — enqueuing a run for %s at %s",
          arrival.eventId(), arrival.eventName(), file.path(), target.display(), head);
      String runId =
          runService.onEventTrigger(
              new CiRunService.EventRun(
                  target,
                  TRIGGER_BRANCH,
                  head,
                  trigger,
                  arrival.eventId(),
                  arrival.eventName(),
                  arrival.occurredAt(),
                  arrival.payload(),
                  file.content()));
      if (runId != null) {
        runIds.add(runId);
      }
    }
  }

  /** The payload's {@code repository}, or null when it carries none or carries a non-string. */
  private static String payloadRepository(JsonNode payload) {
    JsonNode value = payload == null ? null : payload.get(PAYLOAD_REPOSITORY_FIELD);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText();
  }

  /**
   * The candidate a name refers to, or null when the catalogue holds none.
   *
   * <p><b>The public name wins over the storage id</b>, and the id arm is the pre-cutover
   * compatibility half: before the identity campaign the two are the same string, after it only the
   * name is something a config key or an event payload could hold.
   */
  private static CiRepoRef find(List<CiRepoRef> candidates, String name) {
    for (CiRepoRef repo : candidates) {
      if (name.equals(repo.name())) {
        return repo;
      }
    }
    for (CiRepoRef repo : candidates) {
      if (name.equals(repo.repoId())) {
        return repo;
      }
    }
    return null;
  }

  // --- the owed-event sweeps: what a process that did not finish left behind -----------------------

  /**
   * Boot: re-evaluate <b>every</b> owed event, whatever its age.
   *
   * <p>No grace, and that is a property of the deployment rather than an optimism. {@code
   * .config/qits/deployments.yml} declares {@code update_order: stop-first} — one CI process at a
   * time — so any row present at boot was accepted by a process that is gone, and waiting to be sure
   * would only make the release request that is missing its QA run wait too.
   *
   * <p><b>On its own thread</b>, {@code ReleaseJoin.onStart}'s rule: a startup observer that blocks
   * on the network loses the container healthcheck's race and cd kills the deployment. This one
   * reads the git host once per candidate per row.
   *
   * <p><b>At {@code CiRunService.BOOT_SWEEP_PRIORITY}</b> rather than unordered, because it can
   * record runs and hand them to the run worker: it must not precede {@code
   * CiDaemonLauncher.onStart}'s container reap, which cannot tell a container this boot started from
   * one the previous life left. Sharing the run sweep's rung is enough for that — the reap's is
   * lower and the observers before it have returned — and the two do not otherwise interact: a run
   * this sweep records is {@code QUEUED}, which the run sweep either re-enqueues or ignores, and
   * both are correct.
   */
  void onStart(@Observes @Priority(CiRunService.BOOT_SWEEP_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Thread sweeper = new Thread(() -> sweepOwed(Instant.now()), "ci-trigger-owed-sweep");
    sweeper.setDaemon(true);
    sweeper.start();
  }

  /**
   * The schedule underneath the boot pass, and it is not redundant with it.
   *
   * <p>A boot sweep is one attempt: a database that was not there yet when it ran, or a git host
   * that answered nothing, leaves the events owed until the next deployment — which for a service
   * whose release train rides those events is exactly the outage the ledger was written to end. The
   * tick makes the recovery self-healing instead.
   *
   * <p>{@link Scheduled.ConcurrentExecution#SKIP} because a sweep is a fan-out and two of them are
   * one storm at the git host; the grace is what keeps it from re-offering work this process is
   * still doing.
   */
  @Scheduled(
      every = "{qits.ci.trigger-owed-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepOwedTick() {
    sweepOwed(Instant.now().minus(owedGrace));
  }

  /**
   * Re-evaluates every event accepted before {@code cutoff} and never evaluated — package-private
   * because both callers above skip or defer in a suite, so this is what a test drives to make a
   * claim about a lost event.
   *
   * <p>One row at a time on this thread, and per-row containment: a row that throws stays owed for
   * the next sweep, and the ones behind it are still swept. A row whose evaluation <em>returned</em>
   * is settled even if no repository could be read — see the class javadoc for why that is not a
   * retry this ledger owes.
   */
  void sweepOwed(Instant cutoff) {
    List<CiOwedEvent> stale;
    try {
      stale = QuarkusTransaction.requiringNew().call(() -> owed.listAcceptedBefore(cutoff));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the owed trigger events");
      return;
    }
    if (stale.isEmpty()) {
      return;
    }
    LOG.infof(
        "Re-evaluating %d event(s) accepted by a process that did not finish with them", stale.size());
    int recovered = 0;
    for (CiOwedEvent row : stale) {
      Arrival arrival = new Arrival(row.eventId, row.eventName, row.occurredAt, row.payload);
      try {
        Evaluation done = evaluate(arrival);
        settle(row.eventId);
        recovered++;
        if (!done.runIds().isEmpty()) {
          LOG.infof(
              "Event %s (%s) was owed and is now evaluated: %d run(s) recorded",
              row.eventId, row.eventName, done.runIds().size());
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            e, "Could not re-evaluate owed event %s (%s); it stays owed", row.eventId, row.eventName);
      }
    }
    LOG.infof("Owed-event sweep: %d of %d re-evaluated", recovered, stale.size());
  }

  /** Test hook: waits for the evaluation queued at this moment to drain. */
  void awaitIdle() throws Exception {
    evaluator.submit(() -> {}).get();
  }
}
