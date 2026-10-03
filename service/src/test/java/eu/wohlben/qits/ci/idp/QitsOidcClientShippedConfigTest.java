package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The one named oidc client, {@code qits}, as the shipped {@code application.properties} resolves
 * it with no {@code QITS_RESOURCE_IDP_*} env set — the "no deployer" arm every clone-alone build
 * and every other test in this repo runs on (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4).
 *
 * <p>The deployed arm — the deployer's {@code QITS_RESOURCE_IDP_*} read, and the old {@code
 * QUARKUS_OIDC_CLIENT_*} extras still in the container read by nothing — is {@link
 * OidcClientNeutralisationTest}, against a real environment source.
 *
 * <p><b>The idp address is DERIVED, which is why the value below reads {@code dev-}.</b> The
 * default spells {@code http://${QITS_ENVIRONMENT:dev}-qits-idp}, because an application's alias on
 * qits-net is {@code <environment>-<application>} for every service there is and qits-deployments
 * injects {@code QITS_ENVIRONMENT} into every container it starts. A surefire JVM gains no
 * environment variable, so what this case sees is the {@code dev} fallback, which is also this
 * estate's real environment; {@code DerivedEnvironmentAddressTest} is what asks the expression the
 * other question, with a real environment source under it.
 */
@QuarkusTest
class QitsOidcClientShippedConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientResolvesItsOwnLiteralDefaults() {
    assertEquals(
        "http://dev-qits-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("dev-qits-ci", value("quarkus.oidc-client.qits.client-id"));
    // Empty, not absent — SmallRye reads a configured-empty String as null (the trap AGENTS.md
    // documents), so an empty secret reads as an empty Optional rather than as "" itself.
    Optional<String> secret =
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc-client.qits.credentials.secret", String.class);
    assertTrue(secret.isEmpty());
    // One audience for every outbound call now, never qits-containers or qits-githost specifically.
    assertEquals("qits-platform", value("quarkus.oidc-client.qits.grant-options.client.audience"));
  }

  @Test
  void theClientStaysDisabledUnderTest() {
    // %test.quarkus.oidc-client.qits.client-enabled=false wins over the shipped `true` — the arm
    // every test in this repo is on, so a suite never dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theTwoNamesTheDeploymentStillSetsShipNeutralised() {
    // Not stubs: the container still carries QUARKUS_OIDC_CLIENT_* and
    // QUARKUS_OIDC_CLIENT_GITHOST_*, one such variable mints the map key, and an enabled client
    // dials its issuer during runtime init before the listener accepts. `client-enabled` is what
    // disables the client where no variable outranks this file; `discovery-enabled` and
    // `token-path` are what keep an env-ENABLED client from dialling and from failing the boot, and
    // they have no environment twin to lose to. OidcClientNeutralisationTest is the same three keys
    // measured against a real env source.
    assertEquals("false", value("quarkus.oidc-client.client-enabled"));
    assertEquals("false", value("quarkus.oidc-client.discovery-enabled"));
    assertEquals("token", value("quarkus.oidc-client.token-path"));
    assertEquals("false", value("quarkus.oidc-client.githost.client-enabled"));
    assertEquals("false", value("quarkus.oidc-client.githost.discovery-enabled"));
    assertEquals("token", value("quarkus.oidc-client.githost.token-path"));
  }

  @Test
  void theContainersOwnerKeyIsGoneWithTheOrchestrator() {
    // qits.ci.containers.owner was this service's owner at qits-containers, read off the qits
    // client's id; qits-ci calls no orchestrator since qits-506, so the key is not shipped.
    assertTrue(
        ConfigProvider.getConfig()
            .getOptionalValue("qits.ci.containers.owner", String.class)
            .isEmpty());
  }

  @Test
  void theContainerGitAudienceIsNoLongerAConfigKey() {
    // A step container's git helper presents the run's token and asks the idp for nothing, so
    // there is no audience to name. The key is not shipped, so a leftover
    // QITS_CI_CONTAINER_GIT_AUDIENCE entry has nothing to override.
    assertTrue(
        ConfigProvider.getConfig()
            .getOptionalValue("qits.ci.container-git-audience", String.class)
            .isEmpty());
  }
}
