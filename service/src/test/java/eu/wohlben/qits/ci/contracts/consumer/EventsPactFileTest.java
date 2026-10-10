package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.ci.contracts.GoldenFiles;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pact, {@code pacts/qits-ci-service_qits-events-service.json}</b>
 * (qits-1149), and the references every interaction must carry. Copied from qits-maintenance-
 * service's {@code ProjectsPactFileTest}.
 *
 * <p>The test writes the pact {@link EventsContract} describes, normalises it (interactions sorted
 * by description then state, pact-jvm's version dropped from {@code metadata}, 2-space indent,
 * trailing newline) and compares it to the committed file. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites the file instead.
 */
class EventsPactFileTest {

  static final String FILE = "qits-ci-service_qits-events-service.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void theCommittedPactIsWhatTheContractWrites() throws IOException {
    String raw = written();
    Path scratch = Path.of("target", "pacts", FILE);
    Files.createDirectories(scratch.getParent());
    Files.writeString(scratch, raw);
    GoldenFiles.compareOrWrite(
        GoldenFiles.repositoryRoot().resolve("pacts").resolve(FILE), normalise(raw));
  }

  @Test
  void everyInteractionCarriesBothReferences() throws IOException {
    JsonNode pact = MAPPER.readTree(normalise(written()));
    assertEquals(EventsGoldenMasters.CONSUMER, pact.path("consumer").path("name").asText());
    assertEquals(EventsGoldenMasters.PROVIDER, pact.path("provider").path("name").asText());
    assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());

    JsonNode interactions = pact.path("interactions");
    assertEquals(EventsContract.CASES.size(), interactions.size(), "one interaction per row");
    Map<String, String> keyOfKind =
        Map.of("operation", "operationId", "event", "event", "schedule", "schedule");
    Set<String> unique = new HashSet<>();
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode references = interaction.path("comments").path("references");
      JsonNode call = references.path("qits-call");
      assertEquals(EventsGoldenMasters.PROVIDER, call.path("app").asText(), description);
      assertTrue(call.path("operationId").isTextual(), description);
      JsonNode trigger = references.path("qits-trigger");
      String kind = trigger.path("kind").asText();
      assertTrue(keyOfKind.containsKey(kind), description + ": unknown trigger kind '" + kind + "'");
      assertEquals(EventsGoldenMasters.CONSUMER, trigger.path("app").asText(), description);
      assertTrue(
          description.startsWith(trigger.path(keyOfKind.get(kind)).asText() + ": "),
          description + ": the description leads with the trigger");
      String state = interaction.path("providerStates").path(0).path("name").asText();
      assertTrue(unique.add(description + "\u0000" + state), "(description, state) repeats: " + description);
    }
  }

  /** Every durable listener is in the contract: a new one must add its rows here. */
  @Test
  void everyDurableListenerIsInTheContract() {
    Set<String> declared = new HashSet<>();
    try (var scan =
        Files.list(GoldenFiles.repositoryRoot().resolve("service/src/main/java/eu/wohlben/qits/ci/bus"))) {
      for (Path file : (Iterable<Path>) scan::iterator) {
        String text = Files.readString(file);
        if (text.contains("implements QitsDurableEventListener")) {
          declared.add(file.getFileName().toString().replace(".java", ""));
        }
      }
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    Set<String> contracted = new HashSet<>();
    EventsContract.listeners().forEach(l -> contracted.add(l.getClass().getSimpleName()));
    assertEquals(declared, contracted, "EventsContract.listeners() names every durable listener");
  }

  static String written() {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(EventsContract.pact(), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  static String normalise(String raw) throws IOException {
    ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
    if (pact.path("metadata") instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(
          Comparator.comparing((JsonNode i) -> i.path("description").asText())
              .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    StringBuilder out = new StringBuilder();
    print(pact, "", out);
    return out.append('\n').toString();
  }

  /** {@code JSON.stringify(value, null, 2)}. */
  private static void print(JsonNode node, String indent, StringBuilder out) throws IOException {
    String inner = indent + "  ";
    if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
        print(field.getValue(), inner, out);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        out.append(inner);
        print(node.get(i), inner, out);
        out.append(i < node.size() - 1 ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else {
      out.append(MAPPER.writeValueAsString(node));
    }
  }
}
