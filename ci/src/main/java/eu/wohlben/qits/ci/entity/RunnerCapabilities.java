package eu.wohlben.qits.ci.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The two directions of {@link CiRunner#capabilities}: what a runner said about itself when it
 * registered, as a JSON object, written once and read back with the row.
 *
 * <p><b>The runner's word, never interpreted here.</b> Architecture, docker, labels — whatever the
 * runner reports is stored as the object it sent, so a runner that learns to say something new says
 * it without a change on this side. The one rule applied on the way in is shape and size: it must be
 * an object, and it must fit in {@link #MAX_CHARS}, because it is caller-supplied text that lands in
 * a row and on every listing.
 *
 * <p><b>Reading never throws</b>, {@link StepImages}' contract and for its reason: a stored value
 * that is not a JSON object reads back as null — "said nothing" — rather than costing a listing its
 * answer. {@code readTree} plus a walk needs no native-image registration, for the reason {@code
 * EventWireReflection}'s javadoc states.
 */
public final class RunnerCapabilities {

  /** 16 KiB of JSON is far more than a runner has to say about itself, and a bound all the same. */
  public static final int MAX_CHARS = 16 * 1024;

  /** Shared and read-only: {@code readTree} and {@code writeValueAsString} mutate no mapper. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private RunnerCapabilities() {}

  /**
   * The column text for what a runner registered with, or null for nothing.
   *
   * @throws IllegalArgumentException when the value is not a JSON object or is too large to keep
   */
  public static String encode(JsonNode capabilities) {
    if (capabilities == null || capabilities.isNull() || capabilities.isMissingNode()) {
      return null;
    }
    if (!capabilities.isObject()) {
      throw new IllegalArgumentException("capabilities must be a JSON object");
    }
    String text;
    try {
      text = JSON.writeValueAsString(capabilities);
    } catch (Exception unwritable) {
      throw new IllegalArgumentException("capabilities could not be written as JSON");
    }
    if (text.length() > MAX_CHARS) {
      throw new IllegalArgumentException(
          "capabilities are " + text.length() + " characters of JSON, above the " + MAX_CHARS
              + " kept");
    }
    return text;
  }

  /** The stored object, or null when there is none or it cannot be read back as one. */
  public static JsonNode decode(String stored) {
    if (stored == null || stored.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(stored);
      return node != null && node.isObject() ? node : null;
    } catch (Exception unreadable) {
      return null;
    }
  }
}
