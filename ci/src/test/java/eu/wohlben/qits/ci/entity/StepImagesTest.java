package eu.wohlben.qits.ci.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link StepImages} on its own — plain JUnit, since the column is two pure functions.
 *
 * <p>What is under test is the contract rather than the happy path: <b>reading never throws</b>, so
 * every way a stored value can be unusable answers an empty map and the run launches the reference
 * its recipe named, exactly as every run did before the column existed. A throw here would let a
 * value nobody can fix cost a run its steps, which is {@link ExpectedStepDurations}'s rule and the
 * reason both classes are written this way.
 */
public class StepImagesTest {

  private static final String REFERENCE = "registry:8080/qits/build-images/ci-base:latest";
  private static final String PINNED = "registry:8080/qits/build-images/ci-base@sha256:" + "a".repeat(64);

  @Test
  public void aPinRoundTrips() {
    Map<String, String> pins = new LinkedHashMap<>();
    pins.put(REFERENCE, PINNED);
    pins.put("alpine:3", "alpine@sha256:" + "b".repeat(64));

    String stored = StepImages.encode(pins);
    assertEquals(pins, StepImages.decode(stored));
  }

  @Test
  public void nothingPinnedIsNullRatherThanAnEmptyObject() {
    // Two spellings of "nothing recorded" would be two cases every reader has to know about.
    assertNull(StepImages.encode(null));
    assertNull(StepImages.encode(Map.of()));
  }

  @Test
  public void everyUnusableStoredValueReadsAsNoPins() {
    assertEquals(Map.of(), StepImages.decode(null));
    assertEquals(Map.of(), StepImages.decode("  "));
    assertEquals(Map.of(), StepImages.decode("{"), "not JSON");
    assertEquals(Map.of(), StepImages.decode("[\"a\"]"), "not an object");
    assertEquals(Map.of(), StepImages.decode("{}"));
    assertEquals(Map.of(), StepImages.decode("{\"a\":3}"), "a pin that is not a reference");
    assertEquals(Map.of(), StepImages.decode("{\"a\":\"\"}"), "a blank pin is no pin");
  }

  @Test
  public void oneUnusableEntryDoesNotCostTheOthers() {
    // Each key stands alone: a reference with no usable pin launches as the recipe named it, and
    // the steps whose pins are readable still boot the bytes this run chose.
    assertEquals(
        Map.of(REFERENCE, PINNED),
        StepImages.decode("{\"" + REFERENCE + "\":\"" + PINNED + "\",\"broken\":null}"));
  }

  @Test
  public void aReferenceIsEscapedRatherThanAssumedToBeAWord() {
    // An image reference is charset-bounded by CiIdentifiers.requireImage, not word-shaped, and a
    // hand-written encoder that assumed otherwise would emit a document its own decoder rejects.
    Map<String, String> pins = Map.of("weird\"name\\:1", "weird\"name\\@sha256:" + "c".repeat(64));
    assertEquals(pins, StepImages.decode(StepImages.encode(pins)));
  }
}
