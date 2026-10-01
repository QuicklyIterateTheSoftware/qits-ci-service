package eu.wohlben.qits.ci.registry;

import java.net.URI;
import java.util.Optional;

/**
 * Where qits-ci ITSELF reaches qits-artifacts from inside the swarm — one derivation, shared by
 * {@link HttpImagePins} and {@link HttpArtifactPresence} so the two can never dial two stores.
 *
 * <p>{@code qits.artifacts.url} when a deployment states one, otherwise the scheme and authority of
 * {@code qits.artifacts.maven.registry-url}, which is set on every live deployment. Never a key of
 * its own: a second key is a second thing to keep in step with the first.
 */
final class ArtifactsOrigin {

  private ArtifactsOrigin() {}

  /** The origin, without a trailing slash, or {@code ""} when neither value states one. */
  static String of(Optional<String> artifactsUrl, String mavenRegistryUrl) {
    String explicit = explicit(artifactsUrl);
    if (explicit != null) {
      return explicit;
    }
    String fromMaven = originOf(mavenRegistryUrl);
    return fromMaven == null ? "" : fromMaven;
  }

  /** {@code qits.artifacts.url} trimmed of trailing slashes, or null when it is unset or blank. */
  static String explicit(Optional<String> artifactsUrl) {
    String explicit = artifactsUrl == null ? null : artifactsUrl.orElse(null);
    if (explicit == null || explicit.isBlank()) {
      return null;
    }
    return explicit.trim().replaceAll("/+$", "");
  }

  /** The scheme and authority of a url, or null when it has neither. */
  private static String originOf(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      URI uri = URI.create(url.trim());
      if (uri.getScheme() == null || uri.getRawAuthority() == null) {
        return null;
      }
      return uri.getScheme() + "://" + uri.getRawAuthority();
    } catch (RuntimeException badUrl) {
      return null;
    }
  }
}
