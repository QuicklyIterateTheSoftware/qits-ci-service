package eu.wohlben.qits.ci.daemonhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /ci/api/daemon} — what daemon binary a run started right now would download, and where
 * that answer came from.
 *
 * <p>Addressed on its <b>absolute</b> path, like every other route test here, so a
 * {@code quarkus.rest.path} regression fails rather than hides. Note how close the two spellings
 * sit: this resource is {@code /ci/api/daemon} and the control socket is {@code /ci/daemon}.
 *
 * <p><b>The four keys are a cross-repo contract and that is why the shape is asserted whole.</b>
 * qits-artifacts' daemon-binary GC reads this document fail-closed when it plans a sweep — the blobs
 * a live pin names are the ones it must keep — so a key that quietly stopped being sent stops
 * another repository's sweep rather than failing anything here. {@code previousDaemonVersion} is
 * asserted present-and-blank for exactly that reason: it has no rung to name any more, and an
 * always-blank key costs that consumer nothing while a removed one costs it a change.
 *
 * <p><b>It lives in {@code daemonhost} rather than beside the resource in {@code api}, and that
 * still buys one Quarkus start instead of two.</b> Proving both arms means asking with the override
 * unset and with it set, and a second config value normally means a second {@code @TestProfile},
 * which means a second application boot racing the test port. {@link CiDaemonPins#versionOverride}
 * is public for this, so the set case is staged through {@link ClientProxy#unwrap} the way
 * {@code CiDaemonGateIT} stages the container url, and restored after. A deliberate, local ugliness
 * and not a pattern to spread.
 *
 * <p>What this class used to prove and no longer can: that the endpoint answered {@code source:
 * "none"} for a deployment which had adopted and configured nothing. There is no such state — the
 * version is the pinned protocol dependency's, resolved at build time, and {@code CiDaemonBinary}
 * refuses to exist rather than resolve to blank.
 */
@QuarkusTest
public class CiDaemonPinTest {

  private static final String DAEMON = "/ci/api/daemon";

  /** A shape an override can legitimately hold, and deliberately not the pinned version. */
  private static final String A_DIGEST =
      "c04a603e95cf1f2f6a9a1f6f0f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c";

  @Inject CiDaemonPins pins;

  @AfterEach
  void restoreTheShippedDefault() {
    ClientProxy.unwrap(pins).versionOverride = Optional.empty();
  }

  @Test
  public void anOrdinaryDeploymentAnswersTheVersionOfTheDependencyItPins() {
    // The ordinary state of every deployment, and the whole of the change: the version is the pom's,
    // so it is gated by this repository's own release request rather than adopted off the bus.
    given()
        .when()
        .get(DAEMON)
        .then()
        .statusCode(200)
        .body("daemonName", is(CiDaemonPins.DAEMON_NAME))
        .body("daemonVersion", is(CiDaemonBinary.VERSION))
        .body("previousDaemonVersion", is(""))
        .body("source", is(CiDaemonPins.SOURCE_PINNED));
  }

  @Test
  public void theDaemonVersionIsNeverBlank() {
    // ci_run.daemon_version is history — what some run once launched — and the run listing clamps at
    // 100, so the rows cannot enumerate what this instance would launch. This answers the different
    // and much smaller question the artifacts GC asks before it plans a sweep, and a blank answer
    // would make that sweep's fail-closed arm fire on a healthy platform.
    given().when().get(DAEMON).then().statusCode(200).body("daemonVersion", is(not("")));
  }

  @Test
  public void anOverrideIsAnsweredAndSaysSoInItsSource() {
    ClientProxy.unwrap(pins).versionOverride = Optional.of(A_DIGEST);

    given()
        .when()
        .get(DAEMON)
        .then()
        .statusCode(200)
        .body("daemonName", is(CiDaemonPins.DAEMON_NAME))
        .body("daemonVersion", is(A_DIGEST))
        .body("previousDaemonVersion", is(""))
        .body("source", is(CiDaemonPins.SOURCE_OVERRIDE));
  }
}
