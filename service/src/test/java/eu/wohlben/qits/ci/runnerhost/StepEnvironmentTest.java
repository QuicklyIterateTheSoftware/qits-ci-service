package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>A step container's whole environment, asserted literally</b> — every key, every value, in
 * order. There is one address plane (qits-515), so this is the one place a new key, a dropped key or
 * a reordered one fails a build; the other suites assert keys one at a time.
 *
 * <p>It also holds what a spec must never carry again: a docker network, an extra host, a qits-net
 * alias, the commissioned client pair, a {@code QITS_GIT_AUTH_*} variable or the per-container
 * daemon secret.
 */
class StepEnvironmentTest {

  private static WorkloadSpec compose(StepContainerSettings launcher, boolean docker, boolean build) {
    return StepWorkloadSpecs.compose(
        launcher.workloadSettings(),
        StepFixtures.plane(launcher),
        StepFixtures.sampleStep(2, docker, build),
        StepFixtures.token(),
        null);
  }

  private static final String AUTH =
      Base64.getEncoder()
          .encodeToString(("token:" + StepFixtures.TOKEN).getBytes(StandardCharsets.UTF_8));

  /** The keys every step carries, in order, up to where a build step's own begin. */
  private static List<String> common() {
    return List.of(
        "QITS_CI_DAEMON_ID=daemon-7",
        "QITS_CI_DAEMON_URL=wss://ci.qits.example.org/ci/daemon",
        "QITS_CI_DAEMON_BINARY_URL=https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.927.1",
        "QITS_CI_REPOSITORY_URL=https://githost.qits.example.org/git/qits/qits-ci-service",
        "QITS_CI_BRANCH=main",
        "QITS_CI_SHA=cafebabecafebabecafebabecafebabecafebabe",
        // What the QA report hook and `qits ci report submit` name their upload by (qits-754).
        "QITS_CI_RUN_ID=" + StepFixtures.RUN,
        "QITS_CI_STEP_INDEX=2",
        "QITS_CI_REPO_ID=5ca0e7e9-repo",
        "QITS_CI_PROJECT_ID=qits",
        "QITS_CI_REPO_NAME=qits-ci-service",
        "CI=true",
        "QITS_CI=true",
        "QITS_DOMAIN=example.org",
        "QITS_IMAGE_REPOSITORY=qits",
        "QITS_ARTIFACTS_CLI_PACKAGE=qits",
        "QITS_ARTIFACTS_CLI_VERSION=" + PlatformAccessCliBinary.VERSION,
        "QITS_WORKSPACES_URL=https://workspaces.qits.example.org",
        "QITS_TOKEN=" + StepFixtures.TOKEN,
        "QITS_TOKEN_SUBJECT=" + StepFixtures.SUBJECT,
        "QITS_MAVEN_AUTH_USR=" + StepFixtures.SUBJECT,
        "QITS_MAVEN_AUTH_PSW=" + StepFixtures.TOKEN,
        "GIT_CONFIG_GLOBAL=/tmp/qits-gitconfig",
        "QITS_PUBLISH_TOKEN_COMMAND=/tmp/qits-publish-token");
  }

  private static List<String> events() {
    return List.of(
        "QITS_EVENT_ID=0b5f3c1e-0000-4000-8000-000000000001",
        "QITS_EVENT_NAME=MaintenanceBump",
        "QITS_EVENT_OCCURRED_AT=2026-09-12T10:00:00Z",
        "QITS_EVENT_PAYLOAD={\"branch\":\"maintenance/dependencies\"}");
  }

  private static List<String> concat(List<String> a, List<String> b, List<String> c) {
    List<String> all = new java.util.ArrayList<>(a);
    all.addAll(b);
    all.addAll(c);
    return all;
  }

