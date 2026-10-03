package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.runnerhost.StepContainerSettings.LaunchSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the plain-JUnit suites of this package compose a step from: the shipped settings with {@code
 * QITS_ENVIRONMENT=dev} resolved, a public domain, one sample step and the run's token.
 */
public final class StepFixtures {

  private StepFixtures() {}

  public static final String RUN = "0123456789abcdef-run";

  /** The platform's public domain in these suites: a step is told {@code <host>.qits.example.org}. */
  public static final String DOMAIN = "example.org";

  public static final String TOKEN = "qits_tok_run-123";

  public static final String SUBJECT = "tok-ci-run-" + RUN + "-1";

  /** The run's {@code ci-run} token, as qits-idp answers one. */
  public static IdpCommissioner.CommissionedToken token() {
    return new IdpCommissioner.CommissionedToken("token-1", TOKEN, SUBJECT);
  }

  /** The shipped defaults, {@code dev} resolved — everything a spec is composed from but addresses. */
  public static StepContainerSettings shippedLauncher() {
    StepContainerSettings launcher = new StepContainerSettings();
    launcher.memoryLimit = "4g";
    launcher.pidsLimit = 2048;
    launcher.cpus = "2";
    launcher.oomScoreAdj = 1000;
    launcher.artifactsRegistryHost = "dev-qits-artifacts:8080";
    launcher.artifactsImageRepository = "qits";
    launcher.registrySpellings =
        Optional.of(List.of("registry.dev.localhost:8080", "localhost:8081"));
    launcher.mirrorSpellings = Optional.of(List.of("mirror.dev.localhost:8080", "localhost:8082"));
    launcher.environment = "dev";
    launcher.artifactsCliPackage = "qits";
    launcher.artifactsCliVersionOverride = Optional.empty();
    return launcher;
  }

  /** The public origins of a qits-ci whose {@code QITS_DOMAIN} is {@link #DOMAIN}. */
  public static StepAddressPlane.EdgeOrigins origins() {
    return RunnerAddressesFixture.withDomain(DOMAIN).edgeOrigins().orElseThrow();
  }

  /** The addresses a step of {@code launcher} is told. */
  public static StepAddressPlane plane(StepContainerSettings launcher) {
    return launcher.plane(origins());
  }

  public static StepAddressPlane plane() {
    return plane(shippedLauncher());
  }

  /** A step of a MaintenanceBump run, name-addressed — the widest env there is. */
  public static LaunchSpec sampleStep(int stepIndex, boolean docker, boolean build) {
    return sampleStep(stepIndex, docker, build, "registry.dev.localhost:8080/qits/java-builder:25");
  }

  public static LaunchSpec sampleStep(int stepIndex, boolean docker, boolean build, String image) {
    return new LaunchSpec(
        RUN,
        stepIndex,
        CiRepoRef.of("5ca0e7e9-repo", "qits", "qits-ci-service"),
        "main",
        "cafebabecafebabecafebabecafebabecafebabe",
        image,
        "daemon-7",
        "/artifacts/daemons/qits-ci-daemon/2026.927.1",
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

  /** The env as {@code KEY=value} lines in its own iteration order, so order is asserted too. */
  public static List<String> entries(Map<String, String> env) {
    List<String> lines = new ArrayList<>();
    env.forEach((key, value) -> lines.add(key + "=" + value));
    return lines;
  }
}
