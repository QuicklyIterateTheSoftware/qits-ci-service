package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The neutralisation of the two client names this application's CONTAINER still carries — the
 * unnamed default client and {@code githost} — pinned against the environment that makes them exist
 * at all, beside the {@code qits} client that reads the deployer's {@code QITS_RESOURCE_IDP_*} and
 * nothing else.
 *
 * <p><b>Why this cannot be a {@code @QuarkusTest}.</b> The whole statement is about the ENVIRONMENT
 * source: one {@code QUARKUS_OIDC_CLIENT_<NAME>_*} variable mints the map key, and that source
 * outranks {@code application.properties}. A surefire JVM cannot gain an environment variable, and
 * a {@code QuarkusTestProfile} override is an ordinary map-backed source that cannot show which
 * source a dotted key resolves from. So this test assembles the real {@link PropertiesConfigSource}
 * over the SHIPPED file and the real {@link EnvConfigSource} over the container's own variables, at
 * the ordinals a deployed Quarkus gives them, and asks SmallRye Config the questions directly.
 *
 * <p><b>What it holds, and why each half matters.</b> With no environment at all both old names are
 * switched off by this file. With the container's variables set, {@code client-enabled} resolves
 * the environment's {@code true} — the properties {@code false} LOSES, ordinal 300 over 250 — which
 * is precisely why {@code discovery-enabled=false} and {@code token-path} are in the file beside
 * it: they have no environment twin, so they are what keeps an env-enabled client from dialling its
 * issuer during runtime init and from failing the boot on a token endpoint it cannot discover. And
 * the {@code qits} client takes the deployer's resource triple and none of the old extras' values.
 *
 * @see QitsOidcClientShippedConfigTest the same file read through a booted application
 */
class OidcClientNeutralisationTest {

  /** Where a deployed Quarkus puts {@code application.properties} from the classpath. */
  private static final int APPLICATION_PROPERTIES_ORDINAL = 250;

