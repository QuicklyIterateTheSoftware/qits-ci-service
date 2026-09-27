package eu.wohlben.qits.ci.runnerhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.ci.control.CiBacklogListener;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiRunnerPresence;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.RunnerCapabilities;
import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Backlog;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol.Field;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import eu.wohlben.qits.cirunner.protocol.Released;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * Every runner holding a socket to this process: one {@link Session} per runner id, the launches
 * and reaps it owes an answer for, and which run each session took. {@link CiRunnerSocket} owns the
 * WebSocket lifecycle and forwards frames here — {@code CiDaemonSocket}/{@code CiDaemonRegistry}'s
 * split, kept for the same reason.
 *
 * <p><b>A session is one connection, and a runner has at most one.</b> A second dial for the same
 * runner <em>replaces</em> the first, which is closed 1008 {@link #ALREADY_CONNECTED}: unlike a
 * step's daemon, a runner reconnects forever by design, and the likeliest second dial is the same
 * runner coming back before this host noticed its old socket was dead. Refusing the new one would
 * lock a runner out behind a half-open socket until TCP gave up on it. Everything the old session
 * was owed completes as {@link LaunchAnswer.Status#CONNECTION_LOST} at once — the runner reset its
 * own state when it lost that socket, so nothing launched there will ever be answered.
 *
 * <p><b>Runs are held by the session that took them, not by the runner.</b> {@link #hold} binds a
 * run to the session its {@code Take} went out on, and a run whose session is gone is gone with it:
 * the runner forgets every run it held when its socket drops, so a step launched for that run over
 * a <em>later</em> session would run on a runner that no longer counts it against a slot.
 *
 * <p><b>Nothing here waits without a deadline</b> — the daemon registry's rule, for the daemon
 * registry's reason: a run's driver parks on {@link #launch} and {@link #reap}. A frame is sent
 * bounded at {@link #SEND_TIMEOUT}, a close at {@link #CLOSE_TIMEOUT}, and every future is waited on
 * through {@link #await}. {@code CiRunnerRegistryTimeoutTest} greps this package for the untimed
 * shapes. The one send that is not waited on at all is {@link #broadcastBacklog}'s, which is a hint
 * pushed from whatever thread moved the queue — an accept must not wait thirty seconds on a runner
 * that stopped draining its socket.
 */
@ApplicationScoped
public class CiRunnerRegistry implements CiRunnerPresence, CiBacklogListener {

  private static final Logger LOG = Logger.getLogger(CiRunnerRegistry.class);

  /** The close code every refusal and every replaced session gets: 1008, "policy violation". */
  public static final int CLOSE_POLICY = 1008;

  /** The close reason a session replaced by a newer dial of the same runner is given. */
  public static final String ALREADY_CONNECTED = "ALREADY_CONNECTED";

  /** How long one frame may take to leave — {@code CiDaemonRegistry}'s number. */
  static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

  /** How long a close may take before the host stops caring — the daemon registry's again. */
  static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  /**
   * How often a heartbeat may reach the database. A runner beats every ten seconds; {@code
   * last_seen_at} is read by a person on a listing, where a minute is the resolution that matters,
   * so five of every six heartbeats are answered by the open socket alone.
   */
  static final Duration SEEN_INTERVAL = Duration.ofMinutes(1);

  @Inject CiRunnerMessageCodec codec;

  @Inject CiRunners runners;

  /** For the queue's length, which a runner is told once after its {@code Ack}. */
  @Inject CiRunService runService;

  @Inject ObjectMapper objectMapper;

  private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, Session> heldRuns = new ConcurrentHashMap<>();

  private volatile Duration seenInterval = SEEN_INTERVAL;

  /**
   * Package-private for one reason: a suite proving that a heartbeat reaches the row cannot wait a
   * minute. A method rather than a field write because this bean is normal-scoped, and a field set
   * through the client proxy lands on the proxy.
   */
  void seenInterval(Duration interval) {
    this.seenInterval = interval;
  }

  /**
   * One runner's connection and everything it is owed. {@link #closed} completes exactly once, when
   * the session ends by any path — the one thing a run's driver watches to end a step {@code
   * CONNECTION_LOST} promptly instead of at a deadline.
   */
  public static final class Session {

    private final UUID runnerId;
    private final String runnerName;
    private final WebSocketConnection connection;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final ConcurrentHashMap<String, CompletableFuture<LaunchAnswer>> launches =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> reaps =
        new ConcurrentHashMap<>();
    private volatile boolean greeted;
    private volatile Instant seenWrittenAt;

    Session(UUID runnerId, String runnerName, WebSocketConnection connection) {
      this.runnerId = runnerId;
      this.runnerName = runnerName;
      this.connection = connection;
    }

    public UUID runnerId() {
      return runnerId;
    }

    public String runnerName() {
      return runnerName;
    }

    /** Completes once, when this session ends — replaced, closed by either side, or refused. */
    public CompletableFuture<Void> closed() {
      return closed;
    }

    public boolean isOpen() {
      return !closed.isDone();
    }

    boolean owns(WebSocketConnection other) {
      return connection.id().equals(other.id());
    }
  }

  /** What a runner answered to one {@link Launch}, or why it did not. */
  public record LaunchAnswer(Status status, String detail) {

    public enum Status {
      /** {@code docker run} answered with a container id. */
      LAUNCHED,
      /** The runner could not start the container; {@link #detail} is its words. */
      LAUNCH_FAILED,
      /** No answer inside the deadline, on a socket that stayed open. */
      NO_ANSWER,
      /** The session ended before an answer. */
      CONNECTION_LOST,
      /** The run was cancelled while the launch was outstanding. */
      WITHDRAWN
    }

    static LaunchAnswer of(Status status) {
      return new LaunchAnswer(status, null);
    }
  }

  /** How a {@code Hello} was received. */
  public enum Greeting {
    GREETED,
    /** The runner speaks another capability version; the socket is closed 1008. */
    VERSION_MISMATCH,
    /** The row went away between the dial and the {@code Hello}. */
    RUNNER_GONE
  }

  // --- the socket side --------------------------------------------------------------------------

  /**
   * Bind a connection to its runner, replacing (and closing) any session that runner already had.
   * The runner row was resolved from the bearer by the caller; nothing about identity is read here.
   */
  public Session admit(CiRunner runner, WebSocketConnection connection) {
    Session fresh = new Session(runner.id, runner.name, connection);
    Session previous = sessions.put(runner.id, fresh);
    if (previous != null) {
      LOG.warnf(
          "Runner %s (%s) dialled again on connection %s; closing its previous connection %s %s",
          runner.name, runner.id, connection.id(), previous.connection.id(), ALREADY_CONNECTED);
      lose(previous);
      closeBounded(
          previous.connection,
          new CloseReason(CLOSE_POLICY, ALREADY_CONNECTED),
          "the replaced connection of runner " + runner.name);
    }
    LOG.infof("Runner %s (%s) connected (connection %s)", runner.name, runner.id, connection.id());
    return fresh;
  }

  /**
   * The runner said who it is: check the protocol, record what it said, then answer {@code Ack}
   * with the row's slots and {@code Backlog} with the queue.
   *
   * <p><b>The slots are the row's, never the runner's.</b> {@link Hello#slots()} is what its
   * operator configured on the machine, and it is advisory — the row is what an admin edits, so the
   * cap the runner obeys arrives here. A disagreement is logged rather than corrected: which of the
   * two is wrong is a person's call.
   */
  public Greeting onHello(Session session, Hello hello) {
    if (hello.capabilityVersion() != CiRunnerProtocol.CAPABILITY_VERSION) {
      LOG.warnf(
          "Runner %s announced capability version %d and this host speaks %d — refusing it",
          session.runnerName, hello.capabilityVersion(), CiRunnerProtocol.CAPABILITY_VERSION);
      return Greeting.VERSION_MISMATCH;
    }
    CiRunner row = runners.recordHello(session.runnerId, capabilities(hello.capabilities()));
    if (row == null) {
      return Greeting.RUNNER_GONE;
    }
    session.seenWrittenAt = Instant.now();
    if (hello.slots() != row.slots) {
      LOG.infof(
          "Runner %s's machine is configured for %d slot(s) and its row grants %d; it is held to %d",
          row.name, hello.slots(), row.slots, row.slots);
    }
    LOG.infof(
        "Runner %s said hello: %s, capability %d, %d slot(s)",
        row.name, hello.runnerVersion(), hello.capabilityVersion(), row.slots);
    send(session, new Ack(CiRunnerProtocol.CAPABILITY_VERSION, row.slots));
    send(session, new Backlog(runService.queuedCount()));
    // Only now does a broadcast reach it: a Backlog before the Ack would be a frame the runner has
    // no slots to act on yet.
    session.greeted = true;
    return Greeting.GREETED;
  }

  /** Liveness: the socket is the signal, and the row hears about it at most once a minute. */
  public void onHeartbeat(Session session) {
    Instant now = Instant.now();
    Instant written = session.seenWrittenAt;
    if (written != null && written.plus(seenInterval).isAfter(now)) {
      return;
    }
    session.seenWrittenAt = now;
    try {
      runners.touchSeen(session.runnerId);
    } catch (RuntimeException e) {
      // A missed stamp costs a stale "last seen" on a listing, never the runner's socket.
      LOG.debugf("Could not stamp runner %s as seen: %s", session.runnerName, e.getMessage());
    }
  }

  public void onLaunched(Session session, Launched launched) {
    CompletableFuture<LaunchAnswer> pending =
        session.launches.remove(key(launched.runId(), launched.stepIndex()));
    if (pending == null) {
      LOG.debugf(
          "Runner %s reported run %s step %d launched, which nothing is waiting for",
          session.runnerName, launched.runId(), launched.stepIndex());
      return;
    }
    pending.complete(new LaunchAnswer(LaunchAnswer.Status.LAUNCHED, launched.containerId()));
  }

  public void onLaunchFailed(Session session, LaunchFailed failed) {
    CompletableFuture<LaunchAnswer> pending =
        session.launches.remove(key(failed.runId(), failed.stepIndex()));
    if (pending != null) {
      pending.complete(new LaunchAnswer(LaunchAnswer.Status.LAUNCH_FAILED, failed.detail()));
    }
  }

  public void onReaped(Session session, Reaped reaped) {
    CompletableFuture<Boolean> pending =
        session.reaps.remove(key(reaped.runId(), reaped.stepIndex()));
    if (pending != null) {
      pending.complete(Boolean.TRUE);
    }
  }

  /**
   * A connection went away. Only its own session is dropped — a replaced connection closing late
   * must not take the session that replaced it — and everything it was owed completes now.
   */
  public void onClose(Session session) {
    if (sessions.remove(session.runnerId, session)) {
      LOG.infof("Runner %s (%s) disconnected", session.runnerName, session.runnerId);
    }
    lose(session);
  }

  /** The session a connection was admitted as, when it is still the runner's current one. */
  public Session current(UUID runnerId) {
    return sessions.get(runnerId);
  }

  // --- the driver side --------------------------------------------------------------------------

  /** Bind a run to the session its {@code Take} went out on — see the class javadoc. */
  public void hold(Session session, String runId) {
    heldRuns.put(runId, session);
  }

  /** The session holding a run, or null when no session of this process does. */
  public Session holding(String runId) {
    return heldRuns.get(runId);
  }

  /** Whether any session of this process holds the run — a driver of this process owns it. */
  public boolean holds(String runId) {
    return heldRuns.containsKey(runId);
  }

  /**
   * The run is closed: forget it and tell the runner its slot is free. Idempotent — the second call
   * finds nothing held and sends nothing — and best effort: a session that is already gone took the
   * runner's memory of the run with it, so there is nobody left to tell.
   */
  public void release(String runId) {
    Session session = heldRuns.remove(runId);
    if (session == null) {
      return;
    }
    if (session.isOpen() && send(session, new Released(runId))) {
      LOG.debugf("Released run %s on runner %s", runId, session.runnerName);
    }
  }

  /**
   * Ask the runner for one step's container and wait for its answer. The future is registered
   * before the frame leaves, so an answer faster than this thread cannot be missed, and it is
   * completed by the answer, by the session's end, or by {@link #withdraw} — whichever is first.
   */
  public LaunchAnswer launch(Session session, Launch launch, Duration timeout) {
    String key = key(launch.runId(), launch.stepIndex());
    CompletableFuture<LaunchAnswer> pending = new CompletableFuture<>();
    session.launches.put(key, pending);
    if (!session.isOpen()) {
      // The session ended between the caller's look and the put; lose() may have run before the
      // future existed, so it is completed here rather than left to its deadline.
      pending.complete(LaunchAnswer.of(LaunchAnswer.Status.CONNECTION_LOST));
    } else if (!send(session, launch)) {
      pending.complete(LaunchAnswer.of(LaunchAnswer.Status.CONNECTION_LOST));
    }
    try {
      return await(pending, timeout, LaunchAnswer.of(LaunchAnswer.Status.NO_ANSWER));
    } finally {
      session.launches.remove(key, pending);
    }
  }

  /** Complete every outstanding launch of a run as {@link LaunchAnswer.Status#WITHDRAWN}. */
  public void withdraw(String runId) {
    Session session = heldRuns.get(runId);
    if (session == null) {
      return;
    }
    String prefix = runId + "/";
    session.launches.forEach(
        (key, pending) -> {
          if (key.startsWith(prefix)) {
            pending.complete(LaunchAnswer.of(LaunchAnswer.Status.WITHDRAWN));
          }
        });
  }

  /**
   * Ask the runner to remove one step's container and wait at most {@code timeout} for its {@code
   * Reaped}. The answer is only ever logged by the caller: a removal that did not land is the
   * runner's boot sweep's to retry, never a reason to hold a run open.
   */
  public boolean reap(Session session, Reap reap, Duration timeout) {
    if (!session.isOpen()) {
      return false;
    }
    String key = key(reap.runId(), reap.stepIndex());
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    session.reaps.put(key, pending);
    if (!session.isOpen() || !send(session, reap)) {
      pending.complete(Boolean.FALSE);
    }
    try {
      return Boolean.TRUE.equals(await(pending, timeout, Boolean.FALSE));
    } finally {
      session.reaps.remove(key, pending);
    }
  }

  /** Send one frame on a session, bounded. False when it could not leave. */
  public boolean send(Session session, CiRunnerMessage message) {
    WebSocketConnection connection = session.connection;
    if (!session.isOpen() || !connection.isOpen()) {
      LOG.debugf(
          "No live socket for runner %s — dropped %s",
          session.runnerName, message.getClass().getSimpleName());
      return false;
    }
    try {
      connection.sendText(codec.encode(message)).await().atMost(SEND_TIMEOUT);
      return true;
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not send %s to runner %s: %s",
          message.getClass().getSimpleName(), session.runnerName, e.getMessage());
      return false;
    }
  }

  /** {@link #send(Session, CiRunnerMessage)} to a runner's current session, if it has one. */
  public boolean send(UUID runnerId, CiRunnerMessage message) {
    Session session = sessions.get(runnerId);
    return session != null && send(session, message);
  }

  // --- the seams ----------------------------------------------------------------------------------

  @Override
  public boolean connected(UUID runnerId) {
    Session session = sessions.get(runnerId);
    return session != null && session.isOpen();
  }

  @Override
  public void backlogChanged(int queued) {
    broadcastBacklog(queued);
  }

  /**
   * Tell every greeted runner how long the queue is. <b>Not waited on</b>: this runs on whatever
   * thread moved the queue, and a runner that stopped draining its socket must cost its own frame,
   * never an accept's thread. A frame that fails is logged at debug; the next transition sends
   * another.
   */
  public void broadcastBacklog(int queued) {
    String frame = codec.encode(new Backlog(queued));
    for (Session session : sessions.values()) {
      if (!session.greeted || !session.isOpen() || !session.connection.isOpen()) {
        continue;
      }
      session
          .connection
          .sendText(frame)
          .subscribe()
          .with(
              sent -> {},
              failed ->
                  LOG.debugf(
                      "Backlog %d did not reach runner %s: %s",
                      queued, session.runnerName, failed.getMessage()));
    }
  }

  /** Observational: how many runners hold a session here. */
  public int size() {
    return sessions.size();
  }

  // --- internals --------------------------------------------------------------------------------

  /**
   * End a session's obligations: its {@link Session#closed} completes, and every launch and reap it
   * owed completes as lost, so no driver waits out a deadline on a socket that cannot answer.
   */
  private static void lose(Session session) {
    session.closed.complete(null);
    session.launches.values()
        .forEach(pending -> pending.complete(LaunchAnswer.of(LaunchAnswer.Status.CONNECTION_LOST)));
    session.reaps.values().forEach(pending -> pending.complete(Boolean.FALSE));
  }

  /**
   * What a runner said about its host, as the column stores it — the same JSON object shape the
   * register door stores, so the claim reads one shape whichever of the two wrote it last. Null for
   * a {@code Hello} that carried none, which leaves the registered answer standing.
   */
  private String capabilities(Capabilities capabilities) {
    if (capabilities == null) {
      return null;
    }
    ObjectNode node = objectMapper.createObjectNode();
    node.put(Field.DOCKER, capabilities.docker());
    node.put(Field.ARCH, capabilities.arch());
    node.put(Field.OS, capabilities.os());
    ObjectNode labels = node.putObject(Field.LABELS);
    for (Map.Entry<String, String> label : capabilities.labels().entrySet()) {
      labels.put(label.getKey(), label.getValue());
    }
    try {
      return RunnerCapabilities.encode(node);
    } catch (IllegalArgumentException tooLarge) {
      LOG.warnf("Runner capabilities not recorded: %s", tooLarge.getMessage());
      return null;
    }
  }

  private static String key(String runId, int stepIndex) {
    return runId + "/" + stepIndex;
  }

  /** The one place a future is waited on in this package, and it always carries a deadline. */
  static <T> T await(CompletableFuture<T> future, Duration timeout, T onFailure) {
    try {
      return future.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return onFailure;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return onFailure;
    } catch (ExecutionException e) {
      LOG.debugf("A runner await failed rather than timing out: %s", e.getCause());
      return onFailure;
    }
  }

  /** A close with a deadline on it — {@code CiDaemonRegistry.closeBounded}'s shape and reason. */
  static void closeBounded(WebSocketConnection connection, CloseReason reason, String what) {
    try {
      connection.close(reason).await().atMost(CLOSE_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.debugf("Closing the socket of %s did not complete: %s", what, e.getMessage());
    }
  }
}
