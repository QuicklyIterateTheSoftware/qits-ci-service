package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.StubIdp;
import eu.wohlben.qits.ci.runnerhost.StepContainerSettings.LaunchSpec;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The whole environment of one INTERNAL step container, pinned literally</b> — every key, in
 * order, with its value — and the address fields of the spec beside it.
 *
 * <p>Written before the addresses moved into {@code StepAddressPlane} and run green on the code as it
 * was, so the refactor is held to producing byte for byte what shipped: an INTERNAL step is the step
 * every run on the estate is today, and nothing about introducing a second plane may move one of its
 * values. The other suites assert keys one at a time; this one is the only place a new key, a
 * dropped key or a reordered one fails a build.
 *
 * <p>The fixture is the shipped defaults with {@code QITS_ENVIRONMENT=dev} resolved, the edge
 * vhosts {@code docker-auth-hosts} names on a live deployment, and a commissioned run — the arm
 * that carries the most keys. Plain JUnit against {@link StubIdp}, like {@code RunCommissioningTest}.
 */
public class StepEnvironmentCharacterizationTest {

  private static final String RUN = "0123456789abcdef-run";

  private StubIdp idp;

  @BeforeEach
  void startStub() {
    idp = new StubIdp();
  }

  @AfterEach
  void stopStub() {
    idp.close();
  }

  /** The shipped defaults, {@code dev} resolved — every address key a step container is told. */
  static StepContainerSettings shippedLauncher(String idpUrl) {
    StepContainerSettings launcher = new StepContainerSettings();
    launcher.network = "qits-net";
    launcher.containerGitUrl = "http://dev-qits-platform-edge:8080";
    launcher.idpUrl = idpUrl;
    launcher.containerDaemonUrl = "ws://dev-qits-ci:8080/ci/daemon";
    launcher.daemonBinaryUrlTemplate =
        "http://dev-qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/{version}";
    launcher.memoryLimit = "4g";
    launcher.pidsLimit = 2048;
    launcher.cpus = "2";
    launcher.oomScoreAdj = 1000;
    launcher.artifactsRegistryHost = "dev-qits-artifacts:8080";
    launcher.artifactsImageRepository = "qits";
    launcher.dockerAuthHosts = List.of("registry.dev.localhost:8080", "mirror.dev.localhost:8080");
    launcher.buildkitEnabled = true;
    launcher.buildkitRegistryHost = "dev-qits-artifacts:8080";
    launcher.artifactsNpmHostedUrl = "http://dev-qits-artifacts:8080/artifacts/npm/npm/";
    launcher.environment = "dev";
    launcher.artifactsMavenRegistryUrl = "http://dev-qits-artifacts:8080/artifacts/maven/maven";
    launcher.mavenCentralMirrorEnabled = true;
    launcher.mavenCentralMirrorBuildUrl =
        Optional.of("http://mirror.dev.localhost:8080/mirror/maven/central");
    launcher.mavenCentralMirrorStepUrl =
        "http://dev-qits-platform-mirror:8080/mirror/maven/central";
    launcher.artifactsDocsUrl = "http://dev-qits-artifacts:8080/artifacts/docs/docs";
    // Blank, as shipped: $QITS_ARTIFACTS_URL is derived from the maven root.
    launcher.artifactsUrl = Optional.empty();
    launcher.artifactsCliPackage = "qits";
    launcher.artifactsCliVersionOverride = Optional.empty();
    launcher.workspacesUrl = "http://dev-qits-workspaces:8080";
    return launcher;
  }

  /** A {@code docker: true} step of a MaintenanceBump run, name-addressed — the widest env there is. */
  static LaunchSpec sampleStep(int stepIndex, boolean docker, boolean build) {
    return new LaunchSpec(
        RUN,
        stepIndex,
        CiRepoRef.of("5ca0e7e9-repo", "qits", "qits-ci-service"),
        "main",
        "cafebabecafebabecafebabecafebabecafebabe",
        "registry.dev.localhost:8080/qits/java-builder:25",
        "daemon-7",
        "s3cr3t",
        "http://dev-qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/2026.927.1",
        600,
        docker,
        build,
        "",
        Map.of(
            "QITS_EVENT_ID", "0b5f3c1e-0000-4000-8000-000000000001",
            "QITS_EVENT_NAME", "MaintenanceBump",
            "QITS_EVENT_OCCURRED_AT", "2026-09-12T10:00:00Z",
            "QITS_EVENT_PAYLOAD", "{\"branch\":\"maintenance/dependencies\"}"));
  }

