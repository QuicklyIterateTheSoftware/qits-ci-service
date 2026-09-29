package eu.wohlben.qits.ci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiRunService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.Test;

/**
 * The verdict is a pure function of the census and the connected runners, so the whole truth table
 * is assertable here — including the rows a running suite cannot stage, since killing this
 * instance's claim loops to prove the check would leave every later test with no worker.
 *
 * <p>The last case is the wiring: a real instance, its real census and its real (empty) runner
 * registry, and UP with the counts on it.
 */
@QuarkusTest
public class CiRunnerReadinessCheckTest {

  @Inject @Readiness CiRunnerReadinessCheck check;

  private static HealthCheckResponse verdict(
      int liveWorkers, int configured, boolean stopping, int connectedRunners, Integer totalSlots) {
    return CiRunnerReadinessCheck.responseFor(
        new CiRunService.WorkerCensus(liveWorkers, configured, stopping),
        connectedRunners,
        totalSlots);
  }

  private static String data(HealthCheckResponse response, String key) {
    return String.valueOf(response.getData().orElseThrow().get(key));
  }

  @Test
  public void theTruthTableOfWorkersAndRunners() {
    // live workers × connected runners, not stopping: DOWN only where both are zero.
    assertEquals(HealthCheckResponse.Status.DOWN, verdict(0, 4, false, 0, 0).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(0, 4, false, 1, 2).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(4, 4, false, 0, 0).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(4, 4, false, 1, 2).getStatus());
    // The zero-thread qits-ci (qits-503): no loop by design, and a runner is what keeps it ready.
    assertEquals(HealthCheckResponse.Status.UP, verdict(0, 0, false, 1, 1).getStatus());
    assertEquals(HealthCheckResponse.Status.DOWN, verdict(0, 0, false, 0, 0).getStatus());
  }

  @Test
  public void aConnectedRunnerKeepsItUpEvenWithNoUsableSlot() {
    // The bootstrap's localhost registers quarantined awaiting its first health check: connected,
    // zero effective slots. The check that lifts the quarantine is a run this process must accept,
    // so a readiness demanding slots would hold the deployment down over exactly that runner.
    HealthCheckResponse response = verdict(0, 0, false, 1, 0);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals("1", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
  }

  @Test
  public void nothingToExecuteARunAndNoShutdownUnderWayIsDownAndSaysWhy() {
    // The measured state: qits-ci accepting runs, writing QUEUED rows, releasing permits nobody
    // consumes — and now also no runner to Reserve them.
    HealthCheckResponse response = verdict(0, 4, false, 0, 0);

    assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    assertEquals("0", data(response, "liveWorkers"));
    assertEquals("0", data(response, "connectedRunners"));
    assertTrue(data(response, "message").contains("QUEUED"), "naming the consequence");
  }

  @Test
  public void nothingLeftBECAUSEitIsShuttingDownIsUp() {
    // Zero live loops during a shutdown is what a shutdown is, runners or not.
    assertEquals(HealthCheckResponse.Status.UP, verdict(0, 4, true, 0, 0).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(0, 0, true, 0, 0).getStatus());
  }

  @Test
  public void anUnreadableSlotCountIsLeftOutAndNeverJudged() {
    HealthCheckResponse response = verdict(0, 0, false, 2, null);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    Map<String, Object> data = response.getData().orElseThrow();
    assertFalse(data.containsKey("totalSlots"));
    assertEquals("2", String.valueOf(data.get("connectedRunners")));
  }

  @Test
  public void theRunningInstanceReportsItsRealClaimLoopsAndRunners() {
    HealthCheckResponse response = check.call();

    assertEquals("ci-runners", response.getName());
    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals(
        data(response, "configuredWorkers"),
        data(response, "liveWorkers"),
        "every configured claim loop is live on an instance that is serving");
    assertEquals("0", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
  }
}
