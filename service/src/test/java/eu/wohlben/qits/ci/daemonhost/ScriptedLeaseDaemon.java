package eu.wohlben.qits.ci.daemonhost;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>One lease's {@code qits-ci-daemon}, scripted, on one connection</b> — for a suite outside this
 * package that drives the step seam against a real {@link CiDaemonRegistry} with no socket: it is
 * admitted once, says {@code Initialized}, and answers every {@link RunStep} it is sent with one
 * chunk naming the step and a {@link StepFinished} naming its index, on its own thread, the way the
 * capability-2 daemon answers (one {@code seq} counter for the whole lease).
 */
public final class ScriptedLeaseDaemon implements AutoCloseable {

  private final CiDaemonRegistry registry;
  private final String daemonId;
  private final CiDaemonMessageCodec codec;
  private final List<CiDaemonMessage> received = Collections.synchronizedList(new ArrayList<>());
  private final ExecutorService replies = Executors.newSingleThreadExecutor();
  private final AtomicLong seq = new AtomicLong();
  private volatile boolean open = true;
  private final WebSocketConnection connection;

  /** A registry wired the way the application wires it, with {@code grace} as its reconnect grace. */
  public static CiDaemonRegistry registry(Duration grace) {
    CiDaemonRegistry registry = new CiDaemonRegistry();
    registry.codec = codec();
    registry.reconnectGrace(grace);
    return registry;
  }

  private static CiDaemonMessageCodec codec() {
    CiDaemonMessageCodec codec = new CiDaemonMessageCodec();
    codec.objectMapper = new ObjectMapper();
    return codec;
  }

  /** Dials {@code daemonId}'s launch as {@code subject}, says Hello and Initialized. */
  public static ScriptedLeaseDaemon dial(CiDaemonRegistry registry, String daemonId, String subject) {
    ScriptedLeaseDaemon daemon = new ScriptedLeaseDaemon(registry, daemonId);
    if (registry.admitByToken(daemonId, subject, daemon.connection)
        != CiDaemonRegistry.Admission.ADMITTED) {
      throw new IllegalStateException("not admitted");
    }
    registry.onMessage(
        daemonId, daemon.connection, new Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
    registry.onMessage(daemonId, daemon.connection, new Initialized());
    return daemon;
  }

  private ScriptedLeaseDaemon(CiDaemonRegistry registry, String daemonId) {
    this.registry = registry;
    this.daemonId = daemonId;
    this.codec = codec();
    this.connection =
        (WebSocketConnection)
            Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class<?>[] {WebSocketConnection.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "id" -> "scripted-" + daemonId;
                      case "isOpen" -> open;
                      case "isClosed" -> !open;
                      case "sendText" -> {
                        received(codec.decode(String.valueOf(args[0])));
                        yield Uni.createFrom().voidItem();
                      }
                      case "close" -> {
                        open = false;
                        yield Uni.createFrom().voidItem();
                      }
                      case "hashCode" -> System.identityHashCode(proxy);
                      case "equals" -> proxy == args[0];
                      case "toString" -> "ScriptedLeaseDaemon[" + daemonId + "]";
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
  }

  private void received(CiDaemonMessage message) {
    received.add(message);
    if (message instanceof RunStep step) {
      replies.execute(
          () -> {
            registry.onMessage(
                daemonId,
                connection,
                new StepChunk(
                    step.correlationId(),
                    seq.getAndIncrement(),
                    Stream.OUT,
                    "step " + step.stepIndex() + " ran\n"));
            registry.onMessage(
                daemonId,
                connection,
                new StepFinished(step.correlationId(), step.stepIndex(), 0, false));
          });
    }
  }

  /** Every frame the host sent this daemon, in order. */
  public List<CiDaemonMessage> received() {
    synchronized (received) {
      return List.copyOf(received);
    }
  }

  /** Whether the host closed this daemon's one connection. */
  public boolean closed() {
    return !open;
  }

  @Override
  public void close() {
    replies.shutdownNow();
  }
}
