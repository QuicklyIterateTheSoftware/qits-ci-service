package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * <b>A step's image as the runner pulls it</b> (qits-479). The live failure this pins: a runner
 * outside the swarm was handed {@code registry.dev.localhost:8080/qits/build-images/…@sha256:…},
 * which its own docker dialled at {@code [::1]:8080}. A platform registry host is moved to its
 * public vhost with path, tag and digest kept; somebody else's registry is untouched.
 */
class StepImageTest {

  private static final String TOKEN = StepFixtures.TOKEN;
  private static final String DIGEST =
      "sha256:25cf82f5aa0f5a3a1c8b0f8e0d7f6e5d4c3b2a1908f7e6d5c4b3a29181706f5e";

  private static WorkloadSpec compose(String image, boolean docker) {
    StepContainerSettings launcher = StepFixtures.shippedLauncher();
    return StepWorkloadSpecs.compose(
        launcher.workloadSettings(),
        StepFixtures.plane(launcher),
        StepFixtures.sampleStep(0, docker, false, image),
        StepFixtures.token(),
        null);
  }

  private static String document(String... hosts) {
    String auth =
        Base64.getEncoder().encodeToString(("token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
    StringBuilder json = new StringBuilder("{\"auths\":{");
    for (int i = 0; i < hosts.length; i++) {
      json.append(i == 0 ? "" : ",")
          .append('"')
          .append(hosts[i])
          .append("\":{\"auth\":\"")
          .append(auth)
          .append("\"}");
    }
    return json.append("}}").toString();
  }

  @Test
  void aStepPullsAPinnedPlatformImageFromThePublicRegistryWithItsDigestKept() {
    WorkloadSpec spec =
        compose("registry.dev.localhost:8080/qits/build-images/node-docker-base@" + DIGEST, false);

    assertEquals("registry.qits.example.org/qits/build-images/node-docker-base@" + DIGEST, spec.image());
    // The runner pulls with exactly this document, and it logs in where the image comes from.
    assertEquals(document("registry.qits.example.org"), spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"));
    // Not a build: nothing in the container writes or reads a docker login.
    assertFalse(spec.env().containsKey("DOCKER_CONFIG"));
  }

  @Test
  void everySpellingOfThePlatformRegistryMovesAndTheTagIsKept() {
    StepAddressPlane edge = StepFixtures.plane();

    // The alias qits.artifacts.registry-host names in this fixture, and the machine vhost.
    assertEquals(
        "registry.qits.example.org/qits/java-builder:25",
        edge.imageReference("dev-qits-artifacts:8080/qits/java-builder:25"));
    assertEquals(
        "registry.qits.example.org/qits/java-builder:25",
        edge.imageReference("REGISTRY.dev.localhost:8080/qits/java-builder:25"));
    assertEquals(
        "registry.qits.example.org/qits/java-builder@" + DIGEST,
        edge.imageReference("registry.dev.localhost:8080/qits/java-builder@" + DIGEST));
  }

  @Test
  void anImageFromTheMirrorVhostIsPulledFromThePublicMirror() {
    WorkloadSpec spec = compose("mirror.dev.localhost:8080/library/alpine:3", false);

    assertEquals("mirror.qits.example.org/library/alpine:3", spec.image());
    assertEquals(document("mirror.qits.example.org"), spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"));
  }

  @Test
  void aThirdPartyImageIsPulledAsNamedWithNoLogin() {
    for (String image :
        new String[] {"alpine:3", "docker.io/library/alpine:3", "ghcr.io/o/i@" + DIGEST}) {
      WorkloadSpec spec = compose(image, false);

      assertEquals(image, spec.image());
      assertNull(spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"), image);
    }
  }

  @Test
  void aBuildStepKeepsItsWholeDocumentWhichCoversItsImage() {
    WorkloadSpec spec =
        compose("registry.dev.localhost:8080/qits/build-images/node-docker-base@" + DIGEST, true);

    assertEquals("registry.qits.example.org/qits/build-images/node-docker-base@" + DIGEST, spec.image());
    assertEquals(
        document("registry.qits.example.org", "mirror.qits.example.org"),
        spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"));
  }

  @Test
  void theMirrorsAliasAndTheLoopbackSpellingsAreRecognisedToo() {
    StepAddressPlane plane = StepFixtures.plane();

    assertEquals(
        "mirror.qits.example.org/library/alpine:3",
        plane.imageReference("dev-qits-platform-mirror:8080/library/alpine:3"));
    assertEquals(
        "registry.qits.example.org/qits/x:1", plane.imageReference("localhost:8081/qits/x:1"));
    assertEquals(
        "mirror.qits.example.org/library/alpine:3",
        plane.imageReference("localhost:8082/library/alpine:3"));
    // Another environment's alias is not this platform's store.
    assertEquals(
        "prod-qits-artifacts:8080/qits/x:1", plane.imageReference("prod-qits-artifacts:8080/qits/x:1"));
  }
}
