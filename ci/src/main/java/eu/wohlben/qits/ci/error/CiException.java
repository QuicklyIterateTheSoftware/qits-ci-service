package eu.wohlben.qits.ci.error;

/**
 * Base for ci errors. Carries an HTTP-ish status code so the web layer can map it to a response
 * without ci depending on JAX-RS (the same framework-free stance as {@code domain.error} — but ci
 * must not depend on {@code domain}, so it owns its own). The {@code service} module maps these via
 * {@code CiExceptionMapper}.
 */
public class CiException extends RuntimeException {

  private final int statusCode;

  /**
   * A machine-readable name for the refusal, beside the prose — {@code EDGE_PLANE_UNCONFIGURED} —
   * or null for the refusals that never needed one. {@code CiExceptionMapper} answers it as {@code
   * code} when it is set.
   */
  private final String code;

  public CiException(int statusCode, String message) {
    this(statusCode, null, message);
  }

  public CiException(int statusCode, String code, String message) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
  }

  public CiException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    this.code = null;
  }

  public int statusCode() {
    return statusCode;
  }

  public String code() {
    return code;
  }
}
