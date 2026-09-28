package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.idp.IdpCommissioner;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Turns a raw registration token into an identity by asking qits-idp about it — {@link
 * RegistrationTokenMechanism}'s other half, and the only place this service introspects a token.
 *
 * <p><b>A token qits-idp does not call live is a 401</b>, and so is a question qits-idp did not
 * answer: an identity is never built from nothing learned. A live token of any other kind
 * authenticates with <b>no roles</b>, so the door's {@code @RolesAllowed} refuses it 403 — the same
 * answer a JWT of the wrong kind gets. A live registration token gets {@code
 * qits:ci-runner-registration}, its subject as the principal, and the whole introspection answer as
 * the {@link #INTROSPECTED} attribute, which the door reads for the rest of its rule: the token's
 * context must be this runner, and its subject the row's.
 *
 * <p>The call blocks on HTTP, so it runs on a worker through {@code runBlocking}, never on the event
 * loop the mechanism is called on.
 */
@ApplicationScoped
public class RegistrationTokenIdentityProvider
    implements IdentityProvider<RegistrationTokenMechanism.RegistrationTokenRequest> {

  private static final Logger LOG = Logger.getLogger(RegistrationTokenIdentityProvider.class);

  /** The identity attribute carrying qits-idp's answer about the presented token. */
  public static final String INTROSPECTED = "qits.ci.registration-token";

  /** The one role this identity can carry — the register door's and the install script's. */
  public static final String REGISTRATION_ROLE = "qits:ci-runner-registration";

  @Inject IdpCommissioner idp;

  @Override
  public Class<RegistrationTokenMechanism.RegistrationTokenRequest> getRequestType() {
    return RegistrationTokenMechanism.RegistrationTokenRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RegistrationTokenMechanism.RegistrationTokenRequest request,
      AuthenticationRequestContext context) {
    return context.runBlocking(() -> identityOf(request.token()));
  }

  private SecurityIdentity identityOf(String token) {
    IdpCommissioner.IntrospectionAnswer answer = idp.introspectToken(token);
    if (answer.outcome() != IdpCommissioner.Introspection.LIVE) {
      LOG.infof("Refused a raw registration token: %s", answer.detail());
      throw new AuthenticationFailedException("not a live registration token");
    }
    IdpCommissioner.IntrospectedToken introspected = answer.token();
    QuarkusSecurityIdentity.Builder identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(introspected.subject()))
            .addAttribute(INTROSPECTED, introspected);
    // The kind decides the role, never the roles list alone: this identity is for one door.
    if (IdpCommissioner.RUNNER_REGISTRATION_KIND.equals(introspected.contextKind())
        && introspected.roles().contains(REGISTRATION_ROLE)) {
      identity.addRole(REGISTRATION_ROLE);
    }
    return identity.build();
  }
}
