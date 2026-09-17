package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.ci.control.CiDaemonPins.Pin;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which daemon binary a run started right now downloads. Two rungs, no table and no probe: the
 * override when a person set one, the pinned dependency's version otherwise.
 *
 * <p><b>What this class used to be is worth knowing, because the deletions are most of the diff.</b>
 * It drove a ladder — a durable table of candidate versions adopted off {@code SoftwareRelease}
 * frames and probed by launching a throwaway container running whatever had just been published —
 * against a scripted probe seam. Thirteen cases about walking past a rejected rung, never
 * reprobing a {@code REJECTED} candidate, reprobing an {@code UNKNOWN} one, collapsing concurrent
 * probes of the same candidate into one, and refusing a version carrying a slash. Every one of them
 * was a true statement about machinery that no longer exists, and none of them was a statement about
 * which binary a run gets — which is the only question this class has left. They are deleted rather
 * than adapted: a test that has to be rewritten to keep passing was testing the implementation.
 *
 * <p><b>The override is staged through {@link ClientProxy#unwrap} rather than a
 * {@code @TestProfile}.</b> Two values means two application boots otherwise, racing the test port
 * for a single config string; the field is public on {@link CiDaemonPins} for exactly this, and its
 * javadoc says so. Restored in {@code @AfterEach}, because a leaked override would silently repoint
 * every later test in this JVM.
 */
@QuarkusTest
public class CiDaemonPinsTest {

  @Inject CiDaemonPins pins;

  @AfterEach
  void restoreTheShippedDefault() {
    ClientProxy.unwrap(pins).versionOverride = Optional.empty();
  }

  private void override(String value) {
    ClientProxy.unwrap(pins).versionOverride = Optional.of(value);
  }

  @Test
  public void withNoOverrideTheAnswerIsThePinnedDependencysOwnVersion() {
    assertEquals(new Pin(CiDaemonBinary.VERSION, "", CiDaemonPins.SOURCE_PINNED), pins.answer());
  }

  @Test
  public void theAnswerIsNeverBlank() {
    // The state this whole retirement removes: a deployment that had adopted and configured nothing
    // answered blank, which composes a download url that 404s inside a throwaway container nobody is
    // watching. CiDaemonBinary refuses to exist rather than resolve to "", so a build that could
    // produce a blank here could not have been built.
    assertFalse(pins.answer().version().isBlank());
  }

  @Test
  public void anOverrideOutranksThePin() {
    override("2026.803.91607");

    assertEquals(new Pin("2026.803.91607", "", CiDaemonPins.SOURCE_OVERRIDE), pins.answer());
    assertNotEquals(CiDaemonBinary.VERSION, pins.answer().version());
  }

  @Test
  public void theSourceTellsTheTwoApartRatherThanLeavingACallerToGuess() {
    // GET /ci/api/daemon reports this verbatim, and it is the only thing that says a deployment has
    // deviated from what its own release request gated. A caller cannot derive it: an override
    // naming the same version as the pin is still an override.
    assertEquals(CiDaemonPins.SOURCE_PINNED, pins.answer().source());
    override(CiDaemonBinary.VERSION);
    assertEquals(CiDaemonPins.SOURCE_OVERRIDE, pins.answer().source());
  }

  @Test
  public void aBlankOverrideIsUnsetRatherThanAnEmptyVersion() {
    // SmallRye maps an environment variable onto the property, so a deployment template rendering
    // `QITS_CI_DAEMON_VERSION_OVERRIDE=` produces a PRESENT, EMPTY value rather than an absent one.
    // Reading that as an override is how a rendered-but-unfilled template silently points every step
    // container at a url with no version in it.
    override("");
    assertEquals(new Pin(CiDaemonBinary.VERSION, "", CiDaemonPins.SOURCE_PINNED), pins.answer());
  }

  @Test
  public void anOverrideOfNothingButWhitespaceIsAlsoUnset() {
    override("   ");
    assertEquals(CiDaemonPins.SOURCE_PINNED, pins.answer().source());
  }

  @Test
  public void anOverrideIsTrimmedBecauseItBecomesAUrlPathSegment() {
    override("  2026.803.91607  ");
    assertEquals("2026.803.91607", pins.answer().version());
  }

  @Test
  public void previousVersionIsBlankOnBothArms() {
    // Permanently blank rather than unfilled: it named the rung below the current one, and a pinned
    // version has no rung below — the fallback for a bad pin is a commit that changes the pin. The
    // key stays on the wire because qits-artifacts' daemon-binary GC binds this document's shape
    // fail-closed, and dropping a key from a document another repository's sweep reads is a change
    // to that sweep.
    assertEquals("", pins.answer().previousVersion());
    override("2026.803.91607");
    assertEquals("", pins.answer().previousVersion());
  }

  @Test
  public void theDaemonNameComesFromTheProtocolJarRatherThanASecondLiteral() {
    // The release pipeline PUTs the bytes under …/artifacts/daemons/<name>/<version> and a step
    // container's bootstrap downloads from exactly that path, so a second spelling on this side is a
    // second thing a rename can leave behind — as a 404 in a container nobody is watching.
    assertEquals(CiDaemonBinary.DAEMON_NAME, CiDaemonPins.DAEMON_NAME);
  }
}
