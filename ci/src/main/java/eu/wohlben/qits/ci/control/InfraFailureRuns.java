package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The codec for {@code ci_runner.infra_failure_runs}: the distinct runs a runner's streak of
 * runner-caused failures spans, as a JSON array of run ids — {@code StepImages}' shape for a list,
 * and {@code downstream_repos}' storage decision (read whole, queried into by nothing).
 *
 * <p><b>Bounded at {@link #MAX} ids, oldest dropped first.</b> The streak ends at the first step that
 * starts and a quarantine is decided at a handful, so a long array is a runner that keeps failing
 * while already out of service — worth a count, never an unbounded column. <b>A malformed value reads
 * as an empty streak</b> rather than throwing, {@code ExpectedStepDurations}' rule: a column nobody
 * can fix must not cost a runner its step.
 */
final class InfraFailureRuns {

  /** The most run ids a streak keeps. */
  static final int MAX = 32;

  private static final ObjectMapper JSON = new ObjectMapper();

  private InfraFailureRuns() {}

  static List<String> decode(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    JsonNode array;
    try {
      array = JSON.readTree(stored);
    } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException malformed) {
      return List.of();
    }
    if (array == null || !array.isArray()) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode id : array) {
      if (id.isTextual() && !id.asText().isBlank() && !ids.contains(id.asText())) {
        ids.add(id.asText());
      }
    }
    return List.copyOf(ids);
  }

  /** {@code streak} with {@code runId} added when it is not already there, oldest dropped past MAX. */
  static List<String> with(List<String> streak, String runId) {
    List<String> ids = new ArrayList<>(streak);
    if (runId != null && !runId.isBlank() && !ids.contains(runId)) {
      ids.add(runId);
    }
    while (ids.size() > MAX) {
      ids.remove(0);
    }
    return List.copyOf(ids);
  }

  /** The column's text for {@code ids}, or null for none. */
  static String encode(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return null;
    }
    try {
      return JSON.writeValueAsString(ids);
    } catch (com.fasterxml.jackson.core.JsonProcessingException unreachable) {
      throw new IllegalStateException(unreachable);
    }
  }
}