  /** The file under test, found by walking up from the directory surefire started this module in. */
  private static Path shippedProperties() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve("service/src/main/resources/application.properties");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no shipped application.properties above " + Path.of("").toAbsolutePath());
  }

  /**
   * What dev-qits-ci's container really carries (its envKeys, read 2026-10-02): the deployer's
   * {@code idp:client} triple, and the old extras nothing reads any more — the unnamed client's
   * five keys and the {@code githost} family — still reaching it until the config GC and the
   * deployer's extras file let go of them (qits-375). Spelled as the environment spells them; the
   * old extras deliberately carry values the {@code qits} client must NOT end up with.
   */
  private static Map<String, String> deployedEnvironment() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_RESOURCE_IDP_URL", "http://dev-qits-idp:8080/idp");
    env.put("QITS_RESOURCE_IDP_CLIENT_ID", "dev-qits-ci");
    env.put("QITS_RESOURCE_IDP_CLIENT_SECRET", "resource-secret");
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ID", "qits-ci");
    env.put("QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET", "old-extras-secret");
    env.put("QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL", "http://qits-idp:8080/idp");
    env.put("QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE", "qits-containers");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ID", "qits-ci");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CREDENTIALS_SECRET", "old-extras-secret");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_AUTH_SERVER_URL", "http://qits-idp:8080/idp");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_GRANT_OPTIONS_CLIENT_AUDIENCE", "qits-githost");
    return env;
  }

  private static SmallRyeConfig config(Map<String, String> environment) throws IOException {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(
            new PropertiesConfigSource(
                shippedProperties().toUri().toURL(), APPLICATION_PROPERTIES_ORDINAL))
        .withSources(new EnvConfigSource(environment, EnvConfigSource.ORDINAL))
        .build();
  }

  private static String value(SmallRyeConfig config, String key) {
    return config.getConfigValue(key).getValue();
  }

  @Test
  void withNoEnvironmentBothOldNamesAreSwitchedOffByThisFile() throws IOException {
    SmallRyeConfig config = config(Map.of());

    assertEquals("false", value(config, "quarkus.oidc-client.client-enabled"));
    assertEquals("false", value(config, "quarkus.oidc-client.githost.client-enabled"));
  }

  @Test
  void theQitsClientIsOnOutsideDevAndTestAndReadsNoEnvironmentToBeSo() throws IOException {
    // No profile is active on this builder, so this is the deployed resolution: on, with no
    // variable deciding it. %dev and %test switch it off, which QitsOidcClientShippedConfigTest
    // reads through a booted application.
    assertEquals("true", value(config(Map.of()), "quarkus.oidc-client.qits.client-enabled"));
    assertEquals(
        "true",
        value(
            config(Map.of("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED", "false")),
            "quarkus.oidc-client.qits.client-enabled"),
        "the old unnamed client's switch no longer reaches the qits client");
  }

  @Test
  void theDeploymentsOwnVariableOutranksTheShippedFalse() throws IOException {
    SmallRyeConfig config = config(deployedEnvironment());

    // MEASURED, and the reason `client-enabled=false` is not the whole neutralisation: the
    // environment source is ordinal 300 and application.properties 250, so a deployment that sets
    // the variable keeps its client ENABLED whatever this file says about the dotted key.
    assertEquals("true", value(config, "quarkus.oidc-client.client-enabled"));
    assertEquals("true", value(config, "quarkus.oidc-client.githost.client-enabled"));
    assertEquals(
        EnvConfigSource.NAME, config.getConfigValue("quarkus.oidc-client.client-enabled").getConfigSourceName());
  }

  @Test
  void whatKeepsAnEnvEnabledClientFromDiallingIsShippedForBothNames() throws IOException {
    SmallRyeConfig config = config(deployedEnvironment());

    // No deployment entry names either key, so this file is the highest source that speaks about
    // them. `discovery-enabled=false` removes the discovery GET the recorder awaits during runtime
    // init; `token-path` is what stops that same client failing the boot outright on an endpoint it
    // is no longer allowed to discover.
    assertEquals("false", value(config, "quarkus.oidc-client.discovery-enabled"));
    assertEquals("false", value(config, "quarkus.oidc-client.githost.discovery-enabled"));
    assertTrue(
        !value(config, "quarkus.oidc-client.token-path").isBlank(),
        "a discovery-disabled client with no token path fails runtime init");
    assertTrue(
        !value(config, "quarkus.oidc-client.githost.token-path").isBlank(),
        "a discovery-disabled client with no token path fails runtime init");
  }

  @Test
  void theQitsClientReadsTheDeployersResourceAndNoneOfTheOldExtras() throws IOException {
    SmallRyeConfig config = config(deployedEnvironment());

    assertEquals("true", value(config, "quarkus.oidc-client.qits.client-enabled"));
    assertEquals("dev-qits-ci", value(config, "quarkus.oidc-client.qits.client-id"));
    assertEquals("resource-secret", value(config, "quarkus.oidc-client.qits.credentials.secret"));
    assertEquals(
        "http://dev-qits-idp:8080/idp", value(config, "quarkus.oidc-client.qits.auth-server-url"));
    assertEquals(
        "qits-platform", value(config, "quarkus.oidc-client.qits.grant-options.client.audience"));
  }

  @Test
  void theOldExtrasAloneLeaveTheQitsClientOnItsShippedDefaults() throws IOException {
    // A container carrying only the old extras — no QITS_RESOURCE_IDP_* — no longer borrows them:
    // the qits client falls to its dev defaults and an empty secret, and is refused by the idp
    // rather than presenting the old unnamed client's credential.
    Map<String, String> oldExtrasOnly = new LinkedHashMap<>(deployedEnvironment());
    oldExtrasOnly.keySet().removeIf(name -> name.startsWith("QITS_RESOURCE_IDP_"));
    SmallRyeConfig config = config(oldExtrasOnly);

    assertEquals("dev-qits-ci", value(config, "quarkus.oidc-client.qits.client-id"));
    assertEquals(
        "http://dev-qits-idp:8080/idp", value(config, "quarkus.oidc-client.qits.auth-server-url"));
    assertTrue(
        config
            .getOptionalValue("quarkus.oidc-client.qits.credentials.secret", String.class)
            .isEmpty(),
        "the old extras' secret must not reach the qits client");
  }
}
