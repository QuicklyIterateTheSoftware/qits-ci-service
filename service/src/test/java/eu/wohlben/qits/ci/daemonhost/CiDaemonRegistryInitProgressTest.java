package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.Continued;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.InitProgress;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A worktree is waited out for as long as it moves</b> (qits-2539), at the registry: a daemon
 * that announced capability 4 reports {@link InitProgress} while it fetches its mirror, and the
 * init and {@code Continue} awaits fail only when no snapshot changes for the stall window or the
 * total cap passes — never at the flat budget a daemon below 4 still gets. On 2026-11-21 that flat
 * 120 s ended every lease of qits-landing-app on ws-proof thirteen minutes short of a mirror fetch
 * that then started again from zero.
 *
 * <p>Plain JUnit with a recording stand-in for the connection, {@link
 * CiDaemonRegistryReconnectTest}'s arrangement and reason, and every window in milliseconds: what is
 * under test is which bound ends the wait, not how long the shipped ones are.
 */
public class CiDaemonRegistryInitProgressTest {

  private static final String SUBJECT = "tok-ci-run-run-mirror-1";

  /** A hang fails the assertion that bounds it rather than the suite's timeout. */
  private static final long MUST_RETURN_WITHIN_MS = 5_000;

  private final CiDaemonMessageCodec codec = codec();

  private final CiDaemonRegistry registry = registry();

  private final List<Thread> feeders = new ArrayList<>();

  private static CiDaemonMessageCodec codec() {
    CiDaemonMessageCodec codec = new CiDaemonMessageCodec();
    codec.objectMapper = new ObjectMapper();
    return codec;
  }

  private CiDaemonRegistry registry() {
    CiDaemonRegistry registry = new CiDaemonRegistry();
    registry.codec = codec;
    registry.reconnectGrace(Duration.ofSeconds(30));
    return registry;
  }

  @AfterEach
  void stopFeeders() throws InterruptedException {
    for (Thread feeder : feeders) {
      feeder.interrupt();
      feeder.join(2_000);
    }
  }

  private static CiDaemonRegistry.InitDeadline deadline(long flatMs, long stallMs, long maxMs) {
    return new CiDaemonRegistry.InitDeadline(
        Duration.ofMillis(flatMs), Duration.ofMillis(stallMs), Duration.ofMillis(maxMs));
  }

  // --- the init await -----------------------------------------------------------------------------

  @Test
  public void aDaemonThatKeepsReportingChangedProgressOutlivesTheFlatBudgetAndInitializes()
      throws Exception {
    String daemonId = registry.registerLaunch("run-mirror", SUBJECT);
    Connection daemon = admit(daemonId, "c1", 4);
    feed(daemonId, daemon, 60, 15, bytes -> new InitProgress(InitProgress.Phase.MIRROR_FETCH, bytes))
        .thenSay(new Initialized());

    long began = System.nanoTime();
    CiDaemonRegistry.Initialization initialization =
        registry.awaitInitialized(daemonId, deadline(200, 400, 10_000));
    long tookMs = elapsedMs(began);

    assertEquals(CiDaemonRegistry.Initialization.Status.INITIALIZED, initialization.status());
    assertTrue(tookMs > 600, "it outlived the 200 ms flat budget while it moved: " + tookMs);
    assertNull(initialization.overrun());
    assertEquals(
        List.of(new Ack(4)),
        daemon.sent().stream().filter(Ack.class::isInstance).toList(),
        "a capability-4 daemon hears 4, which is what lets it report at all");
  }

  @Test
  public void aDaemonWhoseSnapshotsStopChangingFailsAfterTheStallWindowNamingTheLastOne()
      throws Exception {
    String daemonId = registry.registerLaunch("run-stalled", SUBJECT);
    Connection daemon = admit(daemonId, "c1", 4);
    InitProgress last = new InitProgress(InitProgress.Phase.MIRROR_FETCH, 37_958_042L);
    // One real change, then the same snapshot over and over: a daemon still there, a fetch that is
    // getting nowhere. Only a change is progress.
    feed(daemonId, daemon, 40, 1_000, bytes -> last);

    long began = System.nanoTime();
    CiDaemonRegistry.Initialization initialization =
        registry.awaitInitialized(daemonId, deadline(10_000, 300, 20_000));
    long tookMs = elapsedMs(began);

    assertEquals(CiDaemonRegistry.Initialization.Status.NEVER_INITIALIZED, initialization.status());
    CiDaemonRegistry.Overrun overrun = initialization.overrun();
    assertEquals(CiDaemonRegistry.Overrun.Kind.STALLED, overrun.kind());
    assertEquals(last, overrun.last());
    assertEquals(Duration.ofMillis(300), overrun.window());
    assertTrue(tookMs >= 300, "the stall window was waited out: " + tookMs);
    assertTrue(tookMs < MUST_RETURN_WITHIN_MS, "and not the flat 10 s budget: " + tookMs);
  }

