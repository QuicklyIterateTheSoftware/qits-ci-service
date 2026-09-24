package eu.wohlben.qits.ci.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The addresses this service derives from its own environment, asked what they resolve to WITH an
 * environment and WITHOUT one.</b>
 *
 * <p>Almost nothing about an address on this platform is a decision. Every application answers on
 * qits-net at {@code <environment>-<application>}, unconditionally and for every service there is
 * (qits-deployments' {@code PdNetworks.alias}), and qits-deployments injects {@code QITS_ENVIRONMENT}
 * into every container it starts. So an alias plus a fixed path is a SPELLING this process already
 * holds both halves of, and the shipped defaults write it as {@code ${QITS_ENVIRONMENT:dev}-…}
 * instead of asking a configuration entry to carry it.
 *
 * <p><b>Why this test exists at all: a derived default that quietly stops following its variable is
 * invisible.</b> The failure has no symptom here — the fallback is {@code dev}, this estate IS dev,
 * so a derivation that had been flattened back to a literal, or an expression that had stopped being
 * an expression, resolves to exactly the right string in every suite and on every clone. Every build
 * stays green and the first thing that finds out is a non-dev estate, at boot, dialling a host nobody
 * has. The only way to make that visible is to supply an environment the estate does not have and
 * assert the address MOVED. That is the {@code staging} arm below, and it is the whole point of the
 * class.
 *
 * <p><b>Why the rig has to be a real {@link EnvConfigSource}.</b> A surefire JVM cannot gain an
 * environment variable, and a {@code QuarkusTestProfile} override is an ordinary map-backed source:
 * it is read by an env-NAMED expression but it cannot answer which SOURCE a dotted key resolved
 * from, which is half of what is under test. So this assembles the real {@link PropertiesConfigSource}
 * over the SHIPPED files and the real {@link EnvConfigSource} over an environment of its own, at the
 * ordinals a deployed Quarkus gives them, and asks SmallRye Config directly — the arrangement {@code
 * OidcClientNeutralisationTest} established one package over, reused rather than reinvented.
 *
 * <p><b>Two files, two ordinals, because they are two kinds of thing.</b> {@code
 * service/src/main/resources/application.properties} is the DEPLOYABLE's own settings and is read at
 * 250; {@code ci/src/main/resources/META-INF/microprofile-config.properties} is a library jar's
 * shipped defaults and is read at 100. Both are included because both carry the same expression, and
 * getting the ci jar's ordinal wrong would make this class agree with itself about a precedence no
 * deployment has.
 *
 * <p><b>It lives in its own {@code config} package deliberately.</b> What it pins spans the idp
 * tenant, the telemetry receiver and the ci jar's container-facing addresses, so no feature package
 * owns it; a test-only package is the same shape {@code testdb} already is here.
 */
class DerivedEnvironmentAddressTest {

  /** Where a deployed Quarkus puts the deployable's {@code application.properties}. */
  private static final int APPLICATION_PROPERTIES_ORDINAL = 250;

  /** Where it puts a library jar's {@code META-INF/microprofile-config.properties}. */
  private static final int LIBRARY_DEFAULTS_ORDINAL = 100;

  /** A file under test, found by walking up from the directory surefire started this module in. */
  private static Path shipped(String relative) {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no " + relative + " above " + Path.of("").toAbsolutePath());
  }

  private static SmallRyeConfig config(Map<String, String> environment) throws IOException {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(
            new PropertiesConfigSource(
                shipped("service/src/main/resources/application.properties").toUri().toURL(),
                APPLICATION_PROPERTIES_ORDINAL))
        .withSources(
            new PropertiesConfigSource(
                shipped("ci/src/main/resources/META-INF/microprofile-config.properties")
                    .toUri()
                    .toURL(),
                LIBRARY_DEFAULTS_ORDINAL))
        .withSources(new EnvConfigSource(environment, EnvConfigSource.ORDINAL))
        .build();
  }

  private static String value(SmallRyeConfig config, String key) {
    return config.getConfigValue(key).getValue();
  }

  @Test
  void withNoEnvironmentTheFallbackIsThisEstate() throws IOException {
    SmallRyeConfig config = config(Map.of());

    // The `dev` fallback is not a placeholder: it is the environment this estate runs, so a clone,
    // a `quarkus:dev` and a deployment that somehow lost the variable all name an address that
    // really answers here. The application is NOT renamed by the qualification — qits-platform-idp
    // keeps that name and becomes dev-qits-platform-idp, never dev-qits-idp.
    assertEquals("http://dev-qits-platform-idp:8080/idp", value(config, "quarkus.oidc.auth-server-url"));
    assertTrue(
        config
            .getConfigValue("quarkus.oidc.auth-server-url")
            .getConfigSourceName()
            .contains("application.properties"),
        "the shipped file is what decides this when no deployment speaks about it");
  }

  @Test
  void theDerivationReallyFollowsTheVariable() throws IOException {
    SmallRyeConfig config = config(Map.of("QITS_ENVIRONMENT", "staging"));

    // THE CASE THE OTHER TWO CANNOT MAKE. On a dev estate a flattened literal and a live expression
    // are indistinguishable, so this supplies an environment nothing here has and asserts the
    // address moved with it. qits-deployments injects QITS_ENVIRONMENT into every container it
    // starts, so this is the shape every non-dev deployment really runs on.
    assertEquals(
        "http://staging-qits-platform-idp:8080/idp", value(config, "quarkus.oidc.auth-server-url"));
  }

  @Test
  void aDeployedEntryStillOutranksTheDerivation() throws IOException {
    SmallRyeConfig config =
        config(
            Map.of(
                "QITS_ENVIRONMENT", "staging",
                "QUARKUS_OIDC_AUTH_SERVER_URL", "http://an-entry-somebody-wrote:8080/idp"));

    // WHY "DELETE THE CONFIGURATION ENTRY" IS ITS OWN STEP OF THE ROLLOUT AND NOT SOMETHING THE
    // SHIPPED DEFAULT ACCOMPLISHES. An entry reaches the process as an environment variable at
    // ordinal 300 and the deployable's own file is 250, so for as long as qits-configuration holds
    // QUARKUS_OIDC_AUTH_SERVER_URL that value decides the address and the derivation is inert.
    assertEquals(
        "http://an-entry-somebody-wrote:8080/idp", value(config, "quarkus.oidc.auth-server-url"));
    assertEquals(
        EnvConfigSource.NAME,
        config.getConfigValue("quarkus.oidc.auth-server-url").getConfigSourceName());
  }

  @Test
  void theCiJarsOwnDefaultsDeriveTheSameWayAtTheirOwnOrdinal() throws IOException {
    // The same expression in a library jar's defaults, which is where most of this service's
    // container-facing addresses live. The address a step container is TOLD to dial back at is the
    // one worth pinning of them: the container parses nothing out of it, so the derivation is the
    // host's and happens here, before the string is ever sent.
    assertEquals(
        "ws://dev-qits-ci:8080/ci/daemon",
        value(config(Map.of()), "qits.ci.container-daemon-url"));
    assertEquals(
        "ws://staging-qits-ci:8080/ci/daemon",
        value(config(Map.of("QITS_ENVIRONMENT", "staging")), "qits.ci.container-daemon-url"));
  }
}
