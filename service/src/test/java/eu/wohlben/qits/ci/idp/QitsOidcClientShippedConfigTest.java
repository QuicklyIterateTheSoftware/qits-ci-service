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
 * it with no {@code QITS_RESOURCE_IDP_*} or old extras env set — the "nothing configured" arm every
 * clone-alone build and every other test in this repo runs on (service-client-identity-plan.md, C4).
 *
 * <p>{@link QitsOidcClientOldExtrasFallbackTest} and {@link
 * QitsOidcClientResourceOverridesOldExtrasTest} hold the other two arms — the old extras keys alone,
 * and the new resource keys winning over them — each in its own {@code @QuarkusTest} because a
 * {@code @TestProfile}'s config overrides are fixed for the life of one boot.
 *
 * <p><b>The shipped fallback is DERIVED now, which is why the value below reads {@code dev-}.</b>
 * The innermost arm of that chain spells {@code http://${QITS_ENVIRONMENT:dev}-qits-platform-idp},
 * because an application's alias on qits-net is {@code <environment>-<application>} for every service
 * there is and qits-deployments injects {@code QITS_ENVIRONMENT} into every container it starts — so
 * the address is a spelling this process derives rather than a decision a configuration entry
 * carries. What it replaced, {@code http://qits-idp:8080/idp}, was neither the application name nor
 * the alias and resolved nowhere at all. A surefire JVM gains no environment variable, so what this
 * case sees is the {@code dev} fallback, which is also this estate's real environment;
 * {@code DerivedEnvironmentAddressTest} is what asks the expression the other question, with a real
 * environment source under it.
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
        "http://dev-qits-platform-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("qits-ci", value("quarkus.oidc-client.qits.client-id"));
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
    // %test.quarkus.oidc-client.qits.client-enabled=false wins over the shipped expression
    // regardless of what QUARKUS_OIDC_CLIENT_CLIENT_ENABLED says — the arm every test in this repo
    // is on, so a suite never dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theTwoNamesTheDeploymentStillSetsShipNeutralised() {
    // Not stubs: the deployment sets QUARKUS_OIDC_CLIENT_* and QUARKUS_OIDC_CLIENT_GITHOST_*, one
    // such variable mints the map key, and an enabled client dials its issuer during runtime init
    // before the listener accepts. `client-enabled` is what disables the client where no variable
    // outranks this file; `discovery-enabled` and `token-path` are what keep an env-ENABLED client
    // from dialling and from failing the boot, and they have no environment twin to lose to.
    // OidcClientNeutralisationTest is the same three keys measured against a real env source.
    assertEquals("false", value("quarkus.oidc-client.client-enabled"));
    assertEquals("false", value("quarkus.oidc-client.discovery-enabled"));
    assertEquals("token", value("quarkus.oidc-client.token-path"));
    assertEquals("false", value("quarkus.oidc-client.githost.client-enabled"));
    assertEquals("false", value("quarkus.oidc-client.githost.discovery-enabled"));
    assertEquals("token", value("quarkus.oidc-client.githost.token-path"));
  }

  @Test
  void theContainersOwnerKeyFollowsTheQitsClientsId() {
    // qits.ci.containers.owner (the `ci` jar) reads quarkus.oidc-client.qits.client-id by default —
    // OwnerGuard compares this string to a machine token's `sub` once the gate is on.
    assertEquals("qits-ci", value("qits.ci.containers.owner"));
  }

  @Test
  void theContainerGitAudienceIsNoLongerAConfigKey() {
    // What CiDaemonLauncher hands a step container as $QITS_GIT_AUTH_AUDIENCE is the constant
    // qits-platform now. The key is not shipped, so a leftover QITS_CI_CONTAINER_GIT_AUDIENCE entry
    // has nothing to override.
    assertTrue(
        ConfigProvider.getConfig()
            .getOptionalValue("qits.ci.container-git-audience", String.class)
            .isEmpty());
  }
}
