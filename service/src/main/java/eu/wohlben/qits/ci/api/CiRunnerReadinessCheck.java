package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.runnerhost.CiRunnerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * <b>Always UP: a readout of what could execute a run, never a gate</b> — the connected runners
 * and their slots, plus a {@code warning} when nothing can execute one (no connected runner, and
 * not shutting down). Every run is a runner's (qits-506): this process has no claim loop of its
 * own, so the runners are the whole of the answer.
 *
 * <p><b>Why it never goes DOWN.</b> Swarm gates routing on container health: a task that is not
 * healthy is never put behind the service's VIP and alias. A runner connects THROUGH that routing —
 * edge, alias, this process. So a check that is DOWN until a runner connects can never come up:
 * the runner it waits for cannot reach the task that is waiting. Observed 2026-09-30: the
 * deployment of 2026.930.103022 (the first with no claim loop of its own — the in-process executor
 * was switched off, and has since been deleted) was killed {@code unhealthy} and rolled back, and because the update is stop-first the runner had been cut off from the old task
 * as well. Readiness cannot depend on an inbound connection. And the remedy DOWN buys is the wrong
 * one anyway: restarting or rolling back qits-ci because a remote runner is offline fixes nothing
 * about the runner.
 *
 * <p><b>What it replaced, and what is kept of it.</b> {@code ci-run-workers} counted this
 * process's own claim loops and was DOWN at zero; qits-503 made this check count runners too, DOWN
 * only with neither; qits-506 deleted the loops, so {@code liveWorkers} and {@code
 * configuredWorkers} are gone from the data with them. The state all of that was written for is
 * still real — after a redeploy (2026-09-07) runs sat {@code QUEUED} while every check stayed
 * green, because no surface stated that nothing was left to execute them — and it is still stated,
 * here, as data: the counts on every answer and the {@code warning} sentence exactly where the old
 * DOWN stood. What is given up is only qits-cd's {@code awaitHealthy} restoring the previous
 * container on that state, which no readiness check here reaches any more ({@link
 * CiDaemonReadinessCheck} is a readout too).
 *
 * <p><b>A quarantined runner still counts as connected</b>: the bootstrap's {@code localhost}
 * registers quarantined awaiting its first health check, which is a run this process hands to it.
 * {@code totalSlots} says how much of the connection is usable — a quarantined runner counts none.
 *
 * <p>Nothing here needs the database except {@code totalSlots}, best effort: a read that fails
 * costs that data point and nothing else. The connected runners are the socket registry's, in
 * memory.
 *
 * <p>The answer itself is {@link #responseFor}, a pure function, so every arm is assertable without
 * a process whose runners really have gone.
 */
@Readiness
@ApplicationScoped
public class CiRunnerReadinessCheck implements HealthCheck {

  private static final Logger LOG = Logger.getLogger(CiRunnerReadinessCheck.class);

  static final String NAME = "ci-runners";

  /** The data key present exactly when nothing can execute a run. */
  static final String WARNING = "warning";

  static final String NOTHING_CAN_EXECUTE =
      "nothing can execute a run: no runner is connected and this process is not shutting down;"
          + " an accepted run sits QUEUED until a runner connects";

  @Inject CiRunService runs;

  @Inject CiRunnerRegistry registry;

  @Inject CiRunners runners;

  @Override
  public HealthCheckResponse call() {
    Set<UUID> connected = registry.connectedRunnerIds();
    return responseFor(connected.size(), totalSlots(connected), runs.stopping());
  }

  /**
   * The effective slots of the connected runners — a quarantined one counts none — or null when the
   * rows could not be read.
   */
  private Integer totalSlots(Set<UUID> connected) {
    if (connected.isEmpty()) {
      return 0;
    }
    try {
      int total = 0;
      for (CiRunner row : runners.list()) {
        if (connected.contains(row.id)) {
          total += row.quarantined() ? 0 : Math.max(0, row.slots);
        }
      }
      return total;
    } catch (RuntimeException e) {
      LOG.debugf("The ci-runners readiness check could not read the runner rows: %s", e.getMessage());
      return null;
    }
  }

  /**
   * The readout, as a function of the connected runners and the shutdown and nothing else. UP on
   * every input — see the class javadoc for why.
   *
   * @param totalSlots the connected runners' effective slots, null when they could not be read
   * @param stopping whether this process is on its way out
   */
  static HealthCheckResponse responseFor(
      int connectedRunners, Integer totalSlots, boolean stopping) {
    HealthCheckResponseBuilder response =
        HealthCheckResponse.named(NAME).up().withData("connectedRunners", connectedRunners);
    if (totalSlots != null) {
      response.withData("totalSlots", totalSlots);
    }
    if (connectedRunners == 0 && !stopping) {
      response.withData(WARNING, NOTHING_CAN_EXECUTE);
    }
    return response.build();
  }
}
