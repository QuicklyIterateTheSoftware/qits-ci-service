package eu.wohlben.qits.ci.idp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * qits-idp's commissioning surface, as a real server on a real socket — the same shape {@code
 * githost/StubGitHost} and {@code bus/StubEventsServer} give the other two hops this repo talks to
 * over HTTP.
 *
 * <p>Three routes, which are the whole contract {@link IdpCommissioner} depends on: {@code POST
 * /idp/api/clients} mints a pair, {@code DELETE /idp/api/clients/{id}} gives one back, and {@code
 * GET /idp/api/clients} lists this owner's live ones. Everything a test wants to claim is recorded
 * rather than inferred: the bodies posted, the {@code Authorization} headers, the ids deleted and
 * the number of listings read.
 */
public final class StubIdp implements AutoCloseable {

  private final Vertx vertx = Vertx.vertx();
  private final HttpServer server;
  private final int port;

  /** Every commissioning body the stub was posted, in order. */
  public final List<String> posted = Collections.synchronizedList(new ArrayList<>());

  /** Every {@code Authorization} header it saw, so "who commissioned" is asserted, not assumed. */
  public final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

  /** Every client id it was asked to delete, in order. */
  public final List<String> deleted = Collections.synchronizedList(new ArrayList<>());

  /** How many listings were read. */
  public final AtomicInteger listings = new AtomicInteger();

  /** What the mint answers, so a test can stage a refusal or an outage. */
  public volatile int mintStatus = 201;

  public volatile String mintBody = null;

  /**
   * Answer 400 to a commission whose {@code gitRefs} list is not empty, as a qits-idp with the
   * Git-scope contract does when it refuses the list. An empty list is still minted. (A qits-idp
   * without the contract ignores the field and mints, which is the default here.)
   */
  public volatile boolean refuseGitRefList = false;

  /** What the listing answers. */
  public volatile String listingBody = "[]";

  private final AtomicInteger minted = new AtomicInteger();

  public StubIdp() {
    server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          if (req.method() == HttpMethod.POST) {
            authorizations.add(req.getHeader("Authorization"));
            req.bodyHandler(
                body -> {
                  posted.add(body.toString());
                  int status =
                      refuseGitRefList && body.toString().contains("\"gitRefs\":[\"")
                          ? 400
                          : mintStatus;
                  // Only a mint uses up a number, so the first pair minted is always run-client-1.
                  int n =
                      status == 201 || status == 200 ? minted.incrementAndGet() : minted.get();
                  String answer =
                      mintBody != null
                          ? mintBody
                          : "{\"clientId\":\"run-client-"
                              + n
                              + "\",\"secret\":\"run-s3cr3t-"
                              + n
                              + "\",\"owner\":\"dev-qits-ci\",\"contextKind\":\"ci-run\","
                              + "\"contextId\":\"whatever\",\"createdAt\":\"2026-08-14T10:00:00Z\"}";
                  req.response()
                      .setStatusCode(status)
                      .putHeader("Content-Type", "application/json")
                      .end(status == 201 || status == 200 ? answer : refusal(status));
                });
            return;
          }
          if (req.method() == HttpMethod.DELETE) {
            String path = req.path();
            deleted.add(path.substring(path.lastIndexOf('/') + 1));
            req.response().setStatusCode(204).end();
            return;
          }
          listings.incrementAndGet();
          req.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(listingBody);
        });
    try {
      port =
          server
              .listen(0, "127.0.0.1")
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS)
              .actualPort();
    } catch (Exception e) {
      throw new IllegalStateException("could not start the stub idp", e);
    }
  }

  private static String refusal(int status) {
    return "{\"error\":\"invalid_client\",\"error_description\":\"stubbed "
        + status
        + " refusal\"}";
  }

  /** The base a deployment configures as {@code quarkus.oidc-client.auth-server-url}. */
  public String authServerUrl() {
    return "http://127.0.0.1:" + port + "/idp";
  }

  /** The service's own oidc client, the one qits-idp lets commission. */
  public static final String SERVICE_CLIENT_ID = "dev-qits-ci";

  public static final String SERVICE_SECRET = "service-s3cr3t";

  /**
   * A commissioner pointed at this stub, wired by hand.
   *
   * <p>It lives here rather than in each test because {@link IdpCommissioner}'s config fields are
   * package-private, and the tests that need one are in {@code daemonhost} — the same reason {@code
   * StubGitHost} hands out what its callers cannot assemble themselves.
   */
  public IdpCommissioner commissioner(Duration patience) {
    IdpCommissioner idp = new IdpCommissioner();
    idp.clientEnabled = true;
    idp.authServerUrl = authServerUrl();
    idp.clientId = SERVICE_CLIENT_ID;
    idp.clientSecret = Optional.of(SERVICE_SECRET);
    idp.patience = patience;
    idp.objectMapper = new ObjectMapper();
    return idp;
  }

  /** Run-scoped commissions against this stub. */
  public RunCommissions runCommissions(Duration patience) {
    RunCommissions commissions = new RunCommissions();
    commissions.idp = commissioner(patience);
    commissions.objectMapper = new ObjectMapper();
    return commissions;
  }

  /**
   * The shipped posture: {@code quarkus.oidc-client.client-enabled} off, so there is nothing to
   * commission with and nothing is commissioned. No stub is needed for it — a disabled commissioner
   * dials nothing, which is the property this arm is about.
   */
  public static RunCommissions disabledCommissions() {
    IdpCommissioner idp = new IdpCommissioner();
    idp.clientEnabled = false;
    idp.authServerUrl = "http://127.0.0.1:1/idp";
    idp.clientId = SERVICE_CLIENT_ID;
    idp.clientSecret = Optional.empty();
    idp.patience = Duration.ZERO;
    idp.objectMapper = new ObjectMapper();
    RunCommissions commissions = new RunCommissions();
    commissions.idp = idp;
    commissions.objectMapper = new ObjectMapper();
    return commissions;
  }

  @Override
  public void close() {
    server.close();
    vertx.close();
  }
}
