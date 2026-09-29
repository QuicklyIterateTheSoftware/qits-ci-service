package eu.wohlben.qits.ci;

import java.net.http.HttpRequest;

/**
 * The {@code Authorization} header a download from qits-artifacts needs on the EDGE plane and must
 * not send on the internal one.
 *
 * <p>An internal step reaches the registry through the internal alias, which needs no auth; an EDGE
 * step reaches it through the public edge, which refuses an anonymous read and accepts the run's own
 * job token — {@code QITS_TOKEN}, an opaque {@code qits_tok_…} bearer the step container carries —
 * as {@code Authorization: Bearer <token>}. A pin IT's own downloads of a real, pinned binary run on
 * whichever plane gates this repository, so they carry the same header a composed step's {@code
 * curl}/{@code wget} would (see {@code daemonhost.CiDaemonBootstrapEdgeTokenTest} for the composed
 * side of the same rule).
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
