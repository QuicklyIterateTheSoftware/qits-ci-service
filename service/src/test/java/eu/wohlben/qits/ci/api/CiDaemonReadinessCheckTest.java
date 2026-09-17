package eu.wohlben.qits.ci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.Test;

/**
 * The daemon readout in {@code /q/health/ready}: UP unconditionally, carrying which binary this
 * instance launches and where that came from.
 *
 * <p><b>What this class used to assert, and why none of it survived.</b> It seeded a still-undecided
 * adopted candidate straight into {@code ci_daemon_pin} and asserted the check had not launched a
 * probe container — a real requirement, because the container healthcheck hits this class every few
 * seconds and a probing read on that cadence is what turned one docker container-naming race into a
 * near-certain collision. There is no probe, no candidate and no table to seed, so the requirement
 * is met by there being nothing that could violate it.
 *
 * <p><b>Asserting UP looks like asserting nothing and is not.</b> The value is that it is UP
 * <em>whatever the config says</em>: this entry can no longer hold a deployment out of rotation, and
 * qits-cd's {@code awaitHealthy} restoring the previous container over a daemon version is exactly
 * the behaviour that was deliberately given up — the real gate is {@link
 * CiRunWorkerReadinessCheck}. A DOWN arm reappearing here would be a silent re-introduction of that,
 * and this is the test that fails when it does.
 */
@QuarkusTest
public class CiDaemonReadinessCheckTest {

  @Inject @Readiness CiDaemonReadinessCheck check;

  @Test
  public void itIsUpAndNamesTheDaemonItWouldLaunch() {
    HealthCheckResponse response = check.call();

    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals(CiDaemonReadinessCheck.NAME, response.getName());
    assertEquals(
        CiDaemonPins.DAEMON_NAME, response.getData().orElseThrow().get("daemonName"));
    assertEquals(CiDaemonBinary.VERSION, response.getData().orElseThrow().get("daemonVersion"));
    assertEquals(CiDaemonPins.SOURCE_PINNED, response.getData().orElseThrow().get("source"));
  }

  @Test
  public void theNameIsTheRenamedOneBecauseTheOldOneWasLadderVocabulary() {
    // `ci-daemon-pin` described a rung that could be absent, and the entry's whole job was to say
    // whether one had been found. Naming a pure readout after the thing it no longer decides is how
    // a reader of /q/health/ready concludes a gate is in place. Nothing on the platform binds the
    // literal — it is read by people — so the rename costs a glance.
    assertEquals("ci-daemon-version", CiDaemonReadinessCheck.NAME);
  }
}
