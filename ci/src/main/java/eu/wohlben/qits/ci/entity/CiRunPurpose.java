package eu.wohlben.qits.ci.entity;

/**
 * What a run is FOR — {@code V24__runner_quarantine.sql} carries the column's argument.
 *
 * <p><b>{@link #BUILD} is every run there has ever been</b>: a pipeline a trigger file declared, run
 * against a commit, whose verdict is a statement about that commit. Every insert path that predates
 * health checks writes it by the entity's own default, so none of them had to learn a new word.
 *
 * <p><b>{@link #HEALTHCHECK} is a pseudo-build whose verdict is about a RUNNER</b> ({@code
 * CiRun#targetRunnerId}): one step, {@code echo hello world}, in the platform's own base image,
 * through exactly the path a runner can break — the image pull, the daemon download, the dial back
 * through the edge, the clone through githost with the run's credential. Because it says nothing
 * about the commit it checks out, it announces nothing a build announces, gates nothing, is listed in
 * no repository's runs and is no part of the queue's forecast; see {@code CiRunnerHealth}.
 */
public enum CiRunPurpose {
  BUILD,
  HEALTHCHECK
}
