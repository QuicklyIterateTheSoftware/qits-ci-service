package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.cirunner.protocol.CiRunnerCodec;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A runner that never leaves this JVM: a real Vert.x WebSocket client dialling the real endpoint,
 * framing the real protocol records exactly as the {@code ci-runner} binary does — {@code new
 * JsonObject(CiRunnerCodec.encode(m))} out, {@code CiRunnerCodec.decode(json.getMap())} in — so the
 * host cannot tell it from a runner host. {@code FakeCiDaemon}'s twin, and as deliberately dumb: it
 * answers nothing on its own, and each test scripts the frames it wants.
 *
 * <p><b>It presents no credential.</b> The identity is the test's {@code @TestSecurity}, which
 * Quarkus applies at the HTTP upgrade exactly where a validated bearer would land, so the socket's
 * subject rule is exercised against the claim it reads in production. Extra headers can be passed for
 * the one case that wants the forward-auth pair instead.
 */
public final class FakeCiRunner implements AutoCloseable {

  private final Vertx vertx;
  private final WebSocketClient client;
  private final WebSocket socket;
  private final BlockingQueue<CiRunnerMessage> received = new ArrayBlockingQueue<>(256);
  private final CompletableFuture<Short> closeCode = new CompletableFuture<>();
  private volatile String closeReason;

  public static FakeCiRunner dial(URI endpoint) throws Exception {
    return dial(endpoint, Map.of());
  }

  public static FakeCiRunner dial(URI endpoint, Map<String, String> headers) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath());
      MultiMap extra = MultiMap.caseInsensitiveMultiMap();
      headers.forEach(extra::add);
      options.setHeaders(extra);
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
      return new FakeCiRunner(vertx, client, socket);
    } catch (Exception failedToUpgrade) {
      vertx.close();
      throw failedToUpgrade;
    }
  }

  private FakeCiRunner(Vertx vertx, WebSocketClient client, WebSocket socket) {
    this.vertx = vertx;
    this.client = client;
    this.socket = socket;
    // A refused dial can be closed before this constructor installs anything — FakeCiDaemon's
    // measured flake — so both orderings are handled and the code is read off the socket.
    try {
      if (!socket.isClosed()) {
        socket.textMessageHandler(
            text -> received.offer(CiRunnerCodec.decode(new JsonObject(text).getMap())));
        socket.closeHandler(
            ignored -> {
              closeReason = socket.closeReason();
              closeCode.complete(socket.closeStatusCode());
            });
        return;
      }
    } catch (IllegalStateException closedWhileWeWereListening) {
      // Fall through to the same answer.
    }
    closeReason = socket.closeReason();
    closeCode.complete(socket.closeStatusCode());
  }

  /** Send one frame, framed exactly as the binary frames it. */
  public void send(CiRunnerMessage message) throws Exception {
    socket
        .writeTextMessage(new JsonObject(CiRunnerCodec.encode(message)).encode())
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** The next frame the host sent, or null if none arrived in time. */
  public CiRunnerMessage next(Duration timeout) throws InterruptedException {
    return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The next frame of one type, skipping any other (a Backlog pushed by a neighbour, say). */
  @SuppressWarnings("unchecked")
  public <T extends CiRunnerMessage> T next(Class<T> type, Duration timeout)
      throws InterruptedException {
    return (T) nextMatching(type::isInstance, timeout);
  }

  /** The next frame that satisfies {@code wanted}, or null when none did in time. */
  public CiRunnerMessage nextMatching(Predicate<CiRunnerMessage> wanted, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return null;
      }
      CiRunnerMessage message = received.poll(left, TimeUnit.NANOSECONDS);
      if (message != null && wanted.test(message)) {
        return message;
      }
    }
  }

  /** The close code the host sent, or null if it did not close in time. */
  public Short awaitClose(Duration timeout) {
    try {
      return closeCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception notClosed) {
      return null;
    }
  }

  /** The reason text of the host's close, once {@link #awaitClose} has answered. */
  public String closeReason() {
    return closeReason;
  }

  public boolean isOpen() {
    return !socket.isClosed();
  }

  @Override
  public void close() {
    try {
      socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // already gone
    }
    client.close();
    vertx.close();
  }
}
