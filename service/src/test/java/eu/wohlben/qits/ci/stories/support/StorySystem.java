package eu.wohlben.qits.ci.stories.support;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>qits-system's lease surface, for the LAUNCHED qits-ci</b> (epic qits-1481): where a run asks
 * for its CI lease, and what grants it. It replaced {@code StoryRunner}, the runner a story played
 * over {@code /ci/runners/socket}, when every run became one lease on a target.
 *
 * <p>Two halves on one port. {@code /system/api/leases} is the surface qits-ci calls — create, get,
 * list, end — answered from memory. {@code /stub/…} is the story's: the leases asked for, with the
 * spec each carried, and the grant that turns one {@code RUNNING} the way qits-system's target
 * would once it started the slot. The story reaches the stub over HTTP rather than through a
 * field, for {@link StoryRunTokens}' reason: the profile that starts it is instantiated in more
 * than one classloader, and the process has exactly one property table.
 *
 * <p><b>Nothing starts a container.</b> The story reads the lease's environment the way the slot's
 * daemon would — {@code QITS_CI_DAEMON_ID}, {@code QITS_TOKEN} and the socket url — and dials
 * {@code /ci/daemon} as that daemon ({@link StoryDaemon}).
 *
 * <p><b>The edges are drawn from what the stub recorded</b>, per lease, in the order it answered:
 * {@code POST /system/api/leases} and {@code DELETE /system/api/leases/{id}}. The listing the
 * reconcile makes once a second is never drawn — which story pays for it is a stopwatch question,
 * {@code StoryGitHost}'s rule for {@code GET /git}.
 */
public final class StorySystem {

  public static final String ACTOR = "qits-system";

  private static final String URL_PROPERTY = "qits.test.packaged-it.stub-system-url";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private static final UUID TARGET = UUID.fromString("7a96e7c1-0000-4000-8000-000000001481");

  /** One lease qits-ci asked for, as the story reads it back. */
  public record Lease(UUID id, String runId, String state, Map<String, String> env, List<String> calls) {}

  private StorySystem() {}

  /**
   * Starts the stub if this JVM has none, and answers the config that points the process at it —
   * with the reconcile at a second, so a lease granted without a dial (a run with no step) is
   * claimed promptly.
   */
  public static synchronized Map<String, String> configOverrides() {
    String url = System.getProperty(URL_PROPERTY);
    if (url == null) {
      // Never stopped: it lives as long as the failsafe JVM, like the embedded postgres.
      url = new Server().start();
      System.setProperty(URL_PROPERTY, url);
    }
    return Map.of(
        "qits.system.url", url, "qits.ci.target-leases.reconcile-interval", "PT1S");
  }

