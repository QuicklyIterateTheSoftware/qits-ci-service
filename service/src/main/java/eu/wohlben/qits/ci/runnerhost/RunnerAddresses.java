package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Every address a runner is told: where it reaches qits-ci (and so its socket), where it asks
 * qits-idp for a token and for which audience, and which registry its runner image is pulled from.
 * One class, because the register door's answer, the install line and the install script have to
 * name the same things, and two compositions of one address are two chances for them to disagree.
 *
 * <p><b>A runner goes through the public edge.</b> It is a machine a person owns, not on the swarm:
 * no qits-net, no internal DNS, so an internal alias names nothing it can resolve. Every address is
 * therefore a public name, composed from the platform's domain ({@code QITS_DOMAIN}, which
 * qits-deployments writes into every container) exactly as qits-idp composes its canonical origin
 * ({@code PlatformDomain.canonicalOrigin}: {@code https://idp.qits.<domain>}) and the edge its door
 * ({@code EdgeSessions.canonicalOrigin}: {@code https://qits.<domain>}) — {@code
 * https://<host>.<project>.<domain>} with the platform's own project {@link #PLATFORM_PROJECT}.
 *
 * <p><b>There is no environment label, and that is the grammar, not an omission.</b> The edge reads
 * {@code <app>[.<env>].<project>.<domain>} right to left, and the env label is present exactly when
 * the project supports environments; the platform project does not, so its applications are served
 * at {@code <app>.qits.<domain>} whatever {@code QITS_ENVIRONMENT} says. With {@code
 * QITS_DOMAIN=wohlben.eu} that is {@code https://ci.qits.wohlben.eu}, {@code
 * https://idp.qits.wohlben.eu/idp/token} and {@code https://registry.qits.wohlben.eu} — measured live,
 * while {@code ci.dev.qits.wohlben.eu} answers 404.
 *
 * <p><b>Each address has an override, and an internal fallback.</b> {@code
 * qits.ci.runner.public-url}, {@code qits.ci.runner.token-url} and {@code
 * qits.ci.runner.artifacts-url} replace a derivation outright when set. With no public domain — none
 * stated, or a single label such as {@code localhost}, the test {@code PlatformDomain} uses to tell a
 * clone from an installation — the addresses fall back to the qits-net ones: {@code
 * qits.ci.runner.internal-url}, the idp {@code quarkus.oidc-client.qits.auth-server-url} names plus
 * {@code /token}, {@code qits.ci.runner.artifacts-internal-url}, and for the registry the runner
 * image is pulled from, {@code qits.artifacts.registry-host}. Only a runner on the platform's own
 * host can use those, and the first composition that falls back says so in a WARN.
 *
 * <p><b>An {@code INTERNAL} runner is the exception, and is told the qits-net addresses whatever
 * the domain.</b> Its row says it is on qits-net, beside this service, and there the public names
 * are the wrong ones: a platform being bootstrapped has a domain and no edge yet, so a public
 * address names a door nothing answers. The methods taking a {@link CiRunnerPlane} are what a
 * particular runner is told — {@code INTERNAL} the internal url, the idp's own token endpoint and
 * the platform host's registry spelling, with no override read and no WARN, since nothing fell
 * back; {@code EDGE} the plane-less composition above, unchanged. The plane-less methods remain
 * for what is told to no runner in particular: the generic install script.
 */
@ApplicationScoped
public class RunnerAddresses {

  private static final Logger LOG = Logger.getLogger(RunnerAddresses.class);

  /** The one audience every platform token is requested with. */
  public static final String AUDIENCE = "qits-platform";

  /**
   * The runner socket's path — a literal, like {@code /ci/daemon}: a {@code @WebSocket} path does
   * not follow {@code quarkus.rest.path}, so it carries the {@code /ci} segment itself.
   */
  public static final String SOCKET_PATH = "/ci/runners/socket";

  /**
   * The platform's own project, whose applications every public name here belongs to — qits-idp's
   * {@code PlatformDomain.PROJECT}, the edge's {@code EdgeSessions.PLATFORM_PROJECT} and the
   * bootstrap's {@code PlatformModel.PROJECT}, the same constant of the platform.
   */
  static final String PLATFORM_PROJECT = "qits";

  /** qits-ci's host label: derived from the application {@code qits-ci}, no {@code host:} line. */
  static final String CI_HOST = "ci";

  /** qits-idp's host label, its {@code PlatformDomain.HOST}; the token endpoint is under /idp. */
  static final String IDP_HOST = "idp";

  /** qits-artifacts' host label — {@code host: registry} in its deployments.yml. */
  static final String ARTIFACTS_HOST = "registry";

  /**
   * The runner image's repository under the registry: the platform's image namespace ({@code
   * qits.artifacts.image-repository}'s value) and the runner's name — the path the runner
   * repository's release pushes to. Fixed rather than read from that key, because that release
   * pushes to exactly this path whatever this deployment's key says.
   */
  static final String RUNNER_IMAGE_REPOSITORY = "qits/" + CiRunnerPins.RUNNER_NAME;

  /** qits-platform-mirror's host label: the pull-through caches, {@code /v2} and {@code /mirror}. */
  static final String MIRROR_HOST = "mirror";

  /** qits-githost's host label; smart HTTP answers a bearer under {@code /git/<project>/<repo>}. */
  static final String GITHOST_HOST = "githost";

  /** qits-workspaces' host label, where a step asks for its own repository to be released. */
  static final String WORKSPACES_HOST = "workspaces";

  @ConfigProperty(name = "qits.ci.domain")
  Optional<String> domain;

  @ConfigProperty(name = "qits.ci.runner.internal-url")
  String internalUrl;

  /** Blank is unset: SmallRye reads an empty property as an absent Optional. */
  @ConfigProperty(name = "qits.ci.runner.public-url")
  Optional<String> publicUrl;

  @ConfigProperty(name = "quarkus.oidc-client.qits.auth-server-url")
  String idpUrl;

  /** Blank is unset, as {@link #publicUrl} is. */
  @ConfigProperty(name = "qits.ci.runner.token-url")
  Optional<String> tokenUrlOverride;

  @ConfigProperty(name = "qits.ci.runner.artifacts-internal-url")
  String artifactsInternalUrl;

  /** Blank is unset, as {@link #publicUrl} is. */
  @ConfigProperty(name = "qits.ci.runner.artifacts-url")
  Optional<String> artifactsUrl;

  /**
   * The registry as the platform host's own docker names it — {@code StepContainerSettings}' key, read
   * here only as the no-domain fallback of {@link #registryHost()}.
   */
  @ConfigProperty(name = "qits.artifacts.registry-host")
  String registryInternalHost;

  private final AtomicBoolean warned = new AtomicBoolean();

  /** The base a runner reaches qits-ci at: scheme, host and port, no trailing slash. */
  public String ciBase() {
    return stripSlashes(
        set(publicUrl).or(() -> publicOrigin(CI_HOST)).orElseGet(() -> internal(internalUrl)));
  }

  /** {@link #ciBase()} for a runner on {@code plane}: an INTERNAL one dials qits-ci on qits-net. */
  public String ciBase(CiRunnerPlane plane) {
    return plane == CiRunnerPlane.INTERNAL ? stripSlashes(onNet(internalUrl)) : ciBase();
  }

  /** {@code ws://} or {@code wss://} after {@link #ciBase()}'s own scheme, plus {@link #SOCKET_PATH}. */
  public String socketUrl() {
    return socketUrl(ciBase());
  }

  /** {@link #socketUrl()} for a runner on {@code plane}, after {@link #ciBase(CiRunnerPlane)}. */
  public String socketUrl(CiRunnerPlane plane) {
    return socketUrl(ciBase(plane));
  }

  private static String socketUrl(String base) {
    String socketBase;
    if (base.startsWith("https://")) {
      socketBase = "wss://" + base.substring("https://".length());
    } else if (base.startsWith("http://")) {
      socketBase = "ws://" + base.substring("http://".length());
    } else {
      socketBase = base;
    }
    return socketBase + SOCKET_PATH;
  }

  /** The idp token endpoint a runner mints its bearer at. */
  public String tokenUrl() {
    return set(tokenUrlOverride)
        .map(RunnerAddresses::stripSlashes)
        .or(() -> publicOrigin(IDP_HOST).map(origin -> origin + "/idp/token"))
        .orElseGet(() -> stripSlashes(internal(idpUrl)) + "/token");
  }

  /**
   * {@link #tokenUrl()} for a runner on {@code plane}: an INTERNAL one mints where this service
   * itself does, {@code quarkus.oidc-client.qits.auth-server-url} plus {@code /token}.
   */
  public String tokenUrl(CiRunnerPlane plane) {
    return plane == CiRunnerPlane.INTERNAL ? stripSlashes(onNet(idpUrl)) + "/token" : tokenUrl();
  }

  /**
   * qits-artifacts as a runner reaches it over HTTP: scheme, host and port, no trailing slash. The
   * install script used to download the runner binary under it; since the runner is an image
   * (qits-484) nothing a runner is told is composed from it any more except, through {@link
   * #registryHost()}, its authority — the same store's {@code /v2}.
   */
  public String artifactsBase() {
    return stripSlashes(
        set(artifactsUrl)
            .or(() -> publicOrigin(ARTIFACTS_HOST))
            .orElseGet(() -> internal(artifactsInternalUrl)));
  }

  /**
   * The registry host a runner's docker pulls the runner image from — {@code host[:port]}, no scheme:
   * the authority of {@link #artifactsBase()}'s override or public origin, which is the same store
   * ({@code registry.qits.<domain>} serves both {@code /artifacts} and {@code /v2}), and with no
   * public domain {@code qits.artifacts.registry-host}, the name the platform host's own docker
   * pulls under. Not the {@code qits.ci.runner.artifacts-internal-url} alias: that is a qits-net name
   * a docker daemon cannot resolve, whereas the registry-host key is a docker's view by definition.
   */
  public String registryHost() {
    return set(artifactsUrl)
        .or(() -> publicOrigin(ARTIFACTS_HOST))
        .map(RunnerAddresses::authority)
        .orElseGet(() -> internal(registryInternalHost));
  }

  /**
   * {@link #registryHost()} for a runner on {@code plane}: an INTERNAL one's docker is the platform
   * host's own, so it pulls under {@code qits.artifacts.registry-host} — the name that daemon
   * already pulls every step image under — and never under a public name it may hold no
   * certificate for.
   */
  public String registryHost(CiRunnerPlane plane) {
    return plane == CiRunnerPlane.INTERNAL ? onNet(registryInternalHost) : registryHost();
  }

  /**
   * The runner image of {@code version}: {@code <registryHost>/qits/qits-ci-runner:<version>}. One
   * composition for the install script's {@code docker pull} and the {@code Upgrade} frame's {@code
   * image}, so the container a person started and the one it replaces itself with come from one
   * name.
   */
  public String runnerImage(String version) {
    return registryHost() + "/" + RUNNER_IMAGE_REPOSITORY + ":" + version;
  }

  /** {@link #runnerImage(String)} on {@link #registryHost(CiRunnerPlane)}: an {@code Upgrade}'s image. */
  public String runnerImage(CiRunnerPlane plane, String version) {
    return registryHost(plane) + "/" + RUNNER_IMAGE_REPOSITORY + ":" + version;
  }

  /**
   * The public origin of every service a step on an EDGE runner reaches, or empty when no public
   * domain is known — in which case an EDGE plane cannot be composed at all, and the runner door
   * refuses to declare one ({@code EDGE_PLANE_UNCONFIGURED}).
   *
   * <p><b>{@link #publicOrigin} and nothing else</b>, deliberately without the three runner overrides
   * above: those re-point what a <em>runner</em> is told, and a step is told the same domain's names
   * for five other services that have no override. One composition, so a runner and the steps it
   * starts can never be told two different domains.
   */
  public Optional<StepAddressPlane.EdgeOrigins> edgeOrigins() {
    return publicOrigin(CI_HOST)
        .map(
            ci ->
                new StepAddressPlane.EdgeOrigins(
                    ci,
                    publicOrigin(ARTIFACTS_HOST).orElseThrow(),
                    publicOrigin(MIRROR_HOST).orElseThrow(),
                    publicOrigin(GITHOST_HOST).orElseThrow(),
                    publicOrigin(WORKSPACES_HOST).orElseThrow()));
  }

  /** Whether a public domain is known, which is whether an EDGE plane can be composed. */
  public boolean edgeAvailable() {
    return publicOrigin(CI_HOST).isPresent();
  }

  /** {@link #AUDIENCE}, as a method so a caller holding this bean needs nothing else. */
  public String audience() {
    return AUDIENCE;
  }

  /**
   * {@code https://<host>.qits.<domain>} when a public domain is stated, else empty. A domain with no
   * dot in it is a developer's single-label {@code localhost}, which names no public host.
   */
  Optional<String> publicOrigin(String host) {
    return set(domain)
        .map(value -> value.toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", ""))
        .filter(value -> value.indexOf('.') > 0)
        .map(value -> "https://" + host + "." + PLATFORM_PROJECT + "." + value);
  }

  /** An internal alias, with the one WARN that says what it costs. */
  private String internal(String url) {
    if (warned.compareAndSet(false, true)) {
      LOG.warnf(
          "qits-ci knows no public domain (QITS_DOMAIN is '%s'), so it tells runners the internal"
              + " qits-net addresses (%s); a runner outside the swarm cannot resolve them. Set"
              + " QITS_DOMAIN, or qits.ci.runner.public-url, qits.ci.runner.token-url and"
              + " qits.ci.runner.artifacts-url",
          set(domain).orElse(""),
          url);
    }
    return url == null ? "" : url.trim();
  }

  /** An internal address told to a runner that is on qits-net: the right one, so no WARN. */
  private static String onNet(String url) {
    return url == null ? "" : url.trim();
  }

  private static Optional<String> set(Optional<String> value) {
    return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
  }

  /** {@code host[:port]} of an {@code http(s)://} url; the value itself when it has no scheme. */
  private static String authority(String url) {
    String rest = stripSlashes(url).replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
    int slash = rest.indexOf('/');
    return slash < 0 ? rest : rest.substring(0, slash);
  }

  private static String stripSlashes(String url) {
    return url.trim().replaceAll("/+$", "");
  }
}
