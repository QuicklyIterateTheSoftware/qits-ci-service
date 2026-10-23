package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonSocket;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every address a CI lease is told is the public name of the service that answers it. */
class CiSlotAddressesTest {

  @Test
  void everyAddressIsThePublicNameOfTheServiceThatAnswersIt() {
    CiSlotAddresses.Plane plane = SlotFixtures.plane();
    assertEquals("example.org", plane.domain());
    assertEquals("wss://ci.qits.example.org/ci/daemon", plane.daemonUrl());
    assertEquals("https://githost.qits.example.org", plane.gitBaseUrl());
    assertEquals("https://workspaces.qits.example.org", plane.workspacesUrl());
    assertEquals(
        "https://registry.qits.example.org/artifacts/maven/maven", plane.mavenRepositoryUrl());
    assertEquals(
        "https://mirror.qits.example.org/mirror/maven/central", plane.mavenCentralUrl());
    assertEquals(
        List.of("registry.qits.example.org", "mirror.qits.example.org"), plane.authHosts());
  }

  @Test
  void theDaemonSocketIsTheSocketsOwnLiteral() {
    assertTrue(SlotFixtures.plane().daemonUrl().endsWith(CiDaemonSocket.PATH));
  }

  @Test
  void theRunsPinnedDaemonIsDownloadedFromThePublicRegistry() {
    assertEquals(
        "https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/2026.1",
        SlotFixtures.plane()
            .daemonBinaryUrl(CiSlotAddresses.daemonBinaryPath("qits-ci-daemon", "2026.1")));
    assertEquals("", SlotFixtures.plane().daemonBinaryUrl(" "));
  }

  @Test
  void theSlotImageIsAskedForUnderTheRegistrysPublicNameWhateverHostThePinNamed() {
    CiSlotAddresses.Plane plane = SlotFixtures.plane();
    String digest = "@sha256:" + "b".repeat(64);
    assertEquals(
        "registry.qits.example.org/qits/build-images/ci-slot" + digest,
        plane.slotImage("dev-qits-artifacts:8080/qits/build-images/ci-slot" + digest));
    assertEquals(
        "registry.qits.example.org/qits/build-images/ci-slot:latest",
        plane.slotImage("qits/build-images/ci-slot:latest"),
        "a deployment that pins nothing asks for the tag");
    assertEquals(
        "registry.qits.example.org/qits/build-images/ci-slot:latest",
        plane.slotImage("registry.qits.example.org/qits/build-images/ci-slot:latest"));
  }

  @Test
  void noPublicDomainMeansNoAddresses() {
    assertTrue(CiSlotAddresses.forDomain("").plane().isEmpty());
    assertTrue(CiSlotAddresses.forDomain("localhost").plane().isEmpty());
    assertTrue(CiSlotAddresses.forDomain(null).plane().isEmpty());
    assertEquals(
        "example.org", CiSlotAddresses.forDomain(".Example.ORG.").plane().orElseThrow().domain());
  }
}
