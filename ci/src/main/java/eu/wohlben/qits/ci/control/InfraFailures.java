package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import java.util.List;
import java.util.Set;

/**
 * <b>Which step failures are about the platform rather than the build</b> — what {@code
 * CiRunService.autoRetry} re-fires a run over. It was half of {@code CiRunnerHealth}, whose other
 * half (a runner's quarantine and its health-check pseudo-builds) went with the CI runner (epic
 * qits-1481): a target's health is qits-system's now. The classification itself did not move.
 */
public final class InfraFailures {

  private InfraFailures() {}

  /** The outcomes no build caused: its lease failed to start it, or its daemon went away. */
  public static final Set<StepOutcome> INFRA_OUTCOMES =
      Set.of(StepOutcome.LAUNCH_FAILED, StepOutcome.NEVER_STARTED, StepOutcome.CONNECTION_LOST);

  /**
   * <b>What a step that ran can still say about the host rather than the build</b> (qits-556): the
   * words buildkit and containerd use when the builder's user namespace cannot map an id a layer
   * owns. Such a step exits 1 on that target and builds green on any other, so it is judged like an
   * {@link #INFRA_OUTCOMES infra outcome} and retried automatically. Deliberately narrow: a mistake
   * costs at most {@code CiRunService.AUTO_RETRY_MAX} retries of a real red, never a lost verdict.
   */
  public static final List<String> INFRA_SIGNATURES =
      List.of(
          "mount callback failed",
          "failed to Lchown",
          "subordinate IDs in /etc/subuid",
          "potentially insufficient UIDs or GIDs");

  /** How many of a step output's last lines {@link #infraSignature} reads. */
  static final int SIGNATURE_TAIL_LINES = 40;

  /**
   * Whether a step failed through the infrastructure: one of {@link #INFRA_OUTCOMES}, or it ran,
   * exited non-zero without a deadline, and its output's tail carries one of {@link
   * #INFRA_SIGNATURES}.
   */
  public static boolean isInfra(StepResult result) {
    if (result == null || result.outcome() == null) {
      return false;
    }
    return INFRA_OUTCOMES.contains(result.outcome()) || infraSignature(result) != null;
  }

  /** The signature a step that ran and failed ends with, or null. */
  public static String infraSignature(StepResult result) {
    if (result == null
        || result.outcome() != StepOutcome.OK
        || result.timedOut()
        || result.exitCode() == 0
        || result.output() == null) {
      return null;
    }
    String output = result.output();
    int from = output.length();
    for (int lines = 0; lines <= SIGNATURE_TAIL_LINES && from > 0; lines++) {
      from = output.lastIndexOf('\n', from - 1);
      if (from < 0) {
        from = 0;
      }
    }
    String tail = output.substring(from);
    for (String signature : INFRA_SIGNATURES) {
      if (tail.contains(signature)) {
        return signature;
      }
    }
    return null;
  }

  /** An infra failure in one word for a log line: its outcome's name, or its signature. */
  public static String infraWord(StepResult result) {
    String signature = infraSignature(result);
    return signature == null ? result.outcome().name() : "\"" + signature + "\"";
  }
}
