package eu.wohlben.qits.ci.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * <b>The addresses this service derives, asked what they resolve to WITH a domain and WITHOUT one.</b>
 *
 * <p>Almost nothing about an address on this platform is a decision. Every peer answers at its edge
 * host, {@code https://<label>.qits.<domain>} (feature qits-1406, qits-1414), and qits-deployments
 * injects {@code QITS_DOMAIN} into every container it starts. So a label plus a fixed path is a
 * SPELLING this process already holds both halves of, and the shipped defaults write it as {@code
 * https://<label>.qits.${QITS_DOMAIN}} instead of asking a configuration entry to carry it. There is
 * no environment label in a peer address — the platform project has none — so {@code
 * QITS_ENVIRONMENT} moves none of them, and there is no fallback domain: a process without one
 * refuses to start rather than dial a guessed host.
 *
 * <p><b>Why this test exists at all: a derived default that quietly stops following its variable is
 * invisible.</b> A suite runs the {@code %test} lines, which keep the old qits-net names, so a
 * derivation flattened back to a literal would stay green everywhere and the first thing to find out
 * would be a deployment, at boot. The only way to make that visible is to supply a domain and assert
 * the address follows it, and to supply none and assert the refusal.
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
 * tenant, the telemetry receiver and the ci jar's own peer addresses, so no feature package
 * owns it; a test-only package is the same shape {@code testdb} already is here.
 */
class DerivedEnvironmentAddressTest {

  /** Retired in qits-757; no shipped file may state it in any profile. */
  private static final String RETIRED_REGISTRY_HOST =
      eu.wohlben.qits.ci.control.RetiredRegistryKeys.REGISTRY_HOST_KEY;

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
    return config(null, environment);
  }

  /** The same rig under a named profile; {@code prod} is what a packaged, deployed process runs. */
  private static SmallRyeConfig config(String profile, Map<String, String> environment)
      throws IOException {
    SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder().addDefaultInterceptors();
    if (profile != null) {
      builder.withProfile(profile);
    }
    return builder
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

  /** Every peer address the shipped files derive from {@code QITS_DOMAIN}. */
  private static final List<String> PEER_KEYS =
      List.of(
          "quarkus.oidc.auth-server-url",
          "quarkus.oidc-client.qits.auth-server-url",
          "qits.ci.git-host-url",
          "qits.artifacts.maven.registry-url",
          "qits.system.url");

  @Test
  void eachPeerIsItsEdgeHostUnderTheDomain() throws IOException {
    SmallRyeConfig config = config(Map.of("QITS_DOMAIN", "wohlben.eu"));

    assertEquals("https://idp.qits.wohlben.eu/idp", value(config, "quarkus.oidc.auth-server-url"));
    assertTrue(
        config
            .getConfigValue("quarkus.oidc.auth-server-url")
            .getConfigSourceName()
            .contains("application.properties"),
        "the shipped file is what decides this when no deployment speaks about it");
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        value(config, "quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("https://system.qits.wohlben.eu", value(config, "qits.system.url"));
  }

  @Test
  void theDerivationReallyFollowsTheDomainAndNotTheEnvironment() throws IOException {
    // THE CASE A SUITE CANNOT MAKE. Supply a domain nothing here has and assert the address moved
    // with it; supply an environment as well and assert it moved nothing — the platform project
    // has no environment label, so QITS_ENVIRONMENT is not part of any peer address.
    SmallRyeConfig config =
        config(Map.of("QITS_DOMAIN", "staging.example", "QITS_ENVIRONMENT", "staging"));
    assertEquals(
        "https://idp.qits.staging.example/idp", value(config, "quarkus.oidc.auth-server-url"));
    for (String key : PEER_KEYS) {
      assertFalse(value(config, key).contains("staging-"), key + " carries the environment");
    }
  }

  @Test
  void withNoDomainEveryPeerAddressRefusesToResolve() throws IOException {
    // No QITS_DOMAIN, no guessed domain: the expansion fails, and with it the boot.
    SmallRyeConfig config = config(Map.of("QITS_ENVIRONMENT", "dev"));
    for (String key : PEER_KEYS) {
      NoSuchElementException refused =
          assertThrows(NoSuchElementException.class, () -> config.getValue(key, String.class), key);
      assertTrue(refused.getMessage().contains("QITS_DOMAIN"), key + ": " + refused.getMessage());
    }
  }

  @Test
  void aDeployedEntryStillOutranksTheDerivation() throws IOException {
    SmallRyeConfig config =
        config(
            Map.of(
                "QITS_DOMAIN", "wohlben.eu",
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
    // The same expression in a library jar's defaults: the addresses qits-ci ITSELF dials. (The
    // ones a step container used to be told — the daemon socket among them — are not keys any
    // more: a step is told public names composed from QITS_DOMAIN, qits-515.) The registry is
    // qits-artifacts' published host, `registry`, not its application name.
    SmallRyeConfig config = config(Map.of("QITS_DOMAIN", "wohlben.eu"));
    assertEquals("https://githost.qits.wohlben.eu", value(config, "qits.ci.git-host-url"));
    assertEquals(
        "https://registry.qits.wohlben.eu/artifacts/maven/maven",
        value(config, "qits.artifacts.maven.registry-url"));
    assertTrue(
        config
            .getConfigValue("qits.ci.git-host-url")
            .getConfigSourceName()
            .contains("microprofile-config.properties"));
  }

  @Test
  void aDeployedProcessResolvesTheRetiredExtrasToTheirLiveValues() throws IOException {
    // qits-1391: the deployer stops handing this service qits-configuration's extras, so a deployed
    // process (the prod profile, QITS_ENVIRONMENT and QITS_DOMAIN and nothing else) must resolve each
    // value the extras stated on dev-qits-ci from the shipped files alone — at the peer's edge host
    // now (qits-1414).
    SmallRyeConfig config =
        config("prod", Map.of("QITS_ENVIRONMENT", "dev", "QITS_DOMAIN", "wohlben.eu"));
    assertEquals("true", value(config, "qits.auth.machine.required"));
    assertEquals("true", value(config, "quarkus.oidc.tenant-enabled"));
    assertEquals("https://projects.qits.wohlben.eu", value(config, "qits.ci.projects-url"));
    // The registry host key is retired (qits-757): no shipped default states it, so the deployer's
    // next update removes it from the container's environment.
    assertTrue(config.getOptionalValue(RETIRED_REGISTRY_HOST, String.class).isEmpty());
    assertEquals("https://githost.qits.wohlben.eu", value(config, "qits.ci.git-host-url"));
    assertEquals(
        "https://registry.qits.wohlben.eu/artifacts/maven/maven",
        value(config, "qits.artifacts.maven.registry-url"));
    assertEquals("https://idp.qits.wohlben.eu/idp", value(config, "quarkus.oidc.auth-server-url"));
    // Telemetry moves in a later step of epic qits-428, not with the peers.
    assertEquals(
        "http://dev-qits-observability:8080", value(config, "qits.observability.url"));

    SmallRyeConfig staging =
        config("prod", Map.of("QITS_ENVIRONMENT", "staging", "QITS_DOMAIN", "wohlben.eu"));
    assertEquals("https://projects.qits.wohlben.eu", value(staging, "qits.ci.projects-url"));
    assertTrue(staging.getOptionalValue(RETIRED_REGISTRY_HOST, String.class).isEmpty());
  }

  @Test
  void devAndTestKeepTheirLocalAddresses() throws IOException {
    // %dev and %test keep the old qits-net names so a suite and a `quarkus:dev` resolve with no
    // QITS_DOMAIN; nothing in either dials them.
    for (String profile : List.of("dev", "test")) {
      SmallRyeConfig config = config(profile, Map.of());
      assertEquals(
          "http://dev-qits-idp:8080/idp", value(config, "quarkus.oidc.auth-server-url"), profile);
      assertEquals(
          "http://dev-qits-githost:8080", value(config, "qits.ci.git-host-url"), profile);
      assertEquals(
          "http://dev-qits-artifacts:8080/artifacts/maven/maven",
          value(config, "qits.artifacts.maven.registry-url"),
          profile);
      assertEquals("http://dev-qits-system:8080", value(config, "qits.system.url"), profile);
    }
  }

  @Test
  void devAndTestKeepTheGateOffAndTheKillSwitchUnset() throws IOException {
    for (String profile : List.of("dev", "test")) {
      SmallRyeConfig config = config(profile, Map.of());
      assertEquals("false", value(config, "qits.auth.machine.required"), profile);
      assertTrue(
          config.getOptionalValue("qits.ci.projects-url", String.class).isEmpty(), profile);
      assertTrue(
          config.getOptionalValue(RETIRED_REGISTRY_HOST, String.class).isEmpty(), profile);
    }
  }
}
