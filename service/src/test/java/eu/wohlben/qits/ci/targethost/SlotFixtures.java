package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the plain-JUnit suites of this package compose a lease from: the shipped settings, a public
 * domain, one sample run and the run's token.
 */
public final class SlotFixtures {

  private SlotFixtures() {}

  public static final String RUN = "0123456789abcdef-run";

  /** The platform's public domain in these suites: a lease is told {@code <host>.qits.example.org}. */
  public static final String DOMAIN = "example.org";

  public static final String TOKEN = "qits_tok_run-123";

  public static final String SUBJECT = "tok-ci-run-" + RUN + "-1";

  public static final String DAEMON_ID = "5b1f0c1e-6a43-4f3e-9d1a-0c8b2f7e1a11";

  public static final String PINNED =
      "dev-qits-artifacts:8080/qits/build-images/ci-slot@sha256:" + "a".repeat(64);

  /** The run's {@code ci-run} token, as qits-idp answers one. */
  public static IdpCommissioner.CommissionedToken token() {
    return new IdpCommissioner.CommissionedToken("token-1", TOKEN, SUBJECT);
  }

  /** The shipped defaults — everything a spec is composed from but addresses. */
  public static CiSlotSettings shippedSettings() {
    CiSlotSettings settings = new CiSlotSettings();
    settings.memory = "4g";
    settings.memorySwap = "4g";
    settings.pidsLimit = 4096;
    settings.cpus = "2";
    settings.oomScoreAdj = 1000;
    settings.artifactsImageRepository = "qits";
    settings.artifactsCliPackage = "qits-platform-access-cli";
    settings.artifactsCliVersionOverride = Optional.empty();
    return settings;
  }

  /** The addresses of a qits-ci whose {@code QITS_DOMAIN} is {@link #DOMAIN}. */
  public static CiSlotAddresses.Plane plane() {
    return CiSlotAddresses.forDomain(DOMAIN).plane().orElseThrow();
  }

  /** A MaintenanceBump run, name-addressed — the widest env there is. */
  public static CiSlotSpecs.LeaseRun sampleRun() {
    return new CiSlotSpecs.LeaseRun(
        RUN,
        CiRepoRef.of("5ca0e7e9-repo", "qits", "qits-ci-service"),
        "main",
        "cafebabecafebabecafebabecafebabecafebabe",
        DAEMON_ID,
        "/artifacts/daemons/qits-ci-daemon/2026.927.1",
        PINNED,
        Map.of(
            "QITS_EVENT_ID", "0b5f3c1e-0000-4000-8000-000000000001",
            "QITS_EVENT_NAME", "MaintenanceBump",
            "QITS_EVENT_OCCURRED_AT", "2026-09-12T10:00:00Z",
            "QITS_EVENT_PAYLOAD", "{\"branch\":\"maintenance/dependencies\"}",
            "QITS_VERSION", ""));
  }

  /** The sample run's lease spec, composed the way {@link CiLeases} composes one. */
  public static SystemLeases.LeaseSpec compose() {
    return CiSlotSpecs.compose(shippedSettings().settings(), plane(), sampleRun(), token());
  }

  /** The env as {@code KEY=value} lines in its own iteration order, so order is asserted too. */
  public static List<String> entries(Map<String, String> env) {
    List<String> lines = new ArrayList<>();
    env.forEach((key, value) -> lines.add(key + "=" + value));
    return lines;
  }
}
