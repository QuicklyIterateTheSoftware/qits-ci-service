package eu.wohlben.qits.ci.githost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/**
 * The git-host clients wired by hand against one base url, for tests outside this package — the
 * consumer pacts (qits-1149) point them at a pact mock server. Wired exactly as their own tests wire
 * them.
 */
public final class GitHostClients {

  private GitHostClients() {}

  /** {@link HttpGitConfigSource} against {@code gitHostUrl}, presenting {@code bearer}. */
  public static HttpGitConfigSource configSource(String gitHostUrl, String bearer) {
    HttpGitConfigSource source = new HttpGitConfigSource();
    source.gitHostUrl = gitHostUrl;
    source.objectMapper = new ObjectMapper();
    source.gitHostBearer = () -> Optional.ofNullable(bearer);
    source.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    source.buildClient();
    return source;
  }

  /** {@link HttpGitHostRepoListing} against {@code gitHostUrl}. */
  public static HttpGitHostRepoListing repoListing(String gitHostUrl) {
    HttpGitHostRepoListing listing = new HttpGitHostRepoListing();
    listing.gitHostUrl = gitHostUrl;
    listing.objectMapper = new ObjectMapper();
    listing.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    listing.buildClient();
    return listing;
  }
}
