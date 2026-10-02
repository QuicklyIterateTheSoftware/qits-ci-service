package eu.wohlben.qits.ci.runnerhost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
 * <p><b>Each address has an override, and nothing to fall back to.</b> {@code
 * qits.ci.runner.public-url}, {@code qits.ci.runner.token-url} and {@code
 * qits.ci.runner.artifacts-url} replace a derivation outright when set. With no public domain — none
 * stated, or a single label such as {@code localhost}, the test {@code PlatformDomain} uses to tell a
 * clone from an installation — and no override, an address cannot be composed and asking for it
 * throws {@link UnconfiguredException}, which names the keys to set. The qits-net aliases this class
 * used to answer in that case ({@code qits.ci.runner.internal-url}, {@code
 * qits.ci.runner.artifacts-internal-url}, the idp's wire alias, {@code
 * qits.artifacts.registry-host}) were deleted with the internal plane (qits-515): a runner is
 * outside the swarm and can resolve none of them.
 */
@ApplicationScoped
public class RunnerAddresses {

  /**
   * An address a runner or a step has to be told cannot be composed: this qits-ci knows no public
   * domain and the address has no override. An {@link IllegalStateException}, which is what the
   * install script's own refusal is, so the doors that map one to a 503 map this one too.
   */
  public static final class UnconfiguredException extends IllegalStateException {
    UnconfiguredException(String what, String domain, String overrideKey) {
      super(
          "qits-ci knows no public domain (QITS_DOMAIN is '"
              + domain
              + "'), so it has no "
              + what
              + " to hand out; set QITS_DOMAIN"
              + (overrideKey == null ? "" : ", or " + overrideKey));
    }
  }

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

  /** qits-mirror's host label: the pull-through caches, {@code /v2} and {@code /mirror}. */
  static final String MIRROR_HOST = "mirror";

  /** qits-githost's host label; smart HTTP answers a bearer under {@code /git/<project>/<repo>}. */
  static final String GITHOST_HOST = "githost";

  /** qits-workspaces' host label, where a step asks for its own repository to be released. */
  static final String WORKSPACES_HOST = "workspaces";

  @ConfigProperty(name = "qits.ci.domain")
  Optional<String> domain;

  /** Blank is unset: SmallRye reads an empty property as an absent Optional. */
  @ConfigProperty(name = "qits.ci.runner.public-url")
  Optional<String> publicUrl;

  /** Blank is unset, as {@link #publicUrl} is. */
  @ConfigProperty(name = "qits.ci.runner.token-url")
  Optional<String> tokenUrlOverride;

  /** Blank is unset, as {@link #publicUrl} is. */
  @ConfigProperty(name = "qits.ci.runner.artifacts-url")
  Optional<String> artifactsUrl;

  /**
   * The base a runner reaches qits-ci at: scheme, host and port, no trailing slash.
   *
   * @throws UnconfiguredException with no public domain and no {@code qits.ci.runner.public-url}
   */
  public String ciBase() {
    return stripSlashes(
        set(publicUrl)
            .or(() -> publicOrigin(CI_HOST))
            .orElseThrow(() -> unconfigured("CI address", "qits.ci.runner.public-url")));
  }

  /** {@code ws://} or {@code wss://} after {@link #ciBase()}'s own scheme, plus {@link #SOCKET_PATH}. */
  public String socketUrl() {
    String base = ciBase();
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

  /**
   * The idp token endpoint a runner mints its bearer at.
   *
   * @throws UnconfiguredException with no public domain and no {@code qits.ci.runner.token-url}
   */
  public String tokenUrl() {
    return set(tokenUrlOverride)
        .map(RunnerAddresses::stripSlashes)
        .or(() -> publicOrigin(IDP_HOST).map(origin -> origin + "/idp/token"))
        .orElseThrow(() -> unconfigured("token endpoint", "qits.ci.runner.token-url"));
  }

  /**
   * The registry host a runner's docker pulls the runner image from — {@code host[:port]}, no scheme:
   * the authority of {@code qits.ci.runner.artifacts-url} when that override is set, else of
   * qits-artifacts' public origin ({@code registry.qits.<domain>} serves both {@code /artifacts} and
   * {@code /v2}).
   *
   * @throws UnconfiguredException with no public domain and no {@code qits.ci.runner.artifacts-url}
   */
  public String registryHost() {
    return set(artifactsUrl)
        .or(() -> publicOrigin(ARTIFACTS_HOST))
        .map(RunnerAddresses::authority)
        .orElseThrow(() -> unconfigured("registry host", "qits.ci.runner.artifacts-url"));
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

  /**
   * The public origin of every service a step reaches, or empty when no public domain is known — in
   * which case a step has no address to be told and is not launched ({@code
   * EDGE_PLANE_UNCONFIGURED}).
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

  /** Whether a public domain is known, which is whether a step's addresses can be composed. */
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

  private UnconfiguredException unconfigured(String what, String overrideKey) {
    return new UnconfiguredException(what, set(domain).orElse(""), overrideKey);
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
