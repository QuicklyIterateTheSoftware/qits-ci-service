package eu.wohlben.qits.ci.contracts.consumer;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>qits-events' recorded answers, as qits-ci's consumer pact reads them</b> (qits-1149). Adapted
 * from qits-maintenance-service's {@code testing/contracts/GoldenMasters}, with two changes:
 *
 * <ul>
 *   <li><b>The pact binds only what qits-ci reads.</b> Each interaction names the body paths it
 *       consumes ({@code $.events[*].id}, …). The recorded body is cut down to those paths before
 *       the matchers are built, so a field qits-ci never reads can change without breaking the
 *       pact. A consumed path that names a whole value ({@code $.events}) keeps it whole.
 *   <li><b>The request query is this consumer's own</b>, like a request body: what the library's
 *       {@code EventsQuery} sends, never the recorder's {@code query}.
 * </ul>
 *
 * <p>qits-events publishes {@code golden-masters/} as {@code
 * eu.wohlben.qits:qits-events-golden-masters}, a test-scoped pin in the root pom. Matchers come
 * from the index's {@code frozen} lists: {@code ids} get a UUID matcher, {@code instants} an
 * ISO-8601 regex, {@code listFilteredTo} a "contains at least n" array; every other leaf a type
 * match. Every other non-empty array is {@code minMaxArrayLike(n, n)}.
 */
public final class EventsGoldenMasters {

  public static final String CONSUMER = "qits-ci-service";
  public static final String PROVIDER = "qits-events-service";

  /** The provider as the golden-master index names it: the application name. */
  static final String INDEX_PROVIDER = "qits-events";

  static final String ROOT = "golden-masters/";

  static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static volatile JsonNode index;

  private EventsGoldenMasters() {}

  /** One recorded (state, operation), as the index describes it. */
  record Operation(
      String state,
      Map<String, String> params,
      String operationId,
      String method,
      String path,
      int status,
      String file,
      Set<String> ids,
      Set<String> instants,
      String listFilteredTo) {}

  static Map<String, String> params(String state) {
    Map<String, String> params = new LinkedHashMap<>();
    stateNode(state)
        .path("params")
        .fields()
        .forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
    return params;
  }

  static Operation operation(String state, String operationId) {
    for (JsonNode op : stateNode(state).path("operations")) {
      if (operationId.equals(op.path("operationId").asText())) {
        JsonNode frozen = op.path("frozen");
        JsonNode filtered = frozen.path("listFilteredTo");
        return new Operation(
            state,
            params(state),
            operationId,
            op.path("method").asText(),
            op.path("path").asText(),
            op.path("status").asInt(),
            op.path("file").asText(),
            strings(frozen.path("ids")),
            strings(frozen.path("instants")),
            filtered.isTextual() ? filtered.asText() : null);
      }
    }
    throw new IllegalArgumentException(
        "qits-events' golden masters record no operation " + operationId + " in state '" + state
            + "'");
  }

