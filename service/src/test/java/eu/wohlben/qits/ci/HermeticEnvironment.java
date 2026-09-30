package eu.wohlben.qits.ci;

import java.util.Map;

/**
 * A child process's environment emptied down to {@code PATH} and {@code HOME}, so the test that
 * starts it states every other variable the child reads.
 *
 * <p><b>Why an allow-list and not a remove-list.</b> This suite runs inside a qits-ci step
 * container, which carries whatever qits-ci composes for a step: {@code QITS_TOKEN}, {@code
 * QITS_MAVEN_AUTH_*}, {@code GIT_CONFIG_GLOBAL}, {@code QITS_PUBLISH_TOKEN_COMMAND} and the public
 * {@code QITS_*_URL}s today (see {@code StepWorkloadSpecs.compose}), a commissioned client pair and
 * a token endpoint before qits-515. A remove-list names only the variables that existed when it was
 * written: on qits-ci's first run through the edge {@code CiDaemonBootstrapFetchTest} removed the
 * pair and {@code GIT_CONFIG_GLOBAL} but inherited {@code QITS_TOKEN}, so the bootstrap took its
 * token branch
 * and wrote its git helper config to an empty {@code $GIT_CONFIG_GLOBAL} ({@code can't create :
 * nonexistent directory}). Starting from nothing makes a new ambient variable irrelevant rather
 * than a new way to fail.
 */
public final class HermeticEnvironment {

  /** What a shell needs to find its tools and a place to call home — and nothing about the host. */
  private static final String[] KEPT = {"PATH", "HOME"};

  private HermeticEnvironment() {}

  /**
   * Clears {@code builder}'s environment to {@link #KEPT} and returns it for the caller to fill.
   */
  public static Map<String, String> of(ProcessBuilder builder) {
    Map<String, String> env = builder.environment();
    String[] kept = new String[KEPT.length];
    for (int i = 0; i < KEPT.length; i++) {
      kept[i] = env.get(KEPT[i]);
    }
    env.clear();
    for (int i = 0; i < KEPT.length; i++) {
      if (kept[i] != null) {
        env.put(KEPT[i], kept[i]);
      }
    }
    return env;
  }
}
