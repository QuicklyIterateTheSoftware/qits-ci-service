package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.error.UnavailableException;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>A runner's standing</b>: when one is taken out of service, how it proves itself healthy again,
 * and what a connected runner is told about either (qits-466, epic qits-440).
 *
 * <p><b>Quarantine.</b> A runner is a machine a person owns, and when it breaks it breaks for every
 * run it reserves — so a streak of steps that ended {@link #INFRA_OUTCOMES runner-caused} (the
 * container could not be started, its daemon never dialled back, the runner's socket dropped) takes
 * it out once the streak is {@code qits.ci.runner.quarantine.failures} long and spans {@code
 * qits.ci.runner.quarantine.min-runs} distinct runs. A step that started resets the streak, whatever
 * the build then did. A quarantined runner's <em>effective</em> slots are 0 — {@link
 * #effectiveSlots} is what its {@code Ack} carries, every ordinary {@code Reserve} is answered {@code
 * Nothing} ({@code CiRunService.reserveFor}) and the queue's forecast counts none of it — while its
 * row's {@code slots} stays its operator's number, which is what it gets back. A runner that has just
 * registered starts out quarantined ({@code CiRunners.AWAITING_FIRST_HEALTH_CHECK}): it has proved it
 * holds a token, not that it can build.
 *
 * <p><b>The health check</b> is a pseudo-build ({@code CiRunService.acceptHealthCheck}): one step,
 * {@code echo hello world}, in {@code qits.ci.runner.healthcheck.image}, cloning {@code main} of
 * {@code qits.ci.runner.healthcheck.repository} — exactly the path a runner can break. Only its
 * target runner can take it, and it can take it while quarantined; no local worker ever claims it; it
 * announces no build event, gates nothing and is in no listing but its own id. Green records {@code
 * PASSED} and reinstates a quarantined runner; red records {@code FAILED} with the step's outcome and
 * the head of its output, and quarantines the runner ({@code health check failed: <outcome>}) or
 * keeps it so. One is queued when a runner registers, when an operator asks ({@link
 * #requestHealthCheck}), and by {@link #sweep} every {@code qits.ci.runner.healthcheck.interval} for
 * each quarantined, connected runner with none pending; one its runner has not taken within {@code
 * qits.ci.runner.healthcheck.queue-timeout} is settled {@code FAILED}. A health check's own steps
 * never count toward the streak — a quarantine is about builds the runner failed, and a check is
 * about the runner already.
 *
 * <p><b>Telling the runner</b> is {@link CiRunnerSignals}': {@code Quarantined} and {@code
 * Reinstated} frames, and a fresh {@code Ack} whenever the slots it may hold moved. They are hints —
 * the rows are the decision, and a runner that missed one reads the rows again at its next {@code
 * Hello}.
 *
 * <p><b>Nothing here may fail the run or the request that caused it.</b> A step's outcome is recorded
 * on a run's driver thread, a settled check on the same, a registration's first check on the register
 * door's request — so each is caught and logged, and the row it could not write is corrected by the
 * next step, the next check or the next sweep. Only the two operator doors answer their errors.
 */
@ApplicationScoped
public class CiRunnerHealth {

  private static final Logger LOG = Logger.getLogger(CiRunnerHealth.class);

  /**
   * The outcomes a step can end with before or outside its build script, through the runner's fault:
   * the runner could not start the container, the container never started its daemon, or the
   * runner's socket was lost. Every other outcome means the daemon registered — the step STARTED.
   */
  public static final Set<StepOutcome> INFRA_OUTCOMES =
      Set.of(StepOutcome.LAUNCH_FAILED, StepOutcome.NEVER_STARTED, StepOutcome.CONNECTION_LOST);

  /** {@code RunnerReinstated.by} when a person pressed greenlight. */
  public static final String BY_ADMIN = "admin";

  /** {@code RunnerReinstated.by} when the runner's own health check passed. */
  public static final String BY_HEALTHCHECK = "healthcheck";

  /** The detail and outcome of a check its runner never took, because it was not there to. */
  public static final String NOT_CONNECTED = "runner not connected";

  /** How many lines of a red check's output its detail keeps, and at most how many characters. */
  static final int DETAIL_LINES = 40;

  static final int DETAIL_CHARS = 4000;

  @Inject CiRunners runners;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunRepository runs;

  @Inject CiRunService runService;

  @Inject CiCandidateRepos candidateRepos;

  @Inject CiConfigSource configSource;

  @Inject CiRunnerPresence presence;

  @Inject CiRunnerSignals signals;

  @ConfigProperty(name = "qits.ci.runner.quarantine.failures")
  int quarantineFailures;

  @ConfigProperty(name = "qits.ci.runner.quarantine.min-runs")
  int quarantineMinRuns;

  @ConfigProperty(name = "qits.ci.runner.healthcheck.repository")
  String healthcheckRepository;

  @ConfigProperty(name = "qits.ci.runner.healthcheck.image")
  String healthcheckImage;

  @ConfigProperty(name = "qits.ci.runner.healthcheck.queue-timeout")
  Duration queueTimeout;

  @ConfigProperty(name = "qits.ci.runner.healthcheck.interval")
  Duration interval;

  // --- what a runner's steps say about it ---------------------------------------------------------

  /**
   * One step of a run ended with {@code outcome}. A build on a runner counts: an {@link
   * #INFRA_OUTCOMES infra outcome} toward a quarantine, any other resets the streak. A local
   * worker's run and a health check say nothing here. Never throws.
   */
  public void stepEnded(CiRun run, StepOutcome outcome) {
    if (run == null || run.runnerId == null || run.healthCheck() || outcome == null) {
      return;
    }
    try {
      if (!INFRA_OUTCOMES.contains(outcome)) {
        runners.recordStarted(run.runnerId);
        return;
      }
      CiRunners.StepRecorded recorded =
          runners.recordInfraFailure(
              run.runnerId, run.id, outcome.name(), quarantineFailures, quarantineMinRuns);
      CiRunner runner = recorded.runner();
      if (recorded.quarantined()) {
        LOG.warnf(
            "Runner %s (%s) is quarantined: %s — it takes no work until a health check passes or an"
                + " operator greenlights it",
            runner.name, runner.id, runner.quarantineReason);
        signals.quarantined(runner.id, runner.quarantineReason, runner.quarantinedAt);
      } else if (runner != null) {
        LOG.infof(
            "Runner %s: step of run %s ended %s — %d runner failure(s) in a row",
            runner.name, run.id, outcome, runner.infraFailures);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Run %s: could not record what its step said about runner %s", run.id, run.runnerId);
    }
  }

  // --- the operator's two doors -------------------------------------------------------------------

  /**
   * Lifts a runner's quarantine and starts its streak from nothing ({@code by} {@value #BY_ADMIN}).
   * A runner in service is answered as it is, its streak reset, and nothing is announced.
   *
   * @throws NotFoundException for no such runner
   */
  public CiRunner greenlight(UUID runnerId) {
    CiRunners.Reinstatement done = runners.reinstate(runnerId, BY_ADMIN);
    if (done.lifted()) {
      LOG.infof("Runner %s (%s) greenlit by an operator", done.runner().name, runnerId);
      signals.reinstated(runnerId, BY_ADMIN);
    }
    return done.runner();
  }

  /**
   * Queues a health check for a runner now, and answers its run.
   *
   * @throws NotFoundException for no such runner
   * @throws ConflictException when one is already queued or running for it
   * @throws UnavailableException when the check's repository or its head could not be read, or its
   *     image could not be pinned — a retry is the right answer to all three
   */
  public CiRun requestHealthCheck(UUID runnerId) {
    CiRunner runner = runners.get(runnerId);
    CiRun pending = pendingHealthCheck(runnerId);
    if (pending != null) {
      throw new ConflictException(
          "Runner " + runner.name + " already has health check " + pending.id + " "
              + pending.status);
    }
    return queueHealthCheck(runner);
  }

  /**
   * The register door's follow-up: a newly registered runner is quarantined awaiting its first
   * health check, and this queues it. Best effort — the door has already answered the runner its
   * client, and a check that could not be queued now is queued by {@link #sweep} once the runner is
   * connected and the interval has passed.
   */
  public void onRegistered(UUID runnerId) {
    try {
      CiRunner runner = runners.get(runnerId);
      if (pendingHealthCheck(runnerId) == null) {
        queueHealthCheck(runner);
      }
    } catch (RuntimeException e) {
      LOG.warnf(
          "Runner %s registered and its first health check could not be queued (%s); the sweep"
              + " queues one once it is connected",
          runnerId, e.getMessage());
    }
  }

  // --- a settled health check ---------------------------------------------------------------------

  /**
   * A health check reached its terminal row. Green records {@code PASSED} and reinstates a
   * quarantined runner; red records {@code FAILED} with {@code detail} and quarantines the runner
   * ({@code health check failed: <outcome>}) unless it already is. {@code RunnerHealthChecked} is
   * announced either way, before whatever the result does to the runner. A CANCELLED check was
   * stopped by a person and settles nothing: no verdict, no event, no change of standing.
   *
   * @param outcome the word a red check's quarantine reason names — the failing step's outcome, or
   *     why it never ran; ignored for a green one
   */
  void healthCheckSettled(CiRun run, CiRunStatus status, String outcome, String detail) {
    UUID target = run.targetRunnerId;
    if (target == null) {
      return;
    }
    try {
      if (status == CiRunStatus.CANCELLED) {
        LOG.infof("Health check %s of runner %s was cancelled — it settles nothing", run.id, target);
        signals.slotsChanged(target);
        return;
      }
      boolean passed = status == CiRunStatus.SUCCESS;
      String word = outcome == null || outcome.isBlank() ? status.name() : outcome;
      String said = passed ? null : detail == null || detail.isBlank() ? word : detail;
      CiRunner recorded = runners.recordHealthCheck(target, run.id, passed, said, Instant.now());
      if (recorded == null) {
        return;
      }
      LOG.infof(
          "Health check %s of runner %s %s%s", run.id, recorded.name, passed ? "passed" : "failed",
          passed ? "" : ": " + word);
      if (passed && recorded.quarantined()) {
        if (runners.reinstate(target, BY_HEALTHCHECK).lifted()) {
          signals.reinstated(target, BY_HEALTHCHECK);
          return;
        }
      } else if (!passed) {
        CiRunner quarantined = runners.quarantine(target, "health check failed: " + word);
        if (quarantined != null) {
          signals.quarantined(target, quarantined.quarantineReason, quarantined.quarantinedAt);
          return;
        }
      }
      // Standing unchanged — but the check held a slot it was granted for, which it no longer does.
      signals.slotsChanged(target);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Health check %s of runner %s could not be recorded", run.id, target);
    }
  }

  /** The outcome word of a step that did not go green: its outcome, a timeout, or its exit code. */
  static String outcomeOf(StepResult result, boolean timedOut) {
    if (timedOut) {
      return "TIMED_OUT";
    }
    if (result.outcome() != StepOutcome.OK) {
      return result.outcome().name();
    }
    return "exit " + result.exitCode();
  }

  /**
   * What a red check's row keeps: its outcome word, then the first {@value #DETAIL_LINES} lines of
   * what the step said — which on a runner carries the container's own log tail, appended by the
   * runner step seam — bounded to {@value #DETAIL_CHARS} characters.
   */
  static String detailOf(StepResult result, boolean timedOut) {
    String word = outcomeOf(result, timedOut);
    String output = result.output() == null ? "" : result.output().strip();
    if (output.isEmpty()) {
      return word;
    }
    String[] lines = output.split("\n", DETAIL_LINES + 1);
    String head = String.join("\n", List.of(lines).subList(0, Math.min(lines.length, DETAIL_LINES)));
    if (head.length() > DETAIL_CHARS) {
      head = head.substring(0, DETAIL_CHARS);
    }
    return word + "\n" + head;
  }

  // --- what a runner may hold ---------------------------------------------------------------------

  /**
   * How many runs the runner may hold right now — what its {@code Ack} carries. Its row's slots, or
   * 0 while it is quarantined; and while a health check is QUEUED for it, at least one more than it
   * holds, because a runner never sends {@code Reserve} past its {@code Ack} and the check has to be
   * taken somehow. 0 for a runner that is gone.
   */
  public int effectiveSlots(UUID runnerId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRunner row = runnerRows.findById(runnerId);
              if (row == null) {
                return 0;
              }
              int base = row.quarantined() ? 0 : Math.max(0, row.slots);
              if (runs.findQueuedHealthCheck(runnerId).isEmpty()) {
                return base;
              }
              long held = runs.countRunningOnRunner(runnerId);
              return (int) Math.max(base, Math.min(Integer.MAX_VALUE, held + 1));
            });
  }

  // --- the schedule -------------------------------------------------------------------------------

  /**
   * The schedule underneath both doors: every {@code qits.ci.runner.healthcheck.sweep-interval}, a
   * {@link #sweep}. {@link Scheduled.ConcurrentExecution#SKIP}, the owed-event sweep's reason — a
   * sweep reads the git host, and two at once are one storm. Delayed by one interval so a booting
   * process does not queue checks before any runner could have dialled back.
   */
  @Scheduled(
      every = "{qits.ci.runner.healthcheck.sweep-interval}",
      delayed = "{qits.ci.runner.healthcheck.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepTick() {
    sweep(Instant.now());
  }

  /**
   * One pass, as of {@code now} — package-private because the tick is stretched out of a suite's
   * way, so this is what a test drives. First, every health check still QUEUED after {@code
   * qits.ci.runner.healthcheck.queue-timeout} is settled {@code FAILED} ({@value #NOT_CONNECTED},
   * or that it was not taken in time if the runner is connected). Then every quarantined, connected
   * runner with no check pending whose quarantine — or newest check, whichever is later — is at
   * least {@code qits.ci.runner.healthcheck.interval} old gets one.
   */
  void sweep(Instant now) {
    try {
      expireQueued(now);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not settle the health checks nobody took");
    }
    List<CiRunner> quarantined;
    try {
      quarantined =
          QuarkusTransaction.requiringNew()
              .call(() -> runnerRows.list("quarantinedAt is not null order by name"));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the quarantined runners");
      return;
    }
    for (CiRunner runner : quarantined) {
      if (!due(runner, now) || !presence.connected(runner.id)) {
        continue;
      }
      try {
        if (pendingHealthCheck(runner.id) == null) {
          queueHealthCheck(runner);
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            "Runner %s is due a health check and it could not be queued: %s", runner.name,
            e.getMessage());
      }
    }
  }

  /** Whether a quarantined runner's last word — its quarantine or its newest check — is stale. */
  private boolean due(CiRunner runner, Instant now) {
    Instant since = runner.quarantinedAt;
    if (runner.lastHealthcheckAt != null && (since == null || runner.lastHealthcheckAt.isAfter(since))) {
      since = runner.lastHealthcheckAt;
    }
    return since == null || !since.plus(interval).isAfter(now);
  }

  private void expireQueued(Instant now) {
    List<CiRun> stale =
        QuarkusTransaction.requiringNew()
            .call(() -> runs.listHealthChecksQueuedBefore(now.minus(queueTimeout)));
    for (CiRun check : stale) {
      CiRun settled = runService.failQueuedHealthCheck(check.id);
      if (settled == null) {
        continue;
      }
      String why =
          check.targetRunnerId != null && presence.connected(check.targetRunnerId)
              ? "runner did not take it within " + queueTimeout
              : NOT_CONNECTED;
      healthCheckSettled(settled, CiRunStatus.FAILED, why, why);
    }
  }

  // --- internals ----------------------------------------------------------------------------------

  private CiRun pendingHealthCheck(UUID runnerId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> runs.findPendingHealthCheck(runnerId).orElse(null));
  }

  /**
   * Resolves the check's repository against the catalogue — name first, storage id second, the
   * platform-pipelines repository's rule — and its {@code main} head the way every event run
   * resolves a branch, then records the run and tells the runner it has a slot to take it with.
   */
  private CiRun queueHealthCheck(CiRunner runner) {
    CiRepoRef repo = find(candidateRepos.candidates(), healthcheckRepository);
    if (repo == null) {
      throw new UnavailableException(
          "The health check repository "
              + healthcheckRepository
              + " is in no catalogue this qits-ci can read (qits.ci.runner.healthcheck.repository)");
    }
    EventTriggerLookup head = configSource.readEventTriggers(repo, CiRunService.MAIN_BRANCH);
    if (head.status() != EventTriggerLookup.Status.FOUND || head.headSha() == null) {
      throw new UnavailableException(
          "The head of " + repo.display() + "@" + CiRunService.MAIN_BRANCH
              + " could not be read for a health check");
    }
    CiRun run;
    try {
      run = runService.acceptHealthCheck(runner.id, repo, head.headSha(), healthcheckImage);
    } catch (CiRunService.StepImageUnpinned unpinned) {
      throw new UnavailableException(unpinned.getMessage());
    }
    signals.slotsChanged(runner.id);
    return run;
  }

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
}
