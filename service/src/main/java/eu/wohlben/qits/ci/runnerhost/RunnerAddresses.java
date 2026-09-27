package eu.wohlben.qits.ci.runnerhost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Every address a runner is told: where it asks qits-idp for a token, which audience it asks for,
 * and where it dials qits-ci's runner socket. One class, because the register door's answer and the
 * install script a runner is set up from have to name the same three things, and two compositions
 * of one address are two chances for them to disagree.
 *
 * <p><b>The CI base is {@code qits.ci.runner.public-url} when a deployment sets one, and the internal
 * alias otherwise.</b> qits-ci knows no public domain — nothing in this service composes {@code
 * ci.<env>.<domain>} — so the shipped answer is {@code qits.ci.runner.internal-url}, derived as
 * {@code http://${QITS_ENVIRONMENT}-qits-ci:8080}: the host a step container's daemon already
 * dials ({@code qits.ci.container-daemon-url}), reachable from anything on qits-net, which is where an
 * {@code INTERNAL} runner is. A runner outside qits-net needs a deployment to name the public base;
 * the {@code EDGE} plane is the next epic's.
 *
 * <p><b>The token endpoint is the one qits-ci hands its step daemons</b>: {@code
 * quarkus.oidc-client.qits.auth-server-url} plus {@code /token}, exactly {@code
 * CiDaemonLauncher.tokenUrl} — a qits-net address, the idp this service itself commissions against,
 * so the runner's client and the token it is minted from belong to one issuer by construction.
 */
@ApplicationScoped
public class RunnerAddresses {

  /** The one audience every platform token is requested with. */
  public static final String AUDIENCE = "qits-platform";

  /**
   * The runner socket's path — a literal, like {@code /ci/daemon}: a {@code @WebSocket} path does
   * not follow {@code quarkus.rest.path}, so it carries the {@code /ci} segment itself.
   */
  public static final String SOCKET_PATH = "/ci/runners/socket";

  @ConfigProperty(name = "qits.ci.runner.internal-url")
  String internalUrl;

  /** Blank is unset: SmallRye reads an empty property as an absent Optional. */
  @ConfigProperty(name = "qits.ci.runner.public-url")
  Optional<String> publicUrl;

  @ConfigProperty(name = "quarkus.oidc-client.qits.auth-server-url")
  String idpUrl;

  /** The base a runner reaches qits-ci at: scheme, host and port, no trailing slash. */
  public String ciBase() {
    String base =
        publicUrl.map(String::trim).filter(url -> !url.isEmpty()).orElse(value(internalUrl).trim());
    return base.replaceAll("/+$", "");
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
    return value(idpUrl).trim().replaceAll("/+$", "") + "/token";
  }

  /** {@link #AUDIENCE}, as a method so a caller holding this bean needs nothing else. */
  public String audience() {
    return AUDIENCE;
  }

  private static String value(String text) {
    return text == null ? "" : text;
  }
}
