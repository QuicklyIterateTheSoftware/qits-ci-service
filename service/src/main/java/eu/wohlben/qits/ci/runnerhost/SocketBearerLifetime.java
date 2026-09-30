package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.daemonhost.CiDaemonSocket;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the two control sockets open past the bearer they were opened with.
 *
 * <p><b>What it undoes.</b> quarkus-oidc stamps every identity it builds from a bearer with {@code
 * quarkus.identity.expire-time} — the token's {@code exp} — and websockets-next arms a timer on that
 * attribute for every connection it admits and closes the connection "Authentication expired" when
 * it fires (its {@code SecuritySupport}; there is no key to turn it off). A runner mints a {@code
 * client_credentials} token when it dials, and qits-idp gives it an hour ({@code
 * qits.idp.token-ttl-seconds=3600}), so every runner lost its socket exactly one hour after each
 * connect — measured 2026-09-29 at 11:00:58, 12:00:58 and 13:21:12, each an hour to the second after
 * the dial before it, and the first of them failed run 286b4b9d mid-step (qits-545). A step's
 * daemon is worse off: the edge swaps its {@code qits_tok_} for a JWT that lives five minutes
 * ({@code qits.idp.token-introspection-jwt-ttl-seconds=300}), so no step through the edge could have
 * outlived that.
 *
 * <p><b>Why dropping the attribute is the right answer rather than refreshing the token.</b> Both
 * sockets are authenticated at the upgrade and nowhere after it, by design: a runner is cut off by
 * {@link CiRunnerRegistry#deleted} (a {@code Retire} and a 1008 close, whatever its bearer says) and
 * refused at its next dial by the missing row, and a step's daemon lives exactly as long as its
 * container. Neither has a fresh token to present mid-connection — the daemon's JWT is the edge's,
 * not its own. So the attribute is removed, for these two paths only, and every other request keeps
 * its expiry exactly as quarkus-oidc stated it.
 */
@ApplicationScoped
public class SocketBearerLifetime implements SecurityIdentityAugmentor {

  /** quarkus-oidc's {@code OidcUtils.QUARKUS_IDENTITY_EXPIRE_TIME}, which websockets-next reads. */
  static final String EXPIRE_TIME = "quarkus.identity.expire-time";

  /** The upgrades whose connections outlive their bearer. */
  static final Set<String> SOCKET_PATHS = Set.of(RunnerAddresses.SOCKET_PATH, CiDaemonSocket.PATH);

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity, AuthenticationRequestContext context) {
    return Uni.createFrom().item(identity);
  }

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity,
      AuthenticationRequestContext context,
      Map<String, Object> attributes) {
    RoutingContext request = HttpSecurityUtils.getRoutingContextAttribute(attributes);
    String path = request == null ? null : request.normalizedPath();
    return Uni.createFrom().item(forPath(identity, path));
  }

  /** The identity as a connection on {@code path} should hold it. */
  static SecurityIdentity forPath(SecurityIdentity identity, String path) {
    if (path == null
        || !SOCKET_PATHS.contains(path)
        || identity.isAnonymous()
        || identity.getAttribute(EXPIRE_TIME) == null) {
      return identity;
    }
    Map<String, Object> kept = new HashMap<>(identity.getAttributes());
    kept.remove(EXPIRE_TIME);
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(identity.getPrincipal())
        .addRoles(identity.getRoles())
        .addCredentials(identity.getCredentials())
        .addPermissions(identity.getPermissions())
        .addAttributes(kept)
        .addPermissionChecker(identity::checkPermission)
        .build();
  }
}
