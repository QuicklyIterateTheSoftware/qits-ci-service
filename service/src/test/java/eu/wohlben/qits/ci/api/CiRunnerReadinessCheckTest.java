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
 * The answer is a pure function of the census and the connected runners, so the whole truth table
 * is assertable here: every row UP, the {@code warning} present exactly when nothing can execute a
 * run — including the rows a running suite cannot stage, since killing this
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

  private static boolean warns(HealthCheckResponse response) {
    return response.getData().orElseThrow().containsKey(CiRunnerReadinessCheck.WARNING);
  }

  /** UP on this row, and the warning present exactly when {@code nothingCanExecute}. */
  private static void row(HealthCheckResponse response, boolean nothingCanExecute, String what) {
    assertEquals(HealthCheckResponse.Status.UP, response.getStatus(), what + ": never DOWN");
    assertEquals(nothingCanExecute, warns(response), what + ": the warning");
  }

  @Test
  public void theTruthTableOfWorkersAndRunners() {
    // live workers × connected runners, not stopping: EVERY row is UP — a runner connects through
    // the routing that health gates, so DOWN-until-connected could never come up — and the warning
    // is on exactly the rows where both are zero.
    row(verdict(0, 4, false, 0, 0), true, "dead loops, no runner");
    row(verdict(0, 4, false, 1, 2), false, "dead loops, a runner");
    row(verdict(4, 4, false, 0, 0), false, "live loops, no runner");
    row(verdict(4, 4, false, 1, 2), false, "live loops, a runner");
    // The zero-pool qits-ci (qits-503, qits-443): no loop by design.
    row(verdict(0, 0, false, 1, 1), false, "zero pool, a runner");
    row(verdict(0, 0, false, 0, 0), true, "zero pool, no runner — the state a fresh task boots in");
    // A quarantined runner is connected with no usable slot: still something to hand a run to.
    row(verdict(0, 0, false, 1, 0), false, "zero pool, a quarantined runner");
    row(verdict(0, 0, false, 2, null), false, "zero pool, runners whose rows could not be read");
    // Shutting down: zero of everything is what a shutdown is, and nothing to warn about.
    row(verdict(0, 4, true, 0, 0), false, "stopping");
    row(verdict(0, 0, true, 0, 0), false, "stopping, zero pool");
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
  public void nothingToExecuteARunAndNoShutdownUnderWayIsStillUpAndSaysSo() {
    // The measured state (2026-09-07): accepting runs, writing QUEUED rows, nobody to take them. It
    // was DOWN; it is UP with the sentence as data, because the deployment of 2026.930.103022 showed
    // DOWN here deadlocks a zero-pool qits-ci against the runner that would have lifted it.
    HealthCheckResponse response = verdict(0, 4, false, 0, 0);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals("0", data(response, "liveWorkers"));
    assertEquals("4", data(response, "configuredWorkers"));
    assertEquals("0", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
    String warning = data(response, CiRunnerReadinessCheck.WARNING);
    assertTrue(warning.startsWith("nothing can execute a run"), warning);
    assertTrue(warning.contains("QUEUED until a runner connects"), "naming the consequence");
  }

  @Test
  public void nothingLeftBECAUSEitIsShuttingDownIsUpWithNoWarning() {
    // Zero live loops during a shutdown is what a shutdown is, runners or not.
    row(verdict(0, 4, true, 0, 0), false, "stopping");
    row(verdict(0, 0, true, 0, 0), false, "stopping, zero pool");
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
    assertFalse(warns(response), "a live claim loop can execute a run");
  }
}
