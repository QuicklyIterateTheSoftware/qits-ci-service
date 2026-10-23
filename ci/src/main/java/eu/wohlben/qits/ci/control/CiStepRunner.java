package eu.wohlben.qits.ci.control;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Executes one pipeline step, and holds the transport a run's steps go through — the one seam asked
 * for since qits-506. <b>A run is one CI lease on an execution target</b> (epic qits-1481): the
 * service module's {@code targethost/LeaseStepRunner} asks qits-system for the run's lease when the
 * run is queued ({@link #queued}), the lease's one container runs {@code qits-ci-daemon}, which dials
 * back once, and every step of the run is a {@code RunStep} on that one connection; {@link
 * #runClosed} sends {@code Close} and ends the lease. <b>Nothing on this side of the seam ever runs
 * a step's script</b> — see the invariant in {@code CLAUDE.md}.
 *
 * <p>The seam survived the swap as a name and as a <em>shape</em>: still exactly one blocking call
 * per step, so {@code CiRunService.runSteps} stays a sequential loop with one transaction per row
 * and each run's driver remains sequential within one pipeline. What changed is that a step now <b>emits events</b>
 * while it runs — chunks as the container produces them, and the two lifecycle instants the host
 * stamps — instead of only answering once at the end.
 *
 * <p>Tests replace this bean with a scripted-event fake ({@code @io.quarkus.test.Mock}) so the
 * suites stay docker-free. Both fakes are scripted: no fake anywhere performs real step semantics,
 * which is what keeps {@code bash -c <repo content>} out of this repository entirely.
 */
public interface CiStepRunner {

  /**
   * Everything one step execution needs — ids and strings only, never entities.
   *
   * <p>{@code repo} is how the step addresses the repository it builds: the storage id it has always
   * carried, plus the public {@code (projectId, name)} pair when the run's announcing push carried
   * one. The implementation turns it into the clone URL and into the step's own {@code
   * QITS_CI_PROJECT_ID}/{@code QITS_CI_REPO_NAME}; with no pair it builds the id-addressed URL this
   * service always built.
   *
   * <p>{@code daemonBinaryUrl} is resolved once per run from {@link #pinDaemon()}; the run's one
   * lease container downloads it. {@code timeoutSeconds} is the step's own deadline — the
   * per-step {@code timeout-seconds} from the pipeline config, or the {@code
   * qits.ci.step-timeout-seconds} default — enforced by the daemon inside the container, with the
   * host holding a longer backstop behind it.
   *
   * <p>{@code image}, {@code docker}, {@code build} and {@code user} are the step's own
   * declarations, carried across untouched and <b>not read by the lease seam</b>: every step runs in
   * the run's one ci-slot container, as its build user, with the target's builder ({@link CiSlot}).
   * A {@code docker: true} pipeline never gets here — it is settled {@code CONFIG_ERROR} before a
   * lease is asked for.
   *
   * <p>{@code env} is <b>run-scoped</b> environment the lease gets on top of the fixed contract:
   * today exactly the {@code QITS_EVENT_*} four an event-triggered run carries, and empty on every
   * push. It is a map rather than four fields because what it holds is a property of the <em>trigger</em>
   * and this seam has no business enumerating triggers; it is emitted in sorted key order so an argv
   * stays something a test can assert literally. <b>It never carries anything a repository authored</b>
   * — the implementation writes the platform's own variables after these, so nothing here can shadow
   * the contract the daemon boots on.
   */
  record StepSpec(
      String runId,
      int stepIndex,
      CiRepoRef repo,
      String branch,
      String sha,
      String image,
      String script,
      String daemonBinaryUrl,
      int timeoutSeconds,
      boolean docker,
      boolean build,
      String user,
      Map<String, String> env) {}

  /**
   * How the step ended, in the vocabulary the orchestrator branches on. Every value here is a
   * <b>distinguishable</b> state rather than a shade of "the step failed" — a run whose container
   * docker refused, one whose container never started a daemon, one whose daemon never finished a
   * checkout, one whose checkout found no such commit, and one whose socket dropped mid-step are
   * five different things, and recording them as one exit code −1 is the thing the plan's
   * failure-state rule forbids.
   *
   * <p>Only {@link #SHA_GONE} carries a semantic beyond the recorded message: it is the daemon's
   * checkout reporting the pushed commit is not in the clone, which is how a force-push between the
   * host's ancestor check and the container's clone surfaces. The orchestrator confirms it against
   * the config source before discarding the run.
   */
  enum StepOutcome {
    /** The step ran. {@code exitCode} and {@code timedOut} are the step's own. */
    OK,
    /** The daemon fetched its mirror but could not check the pushed sha out — the force-push backstop. */
    SHA_GONE,
    /** The daemon reported a structured setup failure that was not a missing commit. */
    INIT_FAILED,
    /** The daemon registered and then said nothing before the initialize deadline. */
    NEVER_INITIALIZED,
    /**
     * The run's lease could not run it: it could not be asked for, or it ended {@code FAILED} or
     * {@code EXITED} before its daemon initialized — the lease's detail says why.
     */
    LAUNCH_FAILED,
    /** The lease was granted and nothing ever dialled back — the bootstrap's own log says why. */
    NEVER_STARTED,
    /** The control socket went away before a terminal frame, or the lease ended mid-step. */
    CONNECTION_LOST
  }

  /**
   * What one step execution produced: the exit code, whether a deadline killed it, which of the
   * lifecycle states it reached, and its bounded output tail.
   *
   * <p>{@code timedOut} is a field rather than an inference from {@code exitCode} because a killed
   * child reports the kill's code (143, or 137 after the grace), which a script that traps a signal
   * can produce on its own. A timeout is recorded as a timeout.
   *
   * <p>{@code output} is <b>already bounded</b> to {@code qits.ci.output-max-chars} — it is
   * accumulated incrementally as chunks arrive, never assembled whole, because a step's output is
   * attacker-controlled and unbounded.
   */
  record StepResult(int exitCode, boolean timedOut, StepOutcome outcome, String output) {}

  /**
   * The step's live events, called on whatever thread the transport delivers them on — so an
   * implementation must not block for long and must not throw for anything it cares about.
   *
   * <p>The two instants are the host's, not the container's: a daemon is hostile from the moment
   * step code runs in it and a clock claim is the cheapest thing to forge, so {@code started_at} and
   * {@code finished_at} are stamped here, at the two points the host knows about first-hand.
   */
  interface StepListener {

    /** The host has sent {@code RunStep} — the step's {@code started_at}. */
    void onStarted();

    /** One chunk of combined output, in the order it arrived. */
    void onChunk(String text);

    /** A terminal frame arrived or a deadline fired — the step's {@code finished_at}. */
    void onFinished();

    /**
     * The step is about to be sent to the run's daemon: the launch id the daemon named and the token
     * subject it dialled as. What a restarted process needs to take the step back (qits-1192), so
     * the caller records it before the {@code RunStep} leaves. A throw means it could not be
     * recorded: the seam then does not send the step, and the step fails.
     */
    default void onLaunched(String daemonId, String tokenSubject) {}
  }

  /**
   * Thrown out of {@link #run} and {@link #reattach} when this process is stopping and the step has
   * not ended (qits-1192). The run's lease keeps running and the successor takes the step back, so
   * <b>nothing may settle the run on the way out</b>: no verdict, no step row, no {@code Close}, no
   * lease ended, no credential given back. Every caller up to the driver lets it pass.
   */
  final class ProcessStopping extends RuntimeException {

    public ProcessStopping(String runId, int stepIndex) {
      super(
          "this qits-ci is stopping; run "
              + runId
              + " step "
              + stepIndex
              + " is left running in its lease for the successor to take back",
          null,
          false,
          false);
    }
  }

  /**
   * The step a previous process of this service had in flight when it stopped: its launch as the
   * run row recorded it, and the step itself, so that a daemon still owed its {@code RunStep} can be
   * sent it. {@code startedAt} is null when the step was not started; {@code script} is null when the
   * run's pipeline could not be read back.
   */
  record InFlightStep(
      int stepIndex,
      String daemonId,
      String tokenSubject,
      Instant startedAt,
      String script,
      int timeoutSeconds) {}

  /**
   * A run a previous process of this service left {@code RUNNING} in a lease. {@code daemonId} and
   * {@code tokenSubject} are the run's launch as its row recorded it; {@code inFlight} is null when
   * no step was in flight — between two steps, or before the first.
   */
  record InheritedRun(
      String runId, UUID leaseId, String daemonId, String tokenSubject, InFlightStep inFlight) {}

  /**
   * Wait for the daemon of a run a previous process left {@code RUNNING} to dial back (qits-1192):
   * its lease keeps running through the restart, and the daemon re-dials within its reconnect
   * budget. When it does, {@code adopted} runs on a driver thread of its own and is the rest of the
   * run's life: it continues the run, taking the in-flight step back with {@link #reattach}. When it
   * does not within its reconnect grace, or the lease is gone, {@code lost} runs instead. Exactly one
   * of the two runs, once.
   *
   * <p>The default re-attaches nothing: a seam that cannot take a container back says so at once.
   */
  default void inherit(InheritedRun run, Runnable adopted, Runnable lost) {
    lost.run();
  }

  /**
   * Revoke the credentials a previous process commissioned for an inherited run — every {@code
   * ci-run} token of the run this process does not hold (qits-1192). Called on every path where
   * that old credential stops being needed: the taken-back step ended, the run continued without
   * one, or the run was settled instead of taken back. Never throws.
   */
  default void revokeInherited(String runId) {}

  /**
   * Take back the in-flight step of an inherited run ({@link #inherit}) and wait for its end, as
   * {@link #run} would for a step it started. It never sends the step a second time with a new
   * index: a step whose daemon does not come back ends {@code CONNECTION_LOST}.
   */
  default StepResult reattach(StepSpec spec, StepListener listener) {
    return new StepResult(
        -1,
        false,
        StepOutcome.CONNECTION_LOST,
        "[this step runner cannot take back a step a previous qits-ci started]");
  }

  /**
   * The daemon build a run pins itself to: the version recorded on the run row, and the download url
   * its lease container is handed. Resolved <b>once</b> per run, for the reason the whole pin exists.
   */
  record DaemonPin(String version, String binaryUrl) {}

  /** Resolve the daemon pin for a new run. */
  DaemonPin pinDaemon();

  /**
   * The pin a run already recorded, for an inherited run that continues. The default answers a fresh
   * pin.
   */
  default DaemonPin pinDaemon(String version) {
    return pinDaemon();
  }

  /** Run one step to completion, feeding {@code listener} as it goes. Blocks. */
  StepResult run(StepSpec spec, StepListener listener);

  /**
   * Ask this run's in-flight step to stop. Best-effort and asynchronous: the in-flight {@link #run}
   * returns of its own accord shortly after (a cancelled step still <em>finishes</em> — the daemon
   * answers with a terminal frame), so cancelledness is the caller's to record from its own flag and
   * is not inferrable from how the call came back. A no-op when nothing is in flight.
   */
  void cancel(String runId);

  /**
   * Whether a worker of <b>this</b> process is executing the run — the question {@link #cancel}
   * cannot answer, because a no-op and a cancellation come back the same way.
   *
   * <p>It exists for the row a dead process left behind. A {@code RUNNING} run whose worker died
   * with the previous container has no in-flight step here, so asking this one to stop it is asking
   * nothing of nobody; the caller settles the row itself instead of waiting for a worker that will
   * never report back.
   */
  boolean owns(String runId);

  /**
   * A run was queued — accepted, retried or put back by the boot sweep: ask for its lease. Never
   * throws and never blocks on the network for long; a lease that could not be asked for is asked
   * for again by the seam's own reconcile.
   */
  default void queued(String runId) {}

  /**
   * The run reached a terminal state, or was settled while it was still queued: release everything
   * still held for it — the daemon is told {@code Close}, the lease is ended, the token deleted.
   */
  void runClosed(String runId);
}
