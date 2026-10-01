package eu.wohlben.qits.ci.entity;

/**
 * Where a {@link CiRunner} stands relative to this service. There is one plane: {@link #EDGE}. A
 * runner is a machine outside the swarm, and the steps it starts reach every service through the
 * platform edge — each address the public name of that service ({@code
 * runnerhost/StepAddressPlane}), no docker network, and the run's {@code ci-run} token as the only
 * credential (epic qits-441).
 *
 * <p>{@code INTERNAL} — a runner on qits-net, whose steps were told every service's wire alias and
 * carried a commissioned client — was deleted in qits-515 (epic qits-444). The API refuses the word
 * with a 400, and {@code V28__runner_plane_normalized.sql} normalised every stored
 * {@code ci_runner.plane} row to {@code 'EDGE'} and moved the column's own default to match. A row
 * that still stores the retired word — a restored backup older than that migration — is read as
 * {@link #EDGE} by {@link CiRunnerPlaneConverter}.
 */
public enum CiRunnerPlane {
  EDGE
}
