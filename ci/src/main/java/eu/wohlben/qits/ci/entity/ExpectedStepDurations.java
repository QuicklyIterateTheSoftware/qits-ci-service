package eu.wohlben.qits.ci.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The two directions of {@link CiRun#expectedStepDurations} — a JSON array of millisecond longs, one
 * entry per planned pipeline step, written once at accept and read back with the row.
 *
 * <p><b>Reading never throws, and that is the whole contract.</b> The column is a prediction and a
 * prediction is a convenience: a value that is absent, empty, not JSON, not an array, or holds
 * anything that is not a positive whole number of milliseconds reads back as {@code null} — "this
 * service has nothing to say about how long this run will take" — which is exactly what a row
 * recorded before the feature existed reads as. Throwing here would let a stored value nobody can
 * fix cost a run its whole DTO, on every listing, forever.
 *
 * <p><b>Written by hand, read with Jackson.</b> Encoding a list of {@code long}s needs no library
 * and gains nothing from one; decoding is {@code readTree} plus a walk, which is the same shape
 * every other payload read in this repository takes and needs no native-image registration for the
 * reason {@code EventWireReflection}'s javadoc states — nothing here is bound to a record.
 */
public final class ExpectedStepDurations {

  /** Shared and read-only: {@code readTree} does not mutate a mapper and this one is never configured. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private ExpectedStepDurations() {}

  /**
   * The column text for a prediction, or null for one there is nothing to record about — a null or
   * empty list, or one holding a non-positive entry.
   *
   * <p>An empty list is null rather than {@code []} on purpose: a pipeline with no steps has no
   * progress to draw, and two spellings of "no prediction" would be two cases every reader has to
   * know about.
   */
  public static String encode(List<Long> millis) {
    if (millis == null || millis.isEmpty()) {
      return null;
    }
    StringBuilder text = new StringBuilder(millis.size() * 8 + 2).append('[');
    for (int i = 0; i < millis.size(); i++) {
      Long entry = millis.get(i);
      if (entry == null || entry <= 0) {
        return null;
      }
      if (i > 0) {
        text.append(',');
      }
      text.append(entry.longValue());
    }
    return text.append(']').toString();
  }

  /** The prediction a stored value holds, or null when it holds none — see the class javadoc. */
  public static List<Long> decode(String stored) {
    if (stored == null || stored.isBlank()) {
      return null;
    }
    JsonNode array;
    try {
      array = JSON.readTree(stored);
    } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException malformed) {
      return null;
    }
    if (array == null || !array.isArray() || array.isEmpty()) {
      return null;
    }
    List<Long> millis = new ArrayList<>(array.size());
    for (JsonNode entry : array) {
      if (!entry.isIntegralNumber() || entry.longValue() <= 0) {
        return null;
      }
      millis.add(entry.longValue());
    }
    return List.copyOf(millis);
  }
}
