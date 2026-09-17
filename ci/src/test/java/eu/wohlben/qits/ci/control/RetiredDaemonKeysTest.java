package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The warning line a boot prints about a retired configuration key, asserted as a <em>message</em>
 * rather than as "some logger was called" — the latter is a claim about a framework, and what
 * matters here is that the three facts an operator needs are in the sentence.
 *
 * <p>Plain JUnit and no {@code @QuarkusTest} on purpose. {@link RetiredDaemonKeys#warningFor} is a
 * pure function of an {@code Optional}, and the bean around it exists to be creatable <em>anywhere</em>
 * — every property {@code Optional}, nothing required — so a Quarkus start here would prove the one
 * thing that is already true by construction while costing a boot.
 */
public class RetiredDaemonKeysTest {

  @Test
  public void aSetKeyNamesItselfItsStaleValueAndTheVersionActuallyUsed() {
    String line =
        RetiredDaemonKeys.warningFor(RetiredDaemonKeys.VERSION_KEY, Optional.of("deadbeef"))
            .orElseThrow();

    assertTrue(line.contains(RetiredDaemonKeys.VERSION_KEY), line);
    assertTrue(line.contains("deadbeef"), line);
    assertTrue(line.contains(CiDaemonBinary.VERSION), line);
    // Without this the reader is told what stopped working and not what to do instead.
    assertTrue(line.contains(CiDaemonPins.OVERRIDE_KEY), line);
  }

  @Test
  public void anAbsentKeySaysNothing() {
    assertTrue(
        RetiredDaemonKeys.warningFor(RetiredDaemonKeys.AUTOADOPT_KEY, Optional.empty()).isEmpty());
  }

  @Test
  public void aBlankValueCountsAsUnset() {
    // SmallRye maps an environment variable onto the property, so a deployment template rendering
    // `KEY=` produces a present, empty value. Warning about that is warning about a template with
    // nothing in it, which is a line per boot that teaches nobody anything.
    assertTrue(
        RetiredDaemonKeys.warningFor(RetiredDaemonKeys.PROBE_IMAGE_KEY, Optional.of("")).isEmpty());
    assertTrue(
        RetiredDaemonKeys.warningFor(RetiredDaemonKeys.PROBE_IMAGE_KEY, Optional.of("  "))
            .isEmpty());
  }

  @Test
  public void theOverrideKeyIsNotOneOfTheRetiredOnes() {
    // The rename is the mechanism: the retired name is what the standing platform holds and the
    // override is a name no automation has ever written. A test that let the two collide would let
    // the residue start deciding again with nothing to say so.
    assertFalse(CiDaemonPins.OVERRIDE_KEY.equals(RetiredDaemonKeys.VERSION_KEY));
    assertTrue(CiDaemonPins.OVERRIDE_KEY.startsWith(RetiredDaemonKeys.VERSION_KEY));
  }
}
