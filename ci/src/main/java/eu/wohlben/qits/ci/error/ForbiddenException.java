package eu.wohlben.qits.ci.error;

/**
 * 403 — the caller is authenticated and this particular thing is not theirs. Raised by the domain
 * where the decision needs a row (a runner's registration subject), which is exactly what a role
 * check cannot see.
 */
public class ForbiddenException extends CiException {

  public ForbiddenException(String message) {
    super(403, message);
  }
}
