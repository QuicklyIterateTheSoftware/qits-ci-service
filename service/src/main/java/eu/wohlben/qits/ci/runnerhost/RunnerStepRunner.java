package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiRunnerStepRunner;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import eu.wohlben.qits.cirunner.protocol.Cancel;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The step seam</b> — every run's, since the in-process executor ({@code CiDaemonStepRunner},
 * which asked qits-containers for each container) was deleted in qits-506. The runner that reserved
 * the run is asked over its socket to start each step's container ({@code Launch} → its host's
 * {@code docker run}) and to remove it again ({@code Reap} → {@code docker rm}).
 *
 * <p><b>The sequence per step.</b> The step's identifiers are validated, the run's {@code ci-run}
 * token is commissioned (at its first step), the launch is registered in the {@link
 * CiDaemonRegistry} bound to that token's subject, the spec is composed by {@link
 * StepWorkloadSpecs} from {@link StepContainerSettings}, and the container's own {@code
 * qits-ci-daemon} dials the {@code /ci/daemon} socket and receives its script as the reply to its
 * {@code Initialized}. So there are four deadlines — the runner's answer, register, initialize, the
 * step's backstop — and the outcomes are {@link StepOutcome}s: no answer to a {@code Launch} is
 * {@code NEVER_STARTED}, a {@code LaunchFailed} is {@code LAUNCH_FAILED} carrying docker's own
 * words, and a socket that goes away is {@code CONNECTION_LOST}.
 *
 * <p><b>Two sockets can be lost now, and neither ends the step at once.</b> The daemon's waits
 * {@code qits.ci.daemon.reconnect-grace-seconds} for the daemon to dial again (qits-748): both
 * sockets cross the platform edge, so an edge redeploy drops them together while the container keeps
 * running, and the daemon re-dials, says {@code Hello} again and is sent its step again — the
 * awaits here simply keep waiting through the gap, inside their own deadlines, and only a daemon
 * that does not come back in time ends the step {@code CONNECTION_LOST} ({@link
 * CiDaemonRegistry#onClose}). The runner's (qits-545): a runner that drops its socket keeps the step's container
 * and comes back for the run, so the step is only over once the registry says the <em>run</em> is
 * lost — its runner did not come back inside the grace, or came back without it ({@link
 * CiRunnerRegistry#onRunLost}). Then a loss hook reaps the step's launch record, which completes
 * every daemon await as lost — so a vanished runner costs the run the grace, never a deadline — and
 * the step's output names the runner ({@code [runner <name> disconnected]}). The run is an ordinary
 * failed run and retryable like one. A step that starts, or ends, while its runner is away waits the
 * same grace for it ({@link CiRunnerRegistry#awaitHolding}) rather than failing on the spot.
 *
 * <p><b>The teardown asks the runner too, and never waits on it for long.</b> Every step ends with
 * the daemon's launch record reaped and a {@link Reap} to the runner for the step's container; its
 * {@code Reaped} is awaited for at most {@link #REAP_TIMEOUT} and logged either way, because a
 * removal that did not land is the runner's boot sweep's to retry and never a reason to hold a run
 * open. The run's close sends {@code Released} ({@link CiRunnerRegistry#release}), the only frame
 * that frees the runner's slot.
 *
 * <p><b>{@code Reaped} carries the container's own {@code docker logs} now, and a step that did not
 * finish green gets it appended to its recorded output.</b> The runner takes its own tail — the last
 * lines, redacted, with a first line naming why the container exited — because this host can see
 * only what the daemon relayed over the control socket, and every outcome that matters here is one
 * where that relay said little or nothing: the container never dialled back at all, docker refused
 * to start it, or its socket dropped mid-step. A green step is left untouched, since its own
 * captured output already says everything worth saying. The append happens after {@link #execute}
 * has already returned — the {@code Reaped} this waits on is the teardown's, not the verdict's — so
 * the bound above still holds: a tail arriving inside it is folded in, one arriving after it is
 * exactly as if none arrived, and nothing here waits any longer for it.
 */
@ApplicationScoped
@Typed({RunnerStepRunner.class, CiRunnerStepRunner.class})
public class RunnerStepRunner implements CiRunnerStepRunner {

  private static final Logger LOG = Logger.getLogger(RunnerStepRunner.class);

  /** How long a step's teardown waits for the runner's {@code Reaped} before logging and moving on. */
  static final Duration REAP_TIMEOUT = Duration.ofSeconds(30);

  /** What a step is recorded with on a qits-ci that commissions nothing — see {@link #run}. */
  static final String NOT_COMMISSIONING =
      "this qits-ci commissions no credentials (quarkus.oidc-client.qits.client-enabled is off), so"
          + " a step has no ci-run token to reach the platform with";

  private volatile Duration reapTimeout = REAP_TIMEOUT;

  /**
   * Package-private for one reason: a suite proving a late {@code Reaped} is not appended cannot
   * wait out the real thirty seconds. A method rather than a field write because this bean is
   * normal-scoped, and a field set through the client proxy lands on the proxy — {@link
   * CiRunnerRegistry#seenInterval}'s reason exactly.
   */
  void reapTimeout(Duration timeout) {
    this.reapTimeout = timeout;
  }

  @Inject CiDaemonRegistry daemons;

  /** The settings a spec is composed from, and the daemon pin. */
  @Inject StepContainerSettings launcher;

  @Inject CiStepRelay relay;

  @Inject RunCommissions commissions;

  @Inject CiRunnerRegistry runners;

  @ConfigProperty(name = "qits.ci.runner.launch-timeout-seconds")
  long launchTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.daemon-register-timeout-seconds")
  long registerTimeoutSeconds;

  /**
   * Package-private for {@link #reapTimeout}'s reason: a suite proving the never-dialled-back case
   * cannot wait out the real 180s register deadline. A getter beside it so the same suite can put the
   * shipped value back when it is done, rather than hard-coding a second copy of it.
   */
  long registerTimeoutSeconds() {
    return registerTimeoutSeconds;
  }

  void registerTimeoutSeconds(long seconds) {
    this.registerTimeoutSeconds = seconds;
  }

  @ConfigProperty(name = "qits.ci.daemon-init-timeout-seconds")
  long initTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.step-timeout-grace-seconds")
  long stepTimeoutGraceSeconds;

  @Inject RunnerAddresses addresses;

  @Inject CiRunners runnerRows;

  /** The step each runner run has in flight, for a cancellation arriving on another thread. */
  private final ConcurrentHashMap<String, InFlight> inFlight = new ConcurrentHashMap<>();

  private record InFlight(String daemonId, String containerName, int stepIndex) {}

  @Override
  public DaemonPin pinDaemon() {
    String version = launcher.daemonVersion();
    return new DaemonPin(version, launcher.resolveBinaryUrl(version));
  }

  @Override
  public StepResult run(StepSpec spec, StepListener listener) {
    // Before a launch is recorded or a relay opened: a value that would reach the container's
    // environment is validated here, where a refusal has nothing to tear down.
    CiIdentifiers.requireRepo(spec.repo());
    CiIdentifiers.requireBranch(spec.branch());
    CiIdentifiers.requireSha(spec.sha());
    CiIdentifiers.requireImage(spec.image());

    CiRunnerRegistry.Session session = runners.awaitHolding(spec.runId());
    if (session == null) {
      session = runners.holding(spec.runId()); // gone for good — kept only to name it
    }
    // The step's addresses and the run's token, BEFORE a launch is recorded: the launch is bound
    // to the token's subject, and a refusal here has nothing to tear down. A session already gone
    // skips both: the step is reported lost, and whichever connection holds the run by then is
    // still asked to remove what it may carry of it.
    if (session == null || !session.isOpen()) {
      relay.begin(spec.runId(), spec.stepIndex());
      String name = StepContainerSettings.containerName(spec.runId(), spec.stepIndex());
      Reaped reaped =
          reapOnRunner(runners.awaitHolding(spec.runId()), spec.runId(), spec.stepIndex(), name);
      return withContainerLog(lost(session, ""), reaped, session, name);
    }
    StepAddressPlane plane;
    try {
      plane = planeFor(session);
    } catch (IllegalStateException unconfigured) {
      // This qits-ci knows no public domain: there is no address to tell the step.
      return failed(StepOutcome.LAUNCH_FAILED, unconfigured.getMessage());
    }
    IdpCommissioner.CommissionedToken token;
    try {
      token = commissions.forRun(spec.runId(), spec.env());
    } catch (IdpCommissioner.CommissionFailedException notCommissioned) {
      // An idp blip fails the step, never a launch without the credential.
      return failed(StepOutcome.LAUNCH_FAILED, notCommissioned.getMessage());
    }
    if (token == null) {
      // The token is the step's only credential: without one it can download no daemon, clone
      // nothing and is admitted by no socket, so it would sit until the register deadline and be
      // recorded NEVER_STARTED with nothing naming the cause.
      return failed(StepOutcome.LAUNCH_FAILED, NOT_COMMISSIONING);
    }
    relay.begin(spec.runId(), spec.stepIndex());
    // The launch is bound to its run's token subject: the step's daemon dials through the edge
    // with that token, and CiDaemonSocket admits it only as that subject.
    String daemonId =
        daemons.registerLaunch(
            spec.runId(),
            spec.stepIndex(),
            token.subject(),
            (stream, seq, text) -> {
              relay.append(spec.runId(), text);
              listener.onChunk(text);
            });
    String containerName = StepContainerSettings.containerName(spec.runId(), spec.stepIndex());
    inFlight.put(spec.runId(), new InFlight(daemonId, containerName, spec.stepIndex()));

    // The run being lost ends this step's daemon awaits then rather than at their deadlines: reaping
    // the launch record completes every one of them as lost. Handed to another thread, because the
    // loss is reported on whichever thread decided it and the reap closes a socket with a bounded
    // wait.
    AutoCloseable lossWatch =
        runners.onRunLost(
            spec.runId(),
            () ->
                java.util.concurrent.CompletableFuture.runAsync(
                    () -> daemons.reap(daemonId)));
    StepResult result;
    Reaped reaped;
    try {
      result = execute(spec, listener, session, daemonId, containerName, plane, token);
    } finally {
      closeQuietly(lossWatch);
      inFlight.remove(spec.runId());
      daemons.reap(daemonId);
      // Whichever connection holds the run now: one that came back for it after a blip, or none.
      reaped =
          reapOnRunner(
              runners.awaitHolding(spec.runId()), spec.runId(), spec.stepIndex(), containerName);
    }
    return withContainerLog(result, reaped, session, containerName);
  }

  private StepResult execute(
      StepSpec spec,
      StepListener listener,
      CiRunnerRegistry.Session session,
      String daemonId,
      String containerName,
      StepAddressPlane plane,
      IdpCommissioner.CommissionedToken token) {
    StepContainerSettings.LaunchSpec launchSpec =
        new StepContainerSettings.LaunchSpec(
            spec.runId(),
            spec.stepIndex(),
            spec.repo(),
            spec.branch(),
            spec.sha(),
            spec.image(),
            daemonId,
            spec.daemonBinaryUrl(),
            spec.timeoutSeconds(),
            spec.docker(),
            spec.build(),
            spec.user(),
            spec.env());
    WorkloadSpec workload =
        StepWorkloadSpecs.compose(
            launcher.workloadSettings(), plane, launchSpec, token, stepMemoryLimitOf(session));

    Duration launchTimeout = Duration.ofSeconds(launchTimeoutSeconds);
    CiRunnerRegistry.LaunchAnswer answer =
        runners.launch(session, new Launch(spec.runId(), spec.stepIndex(), workload), launchTimeout);
    switch (answer.status()) {
      case LAUNCHED ->
          LOG.debugf(
              "Runner %s started %s as %s", session.runnerName(), containerName, answer.detail());
      case LAUNCH_FAILED -> {
        return failed(
            StepOutcome.LAUNCH_FAILED,
            "runner " + session.runnerName() + " could not start the container: " + answer.detail());
      }
      case NO_ANSWER -> {
        return failed(
            StepOutcome.NEVER_STARTED,
            "runner " + session.runnerName() + " did not answer the launch within " + launchTimeout);
      }
      case CONNECTION_LOST -> {
        return lost(session, "");
      }
      case WITHDRAWN -> {
        return failed(
            StepOutcome.LAUNCH_FAILED,
            "the run was cancelled before runner " + session.runnerName() + " answered the launch");
      }
    }

    if (!daemons.awaitRegistered(daemonId, Duration.ofSeconds(registerTimeoutSeconds))) {
      if (runners.lost(spec.runId())) {
        return lost(session, "");
      }
      // This host cannot see the runner's docker, so what is recorded is where that log is — and
      // the Reaped's own log tail replaces the pointer below when it arrives.
      return failed(
          StepOutcome.NEVER_STARTED,
          "the step container on runner "
              + session.runnerName()
              + " never dialled back; its own log is on the runner's host ("
              + containerName
              + ")");
    }

    CiDaemonRegistry.Initialization initialization =
        daemons.awaitInitialized(daemonId, Duration.ofSeconds(initTimeoutSeconds));
    if (runners.lost(spec.runId())
        && initialization.status() != CiDaemonRegistry.Initialization.Status.INITIALIZED) {
      return lost(session, "");
    }
    switch (initialization.status()) {
      case INITIALIZED -> {
        /* the step is the reply to this */
      }
      case INIT_FAILED -> {
        return failed(outcomeOf(initialization.reason()), detailOf(initialization));
      }
      case NEVER_INITIALIZED -> {
        return failed(
            StepOutcome.NEVER_INITIALIZED,
            "the step container on runner " + session.runnerName() + " never finished its checkout");
      }
      case CONNECTION_LOST -> {
        return failed(StepOutcome.CONNECTION_LOST, "");
      }
    }

    listener.onStarted();
    relay.started(spec.runId(), Instant.now());
    daemons.sendRunStep(daemonId, spec.script(), spec.timeoutSeconds());

    Duration backstop = Duration.ofSeconds(spec.timeoutSeconds() + stepTimeoutGraceSeconds);
    CiDaemonRegistry.Completion completion = daemons.awaitFinished(daemonId, backstop);
    listener.onFinished();

    if (completion.status() != CiDaemonRegistry.Completion.Status.FINISHED
        && runners.lost(spec.runId())) {
      return lost(session, tail(spec.runId()));
    }
    return switch (completion.status()) {
      case FINISHED ->
          new StepResult(
              completion.exitCode(), completion.timedOut(), StepOutcome.OK, tail(spec.runId()));
      case NO_ANSWER -> {
        LOG.warnf(
            "ci-daemon %s on runner %s did not answer within %s of its step's deadline —"
                + " cancelling and reaping",
            daemonId, session.runnerName(), backstop);
        daemons.cancel(daemonId);
        yield new StepResult(-1, true, StepOutcome.OK, tail(spec.runId()));
      }
      case CONNECTION_LOST ->
          new StepResult(-1, false, StepOutcome.CONNECTION_LOST, tail(spec.runId()));
    };
  }

  /**
   * Stop a runner run. A step whose script is running is stopped by its daemon — a {@code Cancel}
   * on the daemon socket answered with a terminal frame. Before that
   * there is nothing in the container to stop, so the outstanding launch is withdrawn, the launch
   * record reaped (which completes the step's awaits at once) and the runner told to remove every
   * container of the run.
   */
  @Override
  public void cancel(String runId) {
    InFlight current = inFlight.get(runId);
    if (current != null && daemons.phaseOf(current.daemonId()) == CiDaemonRegistry.Phase.RUNNING) {
      daemons.cancel(current.daemonId());
      return;
    }
    CiRunnerRegistry.Session session = runners.holding(runId);
    runners.withdraw(runId);
    if (current != null) {
      daemons.reap(current.daemonId());
    }
    if (session != null && session.isOpen()) {
      runners.send(session, new Cancel(runId));
    }
  }

  /** A driver of this process holds the run from its {@code Take} until its {@code Released}. */
  @Override
  public boolean owns(String runId) {
    return runners.holds(runId);
  }

  /**
   * Everything the run held, given back: the relay's buffer, the in-flight record, the commissioned
   * credential — and the runner's slot, which is the one thing only this can free.
   */
  @Override
  public void runClosed(String runId) {
    relay.drop(runId);
    inFlight.remove(runId);
    commissions.release(runId);
    runners.release(runId);
  }

  // --- internals --------------------------------------------------------------------------------

  /**
   * A step's addresses: the public name of every service it reaches, from this qits-ci's domain.
   *
   * @throws IllegalStateException when this qits-ci knows no public domain
   */
  StepAddressPlane planeFor(CiRunnerRegistry.Session session) {
    StepAddressPlane.EdgeOrigins origins =
        addresses
            .edgeOrigins()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "EDGE_PLANE_UNCONFIGURED: this qits-ci knows no public domain"
                            + " (QITS_DOMAIN), so a step on runner "
                            + session.runnerName()
                            + " has no address to be told"));
    return launcher.plane(origins);
  }

  /**
   * The runner row's step memory limit as it is NOW — read per step: an operator raising a runner's cap for a build that keeps getting OOM-killed
   * wants the very next step to have it, without a reconnect and without waiting for a new run.
   * Null is the platform default ({@code qits.ci.memory-limit}, already in the composed spec). A row
   * that cannot be read falls back to the one the session was admitted as, and a row gone with it to
   * the default — a launch is never refused over the cap.
   */
  private String stepMemoryLimitOf(CiRunnerRegistry.Session session) {
    try {
      return runnerRows.get(session.runnerId()).stepMemoryLimit;
    } catch (RuntimeException gone) {
      return session.runner() == null ? null : session.runner().stepMemoryLimit;
    }
  }

  /** A failed checkout whose commit is gone is its own outcome; every other refusal is INIT_FAILED. */
  static StepOutcome outcomeOf(InitFailed.Reason reason) {
    return reason == InitFailed.Reason.SHA_GONE ? StepOutcome.SHA_GONE : StepOutcome.INIT_FAILED;
  }

  /** The daemon's reason and detail for a failed initialization, as the step's recorded output. */
  static String detailOf(CiDaemonRegistry.Initialization initialization) {
    String reason = initialization.reason() == null ? "unspecified" : initialization.reason().name();
    String detail = initialization.detail();
    return detail == null || detail.isBlank() ? reason : reason + ": " + detail;
  }

  /** The runner's {@code Reaped}, when one arrived — {@code null} for every way it did not. */
  private Reaped reapOnRunner(
      CiRunnerRegistry.Session session, String runId, int stepIndex, String containerName) {
    if (session == null || !session.isOpen()) {
      // The run is lost with its runner; the runner cancels what it carried of it when it is back,
      // and its boot sweep removes the rest.
      return null;
    }
    Reaped reaped = runners.reap(session, new Reap(runId, stepIndex, containerName), reapTimeout);
    if (reaped != null) {
      LOG.debugf("Runner %s reaped %s", session.runnerName(), containerName);
    } else {
      LOG.warnf(
          "Runner %s did not confirm removing %s within %s; its boot sweep removes it",
          session.runnerName(), containerName, reapTimeout);
    }
    return reaped;
  }

  /**
   * Fold the runner's own container log onto a step that did not finish green. A green step ({@link
   * #isGreen}) is returned untouched — its captured output already says everything there is to say,
   * and the runner's tail would only repeat it — and so is one whose {@code Reaped} carried no tail
   * at all, whether because none arrived inside {@link #reapTimeout} or because the runner sent one
   * that was blank. Where it lands is the "never dialled back" message's own former pointer: that
   * text named the runner's host as the only place to look, and once the log is actually here that
   * naming is stale, so it is rewritten to point at the section below instead of duplicated.
   */
  private static StepResult withContainerLog(
      StepResult result, Reaped reaped, CiRunnerRegistry.Session session, String containerName) {
    if (isGreen(result) || reaped == null || isBlank(reaped.logTail())) {
      return result;
    }
    String output = pointToAppendedLog(result.output(), containerName);
    String runnerName = session == null ? "?" : session.runnerName();
    String withLog =
        (output == null || output.isEmpty() ? "" : output + "\n")
            + "--- the step container's own log (from runner "
            + runnerName
            + ") ---\n"
            + reaped.logTail();
    return new StepResult(result.exitCode(), result.timedOut(), result.outcome(), withLog);
  }

  private static boolean isGreen(StepResult result) {
    return result.outcome() == StepOutcome.OK && result.exitCode() == 0 && !result.timedOut();
  }

  private static boolean isBlank(String text) {
    return text == null || text.isBlank();
  }

  /** The one place {@code execute} names the runner's host as where the log is; superseded below. */
  private static String pointToAppendedLog(String output, String containerName) {
    if (output == null) {
      return null;
    }
    String stale = "its own log is on the runner's host (" + containerName + ")";
    return output.contains(stale) ? output.replace(stale, "its own log is appended below") : output;
  }

  private static void closeQuietly(AutoCloseable handle) {
    try {
      handle.close();
    } catch (Exception ignored) {
      // Removing a map entry; nothing to report.
    }
  }

  /** A step that ended because the runner went away, saying so in the step's own output. */
  private static StepResult lost(CiRunnerRegistry.Session session, String tail) {
    String said =
        session == null
            ? "[no runner holds this run any more]"
            : "[runner " + session.runnerName() + " disconnected]";
    String output = tail == null || tail.isEmpty() ? said : tail + "\n" + said;
    return new StepResult(-1, false, StepOutcome.CONNECTION_LOST, output);
  }

  private static StepResult failed(StepOutcome outcome, String detail) {
    return new StepResult(-1, false, outcome, detail == null ? "" : detail);
  }

  private String tail(String runId) {
    return relay.snapshot(runId).map(CiStepRelay.Snapshot::output).orElse("");
  }
}
