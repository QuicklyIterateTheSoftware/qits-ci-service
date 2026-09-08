package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiRunService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * DOWN exactly when this instance has no claim loop left and is not shutting down — the state a
 * qits-ci is in when the queue is dead.
 *
 * <p><b>This check exists because the alternative was measured.</b> After a redeploy, runs sat
 * {@code QUEUED} indefinitely while every health check this service declared stayed green and
 * {@code GET /ci/api/daemon} happily answered {@code source=adopted}: the daemon pin ladder is what
 * {@link CiDaemonReadinessCheck} knows about, and the thing that had actually died — every one of
 * {@code qits.ci.concurrent-builds} claim loops — was a fact no surface stated. Green-while-dead
 * cost the diagnosis hours and the fix a process restart. So the count is a surface now.
 *
 * <p><b>The conjunction is the whole check.</b> Zero live loops during a shutdown is what a
 * shutdown is, so {@code stopping} is UP; zero live loops while the process intends to keep serving
 * is the failure. {@code busyWorkers} is deliberately not consulted — an idle instance is
 * legitimately zero-busy for days, and a check that read it would report every quiet night as an
 * outage.
 *
 * <p><b>What DOWN buys is qits-cd's health gate</b>, exactly as {@link CiDaemonReadinessCheck}'s
 * does and with the same limits: a deployment that lands with no claim loop fails {@code
 * awaitHealthy} and the previous container is restored, while a container already running is not
 * restarted by {@code --restart unless-stopped} and the gateway does not remove a DOWN qits-ci from
 * routing — which stays correct, because the read surface has to answer in order to say what is
 * wrong. Beyond the gate it is what a person or a monitor reads: {@code /q/health/ready} names the
 * live count and the configured one side by side, so "four of four" and "zero of four" are one
 * glance apart.
 *
 * <p>The verdict itself is {@link #responseFor}, a pure function over the census, so both arms are
 * assertable without a process whose workers really have died.
 */
@Readiness
@ApplicationScoped
public class CiRunWorkerReadinessCheck implements HealthCheck {

  static final String NAME = "ci-run-workers";

  @Inject CiRunService runs;

  @Override
  public HealthCheckResponse call() {
    return responseFor(runs.workerCensus());
  }

  /** The verdict, as a function of the census and nothing else. */
  static HealthCheckResponse responseFor(CiRunService.WorkerCensus census) {
    HealthCheckResponseBuilder response =
        HealthCheckResponse.named(NAME)
            .withData("liveWorkers", census.live())
            .withData("configuredWorkers", census.configured());
    if (census.live() > 0 || census.stopping()) {
      return response.up().build();
    }
    return response
        .down()
        .withData(
            "message",
            "no CI run worker is claiming: every claim loop is gone and this process is not"
                + " shutting down, so an accepted run would sit QUEUED forever")
        .build();
  }
}
