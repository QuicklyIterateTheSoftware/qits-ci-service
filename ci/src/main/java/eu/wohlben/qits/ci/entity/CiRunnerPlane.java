package eu.wohlben.qits.ci.entity;

/**
 * Where a {@link CiRunner} stands relative to this service, and therefore which address it dials.
 *
 * <p>{@link #INTERNAL} is a runner on qits-net: it reaches qits-ci and qits-idp by their wire
 * aliases, which is what every runner is today. {@link #EDGE} is a runner outside, dialling through
 * the platform edge — the next epic's, declared now so that feature is a value on a row rather than
 * a migration. Nothing writes it yet.
 *
 * <p>Stored as the constant's name and checked by nothing in the schema, {@code CiRunStatus}'s
 * arrangement: a new plane is one constant and no migration.
 */
public enum CiRunnerPlane {
  INTERNAL,
  EDGE
}
