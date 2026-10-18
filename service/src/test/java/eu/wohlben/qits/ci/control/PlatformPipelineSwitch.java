package eu.wohlben.qits.ci.control;

/**
 * Arms or disarms {@link CiEventTriggerService}'s platform pipelines from a test outside this
 * package — {@code contracts/ProviderStates}' maintenance-bump state (qits-1149). The suites ship
 * them off ({@code qits.ci.platform-pipelines.enabled=false}); a state arms them for its one
 * recording and its clean-up disarms them again.
 */
public final class PlatformPipelineSwitch {

  private PlatformPipelineSwitch() {}

  public static void set(CiEventTriggerService triggers, boolean enabled) {
    triggers.platformPipelines(enabled);
  }
}
