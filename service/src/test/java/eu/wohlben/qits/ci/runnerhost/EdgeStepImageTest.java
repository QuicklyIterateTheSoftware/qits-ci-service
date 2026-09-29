package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * <b>A step's image as each plane pulls it</b> (qits-479). The live failure this pins: a runner
 * outside the swarm was handed {@code registry.dev.localhost:8080/qits/build-images/…@sha256:…},
 * which its own docker dialled at {@code [::1]:8080}. On the edge plane a platform registry host is
 * moved to its public vhost with path, tag and digest kept; somebody else's registry is untouched;
 * and on the internal plane nothing is.
 */
class EdgeStepImageTest {

  private static final String TOKEN = "qits_tok_edge-run-123";
  private static final String SUBJECT = "tok-ci-run-0123456789abcdef-run-1";
  private static final String DIGEST =
      "sha256:25cf82f5aa0f5a3a1c8b0f8e0d7f6e5d4c3b2a1908f7e6d5c4b3a29181706f5e";

  private static StepContainerSettings shipped() {
    return StepEnvironmentCharacterizationTest.shippedLauncher(
        "http://dev-qits-platform-idp:8080/idp");
  }

  private static StepAddressPlane edge(StepContainerSettings launcher) {
    return StepAddressPlane.edge(
        RunnerAddressesFixture.withDomain("example.org").edgeOrigins().orElseThrow(),
        launcher.internalPlane());
  }

  private static StepContainerSettings.LaunchSpec step(String image, boolean docker) {
    StepContainerSettings.LaunchSpec s = StepEnvironmentCharacterizationTest.sampleStep(0, docker, false);
    return new StepContainerSettings.LaunchSpec(
        s.runId(),
        s.stepIndex(),
        s.repo(),
        s.branch(),
        s.sha(),
        image,
        s.daemonId(),
        s.secret(),
        s.daemonBinaryUrl(),
        s.stepTimeoutSeconds(),
        s.docker(),
        s.build(),
        s.user(),
        s.env());
  }

  private static WorkloadSpec composeEdge(String image, boolean docker) {
    StepContainerSettings launcher = shipped();
    return StepWorkloadSpecs.compose(
        launcher.workloadSettings(),
        edge(launcher),
        step(image, docker),
        RunCommissions.Credential.token(
            new IdpCommissioner.CommissionedToken("token-1", TOKEN, SUBJECT)),
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
  void anEdgeStepPullsAPinnedPlatformImageFromThePublicRegistryWithItsDigestKept() {
    WorkloadSpec spec =
        composeEdge("registry.dev.localhost:8080/qits/build-images/node-docker-base@" + DIGEST, false);

    assertEquals("registry.qits.example.org/qits/build-images/node-docker-base@" + DIGEST, spec.image());
    // The runner pulls with exactly this document, and it logs in where the image comes from.
    assertEquals(document("registry.qits.example.org"), spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"));
    // Not a build: nothing in the container writes or reads a docker login.
    assertFalse(spec.env().containsKey("DOCKER_CONFIG"));
  }

  @Test
  void everySpellingOfThePlatformRegistryMovesAndTheTagIsKept() {
    StepAddressPlane edge = edge(shipped());

    // The internal alias qits.artifacts.registry-host names in this fixture, and the host vhost.
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
    WorkloadSpec spec = composeEdge("mirror.dev.localhost:8080/library/alpine:3", false);

    assertEquals("mirror.qits.example.org/library/alpine:3", spec.image());
    assertEquals(document("mirror.qits.example.org"), spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"));
  }

  @Test
  void aThirdPartyImageIsPulledAsNamedWithNoLogin() {
    for (String image :
        new String[] {"alpine:3", "docker.io/library/alpine:3", "ghcr.io/o/i@" + DIGEST}) {
      WorkloadSpec spec = composeEdge(image, false);

      assertEquals(image, spec.image());
      assertNull(spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"), image);
    }
  }

  @Test
  void anEdgeBuildStepKeepsItsWholeDocumentWhichCoversItsImage() {
    WorkloadSpec spec =
        composeEdge("registry.dev.localhost:8080/qits/build-images/node-docker-base@" + DIGEST, true);

    assertEquals("registry.qits.example.org/qits/build-images/node-docker-base@" + DIGEST, spec.image());
    assertEquals(
        document("registry.qits.example.org", "mirror.qits.example.org"),
        spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"));
  }

  @Test
  void theInternalPlaneLeavesEveryImageExactlyAsTheRunPinnedIt() {
    StepContainerSettings launcher = shipped();
    StepAddressPlane internal = launcher.internalPlane();
    String pinned = "registry.dev.localhost:8080/qits/build-images/node-docker-base@" + DIGEST;

    assertEquals(pinned, internal.imageReference(pinned));
    assertNull(internal.imagePullHost(pinned));
    WorkloadSpec spec =
        StepWorkloadSpecs.compose(
            launcher.workloadSettings(), internal, step(pinned, false), null, null);
    assertEquals(pinned, spec.image());
    assertNull(spec.env().get("QITS_CI_REGISTRY_AUTH_CONFIG"));
  }
}
