package eu.wohlben.qits.ci.targethost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>qits-system's lease doors, the four calls a CI run makes</b> (epic qits-1481): {@code
 * createLease} ({@code POST /system/api/leases}), {@code getLease}, {@code listLeases} and {@code
 * endLease} ({@code DELETE /system/api/leases/{id}}). qits-system names the lease's owner from the
 * caller's own client ({@code dev-qits-ci} → {@code qits-ci}), never from a body field, and grants a
 * {@code qits-ci} caller only {@code CI} slots.
 *
 * <p><b>Hand-rolled {@code java.net.http}</b>, for the reason every client in this service is: no
 * REST client library for the native-image builder to be told about. qits-workspaces, the first
 * owner to lease, uses a generated MicroProfile client for the same doors; the requests and the
 * narrowed answers here are the same shapes.
 *
 * <p><b>The bearer is this service's own {@code qits} oidc client's</b> ({@code qits:system},
 * audience {@code qits-platform}), the one outbound identity this service has. With the client off
 * — {@code %dev}, {@code %test} — there is no bearer, and a call answers what qits-system answers a
 * bare one: a refusal, which is a {@link SystemUnavailable} like any other.
 *
 * <p>The address is {@code qits.system.url}: scheme, host and port, no path — qits-system serves its
 * own {@code /system/api} segment.
 */
@ApplicationScoped
@RegisterForReflection(
    targets = {
      SystemLeases.CreateLease.class,
      SystemLeases.LeaseSpec.class,
      SystemLeases.Limits.class,
      SystemLeases.LeaseView.class
    })
public class SystemLeases {

  private static final Logger LOG = Logger.getLogger(SystemLeases.class);

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  /** The one slot type this service leases. */
  public static final String SLOT_TYPE = "CI";

  /** The owner qits-system names this service's leases by. */
  public static final String OWNER = "qits-ci";

  @ConfigProperty(name = "qits.system.url")
  String systemUrl;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean clientEnabled;

  @Inject @NamedOidcClient("qits") OidcClient oidcClient;

  @Inject ObjectMapper objectMapper;

  @Inject QitsHttpClients clients;

  private final TokensHelper tokens = new TokensHelper();

  private HttpClient http;

  /** A suite's hand-wired instance: its own address and bearer, no CDI. */
  static SystemLeases against(String url, ObjectMapper mapper, Optional<String> bearer) {
    SystemLeases leases =
        new SystemLeases() {
          @Override
          Optional<String> bearer() {
            return bearer;
          }
        };
    leases.systemUrl = url;
    leases.objectMapper = mapper;
    leases.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    return leases;
  }

