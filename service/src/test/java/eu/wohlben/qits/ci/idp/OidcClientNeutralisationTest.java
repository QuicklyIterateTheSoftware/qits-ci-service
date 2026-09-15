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
 * The neutralisation of the two client names this application's DEPLOYMENT still sets — the unnamed
 * default client and {@code githost} — pinned against the environment that makes them exist at all.
 *
 * <p><b>Why this cannot be a {@code @QuarkusTest}.</b> The whole statement is about the ENVIRONMENT
 * source: one {@code QUARKUS_OIDC_CLIENT_<NAME>_*} variable mints the map key, and that source
 * outranks {@code application.properties}. A surefire JVM cannot gain an environment variable, and a
 * {@code QuarkusTestProfile} override is an ordinary map-backed source — it is read by the
 * env-NAMED expressions (which is exactly what {@link QitsOidcClientOldExtrasFallbackTest} uses it
 * for) and never by the dotted key, so it cannot show which source a dotted key resolves from. So
 * this test assembles the real {@link PropertiesConfigSource} over the SHIPPED file and the real
 * {@link EnvConfigSource} over the deployment's own variables, at the ordinals a deployed Quarkus
 * gives them, and asks SmallRye Config the questions directly.
 *
 * <p><b>What it holds, and why each half matters.</b> With no environment at all both names are
 * switched off by this file, which is the arm every suite and every clone-alone build runs on. With
 * the deployment's variables set, {@code client-enabled} resolves the environment's {@code true} —
 * the properties {@code false} LOSES, ordinal 300 over 250 — which is precisely why
 * {@code discovery-enabled=false} and {@code token-path} are in the file beside it: they have no
 * environment twin, so they are what keeps an env-enabled client from dialling its issuer during
 * runtime init and from failing the boot on a token endpoint it cannot discover. And the surviving
 * {@code qits} client still reads the environment's own value through its env-named expressions,
 * which is the one thing about this change that would be worse than the bug it fixes.
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
   * What qits-ci's dev deployment really sets today (checked 2026-09-15): the unnamed client's five
   * keys, of which four are declared in {@code .config/qits/configuration.yml} as the {@code qits}
   * client's fallback, and the {@code githost} family. Spelled as the environment spells them.
   */
  private static Map<String, String> deployedEnvironment() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ID", "qits-ci");
    env.put("QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET", "deployed-secret");
    env.put("QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL", "http://qits-idp:8080/idp");
    env.put("QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE", "qits-containers");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ID", "qits-ci");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CREDENTIALS_SECRET", "deployed-secret");
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
  void withNoEnvironmentBothNamesAreSwitchedOffByThisFile() throws IOException {
    SmallRyeConfig config = config(Map.of());

    assertEquals("false", value(config, "quarkus.oidc-client.client-enabled"));
    assertEquals("false", value(config, "quarkus.oidc-client.githost.client-enabled"));
    // The arm every test in this repo and every clone-alone build is on: no client is built, so
    // nothing dials and the file's other two keys per name are never reached.
    assertEquals("false", value(config, "quarkus.oidc-client.qits.client-enabled"));
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
  void theSurvivingQitsClientStillReadsTheDeploymentsOwnValues() throws IOException {
    SmallRyeConfig config = config(deployedEnvironment());

    // The line this change could have broken: `${QUARKUS_OIDC_CLIENT_CLIENT_ENABLED:false}` names an
    // environment VARIABLE, so it reads the environment's `true` and not the neutralising `false`
    // declared for the dotted key above it. Switching the real client off would have been far worse
    // than the boot hazard being closed.
    assertEquals("true", value(config, "quarkus.oidc-client.qits.client-enabled"));
    assertEquals("qits-ci", value(config, "quarkus.oidc-client.qits.client-id"));
    assertEquals("deployed-secret", value(config, "quarkus.oidc-client.qits.credentials.secret"));
    assertEquals("http://qits-idp:8080/idp", value(config, "quarkus.oidc-client.qits.auth-server-url"));
  }
}