  /** Waits for the lease qits-ci asked for {@code runId}, and draws its creation. */
  public static Lease awaitLease(String runId, Duration patience) throws Exception {
    long deadline = System.nanoTime() + patience.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<Lease> lease = leaseOf(runId);
      if (lease.isPresent()) {
        NetworkCapture.observe(
            NetworkEdge.HTTP, StoryTarget.SERVICE, ACTOR, "POST /system/api/leases -> 201");
        return lease.get();
      }
      Thread.sleep(100);
    }
    throw new AssertionError("qits-ci asked qits-system for no lease for run " + runId + " within "
        + patience);
  }

  /** The target starts the slot: the lease is {@code RUNNING}. */
  public static void grant(Lease lease) throws Exception {
    HttpResponse<String> response =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(url() + "/stub/leases/" + lease.id() + "/grant"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new AssertionError("the stub could not grant lease " + lease.id() + ": " + response);
    }
    NetworkCapture.observe(NetworkEdge.EVENT, ACTOR, StoryTarget.SERVICE, "lease RUNNING");
  }

  /** Waits for qits-ci to end the lease, and draws the end. */
  public static void awaitEnded(Lease lease, Duration patience) throws Exception {
    long deadline = System.nanoTime() + patience.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<Lease> now = leaseOf(lease.runId());
      if (now.isPresent() && "ENDED".equals(now.get().state())) {
        NetworkCapture.observe(
            NetworkEdge.HTTP, StoryTarget.SERVICE, ACTOR, "DELETE /system/api/leases/{id} -> 200");
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("qits-ci never ended lease " + lease.id() + " within " + patience);
  }

  private static Optional<Lease> leaseOf(String runId) throws Exception {
    HttpResponse<String> response =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(url() + "/stub/leases")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    for (JsonNode node : JSON.readTree(response.body())) {
      if (runId.equals(node.path("ownerRef").asText())) {
        Map<String, String> env = new LinkedHashMap<>();
        node.path("spec").path("env").fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));
        List<String> calls = new ArrayList<>();
        node.path("calls").forEach(call -> calls.add(call.asText()));
        return Optional.of(
            new Lease(
                UUID.fromString(node.path("id").asText()),
                runId,
                node.path("state").asText(),
                env,
                calls));
      }
    }
    return Optional.empty();
  }

  private static String url() {
    String url = System.getProperty(URL_PROPERTY);
    assertNotNull(url, "the shared profile starts the qits-system stub before the launch");
    return url;
  }

  // --- the server --------------------------------------------------------------------------------

  /** The in-memory qits-system. One lock; every handler is a few map operations. */
  private static final class Server {

    private final Map<UUID, ObjectNode> leases = new LinkedHashMap<>();

    private final Map<UUID, List<String>> calls = new HashMap<>();

    String start() {
      try {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/system/api/leases", this::api);
        server.createContext("/stub/leases", this::stub);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
      } catch (IOException e) {
        throw new IllegalStateException("the qits-system stub could not start", e);
      }
    }

    private void api(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
      synchronized (this) {
        if (path.equals("/system/api/leases") && method.equals("POST")) {
          JsonNode body = JSON.readTree(exchange.getRequestBody());
          UUID id = UUID.randomUUID();
          ObjectNode lease = JSON.createObjectNode();
          lease.put("id", id.toString());
          lease.put("ownerApp", "qits-ci");
          lease.put("ownerRef", body.path("ownerRef").asText());
          lease.put("slotType", body.path("slotType").asText());
          lease.put("state", "QUEUED");
          lease.put("requestedAt", Instant.now().toString());
          lease.set("spec", body.path("spec"));
          leases.put(id, lease);
          calls.computeIfAbsent(id, k -> new ArrayList<>()).add("create");
          answer(exchange, 201, view(lease));
          return;
        }
        if (path.equals("/system/api/leases") && method.equals("GET")) {
          ArrayNode all = JSON.createArrayNode();
          leases.values().forEach(lease -> all.add(view(lease)));
          answer(exchange, 200, all);
          return;
        }
        UUID id;
        try {
          id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        } catch (IllegalArgumentException e) {
          answer(exchange, 404, JSON.createObjectNode());
          return;
        }
        ObjectNode lease = leases.get(id);
        if (lease == null) {
          answer(exchange, 404, JSON.createObjectNode());
          return;
        }
        if (method.equals("DELETE")) {
          lease.put("state", "ENDED");
          calls.get(id).add("end");
        } else {
          calls.get(id).add("get");
        }
        answer(exchange, 200, view(lease));
      }
    }

    private void stub(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
      synchronized (this) {
        if (path.endsWith("/grant")) {
          String[] parts = path.split("/");
          ObjectNode lease = leases.get(UUID.fromString(parts[parts.length - 2]));
          if (lease == null) {
            answer(exchange, 404, JSON.createObjectNode());
            return;
          }
          lease.put("state", "RUNNING");
          lease.put("targetId", TARGET.toString());
          answer(exchange, 200, view(lease));
          return;
        }
        ArrayNode all = JSON.createArrayNode();
        for (Map.Entry<UUID, ObjectNode> entry : leases.entrySet()) {
          ObjectNode full = entry.getValue().deepCopy();
          ArrayNode made = full.putArray("calls");
          calls.getOrDefault(entry.getKey(), List.of()).forEach(made::add);
          all.add(full);
        }
        answer(exchange, 200, all);
      }
    }

    /** What qits-system answers: the lease without its spec, which carries the run's token. */
    private static ObjectNode view(ObjectNode lease) {
      ObjectNode view = lease.deepCopy();
      view.remove("spec");
      return view;
    }

    private static void answer(HttpExchange exchange, int status, JsonNode body)
        throws IOException {
      byte[] bytes = JSON.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
