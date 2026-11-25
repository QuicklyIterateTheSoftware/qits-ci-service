package eu.wohlben.qits.ci.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiReleaseRequestListing;
import eu.wohlben.qits.ci.githost.GitHostBearer;
import eu.wohlben.qits.ci.githost.IdpGitHostBearer;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production {@link CiReleaseRequestListing} (qits-2604): {@code GET
 * {qits.ci.projects-url}/projects/api/repositories/{repoId}/release-requests} — qits-projects'
 * {@code listRepositoryReleaseRequests}, asked with no {@code state}, so the answer is the open
 * requests plus the last ten released — into {@code (id, qualifiedId, state, mergedSha)} per
 * request.
 *
 * <p>It answers every request the door does. Which states count as a fold the counterpart is
 * offering is {@link CiReleaseRequestListing#OPEN_STATES}, applied by {@code CiPactParityInputs}
 * where the parity check is tested; a reader that also filtered would be a second spelling of the
 * same rule.
 *
 * <p><b>Configured like {@link HttpProjectsRepoListing}, by the same key.</b> {@code
 * qits.ci.projects-url} unset is not {@link #configured()}, and the pact parity check reads the
 * counterpart at its release alone — a deployment that predates the catalogue, or a clone-alone
 * build, keeps the check it had before qits-2604.
 *
 * <p><b>A failed read is an answer, not an empty list.</b> No bearer, a socket that will not open,
 * a non-2xx, a body that is not JSON and a body without a {@code requests} array are each one WARN
 * naming the url and {@link Listing#unreachable}: an empty list would claim the counterpart has no
 * open request, and refuse a fold that one of them would have let through. The caller keeps the
 * event owed instead, so the next sweep asks again.
 *
 * <h2>Authentication</h2>
 *
 * <p>The {@code qits} named oidc client's bearer ({@link IdpGitHostBearer}) and nothing beside it,
 * exactly as {@link HttpProjectsRepoListing} presents it (owner ruling 2026-10-18); with no bearer
 * the call is never sent. The door admits every role {@code listRepositories} does, so a bearer
 * the catalogue read accepts is one this read accepts too.
 *
 * <h2>The timeouts, and no cache</h2>
 *
 * <p>The repository listing's bounds ({@link #CONNECT_TIMEOUT} 2s, {@link #REQUEST_TIMEOUT} 3s), for
 * its reason: this runs on {@code ci-trigger-worker}, composing a release request's QA run, and an
 * untimed call would stall every arriving event. There is no cache — it is read once per composed
 * QA run of a repository declaring {@code contracts.mirror}, and a request opened a second ago is
 * exactly what the retry the refusal recommends must see.
 *
 * <p><b>An instance {@code HttpClient}, never a static one</b>, wrapped through {@link
 * QitsHttpClients}; the body is {@code readTree} and a walk, so nothing here needs reflection
 * registering for the native image.
 */
@ApplicationScoped
public class HttpProjectsReleaseRequestListing implements CiReleaseRequestListing {

  private static final Logger LOG = Logger.getLogger(HttpProjectsReleaseRequestListing.class);

  /** Bound on opening the socket — see the class javadoc. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Bound on the whole exchange, response body included. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  /** The path qits-projects serves a repository's release requests under, {@code %s} its id. */
  static final String LISTING_PATH = "/projects/api/repositories/%s/release-requests";

  private HttpClient client;

  /** Where qits-projects answers — the key {@link HttpProjectsRepoListing} reads, unset by default. */
  @ConfigProperty(name = "qits.ci.projects-url")
  Optional<String> projectsUrl;

  @Inject ObjectMapper objectMapper;
  @Inject QitsHttpClients clients;
  @Inject GitHostBearer gitHostBearer;

  /** Built here rather than at the field — see {@code HttpGitConfigSource#buildClient}. */
  @PostConstruct
  void buildClient() {
    client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  @Override
  public boolean configured() {
    return projectsUrl.filter(url -> !url.isBlank()).isPresent();
  }

  @Override
  public Listing releaseRequests(String repoId) {
    if (!configured()) {
      return Listing.unreachable("no qits.ci.projects-url is configured");
    }
    try {
      CiIdentifiers.requireRepoId(repoId);
    } catch (RuntimeException malformed) {
      // Every id this is asked with comes off a CiScmRelease row, so this is a bad row rather than
      // a bad answer — but it reaches a URL, and an unreadable listing is the honest answer to it.
      return Listing.unreachable("'" + repoId + "' is not a repository id");
    }
    String url = listingUrl(repoId);
    if (!isHttp(url)) {
      LOG.warnf("%s is not an HTTP qits-projects — no release requests to read", url);
      return Listing.unreachable(url + " is not an HTTP qits-projects");
    }
    Optional<String> token = gitHostBearer.token();
    if (token.isEmpty()) {
      LOG.warnf(
          "No bearer from the qits oidc client (quarkus.oidc-client.qits) — not reading the release"
              + " requests from %s",
          url);
      return Listing.unreachable("no bearer from the qits oidc client to read " + url);
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", "Bearer " + token.get())
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        LOG.warnf("Could not read the release requests from %s: HTTP %d", url, response.statusCode());
        return Listing.unreachable(url + " answered HTTP " + response.statusCode());
      }
      return parse(url, response.body());
    } catch (Exception e) {
      LOG.warnf("Could not read the release requests from %s: %s", url, e.toString());
      return Listing.unreachable(url + " could not be read: " + e);
    }
  }

  /**
   * {@code {"requests":[{"id","qualifiedId","state","mergedSha",…}]}} into requests, or {@link
   * Listing#unreachable} when the body is not that. An entry with no {@code id} is skipped: it
   * names nothing a person could open, and the rest of the answer is still evidence.
   */
  Listing parse(String url, String body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (Exception notJson) {
      LOG.warnf("The release requests at %s are not JSON: %s", url, notJson.toString());
      return Listing.unreachable(url + " answered no JSON");
    }
    JsonNode requests = root == null ? null : root.get("requests");
    if (requests == null || !requests.isArray()) {
      LOG.warnf("The release requests at %s carry no \"requests\" array", url);
      return Listing.unreachable(url + " answered no \"requests\" array");
    }
    List<Request> answered = new ArrayList<>();
    for (JsonNode entry : requests) {
      String id = text(entry, "id");
      if (id == null) {
        LOG.debugf("Skipping %s from the release requests at %s — no id", entry, url);
        continue;
      }
      answered.add(
          new Request(
              id, text(entry, "qualifiedId"), text(entry, "state"), text(entry, "mergedSha")));
    }
    return Listing.of(answered);
  }

  private static String text(JsonNode entry, String field) {
    JsonNode at = entry == null ? null : entry.get(field);
    return at == null || !at.isTextual() || at.textValue().isBlank() ? null : at.textValue();
  }

  /** {@code <base>/projects/api/repositories/<repoId>/release-requests}. */
  String listingUrl(String repoId) {
    return projectsUrl.orElse("").replaceAll("/+$", "")
        + LISTING_PATH.formatted(URLEncoder.encode(repoId, StandardCharsets.UTF_8));
  }

  private static boolean isHttp(String url) {
    try {
      String scheme = URI.create(url).getScheme();
      return scheme != null
          && switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http", "https" -> true;
            default -> false;
          };
    } catch (RuntimeException notAUrl) {
      return false;
    }
  }
}
