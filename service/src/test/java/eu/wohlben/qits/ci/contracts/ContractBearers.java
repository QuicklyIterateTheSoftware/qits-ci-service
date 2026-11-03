package eu.wohlben.qits.ci.contracts;

import io.quarkus.arc.Arc;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The bearers a provider state hands its consumer</b>, for the doors that judge the caller's
 * token rather than its role: the runner register door (the registration token's subject), a step's
 * report (the run's own ci-run token) and the machine gate.
 *
 * <p>The {@code %test} suite runs no idp and no edge. A consumer, though, sends {@code
 * Authorization: Bearer <token>}. A state registers the bearer it
 * returns as its {@code authorization} param ({@link #grant}), and this augmentor turns a request
 * carrying it into the machine identity the edge would forward for that token: a JWT principal with
 * its {@code sub}, the platform audience and its roles. So the golden master and the pact both send
 * the header a real consumer sends.
 *
 * <p>{@link #refuseUnknown} is the machine gate's state: a bearer no state granted is answered 401,
 * as a token no idp issued is in a deployment.
 *
 * <p><b>Idle unless a state armed it</b>: with nothing granted and the gate not armed it returns the
 * identity unchanged, so no other suite sees it. {@link #reset} disarms it again.
 *
 * <p><b>It is also how a test names a caller that has no {@code @TestSecurity} of its own</b> (epic
 * qits-543, qits-1359): forward-auth and its {@code %test} dev user leave qits-auth-core with
 * qits-1338, so a contract interaction that carries no bearer sends one made by {@link #bearer},
 * exactly as a signed-in person reaches this service. A test with one caller uses {@code
 * @TestSecurity}.
 */
@ApplicationScoped
public class ContractBearers implements SecurityIdentityAugmentor {

  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** What a granted bearer stands for. */
  record Grant(String subject, Set<String> roles) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  private volatile boolean refuseUnknown;

  /**
   * The {@code Authorization} value of a caller this suite names: {@code subject} holding {@code
   * roles} (each may be a comma-separated list), granted on the running application's instance so a
   * request carrying it is that identity. Granted afresh on every call, so a {@link #reset} between
   * tests never strands it.
   */
  public static String bearer(String subject, String... roles) {
    String[] each =
        Arrays.stream(roles)
            .flatMap(role -> Arrays.stream(role.split(",")))
            .map(String::trim)
            .filter(role -> !role.isEmpty())
            .toArray(String[]::new);
    String authorization = "Bearer suite." + subject + "." + String.join("+", each);
    Arc.container().instance(ContractBearers.class).get().grant(authorization, subject, each);
    return authorization;
  }

  /** A request with {@code Authorization: <authorization>} is {@code subject} holding {@code roles}. */
  public void grant(String authorization, String subject, String... roles) {
    grants.put(authorization, new Grant(subject, Set.of(roles)));
  }

  /** A bearer no state granted is refused 401. */
  public void refuseUnknown() {
    refuseUnknown = true;
  }

  public void reset() {
    grants.clear();
    refuseUnknown = false;
  }

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
    if (grants.isEmpty() && !refuseUnknown) {
      return Uni.createFrom().item(identity);
    }
    RoutingContext routing = HttpSecurityUtils.getRoutingContextAttribute(attributes);
    String authorization = routing == null ? null : routing.request().getHeader("Authorization");
    if (authorization == null || authorization.isBlank()) {
      return Uni.createFrom().item(identity);
    }
    Grant grant = grants.get(authorization);
    if (grant == null) {
      if (refuseUnknown) {
        return Uni.createFrom().failure(new AuthenticationFailedException("unknown bearer"));
      }
      return Uni.createFrom().item(identity);
    }
    QuarkusSecurityIdentity.Builder machine =
        QuarkusSecurityIdentity.builder().setPrincipal(new Token(grant.subject()));
    grant.roles().forEach(machine::addRole);
    return Uni.createFrom().item(machine.build());
  }

  /** The JWT the edge would forward: a subject and the platform audience, nothing else. */
  private record Token(String subject) implements JsonWebToken {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String getSubject() {
      return subject;
    }

    @Override
    public Set<String> getAudience() {
      return Set.of(PLATFORM_AUDIENCE);
    }

    @Override
    public Set<String> getClaimNames() {
      return Set.of("sub", "aud");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return switch (claimName) {
        case "sub" -> (T) subject;
        case "aud" -> (T) Set.of(PLATFORM_AUDIENCE);
        default -> null;
      };
    }
  }
}
