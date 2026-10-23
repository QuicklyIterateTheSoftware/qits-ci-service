package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>What one CI lease is</b> (epic qits-1481, qits-1544): the {@code LeaseSpec{image, env, user,
 * limits}} a run asks qits-system for, asserted whole — the env as an ordered list of lines, so a
 * missing, an extra and a reordered key each fail.
 */
class CiSlotSpecsTest {

  private static final String AUTH =
      Base64.getEncoder()
          .encodeToString(("token:" + SlotFixtures.TOKEN).getBytes(StandardCharsets.UTF_8));

  @Test
  void theSpecIsTheRunsEnvironmentTheSlotImageTheBuildUserAndTheSlotLimits() {
    SystemLeases.LeaseSpec spec = SlotFixtures.compose();

    assertEquals(
        "registry.qits.example.org/qits/build-images/ci-slot@sha256:" + "a".repeat(64),
        spec.image(),
        "the run's pinned digest, under the registry's public name qits-system allows");
    assertEquals("1001", spec.user(), "the ci-slot image's build user");
    assertEquals(new SystemLeases.Limits("4g", "4g", 4096L, "2", 1000), spec.limits());
    assertEquals(
        List.of(
            "QITS_CI_DAEMON_ID=" + SlotFixtures.DAEMON_ID,
            "QITS_CI_DAEMON_URL=wss://ci.qits.example.org/ci/daemon",
            "QITS_CI_DAEMON_BINARY_URL=https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.927.1",
            "QITS_CI_REPOSITORY_URL=https://githost.qits.example.org/git/qits/qits-ci-service",
            "QITS_CI_BRANCH=main",
            "QITS_CI_SHA=cafebabecafebabecafebabecafebabecafebabe",
            "QITS_CI_RUN_ID=" + SlotFixtures.RUN,
            "QITS_CI_REPO_ID=5ca0e7e9-repo",
            "QITS_CI_PROJECT_ID=qits",
            "QITS_CI_REPO_NAME=qits-ci-service",
            "CI=true",
            "QITS_CI=true",
            "QITS_DOMAIN=example.org",
            "QITS_MAVEN_REPOSITORY_URL=https://registry.qits.example.org/artifacts/maven/maven",
            "QITS_MAVEN_CENTRAL_URL=https://mirror.qits.example.org/mirror/maven/central",
            "QITS_IMAGE_REPOSITORY=qits",
            "QITS_ARTIFACTS_CLI_PACKAGE=qits-platform-access-cli",
            "QITS_ARTIFACTS_CLI_VERSION=" + PlatformAccessCliBinary.VERSION,
            "QITS_WORKSPACES_URL=https://workspaces.qits.example.org",
            "QITS_TOKEN=" + SlotFixtures.TOKEN,
            "QITS_TOKEN_SUBJECT=" + SlotFixtures.SUBJECT,
            "QITS_MAVEN_AUTH_USR=" + SlotFixtures.SUBJECT,
            "QITS_MAVEN_AUTH_PSW=" + SlotFixtures.TOKEN,
            "GIT_CONFIG_GLOBAL=/tmp/qits-gitconfig",
            "QITS_PUBLISH_TOKEN_COMMAND=/tmp/qits-publish-token",
            "DOCKER_CONFIG=/tmp/qits-ci-registry-auth",
            "QITS_CI_REGISTRY_AUTH_CONFIG={\"auths\":{\"registry.qits.example.org\":{\"auth\":\""
                + AUTH
                + "\"},\"mirror.qits.example.org\":{\"auth\":\""
                + AUTH
                + "\"}}}",
            "QITS_EVENT_ID=0b5f3c1e-0000-4000-8000-000000000001",
            "QITS_EVENT_NAME=MaintenanceBump",
            "QITS_EVENT_OCCURRED_AT=2026-09-12T10:00:00Z",
            "QITS_EVENT_PAYLOAD={\"branch\":\"maintenance/dependencies\"}",
            "QITS_VERSION="),
        SlotFixtures.entries(spec.env()));
  }

