package eu.wohlben.qits.ci.entity;

/**
 * What a run is FOR — {@code V24__runner_quarantine.sql} carries the column's argument.
 *
 * <p><b>{@link #BUILD} is every run there has ever been</b>: a pipeline a trigger file declared, run
 * against a commit, whose verdict is a statement about that commit. Every insert path that predates
 * health checks writes it by the entity's own default, so none of them had to learn a new word.
 *
 * <p><b>{@link #HEALTHCHECK} was a pseudo-build whose verdict was about a CI runner</b>: one step
 * in the platform's base image, through the path a runner could break. It went with the runner (epic
 * qits-1481) — a target's health is qits-system's — and the value stays because historical rows
 * carry the string; nothing writes it any more. Such a row announces nothing, is listed in no
 * repository's runs and is no part of the queue's forecast.
 */
public enum CiRunPurpose {
  BUILD,
  HEALTHCHECK
}
