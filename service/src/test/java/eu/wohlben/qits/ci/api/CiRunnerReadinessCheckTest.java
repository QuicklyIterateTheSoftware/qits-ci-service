package eu.wohlben.qits.ci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.Test;

/**
 * The answer is a pure function of the connected runners and the shutdown, so the whole truth table
 * is assertable here: every row UP, the {@code warning} present exactly when nothing can execute a
 * run — including the rows a running suite cannot stage.
 *
 * <p>The last case is the wiring: a real instance and its real (empty) runner registry — UP, with
 * the counts and the warning on it, because this suite connects no runner over the socket.
 */
@QuarkusTest
public class CiRunnerReadinessCheckTest {

  @Inject @Readiness CiRunnerReadinessCheck check;

  private static HealthCheckResponse verdict(
      int connectedRunners, Integer totalSlots, boolean stopping) {
    return CiRunnerReadinessCheck.responseFor(connectedRunners, totalSlots, stopping);
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
  public void theTruthTableOfRunnersAndTheShutdown() {
    // connected runners × stopping: EVERY row is UP — a runner connects through the routing that
    // health gates, so DOWN-until-connected could never come up — and the warning is on exactly the
    // row where nothing is connected and the process is not on its way out.
    row(verdict(0, 0, false), true, "no runner — the state a fresh task boots in");
    row(verdict(1, 2, false), false, "a runner");
    // A quarantined runner is connected with no usable slot: still something to hand a run to.
    row(verdict(1, 0, false), false, "a quarantined runner");
    row(verdict(2, null, false), false, "runners whose rows could not be read");
    // Shutting down: nothing connected is what a shutdown is, and nothing to warn about.
    row(verdict(0, 0, true), false, "stopping");
    row(verdict(1, 2, true), false, "stopping, a runner still connected");
  }

  @Test
  public void aConnectedRunnerKeepsItUpEvenWithNoUsableSlot() {
    // The bootstrap's localhost registers quarantined awaiting its first health check: connected,
    // zero effective slots. The check that lifts the quarantine is a run this process must accept,
    // so a readiness demanding slots would hold the deployment down over exactly that runner.
    HealthCheckResponse response = verdict(1, 0, false);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals("1", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
  }

  @Test
  public void nothingToExecuteARunAndNoShutdownUnderWayIsStillUpAndSaysSo() {
    // The measured state (2026-09-07): accepting runs, writing QUEUED rows, nobody to take them. It
    // was DOWN; it is UP with the sentence as data, because the deployment of 2026.930.103022 showed
    // DOWN here deadlocks a qits-ci against the runner that would have lifted it.
    HealthCheckResponse response = verdict(0, 0, false);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals("0", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
    Map<String, Object> data = response.getData().orElseThrow();
    assertFalse(data.containsKey("liveWorkers"), "no claim loops left to count (qits-506)");
    assertFalse(data.containsKey("configuredWorkers"));
    String warning = data(response, CiRunnerReadinessCheck.WARNING);
    assertTrue(warning.startsWith("nothing can execute a run"), warning);
    assertTrue(warning.contains("QUEUED until a runner connects"), "naming the consequence");
  }

  @Test
  public void nothingLeftBECAUSEitIsShuttingDownIsUpWithNoWarning() {
    // Nothing connected during a shutdown is what a shutdown is.
    row(verdict(0, 0, true), false, "stopping");
  }

  @Test
  public void anUnreadableSlotCountIsLeftOutAndNeverJudged() {
    HealthCheckResponse response = verdict(2, null, false);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    Map<String, Object> data = response.getData().orElseThrow();
    assertFalse(data.containsKey("totalSlots"));
    assertEquals("2", String.valueOf(data.get("connectedRunners")));
  }

  @Test
  public void theRunningInstanceIsUpWithNoRunnerConnectedAndSaysSo() {
    // THE ROW THE ROLLBACK OF 2026-09-30 WAS ABOUT, on the real bean: no runner has connected over
    // the socket in this suite, and readiness answers UP regardless.
    HealthCheckResponse response = check.call();

    assertEquals("ci-runners", response.getName());
    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals("0", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
    assertTrue(warns(response), "nothing connected and not stopping: the warning is on the answer");
    assertFalse(response.getData().orElseThrow().containsKey("liveWorkers"));
    assertFalse(response.getData().orElseThrow().containsKey("configuredWorkers"));
  }
}
