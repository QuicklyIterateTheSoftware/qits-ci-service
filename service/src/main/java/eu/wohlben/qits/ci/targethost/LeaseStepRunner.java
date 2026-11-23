package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.cidaemon.protocol.Continue;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import eu.wohlben.qits.cidaemon.protocol.InitProgress;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The step seam</b> — every run's (epic qits-1481, "CI in a slot"). A run is one CI lease: {@link
 * CiLeases} asks qits-system for it when the run is queued and starts the run's driver once it is
 * granted, and this class drives each step of the run over the lease's one {@code qits-ci-daemon}
 * connection.
 *
 * <p><b>The sequence.</b> The first step waits, on two clocks, for the lease's container to start
 * and its daemon to dial in, and then for the daemon to report its worktree ready ({@link
 * #initDeadline} — flat for a daemon below capability 4, waited out by its progress at 4 and above,
 * qits-2539); every step, the first included, is then one
 * {@code RunStep{stepIndex, script, timeoutSeconds, env}} on that connection, answered by {@code
 * StepChunk}s and a {@code StepFinished} naming the step, with the host's backstop behind the
 * daemon's own deadline: {@code qits.ci.step-timeout-grace-seconds}, widened since qits-1807 by
 * {@code qits.ci.builder-wait-max-seconds} — see {@link #finish} — because the daemon may pause its
 * own deadline for up to that long while the step's {@code buildctl build} waits at the target's
 * shared builder gate rather than running at all, and the host must not mistake that pause for a
 * daemon that stopped answering. {@link #runClosed} tells the
 * daemon {@code Close} and, once it has hung up (bounded by {@code qits.ci.daemon.close-grace-seconds},
 * qits-2572), ends the lease and deletes the run's token. No container is started or removed here:
 * the lease's container is the target's.
 *
 * <p><b>The two clocks (qits-1544).</b> A run is claimed when its lease is {@code GRANTED}, but a
 * granted lease's container does not exist yet: the target pulls the pinned ci-slot image first
 * (multi-GB, cold on a new target and after every new digest), and on its first CI lease the buildkit
 * builder too, and only then reports the lease {@code RUNNING}. So the first clock is {@code
 * qits.ci.lease-start-timeout-seconds}, counted from the claim, and it covers only that: the run
 * waits while its lease is {@code GRANTED} — no outcome, no retry, the lease kept — until {@link
 * CiLeases} reports it {@code RUNNING} ({@link #leaseRunning}) or its daemon dials in, whichever is
 * first. A lease the target never starts within it is {@code LAUNCH_FAILED}. The second clock,
 * {@code qits.ci.daemon-register-timeout-seconds}, counts from the moment the lease was seen running
 * and covers what happens inside the container: the bootstrap's daemon download and the dial. A
 * running lease whose daemon never dials within it is {@code NEVER_STARTED}, as before.
 *
 * <p><b>A lease that ends early is the step's answer.</b> When {@link CiLeases} learns the lease
 * ended {@code FAILED} or {@code EXITED} ({@link #leaseEnded}), the launch is reaped, which completes
 * every await at once: before the daemon initialized, the step is {@code LAUNCH_FAILED} with the
 * lease's detail; after, {@code CONNECTION_LOST} saying the lease ended.
 *
 * <p><b>That is also what ends a held step</b> (qits-2163). A step whose daemon's socket drops is
 * no longer lost when the reconnect grace runs out: {@link CiDaemonRegistry} holds it for as long as
 * it can still be running, so an edge outage longer than a minute costs nothing while the lease's
 * container goes on. The hold has three ends, and this class owns two of them: the lease seen ended
 * (above — an older daemon that gives up on its own budget exits, and its lease with it), and the
 * step's backstop ({@link #backstopOf}), which the registry answers as {@code CONNECTION_LOST}
 * rather than {@code NO_ANSWER} when no connection is bound by then. The third is {@link #cancel}.
 *
 * <p><b>A restart of this process ends no step</b> (qits-1192), exactly as on a runner: once the
 * process is stopping, a step that has not ended is left alone — no {@code Cancel}, no {@code
 * Close}, no lease ended — and comes back as {@link ProcessStopping}. The run's launch was recorded
 * on its row when the lease was asked for, so the successor's boot sweep hands the run to {@link
 * #inherit}, which registers the launch again under its old id and waits for the daemon to dial back.
 */
@ApplicationScoped
@Typed({LeaseStepRunner.class, CiStepRunner.class})
public class LeaseStepRunner implements CiStepRunner {

  private static final Logger LOG = Logger.getLogger(LeaseStepRunner.class);

  /** What a step is recorded with on a qits-ci whose run holds no lease launch. */
  static final String NO_LEASE = "the run holds no lease this qits-ci knows the launch of";

  @Inject CiDaemonRegistry daemons;

  @Inject CiStepRelay relay;

  @Inject RunCommissions commissions;

  @Inject CiSlotSettings settings;

  @Inject CiLeases leases;

  @Inject CiSlotHolds holds;

  @ConfigProperty(name = "qits.ci.daemon-register-timeout-seconds")
  long registerTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.lease-start-timeout-seconds")
  long leaseStartTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.daemon-init-timeout-seconds")
  long initTimeoutSeconds;

  /**
   * How long a capability-4 daemon's setup may report no changed progress before it is given up on
   * (qits-2539). Initialised to the shipped value, so a runner a suite builds by hand waits as the
   * shipped one does.
   */
  @ConfigProperty(name = "qits.ci.daemon-init-stall-seconds")
  long initStallSeconds = 300;

  /** The total cap on a capability-4 daemon's setup however it moves (qits-2539); shipped value. */
  @ConfigProperty(name = "qits.ci.daemon-init-max-seconds")
  long initMaxSeconds = 1800;

  @ConfigProperty(name = "qits.ci.step-timeout-grace-seconds")
  long stepTimeoutGraceSeconds;

  @ConfigProperty(name = "qits.ci.builder-wait-max-seconds")
  long builderWaitMaxSeconds;

  /** Package-private for a suite that cannot wait out the real 180s register deadline. */
  void registerTimeoutSeconds(long seconds) {
    this.registerTimeoutSeconds = seconds;
  }

  long registerTimeoutSeconds() {
    return registerTimeoutSeconds;
  }

  /**
   * The worktree budget every init await and the {@code Continue}'s await use (qits-2539): the
   * flat {@code qits.ci.daemon-init-timeout-seconds} for a daemon below capability 4, and the stall
   * window and total cap for one at or above it. Composed here, where the config is; the registry
   * only applies it.
   */
  CiDaemonRegistry.InitDeadline initDeadline() {
    return new CiDaemonRegistry.InitDeadline(
        Duration.ofSeconds(initTimeoutSeconds),
        Duration.ofSeconds(initStallSeconds),
        Duration.ofSeconds(initMaxSeconds));
  }

  /** How often the lease-start wait looks again at its launch and at the process stopping. */
  static final Duration START_POLL = Duration.ofMillis(500);

  /**
   * When each run's lease was seen running ({@link #leaseRunning}) — the instant the register clock
   * counts from. Completed with null when the wait has no lease left to start ({@link #leaseEnded},
   * {@link #cancel}). Created by whichever comes first, the wait or the report.
   */
  private final ConcurrentHashMap<String, CompletableFuture<Instant>> leaseStarts =
      new ConcurrentHashMap<>();

  /** The step each run has in flight, for a cancellation arriving on another thread. */
  private final ConcurrentHashMap<String, InFlight> inFlight = new ConcurrentHashMap<>();

  /** Why each run's lease ended before its run did — {@link #leaseEnded}. */
  private final ConcurrentHashMap<String, String> endedLeases = new ConcurrentHashMap<>();

  /**
   * Runs whose lease ran and whose daemon never dialled back to register — whether that answered
   * {@code NEVER_STARTED} (the register wait simply timed out) or {@code LAUNCH_FAILED} (the lease
   * ended, exited or failed, before the register wait returned) — so the run's close ends the lease
   * with {@link SystemLeases#NEVER_DIALLED_BACK} (qits-1809, qits-2382).
   */
  private final Set<String> neverDialledBack = ConcurrentHashMap.newKeySet();

  /** Runs whose daemon has reported its worktree ready in this process. */
  private final Set<String> initialized = ConcurrentHashMap.newKeySet();

  /**
   * A continued run's phase-1 run (qits-732), whose token outlives the {@code Continue} until this
   * run's lease is torn down: the target's shared buildkit caches registry authorizers per host and
   * scope across sessions, so a token revoked while its container still lives can fail another
   * session's token fetch. Revoked in {@link #runClosed}, beside the run's own.
   */
  private final ConcurrentHashMap<String, String> phaseOneOf = new ConcurrentHashMap<>();

  /** Runs inherited but not yet taken back — a check finishing late registers nothing that stays. */
  private final Set<String> givenUp = ConcurrentHashMap.newKeySet();

  /**
   * Raised once this process is stopping — {@code ShutdownEvent}, which Quarkus fires before it
   * destroys any bean. From then on a step that has not ended is left running for the successor.
   */
  private volatile boolean stopping;

  void onShutdown(@Observes ShutdownEvent event) {
    stopping = true;
  }

  /** {@link #onShutdown}'s flag, for a suite; a real process only ever raises it. */
  void stopping(boolean stopping) {
    this.stopping = stopping;
  }

  private record InFlight(String daemonId, int stepIndex) {}

  @Override
  public DaemonPin pinDaemon() {
    String version = settings.daemonVersion();
    return new DaemonPin(version, settings.resolveBinaryUrl(version));
  }

  @Override
  public DaemonPin pinDaemon(String version) {
    return new DaemonPin(version, settings.resolveBinaryUrl(version));
  }

  @Override
  public void queued(String runId) {
    leases.request(runId);
  }

  @Override
  public StepResult run(StepSpec spec, StepListener listener) {
    // Before anything is sent: a value that reached the lease's environment is validated again.
    CiIdentifiers.requireRepo(spec.repo());
    CiIdentifiers.requireBranch(spec.branch());
    CiIdentifiers.requireSha(spec.sha());
    if (stopping) {
      throw new ProcessStopping(spec.runId(), spec.stepIndex());
    }
    String ended = endedLeases.get(spec.runId());
    Optional<CiLeases.Launch> launch = leases.launchOf(spec.runId());
    if (launch.isEmpty() || daemons.phaseOf(launch.get().daemonId()) == null) {
      return failed(StepOutcome.LAUNCH_FAILED, ended != null ? ended : NO_LEASE);
    }
    String daemonId = launch.get().daemonId();
    StepResult unready = awaitReady(spec, daemonId);
    if (unready != null) {
      return unready;
    }
    // Recorded before the step leaves, so a step this process may die running is one its successor
    // can take back. A step that cannot be recorded is not sent (qits-1192).
    try {
      listener.onLaunched(daemonId, launch.get().tokenSubject());
    } catch (RuntimeException unrecorded) {
      return failed(
          StepOutcome.LAUNCH_FAILED,
          "the step was not sent, because it could not be recorded: " + unrecorded.getMessage());
    }
    inFlight.put(spec.runId(), new InFlight(daemonId, spec.stepIndex()));
    StepResult result = null;
    try {
      relay.begin(spec.runId(), spec.stepIndex());
      listener.onStarted();
      relay.started(spec.runId(), Instant.now());
      daemons.sendRunStep(
          daemonId,
          spec.stepIndex(),
          spec.script(),
          spec.timeoutSeconds(),
          CiSlotSpecs.stepEnv(spec.stepIndex(), builderWaitMaxSeconds),
          (stream, seq, text) -> {
            relay.append(spec.runId(), text);
            listener.onChunk(text);
          });
      result = finish(spec, listener, daemonId);
    } finally {
      inFlight.remove(spec.runId());
    }
    return result;
  }

  /**
   * The first step's wait for the daemon to dial in and make its worktree; null once it has. A
   * later step finds the daemon initialized and waits for nothing.
   */
  private StepResult awaitReady(StepSpec spec, String daemonId) {
    if (initialized.contains(spec.runId())) {
      return null;
    }
    StepResult unstarted = awaitLeaseStarted(spec, daemonId);
    if (unstarted != null) {
      return unstarted;
    }
    if (!daemons.awaitRegistered(daemonId, registerWindow(spec.runId()))) {
      abandonIfStopping(spec);
      String ended = endedLeases.get(spec.runId());
      if (ended != null) {
        // The lease ran (awaitLeaseStarted above answered null) and then ended — EXITED, FAILED, or
        // gone — before its daemon ever registered: qits-system never otherwise learns the daemon
        // did not dial back, so it is told here (qits-2382). The same branch is also reachable by a
        // lease that FAILED while still GRANTED, reaped before it ever ran (awaitLeaseStarted's own
        // phase==null arm answers null rather than failing it, leaving this the first failure); that
        // is harmless, because qits-system counts a GRANTED lease's own FAILED transition itself and
        // ignores this reason on it.
        neverDialledBack.add(spec.runId());
        return failed(StepOutcome.LAUNCH_FAILED, ended);
      }
      neverDialledBack.add(spec.runId());
      return failed(
          StepOutcome.NEVER_STARTED,
          "the run's lease container never dialled back within "
              + registerTimeoutSeconds
              + "s of its lease running; its own log is on its execution target");
    }
    CiDaemonRegistry.Initialization initialization =
        daemons.awaitInitialized(daemonId, initDeadline());
    if (initialization.status() != CiDaemonRegistry.Initialization.Status.INITIALIZED) {
      abandonIfStopping(spec);
      String ended = endedLeases.get(spec.runId());
      if (ended != null && initialization.status() != CiDaemonRegistry.Initialization.Status.INIT_FAILED) {
        return failed(StepOutcome.LAUNCH_FAILED, ended);
      }
    }
    switch (initialization.status()) {
      case INITIALIZED -> {
        initialized.add(spec.runId());
        return null;
      }
      case INIT_FAILED -> {
        return failed(outcomeOf(initialization.reason()), detailOf(initialization));
      }
      case NEVER_INITIALIZED -> {
        return failed(StepOutcome.NEVER_INITIALIZED, neverInitialized(initialization.overrun()));
      }
      default -> {
        return failed(StepOutcome.CONNECTION_LOST, "");
      }
    }
  }

  /**
   * The first clock: the wait while the run's lease is granted and its target has not started the
   * container yet — the ci-slot image pull, and the builder's on a target's first CI lease. Null
   * once the lease is running (reported, or proven by the daemon's dial) or has nothing left to
   * start (reaped: ended or cancelled — the register wait then answers at once, with the lease's own
   * words when it ended). {@code LAUNCH_FAILED} when the target never started it in time.
   */
  private StepResult awaitLeaseStarted(StepSpec spec, String daemonId) {
    CompletableFuture<Instant> started = startOf(spec.runId());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(leaseStartTimeoutSeconds);
    while (!started.isDone()) {
      abandonIfStopping(spec);
      CiDaemonRegistry.Phase phase = daemons.phaseOf(daemonId);
      if (phase == null || phase == CiDaemonRegistry.Phase.DONE) {
        return null;
      }
      if (phase != CiDaemonRegistry.Phase.LAUNCHED) {
        // It dialled: its container runs, whatever qits-system has said so far.
        started.complete(Instant.now());
        return null;
      }
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        String ended = endedLeases.get(spec.runId());
        if (ended != null) {
          return failed(StepOutcome.LAUNCH_FAILED, ended);
        }
        LOG.warnf(
            "CI run %s: its lease was granted %ds ago and its target has not started the container",
            spec.runId(), leaseStartTimeoutSeconds);
        return failed(StepOutcome.LAUNCH_FAILED, leaseNeverStarted());
      }
      try {
        started.get(Math.min(left, START_POLL.toNanos()), TimeUnit.NANOSECONDS);
      } catch (TimeoutException stillGranted) {
        // look again
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return failed(StepOutcome.LAUNCH_FAILED, "the wait for the run's lease to start was interrupted");
      } catch (ExecutionException impossible) {
        return null;
      }
    }
    return null;
  }

  /** What a step whose lease the target never started says. */
  String leaseNeverStarted() {
    return "the run's lease was granted, and its execution target did not start the container within "
        + leaseStartTimeoutSeconds
        + "s (qits.ci.lease-start-timeout-seconds: the ci-slot image pull, and the builder's on the"
        + " target's first CI lease); the target's runner log says why";
  }

  /** The second clock: the register deadline, counted from when the lease was seen running. */
  private Duration registerWindow(String runId) {
    Duration full = Duration.ofSeconds(registerTimeoutSeconds);
    Instant runningAt = startOf(runId).getNow(null);
    if (runningAt == null) {
      return full;
    }
    Duration left = full.minus(Duration.between(runningAt, Instant.now()));
    return left.isNegative() ? Duration.ZERO : left;
  }

  private CompletableFuture<Instant> startOf(String runId) {
    return leaseStarts.computeIfAbsent(runId, id -> new CompletableFuture<>());
  }

  /**
   * The run's lease is running: qits-system reported it {@code RUNNING}, or its daemon dialled in.
   * The register clock counts from the first such report; later ones change nothing. Called by
   * {@link CiLeases}, only for a lease whose launch it holds.
   */
  void leaseRunning(String runId) {
    startOf(runId).complete(Instant.now());
  }

  /**
   * The backstop duration itself, pulled out so a suite can assert the arithmetic without waiting
   * one out: the step's own deadline, {@code builderWaitMaxSeconds} and the grace, in that order.
   */
  Duration backstopOf(StepSpec spec) {
    return Duration.ofSeconds(
        spec.timeoutSeconds() + builderWaitMaxSeconds + stepTimeoutGraceSeconds);
  }

  /**
   * The step's end, from its terminal frame or the backstop.
   *
   * <p>The backstop is the step's own deadline, plus {@code qits.ci.builder-wait-max-seconds}
   * (qits-1807), plus the grace. The middle term exists because the daemon enforces the step's
   * deadline against the step's OWN running time, and since qits-1807 it may pause that clock —
   * for at most {@code builderWaitMaxSeconds} — while a {@code buildctl build} inside the step
   * waits at the target's shared, target-wide builder gate rather than actually building: time the
   * step spent queued, not time it spent hung. Without the widening the host's own backstop would
   * fire mid-queue, on a daemon that is behaving exactly as designed, and cancel a step that was
   * simply waiting its turn at the builder. The grace stays last and unchanged: it is still only
   * the daemon's SIGTERM/SIGKILL and one frame's flight once the daemon itself gives up.
   */
  private StepResult finish(StepSpec spec, StepListener listener, String daemonId) {
    Duration backstop = backstopOf(spec);
    CiDaemonRegistry.Completion completion = daemons.awaitFinished(daemonId, backstop);
    if (completion.status() != CiDaemonRegistry.Completion.Status.FINISHED) {
      // Before the NO_ANSWER branch below cancels anything: an await the shutdown cut short is not
      // a step that stopped answering (qits-1192).
      abandonIfStopping(spec);
    }
    listener.onFinished();
    String ended = endedLeases.get(spec.runId());
    return switch (completion.status()) {
      case FINISHED ->
          new StepResult(
              completion.exitCode(), completion.timedOut(), StepOutcome.OK, tail(spec.runId()));
      case NO_ANSWER -> {
        LOG.warnf(
            "ci-daemon %s did not answer within %s of its step's deadline — cancelling",
            daemonId, backstop);
        daemons.cancel(daemonId);
        yield new StepResult(-1, true, StepOutcome.OK, tail(spec.runId()));
      }
      case CONNECTION_LOST ->
          new StepResult(
              -1,
              false,
              StepOutcome.CONNECTION_LOST,
              ended == null
                  ? tail(spec.runId())
                  : withLine(tail(spec.runId()), "[the run's lease ended: " + ended + "]"));
    };
  }

  /**
   * A bracketed line about the run's lease, put on the step it has in flight (qits-1818): appended
   * to the relay, so a live read shows it at once and the step's row carries it — the row's output
   * is the relay's tail when the step ends. Answers whether a step was in flight; with none the line
   * is dropped, and whatever it reported stays on the run alone — a line arriving between two steps
   * is not carried onto the next one.
   */
  boolean noteInFlight(String runId, String line) {
    if (runId == null || line == null || !inFlight.containsKey(runId)) {
      return false;
    }
    String tail = tail(runId);
    relay.append(runId, (tail.isEmpty() || tail.endsWith("\n") ? "" : "\n") + line + "\n");
    return true;
  }

  /** {@link ProcessStopping}, thrown from the step's own waits once the process is stopping. */
  private void abandonIfStopping(StepSpec spec) {
    if (stopping) {
      throw new ProcessStopping(spec.runId(), spec.stepIndex());
    }
  }

  /**
   * The run's lease ended while the run did not (qits-system reported it {@code FAILED}, {@code
   * EXITED} or gone): remember why and reap the launch, which completes every await of the step at
   * once. Called by {@link CiLeases}.
   */
  void leaseEnded(String runId, String detail) {
    endedLeases.put(runId, detail == null || detail.isBlank() ? "no detail" : detail);
    leases.launchOf(runId).ifPresent(launch -> daemons.reap(launch.daemonId()));
    startOf(runId).complete(null);
  }

  // --- a restart (qits-1192) --------------------------------------------------------------------

  /**
   * A run the previous process left in a lease. Its launch is registered again under its old id —
   * the daemon re-dials within its reconnect budget and must be admitted when it does — once its
   * recorded token subject is confirmed as one of the run's live tokens at qits-idp, off the startup
   * thread. When the daemon dials back within its grace, {@code adopted} runs on a driver thread of
   * its own; when it does not, or the subject is not confirmed, {@code lost} runs instead.
   */
  @Override
  public void inherit(InheritedRun run, Runnable adopted, Runnable lost) {
    String runId = run.runId();
    // A thread of its own rather than the common pool: it inherits this thread's class loader, so
    // a bean first created on it reads the application's config.
    Thread check =
        new Thread(
            () -> {
              boolean taken;
              try {
                taken = takeBack(run);
              } catch (Throwable t) {
                LOG.warnf(t, "Run %s could not be taken back", runId);
                taken = false;
              }
              if (taken) {
                leases.driveInherited(runId, adopted);
              } else {
                givenUp.add(runId);
                leases.launchOf(runId).ifPresent(launch -> daemons.reap(launch.daemonId()));
                leases.forgetLaunch(runId);
                relay.drop(runId);
                lost.run();
              }
            },
            "ci-inherit-" + runId);
    check.setDaemon(true);
    check.start();
  }

  /** Registers an inherited launch and waits for its daemon; whether it came back. */
  private boolean takeBack(InheritedRun run) {
    String runId = run.runId();
    Optional<Set<String>> live;
    try {
      live = commissions.liveSubjectsOf(runId);
    } catch (RuntimeException e) {
      live = Optional.empty();
    }
    if (live.isEmpty()) {
      LOG.warnf(
          "Run %s: qits-idp's token listing could not be read, so the recorded launch %s is not"
              + " trusted and nothing is taken back",
          runId, run.daemonId());
      return false;
    }
    if (!live.orElseThrow().contains(run.tokenSubject())) {
      LOG.warnf(
          "Run %s: the recorded token subject of launch %s is not one of the run's live tokens;"
              + " nothing is taken back",
          runId, run.daemonId());
      return false;
    }
    // Before the launch is registered, so the step's first report after its daemon re-dials is
    // accepted: the report door knows a run's token by its subject (qits-1192).
    commissions.adoptInherited(runId, run.tokenSubject());
    leases.adopt(runId, run.leaseId(), run.daemonId(), run.tokenSubject());
    InFlightStep step = run.inFlight();
    CiDaemonRegistry.InFlightStep resumed = null;
    if (step != null) {
      relay.begin(runId, step.stepIndex());
      if (step.startedAt() != null) {
        relay.started(runId, step.startedAt());
        resumed =
            new CiDaemonRegistry.InFlightStep(
                step.stepIndex(),
                step.script(),
                step.timeoutSeconds(),
                CiSlotSpecs.stepEnv(step.stepIndex(), builderWaitMaxSeconds));
      }
    }
    daemons.reattachLaunch(
        run.daemonId(),
        runId,
        run.tokenSubject(),
        resumed,
        (stream, seq, text) -> relay.append(runId, text));
    if (!daemons.awaitRegistered(run.daemonId(), daemons.reconnectGrace().plusSeconds(5))) {
      LOG.warnf(
          "Run %s: its ci-daemon %s did not dial back within %s; it is not taken back",
          runId, run.daemonId(), daemons.reconnectGrace());
      return false;
    }
    // It dialled, so its lease runs: the first step's start wait has nothing left to wait for.
    startOf(runId).complete(Instant.now());
    // A daemon that is back between two steps, or with one in flight, has its worktree: it ran a
    // step in it. Before the first step it may not (qits-1893): it may be dialling for the first
    // time with its checkout still ahead of it, and a RunStep sent then — before its Ack, even — is
    // one it drops, leaving the step to the backstop with no output. So that run's first step waits
    // for Initialized as a fresh run's does, under initDeadline() (qits-2539): a daemon
    // that said it to the previous process says it again after this Ack, since no step answered it
    // (qits-748), and one that never says it ends NEVER_INITIALIZED on that clock.
    if (!run.awaitsInitialized()) {
      initialized.add(runId);
    }
    return !givenUp.contains(runId);
  }

  @Override
  public void revokeInherited(String runId) {
    commissions.endAdoption(runId);
    commissions.revokeOrphaned(runId);
  }

  /** What a taken-back step that could not be collected says, before why. */
  static final String NOT_TAKEN_BACK =
      "[qits-ci restarted while this step was running, and the step could not be taken back";

  /**
   * Take back an inherited run's in-flight step ({@link #inherit}): its daemon has dialled back, so
   * send it its step again when it is still owed it (a daemon ignores a step index it already ran)
   * and collect the result as {@link #run} would.
   */
  @Override
  public StepResult reattach(StepSpec spec, StepListener listener) {
    Optional<CiLeases.Launch> launch = leases.launchOf(spec.runId());
    if (launch.isEmpty() || daemons.phaseOf(launch.get().daemonId()) == null) {
      return failed(
          StepOutcome.CONNECTION_LOST,
          NOT_TAKEN_BACK + ": its launch is no longer known. The step is not started again.]");
    }
    String daemonId = launch.get().daemonId();
    inFlight.put(spec.runId(), new InFlight(daemonId, spec.stepIndex()));
    try {
      if (daemons.phaseOf(daemonId) != CiDaemonRegistry.Phase.RUNNING) {
        // Not started when the previous process stopped: the daemon is owed its step — unless it
        // ends first, having had it after all.
        CiDaemonRegistry.Initialization initialization =
            daemons.awaitInitializedUnlessFinished(daemonId, initDeadline());
        if (initialization != null) {
          switch (initialization.status()) {
            case INITIALIZED -> {
              abandonIfStopping(spec);
              listener.onStarted();
              relay.started(spec.runId(), Instant.now());
              daemons.sendRunStep(
                  daemonId,
                  spec.stepIndex(),
                  spec.script(),
                  spec.timeoutSeconds(),
                  CiSlotSpecs.stepEnv(spec.stepIndex(), builderWaitMaxSeconds),
                  (stream, seq, text) -> relay.append(spec.runId(), text));
            }
            case INIT_FAILED -> {
              return failed(outcomeOf(initialization.reason()), detailOf(initialization));
            }
            case NEVER_INITIALIZED -> {
              abandonIfStopping(spec);
              return failed(
                  StepOutcome.NEVER_INITIALIZED, neverInitialized(initialization.overrun()));
            }
            case CONNECTION_LOST -> {
              abandonIfStopping(spec);
              return failed(
                  StepOutcome.CONNECTION_LOST, NOT_TAKEN_BACK + ": its ci-daemon went away again.]");
            }
          }
        }
      }
      StepResult result = finish(spec, listener, daemonId);
      String note = "[qits-ci restarted while this step was running; it was taken back]";
      return new StepResult(
          result.exitCode(), result.timedOut(), result.outcome(), withLine(note, result.output()));
    } finally {
      inFlight.remove(spec.runId());
    }
  }

  /**
   * Stop a run. A step that is running is stopped by its daemon — a {@code Cancel} answered with a
   * terminal frame. Before that there is nothing in the container to stop, so the launch is reaped,
   * which completes the step's awaits at once; the run's close ends the lease.
   */
  @Override
  public void cancel(String runId) {
    InFlight current = inFlight.get(runId);
    if (current != null && daemons.phaseOf(current.daemonId()) == CiDaemonRegistry.Phase.RUNNING) {
      daemons.cancel(current.daemonId());
      return;
    }
    leases.launchOf(runId).ifPresent(launch -> daemons.reap(launch.daemonId()));
    CompletableFuture<Instant> started = leaseStarts.get(runId);
    if (started != null) {
      started.complete(null);
    }
  }

  /** A driver of this process holds the run from its claim to its close. */
  @Override
  public boolean owns(String runId) {
    return leases.drives(runId);
  }

  /**
   * Everything the run held, given back: the relay's buffer and the in-flight record, at once; the
   * daemon, told {@code Close} so it removes its worktree and flushes its telemetry; and — once it
   * has hung up, or {@code qits.ci.daemon.close-grace-seconds} has passed ({@link
   * CiDaemonRegistry#sendCloseUntilGone}, qits-2572) — the daemon forgotten, the lease ended — with
   * {@link SystemLeases#NEVER_DIALLED_BACK} when its daemon never dialled back (qits-1809) — and the
   * run's {@code ci-run} token deleted, with any a previous process or an earlier lease of the run
   * left and a continued run's phase-1 token. Those three wait because the daemon's last telemetry
   * travels on that token from inside that lease: ended straight after the {@code Close}, they raced
   * the flush and usually won.
   *
   * <p>Never parks the caller: the wait is a stage, and a launch with no live connection — never
   * dialled, lost, inside its reconnect grace, already closed — or no launch at all is torn down
   * before this returns, as always. The launch, the lease and the token are taken off the run
   * <em>now</em> ({@link CiLeases#closing}, {@link RunCommissions#detach}) and the deferred half
   * ends exactly those, so a re-queued run of the same id that asks a lease and a token meanwhile
   * is never confused with them.
   */
  @Override
  public void runClosed(String runId) {
    relay.drop(runId);
    inFlight.remove(runId);
    initialized.remove(runId);
    givenUp.remove(runId);
    String reason = neverDialledBack.remove(runId) ? SystemLeases.NEVER_DIALLED_BACK : null;
    endedLeases.remove(runId);
    leaseStarts.remove(runId);
    String phaseOne = phaseOneOf.remove(runId);
    Optional<CiLeases.Launch> launch = leases.launchOf(runId);
    if (launch.isEmpty()) {
      // No launch held here: its row may still name a lease, which end() reads and ends at once.
      leases.end(runId, reason);
      giveBackTokens(runId, commissions.detach(runId), phaseOne);
      return;
    }
    CiLeases.Launch held = launch.get();
    leases.closing(held.leaseId());
    leases.forgetLaunch(runId);
    IdpCommissioner.CommissionedToken token = commissions.detach(runId);
    CompletionStage<Void> gone;
    try {
      gone = daemons.sendCloseUntilGone(held.daemonId());
    } catch (RuntimeException e) {
      LOG.debugf("CI run %s: its daemon could not be told Close: %s", runId, e.getMessage());
      gone = CompletableFuture.completedFuture(null);
    }
    gone.whenComplete(
        (done, failed) -> {
          try {
            daemons.reap(held.daemonId());
          } catch (RuntimeException e) {
            LOG.debugf("CI run %s: its daemon could not be reaped: %s", runId, e.getMessage());
          }
          leases.endClosed(runId, held.leaseId(), reason);
          giveBackTokens(runId, token, phaseOne);
        });
  }

  /**
   * The run's token given back — {@code token}, which {@link RunCommissions#detach} took off it —
   * then any a previous process left, then a continued run's phase-1 run's. Never throws.
   */
  private void giveBackTokens(
      String runId, IdpCommissioner.CommissionedToken token, String phaseOne) {
    try {
      commissions.endAdoption(runId);
      commissions.release(token);
      commissions.revokeOrphaned(runId);
      if (phaseOne != null) {
        revokePhaseOne(phaseOne);
      }
    } catch (RuntimeException e) {
      LOG.warnf("CI run %s: its tokens could not be given back: %s", runId, e.getMessage());
    }
  }

  /**
   * The slot's half of holding a green QA run's slot for its release (epic qits-732): with the
   * switch on, the run's launch still held here and alive — its daemon connected or inside its
   * reconnect grace, its lease not seen ended — and its daemon having announced capability {@value
   * CiSlotHolds#MIN_CAPABILITY} or more in its {@code Hello}. A capability-2 daemon cannot be told to
   * continue, so its run closes as ever. When {@link CiSlotHolds#enter} holds it, the launch moves
   * from the run to the hold and the run's own bookkeeping here goes; the daemon, the lease and the
   * token are the hold's to give back.
   */
  @Override
  public boolean holdSlot(SlotHold hold) {
    if (!holds.enabled() || stopping) {
      return false;
    }
    String runId = hold.runId();
    Optional<CiLeases.Launch> launch = leases.launchOf(runId);
    if (launch.isEmpty() || endedLeases.containsKey(runId)) {
      return false;
    }
    String daemonId = launch.get().daemonId();
    Integer capability = daemons.capabilityVersionOf(daemonId);
    if (capability == null || capability < CiSlotHolds.MIN_CAPABILITY) {
      LOG.debugf(
          "CI run %s: its ci-daemon %s announced capability %s; its slot is not held",
          runId, daemonId, capability);
      return false;
    }
    if (daemons.phaseOf(daemonId) != CiDaemonRegistry.Phase.INITIALIZED || daemons.lost(daemonId)) {
      return false;
    }
    if (!holds.enter(hold, launch.get())) {
      return false;
    }
    leases.forgetLaunch(runId);
    relay.drop(runId);
    inFlight.remove(runId);
    initialized.remove(runId);
    givenUp.remove(runId);
    endedLeases.remove(runId);
    leaseStarts.remove(runId);
    return true;
  }

  // --- continuing in a held slot (epic qits-732) ------------------------------------------------

  /** Slots taken for a phase-2 run and not yet continued in or given back, by lease. */
  private final ConcurrentHashMap<UUID, CiSlotHolds.Hold> taken = new ConcurrentHashMap<>();

  @Override
  public Optional<HeldSlot> takeHeldSlot(String repoId, String releaseRequestId) {
    return holds
        .take(repoId, releaseRequestId)
        .map(
            hold -> {
              taken.put(hold.leaseId(), hold);
              return new HeldSlot(
                  hold.leaseId(),
                  hold.targetId(),
                  hold.daemonVersion(),
                  hold.heldFromRunId(),
                  hold.requestName());
            });
  }

  @Override
  public void giveBackHeldSlot(HeldSlot slot, String reason) {
    CiSlotHolds.Hold hold = taken.remove(slot.leaseId());
    if (hold != null) {
      holds.teardown(hold, reason);
    }
  }

  /**
   * The {@code Continue}, on the phase-2 run's own driver thread: the run's token minted, the slot's
   * launch handed to the run (in the daemon registry, re-keyed to the new token's subject, and in
   * {@link CiLeases}), the frame sent, and its answer awaited under {@code
   * qits.ci.daemon-init-timeout-seconds} — the budget a daemon has to make a worktree, which is what
   * moving one is; for a capability-4 daemon, its progress-aware {@link #initDeadline} (qits-2539),
   * since a {@code Continue} re-fetches the mirror like a setup does. {@code Continued}: the run
   * is driven ({@code continued}) and the phase-1 token is
   * kept until this run's lease is torn down ({@link #phaseOneOf}), its first {@code RunStep} index 0, since the daemon counts the new run's steps from
   * nothing. Anything else: {@link #abandon} and {@code failed}.
   */
  @Override
  public void continueOnHeldSlot(Continuation continuation, Runnable continued, Runnable failed) {
    CiSlotHolds.Hold hold = taken.remove(continuation.slot().leaseId());
    if (hold == null) {
      failed.run();
      return;
    }
    leases.driveContinued(
        continuation.runId(), () -> continueNow(continuation, hold, continued, failed));
  }

  private void continueNow(
      Continuation continuation, CiSlotHolds.Hold hold, Runnable continued, Runnable failed) {
    String runId = continuation.runId();
    String daemonId = hold.daemonId();
    if (stopping
        || daemons.phaseOf(daemonId) != CiDaemonRegistry.Phase.INITIALIZED
        || daemons.lost(daemonId)) {
      abandon(runId, hold, "its daemon is gone or busy");
      failed.run();
      return;
    }
    Optional<CiSlotAddresses.Plane> plane = leases.plane();
    IdpCommissioner.CommissionedToken token = null;
    try {
      token = plane.isEmpty() ? null : commissions.forRun(runId, continuation.runEnv());
    } catch (IdpCommissioner.CommissionFailedException notCommissioned) {
      LOG.warnf("CI run %s: %s", runId, notCommissioned.getMessage());
    }
    if (token == null) {
      abandon(runId, hold, "no token could be commissioned for the run");
      failed.run();
      return;
    }
    if (!daemons.rekeyLaunch(daemonId, runId, token.subject())) {
      abandon(runId, hold, "its daemon's launch is gone");
      failed.run();
      return;
    }
    leases.adopt(runId, hold.leaseId(), daemonId, token.subject());
    leases.recordContinuedLaunch(runId, hold.leaseId(), daemonId, token.subject());
    Map<String, String> runEnv = continuation.runEnv() == null ? Map.of() : continuation.runEnv();
    // The commit the daemon prepares the worktree at, which is the fold for a release-request
    // automation and the run's own sha otherwise (qits-1836); the run stays recorded at its own.
    String worktreeSha = CiSlotSpecs.worktreeSha(continuation.sha(), runEnv);
    Continue next =
        new Continue(
            runId,
            continuation.branch(),
            worktreeSha,
            token.token(),
            CiSlotSpecs.continueEnv(
                plane.get(), runId, continuation.branch(), worktreeSha, token, runEnv));
    CiDaemonRegistry.Continuation answer =
        daemons.sendContinue(daemonId, next)
            ? daemons.awaitContinued(daemonId, initDeadline())
            : new CiDaemonRegistry.Continuation(
                CiDaemonRegistry.Continuation.Status.CONNECTION_LOST, null, null);
    if (answer.status() != CiDaemonRegistry.Continuation.Status.CONTINUED) {
      LOG.warnf(
          "CI run %s: the daemon %s did not continue on held lease %s (%s%s%s)",
          runId,
          daemonId,
          hold.leaseId(),
          answer.status(),
          answer.reason() == null ? "" : ": " + answer.reason() + " " + answer.detail(),
          answer.overrun() == null ? "" : ": " + describe(answer.overrun()));
      abandon(runId, hold, "Continue answered " + answer.status());
      failed.run();
      return;
    }
    // The daemon is the new run's: its worktree is at the tag, its bearer the new token.
    initialized.add(runId);
    startOf(runId).complete(Instant.now());
    phaseOneOf.put(runId, hold.heldFromRunId());
    LOG.infof("CI run %s continued on held lease %s (daemon %s)", runId, hold.leaseId(), daemonId);
    continued.run();
  }

  /**
   * A held slot nothing continues in, after it was taken: the launch forgotten by the run and the
   * run's own bookkeeping dropped at once; {@code Close} to the daemon; and once it has hung up or the
   * close grace has passed (qits-2572, as in {@link #runClosed}) the launch reaped, the lease ended,
   * and the new run's token and the phase-1 run's both given back. The new run goes back to the queue
   * (its caller's {@code failed}) at once, and its token is taken off it first ({@link
   * RunCommissions#detach}), so the fresh lease it asks commissions a token of its own.
   */
  private void abandon(String runId, CiSlotHolds.Hold hold, String why) {
    LOG.infof("Held lease %s is not continued in by run %s: %s", hold.leaseId(), runId, why);
    leases.closing(hold.leaseId());
    Optional<CiLeases.Launch> launch = leases.launchOf(runId);
    if (launch.isPresent() && launch.get().leaseId().equals(hold.leaseId())) {
      leases.forgetLaunch(runId);
    }
    initialized.remove(runId);
    leaseStarts.remove(runId);
    endedLeases.remove(runId);
    IdpCommissioner.CommissionedToken token = commissions.detach(runId);
    commissions.endAdoption(runId);
    CompletionStage<Void> gone;
    try {
      gone = daemons.sendCloseUntilGone(hold.daemonId());
    } catch (RuntimeException e) {
      LOG.debugf("Held lease %s: its daemon could not be closed: %s", hold.leaseId(), e.getMessage());
      gone = CompletableFuture.completedFuture(null);
    }
    gone.whenComplete(
        (done, failed) -> {
          try {
            daemons.reap(hold.daemonId());
          } catch (RuntimeException e) {
            LOG.debugf(
                "Held lease %s: its daemon could not be reaped: %s", hold.leaseId(), e.getMessage());
          }
          leases.endClosed(runId, hold.leaseId(), null);
          try {
            commissions.release(token);
            revokePhaseOne(hold.heldFromRunId());
          } catch (RuntimeException e) {
            LOG.warnf(
                "Held lease %s: its tokens could not be given back: %s",
                hold.leaseId(), e.getMessage());
          }
        });
  }

  private void revokePhaseOne(String phaseOneRunId) {
    commissions.endAdoption(phaseOneRunId);
    commissions.release(phaseOneRunId);
    commissions.revokeOrphaned(phaseOneRunId);
  }

  // --- internals --------------------------------------------------------------------------------

  /** A failed worktree whose commit is gone is its own outcome; every other refusal is INIT_FAILED. */
  static StepOutcome outcomeOf(InitFailed.Reason reason) {
    return reason == InitFailed.Reason.SHA_GONE ? StepOutcome.SHA_GONE : StepOutcome.INIT_FAILED;
  }

  /** What a worktree that was never finished is recorded with, before what the daemon reported. */
  static final String NEVER_INITIALIZED = "the run's ci-daemon never finished making its worktree";

  /**
   * The step output of a {@code NEVER_INITIALIZED} first step: the old sentence alone for the flat
   * budget (a daemon below capability 4, {@code overrun} null), and for a progress-aware one
   * (qits-2539) where it stood when the host gave up — so a run that failed on a slow target says
   * whether it was still fetching, how far it had got, and which bound ended it.
   */
  static String neverInitialized(CiDaemonRegistry.Overrun overrun) {
    return overrun == null ? NEVER_INITIALIZED : NEVER_INITIALIZED + ": " + describe(overrun);
  }

  /**
   * Where a progress-aware wait left the daemon (qits-2539), in words: {@code fetching the mirror,
   * 36.2 MiB received, no progress for 300 s}, {@code …, still not done after 1800 s}, or {@code it
   * reported no progress within 300 s} when no snapshot arrived at all.
   */
  static String describe(CiDaemonRegistry.Overrun overrun) {
    InitProgress last = overrun.last();
    String window = span(overrun.window());
    if (last == null) {
      return "it reported no progress within " + window;
    }
    return phaseWords(last.phase())
        + ", "
        + bytes(last.bytes())
        + " received, "
        + (overrun.kind() == CiDaemonRegistry.Overrun.Kind.STALLED
            ? "no progress for " + window
            : "still not done after " + window);
  }

  /** The daemon's setup phase as a step output reads it. */
  static String phaseWords(InitProgress.Phase phase) {
    if (phase == null) {
      return "at an unnamed step";
    }
    return switch (phase) {
      case LOCK_WAIT -> "waiting for the mirror's lock";
      case MIRROR_CLONE -> "cloning the mirror";
      case MIRROR_FETCH -> "fetching the mirror";
      case WORKTREE -> "adding the worktree";
      case SUBMODULES -> "updating submodules";
    };
  }

  /** A byte count in binary units with one decimal: {@code 512 B}, {@code 36.2 MiB}. */
  static String bytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    String[] units = {"KiB", "MiB", "GiB", "TiB"};
    double value = bytes;
    int unit = -1;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }

  /** A window in whole seconds, or milliseconds when a suite's is shorter than that. */
  private static String span(Duration window) {
    return window.toMillis() % 1000 == 0
        ? window.toSeconds() + " s"
        : window.toMillis() + " ms";
  }

  /** The daemon's reason and detail for a failed initialization, as the step's recorded output. */
  static String detailOf(CiDaemonRegistry.Initialization initialization) {
    String reason = initialization.reason() == null ? "unspecified" : initialization.reason().name();
    String detail = initialization.detail();
    return detail == null || detail.isBlank() ? reason : reason + ": " + detail;
  }

  private static StepResult failed(StepOutcome outcome, String detail) {
    return new StepResult(-1, false, outcome, detail == null ? "" : detail);
  }

  private static String withLine(String text, String line) {
    if (line == null || line.isEmpty()) {
      return text == null ? "" : text;
    }
    return text == null || text.isEmpty() ? line : text + "\n" + line;
  }

  private String tail(String runId) {
    return relay.snapshot(runId).map(CiStepRelay.Snapshot::output).orElse("");
  }

  /** For a suite: when the run's lease was seen running ({@link #leaseRunning}), or null. */
  Instant leaseRunningSince(String runId) {
    CompletableFuture<Instant> started = leaseStarts.get(runId);
    return started == null ? null : started.getNow(null);
  }

  /** For a suite: the reason a run's lease ended, as {@link #leaseEnded} recorded it. */
  Map<String, String> endedLeases() {
    return Map.copyOf(endedLeases);
  }
}
