package eu.wohlben.qits.ci.runnerhost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Every address a runner is told: where it reaches qits-ci (and so its socket), where it asks
 * qits-idp for a token and for which audience, and where its install script downloads the binary.
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
 * {@code /token}, and {@code qits.ci.runner.artifacts-internal-url}. Only a runner on qits-net can
 * use those, and the first composition that falls back says so in a WARN.
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

  private final AtomicBoolean warned = new AtomicBoolean();

  /** The base a runner reaches qits-ci at: scheme, host and port, no trailing slash. */
  public String ciBase() {
    return stripSlashes(
        set(publicUrl).or(() -> publicOrigin(CI_HOST)).orElseGet(() -> internal(internalUrl)));
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

  /** The idp token endpoint a runner mints its bearer at. */
  public String tokenUrl() {
    return set(tokenUrlOverride)
        .map(RunnerAddresses::stripSlashes)
        .or(() -> publicOrigin(IDP_HOST).map(origin -> origin + "/idp/token"))
        .orElseGet(() -> stripSlashes(internal(idpUrl)) + "/token");
  }

  /**
   * Where the install script downloads the binary from: scheme, host and port of qits-artifacts, no
   * trailing slash, to which the script appends {@code /artifacts/daemons/<name>/<version>}. Through
   * the edge that download is authorized by the registration token the install line carries.
   */
  public String artifactsBase() {
    return stripSlashes(
        set(artifactsUrl)
            .or(() -> publicOrigin(ARTIFACTS_HOST))
            .orElseGet(() -> internal(artifactsInternalUrl)));
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

  private static Optional<String> set(Optional<String> value) {
    return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
  }

  private static String stripSlashes(String url) {
    return url.trim().replaceAll("/+$", "");
  }
}