  @Test
  public void anInternalPublishingStepIsExactlyTheStepThatShipped() {
    StepContainerSettings launcher = shippedLauncher(idp.authServerUrl());
    RunCommissions commissions = idp.runCommissions(Duration.ofMillis(200));
    LaunchSpec step = sampleStep(2, true, false);

    WorkloadSpec spec = compose(launcher, launcher.internalPlane(), step, commissions);

    String auth =
        Base64.getEncoder()
            .encodeToString("run-client-1:run-s3cr3t-1".getBytes(StandardCharsets.UTF_8));
    List<String> expected =
        List.of(
            "QITS_CI_DAEMON_ID=daemon-7",
            "QITS_CI_DAEMON_SECRET=s3cr3t",
            "QITS_CI_DAEMON_URL=ws://dev-qits-ci:8080/ci/daemon",
            "QITS_CI_DAEMON_BINARY_URL=http://dev-qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/2026.927.1",
            "QITS_CI_REPOSITORY_URL=http://dev-qits-platform-edge:8080/git/qits/qits-ci-service",
            "QITS_CI_BRANCH=main",
            "QITS_CI_SHA=cafebabecafebabecafebabecafebabecafebabe",
            "QITS_CI_REPO_ID=5ca0e7e9-repo",
            "QITS_CI_PROJECT_ID=qits",
            "QITS_CI_REPO_NAME=qits-ci-service",
            "CI=true",
            "QITS_CI=true",
            "QITS_REGISTRY=dev-qits-artifacts:8080",
            "QITS_IMAGE_REPOSITORY=qits",
            "QITS_NPM_REGISTRY_URL=http://dev-qits-artifacts:8080/artifacts/npm/npm/",
            "QITS_NPM_PROXY_URL=http://dev-qits-platform-mirror:8080/npm/npmjs/",
            "QITS_MAVEN_REGISTRY_URL=http://dev-qits-artifacts:8080/artifacts/maven/maven",
            "QITS_MAVEN_CENTRAL_MIRROR_URL=http://mirror.dev.localhost:8080/mirror/maven/central",
            "QITS_MAVEN_PROXY_URL=http://dev-qits-platform-mirror:8080/mirror/maven/central",
            "QITS_DOCS_URL=http://dev-qits-artifacts:8080/artifacts/docs/docs",
            "QITS_ARTIFACTS_URL=http://dev-qits-artifacts:8080",
            "QITS_ARTIFACTS_CLI_PACKAGE=qits",
            "QITS_ARTIFACTS_CLI_VERSION=" + PlatformAccessCliBinary.VERSION,
            "QITS_WORKSPACES_URL=http://dev-qits-workspaces:8080",
            "QITS_COMMISSIONED_CLIENT_ID=run-client-1",
            "QITS_COMMISSIONED_CLIENT_SECRET=run-s3cr3t-1",
            "QITS_GIT_AUTH_TOKEN_URL=" + idp.authServerUrl() + "/token",
            "QITS_GIT_AUTH_HOST=dev-qits-platform-edge:8080",
            "QITS_GIT_AUTH_AUDIENCE=qits-platform",
            "GIT_CONFIG_GLOBAL=/tmp/qits-gitconfig",
            "QITS_PUBLISH_TOKEN_COMMAND=/tmp/qits-publish-token",
            "DOCKER_BUILDKIT=1",
            "BUILDX_NO_DEFAULT_ATTESTATIONS=1",
            "QITS_BUILD_REGISTRY=dev-qits-artifacts:8080",
            "DOCKER_CONFIG=/tmp/qits-ci-registry-auth",
            "QITS_CI_REGISTRY_AUTH_CONFIG={\"auths\":{"
                + "\"registry.dev.localhost:8080\":{\"auth\":\"" + auth + "\"},"
                + "\"mirror.dev.localhost:8080\":{\"auth\":\"" + auth + "\"},"
                + "\"dev-qits-artifacts:8080\":{\"auth\":\"" + auth + "\"}}}",
            "QITS_EVENT_ID=0b5f3c1e-0000-4000-8000-000000000001",
            "QITS_EVENT_NAME=MaintenanceBump",
            "QITS_EVENT_OCCURRED_AT=2026-09-12T10:00:00Z",
            "QITS_EVENT_PAYLOAD={\"branch\":\"maintenance/dependencies\"}");
    assertEquals(expected, entries(spec.env()));
    assertEquals("qits-net", spec.network());
    assertEquals(List.of("host.docker.internal:host-gateway"), spec.extraHosts());
  }

