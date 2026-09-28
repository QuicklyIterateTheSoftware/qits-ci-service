package eu.wohlben.qits.ci.entity;

/**
 * Where a {@link CiRunner} stands relative to this service, and therefore which address it dials.
 *
 * <p>{@link #INTERNAL} is a runner on qits-net: the steps it starts are told every service's wire
 * alias, a network to join and the host gateway, and carry the run's commissioned client — exactly
 * the step a local worker starts. {@link #EDGE} is a runner outside, and its steps reach every
 * service through the platform edge: each address the public name of the same service ({@code
 * daemonhost/StepAddressPlane}), no network, and one {@code ci-run} token instead of the client
 * (epic qits-441). A runner is created {@code EDGE} whenever this qits-ci knows its public domain.
 *
 * <p>Stored as the constant's name and checked by nothing in the schema, {@code CiRunStatus}'s
 * arrangement: a new plane is one constant and no migration.
 */
public enum CiRunnerPlane {
  INTERNAL,
  EDGE
}
