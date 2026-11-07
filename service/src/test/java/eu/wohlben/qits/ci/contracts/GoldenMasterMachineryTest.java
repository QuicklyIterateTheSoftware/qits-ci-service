package eu.wohlben.qits.ci.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The recorder's {@link Freezer}, without an application: sticky id numbering (qits-1917). */
class GoldenMasterMachineryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode json(String text) throws IOException {
    return JSON.readTree(text);
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static String f(int n) {
    return Freezer.frozenId(n);
  }

  @Test
  void withoutPinsParamsNumberInKeyOrderAsBefore() throws Exception {
    Map<String, String> params = new TreeMap<>(Map.of("b", id(), "a", id(), "c", "4711"));
    String other = id();
    Freezer pinned = new Freezer().seed(params, Map.of());
    Freezer legacy = new Freezer().seed(params.values());
    JsonNode answer = json("{\"x\":\"" + other + "\",\"a\":\"" + params.get("a") + "\"}");

    assertEquals(f(1), pinned.freezeParam(params.get("a")));
    assertEquals(f(2), pinned.freezeParam(params.get("b")));
    assertEquals(legacy.freeze(answer), pinned.freeze(answer));
    assertEquals("{\"x\":\"" + f(3) + "\",\"a\":\"" + f(1) + "\"}", pinned.freeze(answer).toString());
  }

  @Test
  void aRemovedParamLeavesAGapAndTheOthersKeepTheirNumbers() throws Exception {
    // committed: a=1, gone=2, c=3; "gone" is gone now
    Map<String, String> committed = Map.of("a", f(1), "gone", f(2), "c", f(3));
    Map<String, String> params = Map.of("a", id(), "c", id());
    String answerOnly = id();
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(3), freezer.freezeParam(params.get("c")));
    assertEquals("\"" + f(4) + "\"", freezer.freeze(json("\"" + answerOnly + "\"")).toString());
  }

  @Test
  void anAddedParamTakesTheHighestNumberPlusOne() {
    // committed: b=1, d=2; "a" sorts first but is new
    Map<String, String> committed = Map.of("b", f(1), "d", f(2));
    Map<String, String> params = Map.of("a", id(), "b", id(), "d", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(3), freezer.freezeParam(params.get("a")));
    assertEquals(f(1), freezer.freezeParam(params.get("b")));
    assertEquals(f(2), freezer.freezeParam(params.get("d")));
  }

  @Test
  void answerOnlyIdsNumberAboveEveryPinnedOne() throws Exception {
    // a pin above a gap: the answer's ids go above it, never into the gap
    Map<String, String> committed = Map.of("a", f(1), "z", f(7));
    Map<String, String> params = Map.of("a", id(), "z", id());
    String x = id();
    String y = id();
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(
        "[\"" + f(8) + "\",\"" + f(9) + "\",\"" + f(7) + "\"]",
        freezer.freeze(json("[\"" + x + "\",\"" + y + "\",\"" + params.get("z") + "\"]")).toString());
  }

  @Test
  void aCommittedValueNotOfTheFrozenFormPinsNothing() {
    Map<String, String> params = Map.of("a", id(), "b", id());
    Map<String, String> committed = Map.of("a", id(), "b", "00000000-0000-4000-8000-00000000000g");
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(2), freezer.freezeParam(params.get("b")));
  }

  @Test
  void twoParamsNamingOneEntityShareTheFirstNumber() {
    String shared = id();
    // committed (inconsistently) gave them 2 and 5: the first in key order wins
    Map<String, String> committed = Map.of("a", f(2), "b", f(5), "c", f(1));
    Map<String, String> params = Map.of("a", shared, "b", shared.toUpperCase(), "c", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(2), freezer.freezeParam(params.get("a")));
    assertEquals(f(2), freezer.freezeParam(params.get("b")));
    assertEquals(f(1), freezer.freezeParam(params.get("c")));
  }

  @Test
  void aNumberPinnedTwiceToDifferentEntitiesPinsOnlyTheFirst() {
    Map<String, String> committed = Map.of("a", f(1), "b", f(1));
    Map<String, String> params = Map.of("a", id(), "b", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(2), freezer.freezeParam(params.get("b")));
  }

  @Test
  void aUuidEmbeddedInACursorKeepsItsCommittedNumber() throws Exception {
    // "events to catch up on, fifty at a time": the cursor sorts first and was …0001, the exact
    // event ids …0002-4; the cursor must not be renumbered above them
    Map<String, String> committed =
        Map.of(
            "cursor", "2100-01-01T00:00:00Z," + f(1),
            "firstEventId", f(2),
            "secondEventId", f(3),
            "thirdEventId", f(4));
    Map<String, String> params =
        Map.of(
            "cursor", "2100-01-01T00:00:00Z,00000000-0000-0000-0000-000000000000",
            "firstEventId", id(),
            "secondEventId", id(),
            "thirdEventId", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals("2100-01-01T00:00:00Z," + f(1), freezer.freezeParam(params.get("cursor")));
    assertEquals(f(2), freezer.freezeParam(params.get("firstEventId")));
    assertEquals(f(3), freezer.freezeParam(params.get("secondEventId")));
    assertEquals(f(4), freezer.freezeParam(params.get("thirdEventId")));
    assertEquals("\"" + f(5) + "\"", freezer.freeze(json("\"" + id() + "\"")).toString());
  }

  @Test
  void anEmbeddedUuidCountThatDiffersFromTheCommittedOnePinsNothingInThatParam() {
    Map<String, String> committed = Map.of("a", f(1), "cursor", "x," + f(5));
    String first = id();
    String second = id();
    Map<String, String> params = Map.of("a", id(), "cursor", first + "," + second);
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(2) + "," + f(3), freezer.freezeParam(params.get("cursor")));
  }

  @Test
  void committedParamsAreReadPerSlugAndAbsentWithoutAnIndex(@TempDir Path dir) throws Exception {
    assertEquals(Map.of(), GoldenMasterRecordingTest.committedParams(dir.resolve("index.json")));
    Files.writeString(
        dir.resolve("index.json"),
        "{\"states\":[{\"slug\":\"s\",\"params\":{\"eventId\":\"" + f(1) + "\"}}]}");
    assertEquals(
        Map.of("s", Map.of("eventId", f(1))),
        GoldenMasterRecordingTest.committedParams(dir.resolve("index.json")));
  }
}
