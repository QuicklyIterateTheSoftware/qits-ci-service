package eu.wohlben.qits.ci.stories.support;

import eu.wohlben.qits.ci.idp.StubIdp;
import java.util.Map;

/**
 * Where the LAUNCHED qits-ci commissions a run's {@code ci-run} token from: a {@link StubIdp}
 * answering qits-idp's {@code /api/tokens}, started once per failsafe JVM before the application.
 *
 * <p><b>Why a launched process needs one.</b> A step's only credential is its run's token, and a
 * qits-ci that commissions nothing launches no step at all (qits-515) — so a story that wants a
 * {@code Launch} to read needs the process under test to be able to mint. The shipped posture has
 * the qits oidc client OFF; these overrides turn it on and point it here, which is the posture a
 * deployed platform takes. {@code MockIdp}, which the same profile starts, is a different surface —
 * it signs the JWTs this service VALIDATES — and answers no commissioning call.
 *
 * <p><b>One server, found again through a system property</b>, for {@code MockIdp.ensureStarted}'s
 * reason: a profile is instantiated in more than one classloader, a static field is not shared
 * across them, and the process has exactly one property table.
 *
 * <p>Every key handed over is a RUNTIME key of quarkus-oidc-client: the artifact is already built.
 */
public final class StoryRunTokens {

  private static final String URL_PROPERTY = "qits.test.packaged-it.stub-idp-url";

  private StoryRunTokens() {}

  /** Starts the stub if this JVM has none, and answers the config that points the process at it. */
  public static synchronized Map<String, String> configOverrides() {
    String url = System.getProperty(URL_PROPERTY);
    if (url == null) {
      // Never closed: it lives as long as the failsafe JVM, like the embedded postgres.
      url = new StubIdp().authServerUrl();
      System.setProperty(URL_PROPERTY, url);
    }
    return Map.of(
        "quarkus.oidc-client.qits.client-enabled", "true",
        "quarkus.oidc-client.qits.auth-server-url", url,
        "quarkus.oidc-client.qits.client-id", StubIdp.SERVICE_CLIENT_ID,
        "quarkus.oidc-client.qits.credentials.secret", StubIdp.SERVICE_SECRET);
  }
}
