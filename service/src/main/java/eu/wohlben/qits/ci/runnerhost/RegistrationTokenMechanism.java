package eu.wohlben.qits.ci.runnerhost;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>The register door's second way in: a raw {@code qits_tok_} registration token, presented to
 * qits-ci directly.</b>
 *
 * <p>A runner on the INTERNAL plane dials {@code http://<env>-qits-ci:8080} on qits-net and never
 * crosses the edge — so nothing exchanges its registration token for the short JWT the edge mints,
 * and the bearer that arrives is the opaque value itself, which quarkus-oidc has no way to validate.
 * This mechanism does for that one route what the edge does for every route: it asks qits-idp
 * ({@link RegistrationTokenIdentityProvider}), and turns a live token into an identity.
 *
 * <p><b>It abstains everywhere else, and that is the security property.</b> It acts only on {@code
 * POST /ci/api/runners/<id>/register} carrying {@code Authorization: Bearer qits_tok_…}; for every
 * other request it answers nothing and the ordinary mechanisms decide — which for a {@code qits_tok_}
 * is quarkus-oidc refusing a value that is not a JWT, a 401. So a registration token opens this one
 * route exactly as {@code qits:ci-runner-registration} already did behind the edge, and no other.
 * It runs before quarkus-oidc on its route (a higher priority), because quarkus-oidc would otherwise
 * fail the request before anything else were asked.
 *
 * <p>The path is matched on the normalized path and the method, and it spells {@code /ci/api}
 * itself: an authentication mechanism runs before JAX-RS routing, so it sees what the router sees.
 */
@ApplicationScoped
public class RegistrationTokenMechanism implements HttpAuthenticationMechanism {

  /** The prefix qits-idp gives every opaque token — how a hop recognises one without asking. */
  public static final String TOKEN_PREFIX = "qits_tok_";

  static final Pattern REGISTER_PATH = Pattern.compile("/ci/api/runners/[^/]+/register/?");

  private static final String BEARER = "Bearer ";

  /** Ahead of quarkus-oidc (1001) and forward-auth (1000) — but only on its own route. */
  static final int PRIORITY = HttpAuthenticationMechanism.DEFAULT_PRIORITY + 1000;

  /** The one credential this mechanism produces. */
  public static final class RegistrationTokenRequest extends BaseAuthenticationRequest {

    private final String token;

    RegistrationTokenRequest(String token) {
      this.token = token;
    }

    public String token() {
      return token;
    }

    @Override
    public String toString() {
      return "RegistrationTokenRequest[qits_tok_…]";
    }
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String token = registrationToken(context);
    if (token == null) {
      return Uni.createFrom().optional(java.util.Optional.empty());
    }
    return identityProviderManager.authenticate(
        HttpSecurityUtils.setRoutingContextAttribute(new RegistrationTokenRequest(token), context));
  }

  /** The raw token when this request is the register door's and carries one, else null. */
  static String registrationToken(RoutingContext context) {
    if (!"POST".equals(context.request().method().name())) {
      return null;
    }
    String path = context.normalizedPath();
    if (path == null || !REGISTER_PATH.matcher(path).matches()) {
      return null;
    }
    String header = context.request().getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      return null;
    }
    String token = header.substring(BEARER.length()).trim();
    return token.startsWith(TOKEN_PREFIX) ? token : null;
  }

  /**
   * A challenge only where this mechanism acts. Anywhere else it answers none, so the challenge a
   * refused request gets is exactly the one it got before this mechanism existed.
   */
  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    if (registrationToken(context) == null) {
      return Uni.createFrom().nullItem();
    }
    return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer"));
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of(RegistrationTokenRequest.class);
  }

  @Override
  public int getPriority() {
    return PRIORITY;
  }
}
