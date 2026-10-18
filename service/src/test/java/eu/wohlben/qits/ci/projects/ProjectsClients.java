package eu.wohlben.qits.ci.projects;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/**
 * {@link HttpProjectsRepoListing} wired by hand against one base url, for tests outside this
 * package — the consumer pacts (qits-1149) point it at a pact mock server.
 */
public final class ProjectsClients {

  private ProjectsClients() {}

  public static HttpProjectsRepoListing repoListing(String projectsUrl) {
    HttpProjectsRepoListing listing = new HttpProjectsRepoListing();
    listing.projectsUrl = Optional.of(projectsUrl);
    listing.objectMapper = new ObjectMapper();
    listing.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    listing.buildClient();
    return listing;
  }
}