  @Test
  public void aDaemonThatKeepsMovingIsStillEndedAtTheTotalCap() throws Exception {
    String daemonId = registry.registerLaunch("run-trickle", SUBJECT);
    Connection daemon = admit(daemonId, "c1", 4);
    feed(daemonId, daemon, 50, 1_000, bytes -> new InitProgress(InitProgress.Phase.MIRROR_CLONE, bytes));

    long began = System.nanoTime();
    CiDaemonRegistry.Initialization initialization =
        registry.awaitInitialized(daemonId, deadline(100, 300, 800));
    long tookMs = elapsedMs(began);

    assertEquals(CiDaemonRegistry.Initialization.Status.NEVER_INITIALIZED, initialization.status());
    CiDaemonRegistry.Overrun overrun = initialization.overrun();
    assertEquals(CiDaemonRegistry.Overrun.Kind.NOT_DONE, overrun.kind());
    assertEquals(Duration.ofMillis(800), overrun.window());
    assertEquals(InitProgress.Phase.MIRROR_CLONE, overrun.last().phase());
    assertTrue(tookMs >= 800, "the cap, not the stall window or the flat budget: " + tookMs);
    assertTrue(tookMs < MUST_RETURN_WITHIN_MS, tookMs + "ms");
  }

  @Test
  public void aCapabilityThreeDaemonKeepsTheFlatBudgetWhateverItSends() throws Exception {
    String daemonId = registry.registerLaunch("run-old-daemon", SUBJECT);
    Connection daemon = admit(daemonId, "c1", 3);
    // A capability-3 daemon is Acked 3 and never sends the frame; one arriving anyway changes
    // nothing about the budget it is judged by.
    feed(daemonId, daemon, 40, 1_000, bytes -> new InitProgress(InitProgress.Phase.MIRROR_FETCH, bytes));

    long began = System.nanoTime();
    CiDaemonRegistry.Initialization initialization =
        registry.awaitInitialized(daemonId, deadline(300, 10_000, 20_000));
    long tookMs = elapsedMs(began);

    assertEquals(CiDaemonRegistry.Initialization.Status.NEVER_INITIALIZED, initialization.status());
    assertNull(initialization.overrun(), "the flat budget decided, so nothing new describes it");
    assertTrue(tookMs >= 300 && tookMs < MUST_RETURN_WITHIN_MS, tookMs + "ms");
    assertEquals(
        List.of(new Ack(3)),
        daemon.sent().stream().filter(Ack.class::isInstance).toList(),
        "answered in kind, as the daemon in production now is");
  }

  @Test
  public void progressFromASupersededConnectionIsIgnored() throws Exception {
    String daemonId = registry.registerLaunch("run-superseded", SUBJECT);
    Connection first = admit(daemonId, "c1", 4);
    admit(daemonId, "c2", 4);
    first.awaitClosed();
    // The old socket keeps reporting a moving fetch; the launch's connection is the new one, which
    // says nothing. Like its Heartbeat, the old one's progress speaks for nobody.
    feed(daemonId, first, 40, 1_000, bytes -> new InitProgress(InitProgress.Phase.MIRROR_FETCH, bytes));

    long began = System.nanoTime();
    CiDaemonRegistry.Initialization initialization =
        registry.awaitInitialized(daemonId, deadline(10_000, 300, 20_000));
    long tookMs = elapsedMs(began);

    assertEquals(CiDaemonRegistry.Initialization.Status.NEVER_INITIALIZED, initialization.status());
    assertEquals(CiDaemonRegistry.Overrun.Kind.STALLED, initialization.overrun().kind());
    assertNull(initialization.overrun().last(), "nothing from the superseded connection was kept");
    assertTrue(tookMs < MUST_RETURN_WITHIN_MS, tookMs + "ms");
  }

  @Test
  public void aReattachedLaunchsInitAwaitFollowsProgressToo() throws Exception {
    String daemonId = "inherited-daemon";
    registry.reattachLaunch(daemonId, "run-inherited", SUBJECT, null, null);
    Connection daemon = admit(daemonId, "c1", 4);
    feed(daemonId, daemon, 60, 12, bytes -> new InitProgress(InitProgress.Phase.SUBMODULES, bytes))
        .thenSay(new Initialized());

    CiDaemonRegistry.Initialization initialization =
        registry.awaitInitializedUnlessFinished(daemonId, deadline(200, 400, 10_000));

    assertEquals(CiDaemonRegistry.Initialization.Status.INITIALIZED, initialization.status());
  }

  // --- the Continue await ---------------------------------------------------------------------

  @Test
  public void aContinuesRefetchIsWaitedOutWhileItMovesAndAnsweredContinued() throws Exception {
    String daemonId = registry.registerLaunch("run-qa", SUBJECT);
    Connection daemon = admit(daemonId, "c1", 4);
    registry.onMessage(daemonId, daemon.connection, new Initialized());
    assertTrue(registry.rekeyLaunch(daemonId, "run-release", "tok-ci-run-run-release-1"));
    feed(daemonId, daemon, 60, 15, bytes -> new InitProgress(InitProgress.Phase.MIRROR_FETCH, bytes))
        .thenSay(new Continued("run-release"));

    long began = System.nanoTime();
    CiDaemonRegistry.Continuation answer =
        registry.awaitContinued(daemonId, deadline(200, 400, 10_000));

    assertEquals(CiDaemonRegistry.Continuation.Status.CONTINUED, answer.status());
    assertTrue(elapsedMs(began) > 600, "past the flat budget");
  }