  @Test
  void theSpecCarriesNothingPerStepAndNothingTheSlotsPolicyOwns() {
    Map<String, String> env = SlotFixtures.compose().env();
    // The step index travels in each RunStep (stepEnv): one lease runs every step.
    assertFalse(env.containsKey("QITS_CI_STEP_INDEX"));
    // qits-system refuses these and the runner owns them: the build plane, and the home.
    for (String policy : List.of("BUILDKIT_HOST", "HOME", "CLAUDE_CONFIG_DIR")) {
      assertFalse(env.containsKey(policy), "the spec carries " + policy);
    }
    // No docker socket vocabulary: a slot has none.
    assertFalse(env.containsKey("DOCKER_BUILDKIT"));
    assertFalse(env.containsKey("BUILDX_NO_DEFAULT_ATTESTATIONS"));
    // And nothing of the deleted internal plane (qits-515).
    env.forEach(
        (key, value) -> {
          assertFalse(key.startsWith("QITS_GIT_AUTH_"), key);
          assertFalse(key.startsWith("QITS_COMMISSIONED_CLIENT"), key);
          assertFalse(value.contains("-qits-"), key + " names a qits-net alias: " + value);
          assertFalse(value.contains("http://"), key + " is not TLS through the edge: " + value);
        });
  }

  @Test
  void aRunScopedExtraNeverShadowsAPolicyKey() {
    CiSlotSpecs.LeaseRun sample = SlotFixtures.sampleRun();
    CiSlotSpecs.LeaseRun hostile =
        new CiSlotSpecs.LeaseRun(
            sample.runId(),
            sample.repo(),
            sample.branch(),
            sample.sha(),
            sample.daemonId(),
            sample.daemonBinaryPath(),
            sample.image(),
            Map.of("BUILDKIT_HOST", "tcp://elsewhere:1234", "HOME", "/root", "QITS_EVENT_NAME", "E"));
    Map<String, String> env =
        CiSlotSpecs.compose(
                SlotFixtures.shippedSettings().settings(),
                SlotFixtures.plane(),
                hostile,
                SlotFixtures.token())
            .env();
    assertFalse(env.containsKey("BUILDKIT_HOST"));
    assertFalse(env.containsKey("HOME"));
    assertEquals("E", env.get("QITS_EVENT_NAME"));
  }

  @Test
  void theTokenReachesTheLeaseInExactlyTheFormsItIsSpentIn() {
    SystemLeases.LeaseSpec spec = SlotFixtures.compose();
    List<String> carrying =
        spec.env().entrySet().stream()
            .filter(entry -> entry.getValue().contains(SlotFixtures.TOKEN))
            .map(Map.Entry::getKey)
            .toList();
    assertEquals(List.of("QITS_TOKEN", "QITS_MAVEN_AUTH_PSW"), carrying);
    assertTrue(spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG").contains(AUTH));
    assertFalse(spec.toString().contains(SlotFixtures.TOKEN), "a logged spec carries no token");
    assertFalse(spec.image().contains(SlotFixtures.TOKEN));
    assertFalse(CiSlotSettings.BOOTSTRAP.contains(SlotFixtures.TOKEN));
  }

  @Test
  void aLeaseIsNeverComposedWithoutItsRunsToken() {
    assertThrows(
        NullPointerException.class,
        () ->
            CiSlotSpecs.compose(
                SlotFixtures.shippedSettings().settings(),
                SlotFixtures.plane(),
                SlotFixtures.sampleRun(),
                null));
  }

  @Test
  void aStepsOwnEnvironmentIsItsIndex() {
    assertEquals(Map.of("QITS_CI_STEP_INDEX", "2"), CiSlotSpecs.stepEnv(2));
  }

  @Test
  void anIdAddressedRunFetchesById() {
    assertEquals(
        "https://githost.qits.example.org/git/5ca0e7e9-repo",
        CiSlotSpecs.cloneUrl("https://githost.qits.example.org/", CiRepoRef.of("5ca0e7e9-repo")));
  }
}
