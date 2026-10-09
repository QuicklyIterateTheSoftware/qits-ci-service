package eu.wohlben.qits.ci.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The stored shape of a runner's node health report and its bound (qits-896). */
class RunnerNodeHealthTest {

  @Test
  void aReportIsStoredWithEveryCheckAndItsData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("containers", List.of("a", "b"));
    data.put("runnerContainer", null);
    JsonNode stored =
        RunnerNodeHealth.decode(
            RunnerNodeHealth.encode(
                false,
                "nodeInventory: docker ps failed",
                "req-1",
                List.of(
                    new RunnerNodeHealth.Check("docker", true, "server 27", Map.of("v", 27)),
                    new RunnerNodeHealth.Check("nodeInventory", false, "failed", data))));

    assertFalse(stored.path("ok").asBoolean(true));
    assertEquals("nodeInventory: docker ps failed", stored.path("detail").asText());
    assertEquals("req-1", stored.path("requestId").asText());
    assertFalse(stored.path(RunnerNodeHealth.DATA_OMITTED).asBoolean(true));
    assertEquals(2, stored.path("checks").size());
    assertEquals(27, stored.path("checks").get(0).path("data").path("v").asInt());
    JsonNode inventory = stored.path("checks").get(1);
    assertEquals("nodeInventory", inventory.path("name").asText());
    assertEquals("b", inventory.path("data").path("containers").get(1).asText());
    assertTrue(inventory.path("data").has("runnerContainer"), "a null fact is still a fact");
  }

  @Test
  void anUnansweredRequestIsAReportWithNoChecks() {
    JsonNode stored = RunnerNodeHealth.decode(RunnerNodeHealth.encode(false, "NO_ANSWER", "r", null));

    assertEquals("NO_ANSWER", stored.path("detail").asText());
    assertEquals(0, stored.path("checks").size());
    assertTrue(stored.path("requestId").isTextual());
  }

  @Test
  void aReportAboveTheBoundKeepsItsVerdictsAndDropsItsData() {
    String big = "x".repeat(RunnerNodeHealth.MAX_CHARS);
    String text =
        RunnerNodeHealth.encode(
            true,
            "all 2 checks passed",
            null,
            List.of(
                new RunnerNodeHealth.Check("docker", true, "ok", Map.of("blob", big)),
                new RunnerNodeHealth.Check("session", true, "connected", Map.of("slots", 2))));

    assertTrue(text.length() <= RunnerNodeHealth.MAX_CHARS);
    JsonNode stored = RunnerNodeHealth.decode(text);
    assertTrue(stored.path(RunnerNodeHealth.DATA_OMITTED).asBoolean());
    assertTrue(stored.path("requestId").isNull());
    assertEquals(List.of("docker", "session"), names(stored));
    for (JsonNode check : stored.path("checks")) {
      assertTrue(check.path("ok").asBoolean());
      assertTrue(check.path("data").isObject() && check.path("data").isEmpty());
    }
  }

  @Test
  void whatIsNotAnObjectReadsAsNeverReported() {
    assertNull(RunnerNodeHealth.decode(null));
    assertNull(RunnerNodeHealth.decode(" "));
    assertNull(RunnerNodeHealth.decode("[1]"));
    assertNull(RunnerNodeHealth.decode("{not json"));
  }

  private static List<String> names(JsonNode stored) {
    return java.util.stream.StreamSupport.stream(stored.path("checks").spliterator(), false)
        .map(c -> c.path("name").asText())
        .toList();
  }
}
