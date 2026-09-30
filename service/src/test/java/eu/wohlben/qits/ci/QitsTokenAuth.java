package eu.wohlben.qits.ci;

import java.net.http.HttpRequest;

/**
 * The {@code Authorization} header a download from qits-artifacts needs inside a CI step.
 *
 * <p>A step reaches the registry through the public edge, which refuses an anonymous read and
 * accepts the run's own token — {@code QITS_TOKEN}, an opaque {@code qits_tok_…} bearer the step
 * container carries — as {@code Authorization: Bearer <token>}. A pin IT's own downloads of a real,
 * pinned binary run inside the step that gates this repository, so they carry the same header a
 * composed step's {@code curl}/{@code wget} would (see {@code runnerhost.CiDaemonBootstrapTokenTest}
 * for the composed side of the same rule). Where the suite runs outside a step — a workspace — the
 * variable is unset and the store is reached at an address that asks for none.
 */
public final class QitsTokenAuth {

  private QitsTokenAuth() {}

  /** Adds the header to {@code request} when {@code QITS_TOKEN} is set, and does nothing otherwise. */
  public static void addIfPresent(HttpRequest.Builder request) {
    String token = System.getenv("QITS_TOKEN");
    if (token != null && !token.isBlank()) {
      request.header("Authorization", "Bearer " + token);
    }
  }
}
