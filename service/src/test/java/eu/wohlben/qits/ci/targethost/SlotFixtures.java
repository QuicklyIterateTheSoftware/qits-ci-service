package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.trust.TrustAnchors;
import jakarta.enterprise.inject.Vetoed;
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
      "registry.qits.example.org/qits/build-images/ci-slot@sha256:" + "a".repeat(64);

  /** The run's {@code ci-run} token, as qits-idp answers one. */
  public static IdpCommissioner.CommissionedToken token() {
    return new IdpCommissioner.CommissionedToken("token-1", TOKEN, SUBJECT);
  }

  /** The shipped defaults — everything a spec is composed from but addresses. */
  public static CiSlotSettings shippedSettings() {
    CiSlotSettings settings = new CiSlotSettings();
    settings.memory = "6g";
    settings.memorySwap = "6g";
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

  /**
   * A {@link TrustAnchors} standing in for the real poller (epic qits-2524, "anchor delivery"):
   * {@code envEntry()}/{@code pem()} answer whatever {@link #held} is, read fresh on every call —
   * default {@code null}, the "no bundle yet" case. It dials nothing and starts no scheduler: the
   * injected fields the real class carries (its http client, its bearer source) are never touched
   * because every method that would reach them is overridden here.
   *
   * <p><b>{@code @Vetoed}</b>, deliberately: Quarkus' test bean archive turns every concrete class
   * on the test classpath into a {@code @Default} bean candidate with no annotation needed at all,
   * so an unvetoed subclass of {@code TrustAnchors} made every {@code @QuarkusTest} in this module
   * (whether or not it is about anchors) fail to boot with {@code AmbiguousResolutionException}
   * between the real poller and this one. {@code @Mock} looked like the fix — it is how {@code
   * FakeGitHostRepoListing} and {@code FakeCiStepRunner} replace a port's real implementation — but
   * it does not apply here: {@code TrustAnchors} is a concrete class with its own
   * package-private {@code @Observes StartupEvent} method, and declaring this subclass (in a
   * different package) a SEPARATE bean made Arc generate that observer's notifier against the
   * wrong classloader (`IllegalAccessError` at boot). {@code @Vetoed} removes it from CDI discovery
   * entirely — this fake is never {@code @Inject}ed, only {@code new}ed by the plain-JUnit suites in
   * this package — which answers both problems at once.
   */
  @Vetoed
  public static class FakeTrustAnchors extends TrustAnchors {

    /** The PEM bundle this fake currently holds, or {@code null} for none. Mutable on purpose: a
     * no-roll test changes it mid-test to prove a live lease never notices. */
    public volatile String held;

    @Override
    public Optional<String> pem() {
      return Optional.ofNullable(held);
    }

    @Override
    public Optional<Map.Entry<String, String>> envEntry() {
      return held == null ? Optional.empty() : Optional.of(Map.entry(TrustAnchors.ENV, held));
    }
  }

  /** A {@link FakeTrustAnchors} holding no bundle — the shipped "library disabled or not yet
   * polled" case every suite not about anchors should wire in. */
  public static FakeTrustAnchors noTrustAnchors() {
    return new FakeTrustAnchors();
  }

  /** The env as {@code KEY=value} lines in its own iteration order, so order is asserted too. */
  public static List<String> entries(Map<String, String> env) {
    List<String> lines = new ArrayList<>();
    env.forEach((key, value) -> lines.add(key + "=" + value));
    return lines;
  }
}
