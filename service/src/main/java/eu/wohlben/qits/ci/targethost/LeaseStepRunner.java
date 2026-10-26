package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
 * and its daemon to dial in, and then for the daemon to report its worktree ready ({@code
 * qits.ci.daemon-init-timeout-seconds}); every step, the first included, is then one
 * {@code RunStep{stepIndex, script, timeoutSeconds, env}} on that connection, answered by {@code
 * StepChunk}s and a {@code StepFinished} naming the step, with the host's backstop {@code
 * qits.ci.step-timeout-grace-seconds} behind the daemon's own deadline. {@link #runClosed} tells the
 * daemon {@code Close}, ends the lease and deletes the run's token. No container is started or
 * removed here: the lease's container is the target's.
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

  @ConfigProperty(name = "qits.ci.daemon-register-timeout-seconds")
  long registerTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.lease-start-timeout-seconds")
  long leaseStartTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.daemon-init-timeout-seconds")
  long initTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.step-timeout-grace-seconds")
  long stepTimeoutGraceSeconds;

  /** Package-private for a suite that cannot wait out the real 180s register deadline. */
  void registerTimeoutSeconds(long seconds) {
    this.registerTimeoutSeconds = seconds;
  }

  long registerTimeoutSeconds() {
    return registerTimeoutSeconds;
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

  /** Runs whose daemon has reported its worktree ready in this process. */
  private final Set<String> initialized = ConcurrentHashMap.newKeySet();

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
          CiSlotSpecs.stepEnv(spec.stepIndex()),
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
        return failed(StepOutcome.LAUNCH_FAILED, ended);
      }
      return failed(
          StepOutcome.NEVER_STARTED,
          "the run's lease container never dialled back within "
              + registerTimeoutSeconds
              + "s of its lease running; its own log is on its execution target");
    }
    CiDaemonRegistry.Initialization initialization =
        daemons.awaitInitialized(daemonId, Duration.ofSeconds(initTimeoutSeconds));
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
        return failed(
            StepOutcome.NEVER_INITIALIZED,
            "the run's ci-daemon never finished making its worktree");
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

  /** The step's end, from its terminal frame or the backstop. */
  private StepResult finish(StepSpec spec, StepListener listener, String daemonId) {
    Duration backstop = Duration.ofSeconds(spec.timeoutSeconds() + stepTimeoutGraceSeconds);
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
                CiSlotSpecs.stepEnv(step.stepIndex()));
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
    // A daemon that is back has its worktree: it initialized before the restart, or says so again.
    initialized.add(runId);
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
            daemons.awaitInitializedUnlessFinished(daemonId, Duration.ofSeconds(initTimeoutSeconds));
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
                  CiSlotSpecs.stepEnv(spec.stepIndex()),
                  (stream, seq, text) -> relay.append(spec.runId(), text));
            }
            case INIT_FAILED -> {
              return failed(outcomeOf(initialization.reason()), detailOf(initialization));
            }
            case NEVER_INITIALIZED -> {
              abandonIfStopping(spec);
              return failed(
                  StepOutcome.NEVER_INITIALIZED,
                  "the run's ci-daemon never finished making its worktree");
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
   * Everything the run held, given back: the relay's buffer and the in-flight record; the daemon,
   * told {@code Close} so it removes its worktree, then forgotten; the lease, ended; and the run's
   * {@code ci-run} token, deleted — with any a previous process or an earlier lease of the run left.
   */
  @Override
  public void runClosed(String runId) {
    relay.drop(runId);
    inFlight.remove(runId);
    initialized.remove(runId);
    givenUp.remove(runId);
    Optional<CiLeases.Launch> launch = leases.launchOf(runId);
    launch.ifPresent(
        held -> {
          daemons.sendClose(held.daemonId());
          daemons.reap(held.daemonId());
        });
    leases.end(runId);
    endedLeases.remove(runId);
    leaseStarts.remove(runId);
    commissions.endAdoption(runId);
    commissions.release(runId);
    commissions.revokeOrphaned(runId);
  }

  // --- internals --------------------------------------------------------------------------------

  /** A failed worktree whose commit is gone is its own outcome; every other refusal is INIT_FAILED. */
  static StepOutcome outcomeOf(InitFailed.Reason reason) {
    return reason == InitFailed.Reason.SHA_GONE ? StepOutcome.SHA_GONE : StepOutcome.INIT_FAILED;
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
