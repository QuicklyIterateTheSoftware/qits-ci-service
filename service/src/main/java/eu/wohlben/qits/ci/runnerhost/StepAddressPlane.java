package eu.wohlben.qits.ci.runnerhost;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * <b>Every address a step container is told</b>, as one value, composed from the platform's public
 * domain and nothing else.
 *
 * <p><b>One plane.</b> A step runs on a runner outside the swarm: no qits-net and no internal DNS,
 * so every address is the PUBLIC name of the service that answers it, {@code
 * https://<host>.qits.<domain>}, reached through the platform edge with the run's {@code ci-run}
 * token. There is no docker network to join and no extra host to add. The second plane this record
 * used to describe — a step on qits-net, dialling each service by its wire alias — was deleted in
 * qits-515 (epic qits-444) together with every config key that spelled one of those aliases for a
 * step.
 *
 * <p><b>The public names are {@code RunnerAddresses}', not a second composition.</b> A runner is told
 * where qits-ci and qits-idp are by that class; the steps it starts are told where every other service
 * is by the same {@code publicOrigin}, derived from the platform's {@code QITS_DOMAIN} the way qits-idp
 * composes its own origin. The platform project carries no environment label ({@code
 * ci.dev.qits.wohlben.eu} answers 404 on the live estate).
 *
 * <p><b>The paths are the services' own routes, spelled here as constants.</b> Each was the path of
 * a config key's shipped default while those keys existed; an address was then that key's value with
 * its origin swapped. With the keys gone the path is the only part left, and it is a fact of the
 * service that serves it rather than of a deployment.
 *
 * <p>Pure: strings in, strings out, no I/O and no config of its own.
 */
public record StepAddressPlane(
    /** {@code $QITS_CI_DAEMON_URL}: the {@code /ci/daemon} socket, {@code wss://}. */
    String daemonUrl,
    /** qits-artifacts' public origin; a run's pinned daemon path is resolved under it. */
    String daemonBinaryOrigin,
    /** The git host's base; {@code $QITS_CI_REPOSITORY_URL} is this plus {@code /git/…}. */
    String gitBaseUrl,
    /** {@code $QITS_REGISTRY} and {@code $QITS_BUILD_REGISTRY}: a host and port, no scheme. */
    String registryHost,
    String npmHostedUrl,
    String npmProxyUrl,
    String mavenRegistryUrl,
    /** {@code $QITS_MAVEN_CENTRAL_MIRROR_URL} and {@code $QITS_MAVEN_PROXY_URL}, mirror on. */
    String mavenCentralMirrorUrl,
    String docsUrl,
    /** {@code $QITS_ARTIFACTS_URL}: the store's origin, no path. */
    String artifactsUrl,
    String workspacesUrl,
    /** Every host the run's docker {@code config.json} carries a login for. */
    List<String> authHosts,
    /** Which registry hosts are the platform's own, and where a step image naming one is pulled. */
    ImageRegistries imageRegistries) {

  /** {@code CiDaemonSocket}'s literal: the path every step container's daemon dials. */
  static final String DAEMON_SOCKET_PATH = "/ci/daemon";

  /**
   * qits-artifacts' daemons store, under which a run's pinned binary is {@code
   * qits-ci-daemon/<version>} — the route {@code CiDaemonPinIT} downloads the pin from.
   */
  static final String DAEMON_BINARY_PATH = "/artifacts/daemons/";

  /** qits-artifacts' hosted npm repository, the one {@code @qits/*} is published to. */
  static final String NPM_HOSTED_PATH = "/artifacts/npm/npm/";

  /** qits-mirror's npm pull-through cache. */
  static final String NPM_PROXY_PATH = "/npm/npmjs/";

  /** qits-artifacts' hosted Maven repository. */
  static final String MAVEN_REGISTRY_PATH = "/artifacts/maven/maven";

  /** qits-mirror's Maven Central pull-through. */
  static final String MAVEN_CENTRAL_MIRROR_PATH = "/mirror/maven/central";

  /** qits-artifacts' docs repository, the {@code docs} namespace segment included. */
  static final String DOCS_PATH = "/artifacts/docs/docs";

  public StepAddressPlane {
    authHosts = List.copyOf(authHosts);
    Objects.requireNonNull(imageRegistries, "imageRegistries");
  }

  /**
   * <b>The platform's own spellings of its two image stores</b>, and the public host a step image
   * naming one of them is pulled from (qits-479).
   *
   * <p>A step's image is the one address a step names that is not in its environment: it is the
   * spec's {@code image}, pulled by the runner's own docker. A run pins a platform image under
   * {@code qits.artifacts.registry-host}, and a recipe may name {@code
   * registry.<env>.localhost:<port>}; on the runner's machine neither resolves to the platform, so
   * the reference has its registry host moved to the public vhost of the same store, the repository
   * path, tag and {@code @sha256:} digest kept byte for byte: a digest is content-addressed, so it
   * names the same bytes at either address.
   *
   * <p><b>Ours is decided by the host and nothing else</b>, {@code HttpImagePins}' rule. A host is
   * the registry's when it is {@code qits.artifacts.registry-host}, one of {@code
   * qits.ci.runner.registry-mirrors.registry-hosts}, or qits-artifacts' alias in this environment;
   * it is qits-mirror's when it is one of {@code
   * qits.ci.runner.registry-mirrors.mirror-hosts} or the mirror's alias in this environment.
   * Anything else — {@code alpine:3}, {@code docker.io/…}, {@code ghcr.io/…} — is somebody else's
   * store and is pulled as named.
   *
   * @param registrySpellings every host that names qits-artifacts' registry, lower case
   * @param mirrorSpellings every host that names qits-mirror, lower case
   * @param registryTarget the host a registry image is pulled from; null keeps the reference as is
   * @param mirrorTarget the host a mirror image is pulled from; null keeps the reference as is
   */
  public record ImageRegistries(
      List<String> registrySpellings,
      List<String> mirrorSpellings,
      String registryTarget,
      String mirrorTarget) {

    public ImageRegistries {
      registrySpellings = List.copyOf(registrySpellings);
      mirrorSpellings = List.copyOf(mirrorSpellings);
    }

    /**
     * The spellings of the two stores. A host both lists could claim is the registry's — it is read
     * first.
     */
    public static ImageRegistries of(List<String> registryHosts, List<String> mirrorHosts) {
      List<String> registry = new ArrayList<>();
      List<String> mirror = new ArrayList<>();
      registryHosts.forEach(host -> addHost(registry, List.of(), host));
      mirrorHosts.forEach(host -> addHost(mirror, registry, host));
      return new ImageRegistries(registry, mirror, null, null);
    }

    /** The same spellings, pulled from {@code registry} and {@code mirror}. */
    ImageRegistries pulledFrom(String registry, String mirror) {
      return new ImageRegistries(registrySpellings, mirrorSpellings, registry, mirror);
    }

    /**
     * {@code image} as this plane pulls it: its registry host swapped for the target when it names
     * one of the platform's stores and this plane has a target for it, otherwise exactly {@code
     * image}.
     */
    public String rewrite(String image) {
      String host = registryOf(image);
      if (host == null) {
        return image;
      }
      String target = targetOf(host);
      return target == null ? image : target + image.substring(host.length());
    }

    /**
     * The host {@code image} is pulled from when it is one of the platform's stores this plane has
     * moved, else null — the host a pull has to hold a login for.
     */
    public String pullHost(String image) {
      String host = registryOf(image);
      return host == null ? null : targetOf(host);
    }

    private String targetOf(String host) {
      String folded = host.toLowerCase(Locale.ROOT);
      if (registrySpellings.contains(folded)) {
        return registryTarget;
      }
      if (mirrorSpellings.contains(folded)) {
        return mirrorTarget;
      }
      return null;
    }

    /** The registry authority a reference names — its first path segment — or null for none. */
    private static String registryOf(String image) {
      if (image == null) {
        return null;
      }
      int slash = image.indexOf('/');
      return slash <= 0 ? null : image.substring(0, slash);
    }

    private static void addHost(List<String> into, List<String> claimed, String host) {
      if (host == null || host.isBlank()) {
        return;
      }
      String folded = host.trim().toLowerCase(Locale.ROOT);
      if (!into.contains(folded) && !claimed.contains(folded)) {
        into.add(folded);
      }
    }
  }

  /**
   * The public origin of each service a step reaches, {@code https://<host>.qits.<domain>} — what
   * {@code RunnerAddresses.edgeOrigins} answers when the platform's domain is known.
   */
  public record EdgeOrigins(
      String ci, String artifacts, String mirror, String githost, String workspaces) {}

  /**
   * The addresses of a step, from the public origin of each service that answers one. qits-artifacts
   * answers the registry, the hosted npm and maven roots, the docs store and the daemon binary;
   * qits-mirror the npm and the maven pull-through roots; qits-githost the clone url;
   * qits-workspaces its own root; qits-ci the daemon socket.
   *
   * @param spellings the hosts that name the platform's two image stores, which an image reference
   *     is recognised by; this plane pulls them from the public registry and mirror hosts
   */
  public static StepAddressPlane of(EdgeOrigins origins, ImageRegistries spellings) {
    Objects.requireNonNull(origins, "origins");
    String artifacts = strip(origins.artifacts());
    String mirrorOrigin = strip(origins.mirror());
    String registry = hostOf(origins.artifacts());
    List<String> authHosts = new ArrayList<>();
    authHosts.add(registry);
    String mirror = hostOf(origins.mirror());
    if (!mirror.equals(registry)) {
      authHosts.add(mirror);
    }
    return new StepAddressPlane(
        socketOrigin(origins.ci()) + DAEMON_SOCKET_PATH,
        artifacts,
        strip(origins.githost()),
        registry,
        artifacts + NPM_HOSTED_PATH,
        mirrorOrigin + NPM_PROXY_PATH,
        artifacts + MAVEN_REGISTRY_PATH,
        mirrorOrigin + MAVEN_CENTRAL_MIRROR_PATH,
        artifacts + DOCS_PATH,
        artifacts,
        strip(origins.workspaces()),
        authHosts,
        spellings.pulledFrom(registry, mirror));
  }

  /**
   * The reference a step's image is pulled by — see {@link ImageRegistries}: a platform registry
   * host moved to its public vhost, anything else exactly as given.
   */
  public String imageReference(String image) {
    return imageRegistries.rewrite(image);
  }

  /**
   * The host a pull of {@code image} needs a login for, or null when it needs none of the platform's
   * — an image on somebody else's registry.
   */
  public String imagePullHost(String image) {
    return imageRegistries.pullHost(image);
  }

  /**
   * The path a run pins its daemon binary at: {@code /artifacts/daemons/<name>/<version>}. A path
   * and no origin, because the pin is taken once per run and must not need a domain to be taken;
   * {@link #daemonBinaryUrl} puts it under the public registry when a step is composed.
   */
  public static String daemonBinaryPath(String daemonName, String version) {
    return DAEMON_BINARY_PATH + daemonName + "/" + (version == null ? "" : version);
  }

  /**
   * The url this step downloads its daemon from: the run's pinned path under qits-artifacts' public
   * origin. A pinned value that is a whole url — a run pinned before qits-515 and re-run after it —
   * keeps its path and loses its origin.
   */
  public String daemonBinaryUrl(String pinned) {
    if (blank(pinned)) {
      return "";
    }
    return pinned.trim().startsWith("/") ? daemonBinaryOrigin + pinned.trim()
        : rebase(pinned, daemonBinaryOrigin);
  }

  /**
   * {@code url}'s path (and query) under {@code origin}. Empty stays empty; a url with no scheme to
   * read is refused, because guessing which part of it is the path would put a step on an address
   * nobody composed.
   */
  static String rebase(String url, String origin) {
    if (blank(url)) {
      return url == null ? "" : url;
    }
    URI parsed;
    try {
      parsed = URI.create(url.trim());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("not an absolute url, so it has no origin to move: " + url);
    }
    if (parsed.getScheme() == null || parsed.getRawAuthority() == null) {
      throw new IllegalStateException("not an absolute url, so it has no origin to move: " + url);
    }
    String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
    String query = parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery();
    return strip(origin) + path + query;
  }

  /** {@code https://h} → {@code wss://h}, {@code http://h} → {@code ws://h}: the socket's scheme. */
  private static String socketOrigin(String origin) {
    String o = strip(origin);
    if (o.startsWith("https://")) {
      return "wss://" + o.substring("https://".length());
    }
    if (o.startsWith("http://")) {
      return "ws://" + o.substring("http://".length());
    }
    return o;
  }

  /** The authority of an origin — what a docker login and an image reference name a registry by. */
  static String hostOf(String origin) {
    URI parsed = URI.create(strip(origin));
    if (parsed.getRawAuthority() == null) {
      throw new IllegalStateException("not an origin: " + origin);
    }
    return parsed.getRawAuthority();
  }

  private static String strip(String origin) {
    return origin == null ? "" : origin.trim().replaceAll("/+$", "");
  }

  private static boolean blank(String text) {
    return text == null || text.isBlank();
  }
}
