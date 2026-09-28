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

  /**
   * Which version the runner runs, which it is meant to, and whether it is changing from one to the
   * other right now — in-memory like {@link #connected}, and for the same reason. The default is the
   * answer with no socket behind it: nothing is known about any runner.
   */
  default Versions versions(UUID runnerId) {
    return Versions.UNKNOWN;
  }

  /**
   * @param running what the runner's current connection said it is, null when none has said
   * @param target the version this deployment pins, null where no socket implementation knows one
   * @param updating whether a connection told to upgrade is still open
   */
  record Versions(String running, String target, boolean updating) {

    public static final Versions UNKNOWN = new Versions(null, null, false);
  }
}
