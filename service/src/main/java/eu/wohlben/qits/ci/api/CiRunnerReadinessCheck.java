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
 * DOWN exactly when nothing could execute an accepted run and this instance is not shutting down:
 * no claim loop of its own is live AND no runner is connected (qits-503). It replaced {@code
 * ci-run-workers}, which counted the claim loops alone.
 *
 * <p><b>Why the runners count now.</b> {@code qits.ci.concurrent-builds=0} hands every run to the
 * runners, so a qits-ci configured that way has zero claim loops by design — and the old check read
 * exactly that as the outage it was written for, so a zero-thread qits-ci would have failed its own
 * deployment gate and been rolled back. What the old check stood for is unchanged: "would an
 * accepted run sit {@code QUEUED} forever". A connected runner answers that as well as a live loop
 * does, so either keeps this UP.
 *
 * <p><b>A quarantined runner still counts as connected</b>, deliberately. The bootstrap's {@code
 * localhost} runner registers quarantined awaiting its first health check, and that check is a run
 * this process has to accept and hand to it; a readiness that demanded slots would hold the
 * deployment DOWN over the one runner whose health check is what lifts the quarantine. {@code
 * totalSlots} says how much of that connection is usable, for a person reading it.
 *
 * <p><b>This check exists because the alternative was measured.</b> After a redeploy, runs sat
 * {@code QUEUED} indefinitely while every health check this service declared stayed green: the
 * thing that had died — every one of {@code qits.ci.concurrent-builds} claim loops — was a fact no
 * surface stated. Green-while-dead cost the diagnosis hours and the fix a process restart.
 *
 * <p><b>The disjunction is the whole check.</b> Zero live loops during a shutdown is what a shutdown
 * is, so {@code stopping} is UP; {@code busyWorkers} is deliberately not consulted — an idle instance
 * is legitimately zero-busy for days. The verdict needs no database: the connected runners are the
 * socket registry's, in memory. Only {@code totalSlots} reads the runner rows, best effort — a read
 * that fails costs the data point, never the verdict.
 *
 * <p><b>What DOWN buys is qits-cd's health gate, and this is the ONLY readiness check here that
 * reaches it.</b> A deployment that lands with nothing to execute a run fails {@code awaitHealthy}
 * and the previous container is restored; beyond the gate it is what a person or a monitor reads.
 *
 * <p>The verdict itself is {@link #responseFor}, a pure function, so every arm is assertable without
 * a process whose workers really have died.
 */
@Readiness
@ApplicationScoped
public class CiRunnerReadinessCheck implements HealthCheck {

  private static final Logger LOG = Logger.getLogger(CiRunnerReadinessCheck.class);

  static final String NAME = "ci-runners";

  @Inject CiRunService runs;

  @Inject CiRunnerRegistry registry;

  @Inject CiRunners runners;

  @Override
  public HealthCheckResponse call() {
    Set<UUID> connected = registry.connectedRunnerIds();
    return responseFor(runs.workerCensus(), connected.size(), totalSlots(connected));
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
   * The verdict, as a function of the census and the connected runners and nothing else.
   *
   * @param totalSlots the connected runners' effective slots, null when they could not be read —
   *     reported, never judged
   */
  static HealthCheckResponse responseFor(
      CiRunService.WorkerCensus census, int connectedRunners, Integer totalSlots) {
    HealthCheckResponseBuilder response =
        HealthCheckResponse.named(NAME)
            .withData("liveWorkers", census.live())
            .withData("configuredWorkers", census.configured())
            .withData("connectedRunners", connectedRunners);
    if (totalSlots != null) {
      response.withData("totalSlots", totalSlots);
    }
    if (census.live() > 0 || connectedRunners > 0 || census.stopping()) {
      return response.up().build();
    }
    return response
        .down()
        .withData(
            "message",
            "nothing can execute a run: no CI run worker is claiming and no runner is connected,"
                + " and this process is not shutting down, so an accepted run would sit QUEUED"
                + " forever")
        .build();
  }
}