  /** The recorded JSON for one (state, operation), parsed fresh. */
  static JsonNode json(String state, String operationId) {
    try {
      return MAPPER.readTree(resource(ROOT + operation(state, operationId).file()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Add the V4 HTTP interaction for one recorded (state, operation), reached from {@code trigger},
   * sending {@code query}, binding only {@code consumes} of the recorded answer.
   */
  static PactBuilder interaction(
      PactBuilder builder,
      String state,
      String operationId,
      Trigger trigger,
      Map<String, String> query,
      Set<String> consumes) {
    Objects.requireNonNull(trigger, "trigger: every interaction names the entry point that makes it");
    if (consumes == null || consumes.isEmpty()) {
      throw new IllegalArgumentException(
          operationId + ": name the body paths this consumer reads (status-only has no body)");
    }
    Operation op = operation(state, operationId);
    DslPart body = responseBody(op, consumes);
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", PROVIDER);
    call.put("operationId", operationId);
    references.put("qits-call", call);
    references.put("qits-trigger", trigger.reference());
    return builder.expectsToReceiveHttpInteraction(
        Trigger.description(operationId, trigger),
        http -> {
          http.state(state, new LinkedHashMap<String, Object>(op.params()));
          http.withRequest(
              request -> {
                request.method(op.method());
                request.path(op.path());
                query.forEach(request::queryParameter);
                return request;
              });
          http.willRespondWith(
              response ->
                  response
                      .status(op.status())
                      .header("Content-Type", Matchers.regexp("application/json.*", "application/json"))
                      .body(body));
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  // --- the body ---------------------------------------------------------------------------------

  static DslPart responseBody(Operation op, Set<String> consumes) {
    JsonNode recorded = json(op.state(), op.operationId());
    if (!recorded.isObject()) {
      throw new IllegalStateException(
          "golden master " + op.state() + "/" + op.operationId() + ": only an object body is"
              + " supported, got " + recorded.getNodeType());
    }
    for (String path : consumes) {
      if (!reaches(recorded, "$", path)) {
        throw new IllegalStateException(
            "golden master " + op.state() + "/" + op.operationId() + " records nothing at " + path
                + ", which this consumer reads");
      }
    }
    JsonNode kept = prune(recorded, "$", consumes);
    PactDslJsonBody root = new PactDslJsonBody();
    fillObject(root, Shape.of(kept), "$", op);
    return root;
  }

  /** Whether {@code path} names something in {@code node}; {@code [*]} needs one element at most. */
  private static boolean reaches(JsonNode node, String at, String path) {
    if (at.equals(path)) {
      return true;
    }
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        String child = at + "." + e.getKey();
        if (path.equals(child) || path.startsWith(child + ".") || path.startsWith(child + "[")) {
          return reaches(e.getValue(), child, path);
        }
      }
      return false;
    }
    if (node.isArray()) {
      String child = at + "[*]";
      if (node.isEmpty()) {
        return false;
      }
      for (JsonNode element : node) {
        if (reaches(element, child, path)) {
          return true;
        }
      }
    }
    return false;
  }

  /** {@code node} with every field off the consumed paths removed. */
  private static JsonNode prune(JsonNode node, String at, Set<String> consumes) {
    if (consumes.contains(at)) {
      return node.deepCopy();
    }
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                String child = at + "." + e.getKey();
                if (onAConsumedPath(child, consumes)) {
                  out.set(e.getKey(), prune(e.getValue(), child, consumes));
                }
              });
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      node.forEach(element -> out.add(prune(element, at + "[*]", consumes)));
      return out;
    }
    return node.deepCopy();
  }

  private static boolean onAConsumedPath(String path, Set<String> consumes) {
    for (String consumed : consumes) {
      if (consumed.equals(path)
          || consumed.startsWith(path + ".")
          || consumed.startsWith(path + "[")) {
        return true;
      }
    }
    return false;
  }

  private static void fillObject(PactDslJsonBody target, Shape shape, String path, Operation op) {
    for (Map.Entry<String, Shape> field : shape.fields.entrySet()) {
      String name = field.getKey();
      Shape child = field.getValue();
      String childPath = path + "." + name;
      switch (child.kind) {
        case NULL -> target.nullValue(name);
        case LEAF -> leaf(target, name, child, childPath, op);
        case OBJECT -> {
          if (child.nullable) {
            throw unsupported(op, childPath, "an object that is null in some elements");
          }
          PactDslJsonBody nested = target.object(name);
          fillObject(nested, child, childPath, op);
          nested.closeObject();
        }
        case ARRAY -> array(target, name, child, childPath, op);
      }
    }
  }

  private static void leaf(
      PactDslJsonBody target, String name, Shape leaf, String path, Operation op) {
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      if (leaf.nullable) {
        target.or(
            name, example.asText(), new RegexMatcher(UUID_REGEX, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.uuid(name, example.asText());
      }
    } else if (op.instants().contains(path)) {
      if (leaf.nullable) {
        target.or(
            name, example.asText(), new RegexMatcher(ISO_INSTANT, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, example.asText());
      }
    } else if (leaf.nullable) {
      target.or(name, scalar(example), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (example.isTextual()) {
      target.stringType(name, example.asText());
    } else if (example.isNumber()) {
      target.numberType(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanType(name, example.asBoolean());
    } else {
      throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
    }
  }

  private static void array(
      PactDslJsonBody target, String name, Shape array, String path, Operation op) {
    if (array.nullable) {
      throw unsupported(op, path, "an array that is null in some elements");
    }
    boolean filtered = path.equals(op.listFilteredTo());
    int n = array.length;
    if (n == 0) {
      target.array(name).closeArray();
      return;
    }
    Shape element = array.element;
    String elementPath = path + "[*]";
    switch (element.kind) {
      case OBJECT -> {
        PactDslJsonBody template =
            filtered ? target.minArrayLike(name, n, n) : target.minMaxArrayLike(name, n, n, n);
        fillObject(template, element, elementPath, op);
        DslPart closed = template.closeObject();
        ((PactDslJsonArray) closed).closeArray();
      }
      case LEAF -> {
        PactDslJsonRootValue value = rootLeaf(element, elementPath, op);
        if (filtered) {
          target.minArrayLike(name, n, value, n);
        } else {
          target.minMaxArrayLike(name, n, n, value, n);
        }
      }
      default -> throw unsupported(op, elementPath, "an array of " + element.kind);
    }
  }

  private static PactDslJsonRootValue rootLeaf(Shape leaf, String path, Operation op) {
    if (leaf.nullable) {
      throw unsupported(op, path, "an array holding nulls");
    }
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      return PactDslJsonRootValue.uuid(example.asText());
    }
    if (op.instants().contains(path)) {
      return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
    }
    if (example.isTextual()) {
      return PactDslJsonRootValue.stringType(example.asText());
    }
    if (example.isNumber()) {
      return PactDslJsonRootValue.numberType(example.numberValue());
    }
    if (example.isBoolean()) {
      return PactDslJsonRootValue.booleanType(example.asBoolean());
    }
    throw unsupported(op, path, "a " + example.getNodeType() + " array element");
  }

  private static Object scalar(JsonNode example) {
    if (example.isTextual()) {
      return example.asText();
    }
    if (example.isNumber()) {
      return example.numberValue();
    }
    if (example.isBoolean()) {
      return example.asBoolean();
    }
    throw new IllegalStateException("not a scalar: " + example);
  }

  private static IllegalStateException unsupported(Operation op, String path, String what) {
    return new IllegalStateException(
        "golden master " + op.state() + "/" + op.operationId() + ": " + path + " is " + what
            + ", which EventsGoldenMasters cannot express as a pact matcher yet");
  }

  /** A recorded value's structure, an array's elements merged into one template. */
  private static final class Shape {
    enum Kind {
      NULL,
      LEAF,
      OBJECT,
      ARRAY
    }

    Kind kind;
    boolean nullable;
    JsonNode example;
    final LinkedHashMap<String, Shape> fields = new LinkedHashMap<>();
    Shape element;
    int length;

    static Shape of(JsonNode node) {
      Shape shape = new Shape();
      if (node == null || node.isNull() || node.isMissingNode()) {
        shape.kind = Kind.NULL;
        shape.nullable = true;
      } else if (node.isObject()) {
        shape.kind = Kind.OBJECT;
        node.fields().forEachRemaining(e -> shape.fields.put(e.getKey(), of(e.getValue())));
      } else if (node.isArray()) {
        shape.kind = Kind.ARRAY;
        shape.length = node.size();
        for (JsonNode e : node) {
          shape.element = shape.element == null ? of(e) : merge(shape.element, of(e));
        }
      } else {
        shape.kind = Kind.LEAF;
        shape.example = node;
      }
      return shape;
    }

    static Shape merge(Shape a, Shape b) {
      if (a.kind == Kind.NULL) {
        b.nullable = true;
        return b;
      }
      if (b.kind == Kind.NULL) {
        a.nullable = true;
        return a;
      }
      if (a.kind != b.kind) {
        throw new IllegalStateException(
            "golden master array elements disagree: " + a.kind + " and " + b.kind);
      }
      a.nullable |= b.nullable;
      switch (a.kind) {
        case OBJECT -> {
          List<String> keys = new ArrayList<>(a.fields.keySet());
          for (String key : b.fields.keySet()) {
            if (!keys.contains(key)) {
              keys.add(key);
            }
          }
          LinkedHashMap<String, Shape> merged = new LinkedHashMap<>();
          for (String key : keys) {
            Shape left = a.fields.get(key);
            Shape right = b.fields.get(key);
            merged.put(
                key,
                left == null
                    ? merge(of(null), right)
                    : right == null ? merge(left, of(null)) : merge(left, right));
          }
          a.fields.clear();
          a.fields.putAll(merged);
        }
        case ARRAY -> {
          a.length = Math.min(a.length, b.length);
          a.element =
              a.element == null
                  ? b.element
                  : b.element == null ? a.element : merge(a.element, b.element);
        }
        default -> {
          // LEAF: keep a's example; the matcher is a type match.
        }
      }
      return a;
    }
  }

  // --- reading the jar --------------------------------------------------------------------------

  private static JsonNode index() {
    JsonNode loaded = index;
    if (loaded == null) {
      try {
        loaded = MAPPER.readTree(resource(ROOT + "index.json"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (loaded.path("formatVersion").asInt() != 1) {
        throw new IllegalStateException(
            "golden-masters/index.json is formatVersion " + loaded.path("formatVersion")
                + "; EventsGoldenMasters reads formatVersion 1");
      }
      if (!INDEX_PROVIDER.equals(loaded.path("provider").asText())) {
        throw new IllegalStateException(
            "golden-masters/index.json is " + loaded.path("provider") + "'s, not "
                + INDEX_PROVIDER + "'s");
      }
      index = loaded;
    }
    return loaded;
  }

  private static JsonNode stateNode(String state) {
    for (JsonNode node : index().path("states")) {
      if (state.equals(node.path("name").asText())) {
        return node;
      }
    }
    throw new IllegalArgumentException("qits-events' golden masters record no state '" + state + "'");
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    array.forEach(e -> out.add(e.asText()));
    return Set.copyOf(out);
  }

  private static String resource(String name) {
    ClassLoader loader = EventsGoldenMasters.class.getClassLoader();
    InputStream found = loader == null ? null : loader.getResourceAsStream(name);
    try (InputStream in = found) {
      if (in == null) {
        throw new IllegalStateException(
            name + " is not on the test classpath — is eu.wohlben.qits:qits-events-golden-masters"
                + " a test dependency of this module?");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
