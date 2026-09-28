package eu.wohlben.qits.ci.runnerhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.ci.control.CiBacklogListener;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiRunnerPresence;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.control.RunnerAnnouncements;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.RunnerCapabilities;
import eu.wohlben.qits.ci.events.RunnerDisconnected;
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
import eu.wohlben.qits.cirunner.protocol.Retire;
import eu.wohlben.qits.cirunner.protocol.Upgrade;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * Every runner holding a socket to this process: one {@link Session} per connection, the launches
 * and reaps it owes an answer for, and which run each session took. {@link CiRunnerSocket} owns the
 * WebSocket lifecycle and forwards frames here — {@code CiDaemonSocket}/{@code CiDaemonRegistry}'s
 * split, kept for the same reason.
 *
 * <p><b>A session is one connection, and a runner has at most one per version.</b> A second
 * connection of the same runner that says {@code Hello} in the <em>same</em> version
 * <em>replaces</em> the first, which is closed 1008 {@link #ALREADY_CONNECTED}: unlike a step's
 * daemon, a runner reconnects forever by design, and the likeliest second dial is the same runner
 * coming back before this host noticed its old socket was dead. Refusing the new one would lock a
 * runner out behind a half-open socket until TCP gave up on it. Everything the old session was owed
 * completes as {@link LaunchAnswer.Status#CONNECTION_LOST} at once — the runner reset its own state
 * when it lost that socket, so nothing launched there will ever be answered. The decision waits for
 * the {@code Hello} because the version is only known there; a dial that never says hello replaces
 * nothing.
 *
 * <p><b>Two versions side by side are a self-update, and are allowed</b> (qits-465). A runner whose
 * {@link Hello#runnerVersion()} is not the pinned one ({@link CiRunnerPins}) — older or newer, the
 * pin is the authority — is sent {@link Upgrade} and its session is <b>draining</b>: {@code Ack}
 * grants it 0 slots and every {@code Reserve} is answered {@code Nothing}, while the runs it already
 * holds carry on to their end over it. The runner then starts its successor beside itself, which
 * dials as a second connection; once one of the pinned version has said {@code Hello} it gets the
 * row's slots and every other session of the runner is sent {@link Retire}. Nothing here closes the
 * retired one: the runner closes its own socket and exits, and its close is an ordinary {@link
 * #onClose}.
 *
 * <p><b>Runs are held by the session that took them, not by the runner.</b> {@link #hold} binds a
 * run to the session its {@code Take} went out on, and a run whose session is gone is gone with it:
 * the runner forgets every run it held when its socket drops, so a step launched for that run over
 * a <em>later</em> session would run on a runner that no longer counts it against a slot. That is
 * also what routes a self-update: every {@code Launch}, {@code Reap}, {@code Cancel} and {@code
 * Released} of a held run goes to {@link #holding}'s session — the draining one for the runs it took
 * before it was told to upgrade, the successor for every run after — and never to "the runner".
 *
 * <p><b>Nothing here waits without a deadline</b> — the daemon registry's rule, for the daemon
 * registry's reason: a run's driver parks on {@link #launch} and {@link #reap}. A frame is sent
 * bounded at {@link #SEND_TIMEOUT}, a close at {@link #CLOSE_TIMEOUT}, and every future is waited on
 * through {@link #await}. {@code CiRunnerRegistryTimeoutTest} greps this package for the untimed
 * shapes. The one send that is not waited on at all is {@link #broadcastBacklog}'s, which is a hint
 * pushed from whatever thread moved the queue — an accept must not wait thirty seconds on a runner
 * that stopped draining its socket.
 *
 * <p><b>This is where a runner's connection lifecycle is announced</b> — {@code RunnerConnected},
 * {@code RunnerDisconnected}, {@code RunnerUpdateStarted} and {@code RunnerUpdated}, through {@link
 * RunnerAnnouncements} — because this is where that state lives: in memory, changed by {@link
 * #onHello}, {@link #settle} and {@link #onClose}, with no row and no transaction behind it. Each is
 * announced at the moment the registry's own state changes, after the change and before the frames
 * that follow from it, so the events arrive in the order the lifecycle happened: {@code Connected}
 * (and {@code UpdateStarted}) for the old binary, then {@code Connected} and {@code Updated} for its
 * successor, then the old connection's {@code Disconnected} as {@code RETIRED}. An announcement never
 * waits on qits-events and never throws here, so none of it can cost a runner its {@code Ack}.
 *
 * <p><b>Why a connection ended is decided here, once</b>, and it is the one fact a close does not
 * carry: the registry records {@link RunnerDisconnected#RETIRED} when it sends {@code Retire}, {@link
 * RunnerDisconnected#REFUSED} when it refuses a {@code Hello}, and announces {@link
 * RunnerDisconnected#REPLACED} itself when {@link #settle} drops a same-version session; every other
 * end is {@link RunnerDisconnected#LOST}, and a stopping process announces {@link
 * RunnerDisconnected#SHUTDOWN} for every connection it still has. A session announces its end at
 * most once, whichever of those paths reaches it first, and only if it said {@code Hello}.
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

  /** The pinned runner version every {@code Hello} is compared with. */
  @Inject CiRunnerPins pins;

  /** Where the pinned runner image is pulled from, for {@link Upgrade#image()}. */
  @Inject RunnerAddresses addresses;

  /** The runner lifecycle event port; see the class javadoc. */
  @Inject RunnerAnnouncements announcements;

  /**
   * Every open session of each runner, oldest first. A list rather than one session because a
   * self-updating runner holds two for a while; mutated only inside {@link ConcurrentHashMap#compute}
   * on the runner's key, so the same-version rule and the retirement decide over a stable set.
   */
  private final ConcurrentHashMap<UUID, List<Session>> sessions = new ConcurrentHashMap<>();

  /** Admission order, so "the newest session" does not depend on a clock's resolution. */
  private final AtomicLong admitted = new AtomicLong();

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

    private final CiRunner runner;
    private final UUID runnerId;
    private final String runnerName;
    private final WebSocketConnection connection;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final ConcurrentHashMap<String, CompletableFuture<LaunchAnswer>> launches =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> reaps =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Runnable> onLoss = new ConcurrentHashMap<>();
    private final long order;
    private volatile boolean greeted;
    private volatile Instant seenWrittenAt;
    private volatile String runnerVersion;
    private volatile boolean draining;

    /** What this connection's {@code Hello} said it is — set even for a refused one. */
    private volatile String helloVersion;

    /**
     * Why this connection is ending, when the registry decided it — {@code RETIRED} or {@code
     * REFUSED}; null means nobody here ended it, which a close reads as {@code LOST}.
     */
    private volatile String endReason;

    /** Set by the one announcement of this connection's end, whichever path makes it. */
    private final AtomicBoolean endAnnounced = new AtomicBoolean();

    Session(CiRunner runner, WebSocketConnection connection, long order) {
      this.runner = runner;
      this.runnerId = runner.id;
      this.runnerName = runner.name;
      this.connection = connection;
      this.order = order;
    }

    /** The row this session was admitted as — detached, read once at the dial. */
    public CiRunner runner() {
      return runner;
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

    /** What the runner's {@code Hello} said it is; null until it has said one. */
    public String runnerVersion() {
      return runnerVersion;
    }

    /**
     * Whether this connection was told to {@link Upgrade}: it holds no slot, reserves nothing, and
     * only finishes the runs it already holds.
     */
    public boolean draining() {
      return draining;
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
    /**
     * The runner is the pinned version and speaks another capability version, so there is nothing
     * to update it to; the socket is closed 1008. Any other version is told to upgrade instead.
     */
    VERSION_MISMATCH,
    /** The row went away between the dial and the {@code Hello}. */
    RUNNER_GONE
  }

  // --- the socket side --------------------------------------------------------------------------

  /**
   * Bind a connection to its runner, beside any session that runner already has — which of them
   * stays is decided at its {@code Hello} ({@link #onHello}), where its version is known. The
   * runner row was resolved from the bearer by the caller; nothing about identity is read here.
   */
  public Session admit(CiRunner runner, WebSocketConnection connection) {
    Session fresh = new Session(runner, connection, admitted.incrementAndGet());
    sessions.compute(
        runner.id,
        (id, present) -> {
          List<Session> next = present == null ? new ArrayList<>() : new ArrayList<>(present);
          next.add(fresh);
          return List.copyOf(next);
        });
    LOG.infof("Runner %s (%s) connected (connection %s)", runner.name, runner.id, connection.id());
    return fresh;
  }

  /**
   * The runner said who it is: check its version against the pin, then the protocol, record what
   * it said, settle which of the runner's sessions stay, then answer {@code Ack} with the row's
   * slots and {@code Backlog} with the queue — or, for a runner that is not the pinned version,
   * {@link Upgrade} and an {@code Ack} of 0.
   *
   * <p><b>The version is checked before the capability</b>, because {@link Upgrade}, {@link Retire}
   * and {@code Hello.runnerVersion} are the frozen part of the wire: a runner of any older protocol
   * still reads them, so it is told what to become rather than closed and left to be updated by a
   * person at the machine. Only the pinned binary speaking another capability is refused — there
   * is nothing for it to update to. A mismatched runner that also speaks another capability gets
   * the {@code Upgrade} and no {@code Ack} at all: an {@code Ack} in a capability it does not know
   * is a frame it exits on, which would stop the update it was just sent.
   *
   * <p><b>The slots are the row's, never the runner's.</b> {@link Hello#slots()} is what its
   * operator configured on the machine, and it is advisory — the row is what an admin edits, so the
   * cap the runner obeys arrives here. A disagreement is logged rather than corrected: which of the
   * two is wrong is a person's call.
   */
  public Greeting onHello(Session session, Hello hello) {
    String pin = pins.version();
    boolean current = pin.equals(hello.runnerVersion());
    boolean speaks = hello.capabilityVersion() == CiRunnerProtocol.CAPABILITY_VERSION;
    session.helloVersion = hello.runnerVersion();
    if (current && !speaks) {
      LOG.warnf(
          "Runner %s announced capability version %d and this host speaks %d — refusing it",
          session.runnerName, hello.capabilityVersion(), CiRunnerProtocol.CAPABILITY_VERSION);
      session.endReason = RunnerDisconnected.REFUSED;
      return Greeting.VERSION_MISMATCH;
    }
    // Capabilities in a protocol this host does not speak are not read: the registered answer stands.
    CiRunner row =
        runners.recordHello(session.runnerId, speaks ? capabilities(hello.capabilities()) : null);
    if (row == null) {
      session.endReason = RunnerDisconnected.REFUSED;
      return Greeting.RUNNER_GONE;
    }
    session.seenWrittenAt = Instant.now();
    session.runnerVersion = hello.runnerVersion();
    session.draining = !current;
    List<Session> retiring = settle(session);
    announceConnected(session, hello, pin, current, speaks);
    if (!current) {
      String image = addresses.runnerImage(pin);
      LOG.infof(
          "Runner %s said hello as %s (capability %d) and the pin is %s — upgrading it to %s; this"
              + " connection drains",
          row.name, hello.runnerVersion(), hello.capabilityVersion(), pin, image);
      announcements.announce(
          "runner " + session.runnerName + "'s update",
          announcer ->
              announcer.onRunnerUpdateStarted(
                  session.runnerId.toString(),
                  session.runnerName,
                  hello.runnerVersion(),
                  pin,
                  heldBy(session),
                  Instant.now()));
      send(session, new Upgrade(pin, image, null));
      if (speaks) {
        send(session, new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 0));
      }
      return Greeting.GREETED;
    }
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
    if (!retiring.isEmpty()) {
      // Decided before the first Retire leaves, so the Updated is announced ahead of any close the
      // frame provokes, and a retired runner that closes at once still closes as RETIRED.
      for (Session old : retiring) {
        old.endReason = RunnerDisconnected.RETIRED;
      }
      String from = retiring.get(retiring.size() - 1).runnerVersion;
      announcements.announce(
          "runner " + session.runnerName + "'s rollover",
          announcer ->
              announcer.onRunnerUpdated(
                  session.runnerId.toString(), session.runnerName, from, pin, Instant.now()));
    }
    for (Session old : retiring) {
      LOG.infof(
          "Runner %s's %s connection %s is superseded by %s; retiring it",
          row.name, old.runnerVersion, old.connection.id(), pin);
      if (!send(old, new Retire("superseded by " + pin))) {
        // The frame never left: nothing retired this connection, its socket was already going.
        old.endReason = null;
      }
    }
    return Greeting.GREETED;
  }

  /**
   * The {@code Hello} was taken: {@code RunnerConnected}, with what this host read of the machine —
   * nothing, for a runner whose capability version it does not speak, exactly as {@link
   * #capabilities} records nothing for one.
   */
  private void announceConnected(
      Session session, Hello hello, String pin, boolean current, boolean speaks) {
    Capabilities host = speaks ? hello.capabilities() : null;
    announcements.announce(
        "runner " + session.runnerName + "'s connection",
        announcer ->
            announcer.onRunnerConnected(
                session.runnerId.toString(),
                session.runnerName,
                hello.runnerVersion(),
                pin,
                !current,
                host == null ? null : host.docker(),
                host == null ? null : host.arch(),
                host == null ? null : host.os(),
                Instant.now()));
  }

  /**
   * The same-version rule, applied once a session has said which version it is: every other open
   * session of the runner that said {@code Hello} in the same version is replaced — dropped here,
   * its obligations lost, its socket closed {@link #ALREADY_CONNECTED}. Returned are the sessions of
   * <em>other</em> versions when this one is the pinned version, which are the ones to {@link
   * Retire}; for a draining session nothing is. A session that has not said {@code Hello} yet is
   * left alone either way: its version is unknown, and it is decided at its own.
   */
  private List<Session> settle(Session session) {
    List<Session> replaced = new ArrayList<>();
    List<Session> retiring = new ArrayList<>();
    sessions.computeIfPresent(
        session.runnerId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>();
          for (Session other : present) {
            if (other == session || other.runnerVersion == null) {
              kept.add(other);
            } else if (other.runnerVersion.equals(session.runnerVersion)) {
              replaced.add(other);
            } else {
              kept.add(other);
              if (!session.draining) {
                retiring.add(other);
              }
            }
          }
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    for (Session previous : replaced) {
      LOG.warnf(
          "Runner %s (%s) said hello again as %s on connection %s; closing its previous connection"
              + " %s %s",
          session.runnerName,
          session.runnerId,
          session.runnerVersion,
          session.connection.id(),
          previous.connection.id(),
          ALREADY_CONNECTED);
      announceEnd(previous, RunnerDisconnected.REPLACED);
      lose(previous);
      closeBounded(
          previous.connection,
          new CloseReason(CLOSE_POLICY, ALREADY_CONNECTED),
          "the replaced connection of runner " + session.runnerName);
    }
    return retiring;
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
    boolean[] removed = {false};
    sessions.computeIfPresent(
        session.runnerId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>(present);
          removed[0] = kept.remove(session);
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    if (removed[0]) {
      LOG.infof(
          "Runner %s (%s) disconnected (connection %s)",
          session.runnerName, session.runnerId, session.connection.id());
      String decided = session.endReason;
      announceEnd(session, decided != null ? decided : RunnerDisconnected.LOST);
    }
    lose(session);
  }

  /**
   * This process is stopping: every connection that said {@code Hello} ends as {@code SHUTDOWN},
   * announced now rather than at whatever close the server does or does not deliver on its way down.
   * A close that does arrive afterwards finds the end already announced and adds nothing.
   */
  void onShutdown(@Observes ShutdownEvent stopping) {
    announceShutdown();
  }

  /** {@link #onShutdown}'s work, callable without stopping anything — a suite's handle on it. */
  void announceShutdown() {
    for (Session session : sessions.values().stream().flatMap(List::stream).toList()) {
      announceEnd(session, RunnerDisconnected.SHUTDOWN);
    }
  }

  /**
   * {@code RunnerDisconnected}, at most once per session and only for one that said {@code Hello} —
   * a dial that never did was never announced as connected, so its end is not announced either.
   * {@code heldRuns} is counted now, before the runs it held are completed as lost.
   */
  private void announceEnd(Session session, String reason) {
    if (session.helloVersion == null || !session.endAnnounced.compareAndSet(false, true)) {
      return;
    }
    int held = heldBy(session);
    announcements.announce(
        "runner " + session.runnerName + "'s disconnection",
        announcer ->
            announcer.onRunnerDisconnected(
                session.runnerId.toString(),
                session.runnerName,
                session.helloVersion,
                reason,
                held,
                Instant.now()));
  }

  /** How many runs a session holds — the runs whose driver is bound to it (see {@link #hold}). */
  private int heldBy(Session session) {
    return (int) heldRuns.values().stream().filter(held -> held == session).count();
  }

  /**
   * The runner's current session: the one that holds its slots — greeted in the pinned version —
   * when there is one, else its newest open session (a draining one, or one not greeted yet), else
   * null. Where a frame for the runner rather than for one of its runs goes.
   */
  public Session current(UUID runnerId) {
    List<Session> open = open(runnerId);
    return open.stream()
        .filter(s -> s.greeted && !s.draining)
        .findFirst()
        .orElseGet(() -> open.isEmpty() ? null : open.get(open.size() - 1));
  }

  /** Every open session of a runner, oldest first; empty when it has none. */
  List<Session> open(UUID runnerId) {
    List<Session> present = sessions.get(runnerId);
    if (present == null) {
      return List.of();
    }
    return present.stream()
        .filter(Session::isOpen)
        .sorted(Comparator.comparingLong(s -> s.order))
        .toList();
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

  /**
   * Run {@code action} if the session ends before the returned handle is closed — at once, on this
   * thread, when it already has. A step registers what must happen to it on a lost runner (its
   * daemon awaits completed as lost) and closes the handle when it ends, so a session that lives for
   * weeks does not accumulate a callback per step it ever ran.
   */
  public AutoCloseable onLoss(Session session, Runnable action) {
    Object key = new Object();
    session.onLoss.put(key, action);
    if (!session.isOpen() && session.onLoss.remove(key) != null) {
      action.run();
    }
    return () -> session.onLoss.remove(key);
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

  /** {@link #send(Session, CiRunnerMessage)} to a runner's {@link #current} session, if it has one. */
  public boolean send(UUID runnerId, CiRunnerMessage message) {
    Session session = current(runnerId);
    return session != null && send(session, message);
  }

  // --- the seams ----------------------------------------------------------------------------------

  /** Any open connection is a connected runner — a draining one is still running its runs. */
  @Override
  public boolean connected(UUID runnerId) {
    return !open(runnerId).isEmpty();
  }

  /**
   * What the runner runs and what it should: the {@link #current} session's version (null before
   * any {@code Hello}), the pin, and whether a draining connection is still open — an update in
   * flight, or a runner that never completes one.
   */
  @Override
  public Versions versions(UUID runnerId) {
    List<Session> open = open(runnerId);
    Session current = current(runnerId);
    String running = current == null ? null : current.runnerVersion;
    if (running == null) {
      // A newest session not greeted yet says nothing; the newest that did is the answer.
      running =
          open.stream()
              .map(s -> s.runnerVersion)
              .filter(Objects::nonNull)
              .reduce((older, newer) -> newer)
              .orElse(null);
    }
    boolean updating = open.stream().anyMatch(s -> s.draining);
    return new Versions(running, pins.version(), updating);
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
    for (Session session : sessions.values().stream().flatMap(List::stream).toList()) {
      // A draining session is never greeted: with 0 slots there is nothing for it to act on.
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

  /** Observational: how many runners hold at least one session here. */
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
    for (Object key : session.onLoss.keySet()) {
      Runnable action = session.onLoss.remove(key);
      if (action != null) {
        try {
          action.run();
        } catch (RuntimeException e) {
          LOG.debugf("A loss action of runner %s failed: %s", session.runnerName, e.getMessage());
        }
      }
    }
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
