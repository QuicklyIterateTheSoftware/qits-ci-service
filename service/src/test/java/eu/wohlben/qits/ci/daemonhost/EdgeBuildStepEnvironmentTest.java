package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.runnerhost.RunnerAddressesFixture;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The whole environment of one EDGE build step, pinned literally</b> (qits-479) — the edge
 * plane's twin of {@link StepEnvironmentCharacterizationTest}, for a {@code docker: true} step of an
 * event-triggered run on {@code example.org} holding a {@code ci-run} token.
 *
 * <p>What it holds is that a build on a runner outside the swarm is told the registry's PUBLIC vhost
 * both as the image registry and as the builder's push registry, that {@code BUILDKIT_HOST} stays
 * absent for the runner to fill, that the docker document logs into every public registry host with
 * the token, and that no key anywhere spells an internal alias — the kill switch's empty pair being
 * the one deliberate exception, and a value rather than an address.
 */
class EdgeBuildStepEnvironmentTest {

  private static final String TOKEN = "qits_tok_edge-run-123";
  private static final String SUBJECT = "tok-ci-run-0123456789abcdef-run-1";

  private static Spec compose(CiDaemonLauncher launcher, boolean docker, boolean build) {
    StepAddressPlane edge =
        StepAddressPlane.edge(
            RunnerAddressesFixture.withDomain("example.org").edgeOrigins().orElseThrow(),
            launcher.internalPlane());
    return StepWorkloadSpecs.compose(
        launcher.workloadSettings(),
        edge,
        StepEnvironmentCharacterizationTest.sampleStep(2, docker, build),
        RunCommissions.Credential.token(
            new IdpCommissioner.CommissionedToken("token-1", TOKEN, SUBJECT)));
  }

  private static CiDaemonLauncher shipped() {
    return StepEnvironmentCharacterizationTest.shippedLauncher(
        "http://dev-qits-platform-idp:8080/idp");
  }

