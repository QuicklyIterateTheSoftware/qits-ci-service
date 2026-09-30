package eu.wohlben.qits.ci.stories.support;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The platform edge, as far as one step daemon's control socket is concerned: it takes an upgrade
 * presenting a run's {@code ci-run} token as {@code Authorization: Bearer qits_tok_…}, and forwards
 * the connection to qits-ci as the token's subject — a JWT whose {@code sub} is that subject and
 * whose role is {@code qits:ci-run} ({@link StoryDaemon#forwardedFor}) — relaying text frames both
 * ways.
 *
 * <p><b>Why a story needs one.</b> The real {@code qits-ci-daemon} binary presents the raw token and
 * nothing else; qits-ci does not read a raw token (that is the edge's job, and the mechanism that
 * let this service do it for one route was deleted in qits-515). So a test that runs the real
 * binary against a launched qits-ci has to put what the edge does in between, or the dial is a 401.
 * {@link StoryDaemon} needs none: it is this suite's own client and arrives already forwarded.
 *
 * <p><b>It knows one token</b>, the one the launch under test was handed, and refuses every other
 * bearer 401 at the upgrade — as the edge refuses a value qits-idp does not call live. Whether the
 * forwarded subject is the launch's run's is still qits-ci's to decide.
 */
public final class StoryEdge implements AutoCloseable {

  private final Vertx vertx = Vertx.vertx();
  private final HttpServer server;
  private final WebSocketClient client;
  private final int port;

  /** What each upgrade presented as its {@code Authorization} header, in order. */
  public final List<String> presented = new CopyOnWriteArrayList<>();

  private StoryEdge(URI upstream, String token, String subject) throws Exception {
    client = vertx.createWebSocketClient();
    server = vertx.createHttpServer();
    server.webSocketHandler(
        daemon -> {
          String authorization = daemon.headers().get("Authorization");
          presented.add(String.valueOf(authorization));
          if (!("Bearer " + token).equals(authorization)) {
            daemon.reject(401);
            return;
          }
          // Frames the daemon sends before the upstream socket exists wait here rather than being
          // dropped: its Hello is the first thing it says, and it says it at once.
          daemon.pause();
          WebSocketConnectOptions options =
              new WebSocketConnectOptions()
                  .setHost(upstream.getHost())
                  .setPort(upstream.getPort())
                  .setURI(upstream.getPath())
                  .addHeader(
                      "Authorization", "Bearer " + StoryDaemon.forwardedFor(subject));
          client
              .connect(options)
              .onSuccess(
                  host -> {
                    relay(daemon, host);
                    daemon.resume();
                  })
              .onFailure(refused -> daemon.close((short) 1011, String.valueOf(refused)));
        });
    port =
        server
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .actualPort();
  }

  /** An edge in front of {@code upstream} that knows {@code token} and forwards it as {@code subject}. */
  public static StoryEdge inFrontOf(URI upstream, String token, String subject) throws Exception {
    return new StoryEdge(upstream, token, subject);
  }

  /** Where a daemon dials this edge: the upstream's own path, on this edge's port. */
  public String socketUrl() {
    return "ws://127.0.0.1:" + port + StoryTarget.DAEMON_PATH;
  }

  private static void relay(WebSocketBase daemon, WebSocketBase host) {
    daemon.textMessageHandler(host::writeTextMessage);
    host.textMessageHandler(daemon::writeTextMessage);
    daemon.closeHandler(
        gone -> {
          if (!host.isClosed()) {
            host.close();
          }
        });
    host.closeHandler(
        gone -> {
          if (!daemon.isClosed()) {
            Short code = host.closeStatusCode();
            daemon.close(code == null ? (short) 1000 : code, host.closeReason());
          }
        });
  }

  @Override
  public void close() {
    server.close();
    client.close();
    vertx.close();
  }
}
