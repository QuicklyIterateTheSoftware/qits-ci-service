package eu.wohlben.qits.ci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiRunService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.Test;

/**
 * The verdict is a pure function of the census, so both arms are assertable here — including the
 * one a running suite cannot stage, since killing this instance's claim loops to prove the check
 * would leave every later test with no worker.
 *
 * <p>The last case is the wiring: a real instance, its real census, and UP with the count on it.
 */
@QuarkusTest
public class CiRunWorkerReadinessCheckTest {

  @Inject @Readiness CiRunWorkerReadinessCheck check;

  @Test
  public void anInstanceWithNoClaimLoopLeftAndNoShutdownUnderWayIsDown() {
    // The measured state: qits-ci accepting runs, writing QUEUED rows, releasing permits nobody
    // consumes, and answering every other health check green.
    HealthCheckResponse response =
        CiRunWorkerReadinessCheck.responseFor(new CiRunService.WorkerCensus(0, 4, false));

    assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    assertEquals(
        "0",
        String.valueOf(response.getData().orElseThrow().get("liveWorkers")),
        "and it says how many, so the answer is actionable rather than merely alarming");
    assertTrue(
        response.getData().orElseThrow().get("message").toString().contains("QUEUED"),
        "naming the consequence, not just the count");
  }

  @Test
  public void anInstanceWithNoClaimLoopLeftBECAUSEitIsShuttingDownIsUp() {
    // Zero live loops during a shutdown is what a shutdown is. Reporting it DOWN would make every
    // ordinary stop-first redeploy look like the outage this check exists to name.
    HealthCheckResponse response =
        CiRunWorkerReadinessCheck.responseFor(new CiRunService.WorkerCensus(0, 4, true));

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
  }

  @Test
  public void anIdleInstanceWithItsClaimLoopsIntactIsUp() {
    // Busy-ness is deliberately not consulted: an instance with nothing to build is legitimately
    // idle for days, and a check that read it would report every quiet night as an outage.
    HealthCheckResponse response =
        CiRunWorkerReadinessCheck.responseFor(new CiRunService.WorkerCensus(4, 4, false));

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals("4", String.valueOf(response.getData().orElseThrow().get("liveWorkers")));
  }

  @Test
  public void theRunningInstanceReportsItsRealClaimLoops() {
    HealthCheckResponse response = check.call();

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals(
        String.valueOf(response.getData().orElseThrow().get("configuredWorkers")),
        String.valueOf(response.getData().orElseThrow().get("liveWorkers")),
        "every configured claim loop is live on an instance that is serving");
  }
}
