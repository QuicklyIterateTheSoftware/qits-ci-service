package eu.wohlben.qits.ci.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * Both directions of {@link CiRunner#nodeHealth}: a runner's node health report as the row keeps it,
 * and the stored text read back (qits-896). {@link RunnerCapabilities}' kind of class, and
 * qits-workspaces' {@code WorkspaceRunnerCapabilities.withHealth} bound.
 *
 * <p><b>The shape</b> is {@code {ok, detail, requestId, dataOmitted, checks:[{name, ok, detail,
 * data}]}} — the runner's own report ({@code HealthChecked}, qits-runner-protocol's {@code
 * HealthReport}), with the request it answered and whether its data was dropped. When it settled is
 * the row's {@code node_health_at}, not a field here.
 *
 * <p><b>Bounded at {@link #MAX_CHARS}</b>: a check's {@code data} is the runner's word and can be
 * large (the node inventory lists every container of the runner's), and the report is written on a
 * row every listing reads. Above the bound every check's {@code data} is dropped and {@link
 * #DATA_OMITTED} is set — the verdicts, the details and the names are always kept, which is what a
 * person reads first.
 *
 * <p><b>Reading never throws</b>: a stored value that is not a JSON object reads back as null, "never
 * reported". One shared {@code ObjectMapper} and plain trees, so the native image needs no
 * registration for any of it.
 */
public final class RunnerNodeHealth {

  /** The widest report kept: the checks' data is dropped above it, never the verdict. */
  public static final int MAX_CHARS = 128 * 1024;

  /** Set on a kept report whose checks' data was dropped to fit {@link #MAX_CHARS}. */
  public static final String DATA_OMITTED = "dataOmitted";

  /** Shared and read-only: {@code valueToTree} and {@code writeValueAsString} mutate no mapper. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private RunnerNodeHealth() {}

  /**
   * One named check as the runner reported it.
   *
   * @param name its stable name
   * @param ok whether it found what it looks for
   * @param detail its line for a person
   * @param data its own facts: strings, numbers, booleans, null, and lists and maps of those; null
   *     reads as none
   */
  public record Check(String name, boolean ok, String detail, Map<String, Object> data) {}

  /**
   * The column text for one report, bounded at {@link #MAX_CHARS}.
   *
   * @param requestId the {@code healthCheck} it answered; null when nothing names one
   * @param checks each check in the runner's order; null reads as none
   */
  public static String encode(boolean ok, String detail, String requestId, List<Check> checks) {
    ObjectNode report = JSON.createObjectNode();
    report.put("ok", ok);
    report.put("detail", detail == null ? "" : detail);
    report.put("requestId", requestId);
    report.put(DATA_OMITTED, false);
    ArrayNode list = report.putArray("checks");
    for (Check check : checks == null ? List.<Check>of() : checks) {
      ObjectNode entry = list.addObject();
      entry.put("name", check.name());
      entry.put("ok", check.ok());
      entry.put("detail", check.detail());
      JsonNode data = data(check.data());
      entry.set("data", data);
    }
    String text = write(report);
    if (text.length() <= MAX_CHARS) {
      return text;
    }
    for (JsonNode check : list) {
      ((ObjectNode) check).putObject("data");
    }
    report.put(DATA_OMITTED, true);
    return write(report);
  }

  /** The stored report, or null when there is none or it cannot be read back as one. */
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

  /** A check's data as a tree; a map that cannot be one reads as empty rather than failing. */
  private static JsonNode data(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return JSON.createObjectNode();
    }
    try {
      JsonNode tree = JSON.valueToTree(data);
      return tree != null && tree.isObject() ? tree : JSON.createObjectNode();
    } catch (IllegalArgumentException unconvertible) {
      return JSON.createObjectNode();
    }
  }

  private static String write(JsonNode node) {
    try {
      return JSON.writeValueAsString(node);
    } catch (Exception unwritable) {
      throw new IllegalArgumentException("the node health report could not be written as JSON");
    }
  }
}
