package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.AckReceived;
import eu.wohlben.qits.cidaemon.protocol.Cancel;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Close;
import eu.wohlben.qits.cidaemon.protocol.Continue;
import eu.wohlben.qits.cidaemon.protocol.ContinueFailed;
import eu.wohlben.qits.cidaemon.protocol.Continued;
import eu.wohlben.qits.cidaemon.protocol.Heartbeat;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-memory launch table: one launch per CI run's lease (epic qits-1481, capability 2), keyed
 * by the {@code daemonId} the lease's container was told, holding the subject of its run's {@code
 * ci-run} token, the run it belongs to, its connection once it dials, its lifecycle phase, and the
 * step in flight with the listener that step's output goes to. One daemon runs every step of the
 * run: it says {@code Initialized} once, is sent one {@link RunStep} per step, answers each with a
 * {@link StepFinished} naming the step, and is told {@link Close} when the run is over. It is the in-JVM half of the control plane — {@link CiDaemonSocket} owns the WebSocket
 * lifecycle and forwards frames here, exactly as {@code WorkspaceDaemonRegistry} sits behind {@code
 * DaemonControlSocket} in qits-workspaces.
 *
 * <p><b>The blocking bridge.</b> Each run executes on one worker ({@code CiRunService}), one step at
 * a time, and that thread used to park on a {@code docker run} process. It now parks
 * here instead: {@link #awaitRegistered}, {@link #awaitHello}, {@link #awaitAckConfirmed}, {@link
 * #awaitInitialized} and {@link #awaitFinished} each block on one {@code CompletableFuture} per
 * lifecycle transition while chunks
 * flow to the step's listener as they arrive. The failure mode that swap introduces is anything that
 * never returns
 * wedging all of CI, so <b>nothing in this package waits without a deadline</b> — and that covers
 * three kinds of wait, not one:
 *
 * <ul>
 *   <li>the lifecycle futures, through {@link #await}, the single place a future is waited on and
 *       one that always passes a deadline;
 *   <li>writing a frame, through {@link #send}, which spells out {@code .await().atMost(…)} rather
 *       than taking the {@code sendTextAndAwait} convenience;
 *   <li>closing a socket, through {@link #closeBounded}, for the same reason and against the same
 *       convenience — {@code closeAndAwait} is {@code close().await().indefinitely()} with the
 *       untimed part one frame out of sight.
 * </ul>
 *
 * <p>{@code CiDaemonRegistryTimeoutTest} holds the behaviour and greps this package for every one of
 * those conveniences, so a fourth wait added untimed fails a build rather than a run.
 *
 * <p><b>A daemon is matched to its launch by the launch id it names, bound to its run's token.</b>
 * The step's daemon dials through the platform edge with its run's {@code ci-run} token, so the
 * connection arrives as that token's subject; its first frame, a {@code Hello}, names the launch;
 * and {@link #admitByToken} admits it only when the named launch was recorded against that subject.
 * There is no per-container secret and no handshake header: the {@code X-Qits-Ci-Daemon-Id}/{@code
 * -Secret} pair a daemon on qits-net presented was deleted with that plane (qits-515). The launch
 * id is not a secret — the token is the credential, worth one run and dead when the run closes.
 * There is no storage beyond this map, which is what makes the restart story free: a qits-ci
 * restart forgets every launch by construction, so a daemon from a previous life names one this
 * registry does not know and is closed 1008. What an admitted connection may do is exactly
 * "deliver data about this run" — the container turns hostile the moment step code runs in it, so
 * everything arriving over that connection is attacker-influenced data, recorded and never trusted
 * (which is why timestamps are host-stamped and a {@code Hello}'s {@code daemonId} is checked
 * rather than believed).
 *
 * <p><b>A dropped socket is a gap, not an ending</b> (qits-748). The daemon's socket goes through the
 * platform edge like the runner's, so an edge redeploy drops every step's socket at the same instant
 * while the containers keep running. {@link #onClose} therefore unbinds the connection and completes
 * nothing: the launch waits {@code qits.ci.daemon.reconnect-grace-seconds} for its daemon to dial
 * again and name it in a fresh {@code Hello}, which {@link #admitByToken} admits like the first
 * (same token subject, no live connection). The re-admission is answered an {@code Ack}, then the
 * {@link RunStep} again if one was sent — a daemon ignores a second one — then a {@link Cancel}
 * that could not be delivered while nothing was bound. A replayed {@link StepChunk} whose {@code seq}
 * the host already has is dropped. Only a grace that runs out with nobody back completes the awaits
 * {@code CONNECTION_LOST}, exactly as a close used to at once; a {@link #reap} during it completes
 * them at once, and the grace never extends a deadline — the awaits keep theirs throughout. A
 * daemon that does not re-dial (one older than the re-dial) costs its step the grace and no more.
 * {@code 0} restores the old behaviour.
 *
 * <p><b>A drop the host never hears about is found by silence</b> (qits-1728). The edge splices the
 * daemon's leg and this host's into one tunnel, so a daemon leg that dies with no FIN or RST
 * leaves this side's socket open — for the edge's idle timeout, an hour — and {@link #onClose}
 * never fires. Two things close that gap. Every frame the bound connection delivers, a {@code
 * Heartbeat} included, stamps the launch, and a connection silent for {@code
 * qits.ci.daemon.silence-limit-seconds} is closed and handed to {@link #onClose}, so the grace above
 * starts. And the daemon, which saw its own socket close, re-dials at once: a same-token dial for a
 * launch whose connection still reads open <b>supersedes</b> that connection rather than being
 * refused, because nothing else can make it — see {@link #admitByToken}. A step's results that
 * still arrive on the superseded connection are kept: the daemon resends only a frame whose write
 * failed, so a late {@code StepFinished} there may be the only copy there is ({@link #onMessage}).
 *
 * <p>{@code targethost/LeaseStepRunner} is what drives this in production, one step at a time.
 */
@ApplicationScoped
public class CiDaemonRegistry {

  private static final Logger LOG = Logger.getLogger(CiDaemonRegistry.class);

  /** The close code every rejected dial gets: 1008, "policy violation". */
  public static final int CLOSE_UNAUTHORIZED = 1008;

  /**
   * The close code a connection the host gave up on gets — superseded by its daemon's re-dial, or
   * silent past {@link #silenceLimit()}: 1001, "going away". Not a refusal, so not 1008.
   */
  public static final int CLOSE_GOING_AWAY = 1001;

  /** The reason a connection replaced by its own daemon's re-dial is closed with (qits-1728). */
  public static final String SUPERSEDED = "SUPERSEDED";

  /** The reason a connection that went silent past {@link #silenceLimit()} is closed with. */
  public static final String SILENT = "SILENT";

  /** How long one frame may take to leave. See {@link #send}. */
  private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

  /**
   * How long a close handshake may take before the host stops caring.
   *
   * <p>Much shorter than {@link #SEND_TIMEOUT} on purpose. A send is trying to deliver something the
   * run needs; a close is trying to be polite to a peer the host has already finished with, and the
   * container is about to be {@code docker rm -f}'d either way. The one moment this fires is the one
   * that matters most: the step-timeout backstop closes a socket whose peer is <em>by definition</em>
   * not answering, and the {@code docker rm -f} that actually removes the container is the next
   * statement after it.
   */
  static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  @Inject CiDaemonMessageCodec codec;

  private final ConcurrentHashMap<String, Launch> launches = new ConcurrentHashMap<>();

  /**
   * Ends the graces nobody came back inside. One thread: an expiry is a handful of future
   * completions, never a wait.
   */
  private final ScheduledExecutorService graces =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "ci-daemon-grace");
            t.setDaemon(true);
            return t;
          });

  /** How long a launch whose socket dropped waits for its daemon to dial again. 0 is no grace. */
  @ConfigProperty(name = "qits.ci.daemon.reconnect-grace-seconds")
  long reconnectGraceSeconds;

  /** Set by a suite only; otherwise {@link #reconnectGraceSeconds}. */
  private volatile Duration reconnectGrace;

  /**
   * Package-private for one reason: a suite proving a grace runs out cannot wait a minute. A method
   * rather than a field write because this bean is normal-scoped. {@code null} puts the configured value back.
   */
  void reconnectGrace(Duration grace) {
    this.reconnectGrace = grace;
  }

  /** How long a launch whose socket dropped waits for its daemon to dial again. */
  public Duration reconnectGrace() {
    Duration set = reconnectGrace;
    return set != null ? set : Duration.ofSeconds(reconnectGraceSeconds);
  }

  /**
   * How long a bound connection may deliver no frame at all before the host closes it (qits-1728).
   * The daemon heartbeats every 10s, so this is three missed beats. 0 turns the check off.
   */
  @ConfigProperty(name = "qits.ci.daemon.silence-limit-seconds")
  long silenceLimitSeconds;

  /** Set by a suite only; otherwise {@link #silenceLimitSeconds}. */
  private volatile Duration silenceLimit;

  /** {@link #reconnectGrace(Duration)}'s reason, for the silence limit. */
  void silenceLimit(Duration limit) {
    this.silenceLimit = limit;
  }

  /** How long a bound connection may be silent before it is treated as dropped. */
  public Duration silenceLimit() {
    Duration set = silenceLimit;
    return set != null ? set : Duration.ofSeconds(silenceLimitSeconds);
  }

  @PreDestroy
  void stopGraces() {
    graces.shutdownNow();
  }

  /**
   * Where a running step's output goes as it arrives — {@link CiStepRelay}, which is both the live
   * surface and the accumulator the persisted tail is read back out of.
   *
   * <p>Called on the socket's virtual thread, so it must not block for long — and a throw is
   * swallowed rather than allowed to close the connection, because a listener that fails must cost
   * its chunk and not the terminal frame behind it.
   */
  @FunctionalInterface
  public interface StepListener {
    void onChunk(Stream stream, long seq, String text);
  }

  /** How far along one launch is. Observational — the awaits are what callers actually use. */
  public enum Phase {
    /** Minted and its lease asked for; nothing has dialled. */
    LAUNCHED,
    /** A dial was admitted: its {@code Hello} named this launch and its token is the run's. */
    CONNECTED,
    /** The daemon reported its worktree ready, and no step is in flight — between two steps too. */
    INITIALIZED,
    /** A {@link RunStep} has been sent and its {@link StepFinished} has not arrived. */
    RUNNING,
    /** The daemon failed to initialize, or the launch was reaped. */
    DONE
  }

  /**
   * The outcome of awaiting the initialize transition. Four distinct states rather than a boolean,
   * because "the container never came up", "the daemon never got as far as a checkout", "the clone
   * failed", "the sha is gone" and "the socket dropped" are five different things a run must record
   * differently (finish-ci-feature.md §3, the transferred failure-state rule).
   */
  public record Initialization(Status status, InitFailed.Reason reason, String detail) {

    public enum Status {
      /** Clone and checkout done; the step's script is the reply to this. */
      INITIALIZED,
      /** The daemon reported a structured setup failure — {@link #reason} says which. */
      INIT_FAILED,
      /** It registered and then said nothing before the deadline. */
      NEVER_INITIALIZED,
      /** The socket closed before either. */
      CONNECTION_LOST
    }

    static Initialization ok() {
      return new Initialization(Status.INITIALIZED, null, null);
    }

    static Initialization failed(InitFailed message) {
      return new Initialization(Status.INIT_FAILED, message.reason(), message.detail());
    }

    static Initialization of(Status status) {
      return new Initialization(status, null, null);
    }
  }

  /** The outcome of awaiting the step's terminal frame. */
  public record Completion(Status status, int exitCode, boolean timedOut) {

    public enum Status {
      /** {@code StepFinished} arrived; {@link #exitCode} and {@link #timedOut} are the daemon's. */
      FINISHED,
      /** The host's backstop deadline expired with the socket still open. */
      NO_ANSWER,
      /** The socket closed before a terminal frame. */
      CONNECTION_LOST
    }

    static Completion finished(StepFinished message) {
      return new Completion(Status.FINISHED, message.exitCode(), message.timedOut());
    }

    static Completion of(Status status) {
      return new Completion(status, -1, false);
    }
  }

  /**
   * Why a dial was refused, or that it was not. There is no "already connected" refusal any more
   * (qits-1728): a dial with the launch's own token while its connection still reads open is its
   * daemon re-dialling past a drop this host has not seen, and is admitted.
   */
  public enum Admission {
    ADMITTED,
    /**
     * No launch record with that id — a stale daemon from before a restart, a stranger, or a first
     * frame that did not decode as a {@link Hello} at all, which is exactly as unidentifiable.
     */
    UNKNOWN_DAEMON,
    /**
     * The caller is not this launch's run's token: the subject it arrived as is not the one
     * recorded at {@link #registerLaunch(String, String)}.
     */
    WRONG_RUN
  }

  // --- the launch side (called by the lease's step runner) --------------------------------------

  /**
   * Who hears that a launch's daemon dialled in for the first time — {@code CiLeases}, which starts
   * the run's driver then rather than waiting for qits-system to say the lease is running. Fired on
   * the socket's virtual thread, after the admission; an observer must not block for long.
   */
  public record DaemonDialled(String daemonId, String runId) {}

  @Inject Event<DaemonDialled> dialled;

  /**
   * Mint an id for one run's lease and record the launch, bound to the subject of its run's {@code
   * ci-run} token: the daemon dials through the edge with that token and is admitted only when the
   * {@code sub} it arrives as is this one. Called before the lease is asked for, so the record exists
   * before anything can dial against it.
   *
   * @return the launch's {@code daemonId}, which the lease's container is told as {@code
   *     $QITS_CI_DAEMON_ID} and names in its {@code Hello}
   */
  public String registerLaunch(String runId, String tokenSubject) {
    Objects.requireNonNull(tokenSubject, "a launch is bound to its run's token subject");
    String daemonId = UUID.randomUUID().toString();
    launches.put(daemonId, new Launch(daemonId, runId, tokenSubject));
    LOG.debugf("Minted ci-daemon %s for run %s", daemonId, runId);
    return daemonId;
  }

  /** A step a previous process had in flight on this launch: what {@link #reattachLaunch} resumes. */
  public record InFlightStep(
      int stepIndex, String script, int timeoutSeconds, Map<String, String> env) {}

  /**
   * Record a launch a <b>previous process</b> of this service made, so that its daemon — still
   * running in its lease, re-dialling through its reconnect budget — is admitted when it comes back
   * (qits-1192). The id, the run and the token subject are the ones that process recorded on the run
   * row; nothing is minted. The launch counts as admitted once already, so the daemon's dial is a
   * reconnect, and it waits {@link #reconnectGrace()} for it from now, as a dropped socket does.
   *
   * <p>{@code inFlight} is the step the daemon was sent, when one was in flight: it is sent again
   * after the re-admission's {@code Ack} (a daemon ignores a step index it already ran), and its
   * output goes to {@code listener}. Null when no step was in flight — before the first, or between
   * two: the daemon then waits for its next {@code RunStep}.
   */
  public void reattachLaunch(
      String daemonId,
      String runId,
      String tokenSubject,
      InFlightStep inFlight,
      StepListener listener) {
    Objects.requireNonNull(daemonId, "a re-attached launch names its daemon");
    Objects.requireNonNull(tokenSubject, "a launch is bound to its run's token subject");
    Launch launch = new Launch(daemonId, runId, tokenSubject);
    Duration grace = reconnectGrace();
    synchronized (launch) {
      launch.admissions = 1;
      // A launch that was running steps had initialized; one that had not is told so again by its
      // daemon, which resends an Initialized nobody read.
      if (inFlight != null) {
        launch.initialized.complete(Initialization.ok());
        launch.beginStep(inFlight.stepIndex(), listener);
        // The correlation id is the launch id plus the step: the old one died with the previous
        // process, and the step index is what the daemon matches a repeated RunStep by.
        launch.correlationId = daemonId + "-" + inFlight.stepIndex();
        launch.runStep =
            new RunStep(
                launch.correlationId,
                inFlight.stepIndex(),
                inFlight.script(),
                inFlight.timeoutSeconds(),
                inFlight.env());
        launch.phase = Phase.RUNNING;
      }
      if (!grace.isZero() && !grace.isNegative()) {
        Object token = new Object();
        launch.expiryToken = token;
        launch.expiry =
            graces.schedule(() -> expire(launch, token), grace.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
    if (launches.putIfAbsent(daemonId, launch) != null) {
      synchronized (launch) {
        cancelExpiry(launch);
      }
      return;
    }
    LOG.infof(
        "Waiting %ss for ci-daemon %s of run %s, launched by the previous process, to come back%s",
        grace.toSeconds(),
        daemonId,
        runId,
        inFlight == null ? "" : " (step " + inFlight.stepIndex() + " in flight)");
  }

  /**
   * For a re-attached launch whose step was not started: block until the daemon says {@code
   * Initialized} (it is still owed its step), or the step's terminal frame arrives first (it had the
   * step after all), or the deadline expires. Null means the terminal frame came first.
   */
  public Initialization awaitInitializedUnlessFinished(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return Initialization.of(Initialization.Status.CONNECTION_LOST);
    }
    CompletableFuture<Completion> step = launch.stepFinished;
    await(CompletableFuture.anyOf(launch.initialized, step), timeout, null);
    Initialization initialized = launch.initialized.getNow(null);
    Completion finished = step.getNow(null);
    if (finished != null && finished.status() == Completion.Status.FINISHED) {
      return null;
    }
    return initialized != null
        ? initialized
        : Initialization.of(Initialization.Status.NEVER_INITIALIZED);
  }

  /**
   * Whether the step in flight on this launch has its terminal frame — the step ended, whatever the
   * host did. False with no step in flight.
   */
  public boolean finished(String daemonId) {
    Launch launch = launches.get(daemonId);
    Completion finished = launch == null ? null : launch.stepFinished.getNow(null);
    return finished != null && finished.status() == Completion.Status.FINISHED;
  }

  /**
   * Block until the container's daemon dials and is admitted. False means it never did
   * — the never-registered state, whose diagnosis is the bootstrap's own output and therefore a
   * {@code docker logs} tail captured <em>before</em> the container is reaped.
   */
  public boolean awaitRegistered(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    return launch != null && Boolean.TRUE.equals(await(launch.registered, timeout, Boolean.FALSE));
  }

  /**
   * Block until the daemon's {@link Hello} arrives, the launch is reaped, or the deadline expires.
   * Returns the announced capability version, or {@code null} when none arrived in time.
   *
   * <p>Registration ({@link #awaitRegistered}) completes at websocket <em>admission</em> — one round
   * trip before the daemon has said anything at all. A caller that needs to know what the daemon
   * announced (not just that it dialled) must wait on this instead of reading {@link
   * #capabilityVersionOf} straight after {@link #awaitRegistered}, which can observe the gap between
   * the two and see {@code null} for a daemon that is about to say Hello perfectly normally.
   */
  public Integer awaitHello(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return null;
    }
    int version = await(launch.helloReceived, timeout, -1);
    return version < 0 ? null : version;
  }

  /**
   * Block until the daemon's {@link AckReceived} confirms the host's {@link Ack} arrived, the
   * launch is reaped, or the deadline expires. This is the third leg of the round trip {@link
   * #awaitRegistered} and {@link #awaitHello} start: those two prove the daemon can reach the host
   * (it dialled, it said {@code Hello}); this is the only one that proves the reverse, host→daemon
   * delivery — which is what a real run actually depends on, since {@code Ack} and every frame after
   * it (not least {@code RunStep}) travel that direction.
   *
   * <p><b>Nothing calls this today, and it is kept deliberately.</b> {@code LeaseStepRunner}
   * moves straight from {@link #awaitRegistered} to {@link #awaitInitialized}, so a real container's
   * {@link AckReceived} is recorded here and never awaited. Its one caller was the pin ladder's
   * container probe, whose whole job was to prove the host→daemon round trip before a version was
   * pinned — the ladder is retired and the probe with it. The method stays because the property it
   * measures is the only one {@link #awaitRegistered} and {@link #awaitHello} cannot: those prove
   * the daemon reached the host, and this proves the host reaches the daemon, which is what every
   * frame after the handshake actually depends on.
   */
  public boolean awaitAckConfirmed(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    return launch != null && Boolean.TRUE.equals(await(launch.ackConfirmed, timeout, Boolean.FALSE));
  }

  /** Block until the daemon reports its checkout done, fails it, drops, or the deadline expires. */
  public Initialization awaitInitialized(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return Initialization.of(Initialization.Status.CONNECTION_LOST);
    }
    return await(
        launch.initialized, timeout, Initialization.of(Initialization.Status.NEVER_INITIALIZED));
  }

  /**
   * Send one step of the run. Its output goes to {@code listener} from here on, its terminal frame
   * is what {@link #awaitFinished} waits for, and it names {@code stepIndex}, which the daemon echoes
   * on that frame and matches a repeated {@code RunStep} by. {@code env} is the step's own
   * environment, laid over the lease's for that step only. Returns the correlation id.
   *
   * <p>A launch whose daemon is lost — its reconnect grace ran out, or it was reaped — answers the
   * step lost at once rather than leaving it to its backstop.
   */
  public String sendRunStep(
      String daemonId,
      int stepIndex,
      String script,
      int timeoutSeconds,
      Map<String, String> env,
      StepListener listener) {
    Launch launch = require(daemonId);
    String correlationId = UUID.randomUUID().toString();
    RunStep runStep = new RunStep(correlationId, stepIndex, script, timeoutSeconds, env);
    synchronized (launch) {
      launch.beginStep(stepIndex, listener);
      launch.correlationId = correlationId;
      // Recorded BEFORE the send reads the connection: a re-admission that binds a socket after this
      // send found none is guaranteed to see it and deliver it (see #resume).
      launch.runStep = runStep;
      launch.phase = Phase.RUNNING;
      if (launch.lost) {
        launch.stepFinished.complete(Completion.of(Completion.Status.CONNECTION_LOST));
        return correlationId;
      }
    }
    send(launch, runStep);
    return correlationId;
  }

  /**
   * Tell the daemon the run is over: it kills a step still running, removes the run's worktree and
   * exits 0. There is no answer frame; the socket closing is the answer, and the lease is ended
   * after this. Best effort: a daemon with no live socket is not owed it — ending the lease removes
   * its container either way.
   */
  public void sendClose(String daemonId) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return;
    }
    send(launch, new Close());
  }

  /**
   * Block until the step in flight has its terminal frame, the socket is lost, or the host's backstop
   * deadline passes.
   */
  public Completion awaitFinished(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return Completion.of(Completion.Status.CONNECTION_LOST);
    }
    return await(launch.stepFinished, timeout, Completion.of(Completion.Status.NO_ANSWER));
  }

  /**
   * Ask the daemon to kill its child. It answers with {@link StepFinished}, so the in-flight {@link
   * #awaitFinished} completes normally instead of timing out on a socket the host then has to reap.
   * When nothing is connected — the daemon's socket is inside its reconnect grace — the {@code
   * Cancel} is kept and delivered on the re-admission; a launch nobody comes back for is reaped by
   * the caller either way.
   */
  public void cancel(String daemonId) {
    Launch launch = launches.get(daemonId);
    if (launch == null || launch.correlationId == null || launch.phase != Phase.RUNNING) {
      // No step in flight: a Cancel would reach a daemon with nothing to kill, and one before the
      // first step makes it exit. The run's close is what ends the lease.
      return;
    }
    launch.cancelPending = true;
    if (send(launch, new Cancel(launch.correlationId))) {
      launch.cancelPending = false;
    }
  }

  /**
   * Forget a launch: close its socket and complete anything still pending so no await can outlive
   * the record. Called on every teardown path — after this a dial naming the launch is closed 1008
   * like any stranger's.
   *
   * <p>The close is <b>bounded</b>, for the reason the whole package is: this runs on the run
   * worker, and the caller's very next statement is the {@code docker rm -f} that removes the
   * container. A close that waited on an unresponsive peer would wedge all of CI <em>and</em> leak
   * the container it was being polite to. See {@link #closeBounded}.
   */
  public void reap(String daemonId) {
    Launch launch = launches.remove(daemonId);
    if (launch == null) {
      return;
    }
    launch.phase = Phase.DONE;
    synchronized (launch) {
      cancelExpiry(launch);
      cancelWatch(launch);
    }
    launch.registered.complete(Boolean.FALSE);
    completeLost(launch);
    WebSocketConnection connection = launch.connection;
    if (connection != null && connection.isOpen()) {
      closeBounded(connection, null, "reaped ci-daemon " + daemonId);
    }
  }

  /**
   * Close a connection with a deadline on it, and treat a missed deadline as "not confirmed, carry
   * on" rather than as something to keep waiting for.
   *
   * <p><b>Why this is not {@code closeAndAwait}.</b> That convenience is a default method spelled
   * {@code close().await().indefinitely()} — precisely the shape this package forbids, hidden one
   * frame deeper than the source grep in {@code CiDaemonRegistryTimeoutTest} could see. The peer here
   * is a container running repo-controlled code, and the one path that reaches this most reliably is
   * the step-timeout backstop, whose whole meaning is that the peer has stopped answering. Nothing
   * downstream needs the close to have completed: the connection is already off the launch table and
   * the container is about to be removed, so the close is a courtesy and a courtesy gets a deadline.
   *
   * <p>Shared with {@link CiDaemonSocket}, which refuses dials with it, so there is one bounded close
   * in the package rather than two spellings that can drift.
   */
  static void closeBounded(WebSocketConnection connection, CloseReason reason, String what) {
    try {
      if (reason == null) {
        connection.close().await().atMost(CLOSE_TIMEOUT);
      } else {
        connection.close(reason).await().atMost(CLOSE_TIMEOUT);
      }
    } catch (RuntimeException e) {
      // Includes the deadline expiring. The socket is abandoned either way; the peer's opinion of
      // the handshake stops being this host's problem here.
      LOG.debugf("Closing the socket of %s did not complete: %s", what, e.getMessage());
    }
  }

  /** Observational: how far a launch got, or null once it is reaped. */
  public Phase phaseOf(String daemonId) {
    Launch launch = launches.get(daemonId);
    return launch == null ? null : launch.phase;
  }

  /**
   * The version the {@link Ack} answering a {@link Hello} carries: the daemon's own, when this host
   * speaks it too — a capability-2 daemon from a lease asked before this host was deployed exits on
   * an Ack version it does not know, and must keep running its run — and this host's otherwise. Only
   * a launch whose daemon announced capability 3 or more is ever held for a release (epic qits-732).
   */
  static int ackVersion(int announced) {
    return announced >= MIN_ACK_VERSION && announced <= CiDaemonProtocol.CAPABILITY_VERSION
        ? announced
        : CiDaemonProtocol.CAPABILITY_VERSION;
  }

  /** The oldest capability this host still answers in kind: the lease protocol's (epic qits-1481). */
  static final int MIN_ACK_VERSION = 2;

  /**
   * Whether the launch is lost: its daemon's socket went away and did not come back within {@link
   * #reconnectGrace()}, or it was reaped. True for a launch that is unknown here, too — there is
   * nothing to reach. What a held slot asks of its daemon (epic qits-732).
   */
  public boolean lost(String daemonId) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return true;
    }
    synchronized (launch) {
      return launch.lost || launch.phase == Phase.DONE;
    }
  }

  /**
   * The capability version this launch's {@link Hello} announced, or {@code null} when none has
   * arrived yet (or the launch is unknown, or already reaped). Observational only, and racy for a
   * caller that needs to know what the daemon announced: reading this straight after {@link
   * #awaitRegistered} returns can still see {@code null} for a daemon whose {@link Hello} has not
   * arrived yet, because registration completes at websocket admission, one round trip earlier. A
   * caller that must not see that gap blocks on {@link #awaitHello} instead. That was the fix for
   * exactly this race in the pin ladder's container probe, proven in production: it read this method
   * right after {@link #awaitRegistered} and rejected every genuine daemon. The probe is deleted
   * with the ladder, and the race is not — it is a property of when registration completes, so the
   * warning stays for the next caller that reaches for the cheap read.
   */
  public Integer capabilityVersionOf(String daemonId) {
    Launch launch = launches.get(daemonId);
    return launch == null || launch.capabilityVersion < 0 ? null : launch.capabilityVersion;
  }

  /**
   * Observational: whether the launch has a live connection bound right now — false inside its
   * reconnect grace, and for a launch that is unknown or reaped.
   */
  public boolean connected(String daemonId) {
    Launch launch = launches.get(daemonId);
    WebSocketConnection connection = launch == null ? null : launch.connection;
    return connection != null && connection.isOpen();
  }

  /** Observational: how many launches are on the books. Zero after a clean run. */
  public int size() {
    return launches.size();
  }

  // --- the socket side --------------------------------------------------------------------------

  /**
   * Admit a connection once its {@link Hello} has named the launch it is. {@code runSubject} is the
   * {@code sub} the caller arrived as — its {@code ci-run} token's — and empty when it arrived as
   * none. No secret is checked: a {@code ci-run} token is worth exactly one run, so what remains to
   * establish is only which of that run's launches this connection is, and a launch id is not a
   * secret.
   *
   * <p><b>The subject binds the socket to ITS run.</b> The edge admits the token to {@code
   * /ci/daemon} for any launch id at all, so a caller that merely knew another launch's id could
   * otherwise speak for that launch by naming it — the mismatch is {@link Admission#WRONG_RUN},
   * logged here with both subjects, before the frame that named it is processed any further.
   *
   * <p><b>A dial while the bound connection still reads open SUPERSEDES it</b> (qits-1728). It used
   * to be refused {@code ALREADY_CONNECTED}, as a second party claiming the launch — but {@link
   * Admission#WRONG_RUN} is checked first and the run's token lives only in that run's lease
   * container, so a dial that gets this far can only be the launch's own daemon. And it dials again
   * only because it saw its socket close: a leg that died past the edge with no FIN or RST leaves
   * this side open for the edge's idle timeout, and refusing the daemon's re-dials there cost the
   * run once its reconnect budget ran out. So the old connection is unbound and closed {@link
   * #CLOSE_GOING_AWAY} {@link #SUPERSEDED}. Its late close starts no grace ({@link #onClose}
   * compares it to the bound one), while a step result still arriving on it is kept — see {@link
   * #onMessage}.
   *
   * <p>Either way — superseding, or after the bound one closed — a dial naming the launch is its
   * daemon <b>coming back</b> (qits-748): it is admitted the same way, a reconnect grace {@link
   * #onClose} started is cancelled, and the phase is left where it was — a re-admission is not a
   * fresh connection, and what it is owed is sent at its {@code Hello} ({@link #resume}). Atomic in
   * the launch, so two racing dials are admitted one after the other and the later one holds it.
   */
  public Admission admitByToken(String daemonId, String runSubject, WebSocketConnection connection) {
    if (daemonId == null) {
      return Admission.UNKNOWN_DAEMON;
    }
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return Admission.UNKNOWN_DAEMON;
    }
    WebSocketConnection superseded = null;
    synchronized (launch) {
      if (!launch.tokenSubject.equals(runSubject)) {
        LOG.warnf(
            "ci-daemon %s of run %s was dialled as subject '%s', and the run's ci-run token"
                + " is '%s' — refused WRONG_RUN",
            daemonId, launch.runId, runSubject, launch.tokenSubject);
        return Admission.WRONG_RUN;
      }
      WebSocketConnection bound = launch.connection;
      if (bound != null && bound.isOpen()) {
        LOG.warnf(
            "ci-daemon %s of run %s was re-dialled (connection %s) while connection %s still read"
                + " open; the old one is superseded (phase %s)",
            daemonId, launch.runId, connection.id(), bound.id(), launch.phase);
        superseded = bound;
      } else if (launch.admissions > 0) {
        LOG.infof(
            "ci-daemon %s of run %s came back (connection %s, phase %s)",
            daemonId, launch.runId, connection.id(), launch.phase);
      }
      cancelExpiry(launch);
      launch.admissions++;
      launch.connection = connection;
      watch(launch, connection);
      if (launch.phase == Phase.LAUNCHED) {
        launch.phase = Phase.CONNECTED;
      }
    }
    if (superseded != null) {
      closeInBackground(
          superseded,
          new CloseReason(CLOSE_GOING_AWAY, SUPERSEDED),
          "superseded ci-daemon connection " + superseded.id());
    }
    boolean first = launch.registered.complete(Boolean.TRUE);
    LOG.debugf(
        "ci-daemon %s registered for run %s (connection %s)", daemonId, launch.runId, connection.id());
    if (first && dialled != null) {
      try {
        dialled.fire(new DaemonDialled(daemonId, launch.runId));
      } catch (RuntimeException e) {
        LOG.debugf("A DaemonDialled observer failed for run %s: %s", launch.runId, e.getMessage());
      }
    }
    return Admission.ADMITTED;
  }

  /**
   * Handle one decoded frame. Returns false when the connection should be closed 1008 — today only
   * for a {@link Hello} whose {@code daemonId} disagrees with the connection the host already
   * authenticated. That field is a claim the host checks rather than an identity it accepts.
   *
   * <p><b>A connection superseded by its daemon's re-dial (qits-1728) still delivers results.</b>
   * The daemon re-holds only a frame whose write <em>failed</em>; one written to the old socket
   * before it noticed the drop is never sent again, and a repeated {@code RunStep} for a step it
   * already ran is ignored. So a {@link StepChunk}, {@link StepFinished}, {@link Initialized} or
   * {@link InitFailed} arriving on the superseded connection may be the only copy the host will ever
   * get, and is handled exactly as from the bound one — each is already safe twice: a chunk is
   * deduplicated by its {@code seq}, a {@code StepFinished} completes only the step in flight with
   * its index and only once, and a late {@code Initialized} never moves the phase back. Its {@code
   * Hello}, {@code Heartbeat} and {@code AckReceived} are ignored, since they speak for a
   * connection the launch no longer holds, and nothing from it counts towards the bound one's
   * liveness or ever closes the bound one.
   *
   * <p>Any frame from the bound connection, a {@code Heartbeat} included, is what keeps it inside
   * {@link #silenceLimit()}.
   */
  public boolean onMessage(
      String daemonId, WebSocketConnection connection, CiDaemonMessage message) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      LOG.debugf("Frame for reaped ci-daemon %s dropped: %s", daemonId, message.getClass());
      return true;
    }
    WebSocketConnection bound = launch.connection;
    if (bound != null && !bound.id().equals(connection.id())) {
      if (message instanceof Hello
          || message instanceof Heartbeat
          || message instanceof AckReceived) {
        LOG.debugf(
            "%s from superseded connection %s of ci-daemon %s ignored",
            message.getClass().getSimpleName(), connection.id(), daemonId);
        return true;
      }
      LOG.debugf(
          "%s from superseded connection %s of ci-daemon %s kept",
          message.getClass().getSimpleName(), connection.id(), daemonId);
    } else {
      launch.lastFrameNanos = System.nanoTime();
    }
    switch (message) {
      case Hello hello -> {
        if (!daemonId.equals(hello.daemonId())) {
          LOG.warnf(
              "ci-daemon %s said hello as '%s' — closing the connection",
              daemonId, hello.daemonId());
          return false;
        }
        launch.capabilityVersion = hello.capabilityVersion();
        launch.helloReceived.complete(hello.capabilityVersion());
        if (hello.capabilityVersion() != CiDaemonProtocol.CAPABILITY_VERSION) {
          // Logged, not refused: the daemon is the side that decides it cannot speak the Ack's
          // version (it exits nonzero, and its container log is the diagnosis).
          LOG.warnf(
              "ci-daemon %s announced capability %d, this host speaks %d",
              daemonId, hello.capabilityVersion(), CiDaemonProtocol.CAPABILITY_VERSION);
        }
        send(launch, new Ack(ackVersion(hello.capabilityVersion())));
        resume(launch);
      }
      case AckReceived ignored -> {
        // Proves host→daemon delivery. Real runs never await this (see #awaitAckConfirmed); it is
        // recorded unconditionally so a probe running concurrently with nothing else in this
        // switch statement still sees it.
        launch.ackConfirmed.complete(Boolean.TRUE);
      }
      case Heartbeat ignored -> {
        // Liveness only, and stamped above like every frame: an open socket is not the signal,
        // because one whose far leg died behind the edge stays open here (qits-1728).
      }
      case Initialized ignored -> {
        // Once per lease. A daemon that re-dials resends an Initialized it could not deliver; one
        // arriving after a step was sent must not move the phase back.
        synchronized (launch) {
          if (launch.phase == Phase.LAUNCHED || launch.phase == Phase.CONNECTED) {
            launch.phase = Phase.INITIALIZED;
          }
        }
        launch.initialized.complete(Initialization.ok());
      }
      case InitFailed failed -> {
        launch.phase = Phase.DONE;
        launch.initialized.complete(Initialization.failed(failed));
      }
      case StepChunk chunk -> relay(launch, chunk);
      case StepFinished finished -> {
        // It ends the step it names, not the lease. A StepFinished for any other step — a replay
        // of an earlier one after a reconnect — has nothing left to complete.
        synchronized (launch) {
          if (launch.phase == Phase.RUNNING && finished.stepIndex() == launch.stepIndex) {
            launch.phase = Phase.INITIALIZED;
            launch.stepFinished.complete(Completion.finished(finished));
          } else {
            LOG.debugf(
                "ci-daemon %s finished step %d, and step %d is the one in flight — ignored",
                daemonId, finished.stepIndex(), launch.stepIndex);
          }
        }
      }
      // Answers to a Continue (capability 3, epic qits-732). Nothing sends a Continue yet — the
      // adoption of a held slot is qits-1789's — so one arriving now answers nothing and is logged.
      case Continued continued ->
          LOG.infof(
              "ci-daemon %s of run %s said Continued for run %s; nothing here asked it to continue",
              daemonId, launch.runId, continued.runId());
      case ContinueFailed failed ->
          LOG.warnf(
              "ci-daemon %s of run %s said ContinueFailed for run %s (%s: %s); nothing here asked it"
                  + " to continue",
              daemonId, launch.runId, failed.runId(), failed.reason(), failed.detail());
      // Host → daemon messages are never received here; ignore defensively.
      case Continue ignored -> {}
      case Ack ignored -> {}
      case RunStep ignored -> {}
      case Cancel ignored -> {}
      case Close ignored -> {}
    }
    return true;
  }

  /**
   * A connection went away. The connection is unbound and <b>nothing is completed yet</b>: the
   * launch waits {@link #reconnectGrace()} for its daemon to dial again (qits-748), and only a grace
   * that runs out with nobody back completes whatever the worker is parked on as {@code
   * CONNECTION_LOST} — still well inside its deadline, so a lost socket stays a distinguishable
   * outcome rather than a slow one. A zero grace, and a launch already done, complete at once, as
   * every close used to. The launch record itself survives either way: the caller still has to reap
   * the container.
   *
   * <p>Only the <b>bound</b> connection's close counts. A connection a re-dial superseded closes
   * after its successor is bound (qits-1728), and that close starts no grace; nor does a second
   * report of a close already handled, which is what {@link #checkSilence} relies on.
   */
  public void onClose(String daemonId, WebSocketConnection connection) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return;
    }
    Duration grace = reconnectGrace();
    boolean now;
    synchronized (launch) {
      WebSocketConnection bound = launch.connection;
      if (bound == null || !bound.id().equals(connection.id())) {
        return;
      }
      launch.connection = null;
      cancelWatch(launch);
      now = grace.isZero() || grace.isNegative() || launch.phase == Phase.DONE;
      if (!now) {
        cancelExpiry(launch);
        Object token = new Object();
        launch.expiryToken = token;
        launch.expiry =
            graces.schedule(
                () -> expire(launch, token), grace.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
    if (now) {
      completeLost(launch);
      LOG.debugf("ci-daemon %s disconnected (connection %s)", daemonId, connection.id());
      return;
    }
    LOG.infof(
        "ci-daemon %s of run %s disconnected in phase %s; waiting %ss for it to come back",
        daemonId, launch.runId, launch.phase, grace.toSeconds());
  }

  /** The grace ran out: unless the daemon came back (or the launch was reaped) first, it is lost. */
  private void expire(Launch launch, Object token) {
    synchronized (launch) {
      if (launch.expiryToken != token || launch.connection != null) {
        return;
      }
      launch.expiry = null;
      launch.expiryToken = null;
    }
    if (launches.get(launch.daemonId) == launch) {
      LOG.warnf(
          "ci-daemon %s of run %s did not come back within %ss; its lease is lost",
          launch.daemonId, launch.runId, reconnectGrace().toSeconds());
    }
    completeLost(launch);
  }

  /** Called holding the launch's lock: a pending expiry, if any, will not fire. */
  private static void cancelExpiry(Launch launch) {
    ScheduledFuture<?> pending = launch.expiry;
    launch.expiry = null;
    launch.expiryToken = null;
    if (pending != null) {
      pending.cancel(false);
    }
  }

  /**
   * Start watching a just-bound connection for silence. Holding the launch's lock. The admission
   * counts as a frame, so the limit runs from here.
   */
  private void watch(Launch launch, WebSocketConnection connection) {
    cancelWatch(launch);
    launch.lastFrameNanos = System.nanoTime();
    Duration limit = silenceLimit();
    if (limit.isZero() || limit.isNegative()) {
      return;
    }
    Object token = new Object();
    launch.watchToken = token;
    launch.watch =
        graces.schedule(
            () -> checkSilence(launch, connection, token, limit),
            limit.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  /** Called holding the launch's lock: a pending silence check, if any, will not act. */
  private static void cancelWatch(Launch launch) {
    ScheduledFuture<?> pending = launch.watch;
    launch.watch = null;
    launch.watchToken = null;
    if (pending != null) {
      pending.cancel(false);
    }
  }

  /**
   * One silence check (qits-1728): a connection still bound that has delivered nothing for {@code
   * limit} is treated as the drop it almost certainly is — unbound through {@link #onClose}, so the
   * reconnect grace starts, and then closed. A daemon that is really gone is ended by the grace; one
   * that is not re-dials and is resumed. Otherwise the check is scheduled again for when the limit
   * would next run out. Runs on the grace thread, so the close itself goes to another one.
   */
  private void checkSilence(
      Launch launch, WebSocketConnection connection, Object token, Duration limit) {
    long silentNanos;
    synchronized (launch) {
      if (launch.watchToken != token || launch.connection != connection) {
        return;
      }
      silentNanos = System.nanoTime() - launch.lastFrameNanos;
      if (silentNanos < limit.toNanos()) {
        launch.watch =
            graces.schedule(
                () -> checkSilence(launch, connection, token, limit),
                limit.toNanos() - silentNanos,
                TimeUnit.NANOSECONDS);
        return;
      }
      launch.watch = null;
      launch.watchToken = null;
    }
    LOG.warnf(
        "ci-daemon %s of run %s sent nothing on connection %s for %dms (limit %ss); closing it as"
            + " dropped",
        launch.daemonId, launch.runId, connection.id(), silentNanos / 1_000_000, limit.toSeconds());
    onClose(launch.daemonId, connection);
    closeInBackground(
        connection,
        new CloseReason(CLOSE_GOING_AWAY, SILENT),
        "silent ci-daemon connection " + connection.id());
  }

  /**
   * {@link #closeBounded} on a virtual thread of its own. The connections closed this way are ones
   * the host has given up on, whose peer may well never answer the close: the bound still applies,
   * but neither a re-dial's admission nor the grace thread should wait it out.
   */
  private static void closeInBackground(
      WebSocketConnection connection, CloseReason reason, String what) {
    Thread.ofVirtual()
        .name("ci-daemon-close")
        .start(() -> closeBounded(connection, reason, what));
  }

  /**
   * Whatever the worker is parked on, completed as lost, and the launch marked lost so a step sent
   * after this is answered lost at once. A completed future is left as it is.
   */
  private static void completeLost(Launch launch) {
    synchronized (launch) {
      launch.lost = true;
    }
    launch.registered.complete(Boolean.FALSE);
    launch.helloReceived.complete(-1);
    launch.ackConfirmed.complete(Boolean.FALSE);
    launch.initialized.complete(Initialization.of(Initialization.Status.CONNECTION_LOST));
    launch.stepFinished.complete(Completion.of(Completion.Status.CONNECTION_LOST));
  }

  /**
   * What a daemon that came back is owed, sent right after its {@code Ack}: the {@link RunStep}
   * again when one was sent and the step has not ended — the frame may have died with the old socket,
   * and a daemon that already has it ignores a second — and then a {@link Cancel} that found no
   * socket to go out on. A first {@code Hello} is owed neither, so this is a no-op there.
   */
  private void resume(Launch launch) {
    if (launch.phase == Phase.DONE) {
      return;
    }
    RunStep runStep = launch.phase == Phase.RUNNING ? launch.runStep : null;
    if (runStep != null) {
      LOG.infof("Resending the step to ci-daemon %s after its reconnect", launch.daemonId);
      send(launch, runStep);
    }
    if (launch.cancelPending && launch.correlationId != null) {
      if (send(launch, new Cancel(launch.correlationId))) {
        launch.cancelPending = false;
      }
    }
  }

  // --- internals --------------------------------------------------------------------------------

  /**
   * The one place a future is waited on in this package, and it always carries a deadline. A
   * timeout, an interrupt and a failed future all yield {@code onFailure} — the caller's job is to
   * record a distinguishable outcome and reap, never to wait longer.
   */
  private static <T> T await(CompletableFuture<T> future, Duration timeout, T onFailure) {
    try {
      return future.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return onFailure;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return onFailure;
    } catch (ExecutionException e) {
      LOG.debugf("A ci-daemon await failed rather than timing out: %s", e.getCause());
      return onFailure;
    }
  }

  /**
   * Hand one chunk to the step's listener, asserting the per-correlation sequence on the way past. A
   * gap is logged and the chunk still delivered: {@code seq} exists so the host can tell "the step
   * printed nothing" from "we lost frames", not so it can drop output. A chunk at or below the
   * highest {@code seq} already relayed is a <b>duplicate</b> — a daemon that re-dialled replays the
   * chunks it could not confirm (qits-748) — and is dropped, so a reconnect never prints a line twice.
   */
  private void relay(Launch launch, StepChunk chunk) {
    synchronized (launch.seqLock) {
      if (launch.lastSeq >= 0 && chunk.seq() <= launch.lastSeq) {
        LOG.debugf(
            "ci-daemon %s chunk seq %d already relayed (have up to %d) — dropped as a replay",
            launch.daemonId, chunk.seq(), launch.lastSeq);
        return;
      }
      long expected = launch.lastSeq + 1;
      if (chunk.seq() != expected && launch.lastSeq >= 0) {
        LOG.warnf(
            "ci-daemon %s chunk seq %d, expected %d — output may be missing",
            launch.daemonId, chunk.seq(), expected);
      }
      launch.lastSeq = chunk.seq();
    }
    StepListener listener = launch.listener;
    if (listener == null) {
      return;
    }
    try {
      listener.onChunk(chunk.stream(), chunk.seq(), chunk.text());
    } catch (RuntimeException e) {
      LOG.debugf("ci-daemon %s chunk listener failed (dropped): %s", launch.daemonId, e.getMessage());
    }
  }

  /**
   * Write one frame, bounded. {@code sendTextAndAwait} would be the precedent's spelling and is
   * exactly {@code sendText(m).await().indefinitely()} — an untimed block on a socket whose peer is
   * a container running repo-controlled code. The same rule that forbids an untimed {@code get()}
   * here forbids that: a peer that stops draining its side must cost this send its deadline and no
   * more, never the run worker forever.
   *
   * <p>Answers whether the frame went out. With no live socket it is not sent here; the two frames
   * a reconnect must still deliver — the {@link RunStep} and a {@link Cancel} — are recorded on the
   * launch by their callers and sent again by {@link #resume}.
   */
  private boolean send(Launch launch, CiDaemonMessage message) {
    WebSocketConnection connection = launch.connection;
    if (connection == null || !connection.isOpen()) {
      LOG.debugf(
          "No live socket for ci-daemon %s — %s kept for its reconnect, if it is owed one",
          launch.daemonId, message.getClass().getSimpleName());
      return false;
    }
    try {
      connection.sendText(codec.encode(message)).await().atMost(SEND_TIMEOUT);
      return true;
    } catch (RuntimeException e) {
      LOG.warnf("Could not send %s to ci-daemon %s: %s", message.getClass().getSimpleName(),
          launch.daemonId, e.getMessage());
      return false;
    }
  }

  private Launch require(String daemonId) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      throw new IllegalStateException("No ci-daemon launch record for " + daemonId);
    }
    return launch;
  }

  /** One run's lease container: what it is bound to and the transitions it owes the host. */
  private static final class Launch {

    private final String daemonId;

    private final String runId;

    /** The subject of the run's ci-run token, which the daemon's connection must arrive as. */
    private final String tokenSubject;

    private volatile WebSocketConnection connection;
    private volatile Phase phase = Phase.LAUNCHED;

    /** The step in flight, or the last one: its index, its listener and its correlation id. */
    private volatile int stepIndex = -1;
    private volatile StepListener listener;
    private volatile String correlationId;

    /** Guards {@link #lastSeq}: a re-dial can briefly overlap the old socket's last frames. */
    private final Object seqLock = new Object();
    /** One counter for the whole lease (capability 2): a replay is anything at or below it. */
    private long lastSeq = -1;

    /** How many connections have been admitted for this launch; above one is a reconnect. */
    private int admissions;

    /** The step in flight, once sent — what a daemon that comes back is sent again ({@link #resume}). */
    private volatile RunStep runStep;

    /** A {@link Cancel} that found no live socket, owed to the daemon when it comes back. */
    private volatile boolean cancelPending;

    /** Set once the launch is lost — its grace ran out, or it was reaped; guarded by the launch. */
    private boolean lost;

    /** The reconnect grace's end, while the launch has no connection; guarded by the launch. */
    private ScheduledFuture<?> expiry;

    /** Which scheduled expiry is the live one — a cancelled one that fires anyway is ignored. */
    private Object expiryToken;

    /** When the bound connection last delivered a frame, or was admitted ({@link System#nanoTime}). */
    private volatile long lastFrameNanos;

    /** The bound connection's next silence check; guarded by the launch. */
    private ScheduledFuture<?> watch;

    /** Which scheduled silence check is the live one; guarded by the launch. */
    private Object watchToken;
    /** -1 until a {@link Hello} arrives — see {@link #capabilityVersionOf(String)}. */
    private volatile int capabilityVersion = -1;

    private final CompletableFuture<Boolean> registered = new CompletableFuture<>();
    /** Completed by the {@link Hello} branch of {@link #onMessage} — see {@link #awaitHello}. */
    private final CompletableFuture<Integer> helloReceived = new CompletableFuture<>();
    /**
     * Completed by the {@link AckReceived} branch of {@link #onMessage} — see {@link
     * #awaitAckConfirmed}.
     */
    private final CompletableFuture<Boolean> ackConfirmed = new CompletableFuture<>();
    /** Once per lease: the worktree is ready, or could not be made. */
    private final CompletableFuture<Initialization> initialized = new CompletableFuture<>();
    /** The step in flight's terminal frame; replaced by every {@link #beginStep}. */
    private volatile CompletableFuture<Completion> stepFinished = new CompletableFuture<>();

    Launch(String daemonId, String runId, String tokenSubject) {
      this.daemonId = daemonId;
      this.runId = runId;
      this.tokenSubject = tokenSubject;
    }

    /** A new step in flight: its own terminal future, its own listener. Holding the launch. */
    void beginStep(int index, StepListener stepListener) {
      this.stepIndex = index;
      this.listener = stepListener;
      this.cancelPending = false;
      this.stepFinished = new CompletableFuture<>();
    }
  }
}
