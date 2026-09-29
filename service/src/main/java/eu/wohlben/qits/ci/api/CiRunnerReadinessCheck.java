package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.runnerhost.CiRunnerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * DOWN exactly when nothing could execute an accepted run and this instance is not shutting down:
 * no runner is connected (qits-503, narrowed in qits-506). It replaced {@code ci-run-workers}, which
 * counted this process's own claim loops — loops that no longer exist: every run is a runner's, the
 * platform host's included (the reserved {@code localhost} runner qits-deployments runs beside this
 * service).
 *
 * <p><b>What it stands for is unchanged: "would an accepted run sit {@code QUEUED} forever".</b> With
 * no runner connected, the answer is yes, so the instance is DOWN and qits-cd's health gate refuses a
 * deployment that lands that way.
 *
 * <p><b>A quarantined runner still counts as connected</b>, deliberately. The {@code localhost}
 * runner registers quarantined awaiting its first health check, and that check is a run this
 * process has to accept and hand to it; a readiness that demanded slots would hold the deployment
 * DOWN over the one runner whose health check is what lifts the quarantine. {@code totalSlots} says
 * how much of that connection is usable, for a person reading it.
 *
 * <p><b>{@code qits.ci.runners.readiness-requires-connected=false} is the escape hatch</b>, shipped
 * {@code true}. It exists for the one moment the rule is circular: a qits-ci whose runners cannot
 * connect until it is live — a fresh estate, a runner that dials an address this deployment is
 * about to own — would otherwise fail its own gate and be rolled back forever. Off, the check is UP
 * with nothing connected and says so in its data; it is a switch for a person, never a default.
 *
 * <p><b>This check exists because the alternative was measured.</b> After a redeploy, runs sat
 * {@code QUEUED} indefinitely while every health check this service declared stayed green: the
 * thing that had died — every claim loop the in-process executor had — was a fact no surface stated.
 * Green-while-dead cost the diagnosis hours and the fix a process restart.
 *
 * <p><b>The disjunction is the whole check.</b> Nothing connected during a shutdown is what a
 * shutdown is, so {@code stopping} is UP. The verdict needs no database: the connected runners are
 * the socket registry's, in memory. Only {@code totalSlots} reads the runner rows, best effort — a
 * read that fails costs the data point, never the verdict.
 *
 * <p><b>What DOWN buys is qits-cd's health gate, and this is the ONLY readiness check here that
 * reaches it.</b> A deployment that lands with nothing to execute a run fails {@code awaitHealthy}
 * and the previous container is restored; beyond the gate it is what a person or a monitor reads.
 *
 * <p>The verdict itself is {@link #responseFor}, a pure function, so every arm is assertable without
 * a process whose runners really have gone.
 */
@Readiness
@ApplicationScoped
public class CiRunnerReadinessCheck implements HealthCheck {

  private static final Logger LOG = Logger.getLogger(CiRunnerReadinessCheck.class);

  static final String NAME = "ci-runners";

  @Inject CiRunService runs;

  @Inject CiRunnerRegistry registry;

  @Inject CiRunners runners;

  /** See the class javadoc: the hatch for an estate whose runners cannot connect first. */
  @ConfigProperty(name = "qits.ci.runners.readiness-requires-connected")
  boolean requiresConnected;

  @Override
  public HealthCheckResponse call() {
    Set<UUID> connected = registry.connectedRunnerIds();
    return responseFor(
        connected.size(), totalSlots(connected), requiresConnected, runs.stopping());
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
   * The verdict, as a function of the connected runners, the hatch and the shutdown, and nothing
   * else.
   *
   * @param totalSlots the connected runners' effective slots, null when they could not be read —
   *     reported, never judged
   * @param requiresConnected {@code qits.ci.runners.readiness-requires-connected}
   * @param stopping whether this process is on its way out
   */
  static HealthCheckResponse responseFor(
      int connectedRunners, Integer totalSlots, boolean requiresConnected, boolean stopping) {
    HealthCheckResponseBuilder response =
        HealthCheckResponse.named(NAME).withData("connectedRunners", connectedRunners);
    if (totalSlots != null) {
      response.withData("totalSlots", totalSlots);
    }
    if (connectedRunners > 0 || stopping) {
      return response.up().build();
    }
    if (!requiresConnected) {
      return response
          .up()
          .withData(
              "message",
              "no runner is connected, and qits.ci.runners.readiness-requires-connected is false,"
                  + " so this is reported UP: an accepted run waits QUEUED until one connects")
          .build();
    }
    return response
        .down()
        .withData(
            "message",
            "nothing can execute a run: no runner is connected and this process is not shutting"
                + " down, so an accepted run would sit QUEUED until one connects")
        .build();
  }
}
