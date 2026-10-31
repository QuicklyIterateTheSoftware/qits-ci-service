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
  private volatile WebSocketConnection connection;

  /** How many connections this daemon has dialled; above one is a re-dial. */
  private final java.util.concurrent.atomic.AtomicInteger dials = new java.util.concurrent.atomic.AtomicInteger();

  /**
   * What this daemon answers a {@code Continue} with (epic qits-732): {@code Continued} unless a
   * suite says otherwise; null answers nothing at all.
   */
  private volatile java.util.function.Function<eu.wohlben.qits.cidaemon.protocol.Continue, CiDaemonMessage>
      continueAnswer = next -> new eu.wohlben.qits.cidaemon.protocol.Continued(next.runId());

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
    return dial(registry, daemonId, subject, CiDaemonProtocol.CAPABILITY_VERSION);
  }

  /** {@link #dial(CiDaemonRegistry, String, String)}, announcing {@code capability} in its Hello. */
  public static ScriptedLeaseDaemon dial(
      CiDaemonRegistry registry, String daemonId, String subject, int capability) {
    ScriptedLeaseDaemon daemon = new ScriptedLeaseDaemon(registry, daemonId);
    if (registry.admitByToken(daemonId, subject, daemon.connection)
        != CiDaemonRegistry.Admission.ADMITTED) {
      throw new IllegalStateException("not admitted");
    }
    registry.onMessage(daemonId, daemon.connection, new Hello(daemonId, capability));
    registry.onMessage(daemonId, daemon.connection, new Initialized());
    return daemon;
  }

  private ScriptedLeaseDaemon(CiDaemonRegistry registry, String daemonId) {
    this.registry = registry;
    this.daemonId = daemonId;
    this.codec = codec();
    this.connection = newConnection();
  }

  private WebSocketConnection newConnection() {
    String id = "scripted-" + daemonId + (dials.getAndIncrement() == 0 ? "" : "-" + dials.get());
    return
        (WebSocketConnection)
            Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class<?>[] {WebSocketConnection.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "id" -> id;
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

  /** Answer every later {@code Continue} with {@code answer}; null answers nothing. */
  public void answerContinue(
      java.util.function.Function<eu.wohlben.qits.cidaemon.protocol.Continue, CiDaemonMessage>
          answer) {
    this.continueAnswer = answer;
  }

  /**
   * Dial again as {@code subject} on a fresh connection — what the daemon does after a drop, with
   * the token it holds now — and say Hello on it when admitted. Answers the admission.
   */
  public CiDaemonRegistry.Admission redial(String subject) {
    WebSocketConnection fresh = newConnection();
    CiDaemonRegistry.Admission admission = registry.admitByToken(daemonId, subject, fresh);
    if (admission == CiDaemonRegistry.Admission.ADMITTED) {
      connection = fresh;
      registry.onMessage(daemonId, fresh, new Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
    }
    return admission;
  }

  private void received(CiDaemonMessage message) {
    received.add(message);
    if (message instanceof eu.wohlben.qits.cidaemon.protocol.Continue next) {
      java.util.function.Function<eu.wohlben.qits.cidaemon.protocol.Continue, CiDaemonMessage>
          answer = continueAnswer;
      CiDaemonMessage reply = answer == null ? null : answer.apply(next);
      if (reply != null) {
        replies.execute(() -> registry.onMessage(daemonId, connection, reply));
      }
      return;
    }
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
