package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.runnerhost.RunnerAddresses;
import eu.wohlben.qits.ci.runnerhost.RunnerAddressesFixture;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two planes' addresses: the internal one is the launcher's keys as they always were (the whole
 * environment is pinned by {@link StepEnvironmentCharacterizationTest}), and the edge one is each of
 * those with its origin moved to the public name of the service that answers it — asserted value by
 * value for {@code example.org}, so a service composed onto the wrong vhost fails here by name.
 */
class StepAddressPlaneTest {

  private static final String D = "example.org";

  private static StepAddressPlane edge() {
    RunnerAddresses addresses = RunnerAddressesFixture.withDomain(D);
    StepAddressPlane internal = StepEnvironmentCharacterizationTest.shippedLauncher("http://dev-qits-platform-idp:8080/idp").internalPlane();
    return StepAddressPlane.edge(addresses.edgeOrigins().orElseThrow(), internal);
  }

  @Test
  void theInternalPlaneIsTheLaunchersOwnKeys() {
    StepAddressPlane internal =
        StepEnvironmentCharacterizationTest.shippedLauncher("http://dev-qits-platform-idp:8080/idp")
            .internalPlane();

    assertEquals(CiRunnerPlane.INTERNAL, internal.plane());
    assertFalse(internal.isEdge());
    assertEquals("qits-net", internal.network());
    assertEquals(List.of("host.docker.internal:host-gateway"), internal.extraHosts());
    // The pinned url is the internal plane's as it is — never recomposed.
    assertEquals("http://anything/at/all", internal.daemonBinaryUrl("http://anything/at/all"));
  }

  @Test
  void everyEdgeAddressIsThePublicNameOfTheSameServiceWithThePathKept() {
    StepAddressPlane edge = edge();

    assertEquals(CiRunnerPlane.EDGE, edge.plane());
    assertTrue(edge.isEdge());
    assertEquals("wss://ci.qits.example.org/ci/daemon", edge.daemonUrl());
    assertEquals(
        "https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.927.1",
        edge.daemonBinaryUrl(
            "http://dev-qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/2026.927.1"));
    assertEquals("https://githost.qits.example.org", edge.gitBaseUrl());
    assertEquals(
        "https://githost.qits.example.org/git/qits/qits-ci-service",
        StepWorkloadSpecs.cloneUrl(
            edge.gitBaseUrl(),
            eu.wohlben.qits.ci.control.CiRepoRef.of("r", "qits", "qits-ci-service")));
    assertNull(edge.idpUrl(), "an edge step carries a token, never a client to mint with");
    assertEquals("registry.qits.example.org", edge.registryHost());
    assertEquals("registry.qits.example.org", edge.buildRegistryHost());
    assertEquals("https://registry.qits.example.org/artifacts/npm/npm/", edge.npmHostedUrl());
    assertEquals("https://mirror.qits.example.org/artifacts/npm/npmjs/", edge.npmProxyUrl());
    assertEquals("https://registry.qits.example.org/artifacts/maven/maven", edge.mavenRegistryUrl());
    assertEquals(
        "https://mirror.qits.example.org/mirror/maven/central", edge.mavenCentralMirrorBuildUrl());
    assertEquals(
        "https://mirror.qits.example.org/mirror/maven/central", edge.mavenCentralMirrorStepUrl());
    assertEquals("https://registry.qits.example.org/artifacts/docs/docs", edge.docsUrl());
    assertEquals("https://registry.qits.example.org", edge.artifactsUrl());
    assertEquals("https://workspaces.qits.example.org", edge.workspacesUrl());
    assertEquals(
        List.of("registry.qits.example.org", "mirror.qits.example.org"), edge.authHosts());
    assertNull(edge.network(), "no network: the runner's host has no qits-net");
    assertEquals(List.of(), edge.extraHosts());
  }

  @Test
  void aValueSwitchedOffInternallyStaysOffOnTheEdge() {
    CiDaemonLauncher launcher =
        StepEnvironmentCharacterizationTest.shippedLauncher("http://dev-qits-platform-idp:8080/idp");
    launcher.mavenCentralMirrorBuildUrl = Optional.empty();
    StepAddressPlane edge =
        StepAddressPlane.edge(
            RunnerAddressesFixture.withDomain(D).edgeOrigins().orElseThrow(),
            launcher.internalPlane());

    assertEquals("", edge.mavenCentralMirrorBuildUrl());
  }

  @Test
  void noPublicDomainMeansNoEdgePlane() {
    RunnerAddresses local = RunnerAddressesFixture.withDomain("localhost");

    assertFalse(local.edgeAvailable());
    assertTrue(local.edgeOrigins().isEmpty());
    assertTrue(RunnerAddressesFixture.withDomain(D).edgeAvailable());
  }

  @Test
  void anEdgeStepHasNoNetworkNoExtraHostAndThePublicNamesInItsEnvironment() {
    CiDaemonLauncher launcher =
        StepEnvironmentCharacterizationTest.shippedLauncher("http://dev-qits-platform-idp:8080/idp");

    Spec spec =
        StepWorkloadSpecs.compose(
            launcher.workloadSettings(),
            edge(),
            StepEnvironmentCharacterizationTest.sampleStep(0, false, false),
            null);

    assertNull(spec.network());
    assertEquals(List.of(), spec.addHosts());
    // The image is an address too, and the one the runner's own docker dials (qits-479).
    assertEquals("registry.qits.example.org/qits/java-builder:25", spec.image());
    assertEquals("wss://ci.qits.example.org/ci/daemon", spec.env().get("QITS_CI_DAEMON_URL"));
    assertEquals(
        "https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.927.1",
        spec.env().get("QITS_CI_DAEMON_BINARY_URL"));
    assertEquals(
        "https://githost.qits.example.org/git/qits/qits-ci-service",
        spec.env().get("QITS_CI_REPOSITORY_URL"));
    // And not one internal alias anywhere in it.
    spec.env()
        .forEach(
            (key, value) ->
                assertFalse(
                    value.contains("dev-qits-") || value.contains(".localhost"),
                    key + " still names an internal address: " + value));
  }
}
