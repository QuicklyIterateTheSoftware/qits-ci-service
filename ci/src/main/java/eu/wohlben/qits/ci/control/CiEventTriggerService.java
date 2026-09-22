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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * <p><b>Three outcomes leave a row owed: a throw, a release evaluation that could not read a
 * candidate's release pipeline, and a run whose step image could not be pinned.</b> The second covers both halves of that pipeline — the
 * repository's {@code .config/qits/release.yml} coming back {@code UNREACHABLE}, and the wrapper
 * repository's own trigger listing failing, which leaves no revision to read an archetype recipe at.
 * They are one case because they are one sentence: nothing was learned, so nothing about this
 * repository's release cycle is known. Everything else settles when the evaluation returns — including an
 * evaluation that reached no readable repository at all, which is the git host's answer about every
 * candidate rather than about one, behaves exactly as a live frame's does, and would otherwise keep
 * rows for a platform that simply has no candidates yet.
 *
 * <p>The second of the two was added on 2026-09-18 and is a correction rather than a widening. Every
 * repository in the estate is on {@code .config/qits/release.yml} now, so an {@code UNREACHABLE}
 * read of that file during a {@code ReleaseRequestChanged} or an {@code SCMRelease} is the whole of
 * what that repository's release pipeline is — and the old rule settled the row for it: no run, the
 * repository counted as <em>read</em> rather than skipped, {@link #evaluate} returning normally, the
 * row deleted, and a release request left PENDING forever on a QA verdict nothing anywhere would
 * ever record again. One git-host blip cost a release. {@link Evaluation#repositoriesUnreadable()}
 * is what carries that fact out of the evaluation, and the two settling callers — {@link
 * #evaluateQuietly} and {@link #sweepOwed} — skip the settle when it is non-empty.
 *
 * <p>The third is the same sentence about a different read (2026-09-22). Every recipe step on the
 * estate names a floating {@code qits/build-images/*:latest}, and a run now fixes that tag to a
 * digest once, at accept, for all of its steps — see {@code CiRunService.pinStepImages}. A platform
 * image whose digest the registry would not answer is not a verdict about the repository: nothing
 * is known about which tool the build would have run inside, and accepting the run anyway is the
 * floating-tag defect the pin exists to close. So the accept refuses, the candidate lands on {@link
 * Evaluation#repositoriesUnreadable()} beside the {@code release.yml} case, and a sweep asks again.
 *
 * <p>What makes that safe is the dedupe two paragraphs up and nothing else: the candidates that
 * <em>did</em> answer have already recorded their runs, the constraint refuses them a second time,
 * and the only thing a sweep can add is the run the unreadable candidate was owed.
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
   * The branch an event trigger is READ at, and the one its run builds when the event names no
   * revision of its own. The platform's one tracked branch, supplied by convention because most
   * events name no ref. A trigger with {@code checkout:} still <b>decides</b> here ("decide at main,
   * build at the event's commit"): discovery, parsing and selection read this branch's head, so a
   * pushed branch cannot alter the CI that gates it; only the recorded run's branch/sha come from
   * the payload.
   *
   * <p><b>It is no longer what a trigger with no {@code checkout:} builds on a RELEASE event.</b>
   * Those two events each name the revision they are about, so that revision is what their runs are
   * recorded at whether the file declares the pair or not — see {@link #defaultCheckout}. This
   * constant is the answer for every other event, where no revision exists in this repository to
   * name; that is a different question with one defined answer rather than the same fallback under
   * another name.
   *
   * <p><b>It is a branch name only where a head has to be RESOLVED, and nothing is ever read at the
   * literal string.</b> Both listings — a candidate's own trigger files and the platform
   * repository's — are made at this branch and answer the sha they resolved; everything read
   * afterwards is read at a sha. The wrapper's archetype recipes used to be the exception (read at
   * the literal string, once per candidate, so a push to the wrapper mid-evaluation could compose
   * two repositories of one archetype from two different recipes with nothing recording which);
   * they are read at {@code ArchetypeReads.rev()} now, once for the whole evaluation.
   *
   * <p><b>A repository's release SLOTS are the one thing not read here at all.</b> {@code
   * .config/qits/release.yml} used to be read at this branch's resolved head, which composed a
   * release pipeline out of a commit the run would never build. It is read at the revision the
   * event is about — the fold, or the released tag — so that <b>the pipeline that gates a revision
   * is read from that revision</b>. See {@link #releaseRevision}. The wrapper's half of that
   * composition is still this branch's, deliberately: see {@link #releaseSlots}.
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
   *     EventTriggerLookup} cannot tell those apart and neither can this. Every repository on
   *     {@code repositoriesUnreadable} is on this list too, because a candidate whose pipeline could
   *     not be read is exactly a candidate that did not answer, and the synchronous door reports
   *     this list.
   * @param repositoriesUnreadable the candidates whose {@code .config/qits/release.yml} came back
   *     {@code UNREACHABLE} during a release event — the one outcome that leaves the event OWED. It
   *     is a separate list rather than a flag on the one above because the skipped list mixes four
   *     answers that are all final for this evaluation, and this one is the only one a later sweep
   *     can improve on. See the class javadoc for the release it cost.
   */
  public record Evaluation(
      List<String> runIds,
      int repositoriesRead,
      List<String> repositoriesSkipped,
      List<String> repositoriesUnreadable) {

    public Evaluation {
      runIds = List.copyOf(runIds);
      repositoriesSkipped = List.copyOf(repositoriesSkipped);
      repositoriesUnreadable = List.copyOf(repositoriesUnreadable);
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
    Evaluation done;
    try {
      done = evaluate(arrival);
    } catch (RuntimeException e) {
      // The owed row is deliberately NOT settled here: a throw is one of the two outcomes a later
      // sweep can improve on, and the dedupe makes re-evaluating whatever did get recorded a no-op.
      LOG.errorf(
          e,
          "Evaluating triggers for event %s failed unexpectedly; it stays owed for the next sweep",
          arrival.eventId());
      return;
    }
    if (!done.repositoriesUnreadable().isEmpty()) {
      // The other one. A release event whose candidate could not have its release.yml read has had
      // no verdict recorded for that repository, and nothing else on the platform would ever ask
      // again — so the row stands and the next sweep asks a git host that has probably come back.
      LOG.warnf(
          "Event %s (%s) stays owed: %s could not have %s read, so no release pipeline was composed"
              + " for it — the next sweep re-evaluates the event",
          arrival.eventId(),
          arrival.eventName(),
          done.repositoriesUnreadable(),
          CiReleaseSlotParser.CONFIG_PATH);
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
      return new Evaluation(List.of(), 0, List.of(), List.of());
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
    // The candidates whose release.yml could not be read, which is the one entry on the skipped list
    // that a later sweep can do something about — see the class javadoc and evaluateQuietly.
    List<String> unreadable = new ArrayList<>();
    // The head each candidate answered with, kept for the platform pass: a platform run is recorded
    // against the repository the payload names, at the commit that repository's main was on for THIS
    // evaluation. Reading it again would be a second read of a branch that may have moved.
    Map<String, String> heads = new HashMap<>();
    // Resolved AND LISTED once for the whole evaluation, and used twice: the platform pass reads its
    // trigger files out of this listing, and a candidate's release.yml reads its archetype recipe at
    // the sha this listing resolved. One catalogue lookup, one listing, no second read.
    //
    // Unconditional, including when projectScope narrows the evaluation and no platform pass will
    // run at all: a project-scoped evaluation composes release pipelines like any other, and those
    // need the wrapper's sha. The listing is where the sha comes from, so skipping it here would
    // leave a scoped evaluation with no rev and — under the fail-closed rule — no release run.
    ArchetypeReads wrapper = readWrapper(candidates);
    // THE REVISION THIS EVENT IS ABOUT, resolved once for the whole evaluation and never per
    // candidate: it is a property of the event's payload, so a second resolution per repository
    // would be the same answer arrived at N times and N copies of the same log line. Null for every
    // event that is not one of the two release events — see releaseRevision.
    ReleaseRevision revision = releaseRevision(arrival, payload);
    for (CiRepoRef repo : candidates) {
      if (deadlineNanos != null && System.nanoTime() - deadlineNanos >= 0) {
        // Out of time rather than out of answers, and the two must not look alike to the caller —
        // so the repository goes on the skipped list like any other one that could not be asked.
        skipped.add(repo.repoId());
        continue;
      }
      try {
        if (!evaluateRepo(repo, arrival, payload, revision, runIds, heads, wrapper, unreadable)) {
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
        evaluatePlatform(arrival, payload, candidates, wrapper, heads, runIds, unreadable);
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
    return new Evaluation(runIds, candidates.size() - skipped.size(), skipped, unreadable);
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
   * Evaluates one repository. {@code false} means it could not be read, which is not "no match" —
   * either its trigger listing did not answer at all, or its {@code release.yml} did not, and the
   * second of those also lands the repository on {@code unreadable}.
   *
   * <p>The reference travels rather than an id: the trigger files are read name-addressed when the
   * candidate carries a public coordinate, and id-addressed when it does not.
   */
  private boolean evaluateRepo(
      CiRepoRef repo,
      Arrival arrival,
      JsonNode payload,
      ReleaseRevision revision,
      List<String> runIds,
      Map<String, String> heads,
      ArchetypeReads wrapper,
      List<String> unreadable) {
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
    // THE REVISION THIS EVENT IS ABOUT, which for the two release events is the fold or the tag and
    // never main — see releaseRevision. The repository's own committed trigger files above are read
    // at main, exactly as they always were; only the release SLOTS move.
    ReleaseSlots slots = releaseSlots(repo, repoId, arrival, revision, wrapper);
    // Whether every document this candidate declares was evaluated to a conclusion. A step image
    // this platform publishes whose digest could not be resolved is the second way that can be
    // false — see evaluateTrigger — and it is the release.yml read's case one layer down: nothing
    // was learned, so the event is owed rather than settled with a run missing.
    boolean complete = true;
    for (EventTriggerFile file : lookup.files()) {
      // A committed trigger file has no archetype: its bytes are the repository's own word about its
      // own pipeline, with no platform share in them to record the provenance of.
      complete &=
          evaluateTrigger(
              repo,
              repoId,
              file.path(),
              file.content(),
              arrival,
              payload,
              revision,
              lookup,
              null,
              runIds);
    }
    if (slots.document() != null) {
      complete &=
          evaluateTrigger(
              repo,
              repoId,
              CiReleaseSlotParser.CONFIG_PATH,
              slots.document(),
              arrival,
              payload,
              revision,
              lookup,
              slots.archetype(),
              runIds);
    }
    if (!complete) {
      unreadable.add(repo.repoId());
      return false;
    }
    if (slots.unreadable()) {
      // The repository's OWN trigger files above were evaluated — that listing answered — and what
      // could not be read is the one file its release cycle is made of. So the evaluation of this
      // candidate is incomplete rather than done, and the event stays owed on the strength of it.
      // Re-firing the bespoke files a sweep then re-evaluates is free: the dedupe refuses them.
      unreadable.add(repo.repoId());
      return false;
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
   *
   * @return whether this document was evaluated <b>to a conclusion</b>. Every verdict is one,
   *     including the several that record no run: a file that declares another event, a selection
   *     that did not match, a checkout the payload cannot supply. {@code false} means the opposite
   *     of a verdict — the run's step image could not be pinned, so nothing is known about what
   *     this build would have run inside, and the caller leaves the event owed for a sweep.
   */
  private boolean evaluateTrigger(
      CiRepoRef repo,
      String repoId,
      String configPath,
      String content,
      Arrival arrival,
      JsonNode payload,
      ReleaseRevision revision,
      EventTriggerLookup lookup,
      CiReleaseArchetypes.ArchetypeRef archetype,
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
        return true;
      }
      if (!trigger.eventName().equals(arrival.eventName())) {
        return true;
      }
      if (!CiEventSelectionEvaluator.matches(trigger.selection(), payload)) {
        LOG.debugf(
            "%s: %s declares %s but its selection did not match event %s",
            repoId, file.path(), trigger.eventName(), arrival.eventId());
        return true;
      }
      // THE DEFAULT CHECKOUT — what a trigger that declares no `checkout:` is recorded at. Two
      // answers, and the split is the whole of what makes it correct rather than a special case:
      //
      //   * A RELEASE event is about a revision, and that revision is the only honest answer. The
      //     pair comes out of the payload through the same paths CiReleaseComposer emits into a
      //     composed `checkout:`, so a run that declares none and a run that declares the canonical
      //     one are recorded at one commit. Recording such a run at main's head was the defect: the
      //     fold lives on a branch nobody pushed and a tag's commit need not be on main at all, so
      //     the row named a revision the pipeline was never about and the clone could not reach.
      //     A release event that names no usable pair is NO RUN — see defaultCheckout.
      //   * Every OTHER event names no revision in this repository. A BuildSuccessful, or an
      //     SCMRelease from a DIFFERENT repository, says nothing about what this repository should
      //     build, so main's head is not a fallback but the only revision that exists — the tracked
      //     branch, supplied by convention exactly as TRIGGER_BRANCH's javadoc says. That is not
      //     the rule above weakened; it is a different question with one defined answer.
      DefaultCheckout dflt = defaultCheckout(revision, lookup.headSha());
      // The trigger AS THIS RUN IS ACCEPTED UNDER, which is the declared one except on the
      // compatibility arm below — see there for why the difference has to be carried rather than
      // merely logged.
      CiEventTrigger accepted = trigger;
      String branch;
      String sha;
      if (trigger.checkout() == null) {
        if (dflt == null) {
          // The event is a release event and names no revision this run could be about. One WARN
          // and no run, which is the same answer a declared-but-unresolvable checkout gets a few
          // lines down and for the identical reason: there is no truthful (ref, sha) pair to record
          // a row against, and main's head is not one — it is a different commit wearing this
          // event's name. The missing or refused field itself was named once for the whole
          // evaluation by releaseRevision; this line says which file went without a run.
          LOG.warnf(
              "%s: %s declares no checkout and event %s (%s) names no usable revision — no run",
              repoId, file.path(), arrival.eventId(), arrival.eventName());
          return true;
        }
        branch = dflt.branch();
        sha = dflt.sha();
      } else {
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
            return true;
          }
          branch = declaredBranch;
          sha = declaredSha;
        } else if (trigger.checkout().optional()
            && RELEASE_EVENTS.contains(arrival.eventName())) {
          // THE FALLBACK IS REFUSED FOR THE TWO RELEASE EVENTS, and this arm is what closes the
          // last path by which a release run could be dispatched at main's head.
          //
          // What it used to do: branch = main, sha = the candidate's head, checkout stripped. That
          // is a RELEASE run — the event names a fold or a released tag — recorded against, and
          // built from, a revision the event says nothing about. A pipeline composed from main
          // gates a commit nobody released, and the run's own verdict then travels to
          // qits-projects' gate as if it were about the released one. Owner ruling: a release
          // pipeline is composed from, and run against, the revision actually being built, and no
          // step falls back to main.
          //
          // It is the same answer the non-optional arm below gives, and the same answer
          // defaultCheckout gives a release event that names no revision — one line, no run — so a
          // release event with a half-missing payload costs the file its run whichever way the file
          // is written. ERROR rather than WARN for releaseRevision's reason: the payload of a
          // release event is expected to carry the revision it is about, so a half of it missing is
          // a defect in the publisher rather than a shape to accommodate.
          //
          // SETTLED, NOT OWED. Nothing is added to the evaluation's unreadable list, so the event
          // is settled exactly as ReleaseSlots.NO_REVISION settles: an event's bytes are immutable,
          // the missing half is missing on every future offer, and an owed row for it is a row
          // nothing could ever clear with the watermark stuck behind it.
          LOG.errorf(
              "%s: %s declares an optional checkout { %s, %s } and release event %s (%s) does not"
                  + " carry both — no run. The fallback to %s's head is gone: a release run at a"
                  + " revision the event is not about gates a commit nobody released",
              repoId,
              file.path(),
              trigger.checkout().branchPath(),
              trigger.checkout().shaPath(),
              arrival.eventId(),
              arrival.eventName(),
              TRIGGER_BRANCH);
          return true;
        } else if (trigger.checkout().optional()) {
          // THE COMPATIBILITY ARM, and what `optional:` means now that the release events are
          // refused above: a file reacting to ANOTHER repository's release.
          //
          // The event names a revision of somebody else's repository — a `SoftwareRelease` off
          // qits-ci's own announce path is the live shape, and it is not a release event here — so
          // this repository has exactly one revision the event could be built at: the tracked
          // branch's head. That is not a fallback from a revision that exists; it is the same
          // answer defaultCheckout gives every non-release event, reached by a file that hoped the
          // payload would carry a coordinate and found it did not. A downstream bump is B building
          // B's own main because A released, which is correct and is the run the author declared.
          // INFO rather than WARN: a supported shape of the event, not a fault.
          //
          // The trigger is handed on WITHOUT its checkout, which is the point and not bookkeeping.
          // Everything downstream that asks "does this run follow the event's own ref?" must get the
          // pre-checkout answer here, because that is the run this is — above all the per-ref burst
          // collapse in CiRunService, which is correct for payload-resolved refs and WRONG for the
          // "main" convention: two distinct release events falling back would share the ref and the
          // older one would be deduped away, publishing no image for a version that really released.
          // Rewriting the value is how that stays true of every such question, including ones added
          // later, rather than of the one we remembered.
          //
          // THERE IS NO SURVIVING FALLBACK FOR A RELEASE RUN. This arm used to be reachable for a
          // composed release document (`checkout: { branch: version, sha: commitSha, optional: true
          // }`) whose payload carried a usable `commitSha` and no usable `version`, and it
          // dispatched that release run at main's head. Both halves of that are closed: the
          // composer emits no `optional:` at all, and the arm above refuses the flag outright for
          // the two release events. What is left here is a file about somebody else's release, for
          // which main's head is the only revision there is.
          branch = TRIGGER_BRANCH;
          sha = lookup.headSha();
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
          return true;
        }
      }
      LOG.infof(
          "Event %s (%s) matched %s in %s — enqueuing a run at %s@%s",
          arrival.eventId(), arrival.eventName(), file.path(), repoId, branch, sha);
      String runId;
      try {
        runId =
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
                    file.content(),
                    archetype));
      } catch (CiRunService.StepImageUnpinned unpinned) {
        // NOTHING WAS LEARNED ABOUT WHICH TOOL THIS BUILD WOULD USE, so there is no run and the
        // event is left OWED — the answer an unreadable release.yml already gets, for the identical
        // reason. A step image this platform publishes whose digest the registry would not answer
        // is a question that stands rather than a verdict: accepting the run anyway would build
        // against whatever that tag points at when each container starts, and settling the event
        // would lose the build outright. A sweep asks a registry that has probably come back.
        //
        // WARN rather than ERROR: unlike a payload with no revision, this really can right itself,
        // and it says so.
        LOG.warnf(
            "%s: %s declares step image %s, whose digest could not be resolved — no run, and event"
                + " %s (%s) stays owed so a sweep re-evaluates it: %s",
            repoId,
            file.path(),
            unpinned.reference(),
            arrival.eventId(),
            arrival.eventName(),
            unpinned.getMessage());
        return false;
      }
      if (runId != null) {
        runIds.add(runId);
      }
    }
    return true;
  }

  // --- the release slot file ----------------------------------------------------------------------

  /**
   * What {@code .config/qits/release.yml} means for ONE candidate and ONE arriving event — and there
   * are <b>three</b> answers, not two.
   *
   * <p>The third is what the whole of the owed-event correction hangs on. "A document to run" and
   * "nothing to run" are both verdicts about the repository's own committed bytes and are final;
   * "the file could not be read" is a verdict about nothing at all, and collapsing it into either of
   * the others is how a git-host blip used to cost a release request its QA run — see the class
   * javadoc.
   *
   * @param unreadable the read came back {@code UNREACHABLE}. Nothing is known about this
   *     repository's release cycle, so the evaluation is incomplete and the event stays owed
   * @param document the composed trigger document for this event, or null when there is none to run
   * @param archetype which wrapper recipe the document was composed from, at which revision — null
   *     when there is no document, and equally null when the slot file names no archetype at all,
   *     which four repositories on the estate do today
   */
  private record ReleaseSlots(
      boolean unreadable, String document, CiReleaseArchetypes.ArchetypeRef archetype) {

    /**
     * Not a release event, or the repository commits no slot file: nothing composes, and that is
     * final.
     */
    static final ReleaseSlots NONE = new ReleaseSlots(false, null, null);

    /**
     * The file is there and composes no run for this event — a declaration, and equally final.
     *
     * <p><b>The same value as {@link #NONE}, and still spelled separately.</b> They stopped
     * differing when the supersession went: {@code present} existed so that a slot file which was
     * there but unusable would still keep the legacy trigger files from firing, and there are no
     * legacy trigger files left. What is left is two call sites saying two different things about
     * one repository, and a reader of {@link #releaseSlots} should be able to tell "declares
     * nothing" from "declares nothing for THIS event" without counting nulls.
     */
    static final ReleaseSlots NO_RUN = new ReleaseSlots(false, null, null);

    /**
     * A release event that names no revision: nothing was read, nothing composes, and the event is
     * <b>settled</b>.
     *
     * <h2>Why this settles where {@link #UNREADABLE} does not</h2>
     *
     * <p>The three outcomes beside it divide on one question — <em>can asking again produce a
     * different answer?</em> — and this one divides with the finals rather than with the blip.
     * {@link #UNREADABLE} is a git host that did not answer: the question stands, nothing was
     * learned, and a sweep minutes later is a different question with a different answer, so the
     * event stays owed. This is a payload that carries no usable sha — the field is absent, or it is
     * there and {@link CiIdentifiers#requireSha} refuses it. <b>An event is a record of something
     * that happened and its bytes are immutable</b>: the field will be absent on the ten thousandth
     * offer exactly as it was on the first, so leaving the event owed hands the consumer a row
     * nothing can ever clear, with the watermark stuck behind it and every later event of the stream
     * re-evaluated on every sweep for as long as the deployment lives. That is the opposite failure
     * to the one the owed ledger exists for, and a strictly worse one: the QA verdict is lost either
     * way, and the estate gets a wedged consumer with it.
     *
     * <p>So it is the same verdict {@link #NONE} and {@link #NO_RUN} are — final, no run, settled —
     * and it is spelled separately for their reason: three call sites saying three different things
     * about one evaluation, and a reader should be able to tell "this repository declares no release
     * cycle" from "this EVENT named no revision to read one at" without counting nulls. The
     * difference matters to a person reading the log, because only one of the three is a defect
     * somewhere else on the platform — a publisher that stopped carrying its own commit.
     *
     * <p><b>Settled is not "handled".</b> This state is unreachable by construction on the live
     * path ({@link #releaseRevision} carries the argument, including why a conflicted release
     * request emits nothing at all), so it is reported at ERROR as a broken invariant. Settling is
     * only about not spinning on it: a malformed event that cannot be evaluated must not be offered
     * forever, and it is the one choice here that is about the ledger rather than about the run.
     */
    static final ReleaseSlots NO_REVISION = new ReleaseSlots(false, null, null);

    /** The question could not be asked. The only answer a later sweep can improve on. */
    static final ReleaseSlots UNREADABLE = new ReleaseSlots(true, null, null);
  }

  /**
   * Reads, resolves and compiles a candidate's release slots.
   *
   * <p><b>Gated on the two release event names</b>, which is the whole of what this feature costs an
   * ordinary event: nothing. A {@code BuildSuccessful} evaluates exactly the reads it always did.
   *
   * <p><b>Read at the revision the event is about</b>, which {@link #releaseRevision} resolves once
   * per evaluation: the request's fold for a {@code ReleaseRequestChanged}, the released tag's
   * commit for an {@code SCMRelease}, and <b>nothing at all</b> for a release event that names
   * neither — such an event reads no file and composes no run ({@link ReleaseSlots#NO_REVISION}),
   * where it used to fall back to {@code main}'s head. Never a branch NAME — the listing's own
   * discipline, for the listing's own reason: a run must never be recorded against one commit with a
   * declaration from another. <b>The pipeline that gates a revision is read from that revision</b>,
   * and it is the same revision the run checks out, so nothing is composed from one commit and
   * executed against another.
   *
   * <p><b>The WRAPPER's archetype recipe is not part of that and stays at the wrapper's own {@code
   * main}</b> ({@code wrapper}, resolved once per evaluation by {@link #readWrapper}). That is a
   * different repository: it is no part of the release request, nobody approves it through this
   * request's gate, and the prelude and postlude it contributes are platform process. Reading it at
   * the revision under test would let a branch rewrite the platform's half of its own gate. So the
   * repository's declaration moves with the repository and the platform's share does not, and the
   * run row records both revisions — {@code branch}/{@code commit_sha} for the first, {@code
   * archetype_rev} for the second — so which recipe met which commit is readable afterwards.
   *
   * <p><b>ABSENT and UNREACHABLE are still different answers, and the distinction is more
   * load-bearing now rather than less.</b> {@code ABSENT} is a 404 at a rev the host has already
   * resolved, so it is the repository saying it declares no release cycle at all — {@code
   * qits-eventstream-javalib} and every other repository whose releases are somebody else's business
   * — and it is final. {@code UNREACHABLE} is a blip, and it used to be read as absent on the
   * argument that this costs a migrated repository nothing, since it had no legacy file left for the
   * fallback to find. <b>That reasoning inverted the day the fleet finished migrating.</b> There is
   * no fallback and no legacy file anywhere: {@code release.yml} IS the release pipeline, so reading
   * a blip as "no slot file" silently answers "this repository declares no QA" for a repository
   * whose release request is at that moment waiting for exactly that QA's verdict — every repository
   * on the platform, not the unmigrated ones. So the read is reported as unread, the evaluation
   * stays owed, and a sweep asks again.
   *
   * <p><b>A 404 at the event's own revision is still {@code ABSENT} and still final</b>, and that is
   * the same sentence rather than a new leniency. The host answered; it holds no {@code release.yml}
   * at that commit — because the repository declares none there, or because the commit itself is not
   * one it holds, which for a fold that was announced and a tag that was cut means the ref is gone
   * rather than late. Neither improves by asking again, and every candidate that is not the one this
   * release event names answers exactly this way, since the payload's commit lives in one repository
   * only. A blip is the other status and is the one that leaves the event owed.
   *
   * @param revision the revision this event is about — {@link #releaseRevision}'s answer, resolved
   *     once for the whole evaluation, and the revision the run this composes will check out. Null
   *     for every event that is not a release event; carrying no sha when the release event named
   *     none
   */
  private ReleaseSlots releaseSlots(
      CiRepoRef repo,
      String repoId,
      Arrival arrival,
      ReleaseRevision revision,
      ArchetypeReads wrapper) {
    if (revision == null) {
      return ReleaseSlots.NONE;
    }
    String rev = revision.sha();
    if (rev == null) {
      // NO REVISION, NO READ, NO RUN — and the reasoning is ReleaseSlots.NO_REVISION's. There is
      // nowhere to read the repository's declaration AT: the event named no commit, and the one
      // thing this must never do is answer "main" to that question, because the pipeline that gates
      // a revision is read from that revision and main is a different one. The field that was
      // missing or refused was named in one ERROR by releaseRevision, once for the evaluation.
      return ReleaseSlots.NO_REVISION;
    }
    CiConfigSource.FileLookup found =
        configSource.readFile(repo, rev, CiReleaseSlotParser.CONFIG_PATH);
    if (found.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
      LOG.warnf(
          "%s: %s could not be read at %s — no release pipeline was composed and this event stays"
              + " owed, so a sweep evaluates it again",
          repoId, CiReleaseSlotParser.CONFIG_PATH, rev);
      return ReleaseSlots.UNREADABLE;
    }
    if (found.status() != CiConfigSource.FileLookup.Status.FOUND) {
      return ReleaseSlots.NONE;
    }
    ComposeAttempt attempt =
        attemptCompose(repo, repoId, found.content(), wrapper, "no release run");
    if (attempt.outcome() == ComposeOutcome.ARCHETYPE_UNREADABLE && wrapper.unlistable()) {
      // THE WRAPPER COULD NOT BE READ AT ALL, which is a verdict about nothing — the {@code
      // UNREACHABLE} slot file's case one repository over. Every other ARCHETYPE_UNREADABLE is
      // committed content (the slot file names a recipe the wrapper does not carry, or carries a
      // broken one) and is final and settled; this one is a git host that did not answer, so the
      // evaluation is incomplete and the event stays owed for the sweep. Collapsing the two would
      // hang a release request on a blip exactly as reading an UNREACHABLE release.yml as ABSENT
      // used to.
      return ReleaseSlots.UNREADABLE;
    }
    if (attempt.composed() == null) {
      return ReleaseSlots.NO_RUN;
    }
    String document = documentFor(attempt.composed(), arrival.eventName());
    // No document is no run, and a run that does not exist records no archetype: the identity is
    // carried only where there is a row to carry it onto.
    return document == null
        ? ReleaseSlots.NO_RUN
        : new ReleaseSlots(false, document, attempt.archetype());
  }

  /**
   * The revision ONE arriving release event is about: the ref it names and the commit that ref
   * points at, as the payload states them.
   *
   * <p>Both halves are nullable and they are missing independently, which is why this is a pair
   * rather than two calls: {@code sha} is what a candidate's {@code release.yml} is read at, {@code
   * ref} is only needed by a trigger that declares no {@code checkout:}, and a payload carrying one
   * and not the other is a real shape of both events.
   *
   * <p><b>Both are validated before they exist.</b> A payload is attacker-shaped — a durable claim
   * establishes delivery, never content — and both values reach a git-host URL, a clone argv and a
   * run row, so a component of this record has already been through {@link CiIdentifiers}. A value
   * that was refused is null here and is indistinguishable from one that was absent, deliberately:
   * every caller owes the same answer to both, and a "present but refused" third state would be a
   * distinction only a caller that wanted to use it anyway could spend.
   *
   * @param ref the ref name the event is about — a release request's backing branch, or a release's
   *     tag, whose name IS the version
   * @param sha the commit that ref points at
   */
  private record ReleaseRevision(String ref, String sha) {}

  /**
   * <b>The revision a release event is about — the one the run this evaluation may record will
   * really check out — or nothing at all.</b> Null for every event that is not one of the two
   * release events.
   *
   * <h2>The invariant</h2>
   *
   * <p><b>The pipeline that gates a revision is read from that revision.</b> A run must never be
   * composed from one commit and executed against another. The two release events each name the
   * commit they are about, the composed {@code checkout:} spends exactly that pair, and this method
   * is what puts the {@code release.yml} read on the same commit:
   *
   * <ul>
   *   <li>{@code ReleaseRequestChanged} → the request's FOLD, {@code payload.mergedSha} ({@link
   *       CiReleaseComposer#RELEASE_REQUEST_SHA_PATH}) — the branch nobody pushed that the QA run
   *       clones.
   *   <li>{@code SCMRelease} → the released TAG's commit, {@code payload.commitSha} ({@link
   *       CiReleaseComposer#RELEASE_SHA_PATH}). The tag ref and that commit are one revision, and
   *       the sha is the one the run records and the daemon detaches at.
   * </ul>
   *
   * <p>It used to read {@code main}'s head, which broke in two directions at once. A repository
   * could never ship its own <em>first</em> {@code release.yml}: the tag declared a {@code release:}
   * slot, qits-projects stamped the request publish-gated on the strength of it ({@link
   * #releasePhaseAt}, which has always read at the rev it was asked about), and the run was composed
   * from a {@code main} that carried no such file — no document, no run, the event settled, and the
   * request RELEASED forever with nothing left to re-drive it. Measured 2026-09-22 on
   * qits-landing-app. And in the ordinary case a change to {@code release.yml} was never exercised
   * by the release that carried it; it took effect on the next one, which is a gate reviewing a
   * pipeline nobody ran.
   *
   * <p><b>The wrapper's archetype recipe stays at the WRAPPER's own {@code main}</b>, and that split
   * is deliberate rather than an omission — see {@link #releaseSlots}.
   *
   * <h2>There is NO fallback to the head, and that parameter is gone</h2>
   *
   * <p>This method took a {@code headSha} and answered it whenever the payload carried no usable
   * sha, on the argument that the composed {@code optional: true} arm would build main's head
   * anyway, so reading the file there was reading it at the rev the run builds. <b>That argument
   * justified the defect with the defect.</b> A release pipeline composed from {@code main} is a
   * pipeline nobody released reviewing a commit nobody released, and it gates — or waves through —
   * the revision that really was released. Owner ruling, twice: the pipeline is composed from the
   * revision actually being built, and no per-step case falls back to {@code main} or synthesises a
   * revision that corresponds to nothing real.
   *
   * <p>So a release event with no usable revision composes <b>nothing</b>: no read, no document, no
   * run, one ERROR naming the event, its name and the field that was missing or refused. The
   * parameter is deleted rather than passed and ignored, so the fallback cannot come back by
   * somebody reaching for a value that is in scope.
   *
   * <h2>A release event with no sha is a BROKEN INVARIANT, not a state to handle</h2>
   *
   * <p><b>It is unreachable by construction on the live path, and the fallback invented the
   * scenario it then resolved wrongly.</b> For {@code ReleaseRequestChanged}, qits-projects
   * announces from the fold path alone — {@code ReleaseRequestAnnouncer} calls {@code mergedSha}
   * "the tip of the fold: what to build, gate and release" — so a fold that produced nothing
   * announces nothing, and a fold that could not be made at all leaves the request {@code
   * CONFLICTED}: no ref moved, the conflict is stored, and {@code ReleaseRequests} never re-folds or
   * re-announces such a request until a push clears it. A frozen request emits nothing that could
   * run. So the old fallback did not protect a real scenario; it turned "nothing should run" into "a
   * pipeline composed from {@code main} ran against a tree the request is not, and reported a
   * verdict about it".
   *
   * <p>The one genuinely absent case is a legacy {@code SCMRelease} minted before {@code commitSha}
   * existed — <b>schema evolution, not a live state</b> — and it gets the same answer for the same
   * reason: a release run at {@code main}'s head is a run about a commit nobody released.
   *
   * <p><b>Do not add a defensive default here.</b> Every value this could fall back to is a
   * revision the event is not about, and the whole point of the read below is that the pipeline
   * gating a revision comes from that revision.
   *
   * <p><b>The event is SETTLED rather than left owed</b>, and the reasoning is {@link
   * ReleaseSlots#NO_REVISION}'s: a payload is immutable, so a missing field is missing forever and
   * an owed row for it is a row nothing can ever clear.
   *
   * <p><b>Both values are validated before they can reach a URL.</b> An event payload is
   * attacker-shaped — a durable claim establishes delivery, never content — and the sha becomes a
   * path segment in a git-host read while the ref becomes a clone argument, so both go through
   * {@link CiIdentifiers} here. A refused value is reported as absent rather than thrown: this runs
   * once for the whole evaluation and a throw would cost every candidate its other trigger files.
   */
  private ReleaseRevision releaseRevision(Arrival arrival, JsonNode payload) {
    if (!RELEASE_EVENTS.contains(arrival.eventName())) {
      return null;
    }
    boolean releaseRequest = CiReleaseComposer.RELEASE_REQUEST_EVENT.equals(arrival.eventName());
    String refPath =
        releaseRequest
            ? CiReleaseComposer.RELEASE_REQUEST_BRANCH_PATH
            : CiReleaseComposer.RELEASE_BRANCH_PATH;
    String shaPath =
        releaseRequest
            ? CiReleaseComposer.RELEASE_REQUEST_SHA_PATH
            : CiReleaseComposer.RELEASE_SHA_PATH;
    String ref =
        usable(arrival, refPath, checkoutField(payload, refPath), CiIdentifiers::requireBranch);
    String sha =
        usable(arrival, shaPath, checkoutField(payload, shaPath), CiIdentifiers::requireSha);
    if (sha == null) {
      // A BROKEN INVARIANT, said out loud, and the only line this method makes. A release event is
      // expected to carry the revision it is about — see this method's javadoc for why no live
      // emitter produces one without it — so this is not a shape to accommodate. It names the
      // event, its name and the field, so the cause is readable from this line alone: a publisher
      // that stopped carrying its own commit is somebody else's defect and this is where the
      // platform notices it, instead of quietly gating whatever main happened to hold.
      LOG.errorf(
          "Event %s (%s) carries no usable %s: a release event is expected to carry the revision it"
              + " is about. No release pipeline is composed for any candidate, and the event is"
              + " settled rather than owed — a payload cannot grow the field later",
          arrival.eventId(), arrival.eventName(), shaPath);
    }
    return new ReleaseRevision(ref, sha);
  }

  /**
   * One payload field, or null when it is absent or refused — the shared half of {@link
   * #releaseRevision}, so that a ref and a sha are read and validated by one piece of code.
   *
   * <p>A refused value is a DEBUG rather than a second WARN: the caller warns once per event about
   * what it could not resolve, and a value that is there and hostile is worth naming in a log
   * somebody turns up rather than in the line everybody reads.
   */
  private static String usable(
      Arrival arrival, String path, String declared, java.util.function.Consumer<String> guard) {
    if (declared == null) {
      return null;
    }
    try {
      guard.accept(declared);
    } catch (RuntimeException refused) {
      LOG.debugf(
          "Event %s (%s) carries an unusable %s: %s",
          arrival.eventId(), arrival.eventName(), path, refused.getMessage());
      return null;
    }
    return declared;
  }

  /**
   * What a trigger declaring no {@code checkout:} is recorded at, or null when there is no such
   * revision and the file therefore gets no run.
   *
   * <p><b>Two answers, and the second is not the first weakened.</b>
   *
   * <ul>
   *   <li><b>A release event</b> is about one revision, and that revision is what its run builds —
   *       the pair {@link #releaseRevision} resolved, which is the same pair {@link
   *       CiReleaseComposer} emits into a composed {@code checkout:}. So a repository's bespoke
   *       release pipeline and the composed one are recorded at one commit, and a release run can
   *       never be dispatched at {@code main}: a fold lives on a branch nobody pushed and a tag's
   *       commit need not be on {@code main} at all, so a row saying {@code main@<head>} named a
   *       revision the event was not about and a clone the daemon could not resolve the sha in.
   *       Null when the event named no usable pair, which is one WARN and no run at the call site.
   *   <li><b>Every other event</b> names no revision in THIS repository, so {@code main}'s head is
   *       not a fallback — it is the only revision that exists. A {@code BuildSuccessful}, or an
   *       {@code SCMRelease} from a <em>different</em> repository, says nothing whatever about what
   *       this repository should build; the tracked branch supplied by convention is the defined
   *       answer and {@link #TRIGGER_BRANCH}'s javadoc is where that convention lives. Reading this
   *       arm as the same thing that was removed above is the mistake worth guarding against: there
   *       the event DID name a revision and the code ignored it.
   * </ul>
   *
   * <p><b>One consequence is worth knowing before writing a bespoke trigger.</b> The split is by
   * event NAME, so a file that selects {@code SCMRelease} in order to react to ANOTHER repository's
   * release — a downstream bump, say — and declares no {@code checkout:} is now recorded at that
   * other repository's tag and commit, which are not refs of its own. No recipe on the estate has
   * that shape today (a composed release document's {@code when:} always names its own repository,
   * and the bespoke files that exist select their own), and such a file should declare the checkout
   * it really wants. {@code SoftwareRelease} — the event a downstream consumer actually reacts to —
   * is not a release event here and takes the second arm unchanged.
   *
   * @param revision {@link #releaseRevision}'s answer — null for every non-release event
   * @param headSha the head {@link #TRIGGER_BRANCH} resolved to for this candidate. Read by the
   *     second arm only; the release arm never sees it, which is what keeps the deleted fallback
   *     deleted rather than one line away
   */
  private static DefaultCheckout defaultCheckout(ReleaseRevision revision, String headSha) {
    if (revision == null) {
      return new DefaultCheckout(TRIGGER_BRANCH, headSha);
    }
    return revision.ref() == null || revision.sha() == null
        ? null
        : new DefaultCheckout(revision.ref(), revision.sha());
  }

  /** The {@code (ref, sha)} pair a run with no declared checkout is accepted at. */
  private record DefaultCheckout(String branch, String sha) {}

  /**
   * Which of the four ways a composition attempt ended. The three failures are one {@code null} to
   * {@link #compose}, and they are told apart here for the caller that must not collapse them — see
   * {@link #releasePhaseAt}.
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
   * One composition attempt: what came of it, the pair when there is one, and the sentence a caller
   * can put in front of a person.
   *
   * <p>The detail is built here rather than at the call site because it is the same fact the WARN
   * already names — the file, the archetype, the parser's own message — and a second spelling of it
   * would be a second thing to keep in step with the log.
   *
   * <p><b>Public for {@link ComposeOutcome}'s reason.</b> It carried a fourth component,
   * {@code declaredArtifacts} — every {@code artifacts:} entry either document declared, as a lookup
   * table by coordinate — for exactly one reader, the composed-versus-committed read that retired
   * with the split release pipeline. Nothing else ever wanted it: a composed {@code artifacts:}
   * block is what the run really declares, and that travels on the document.
   *
   * <p><b>{@code archetype} is what the composition really used, and it is what a run records.</b>
   * Null on every failure — nothing was composed, so nothing was used — and <b>also null on a
   * successful composition whose slot file names no {@code archetype:} at all</b>, which is a
   * legitimate shape rather than a gap: four repositories on the estate declare both their slots
   * themselves today. A reader must not conflate that null with "unknown"; the run either was
   * composed from a recipe, in which case all three of the recipe's strings are there, or it was
   * composed from the repository's own document alone.
   */
  public record ComposeAttempt(
      ComposeOutcome outcome,
      CiReleaseComposer.Composed composed,
      String detail,
      CiReleaseArchetypes.ArchetypeRef archetype) {

    /** One of the three failures: no pair, no archetype used, and the sentence behind it. */
    static ComposeAttempt failed(ComposeOutcome outcome, String detail) {
      return new ComposeAttempt(outcome, null, detail, null);
    }
  }

  /**
   * Parses one repository's slot file, reads whatever archetype it names <b>at the wrapper's
   * resolved sha</b>, and compiles the pair — saying which of the three ways it failed rather than
   * only that it did, and which recipe it used when it did not fail.
   *
   * <p><b>Extracted rather than copied.</b> The two evaluation callers want a null and a WARN; the
   * release-phase read wants the distinction, because "this repository's pipeline is broken" and
   * "qits-ci could not read the wrapper" are opposite answers there — one is a pipeline somebody
   * must fix, the other is a question this instance could not ask at all. A second copy of the
   * parse/read/compile sequence would be a second place for the archetype branch to drift.
   *
   * <p><b>No wrapper sha is {@link ComposeOutcome#ARCHETYPE_UNREADABLE}, and there is deliberately
   * no fallback.</b> A wrapper whose listing did not answer has no head this evaluation can name, so
   * a repository that asks for a recipe gets the same outcome it gets when the recipe file itself
   * cannot be read: no run, and — on the evaluation path — an event left owed for the sweep. The
   * tempting alternative is to read at {@link #TRIGGER_BRANCH} instead, which is exactly the moving
   * ref this arrangement removes: it would reintroduce it silently, only under a flaky git host, and
   * only for the repositories whose composition mattered most.
   */
  private ComposeAttempt attemptCompose(
      CiRepoRef repo,
      String repoId,
      String slotFile,
      ArchetypeReads wrapper,
      String consequence) {
    CiReleaseSlots slots;
    try {
      slots = slotParser.parse(CiReleaseSlotParser.CONFIG_PATH, slotFile);
    } catch (CiConfigException e) {
      LOG.warnf(
          "%s: %s is not a usable release slot file: %s — %s",
          repoId, CiReleaseSlotParser.CONFIG_PATH, e.getMessage(), consequence);
      return ComposeAttempt.failed(
          ComposeOutcome.UNPARSEABLE,
          CiReleaseSlotParser.CONFIG_PATH + " is not a usable release slot file: " + e.getMessage());
    }
    CiReleaseSlots archetypeSlots = null;
    CiReleaseArchetypes.ArchetypeRef used = null;
    if (slots.namesArchetype()) {
      if (wrapper.repo() != null && wrapper.rev() == null) {
        // FAIL CLOSED. There is a wrapper and its listing did not answer, so no sha exists to read
        // the recipe at. Reading at the branch name would compose from whatever main happens to be
        // when the read lands, which is the thing this whole path stopped doing.
        LOG.warnf(
            "%s: %s names release archetype '%s', but %s@%s could not be listed, so there is no"
                + " revision to read the recipe at — %s",
            repoId,
            CiReleaseSlotParser.CONFIG_PATH,
            slots.archetype(),
            wrapper.repo().display(),
            TRIGGER_BRANCH,
            consequence);
        return ComposeAttempt.failed(
            ComposeOutcome.ARCHETYPE_UNREADABLE,
            CiReleaseSlotParser.CONFIG_PATH
                + " names release archetype '"
                + slots.archetype()
                + "', and the platform-pipelines repository could not be read at all, so there is no"
                + " revision to read that recipe at");
      }
      // A null wrapper repository falls through here on purpose: CiReleaseArchetypes.read already
      // owns that case — "this deployment has no platform-pipelines repository" — and never touches
      // the rev to say it.
      Optional<CiReleaseArchetypes.Archetype> recipe = wrapper.read(slots.archetype());
      if (recipe.isEmpty()) {
        // CiReleaseArchetypes has already said which of the four ways it failed; this line is what
        // names the repository that asked, which that class deliberately does not hold.
        LOG.warnf(
            "%s: %s names release archetype '%s', which could not be read — %s",
            repoId, CiReleaseSlotParser.CONFIG_PATH, slots.archetype(), consequence);
        return ComposeAttempt.failed(
            ComposeOutcome.ARCHETYPE_UNREADABLE,
            CiReleaseSlotParser.CONFIG_PATH
                + " names release archetype '"
                + slots.archetype()
                + "', which could not be read from the platform-pipelines repository");
      }
      archetypeSlots = recipe.get().slots();
      used = recipe.get().ref();
    }
    try {
      return new ComposeAttempt(
          ComposeOutcome.COMPOSED,
          CiReleaseComposer.compose(repo, slots, archetypeSlots),
          null,
          used);
    } catch (CiConfigException e) {
      LOG.warnf(
          "%s: %s could not be composed into a release pipeline: %s — %s",
          repoId, CiReleaseSlotParser.CONFIG_PATH, e.getMessage(), consequence);
      return ComposeAttempt.failed(
          ComposeOutcome.UNCOMPOSABLE,
          CiReleaseSlotParser.CONFIG_PATH
              + " could not be composed into a release pipeline: "
              + e.getMessage());
    }
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
   * <p><b>The wrapper's head is resolved AT RETRY TIME, and that is the point rather than an
   * accident of where the code sits.</b> The repository's half is pinned to the commit the source
   * run built; the platform's half is deliberately today's, so the recipe is read at whatever sha
   * the wrapper's {@code main} names <em>now</em>. Pinning a retry to the source run's archetype
   * revision would re-run the broken prelude and close exactly the loop this method exists to open.
   * The two revisions on the two rows are therefore the answer to "did the recipe move", which is
   * the reason they are recorded at all.
   *
   * @param repo the repository the run was recorded against
   * @param rev the run's own commit — never a branch name, which moves
   * @param eventName the run's triggering event, which picks the QA half or the release half
   */
  public ComposedPipeline recomposedReleaseDocument(CiRepoRef repo, String rev, String eventName) {
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
    ComposeAttempt attempt =
        attemptCompose(
            repo,
            repoId,
            found.content(),
            readWrapper(candidateRepos.candidates()),
            "this retry replays the pipeline stored on the run it re-fires");
    if (attempt.composed() == null) {
      return null;
    }
    String document = documentFor(attempt.composed(), eventName);
    return document == null ? null : new ComposedPipeline(document, attempt.archetype());
  }

  /**
   * A composed trigger document and the recipe it came from — what a caller writing a run row needs,
   * which is both halves rather than the text alone.
   *
   * <p>{@code archetype} is null when the slot file names none, and a caller records three nulls for
   * such a run rather than treating the absence as a failure to look it up.
   */
  public record ComposedPipeline(
      String document, CiReleaseArchetypes.ArchetypeRef archetype) {}

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
   * <p><b>The repository's half is read at {@code rev}, the platform's at the sha the wrapper's
   * {@code main} resolves to when this read is made</b> — {@link #recomposedReleaseDocument}'s
   * split, for its reason, and this door resolves that head itself with one listing of its own,
   * since it is outside any evaluation. So this is an answer
   * about the pipeline <em>as it composes now</em>, not as it composed when the tag was cut: an
   * archetype that gains or loses its {@code release:} slot changes what this read says about a tag
   * whose own bytes never moved. That is the wanted direction, since the run that would satisfy the
   * gate would be composed now too.
   *
   * <p><b>This side was always the correct one and the evaluation now agrees with it.</b> The gate
   * asks about {@code refs/tags/<version>} and this read has always composed at that rev, while the
   * run composition read the repository's {@code release.yml} at {@code main} — so a tag that
   * declared a {@code release:} slot {@code main} did not was stamped publish-gated here and
   * composed nothing there, and the request sat RELEASED with a gate nothing would ever answer.
   * {@link #releaseRevision} put the evaluation on the event's own revision; both halves of the split are
   * now identical on both sides (the repository's declaration at the rev under test, the wrapper's
   * recipe at the wrapper's {@code main}), which is what makes this answer a prediction of what the
   * run will do rather than a second opinion about it. Nothing here changed to get there.
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
            readWrapper(candidates),
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

  /**
   * The platform-pipelines repository as a candidate, or null when the feature is off or the
   * catalogue does not hold it. No logging: both callers say what a null means in their own terms,
   * and one of them (the archetype read) only cares when a repository actually asked for a recipe.
   */
  private CiRepoRef platformRepo(List<CiRepoRef> candidates) {
    String configured = platformPipelinesRepository;
    return configured.isEmpty() ? null : find(candidates, configured);
  }

  /**
   * <b>The wrapper half of one composition pass: which repository, at which sha, and what has
   * already been read out of it.</b>
   *
   * <p>It exists so that the wrapper is resolved and listed <b>once</b> and then travels as one
   * value. The alternative was a {@code CiRepoRef} and a {@code String rev} threaded side by side
   * through five signatures, which is two things that must always agree and nothing to make them.
   *
   * <p><b>{@link #rev()} null is the fail-closed state and the whole of the discipline.</b> There is
   * a sha only when the wrapper's own trigger listing came back {@code FOUND}; an {@code
   * UNREACHABLE} one leaves no sha, and a caller that finds none must refuse to compose rather than
   * fall back to the literal branch name. Falling back would silently reintroduce the moving ref
   * this whole arrangement removes — and it would do so exactly when the git host is flaky, which is
   * when two candidates are most likely to see two different recipes.
   *
   * <p><b>The memo is free and is the reason this is a class rather than a record.</b> Twenty
   * repositories on {@code java-service} were twenty identical HTTP fetches of one file per
   * evaluation. With a moving ref memoising them would have been a lie about what was read; with a
   * resolved sha the bytes cannot change under it, so one read per {@code (wrapper, sha, name)} is
   * the same answer by construction. It is scoped to one pass and thrown away with it — there is no
   * cache here to invalidate, no clock and no bean.
   */
  private final class ArchetypeReads {

    private final CiRepoRef platformRepo;

    /** The wrapper's platform-scope trigger listing, or null when there is no wrapper to list. */
    private final EventTriggerLookup listing;

    private final Map<String, Optional<CiReleaseArchetypes.Archetype>> memo = new HashMap<>();

    private ArchetypeReads(CiRepoRef platformRepo, EventTriggerLookup listing) {
      this.platformRepo = platformRepo;
      this.listing = listing;
    }

    CiRepoRef repo() {
      return platformRepo;
    }

    EventTriggerLookup listing() {
      return listing;
    }

    /** The sha {@link #TRIGGER_BRANCH} resolved to, or null when the wrapper could not be read. */
    String rev() {
      return listing == null || listing.status() != EventTriggerLookup.Status.FOUND
          ? null
          : listing.headSha();
    }

    /**
     * There <b>is</b> a wrapper and it could not be read — the one state that is a statement about
     * the git host rather than about anybody's committed bytes, and therefore the one an evaluation
     * leaves its event owed for. No wrapper at all is not this: that is a deployment's own decision
     * and is as final as a declaration.
     */
    boolean unlistable() {
      return platformRepo != null && rev() == null;
    }

    /** One recipe, read at {@link #rev()} and remembered for the rest of this pass. */
    Optional<CiReleaseArchetypes.Archetype> read(String name) {
      return memo.computeIfAbsent(name, asked -> archetypes.read(platformRepo, rev(), asked));
    }
  }

  /**
   * Resolves the wrapper and lists it — <b>one</b> listing, which is the whole cost of this.
   *
   * <p>Factored out because three callers need a wrapper sha and none of them may invent one: the
   * evaluation (which also uses the listing for its platform pass, so nothing is read twice), the
   * retry's re-composition, and the release-phase read. The two outside the evaluation each pay
   * their own listing, which is accepted: they are one operator or peer request each, not a fan-out.
   *
   * <p><b>A null platform repository lists nothing.</b> The feature is off, or this deployment's
   * configured repository is not in the catalogue — either way there is nothing to ask, and {@code
   * CiReleaseArchetypes.read} already says what a null wrapper means to a repository that asked for
   * a recipe.
   */
  private ArchetypeReads readWrapper(List<CiRepoRef> candidates) {
    CiRepoRef platformRepo = platformRepo(candidates);
    return new ArchetypeReads(
        platformRepo,
        platformRepo == null
            ? null
            : configSource.readEventTriggers(platformRepo, TRIGGER_BRANCH, CiTriggerScope.PLATFORM));
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
   * <p><b>The listing is handed in rather than made here</b>, because the same one resolves the sha
   * every archetype read of this evaluation is made at — see {@code ArchetypeReads}. It used to be
   * read at the top of this method, which is what made the wrapper's head a fact only the platform
   * pass held and left the archetype reads with nothing but the branch name to go on.
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
      ArchetypeReads wrapper,
      Map<String, String> heads,
      List<String> runIds,
      List<String> unreadable) {
    String configured = platformPipelinesRepository;
    if (configured.isEmpty()) {
      // Off, and off means no read at all.
      return;
    }
    if (wrapper.repo() == null) {
      // WARN rather than DEBUG, unlike the per-candidate reads: this repository is named in this
      // deployment's own config, so a missing one is a misconfiguration that silently disables every
      // platform pipeline, and it can be acted on.
      LOG.warnf(
          "The platform-pipelines repository %s is not in the catalogue — no platform pipeline was"
              + " evaluated for event %s",
          configured, arrival.eventId());
      return;
    }
    EventTriggerLookup lookup = wrapper.listing();
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
      String runId;
      try {
        runId =
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
                    file.content(),
                    // A platform pipeline is a committed file like a repository's own: no
                    // composition, so no archetype to record.
                    null));
      } catch (CiRunService.StepImageUnpinned unpinned) {
        // The candidate pass's arm, for the file that acts on the whole catalogue: no run, and the
        // event is left owed rather than settled. The repository the row goes on is the one the
        // payload named, so that is the one the owed list carries — a sweep re-evaluates the whole
        // event and the dedupe refuses everything that did record a run.
        LOG.warnf(
            "%s: %s declares step image %s, whose digest could not be resolved — no run for %s, and"
                + " event %s (%s) stays owed so a sweep re-evaluates it",
            configured,
            file.path(),
            unpinned.reference(),
            target.display(),
            arrival.eventId(),
            arrival.eventName());
        unreadable.add(target.repoId());
        continue;
      }
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
   * retry this ledger owes — <b>unless it returned naming a candidate whose {@code release.yml} it
   * could not read</b>, which is the one returning outcome that is worth asking again. Such a row is
   * left owed and is <em>not</em> counted as recovered, so the sweep's own line says what happened
   * rather than reporting a recovery the next sweep will repeat.
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
        if (!done.repositoriesUnreadable().isEmpty()) {
          LOG.warnf(
              "Owed event %s (%s) was re-evaluated and stays owed: %s could not have %s read, so no"
                  + " release pipeline was composed for it",
              row.eventId,
              row.eventName,
              done.repositoriesUnreadable(),
              CiReleaseSlotParser.CONFIG_PATH);
          continue;
        }
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