  @Test
  public void anInternalPlainStepOnADeploymentThatCommissionsNothingIsExactlyTheStepThatShipped() {
    StepContainerSettings launcher = shippedLauncher(idp.authServerUrl());
    RunCommissions commissions = StubIdp.disabledCommissions();
    launcher.buildkitEnabled = false;
    launcher.mavenCentralMirrorEnabled = false;
    LaunchSpec step = sampleStep(0, false, true);

    WorkloadSpec spec = compose(launcher, launcher.internalPlane(), step, commissions);

    List<String> expected =
        List.of(
            "QITS_CI_DAEMON_ID=daemon-7",
            "QITS_CI_DAEMON_SECRET=s3cr3t",
            "QITS_CI_DAEMON_URL=ws://dev-qits-ci:8080/ci/daemon",
            "QITS_CI_DAEMON_BINARY_URL=http://dev-qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/2026.927.1",
            "QITS_CI_REPOSITORY_URL=http://dev-qits-platform-edge:8080/git/qits/qits-ci-service",
            "QITS_CI_BRANCH=main",
            "QITS_CI_SHA=cafebabecafebabecafebabecafebabecafebabe",
            "QITS_CI_REPO_ID=5ca0e7e9-repo",
            "QITS_CI_PROJECT_ID=qits",
            "QITS_CI_REPO_NAME=qits-ci-service",
            "CI=true",
            "QITS_CI=true",
            "QITS_REGISTRY=dev-qits-artifacts:8080",
            "QITS_IMAGE_REPOSITORY=qits",
            "QITS_NPM_REGISTRY_URL=http://dev-qits-artifacts:8080/artifacts/npm/npm/",
            "QITS_NPM_PROXY_URL=http://dev-qits-platform-mirror:8080/npm/npmjs/",
            "QITS_MAVEN_REGISTRY_URL=http://dev-qits-artifacts:8080/artifacts/maven/maven",
            "QITS_MAVEN_CENTRAL_MIRROR_URL=",
            "QITS_MAVEN_PROXY_URL=",
            "QITS_DOCS_URL=http://dev-qits-artifacts:8080/artifacts/docs/docs",
            "QITS_ARTIFACTS_URL=http://dev-qits-artifacts:8080",
            "QITS_ARTIFACTS_CLI_PACKAGE=qits",
            "QITS_ARTIFACTS_CLI_VERSION=" + PlatformAccessCliBinary.VERSION,
            "QITS_WORKSPACES_URL=http://dev-qits-workspaces:8080",
            "QITS_BUILD_REGISTRY=",
            "BUILDKIT_HOST=",
            "QITS_EVENT_ID=0b5f3c1e-0000-4000-8000-000000000001",
            "QITS_EVENT_NAME=MaintenanceBump",
            "QITS_EVENT_OCCURRED_AT=2026-09-12T10:00:00Z",
            "QITS_EVENT_PAYLOAD={\"branch\":\"maintenance/dependencies\"}");
    assertEquals(expected, entries(spec.env()));
    assertEquals("qits-net", spec.network());
    assertEquals(List.of("host.docker.internal:host-gateway"), spec.extraHosts());
    assertNull(spec.env().get("QITS_COMMISSIONED_CLIENT_ID"));
  }

  /** Composes exactly what {@code StepContainerSettings#launch} used to, before it was deleted. */
  static WorkloadSpec compose(
      StepContainerSettings launcher,
      StepAddressPlane plane,
      LaunchSpec step,
      RunCommissions commissions) {
    RunCommissions.Credential credential =
        RunCommissions.Credential.client(
            commissions == null ? null : commissions.forRun(step.runId(), step.env()));
    return StepWorkloadSpecs.compose(launcher.workloadSettings(), plane, step, credential, null);
  }

  /** The env as {@code KEY=value} lines in its own iteration order, so order is asserted too. */
  static List<String> entries(Map<String, String> env) {
    List<String> lines = new ArrayList<>();
    env.forEach((key, value) -> lines.add(key + "=" + value));
    return lines;
  }
}
