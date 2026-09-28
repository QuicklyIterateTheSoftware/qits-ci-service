package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiRunnerStepRunner;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.CiDaemonStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.daemonhost.StepAddressPlane;
import eu.wohlben.qits.ci.daemonhost.StepWorkloadSpecs;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.cirunner.protocol.Cancel;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The step seam for a run a runner reserved: {@link CiDaemonStepRunner}'s sequence with one link
 * changed — the container is started by the runner, asked over its socket, instead of by
 * qits-containers.
 *
 * <p><b>Everything else is the local path's, deliberately to the line.</b> The step's identifiers
 * are validated the same way, the launch is registered in the same {@link CiDaemonRegistry} with the
 * same per-container secret, the spec is composed by the same {@link StepWorkloadSpecs} from the same
 * settings, and the container's own {@code qits-ci-daemon} dials the same {@code /ci/daemon} socket
 * and receives its script as the reply to its {@code Initialized}. So the four deadlines are the
 * same four — the runner's answer, register, initialize, the step's backstop — and the outcomes are
 * the same {@link StepOutcome}s: no answer to a {@code Launch} is {@code NEVER_STARTED}, a {@code
 * LaunchFailed} is {@code LAUNCH_FAILED} carrying docker's own words, and a socket that goes away is
 * {@code CONNECTION_LOST}. No new outcome and no new step status was needed.
 *
 * <p><b>Two sockets can be lost now, and both end the step at once.</b> The daemon's is what it
 * always was. The runner's is the new one: the runner forgets every run it held when its socket
 * drops, so a step whose runner is gone is over whatever its container is doing. A loss hook on the
 * session ({@link CiRunnerRegistry#onLoss}) reaps the step's launch record the moment the session
 * ends, which completes every daemon await as lost — so a vanished runner costs the run a few milliseconds, never a
 * deadline — and the step's output then names the runner ({@code [runner <name> disconnected]}).
 * The run is an ordinary failed run and retryable like one.
 *
 * <p><b>The teardown asks the runner too, and never waits on it for long.</b> Every step ends with
 * the daemon's launch record reaped and a {@link Reap} to the runner for the step's container; its
 * {@code Reaped} is awaited for at most {@link #REAP_TIMEOUT} and logged either way, because a
 * removal that did not land is the runner's boot sweep's to retry and never a reason to hold a run
 * open. The run's close sends {@code Released} ({@link CiRunnerRegistry#release}), the only frame
 * that frees the runner's slot.
 */
@ApplicationScoped
@Typed({RunnerStepRunner.class, CiRunnerStepRunner.class})
public class RunnerStepRunner implements CiRunnerStepRunner {

  private static final Logger LOG = Logger.getLogger(RunnerStepRunner.class);

  /** How long a step's teardown waits for the runner's {@code Reaped} before logging and moving on. */
  static final Duration REAP_TIMEOUT = Duration.ofSeconds(30);

  @Inject CiDaemonRegistry daemons;

  /** For the settings a spec is composed from and the daemon pin — never to start anything. */
  @Inject CiDaemonLauncher launcher;

  @Inject CiStepRelay relay;

  @Inject RunCommissions commissions;

  @Inject CiRunnerRegistry runners;

  @ConfigProperty(name = "qits.ci.runner.launch-timeout-seconds")
  long launchTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.daemon-register-timeout-seconds")
  long registerTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.daemon-init-timeout-seconds")
  long initTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.step-timeout-grace-seconds")
  long stepTimeoutGraceSeconds;

  @Inject RunnerAddresses addresses;

  @Inject CiRunners runnerRows;

  /**
   * The plane each runner run was started on, read off the runner's row at the run's first step and
   * kept until the run closes — so a {@code PATCH} of the plane reaches the runner's next run, never
   * the middle of one whose first step was told the other plane's addresses and credential.
   */
  private final ConcurrentHashMap<String, CiRunnerPlane> planes = new ConcurrentHashMap<>();

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
    // CiDaemonStepRunner's checks, for its reasons: before a secret is minted or a relay opened.
    CiIdentifiers.requireRepo(spec.repo());
    CiIdentifiers.requireBranch(spec.branch());
    CiIdentifiers.requireSha(spec.sha());
    CiIdentifiers.requireImage(spec.image());

    CiRunnerRegistry.Session session = runners.holding(spec.runId());
    relay.begin(spec.runId(), spec.stepIndex());
    CiDaemonRegistry.Credentials credentials =
        daemons.registerLaunch(
            spec.runId(),
            spec.stepIndex(),
            (stream, seq, text) -> {
              relay.append(spec.runId(), text);
              listener.onChunk(text);
            });
    String containerName = CiDaemonLauncher.containerName(spec.runId(), spec.stepIndex());
    inFlight.put(
        spec.runId(), new InFlight(credentials.daemonId(), containerName, spec.stepIndex()));

    // The runner's socket closing ends this step's daemon awaits now rather than at their
    // deadlines: reaping the launch record completes every one of them as lost. Handed to another
    // thread, because the loss is reported on whichever thread saw the close and the reap closes a
    // socket with a bounded wait.
    AutoCloseable lossWatch =
        session == null
            ? () -> {}
            : runners.onLoss(
                session,
                () ->
                    java.util.concurrent.CompletableFuture.runAsync(
                        () -> daemons.reap(credentials.daemonId())));
    try {
      return execute(spec, listener, session, credentials, containerName);
    } finally {
      closeQuietly(lossWatch);
      inFlight.remove(spec.runId());
      daemons.reap(credentials.daemonId());
      reapOnRunner(session, spec.runId(), spec.stepIndex(), containerName);
    }
  }

  private StepResult execute(
      StepSpec spec,
      StepListener listener,
      CiRunnerRegistry.Session session,
      CiDaemonRegistry.Credentials credentials,
      String containerName) {
    String daemonId = credentials.daemonId();
    if (session == null || !session.isOpen()) {
      return lost(session, "");
    }

    StepAddressPlane plane;
    try {
      plane = planeFor(spec.runId(), session);
    } catch (IllegalStateException unconfigured) {
      // An EDGE runner on a qits-ci that has since lost its public domain: there is no address to
      // tell the step, and an internal alias would name nothing the runner's host can resolve.
      return failed(StepOutcome.LAUNCH_FAILED, unconfigured.getMessage());
    }
    CiDaemonLauncher.LaunchSpec launchSpec =
        new CiDaemonLauncher.LaunchSpec(
            spec.runId(),
            spec.stepIndex(),
            spec.repo(),
            spec.branch(),
            spec.sha(),
            spec.image(),
            daemonId,
            credentials.secret(),
            spec.daemonBinaryUrl(),
            spec.timeoutSeconds(),
            spec.docker(),
            spec.build(),
            spec.user(),
            spec.env());
    IdpCommissioner.Commission commission;
    try {
      commission = commissions == null ? null : commissions.forRun(spec.runId(), spec.env());
    } catch (IdpCommissioner.CommissionFailedException notCommissioned) {
      // The local path's decision, for its reason: an idp blip fails the step, never a launch
      // without the credential.
      return failed(StepOutcome.LAUNCH_FAILED, notCommissioned.getMessage());
    }
    WorkloadSpec workload =
        workloadSpec(
            StepWorkloadSpecs.compose(launcher.workloadSettings(), plane, launchSpec, commission),
            spec.docker() || spec.build());

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
      if (!session.isOpen()) {
        return lost(session, "");
      }
      // The local path reads the bootstrap's own log off the removal; this host cannot see the
      // runner's docker, so what is recorded is where that log is.
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
    if (!session.isOpen()
        && initialization.status() != CiDaemonRegistry.Initialization.Status.INITIALIZED) {
      return lost(session, "");
    }
    switch (initialization.status()) {
      case INITIALIZED -> {
        /* the step is the reply to this */
      }
      case INIT_FAILED -> {
        return failed(
            CiDaemonStepRunner.outcomeOf(initialization.reason()),
            CiDaemonStepRunner.detailOf(initialization));
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

    if (completion.status() != CiDaemonRegistry.Completion.Status.FINISHED && !session.isOpen()) {
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
   * Stop a runner run. A step whose script is running is stopped by its daemon, exactly as on the
   * local path — a {@code Cancel} on the daemon socket answered with a terminal frame. Before that
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
    planes.remove(runId);
    if (commissions != null) {
      commissions.release(runId);
    }
    runners.release(runId);
  }

  // --- internals --------------------------------------------------------------------------------

  /**
   * This run's addresses: the launcher's own internal plane for an {@code INTERNAL} runner, and the
   * same addresses moved onto the public edge names for an {@code EDGE} one.
   *
   * @throws IllegalStateException for an EDGE runner when this qits-ci knows no public domain
   */
  StepAddressPlane planeFor(String runId, CiRunnerRegistry.Session session) {
    CiRunnerPlane kind = planes.computeIfAbsent(runId, id -> planeOf(session));
    StepAddressPlane internal = launcher.internalPlane();
    if (kind != CiRunnerPlane.EDGE) {
      return internal;
    }
    StepAddressPlane.EdgeOrigins origins =
        addresses
            .edgeOrigins()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "EDGE_PLANE_UNCONFIGURED: runner "
                            + session.runnerName()
                            + " is on the EDGE plane and this qits-ci knows no public domain"
                            + " (QITS_DOMAIN), so its step has no address to be told"));
    return StepAddressPlane.edge(origins, internal);
  }

  /** The runner row's plane as it is now; the row the session was admitted as if it is gone. */
  private CiRunnerPlane planeOf(CiRunnerRegistry.Session session) {
    CiRunnerPlane plane;
    try {
      plane = runnerRows.get(session.runnerId()).plane;
    } catch (RuntimeException gone) {
      plane = session.runner() == null ? null : session.runner().plane;
    }
    return plane == null ? CiRunnerPlane.INTERNAL : plane;
  }

  /**
   * The composed spec as the runner protocol carries it. The protocol's {@code WorkloadSpec} is the
   * subset of qits-containers' spec this service fills, so this is a field-for-field copy plus the
   * one fact the protocol adds: {@code buildPlane}, the step's {@code docker: || build:}.
   *
   * <p><b>No {@code qits.ci.runner} label is added here, and none may be.</b> That namespace is the
   * runner's own: it stamps {@code qits.ci.runner=<runner id>} and {@code qits.ci.runner.run} on
   * every container it starts (its boot sweep selects by them), and it refuses a spec whose labels
   * reach into it — so a label written here would fail every launch.
   */
  static WorkloadSpec workloadSpec(Spec spec, boolean buildPlane) {
    Security security = spec.security() == null ? Security.none() : spec.security();
    return new WorkloadSpec(
        spec.image(),
        spec.entrypoint(),
        spec.args(),
        spec.env(),
        spec.extraLabels(),
        spec.network(),
        spec.addHosts(),
        spec.user(),
        spec.hostDockerSocket(),
        security.capDropAll(),
        security.noNewPrivileges(),
        security.memory(),
        security.memorySwap(),
        security.pidsLimit(),
        security.cpus(),
        security.oomScoreAdj(),
        spec.explicitName(),
        buildPlane);
  }

  private void reapOnRunner(
      CiRunnerRegistry.Session session, String runId, int stepIndex, String containerName) {
    if (session == null || !session.isOpen()) {
      // The runner lost its socket and with it every run it held; its boot sweep removes what is
      // left on the next connect.
      return;
    }
    if (runners.reap(session, new Reap(runId, stepIndex, containerName), REAP_TIMEOUT)) {
      LOG.debugf("Runner %s reaped %s", session.runnerName(), containerName);
    } else {
      LOG.warnf(
          "Runner %s did not confirm removing %s within %s; its boot sweep removes it",
          session.runnerName(), containerName, REAP_TIMEOUT);
    }
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
