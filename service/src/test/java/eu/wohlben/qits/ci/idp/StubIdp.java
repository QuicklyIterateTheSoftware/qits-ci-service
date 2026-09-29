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
 * GET /idp/api/clients} lists this owner's live ones — and the same three again under {@code
 * /idp/api/tokens}, for the opaque tokens a runner registers with. Everything a test wants to claim is recorded
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

  /**
   * Asked as each client is decommissioned — before it is recorded and answered — polled for up to
   * 100 ms; what it answered is appended to {@link #decommissionChecks}. How a case proves something
   * happened BEFORE the client was revoked: shorter than the commissioner's 200 ms deadline, so a
   * caller that waits on this answer before doing the thing cannot have done it within the window.
   */
  public volatile java.util.function.BooleanSupplier onDecommission;

  public final List<Boolean> decommissionChecks = Collections.synchronizedList(new ArrayList<>());

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

  /** Every token commissioning body the stub was posted, in order — {@code /api/tokens}. */
  public final List<String> postedTokens = Collections.synchronizedList(new ArrayList<>());

  /** Every token id it was asked to delete, in order. */
  public final List<String> deletedTokens = Collections.synchronizedList(new ArrayList<>());

  /** What a token mint answers — a refusal or an outage, staged. */
  public volatile int tokenMintStatus = 201;

  /** What the token listing answers. */
  public volatile String tokenListingBody = "[]";

  private final AtomicInteger tokensMinted = new AtomicInteger();

  /**
   * What {@code POST /idp/api/tokens/introspect} answers, per presented value: the body of a 200.
   * A value with no entry is qits-idp's one 404, {@code no live token for that value} — unknown,
   * deleted, or its owner gone, which the real door does not tell apart either.
   */
  public final java.util.Map<String, String> introspection =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** Every value the stub was asked to introspect, in order, and who asked. */
  public final List<String> introspected = Collections.synchronizedList(new ArrayList<>());

  public final List<String> introspectionCallers = Collections.synchronizedList(new ArrayList<>());

  public StubIdp() {
    server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          if (req.path().contains("/api/tokens")) {
            tokens(req);
            return;
          }
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
            java.util.function.BooleanSupplier check = onDecommission;
            if (check == null) {
              deleted.add(path.substring(path.lastIndexOf('/') + 1));
              req.response().setStatusCode(204).end();
              return;
            }
            vertx
                .executeBlocking(
                    () -> {
                      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                      while (!check.getAsBoolean() && System.nanoTime() < deadline) {
                        Thread.sleep(5);
                      }
                      return check.getAsBoolean();
                    })
                .onComplete(
                    answered -> {
                      decommissionChecks.add(answered.succeeded() && answered.result());
                      deleted.add(path.substring(path.lastIndexOf('/') + 1));
                      req.response().setStatusCode(204).end();
                    });
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

  /**
   * The opaque-token surface: {@code POST} mints {@code token-<n>} with the value {@code
   * qits_tok_stub-<n>} and the subject {@code tok-<kind>-<contextId>-<n>}, {@code DELETE} records the
   * id, {@code GET} answers {@link #tokenListingBody}.
   */
  private void tokens(io.vertx.core.http.HttpServerRequest req) {
    if (req.method() == HttpMethod.POST && req.path().endsWith("/api/tokens/introspect")) {
      introspectionCallers.add(req.getHeader("Authorization"));
      req.bodyHandler(
          body -> {
            String token = body.toJsonObject().getString("token");
            introspected.add(token);
            String answer = token == null ? null : introspection.get(token);
            req.response()
                .setStatusCode(answer == null ? 404 : 200)
                .putHeader("Content-Type", "application/json")
                .end(
                    answer == null
                        ? "{\"error\":\"not_found\",\"error_description\":\"no live token for"
                            + " that value\"}"
                        : answer);
          });
      return;
    }
    if (req.method() == HttpMethod.POST) {
      authorizations.add(req.getHeader("Authorization"));
      req.bodyHandler(
          body -> {
            postedTokens.add(body.toString());
            int status = tokenMintStatus;
            if (status != 201 && status != 200) {
              req.response()
                  .setStatusCode(status)
                  .putHeader("Content-Type", "application/json")
                  .end(refusal(status));
              return;
            }
            int n = tokensMinted.incrementAndGet();
            io.vertx.core.json.JsonObject posted = body.toJsonObject();
            String kind = posted.getString("contextKind");
            String context = posted.getString("contextId");
            String answer =
                new io.vertx.core.json.JsonObject()
                    .put("tokenId", "token-" + n)
                    .put("token", "qits_tok_stub-" + n)
                    .put("subject", "tok-" + kind + "-" + context + "-" + n)
                    .put("owner", SERVICE_CLIENT_ID)
                    .put("contextKind", kind)
                    .put("contextId", context)
                    .put("createdAt", "2026-09-27T10:00:00Z")
                    .encode();
            req.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(answer);
          });
      return;
    }
    if (req.method() == HttpMethod.DELETE) {
      String path = req.path();
      deletedTokens.add(path.substring(path.lastIndexOf('/') + 1));
      req.response().setStatusCode(204).end();
      return;
    }
    listings.incrementAndGet();
    req.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(tokenListingBody);
  }

  private static String refusal(int status) {
    return "{\"error\":\"invalid_client\",\"error_description\":\"stubbed "
        + status
        + " refusal\"}";
  }

  /** The base a deployment configures as {@code quarkus.oidc-client.qits.auth-server-url}. */
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
   * The shipped posture: {@code quarkus.oidc-client.qits.client-enabled} off, so there is nothing to
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
