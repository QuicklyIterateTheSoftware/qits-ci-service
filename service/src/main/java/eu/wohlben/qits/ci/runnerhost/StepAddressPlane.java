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
    /**
     * {@code $QITS_DOMAIN}: the bare public domain every address below is composed from, the one
     * input a recipe derives its own registry and mirror addresses from (qits-731).
     */
    String domain,
    /** {@code $QITS_CI_DAEMON_URL}: the {@code /ci/daemon} socket, {@code wss://}. */
    String daemonUrl,
    /** qits-artifacts' public origin; a run's pinned daemon path is resolved under it. */
    String daemonBinaryOrigin,
    /** The git host's base; {@code $QITS_CI_REPOSITORY_URL} is this plus {@code /git/…}. */
    String gitBaseUrl,
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

  /**
   * The registry's public host and the hosted Maven repository's path under it — {@code
   * $QITS_MAVEN_REPOSITORY_URL} (qits-896). Every repository's committed {@code
   * .qits-maven-settings.xml} reads it as {@code ${env.QITS_MAVEN_REPOSITORY_URL}}, the mirror for
   * server id {@code qits-maven}.
   */
  static final String MAVEN_REPOSITORY_HOST = "registry.qits.";

  static final String MAVEN_REPOSITORY_PATH = "/artifacts/maven/maven";

  /**
   * The mirror's public host and the Maven Central cache's path under it — {@code
   * $QITS_MAVEN_CENTRAL_URL} (qits-896). Every repository's committed {@code
   * .qits-maven-settings.xml} activates its central-proxy profile on it being non-empty.
   */
  static final String MAVEN_CENTRAL_HOST = "mirror.qits.";

  static final String MAVEN_CENTRAL_PATH = "/mirror/maven/central";

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
   * {@code RunnerAddresses.edgeOrigins} answers when the platform's domain is known — and that
   * domain itself.
   */
  public record EdgeOrigins(
      String domain, String ci, String artifacts, String mirror, String githost, String workspaces) {}

  /**
   * The addresses of a step, from the public origin of each service that answers one. qits-artifacts
   * answers the daemon binary and, with qits-mirror, the registry logins and image pulls;
   * qits-githost the clone url; qits-workspaces its own root; qits-ci the daemon socket. The npm and
   * docs roots are not here: a step spells those itself under {@code $QITS_DOMAIN} (qits-731), and
   * no URL variable carries them. The two Maven roots are the one exception (qits-896): {@link
   * #mavenRepositoryUrl()} and {@link #mavenCentralUrl()} compose them here too, both still derived
   * from the domain alone, because every repository's committed {@code .qits-maven-settings.xml}
   * already reads them as {@code $QITS_MAVEN_REPOSITORY_URL}/{@code $QITS_MAVEN_CENTRAL_URL} and a
   * repository overriding an archetype's slots would otherwise have to re-derive both by hand.
   *
   * @param spellings the hosts that name the platform's two image stores, which an image reference
   *     is recognised by; this plane pulls them from the public registry and mirror hosts
   */
  public static StepAddressPlane of(EdgeOrigins origins, ImageRegistries spellings) {
    Objects.requireNonNull(origins, "origins");
    String artifacts = strip(origins.artifacts());
    String registry = hostOf(origins.artifacts());
    List<String> authHosts = new ArrayList<>();
    authHosts.add(registry);
    String mirror = hostOf(origins.mirror());
    if (!mirror.equals(registry)) {
      authHosts.add(mirror);
    }
    return new StepAddressPlane(
        origins.domain(),
        socketOrigin(origins.ci()) + DAEMON_SOCKET_PATH,
        artifacts,
        strip(origins.githost()),
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
   * {@code https://registry.qits.<domain>/artifacts/maven/maven} — the platform's hosted Maven
   * repository, the exact string a repository's own {@code .qits-maven-settings.xml} reads as
   * {@code ${env.QITS_MAVEN_REPOSITORY_URL}} (qits-896). Derived from {@link #domain()} and nothing
   * else — a host is code, not configuration, the rule qits-731 states for every address this plane
   * composes.
   */
  public String mavenRepositoryUrl() {
    return "https://" + MAVEN_REPOSITORY_HOST + domain + MAVEN_REPOSITORY_PATH;
  }

  /**
   * {@code https://mirror.qits.<domain>/mirror/maven/central} — the platform's Maven Central cache,
   * the exact string a repository's own {@code .qits-maven-settings.xml} reads as {@code
   * ${env.QITS_MAVEN_CENTRAL_URL}} to decide whether its central-proxy profile activates (qits-896).
   * Derived from {@link #domain()} and nothing else, {@link #mavenRepositoryUrl()}'s reason.
   */
  public String mavenCentralUrl() {
    return "https://" + MAVEN_CENTRAL_HOST + domain + MAVEN_CENTRAL_PATH;
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
