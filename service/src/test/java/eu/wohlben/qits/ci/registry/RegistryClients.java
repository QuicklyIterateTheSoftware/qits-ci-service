package eu.wohlben.qits.ci.registry;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/**
 * The qits-artifacts clients wired by hand against one origin, for tests outside this package —
 * the consumer pacts (qits-1149) point them at a pact mock server through {@code qits.artifacts.url}.
 */
public final class RegistryClients {

  private RegistryClients() {}

  /** {@link HttpImagePins} for images under {@code registryHost}, asking {@code artifactsUrl}. */
  public static HttpImagePins imagePins(String registryHost, String artifactsUrl) {
    HttpImagePins pins = new HttpImagePins();
    pins.artifactsRegistryHost = registryHost;
    pins.artifactsUrl = Optional.of(artifactsUrl);
    pins.artifactsMavenRegistryUrl = "";
    pins.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    pins.buildClient();
    return pins;
  }

  /** {@link HttpArtifactPresence} asking {@code artifactsUrl}. */
  public static HttpArtifactPresence presence(String artifactsUrl) {
    HttpArtifactPresence presence = new HttpArtifactPresence();
    presence.artifactsUrl = Optional.of(artifactsUrl);
    presence.artifactsMavenRegistryUrl = "";
    presence.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    presence.buildClient();
    return presence;
  }
}