  @Test
  void anEdgeBuildStepIsTheWholeOfThisEnvironment() {
    Spec spec = compose(shipped(), true, false);

    String auth =
        Base64.getEncoder().encodeToString(("token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
    List<String> expected =
        List.of(
            "QITS_CI_DAEMON_ID=daemon-7",
            // No QITS_CI_DAEMON_SECRET on the edge plane: the ci-run token already proves the run,
            // and the launch names itself in its Hello instead (CiDaemonRegistry.admitByToken).
            "QITS_CI_DAEMON_URL=wss://ci.qits.example.org/ci/daemon",
            "QITS_CI_DAEMON_BINARY_URL=https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.927.1",
            "QITS_CI_REPOSITORY_URL=https://githost.qits.example.org/git/qits/qits-ci-service",
            "QITS_CI_BRANCH=main",
            "QITS_CI_SHA=cafebabecafebabecafebabecafebabecafebabe",
            "QITS_CI_REPO_ID=5ca0e7e9-repo",
            "QITS_CI_PROJECT_ID=qits",
            "QITS_CI_REPO_NAME=qits-ci-service",
            "CI=true",
            "QITS_CI=true",
            "QITS_REGISTRY=registry.qits.example.org",
            "QITS_IMAGE_REPOSITORY=qits",
            "QITS_NPM_REGISTRY_URL=https://registry.qits.example.org/artifacts/npm/npm/",
            "QITS_NPM_PROXY_URL=https://mirror.qits.example.org/npm/npmjs/",
            "QITS_MAVEN_REGISTRY_URL=https://registry.qits.example.org/artifacts/maven/maven",
            "QITS_MAVEN_CENTRAL_MIRROR_URL=https://mirror.qits.example.org/mirror/maven/central",
            "QITS_MAVEN_PROXY_URL=https://mirror.qits.example.org/mirror/maven/central",
            "QITS_DOCS_URL=https://registry.qits.example.org/artifacts/docs/docs",
            "QITS_ARTIFACTS_URL=https://registry.qits.example.org",
            "QITS_ARTIFACTS_CLI_PACKAGE=qits",
            "QITS_ARTIFACTS_CLI_VERSION=" + PlatformAccessCliBinary.VERSION,
            "QITS_WORKSPACES_URL=https://workspaces.qits.example.org",
            "QITS_TOKEN=" + TOKEN,
            "QITS_TOKEN_SUBJECT=" + SUBJECT,
            "GIT_CONFIG_GLOBAL=/tmp/qits-gitconfig",
            "QITS_PUBLISH_TOKEN_COMMAND=/tmp/qits-publish-token",
            "DOCKER_BUILDKIT=1",
            "BUILDX_NO_DEFAULT_ATTESTATIONS=1",
            "QITS_BUILD_REGISTRY=registry.qits.example.org",
            "DOCKER_CONFIG=/tmp/qits-ci-registry-auth",
            "QITS_CI_REGISTRY_AUTH_CONFIG={\"auths\":{"
                + "\"registry.qits.example.org\":{\"auth\":\"" + auth + "\"},"
                + "\"mirror.qits.example.org\":{\"auth\":\"" + auth + "\"}}}",
            "QITS_EVENT_ID=0b5f3c1e-0000-4000-8000-000000000001",
            "QITS_EVENT_NAME=MaintenanceBump",
            "QITS_EVENT_OCCURRED_AT=2026-09-12T10:00:00Z",
            "QITS_EVENT_PAYLOAD={\"branch\":\"maintenance/dependencies\"}");
    assertEquals(expected, StepEnvironmentCharacterizationTest.entries(spec.env()));
    assertFalse(spec.env().containsKey("BUILDKIT_HOST"), "the runner fills it, as qits-containers does");
    assertNull(spec.network());
    assertEquals(List.of(), spec.addHosts());
    // Still the declared opt-in, and still the only thing about a step that asks for the socket.
    assertEquals(true, spec.hostDockerSocket());
  }

  @Test
  void aBuildStepWithNoSocketGetsTheSameBuildEnvironmentWithoutTheLegacyFlags() {
    Map<String, String> env = compose(shipped(), false, true).env();

    assertEquals("registry.qits.example.org", env.get("QITS_BUILD_REGISTRY"));
    assertEquals("registry.qits.example.org", env.get("QITS_REGISTRY"));
    assertEquals("qits", env.get("QITS_IMAGE_REPOSITORY"));
    assertFalse(env.containsKey("BUILDKIT_HOST"));
    assertFalse(env.containsKey("DOCKER_BUILDKIT"), "a buildctl step has no docker CLI to steer");
    assertEquals("/tmp/qits-ci-registry-auth", env.get("DOCKER_CONFIG"));
  }

  @Test
  void theKillSwitchSendsTheEmptyPairOnTheEdgeToo() {
    CiDaemonLauncher off = shipped();
    off.buildkitEnabled = false;

    Map<String, String> env = compose(off, false, true).env();

    assertEquals("", env.get("QITS_BUILD_REGISTRY"));
    assertEquals("", env.get("BUILDKIT_HOST"));
    // The document still names the public hosts: they come from the plane, not from the switch.
    String document = env.get("QITS_CI_REGISTRY_AUTH_CONFIG");
    assertTrue(document.contains("\"registry.qits.example.org\""), document);
    assertTrue(document.contains("\"mirror.qits.example.org\""), document);
    assertFalse(document.contains("dev-qits-artifacts"), document);
    assertFalse(document.contains(".localhost"), document);
  }

  @Test
  void noKeyOfAnEdgeBuildStepSpellsAnInternalAddress() {
    compose(shipped(), true, false)
        .env()
        .forEach(
            (key, value) -> {
              assertFalse(value.contains("dev-qits-"), key + " names a qits-net alias: " + value);
              assertFalse(value.contains(".localhost"), key + " names a host-loopback vhost: " + value);
              assertFalse(value.contains("http://"), key + " is not TLS through the edge: " + value);
            });
  }
}
