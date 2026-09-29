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
 * The verdict is a pure function of the connected runners, the {@code
 * qits.ci.runners.readiness-requires-connected} hatch and the shutdown, so the whole truth table is
 * assertable here — including the rows a running suite cannot stage.
 *
 * <p>The last case is the wiring: a real instance, its real (empty) runner registry and the shipped
 * {@code true} hatch — DOWN, with the counts on it, because this suite connects no runner.
 */
@QuarkusTest
public class CiRunnerReadinessCheckTest {

  @Inject @Readiness CiRunnerReadinessCheck check;

  private static HealthCheckResponse verdict(
      int connectedRunners, Integer totalSlots, boolean requiresConnected, boolean stopping) {
    return CiRunnerReadinessCheck.responseFor(
        connectedRunners, totalSlots, requiresConnected, stopping);
  }

  private static String data(HealthCheckResponse response, String key) {
    return String.valueOf(response.getData().orElseThrow().get(key));
  }

  @Test
  public void theTruthTableOfRunnersTheHatchAndTheShutdown() {
    // connected × requires-connected × stopping: DOWN only where nothing is connected, the hatch is
    // on (shipped) and the process is not on its way out.
    assertEquals(HealthCheckResponse.Status.DOWN, verdict(0, 0, true, false).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(0, 0, true, true).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(0, 0, false, false).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(0, 0, false, true).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(1, 2, true, false).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(1, 2, true, true).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(1, 2, false, false).getStatus());
    assertEquals(HealthCheckResponse.Status.UP, verdict(1, 2, false, true).getStatus());
  }

  @Test
  public void aConnectedRunnerKeepsItUpEvenWithNoUsableSlot() {
    // The localhost runner registers quarantined awaiting its first health check: connected, zero
    // effective slots. The check that lifts the quarantine is a run this process must accept, so a
    // readiness demanding slots would hold the deployment down over exactly that runner.
    HealthCheckResponse response = verdict(1, 0, true, false);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals("1", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
  }

  @Test
  public void nothingToExecuteARunAndNoShutdownUnderWayIsDownAndSaysWhy() {
    HealthCheckResponse response = verdict(0, 0, true, false);

    assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    assertEquals("0", data(response, "connectedRunners"));
    assertFalse(response.getData().orElseThrow().containsKey("liveWorkers"), "no claim loops left");
    assertTrue(data(response, "message").contains("QUEUED"), "naming the consequence");
  }

  @Test
  public void theHatchOffIsUpAndSaysSo() {
    HealthCheckResponse response = verdict(0, 0, false, false);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertTrue(
        data(response, "message").contains("qits.ci.runners.readiness-requires-connected"),
        "an UP with nothing connected names the switch that made it UP");
  }

  @Test
  public void nothingLeftBECAUSEitIsShuttingDownIsUp() {
    HealthCheckResponse response = verdict(0, 0, true, true);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertFalse(response.getData().orElseThrow().containsKey("message"));
  }

  @Test
  public void anUnreadableSlotCountIsLeftOutAndNeverJudged() {
    HealthCheckResponse response = verdict(2, null, true, false);

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    Map<String, Object> data = response.getData().orElseThrow();
    assertFalse(data.containsKey("totalSlots"));
    assertEquals("2", String.valueOf(data.get("connectedRunners")));
  }

  @Test
  public void theRunningInstanceWithNoRunnerConnectedIsDownOnTheShippedHatch() {
    HealthCheckResponse response = check.call();

    assertEquals("ci-runners", response.getName());
    assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    assertEquals("0", data(response, "connectedRunners"));
    assertEquals("0", data(response, "totalSlots"));
    assertFalse(response.getData().orElseThrow().containsKey("liveWorkers"));
    assertFalse(response.getData().orElseThrow().containsKey("configuredWorkers"));
  }
}
