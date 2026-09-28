package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>Every address a step container is told</b>, as one value — so which network a step stands on is
 * one decision, made once, rather than a question each line of {@link StepWorkloadSpecs} answers for
 * itself.
 *
 * <p><b>Two planes, and a step is on exactly one.</b> {@link #internal} is the step every run on the
 * estate has always been: a container on {@code qits-net}, dialling each service by its wire alias,
 * with the host gateway as an extra host. It reads exactly the keys the launcher always read and
 * produces byte for byte the values it always produced — {@code StepEnvironmentCharacterizationTest}
 * pins a whole environment to hold that. {@link #edge} is a step on a runner outside the swarm (epic
 * qits-441): no qits-net and no internal DNS, so every address is the PUBLIC name of the same service
 * — the origin swapped for {@code https://<host>.qits.<domain>}, the path kept — and there is no
 * network and no extra host to ask for.
 *
 * <p><b>The public names are {@code RunnerAddresses}', not a second composition.</b> A runner is told
 * where qits-ci and qits-idp are by that class; the steps it starts are told where every other service
 * is by the same {@code publicOrigin}, derived from the platform's {@code QITS_DOMAIN} the way qits-idp
 * composes its own origin. The epic spelled these {@code <label>.<env>.<domain>} under two new keys;
 * the platform project carries no environment label ({@code ci.dev.qits.wohlben.eu} answers 404 on the
 * live estate), and {@code qits.ci.domain} already is {@code QITS_DOMAIN}, so neither key exists.
 *
 * <p><b>The daemon binary is the one address that is not a deployment fact.</b> Its url is pinned per
 * run ({@code CiStepRunner.pinDaemon}) so every step downloads the same build; {@link #daemonBinaryUrl}
 * keeps that pinned url as it is on the internal plane and moves only its origin on the edge one.
 *
 * <p>Pure: strings in, strings out, no I/O and no config of its own.
 */
public record StepAddressPlane(
    CiRunnerPlane plane,
    /** {@code $QITS_CI_DAEMON_URL}: the {@code /ci/daemon} socket, {@code ws://} or {@code wss://}. */
    String daemonUrl,
    /** Null keeps the run's pinned binary url as it is; set, it replaces that url's origin. */
    String daemonBinaryOrigin,
    /** The git host's base; {@code $QITS_CI_REPOSITORY_URL} is this plus {@code /git/…}. */
    String gitBaseUrl,
    /** The idp a commissioned client's helper mints at; null on a plane that carries no client. */
    String idpUrl,
    /** {@code $QITS_REGISTRY}: a host and port, no scheme. */
    String registryHost,
    /** {@code $QITS_BUILD_REGISTRY} when BuildKit is on: a host and port, no scheme. */
    String buildRegistryHost,
    String npmHostedUrl,
    String npmProxyUrl,
    String mavenRegistryUrl,
    /** {@code $QITS_MAVEN_CENTRAL_MIRROR_URL} when the mirror is on; may be empty. */
    String mavenCentralMirrorBuildUrl,
    /** {@code $QITS_MAVEN_PROXY_URL} when the mirror is on. */
    String mavenCentralMirrorStepUrl,
    String docsUrl,
    /** {@code $QITS_ARTIFACTS_URL}: the store's origin, no path; may be empty. */
    String artifactsUrl,
    String workspacesUrl,
    /** Every host the run's docker {@code config.json} carries a login for. */
    List<String> authHosts,
    /** The docker network the container joins; null on the edge plane, where there is none. */
    String network,
    /** {@code --add-host} entries; empty on the edge plane. */
    List<String> extraHosts) {

  /** The one extra host an internal step has always been given. */
  static final String HOST_GATEWAY = "host.docker.internal:host-gateway";

  public StepAddressPlane {
    Objects.requireNonNull(plane, "plane");
    authHosts = List.copyOf(authHosts);
    extraHosts = List.copyOf(extraHosts);
  }

  /**
   * The public origin of each service a step reaches, {@code https://<host>.qits.<domain>} — what
   * {@code RunnerAddresses.edgeOrigins} answers when the platform's domain is known.
   */
  public record EdgeOrigins(
      String ci, String artifacts, String mirror, String githost, String workspaces) {}

  /**
   * Today's addresses, from today's keys and nothing else — the arguments are the launcher's own
   * fields, resolved the way it always resolved them ({@code artifactsUrl} is its {@code
   * resolvedArtifactsUrl()}, {@code authHosts} its {@code authHosts()}).
   */
  public static StepAddressPlane internal(
      String containerDaemonUrl,
      String containerGitUrl,
      String idpUrl,
      String artifactsRegistryHost,
      String buildkitRegistryHost,
      String npmHostedUrl,
      String npmProxyUrl,
      String mavenRegistryUrl,
      String mavenCentralMirrorBuildUrl,
      String mavenCentralMirrorStepUrl,
      String docsUrl,
      String artifactsUrl,
      String workspacesUrl,
      List<String> authHosts,
      String network) {
    return new StepAddressPlane(
        CiRunnerPlane.INTERNAL,
        containerDaemonUrl,
        null,
        containerGitUrl,
        idpUrl,
        artifactsRegistryHost,
        buildkitRegistryHost,
        npmHostedUrl,
        npmProxyUrl,
        mavenRegistryUrl,
        mavenCentralMirrorBuildUrl,
        mavenCentralMirrorStepUrl,
        docsUrl,
        artifactsUrl,
        workspacesUrl,
        authHosts,
        network,
        List.of(HOST_GATEWAY));
  }

  /**
   * The same step, through the edge: each internal address's origin swapped for the public origin
   * of the service that answers it, the path kept. qits-artifacts answers the registry, the hosted
   * npm and maven roots, the docs store and the daemon binary; qits-platform-mirror the two
   * pull-through roots; qits-githost the clone url; qits-workspaces its own root. No network, no
   * extra host, and no idp — an edge step carries a {@code ci-run} token, never a client to mint with.
   *
   * <p>A value that is empty on the internal plane stays empty: the mirror's off state is an empty
   * value, and it means the same thing on either side of the edge.
   */
  public static StepAddressPlane edge(EdgeOrigins origins, StepAddressPlane internal) {
    Objects.requireNonNull(origins, "origins");
    String registry = hostOf(origins.artifacts());
    List<String> authHosts = new ArrayList<>();
    authHosts.add(registry);
    String mirror = hostOf(origins.mirror());
    if (!mirror.equals(registry)) {
      authHosts.add(mirror);
    }
    return new StepAddressPlane(
        CiRunnerPlane.EDGE,
        rebase(internal.daemonUrl(), socketOrigin(origins.ci())),
        origins.artifacts(),
        rebase(internal.gitBaseUrl(), origins.githost()),
        null,
        registry,
        registry,
        rebase(internal.npmHostedUrl(), origins.artifacts()),
        rebase(internal.npmProxyUrl(), origins.mirror()),
        rebase(internal.mavenRegistryUrl(), origins.artifacts()),
        rebase(internal.mavenCentralMirrorBuildUrl(), origins.mirror()),
        rebase(internal.mavenCentralMirrorStepUrl(), origins.mirror()),
        rebase(internal.docsUrl(), origins.artifacts()),
        blank(internal.artifactsUrl()) ? "" : strip(origins.artifacts()),
        rebase(internal.workspacesUrl(), origins.workspaces()),
        authHosts,
        null,
        List.of());
  }

  /** Whether this is the edge plane — a step on a runner outside the swarm. */
  public boolean isEdge() {
    return plane == CiRunnerPlane.EDGE;
  }

  /**
   * The url this step downloads its daemon from: the run's pinned url, on the internal plane as it
   * is, on the edge plane with its origin moved to the public registry and its path — {@code
   * /artifacts/daemons/qits-ci-daemon/<version>} — kept.
   */
  public String daemonBinaryUrl(String pinned) {
    return daemonBinaryOrigin == null ? pinned : rebase(pinned, daemonBinaryOrigin);
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
