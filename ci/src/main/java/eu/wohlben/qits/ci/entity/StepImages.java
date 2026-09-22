package eu.wohlben.qits.ci.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two directions of {@link CiRun#stepImages} — a JSON object mapping each distinct image
 * reference this run's steps named to the immutable reference it was pinned to, written once at
 * accept and read back with the row.
 *
 * <p><b>It is {@code archetype_rev}'s shape for the other half of a run's environment.</b> That
 * column records which wrapper commit produced the platform half of the pipeline; this one records
 * which bytes the tools that ran it really were. Between them a finished run says what it was
 * built from, without anybody having to ask a registry what {@code :latest} used to be.
 *
 * <p><b>A map rather than a list, because the key is what makes it readable.</b> One entry per
 * distinct reference, not per step: two steps naming one tag share an entry by construction, which
 * is the property the whole feature exists for — a build must never straddle two versions of one
 * tool. A per-step array would have made that a coincidence a reader has to check.
 *
 * <p><b>Reading never throws</b>, {@link ExpectedStepDurations}'s contract and for its reason: a
 * value that is absent, empty, not JSON, not an object or not a map of strings reads back as an
 * empty map — "nothing was pinned for this run" — which is exactly what every row written before
 * this column existed reads as. Such a run launches the reference its recipe named, which is what
 * every run did before the pin. Throwing here would let a stored value nobody can fix cost a run
 * its steps.
 *
 * <p><b>Written by hand, read with Jackson</b>, again {@link ExpectedStepDurations}'s split, and
 * the writing is where the hand-rolling earns its keep: an image reference is charset-bounded by
 * {@code CiIdentifiers.requireImage} but is not a word, so the encoder escapes rather than
 * assuming. Decoding is {@code readTree} plus a walk, which needs no native-image registration for
 * the reason {@code EventWireReflection}'s javadoc states.
 */
public final class StepImages {

  /** Shared and read-only: {@code readTree} does not mutate a mapper and this one is never configured. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private StepImages() {}

  /**
   * The column text for one run's pins, or null when there are none — which is an ordinary run
   * rather than a failure: a pipeline whose every step names an image this platform does not
   * publish pins nothing at all.
   *
   * <p>Null rather than {@code {}} for {@link ExpectedStepDurations#encode}'s reason: two spellings
   * of "nothing recorded" would be two cases every reader has to know about.
   */
  public static String encode(Map<String, String> pins) {
    if (pins == null || pins.isEmpty()) {
      return null;
    }
    StringBuilder text = new StringBuilder(pins.size() * 96).append('{');
    boolean first = true;
    for (Map.Entry<String, String> pin : pins.entrySet()) {
      if (pin.getKey() == null || pin.getValue() == null) {
        continue;
      }
      if (!first) {
        text.append(',');
      }
      first = false;
      quote(text, pin.getKey()).append(':');
      quote(text, pin.getValue());
    }
    return first ? null : text.append('}').toString();
  }

  /** The pins a stored value holds, never null and never throwing — see the class javadoc. */
  public static Map<String, String> decode(String stored) {
    if (stored == null || stored.isBlank()) {
      return Map.of();
    }
    JsonNode object;
    try {
      object = JSON.readTree(stored);
    } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException malformed) {
      return Map.of();
    }
    if (object == null || !object.isObject() || object.isEmpty()) {
      return Map.of();
    }
    Map<String, String> pins = new LinkedHashMap<>();
    for (Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
      String reference = names.next();
      JsonNode pinned = object.get(reference);
      if (pinned == null || !pinned.isTextual() || pinned.asText().isBlank()) {
        // One unusable entry is not a reason to throw away the others: each key stands alone, and a
        // reference with no usable pin simply launches as the recipe named it.
        continue;
      }
      pins.put(reference, pinned.asText());
    }
    return Map.copyOf(pins);
  }

  /** A JSON string literal. Minimal and sufficient: the two structural characters and the C0 range. */
  private static StringBuilder quote(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"');
  }
}
