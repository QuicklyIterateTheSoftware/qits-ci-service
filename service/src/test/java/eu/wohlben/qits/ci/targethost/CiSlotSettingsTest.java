package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link CiSlotSettings#BOOTSTRAP}, the text a CI lease's container runs to become its daemon, and
 * the settings a lease is composed from. What the bootstrap DOES, run through a real {@code /bin/sh},
 * is {@code CiDaemonBootstrapFetchTest}'s and {@code CiDaemonBootstrapTokenTest}'s.
 */
class CiSlotSettingsTest {

  @Test
  void theBootstrapInterpolatesNothingAtAll() {
    String bootstrap = CiSlotSettings.BOOTSTRAP;
    for (String value :
        List.of(
            "repo-1",
            "cafebabe",
            SlotFixtures.RUN,
            SlotFixtures.DAEMON_ID,
            SlotFixtures.TOKEN,
            "ci-slot",
            "qits-artifacts",
            "example.org")) {
      assertFalse(bootstrap.contains(value), "bootstrap must not carry '" + value + "'");
    }
    // The invariant the whole feature rests on: no repo-controlled code in a host argv.
    assertFalse(bootstrap.contains("bash -c"), bootstrap);
    // No docker vocabulary: a slot has no docker CLI. $DOCKER_CONFIG is a variable the shell
    // expands, upper case, which this assertion does not match.
    assertFalse(bootstrap.contains("docker"), bootstrap);
  }

  @Test
  void theBootstrapProbesBothDownloadersAndBecomesTheDaemon() {
    String bootstrap = CiSlotSettings.BOOTSTRAP;
    assertTrue(bootstrap.contains("command -v wget"), bootstrap);
    assertTrue(bootstrap.contains("command -v curl"), bootstrap);
    assertTrue(bootstrap.contains("neither wget nor curl"), bootstrap);
    assertTrue(bootstrap.contains("chmod +x /tmp/qits-ci-daemon"), bootstrap);
    assertTrue(bootstrap.contains("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  void theBootstrapWritesTheRegistryLoginOutsideTheWorktreeBeforeTheDaemonRuns() {
    String bootstrap = CiSlotSettings.BOOTSTRAP;
    assertTrue(bootstrap.contains("\"$DOCKER_CONFIG/config.json\""), bootstrap);
    assertTrue(bootstrap.contains("$QITS_CI_REGISTRY_AUTH_CONFIG"), bootstrap);
    assertTrue(CiSlotSettings.REGISTRY_AUTH_DIR.startsWith("/tmp/"));
    assertTrue(
        bootstrap.indexOf("config.json") < bootstrap.indexOf("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  void everyMavenCallRetriesAFailedDownloadWithOrWithoutAToken() {
    String bootstrap = CiSlotSettings.BOOTSTRAP;
    String line =
        "MAVEN_ARGS=\"${MAVEN_ARGS:+$MAVEN_ARGS }" + CiSlotSettings.MAVEN_RETRY_ARGS + "\"\n";
    int at = bootstrap.indexOf(line);
    assertTrue(at >= 0, bootstrap);
    // Unindented, so outside every if block (each block's body is indented); the cache blocks
    // that follow it (qits-1655) are the only ifs after it.
    assertTrue(at > 0 && bootstrap.charAt(at - 1) == '\n', "outside every if block");
    assertTrue(at > bootstrap.indexOf("-gs /tmp/qits-deploy-settings.xml"), "after the token block");
    assertTrue(at < bootstrap.indexOf("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  void theCacheVolumesAreUsedOnlyWhenTheyAreWritableDirectories() {
    String bootstrap = CiSlotSettings.BOOTSTRAP;
    String maven =
        "if [ -d /caches/m2 ] && [ -w /caches/m2 ]; then\n"
            + "  MAVEN_ARGS=\"${MAVEN_ARGS:+$MAVEN_ARGS }"
            + CiSlotSettings.MAVEN_CACHE_ARGS
            + "\"\n"
            + "  export MAVEN_ARGS\n"
            + "fi\n";
    String npm =
        "if [ -d /caches/pnpm ] && [ -w /caches/pnpm ]; then\n"
            + "  npm_config_cache="
            + CiSlotSettings.NPM_CACHE_DIR
            + "\n"
            + "  npm_config_store_dir="
            + CiSlotSettings.PNPM_STORE_DIR
            + "\n"
            + "  export npm_config_cache npm_config_store_dir\n"
            + "fi\n";
    assertTrue(bootstrap.contains(maven), bootstrap);
    assertTrue(bootstrap.contains(npm), bootstrap);
    String retry = "MAVEN_ARGS=\"${MAVEN_ARGS:+$MAVEN_ARGS }" + CiSlotSettings.MAVEN_RETRY_ARGS;
    assertTrue(bootstrap.indexOf(retry) < bootstrap.indexOf(maven), "after the retry flags");
    assertTrue(bootstrap.indexOf(npm) < bootstrap.indexOf("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  void theBootstrapHandsTheTokenOverAndHoldsNoExchangeAtAll() {
    String bootstrap = CiSlotSettings.BOOTSTRAP;
    assertTrue(CiSlotSettings.PUBLISH_TOKEN_COMMAND.startsWith("/tmp/"));
    assertTrue(bootstrap.contains("cat > " + CiSlotSettings.PUBLISH_TOKEN_COMMAND), bootstrap);
    assertTrue(bootstrap.contains("chmod 0700 " + CiSlotSettings.PUBLISH_TOKEN_COMMAND), bootstrap);
    assertTrue(bootstrap.contains("QITS_PUBLISH_TOKEN=$QITS_TOKEN"), bootstrap);
    assertTrue(bootstrap.contains(CiSlotSettings.DEPLOY_SETTINGS_FILE), bootstrap);
    for (String gone :
        List.of(
            "QITS_COMMISSIONED_CLIENT",
            "QITS_GIT_AUTH_",
            "grant_type",
            "access_token",
            "Authorization: Basic",
            "QITS_CI_DAEMON_SECRET")) {
      assertFalse(bootstrap.contains(gone), "the bootstrap still carries " + gone);
    }
    assertFalse(bootstrap.contains("echo \"$QITS_TOKEN\""), bootstrap);
    assertFalse(bootstrap.contains("set -x"), bootstrap);
  }

  @Test
  void theBinaryIsPinnedAsThePathOfItsVersionInTheDaemonsStore() {
    assertEquals(
        "/artifacts/daemons/qits-ci-daemon/abc123",
        SlotFixtures.shippedSettings().resolveBinaryUrl("abc123"));
  }

  @Test
  void theCliVersionIsThePinUnlessAnOperatorOverridesIt() {
    CiSlotSettings settings = SlotFixtures.shippedSettings();
    assertEquals(PlatformAccessCliBinary.VERSION, settings.artifactsCliVersion());
    settings.artifactsCliVersionOverride = Optional.of(" ");
    assertEquals(PlatformAccessCliBinary.VERSION, settings.artifactsCliVersion(), "blank is unset");
    settings.artifactsCliVersionOverride = Optional.of("2026.1.1");
    assertEquals("2026.1.1", settings.artifactsCliVersion());
  }

  @Test
  void theSlotLimitsAreTheSettingsSpecs() {
    CiSlotSpecs.Settings settings = SlotFixtures.shippedSettings().settings();
    assertEquals("4g", settings.memory());
    assertEquals("4g", settings.memorySwap());
    assertEquals(4096L, settings.pidsLimit());
    assertEquals("2", settings.cpus());
    assertEquals(1000, settings.oomScoreAdj());
  }
}
