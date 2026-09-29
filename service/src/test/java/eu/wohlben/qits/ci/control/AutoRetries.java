package eu.wohlben.qits.ci.control;

/**
 * The service module's reach to {@link CiRunService#autoRetryMax(int)}, which is package-private in
 * {@code ci/control} on purpose: the cap is {@link CiRunService#AUTO_RETRY_MAX} in production and
 * only a suite staging an infra failure for some other assertion ever turns it off. A class in the
 * same package, the arrangement {@code CiRestartReconciliationTest} uses for {@code
 * sweepInterrupted}.
 */
public final class AutoRetries {

  private AutoRetries() {}

  /** No automatic retries until {@link #restore}. */
  public static void off(CiRunService service) {
    service.autoRetryMax(0);
  }

  /** The shipped cap back. */
  public static void restore(CiRunService service) {
    service.autoRetryMax(CiRunService.AUTO_RETRY_MAX);
  }
}