  @Test
  void aDockerStepIsTheWholeOfThisEnvironment() {
    WorkloadSpec spec = compose(StepFixtures.shippedLauncher(), true, false);

    List<String> build =
        List.of(
            "DOCKER_BUILDKIT=1",
            "BUILDX_NO_DEFAULT_ATTESTATIONS=1",
            "DOCKER_CONFIG=/tmp/qits-ci-registry-auth",
            "QITS_CI_REGISTRY_AUTH_CONFIG={\"auths\":{"
                + "\"registry.qits.example.org\":{\"auth\":\"" + AUTH + "\"},"
                + "\"mirror.qits.example.org\":{\"auth\":\"" + AUTH + "\"}}}");
    assertEquals(concat(common(), build, events()), StepFixtures.entries(spec.env()));
    assertFalse(spec.env().containsKey("BUILDKIT_HOST"), "the runner fills it; nothing is sent");
    assertNull(spec.network());
    assertEquals(List.of(), spec.extraHosts());
    // Still the declared opt-in, and still the only thing about a step that asks for the socket.
    assertEquals(true, spec.hostDockerSocket());
  }

  @Test
  void aBuildStepWithNoSocketGetsTheSameBuildEnvironmentWithoutTheLegacyFlags() {
    WorkloadSpec spec = compose(StepFixtures.shippedLauncher(), false, true);

    List<String> build =
        List.of(
            "DOCKER_CONFIG=/tmp/qits-ci-registry-auth",
            "QITS_CI_REGISTRY_AUTH_CONFIG={\"auths\":{"
                + "\"registry.qits.example.org\":{\"auth\":\"" + AUTH + "\"},"
                + "\"mirror.qits.example.org\":{\"auth\":\"" + AUTH + "\"}}}");
    assertEquals(concat(common(), build, events()), StepFixtures.entries(spec.env()));
    assertFalse(spec.env().containsKey("BUILDKIT_HOST"));
    assertFalse(spec.env().containsKey("DOCKER_BUILDKIT"), "a buildctl step has no docker CLI to steer");
    assertEquals(false, spec.hostDockerSocket());
  }

  @Test
  void aPlainStepHoldsTheTokenAndOnlyItsOwnImagesPullLogin() {
    WorkloadSpec spec = compose(StepFixtures.shippedLauncher(), false, false);

    // The sample image is the platform's, so the runner's pull of it gets one login — and the
    // container gets no DOCKER_CONFIG, so nothing in it logs in.
    List<String> pull =
        List.of(
            "QITS_CI_REGISTRY_AUTH_CONFIG={\"auths\":{"
                + "\"registry.qits.example.org\":{\"auth\":\"" + AUTH + "\"}}}");
    assertEquals(concat(common(), pull, events()), StepFixtures.entries(spec.env()));
    assertFalse(spec.env().containsKey("DOCKER_CONFIG"));
  }

  /**
   * qits-731: the platform's public domain is the ONE address input a step is told. Every host a
   * recipe reaches is code under it — {@code registry.qits.<d>}, {@code mirror.qits.<d>} — so the
   * URL variables that used to carry the same addresses composed here are gone, from every shape of
   * step, and none may come back as a second answer for a recipe to read instead.
   */
  @Test
  void theDomainIsTheOnlyAddressInputAndNoUrlVariableRemains() {
    for (boolean[] shape : new boolean[][] {{false, false}, {true, false}, {false, true}}) {
      Map<String, String> env = compose(StepFixtures.shippedLauncher(), shape[0], shape[1]).env();
      String what = "docker=" + shape[0] + " build=" + shape[1];

      assertEquals("example.org", env.get("QITS_DOMAIN"), what);
      for (String gone :
          List.of(
              "QITS_NPM_REGISTRY_URL",
              "QITS_NPM_PROXY_URL",
              "QITS_MAVEN_REGISTRY_URL",
              "QITS_MAVEN_CENTRAL_MIRROR_URL",
              "QITS_MAVEN_PROXY_URL",
              "QITS_ARTIFACTS_URL",
              "QITS_DOCS_URL",
              "QITS_REGISTRY",
              "QITS_BUILD_REGISTRY")) {
        assertFalse(env.containsKey(gone), what + " still carries " + gone);
      }
    }
  }

