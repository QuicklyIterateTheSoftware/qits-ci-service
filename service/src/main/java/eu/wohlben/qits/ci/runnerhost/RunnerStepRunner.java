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
import eu.wohlben.qits.cirunner.protocol.Reaped;
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
 * <p><b>Two sockets can be lost now.</b> The daemon's ends the step at once, as it always did. The
 * runner's does not any more (qits-545): a runner that drops its socket keeps the step's container
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

  /** For the settings a spec is composed from and the daemon pin — never to start anything. */
  @Inject CiDaemonLauncher launcher;

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

    CiRunnerRegistry.Session session = runners.awaitHolding(spec.runId());
    if (session == null) {
      session = runners.holding(spec.runId()); // gone for good — kept only to name it
    }
    // The step's plane and the run's credential on it, BEFORE a secret is minted: which kind of
    // credential the run holds decides what the launch record is bound to (a ci-run token's subject,
    // or nothing), and a refusal here has nothing to tear down. A session already gone skips both,
    // and execute reports it lost exactly as before.
    StepAddressPlane plane = null;
    RunCommissions.Credential credential = null;
    if (session != null && session.isOpen()) {
      try {
        plane = planeFor(spec.runId(), session);
      } catch (IllegalStateException unconfigured) {
        // An EDGE runner on a qits-ci that has since lost its public domain: there is no address to
        // tell the step, and an internal alias would name nothing the runner's host can resolve.
        return failed(StepOutcome.LAUNCH_FAILED, unconfigured.getMessage());
      }
      try {
        credential =
            commissions == null ? null : commissions.forRun(spec.runId(), spec.env(), plane.plane());
      } catch (IdpCommissioner.CommissionFailedException notCommissioned) {
        // The local path's decision, for its reason: an idp blip fails the step, never a launch
        // without the credential.
        return failed(StepOutcome.LAUNCH_FAILED, notCommissioned.getMessage());
      }
    }
    relay.begin(spec.runId(), spec.stepIndex());
    // An EDGE step's launch is bound to its run's token subject: its daemon dials through the edge
    // with that token, and CiDaemonSocket admits it only as that subject.
    CiDaemonRegistry.Credentials credentials =
        daemons.registerLaunch(
            spec.runId(),
            spec.stepIndex(),
            credential != null && credential.isToken() ? credential.token().subject() : null,
            (stream, seq, text) -> {
              relay.append(spec.runId(), text);
              listener.onChunk(text);
            });
    String containerName = CiDaemonLauncher.containerName(spec.runId(), spec.stepIndex());
    inFlight.put(
        spec.runId(), new InFlight(credentials.daemonId(), containerName, spec.stepIndex()));

    // The run being lost ends this step's daemon awaits then rather than at their deadlines: reaping
    // the launch record completes every one of them as lost. Handed to another thread, because the
    // loss is reported on whichever thread decided it and the reap closes a socket with a bounded
    // wait.
    AutoCloseable lossWatch =
        runners.onRunLost(
            spec.runId(),
            () ->
                java.util.concurrent.CompletableFuture.runAsync(
                    () -> daemons.reap(credentials.daemonId())));
    StepResult result;
    Reaped reaped;
    try {
      result = execute(spec, listener, session, credentials, containerName, plane, credential);
    } finally {
      closeQuietly(lossWatch);
      inFlight.remove(spec.runId());
      daemons.reap(credentials.daemonId());
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
      CiDaemonRegistry.Credentials credentials,
      String containerName,
      StepAddressPlane plane,
      RunCommissions.Credential credential) {
    String daemonId = credentials.daemonId();
    if (session == null || !session.isOpen() || plane == null) {
      return lost(session, "");
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
    WorkloadSpec workload =
        workloadSpec(
            StepWorkloadSpecs.compose(launcher.workloadSettings(), plane, launchSpec, credential),
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
      if (runners.lost(spec.runId())) {
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
    if (runners.lost(spec.runId())
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