  @Test
  public void aSilentContinueIsNoAnswerAfterTheStallWindowAndTheSetupsProgressDoesNotCount() {
    String daemonId = registry.registerLaunch("run-qa", SUBJECT);
    Connection daemon = admit(daemonId, "c1", 4);
    registry.onMessage(
        daemonId, daemon.connection, new InitProgress(InitProgress.Phase.WORKTREE, 0));
    registry.onMessage(daemonId, daemon.connection, new Initialized());
    assertTrue(registry.rekeyLaunch(daemonId, "run-release", "tok-ci-run-run-release-1"));

    long began = System.nanoTime();
    CiDaemonRegistry.Continuation answer =
        registry.awaitContinued(daemonId, deadline(10_000, 300, 20_000));
    long tookMs = elapsedMs(began);

    assertEquals(CiDaemonRegistry.Continuation.Status.NO_ANSWER, answer.status());
    assertEquals(CiDaemonRegistry.Overrun.Kind.STALLED, answer.overrun().kind());
    assertNull(answer.overrun().last(), "the setup's last snapshot is the previous run's");
    assertTrue(tookMs >= 300 && tookMs < MUST_RETURN_WITHIN_MS, tookMs + "ms");
  }

  // --- the stand-in -------------------------------------------------------------------------------

  private static long elapsedMs(long began) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
  }

  /** Admit a connection for the launch and say its Hello on it, announcing {@code capability}. */
  private Connection admit(String daemonId, String id, int capability) {
    Connection connection = new Connection(id);
    assertEquals(
        CiDaemonRegistry.Admission.ADMITTED,
        registry.admitByToken(daemonId, SUBJECT, connection.connection));
    assertTrue(
        registry.onMessage(daemonId, connection.connection, new Hello(daemonId, capability)));
    return connection;
  }

  /** A daemon's sampler: {@code count} snapshots {@code everyMs} apart, the n-th from {@code n}. */
  private Feeder feed(
      String daemonId,
      Connection connection,
      long everyMs,
      int count,
      LongFunction<CiDaemonMessage> snapshot) {
    return new Feeder(daemonId, connection, everyMs, count, snapshot);
  }

  private final class Feeder {
    private final String daemonId;
    private final Connection connection;
    private final long everyMs;
    private final int count;
    private final LongFunction<CiDaemonMessage> snapshot;
    private volatile CiDaemonMessage then;

    Feeder(
        String daemonId,
        Connection connection,
        long everyMs,
        int count,
        LongFunction<CiDaemonMessage> snapshot) {
      this.daemonId = daemonId;
      this.connection = connection;
      this.everyMs = everyMs;
      this.count = count;
      this.snapshot = snapshot;
      Thread thread = new Thread(this::run, "init-progress-feeder");
      thread.setDaemon(true);
      feeders.add(thread);
      thread.start();
    }

    /** What the daemon says once its snapshots are done — its Initialized, its Continued. */
    void thenSay(CiDaemonMessage message) {
      then = message;
    }

    private void run() {
      try {
        for (int n = 1; n <= count; n++) {
          registry.onMessage(daemonId, connection.connection, snapshot.apply(n * 1024L));
          Thread.sleep(everyMs);
        }
        CiDaemonMessage last = then;
        if (last != null) {
          registry.onMessage(daemonId, connection.connection, last);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * A {@link WebSocketConnection} recording what is sent on it; only the methods the registry calls
   * answer.
   */
  private final class Connection {
    private final String id;
    private final List<String> texts = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean open = true;
    private final java.util.concurrent.CompletableFuture<Void> closed =
        new java.util.concurrent.CompletableFuture<>();
    final WebSocketConnection connection;

    Connection(String id) {
      this.id = id;
      this.connection =
          (WebSocketConnection)
              Proxy.newProxyInstance(
                  WebSocketConnection.class.getClassLoader(),
                  new Class<?>[] {WebSocketConnection.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "id" -> this.id;
                        case "isOpen" -> open;
                        case "isClosed" -> !open;
                        case "sendText" -> {
                          texts.add(String.valueOf(args[0]));
                          yield Uni.createFrom().voidItem();
                        }
                        case "close" -> {
                          open = false;
                          closed.complete(null);
                          yield Uni.createFrom().voidItem();
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Connection[" + this.id + "]";
                        default -> throw new UnsupportedOperationException(method.getName());
                      });
    }

    List<CiDaemonMessage> sent() {
      synchronized (texts) {
        return texts.stream().map(codec::decode).toList();
      }
    }

    void awaitClosed() throws Exception {
      closed.get(MUST_RETURN_WITHIN_MS, TimeUnit.MILLISECONDS);
    }
  }
}
