package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The codec for {@code ci_run.avoid_runner_ids} (qits-556): the runners that failed this run's work
 * by the infrastructure, as a JSON array of runner ids — {@link InfraFailureRuns}' shape and its
 * storage decision (read whole, queried into by nothing).
 *
 * <p><b>A malformed value reads as avoiding nobody</b>, {@code InfraFailureRuns}' rule: a column
 * nobody can fix must not strand a run in the queue. Bounded at {@link #MAX}, oldest dropped first,
 * since {@code AUTO_RETRY_MAX} keeps an automatic chain short and only a person's repeated retries
 * could grow one.
 */
final class AvoidRunnerIds {

  /** The most runner ids a run keeps. */
  static final int MAX = 16;

  private static final ObjectMapper JSON = new ObjectMapper();

  private AvoidRunnerIds() {}

  static List<UUID> decode(String stored) {
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
    List<UUID> ids = new ArrayList<>();
    for (JsonNode id : array) {
      if (!id.isTextual()) {
        continue;
      }
      try {
        UUID parsed = UUID.fromString(id.asText());
        if (!ids.contains(parsed)) {
          ids.add(parsed);
        }
      } catch (IllegalArgumentException notAnId) {
        // skipped, not fatal — see the class comment
      }
    }
    return List.copyOf(ids);
  }

  /** {@code ids} with {@code runnerId} added when it is not already there, oldest dropped past MAX. */
  static List<UUID> with(List<UUID> ids, UUID runnerId) {
    List<UUID> all = new ArrayList<>(ids);
    if (runnerId != null && !all.contains(runnerId)) {
      all.add(runnerId);
    }
    while (all.size() > MAX) {
      all.remove(0);
    }
    return List.copyOf(all);
  }

  /** The column's text for {@code ids}, or null for none. */
  static String encode(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return null;
    }
    try {
      return JSON.writeValueAsString(ids.stream().map(UUID::toString).toList());
    } catch (com.fasterxml.jackson.core.JsonProcessingException unreachable) {
      throw new IllegalStateException(unreachable);
    }
  }
}
