package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiRepoRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The addresses a step is told: the public name of each service, composed from the platform's
 * domain, with the service's own path. Plain JUnit — the plane is a pure value.
 */
class StepAddressPlaneTest {

  private static final String D = "example.org";

  @Test
  void everyAddressIsThePublicNameOfTheServiceThatAnswersIt() {
    StepAddressPlane plane = StepFixtures.plane();

    assertEquals("wss://ci.qits.example.org/ci/daemon", plane.daemonUrl());
    assertEquals("https://githost.qits.example.org", plane.gitBaseUrl());
    assertEquals(
        "https://githost.qits.example.org/git/qits/qits-ci-service",
        StepWorkloadSpecs.cloneUrl(plane.gitBaseUrl(), CiRepoRef.of("r", "qits", "qits-ci-service")));
    assertEquals("registry.qits.example.org", plane.registryHost());
    assertEquals("https://registry.qits.example.org/artifacts/npm/npm/", plane.npmHostedUrl());
    assertEquals("https://mirror.qits.example.org/npm/npmjs/", plane.npmProxyUrl());
    assertEquals("https://registry.qits.example.org/artifacts/maven/maven", plane.mavenRegistryUrl());
    assertEquals(
        "https://mirror.qits.example.org/mirror/maven/central", plane.mavenCentralMirrorUrl());
    assertEquals("https://registry.qits.example.org/artifacts/docs/docs", plane.docsUrl());
    assertEquals("https://registry.qits.example.org", plane.artifactsUrl());
    assertEquals("https://workspaces.qits.example.org", plane.workspacesUrl());
    assertEquals(
        List.of("registry.qits.example.org", "mirror.qits.example.org"), plane.authHosts());
  }

  @Test
  void theDaemonSocketPathIsTheSocketsOwnLiteral() {
    // One string in two packages: move the socket's path and this composition moves with it.
    assertEquals(eu.wohlben.qits.ci.daemonhost.CiDaemonSocket.PATH, StepAddressPlane.DAEMON_SOCKET_PATH);
  }

  @Test
  void theRunsPinnedDaemonPathIsDownloadedFromThePublicRegistry() {
    StepAddressPlane plane = StepFixtures.plane();
    String pinned = StepAddressPlane.daemonBinaryPath("qits-ci-daemon", "2026.927.1");

    assertEquals("/artifacts/daemons/qits-ci-daemon/2026.927.1", pinned);
    assertEquals(
        "https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.927.1",
        plane.daemonBinaryUrl(pinned));
    // What the settings bean pins for a run is exactly that path.
    assertEquals(pinned, StepFixtures.shippedLauncher().resolveBinaryUrl("2026.927.1"));
    // A whole url — a pin taken by a qits-ci that still spelled the store's alias — keeps its path
    // and loses its origin.
    assertEquals(
        "https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.927.1",
        plane.daemonBinaryUrl(
            "http://dev-qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/2026.927.1"));
    assertEquals("", plane.daemonBinaryUrl(null));
    assertThrows(IllegalStateException.class, () -> plane.daemonBinaryUrl("not a url"));
  }

  @Test
  void noPublicDomainMeansNoAddressesToCompose() {
    RunnerAddresses local = RunnerAddressesFixture.withDomain("localhost");

    assertFalse(local.edgeAvailable());
    assertTrue(local.edgeOrigins().isEmpty());
    assertTrue(RunnerAddressesFixture.withDomain(null).edgeOrigins().isEmpty());
    assertTrue(RunnerAddressesFixture.withDomain(D).edgeAvailable());
  }

  @Test
  void theSpellingsOfThePlatformsStoresAreTheRegistryKeyTheMachineVhostsAndThisEnvironmentsAliases() {
    StepAddressPlane.ImageRegistries spellings = StepFixtures.shippedLauncher().imageSpellings();

    assertEquals(
        List.of("dev-qits-artifacts:8080", "registry.dev.localhost:8080", "localhost:8081"),
        spellings.registrySpellings());
    assertEquals(
        List.of("mirror.dev.localhost:8080", "localhost:8082", "dev-qits-platform-mirror:8080"),
        spellings.mirrorSpellings());
  }
}
