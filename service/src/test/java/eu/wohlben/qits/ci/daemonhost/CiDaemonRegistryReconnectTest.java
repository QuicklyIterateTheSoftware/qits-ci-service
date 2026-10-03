package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.Cancel;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The daemon socket's reconnect grace (qits-748), at the registry: a dropped connection is a gap
 * the launch waits out for its daemon to come back, not the step's end.
 *
 * <p>Plain JUnit, {@link CiDaemonRegistryTimeoutTest}'s reason: the registry is driven through its
 * own socket-side methods with a recording stand-in for the connection, so nothing here races for a
 * port. The end-to-end re-dial over a real socket is {@code CiDaemonSocketTest}'s.
 */
public class CiDaemonRegistryReconnectTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  private static final String SUBJECT = "tok-ci-run-run-blip-1";

  private final CiDaemonMessageCodec codec = codec();

  private static CiDaemonMessageCodec codec() {
    CiDaemonMessageCodec codec = new CiDaemonMessageCodec();
    codec.objectMapper = new ObjectMapper();
    return codec;
  }

  private CiDaemonRegistry registry(Duration grace) {
    CiDaemonRegistry registry = new CiDaemonRegistry();
    registry.codec = codec;
    registry.reconnectGrace(grace);
    return registry;
  }

  // --- the grace ----------------------------------------------------------------------------------

  @Test
  public void aDaemonThatComesBackInsideTheGraceFinishesItsStepOnTheNewSocket() throws Exception {
    CiDaemonRegistry registry = registry(Duration.ofSeconds(30));
    String daemonId = registry.registerLaunch("run-blip", 0, SUBJECT, null);
    FakeConnection first = admit(registry, daemonId, "c1");
    registry.onMessage(daemonId, first.connection, new Initialized());
    assertEquals(
        CiDaemonRegistry.Initialization.Status.INITIALIZED,
        registry.awaitInitialized(daemonId, SOON).status());
    String correlationId = registry.sendRunStep(daemonId, "make", 600);
    assertInstanceOf(RunStep.class, first.sent().getLast());

    first.drop(registry, daemonId);

    CompletableFuture<CiDaemonRegistry.Completion> finished =
        CompletableFuture.supplyAsync(() -> registry.awaitFinished(daemonId, Duration.ofSeconds(30)));
    Thread.sleep(300);
    assertFalse(finished.isDone(), "a dropped socket inside the grace must not end the step");

    FakeConnection second = admit(registry, daemonId, "c2");
    // The re-admission is owed the step again: the frame may have died with the old socket.
    List<CiDaemonMessage> resent = second.sent();
    assertEquals(2, resent.size(), "an Ack, then the RunStep: " + resent);
    assertInstanceOf(Ack.class, resent.get(0));
    assertEquals(correlationId, assertInstanceOf(RunStep.class, resent.get(1)).correlationId());
    assertEquals(CiDaemonRegistry.Phase.RUNNING, registry.phaseOf(daemonId));

    // A daemon resends an Initialized it could not confirm; it must not move the phase back.
    registry.onMessage(daemonId, second.connection, new Initialized());
    assertEquals(CiDaemonRegistry.Phase.RUNNING, registry.phaseOf(daemonId));

    registry.onMessage(daemonId, second.connection, new StepFinished(correlationId, 3, false));
    CiDaemonRegistry.Completion completion = finished.get(SOON.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals(CiDaemonRegistry.Completion.Status.FINISHED, completion.status());
    assertEquals(3, completion.exitCode());
    registry.reap(daemonId);
  }

  @Test
  public void aDaemonThatDoesNotComeBackIsLostWhenTheGraceRunsOutAndNotAtTheClose() {
    Duration grace = Duration.ofMillis(600);
    CiDaemonRegistry registry = registry(grace);
    String daemonId = registry.registerLaunch("run-gone", 0, SUBJECT, null);
    FakeConnection first = admit(registry, daemonId, "c1");
    registry.onMessage(daemonId, first.connection, new Initialized());
    registry.sendRunStep(daemonId, "make", 600);

    long start = System.nanoTime();
    first.drop(registry, daemonId);
    CiDaemonRegistry.Completion completion =
        registry.awaitFinished(daemonId, Duration.ofSeconds(30));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertEquals(CiDaemonRegistry.Completion.Status.CONNECTION_LOST, completion.status());
    assertTrue(elapsedMs >= grace.toMillis() - 50, "lost at the close, not at the grace: " + elapsedMs);
    assertTrue(elapsedMs < 10_000, "the grace, not the step's deadline: " + elapsedMs);
    registry.reap(daemonId);
  }

  @Test
  public void aZeroGraceIsTheOldBehaviourLostAtTheClose() {
    CiDaemonRegistry registry = registry(Duration.ZERO);
    String daemonId = registry.registerLaunch("run-nograce", 0, SUBJECT, null);
    FakeConnection first = admit(registry, daemonId, "c1");
    registry.onMessage(daemonId, first.connection, new Initialized());
    registry.sendRunStep(daemonId, "make", 600);

    first.drop(registry, daemonId);

    long start = System.nanoTime();
    assertEquals(
        CiDaemonRegistry.Completion.Status.CONNECTION_LOST,
        registry.awaitFinished(daemonId, Duration.ofSeconds(30)).status());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000);
    registry.reap(daemonId);
  }

  @Test
  public void aReapDuringTheGraceEndsTheStepAtOnce() throws Exception {
    CiDaemonRegistry registry = registry(Duration.ofSeconds(60));
    String daemonId = registry.registerLaunch("run-reap", 0, SUBJECT, null);
    FakeConnection first = admit(registry, daemonId, "c1");
    registry.onMessage(daemonId, first.connection, new Initialized());
    registry.sendRunStep(daemonId, "make", 600);
    first.drop(registry, daemonId);

    CompletableFuture<CiDaemonRegistry.Completion> finished =
        CompletableFuture.supplyAsync(() -> registry.awaitFinished(daemonId, Duration.ofSeconds(30)));
    Thread.sleep(200);
    assertFalse(finished.isDone());

    long start = System.nanoTime();
    registry.reap(daemonId);
    assertEquals(
        CiDaemonRegistry.Completion.Status.CONNECTION_LOST,
        finished.get(SOON.toMillis(), TimeUnit.MILLISECONDS).status());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "a reap must not wait the grace");
  }

  // --- what a re-admission is owed, and what it must not repeat ----------------------------------

  @Test
  public void aRunStepSentWhileUnboundIsDeliveredOnTheReadmissionAndACancelAfterIt() {
    CiDaemonRegistry registry = registry(Duration.ofSeconds(30));
    String daemonId = registry.registerLaunch("run-unbound", 0, SUBJECT, null);
    FakeConnection first = admit(registry, daemonId, "c1");
    registry.onMessage(daemonId, first.connection, new Initialized());
    first.drop(registry, daemonId);

    // The worker hands over the step and then asks for it to stop, both while nothing is bound.
    String correlationId = registry.sendRunStep(daemonId, "make", 600);
    registry.cancel(daemonId);
    assertEquals(1, first.sent().size(), "nothing goes out on a closed socket: " + first.sent());

    FakeConnection second = admit(registry, daemonId, "c2");
    List<CiDaemonMessage> resent = second.sent();
    assertEquals(3, resent.size(), "Ack, RunStep, Cancel: " + resent);
    assertInstanceOf(Ack.class, resent.get(0));
    assertEquals(correlationId, assertInstanceOf(RunStep.class, resent.get(1)).correlationId());
    assertEquals(correlationId, assertInstanceOf(Cancel.class, resent.get(2)).correlationId());
    registry.reap(daemonId);
  }

  /**
   * The daemon's half of the contract (qits-748 part A): on a new socket it says only {@code Hello}
   * until it is answered an {@code Ack}, then re-sends an {@code Initialized} it had sent but got no
   * step for. So every re-Hello is answered, and a duplicate {@code Initialized} never moves the
   * phase back nor disturbs the settled future.
   */
  @Test
  public void everyReHelloIsAckedAndADuplicateInitializedChangesNothing() {
    CiDaemonRegistry registry = registry(Duration.ofSeconds(30));
    String daemonId = registry.registerLaunch("run-dup-init", 0, SUBJECT, null);
    FakeConnection first = admit(registry, daemonId, "c1");
    registry.onMessage(daemonId, first.connection, new Initialized());
    assertEquals(CiDaemonRegistry.Phase.INITIALIZED, registry.phaseOf(daemonId));
    first.drop(registry, daemonId);

    // Back before the worker sent the step: Ack, nothing else owed, and the replayed Initialized
    // leaves the launch exactly where it was.
    FakeConnection second = admit(registry, daemonId, "c2");
    assertEquals(1, second.sent().size(), "the Ack and nothing else: " + second.sent());
    assertInstanceOf(Ack.class, second.sent().get(0));
    registry.onMessage(daemonId, second.connection, new Initialized());
    assertEquals(CiDaemonRegistry.Phase.INITIALIZED, registry.phaseOf(daemonId));
    assertEquals(
        CiDaemonRegistry.Initialization.Status.INITIALIZED,
        registry.awaitInitialized(daemonId, SOON).status());

    // A second drop and re-Hello is answered too.
    String correlationId = registry.sendRunStep(daemonId, "make", 600);
    second.drop(registry, daemonId);
    FakeConnection third = admit(registry, daemonId, "c3");
    assertInstanceOf(Ack.class, third.sent().get(0));
    registry.onMessage(daemonId, third.connection, new Initialized());
    assertEquals(CiDaemonRegistry.Phase.RUNNING, registry.phaseOf(daemonId));

    // After the terminal frame a late duplicate is ignored as well.
    registry.onMessage(daemonId, third.connection, new StepFinished(correlationId, 0, false));
    registry.onMessage(daemonId, third.connection, new Initialized());
    assertEquals(CiDaemonRegistry.Phase.DONE, registry.phaseOf(daemonId));
    registry.reap(daemonId);
  }

  @Test
  public void aFirstHelloIsOwedNothingButItsAck() {
    CiDaemonRegistry registry = registry(Duration.ofSeconds(30));
    String daemonId = registry.registerLaunch("run-first", 0, SUBJECT, null);
    FakeConnection first = admit(registry, daemonId, "c1");
    assertEquals(1, first.sent().size());
    assertEquals(CiDaemonRegistry.Phase.CONNECTED, registry.phaseOf(daemonId));
    registry.reap(daemonId);
  }

  @Test
  public void aChunkReplayedAfterTheReconnectIsDroppedByItsSeq() {
    List<String> chunks = Collections.synchronizedList(new ArrayList<>());
    CiDaemonRegistry registry = registry(Duration.ofSeconds(30));
    String daemonId =
        registry.registerLaunch(
            "run-replay", 0, SUBJECT, (stream, seq, text) -> chunks.add(seq + ":" + text));
    FakeConnection first = admit(registry, daemonId, "c1");
    registry.onMessage(daemonId, first.connection, new Initialized());
    String correlationId = registry.sendRunStep(daemonId, "make", 600);
    for (int seq = 0; seq <= 2; seq++) {
      registry.onMessage(
          daemonId, first.connection, new StepChunk(correlationId, seq, Stream.OUT, "l" + seq));
    }
    first.drop(registry, daemonId);

    FakeConnection second = admit(registry, daemonId, "c2");
    // The daemon replays what it could not confirm: 1 and 2 again, then the new 3.
    for (int seq = 1; seq <= 3; seq++) {
      registry.onMessage(
          daemonId, second.connection, new StepChunk(correlationId, seq, Stream.OUT, "l" + seq));
    }
    // A gap is still delivered (and logged): seq exists to tell a lost frame from silence.
    registry.onMessage(daemonId, second.connection, new StepChunk(correlationId, 5, Stream.OUT, "l5"));

    assertEquals(List.of("0:l0", "1:l1", "2:l2", "3:l3", "5:l5"), chunks);
    registry.reap(daemonId);
  }

  @Test
  public void aSecondDialWhileTheFirstIsStillOpenIsStillAClaim() {
    CiDaemonRegistry registry = registry(Duration.ofSeconds(30));
    String daemonId = registry.registerLaunch("run-claim", 0, SUBJECT, null);
    admit(registry, daemonId, "c1");
    FakeConnection second = new FakeConnection("c2");
    assertEquals(
        CiDaemonRegistry.Admission.ALREADY_CONNECTED,
        registry.admitByToken(daemonId, SUBJECT, second.connection));
    registry.reap(daemonId);
  }

  // --- the stand-in -------------------------------------------------------------------------------

  /** Admit a connection for the launch and say its Hello on it, as the socket does. */
  private FakeConnection admit(CiDaemonRegistry registry, String daemonId, String id) {
    FakeConnection connection = new FakeConnection(id);
    assertEquals(
        CiDaemonRegistry.Admission.ADMITTED,
        registry.admitByToken(daemonId, SUBJECT, connection.connection));
    assertTrue(
        registry.onMessage(
            daemonId,
            connection.connection,
            new Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION)));
    return connection;
  }

  /**
   * A {@link WebSocketConnection} that records what is sent on it. Only the four methods the
   * registry calls answer; anything else throws, so a new call is noticed rather than faked.
   */
  private final class FakeConnection {

    private final String id;
    private final List<String> texts = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean open = true;
    final WebSocketConnection connection;

    FakeConnection(String id) {
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
                          if (!open) {
                            throw new IllegalStateException("closed");
                          }
                          texts.add(String.valueOf(args[0]));
                          yield Uni.createFrom().voidItem();
                        }
                        case "close" -> {
                          open = false;
                          yield Uni.createFrom().voidItem();
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "FakeConnection[" + this.id + "]";
                        default ->
                            throw new UnsupportedOperationException(method.getName());
                      });
    }

    List<CiDaemonMessage> sent() {
      synchronized (texts) {
        return texts.stream().map(codec::decode).toList();
      }
    }

    /** The socket drops under the step, and the endpoint reports it. */
    void drop(CiDaemonRegistry registry, String daemonId) {
      open = false;
      registry.onClose(daemonId, connection);
    }
  }
}