  @PostConstruct
  void buildClient() {
    http = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /** A call qits-system did not answer, or answered with something other than its contract. */
  public static final class SystemUnavailable extends RuntimeException {
    private final int status;

    SystemUnavailable(String message, int status) {
      super(message, null, false, false);
      this.status = status;
    }

    /** The HTTP status qits-system answered, or 0 when it did not answer at all. */
    public int status() {
      return status;
    }
  }

  /** qits-system's {@code CreateLeaseRequest}. Absent members are left out of the body. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CreateLease(String slotType, String ownerRef, Integer priority, LeaseSpec spec) {}

  /** {@code LeaseSpec{image, env, user, limits}}, the shape qits-system-runner decodes. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record LeaseSpec(String image, Map<String, String> env, String user, Limits limits) {
    @Override
    public String toString() {
      // The environment carries the run's token.
      return "LeaseSpec[image=" + image + ", user=" + user + ", limits=" + limits + "]";
    }
  }

  /** The runner's {@code Limits}; an unset limit is left out. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Limits(
      String memory, String memorySwap, Long pidsLimit, String cpus, Integer oomScoreAdj) {}

  /** qits-system's {@code Lease}, narrowed to what this service reads. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LeaseView(
      UUID id,
      String ownerRef,
      String state,
      UUID targetId,
      Integer exitCode,
      String detail,
      Instant requestedAt) {}

  /**
   * {@code POST /system/api/leases}: a CI slot for the run, QUEUED until qits-system grants it.
   *
   * @throws SystemUnavailable when qits-system did not answer 201 with a lease — a 409 {@code
   *     LEASE_EXISTS} included, which {@link #liveLeaseOf} resolves
   */
  public LeaseView create(CreateLease request) {
    String body;
    try {
      body = objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new IllegalStateException("the lease request could not be written", e);
    }
    HttpResponse<String> response =
        send(
            "POST /system/api/leases",
            base()
                .uri(URI.create(leasesUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    if (response.statusCode() != 201 && response.statusCode() != 200) {
      throw refused("POST /system/api/leases", response);
    }
    return read(response.body(), "POST /system/api/leases");
  }

  /** {@code GET /system/api/leases/{id}}: the lease, or empty when qits-system holds none by it. */
  public Optional<LeaseView> get(UUID leaseId) {
    String what = "GET /system/api/leases/" + leaseId;
    HttpResponse<String> response =
        send(what, base().uri(URI.create(leasesUrl() + "/" + leaseId)).GET());
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      throw refused(what, response);
    }
    return Optional.of(read(response.body(), what));
  }

  /**
   * {@code GET /system/api/leases}: every lease of this service's — qits-system answers a {@code
   * qits:system} caller its own, and every one of them is a CI lease.
   */
  public List<LeaseView> list() {
    String what = "GET /system/api/leases";
    HttpResponse<String> response = send(what, base().uri(URI.create(leasesUrl())).GET());
    if (response.statusCode() != 200) {
      throw refused(what, response);
    }
    try {
      JsonNode root = objectMapper.readTree(response.body());
      if (root == null || !root.isArray()) {
        throw new SystemUnavailable(what + " answered no JSON array", response.statusCode());
      }
      List<LeaseView> leases = new ArrayList<>();
      for (JsonNode lease : root) {
        leases.add(objectMapper.treeToValue(lease, LeaseView.class));
      }
      return List.copyOf(leases);
    } catch (SystemUnavailable e) {
      throw e;
    } catch (Exception e) {
      throw new SystemUnavailable(what + " answered an unreadable body: " + e, response.statusCode());
    }
  }

  /**
   * {@code DELETE /system/api/leases/{id}}: the lease ENDING (or ENDED at once when no target holds
   * it), or empty when qits-system holds none by that id — which is the state that was asked for.
   */
  public Optional<LeaseView> end(UUID leaseId) {
    String what = "DELETE /system/api/leases/" + leaseId;
    HttpResponse<String> response =
        send(what, base().uri(URI.create(leasesUrl() + "/" + leaseId)).DELETE());
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      throw refused(what, response);
    }
    return Optional.of(read(response.body(), what));
  }

  /** This service's live lease for a run, from the listing — what a 409 LEASE_EXISTS points at. */
  public Optional<LeaseView> liveLeaseOf(String runId) {
    return list().stream()
        .filter(lease -> runId.equals(lease.ownerRef()) && !"ENDED".equals(lease.state()))
        .findFirst();
  }

  // --- internals --------------------------------------------------------------------------------

  String leasesUrl() {
    return (systemUrl == null ? "" : systemUrl.trim().replaceAll("/+$", "")) + "/system/api/leases";
  }

  private HttpRequest.Builder base() {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().timeout(REQUEST_TIMEOUT).header("Accept", "application/json");
    bearer().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
    return builder;
  }

  /** This service's own machine token, or empty when the client is off or the idp did not answer. */
  Optional<String> bearer() {
    if (!clientEnabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for qits-system: %s", e.toString());
      return Optional.empty();
    }
  }

  private HttpResponse<String> send(String what, HttpRequest.Builder request) {
    try {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SystemUnavailable(what + " was interrupted", 0);
    } catch (Exception e) {
      throw new SystemUnavailable(what + " did not answer: " + e, 0);
    }
  }

  private LeaseView read(String body, String what) {
    try {
      LeaseView lease = objectMapper.readValue(body, LeaseView.class);
      if (lease == null || lease.id() == null) {
        throw new SystemUnavailable(what + " answered no lease", 200);
      }
      return lease;
    } catch (SystemUnavailable e) {
      throw e;
    } catch (Exception e) {
      throw new SystemUnavailable(what + " answered an unreadable lease: " + e, 200);
    }
  }

  private static SystemUnavailable refused(String what, HttpResponse<String> response) {
    String body = response.body() == null ? "" : response.body().strip();
    if (body.length() > 300) {
      body = body.substring(0, 300) + "…";
    }
    return new SystemUnavailable(
        what + " answered HTTP " + response.statusCode() + (body.isEmpty() ? "" : ": " + body),
        response.statusCode());
  }
}
