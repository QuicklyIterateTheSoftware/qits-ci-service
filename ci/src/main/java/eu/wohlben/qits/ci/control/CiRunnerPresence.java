package eu.wohlben.qits.ci.control;

import java.util.UUID;

/**
 * Whether a runner holds a socket to this process right now — the one fact about a runner that is
 * not on its row, because it is true only of this process and only for as long as the socket lives.
 *
 * <p>A seam in {@code ci/} for the reason every port here is one: the socket is a web stack's, and
 * {@code ci/} has none. The implementation that knows is the runner socket's, in {@code service/};
 * until one exists, {@link NoRunnerPresence} answers for every runner, and its answer — nobody is
 * connected — is the true one while there is no socket to connect to.
 */
public interface CiRunnerPresence {

  boolean connected(UUID runnerId);
}
