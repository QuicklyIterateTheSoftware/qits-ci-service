package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonCodec;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A ci-daemon that never leaves this JVM: a real Vert.x WebSocket client dialling the real endpoint
 * and framing the real protocol messages the same way the native binary does — {@code new
 * JsonObject(CiDaemonCodec.encode(m))} out, {@code CiDaemonCodec.decode(json)} in. The host cannot
 * tell it from a container, which is the point: {@link CiDaemonSocket} and {@link CiDaemonRegistry}
 * are provable in a docker-free suite, and only {@code CiDaemonPinIT} needs a published binary.
 *
 * <p>Deliberately dumb — it holds no state machine and answers nothing on its own. Each test scripts
 * the frames it wants, including the wrong ones, which is how the refused dials and the malformed
 * frame are testable at all.
 *
 * <p><b>It arrives the way a step's daemon arrives BEHIND THE EDGE.</b> The real binary presents its
 * run's {@code ci-run} token as {@code Authorization: Bearer}; the platform edge introspects it and
 * forwards the caller as the token's subject with the role {@code qits:ci-run}. No suite here has
 * an edge, so this dials as what the edge forwards: {@code X-Qits-User: <subject>}, {@code
 * X-Qits-Roles: qits:ci-run}, which the forward-auth mechanism reads into the same identity. {@link
 * CiDaemonSocket}'s {@code @RolesAllowed} is enforced at the HTTP <em>upgrade</em>, so a dial with
 * no identity is answered <b>401 and never reaches {@code @OnOpen} at all</b>.
 *
 * <p><b>There is no launch header and no secret</b> (qits-515). The connection names its launch in
 * its first frame, a {@code Hello} — {@link #hello(String)} — and is admitted when that launch was
 * recorded against the subject it arrived as.
 */
public final class FakeCiDaemon implements AutoCloseable {

  /** How the edge names the caller to the forward-auth mechanism: the token's subject. */
  public static final String USER_HEADER = "X-Qits-User";

  /** …and its roles. */
  public static final String ROLES_HEADER = "X-Qits-Roles";

  /** The role a {@code ci-run} token carries — {@code CiDaemonSocket.RUN_ROLE}. */
  public static final String RUN_ROLE = "qits:ci-run";

  private final Vertx vertx;
  private final WebSocketClient client;
  private final WebSocket socket;
  private final BlockingQueue<CiDaemonMessage> received = new ArrayBlockingQueue<>(256);
  private final CompletableFuture<Short> closeCode = new CompletableFuture<>();
  private final CompletableFuture<String> closeReason = new CompletableFuture<>();

  /**
   * Dial the endpoint as the {@code ci-run} token of {@code subject}, the way the edge forwards one.
   * Returns once the HTTP upgrade completed; nothing is admitted until {@link #hello(String)} names a
   * launch. A refused dial is a 1008 <em>close</em> after a successful upgrade, not a failed
   * handshake, so the caller asserts on {@link #awaitClose} rather than on this throwing.
   */
  public static FakeCiDaemon dial(URI endpoint, String subject) throws Exception {
    return dial(endpoint, java.util.Map.of(USER_HEADER, subject, ROLES_HEADER, RUN_ROLE));
  }

  /**
   * {@link #dial(URI, String)} with exactly the handshake headers given — another identity, a header
   * the host no longer reads, or none at all where the test's own {@code @TestSecurity} identity is
   * what every upgrade in the method arrives as.
   */
  public static FakeCiDaemon dial(URI endpoint, java.util.Map<String, String> headers)
      throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath());
      headers.forEach(options::addHeader);
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
      return new FakeCiDaemon(vertx, client, socket);
    } catch (Exception failedToUpgrade) {
      vertx.close();
      throw failedToUpgrade;
    }
  }

  private FakeCiDaemon(Vertx vertx, WebSocketClient client, WebSocket socket) {
    this.vertx = vertx;
    this.client = client;
    this.socket = socket;
    // A socket may already be closed by the time a handler is set on this thread, and
    // Vert.x answers every handler setter on a closed socket with IllegalStateException("WebSocket
    // is closed"). So both orderings are handled — check first, and catch the one that slips
    // between the check and the setter — and the code is read off the socket, since a closeHandler
    // registered after the close will never fire. Left unguarded this is a genuine flake: it fails
    // the two refusal cases and only under load, which is exactly the shape that reads as an
    // unrelated regression in whatever change happened to be in the tree.
    try {
      if (!socket.isClosed()) {
        socket.textMessageHandler(
            text -> received.offer(CiDaemonCodec.decode(new JsonObject(text).getMap())));
        socket.closeHandler(
            ignored -> {
              closeReason.complete(socket.closeReason());
              closeCode.complete(socket.closeStatusCode());
            });
        return;
      }
    } catch (IllegalStateException closedWhileWeWereListening) {
      // Fall through to the same answer.
    }
    closeReason.complete(socket.closeReason());
    closeCode.complete(socket.closeStatusCode());
  }

  /**
   * The daemon's first frame: name the launch this connection is, at the capability version this
   * suite is built against. The host answers an admitted one with an {@code Ack}.
   */
  public void hello(String daemonId) throws Exception {
    send(
        new eu.wohlben.qits.cidaemon.protocol.Hello(
            daemonId, eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol.CAPABILITY_VERSION));
  }

  /** Send one frame, framed exactly as the binary frames it. */
  public void send(CiDaemonMessage message) throws Exception {
    socket
        .writeTextMessage(new JsonObject(CiDaemonCodec.encode(message)).encode())
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** Send text the codec cannot decode — the one thing a well-behaved client would never do. */
  public void sendRaw(String text) throws Exception {
    socket.writeTextMessage(text).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** The next frame the host sent, or null if none arrived in time. */
  public CiDaemonMessage next(Duration timeout) throws InterruptedException {
    return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The close code the host sent, or null if it did not close in time. */
  public Short awaitClose(Duration timeout) {
    try {
      return closeCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception notClosed) {
      return null;
    }
  }

  /** The reason the host closed with — the refusal's name — once {@link #awaitClose} has seen it. */
  public String closeReason() {
    return closeReason.getNow(null);
  }

  public boolean isOpen() {
    return !socket.isClosed();
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (RuntimeException ignored) {
      // already gone
    }
    client.close();
    vertx.close();
  }
}
