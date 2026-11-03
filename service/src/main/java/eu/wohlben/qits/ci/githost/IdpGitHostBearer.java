package eu.wohlben.qits.ci.githost;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * qits-ci's credential for its reads from other platform services: qits-githost ({@code
 * githost/HttpGitConfigSource}, {@code githost/HttpGitHostRepoListing}) and qits-projects ({@code
 * projects/HttpProjectsRepoListing}).
 *
 * <p>The same {@code qits} named client every outbound call this service makes now shares (epic
 * qits-540 dossier, 'Plan (as of 2026-09-13)', C4), asking one audience — {@code qits-platform} —
 * rather than a target-specific one. An empty answer never throws here: what it costs the caller is
 * the caller's own decision. {@code HttpGitConfigSource} spends it on the {@code Authorization}
 * header alone and lets the git host refuse a bare request itself; the two repository listings fail
 * closed instead and never open the socket — see their own {@code read} methods.
 */
@ApplicationScoped
public class IdpGitHostBearer implements GitHostBearer {

  private static final Logger LOG = Logger.getLogger(IdpGitHostBearer.class);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("qits") OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  @Override
  public Optional<String> token() {
    if (!enabled) {
      LOG.debug("The qits oidc client is disabled (quarkus.oidc-client.qits.client-enabled); no bearer to give");
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token from the qits oidc client: %s", e.toString());
      return Optional.empty();
    }
  }
}
