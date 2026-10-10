package eu.wohlben.qits.ci.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Freezes one state's answer</b> so a re-recording against fresh ids is byte-identical — the
 * golden-master format's freezing rules, and nothing else:
 *
 * <ul>
 *   <li><b>Ids.</b> Every distinct UUID, wherever it sits in a string value, becomes {@code
 *       00000000-0000-4000-8000-00000000000N} (N in hex, the last group 12 digits), numbered by first
 *       appearance. The mapping is seeded with the state's params first ({@link #seed}), which is
 *       how a state's {@code projectId} is {@code …0001}.
 *   <li><b>Instants.</b> Every string value that is an ISO-8601 instant becomes {@value
 *       #FROZEN_INSTANT} — by shape, not by a list of field names. An instant inside a longer
 *       string (the JSON text of an audit snapshot) is frozen in place, and that string's path goes
 *       in {@link #stringPaths}.
 *   <li><b>Unique tokens.</b> A random token a state had to put into a name to keep it unique (a
 *       project slug is unique service-wide) becomes the same-length hex counter {@code 0…0N},
 *       numbered by first appearance.
 *   <li><b>Keys.</b> A member name holding a unique token — a map keyed by qualified id, as {@code
 *       POST /work/transition} answers — is frozen through the same token mapping, so it reads as
 *       the frozen param does. The object's path goes in {@link #keyPaths} (the index's {@code
 *       frozen.keys}, written only where there is one), and the paths beneath such a member name it
 *       {@code .*}, since no JSONPath can name "whatever this key is frozen to". A UUID in a key is
 *       still refused outright.
 * </ul>
 *
 * <p>Every value it changes is recorded as a JSONPath, by what the WHOLE value was: a UUID goes in
 * {@link #idPaths}, an instant in {@link #instantPaths}, and a string that merely contains a frozen
 * id or token (a message, a url, a slug) in {@link #stringPaths}. Paths are {@code $.a.b}, array
 * elements as {@code [*]}, in order of first appearance and de-duplicated — the index's {@code
 * frozen.ids}, {@code frozen.instants} and {@code frozen.strings}.
 */
public final class Freezer {

  static final Pattern UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final Map<String, String> ids = new HashMap<>();
  private final Map<String, String> tokens = new LinkedHashMap<>();
  private int tokenCount;
  private final Set<String> idPaths = new LinkedHashSet<>();
  private final Set<String> instantPaths = new LinkedHashSet<>();
  private final Set<String> stringPaths = new LinkedHashSet<>();
  private final Set<String> keyPaths = new LinkedHashSet<>();

  /** Numbers each UUID in {@code values}, in order, ahead of anything the answer holds. */
  public Freezer seed(Collection<String> values) {
    for (String value : values) {
      freezeIds(value);
    }
    return this;
  }

  /**
   * Registers the random tokens a state put into names. Numbered when first met in the answer, not
   * here: a token the answer never shows takes no number.
   */
  public Freezer uniqueTokens(Collection<String> values) {
    for (String token : values) {
      if (token == null || token.isEmpty()) {
        throw new IllegalArgumentException("an empty unique token would match everywhere");
      }
      tokens.putIfAbsent(token, null);
    }
    return this;
  }

  /**
   * The frozen form of a param value: its UUIDs and unique tokens through the same mappings as the
   * answer — a qualified id param ({@code contract-<token>-3}) carries the project slug's token.
   */
  public String freezeParam(String value) {
    return freezeTokens(freezeIds(value));
  }

  public JsonNode freeze(JsonNode node) {
    return freeze(node, "$");
  }

  public List<String> idPaths() {
    return new ArrayList<>(idPaths);
  }

  public List<String> instantPaths() {
    return new ArrayList<>(instantPaths);
  }

  public List<String> stringPaths() {
    return new ArrayList<>(stringPaths);
  }

  /** The objects whose member names were frozen, in order of first appearance. */
  public List<String> keyPaths() {
    return new ArrayList<>(keyPaths);
  }

  /** {@code 00000000-0000-4000-8000-} plus {@code n} as 12 hex digits. */
  public static String frozenId(int n) {
    return String.format("00000000-0000-4000-8000-%012x", n);
  }

  private JsonNode freeze(JsonNode node, String path) {
    if (node == null || node.isNull()) {
      return node;
    }
    if (node.isTextual()) {
      return TextNode.valueOf(freezeText(node.asText(), path));
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode element : node) {
        out.add(freeze(element, path + "[*]"));
      }
      return out;
    }
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String key = field.getKey();
        if (UUID.matcher(key).find()) {
          // A JSONPath cannot name "whatever this key is frozen to"; fail loudly rather than
          // record a file no consumer could match.
          throw new IllegalStateException("A UUID in a key cannot be frozen: " + path + " / " + key);
        }
        String frozenKey = freezeTokens(key);
        if (frozenKey.equals(key)) {
          out.set(key, freeze(field.getValue(), path + segment(key)));
        } else {
          keyPaths.add(path);
          out.set(frozenKey, freeze(field.getValue(), path + ".*"));
        }
      }
      return out;
    }
    return node;
  }

  private String freezeText(String value, String path) {
    if (INSTANT.matcher(value).matches()) {
      instantPaths.add(path);
      return FROZEN_INSTANT;
    }
    // An instant inside a longer string — an audit snapshot is JSON held as text — is frozen in
    // place, and the string is recorded as one that merely contains frozen values.
    String result =
        INSTANT.matcher(freezeTokens(freezeIds(value))).replaceAll(FROZEN_INSTANT);
    // An id is recorded even when it was already in its frozen form: qits-ci's states seed fixed,
    // frozen-shaped ids (a pact's query names them literally), and a consumer still needs the path.
    if (result.equals(value) && !UUID.matcher(value).find()) {
      return result;
    }
    // A consumer puts a uuid matcher on every frozen.ids path, so only a value that IS a UUID goes
    // there; a string that merely contains a frozen id or token is type-matched, in frozen.strings.
    if (UUID.matcher(value).matches()) {
      idPaths.add(path);
    } else {
      stringPaths.add(path);
    }
    return result;
  }

  private String freezeIds(String value) {
    if (value == null) {
      return null;
    }
    Matcher m = UUID.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String original = m.group().toLowerCase(java.util.Locale.ROOT);
      String frozen = ids.computeIfAbsent(original, k -> frozenId(ids.size() + 1));
      m.appendReplacement(out, Matcher.quoteReplacement(frozen));
    }
    m.appendTail(out);
    return out.toString();
  }

  private String freezeTokens(String value) {
    // One left-to-right pass over the original, so two tokens in one string are numbered in reading
    // order and a replacement is never scanned again.
    StringBuilder out = new StringBuilder();
    int from = 0;
    while (true) {
      String earliest = null;
      int at = Integer.MAX_VALUE;
      for (String token : tokens.keySet()) {
        int i = value.indexOf(token, from);
        if (i >= 0 && i < at) {
          at = i;
          earliest = token;
        }
      }
      if (earliest == null) {
        return out.append(value, from, value.length()).toString();
      }
      String frozen = tokens.get(earliest);
      if (frozen == null) {
        frozen = String.format("%0" + earliest.length() + "x", ++tokenCount);
        tokens.put(earliest, frozen);
      }
      out.append(value, from, at).append(frozen);
      from = at + earliest.length();
    }
  }

  private static String segment(String key) {
    if (PLAIN_KEY.matcher(key).matches()) {
      return "." + key;
    }
    return "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
  }
}
