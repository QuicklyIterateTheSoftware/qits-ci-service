package eu.wohlben.qits.ci.idp;

import eu.wohlben.qits.auth.MachineIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.Optional;

/**
 * The {@code sub} a caller arrived as — the one read that binds a request to a run's {@code ci-run}
 * token ({@link RunCommissions#tokenSubjectOf}).
 *
 * <p>Two doors bind that way and both read it here, so they cannot disagree about who a caller is:
 * the ci-daemon control socket's admission ({@code CiDaemonSocket}) and the release-report submit
 * door ({@code CiReportController}).
 */
public final class CallerSubject {

  private CallerSubject() {}

  /**
   * The validated token's {@code sub} claim when there is one, and the principal's name otherwise —
   * the edge names the token's subject either way. Empty when the caller carries none, which matches
   * no run.
   */
  public static String of(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      return "";
    }
    return MachineIdentity.claim(identity, "sub")
        .or(
            () ->
                Optional.ofNullable(
                    identity.getPrincipal() == null ? null : identity.getPrincipal().getName()))
        .orElse("");
  }
}