  /**
   * qits-515: the internal plane and everything only it carried. Asserted over every shape of step,
   * since each of these keys used to be conditional on one.
   */
  @Test
  void noStepCarriesAnythingOfTheDeletedInternalPlane() {
    for (boolean[] shape : new boolean[][] {{false, false}, {true, false}, {false, true}}) {
      WorkloadSpec spec = compose(StepFixtures.shippedLauncher(), shape[0], shape[1]);
      Map<String, String> env = spec.env();
      String what = "docker=" + shape[0] + " build=" + shape[1];

      assertEquals(StepFixtures.TOKEN, env.get("QITS_TOKEN"), what);
      for (String gone :
          List.of(
              "QITS_COMMISSIONED_CLIENT_ID",
              "QITS_COMMISSIONED_CLIENT_SECRET",
              "QITS_CI_DAEMON_SECRET",
              "QITS_GIT_AUTH_TOKEN_URL",
              "QITS_GIT_AUTH_HOST",
              "QITS_GIT_AUTH_AUDIENCE")) {
        assertFalse(env.containsKey(gone), what + " still carries " + gone);
      }
      env.forEach(
          (key, value) -> {
            assertFalse(key.startsWith("QITS_GIT_AUTH_"), what + " carries " + key);
            assertFalse(value.contains("-qits-"), what + ": " + key + " names a qits-net alias: " + value);
            assertFalse(value.contains(".localhost"), what + ": " + key + " names a loopback vhost: " + value);
            assertFalse(value.contains("host.docker.internal"), what + ": " + key + " = " + value);
            assertFalse(value.contains("http://"), what + ": " + key + " is not TLS through the edge: " + value);
            assertFalse(value.contains("ws://"), what + ": " + key + " is not TLS through the edge: " + value);
          });
      assertNull(spec.network(), what + " joins a docker network");
      assertEquals(List.of(), spec.extraHosts(), what + " is given an extra host");
      assertFalse(spec.image().contains("-qits-"), what + " pulls from an alias: " + spec.image());
      assertFalse(spec.image().contains(".localhost"), what + " pulls from a vhost: " + spec.image());
      // And the text the container runs names none of it either.
      for (String arg : spec.args()) {
        assertFalse(arg.contains("QITS_COMMISSIONED_CLIENT"), what + ": the bootstrap names the pair");
        assertFalse(arg.contains("QITS_GIT_AUTH_"), what + ": the bootstrap names the git-auth keys");
        assertFalse(arg.contains("QITS_CI_DAEMON_SECRET"), what + ": the bootstrap names the secret");
        assertFalse(arg.contains("host.docker.internal"), what);
      }
    }
  }

  @Test
  void aStepIsNeverComposedWithoutItsRunsToken() {
    StepContainerSettings launcher = StepFixtures.shippedLauncher();

    assertThrows(
        NullPointerException.class,
        () ->
            StepWorkloadSpecs.compose(
                launcher.workloadSettings(),
                StepFixtures.plane(launcher),
                StepFixtures.sampleStep(0, false, false),
                null,
                null));
  }

  @Test
  void theRunnersOwnStepMemoryLimitReplacesThePlatformDefaultAsMemoryAndSwap() {
    StepContainerSettings launcher = StepFixtures.shippedLauncher();

    WorkloadSpec capped =
        StepWorkloadSpecs.compose(
            launcher.workloadSettings(),
            StepFixtures.plane(launcher),
            StepFixtures.sampleStep(0, false, false),
            StepFixtures.token(),
            "6g");

    assertEquals("6g", capped.memory());
    assertEquals("6g", capped.memorySwap());
    assertTrue(compose(launcher, false, false).memory().equals("4g"));
  }
}
