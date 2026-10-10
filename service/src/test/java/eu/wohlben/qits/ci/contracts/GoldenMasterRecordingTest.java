package eu.wohlben.qits.ci.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-ci's provider golden masters</b> (epic qits-112) — {@code golden-masters/} at the
 * repository root, which the release publishes as {@code @qits/ci-golden-masters} and {@code
 * eu.wohlben.qits:qits-ci-golden-masters}, and which consumers write their pacts against. It works
 * as qits-projects' and qits-events' recorders do.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the endpoint through REST-assured as the {@code %test} dev user, keeps
 * only the list entries the state created, freezes ids and instants ({@link Freezer}) and renders
 * {@code golden-masters/<state-slug>/<operationId>.json}; then it renders {@code
 * golden-masters/index.json} describing all of them.
 *
 * <p><b>A query value may name a state param</b> ({@code {repositoryId}}), as a path can. The index
 * records the value sent, frozen. A consumer's pact matches that query literally, so the frozen
 * value must be the value sent: a state whose ids freeze to something else fails here.
 *
 * <p>An answer with no body (a 204, a 401) is recorded as JSON {@code null}: only its status is the
 * contract.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The provider as the index names it: the application name. A pact names the repository. */
  static final String PROVIDER = "qits-ci";

  /**
   * One recorded interaction.
   *
   * @param query the query sent; a value {@code {name}} is the state's param of that name
   * @param listFilteredTo the arrays ({@code $.a.b} paths, comma-separated) reduced to the entries
   *     the state created, or null — the index's {@code frozen.listFilteredTo}
   * @param requestBody the JSON a write sends, recorded into the index as the operation's {@code
   *     body} so a consumer's pact sends the same; null for a read and for a write whose operation
   *     takes no body (the openapi says which — the recording fails on a mismatch). A {@code {param}}
   *     in it is expanded from the state's params as a path's is, and recorded unexpanded
   * @param headers request headers a consumer sends because the answer depends on them — the
   *     bearer a door judges ({@link ContractBearers}); a {@code {param}} in a value is expanded and
   *     recorded unexpanded, as the index's {@code headers}
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      Map<String, String> query,
      int status,
      String listFilteredTo,
      String requestBody,
      Map<String, String> headers) {

    /** A read: no body, no header. */
    Interaction(
        String state,
        String operationId,
        String method,
        String path,
        Map<String, String> query,
        int status,
        String listFilteredTo) {
      this(state, operationId, method, path, query, status, listFilteredTo, null, Map.of());
    }
  }

  private static Interaction read(String state, String operationId, String path) {
    return new Interaction(state, operationId, "GET", path, Map.of(), 200, null);
  }

  private static Interaction write(
      String state, String operationId, String method, String path, int status, String body) {
    return new Interaction(
        state, operationId, method, path, Map.of(), status, null, body, Map.of());
  }

  /** The header a door that judges the caller's token reads: the state's {@code authorization}. */
  private static final Map<String, String> BEARER = Map.of("Authorization", "{authorization}");

  private static final String REPORTS =
      ProviderStates.A_RUN_WITH_REPORTS_FAILING_TESTS_AND_COVERAGE;

  private static final String RETRIES = ProviderStates.A_FAILED_RUN_THAT_RETRIES_ANOTHER;

  /**
   * Every (state, operation) a consumer reads. The first three are qits-landing-app's release request
   * page; the rest are qits-1149's: qits-bootstrap-cli, qits-platform-access-cli,
   * qits-maintenance-service, qits-projects-service, qits-artifacts-service,
   * qits-orchestrator-service and qits-ci-runner-daemon. Which consumer asked for which is the
   * round-2 states table's ({@code qits-ci-service-states.md}).
   */
  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST,
              "listRuns",
              "GET",
              "/ci/api/runs",
              Map.of("repositoryId", "{repositoryId}", "limit", "100"),
              200,
              "$.runs"),
          read(REPORTS, "listRunReports", "/ci/api/runs/{runId}/reports"),
          read(REPORTS, "getRunReport", "/ci/api/runs/{runId}/reports/{reportId}"),
          read(REPORTS, "getRunBaseline", "/ci/api/runs/{runId}/baseline"),
          read(
              REPORTS,
              "listRunBaselineReports",
              "/ci/api/runs/{runId}/baseline/reports/test-results"),
          new Interaction(
              ProviderStates.A_GREEN_RELEASE_RUN,
              "listRuns",
              "GET",
              "/ci/api/runs",
              Map.of("repositoryId", "{repositoryId}", "limit", "20"),
              200,
              "$.runs"),
          read(RETRIES, "getRun", "/ci/api/runs/{runId}"),
          write(RETRIES, "retryRun", "POST", "/ci/api/runs/{runId}/retry", 202, null),
          new Interaction(
              RETRIES,
              "listRuns",
              "GET",
              "/ci/api/runs",
              Map.of("repositoryId", "{repositoryId}", "limit", "100"),
              200,
              "$.runs"),
          read(ProviderStates.A_RUNNING_RUN, "getRun", "/ci/api/runs/{runId}"),
          new Interaction(
              ProviderStates.A_RUNNING_RUN,
              "putRunStepReport",
              "PUT",
              "/ci/api/runs/{runId}/steps/1/reports/test-results",
              Map.of(),
              204,
              null,
              """
              {"kindVersion":1,\
              "highlights":[{"severity":"bad","text":"1 test failed","metric":"tests.failed",\
              "value":1.0,"delta":null}],\
              "payload":{"totals":{"tests":128,"passed":127,"failed":1,"errored":0,"skipped":0,\
              "durationMs":44400}}}""",
              BEARER),
          new Interaction(
              ProviderStates.A_REPOSITORY_WHOSE_RELEASE_RECIPE_SELECTS_SCM_RELEASE,
              "triggerEvent",
              "POST",
              "/ci/api/events/trigger",
              Map.of(),
              200,
              null,
              """
              {"name":"SCMRelease","payload":{"branch":"{version}","commitSha":"{commitSha}",\
              "projectId":"{projectId}","repository":"{repositoryId}",\
              "repositoryName":"{repositoryName}","version":"{version}"}}""",
              Map.of()),
          new Interaction(
              ProviderStates.THE_MACHINE_GATE_IS_ON,
              "triggerEvent",
              "POST",
              "/ci/api/events/trigger",
              Map.of(),
              401,
              null,
              "{}",
              BEARER),
          write(
              ProviderStates.NO_RUNNERS,
              "createRunner",
              "POST",
              "/ci/api/runners",
              201,
              """
              {"name":"localhost","description":"the workstation's own runner","slots":2}"""),
          new Interaction(
              ProviderStates.AN_UNREGISTERED_RUNNER,
              "listRunners",
              "GET",
              "/ci/api/runners",
              Map.of(),
              200,
              "$.runners"),
          write(
              ProviderStates.AN_UNREGISTERED_RUNNER,
              "rotateRegistrationToken",
              "POST",
              "/ci/api/runners/{runnerId}/registration-token",
              200,
              null),
          new Interaction(
              ProviderStates.AN_UNREGISTERED_RUNNER,
              "registerRunner",
              "POST",
              "/ci/api/runners/{runnerId}/register",
              Map.of(),
              200,
              null,
              """
              {"capabilities":{"arch":"amd64","docker":true}}""",
              BEARER),
          new Interaction(
              ProviderStates.A_REGISTERED_RUNNER,
              "registerRunner",
              "POST",
              "/ci/api/runners/{runnerId}/register",
              Map.of(),
              409,
              null,
              """
              {"capabilities":{"arch":"amd64","docker":true}}""",
              BEARER),
          new Interaction(
              ProviderStates.A_CONNECTED_RUNNER,
              "listRunners",
              "GET",
              "/ci/api/runners",
              Map.of(),
              200,
              "$.runners"),
          write(
              ProviderStates.A_QUARANTINED_RUNNER,
              "greenlightRunner",
              "POST",
              "/ci/api/runners/{runnerId}/greenlight",
              200,
              null),
          new Interaction(
              ProviderStates.RUNNERS_WITH_FREE_SLOTS,
              "getRunQueue",
              "GET",
              "/ci/api/runs/queue",
              Map.of(),
              200,
              "$.running,$.queued,$.runners"),
          read(ProviderStates.A_PINNED_DAEMON, "getDaemonPin", "/ci/api/daemon"),
          read(
              ProviderStates.A_RELEASED_VERSION_WITH_ARTIFACT_DECISIONS,
              "listReleaseArtifacts",
              "/ci/api/repositories/{repositoryId}/releases/{version}/artifacts"),
          write(
              ProviderStates.A_RELEASE_REQUEST_WITH_RUNS_IN_FLIGHT,
              "cancelReleaseRequestRuns",
              "POST",
              "/ci/api/runs/cancellations",
              202,
              """
              {"repoId":"{repositoryId}","releaseRequestId":"{releaseRequestId}"}"""),
          write(
              ProviderStates.A_RELEASE_REQUEST_WHOSE_QA_RUN_FAILED,
              "rerunReleaseRequestPhase",
              "POST",
              "/ci/api/runs/rerun",
              202,
              """
              {"repoId":"{repositoryId}","releaseRequestId":"{releaseRequestId}",\
              "phase":"RELEASE_REQUEST"}"""),
          new Interaction(
              ProviderStates.A_REPOSITORY_THAT_DECLARES_A_RELEASE_PHASE,
              "getReleasePhase",
              "GET",
              "/ci/api/repositories/{repositoryId}/release-phase",
              Map.of("rev", "{rev}"),
              200,
              null),
          new Interaction(
              ProviderStates.A_COMMIT_WITH_A_RUN_IN_FLIGHT,
              "listActiveRuns",
              "GET",
              "/ci/api/runs/active",
              Map.of(),
              200,
              "$.runs"),
          read(
              ProviderStates.A_RELEASE_RUN_WHOSE_GATE_RUN_HAS_REPORTS,
              "listRunGateReports",
              "/ci/api/runs/{runId}/gate/reports"));

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

  @Inject ProviderStates states;

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    // slug -> recorded state, sorted by slug; operations sorted by operationId below
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    Set<String> takesBody = operationsTakingABody();
    for (Interaction interaction : INTERACTIONS) {
      if ((interaction.requestBody() != null) != takesBody.contains(interaction.operationId())) {
        failures.add(
            interaction.operationId()
                + (interaction.requestBody() != null
                    ? " takes no request body, but the recording sends one: record null."
                    : " takes a request body, but the recording sends none."));
      }
      Recorded recorded = record(interaction);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      if (!recorded.query().isEmpty()) {
        ObjectNode query = operation.putObject("query");
        recorded.query().forEach(query::put);
      }
      if (!interaction.headers().isEmpty()) {
        ObjectNode headers = operation.putObject("headers");
        new TreeMap<>(interaction.headers()).forEach(headers::put);
      }
      if (interaction.requestBody() != null) {
        operation.set("body", JSON.readTree(interaction.requestBody()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      if (interaction.listFilteredTo() == null) {
        frozen.putNull("listFilteredTo");
      } else {
        frozen.put("listFilteredTo", interaction.listFilteredTo());
      }
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /**
   * One interaction's frozen answer, its frozen params, the frozen query (keys sorted) and what was
   * frozen where.
   */
  record Recorded(
      JsonNode body, ObjectNode params, Map<String, String> query, Freezer freezer) {}

  private Recorded record(Interaction interaction) throws IOException {
    ProviderStates.Setup setup = states.setUp(interaction.state());
    try {
      return recordIn(interaction, setup);
    } finally {
      states.cleanUp();
    }
  }

  private Recorded recordIn(Interaction interaction, ProviderStates.Setup setup)
      throws IOException {
    Map<String, String> params = setup.params();
    Map<String, String> sent = new TreeMap<>();
    interaction.query().forEach((k, v) -> sent.put(k, expand(v, params)));

    var request = given().queryParams(sent);
    for (Map.Entry<String, String> header : interaction.headers().entrySet()) {
      request = request.header(header.getKey(), expand(header.getValue(), params));
    }
    if (interaction.requestBody() != null) {
      request =
          request.contentType("application/json").body(expand(interaction.requestBody(), params));
    } else {
      // As a browser sends a body-less call: RestAssured would otherwise add a form content type.
      request = request.noContentType();
    }
    Response response =
        request.when().request(interaction.method(), expand(interaction.path(), params));
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }
    // An answer with no body (204, a 401 challenge) is recorded as JSON null: there is nothing to
    // bind, only the status.
    JsonNode parsed = raw == null || raw.isBlank() ? NullNode.getInstance() : JSON.readTree(raw);
    // Only the ids: a sha or a version param could match another suite's rows by accident.
    List<String> created =
        params.values().stream().filter(v -> Freezer.UUID.matcher(v).matches()).toList();
    JsonNode body = recordable(parsed, interaction, created);

    Freezer freezer = new Freezer().seed(params.values()).uniqueTokens(setup.uniqueTokens());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    Map<String, String> query = new TreeMap<>();
    sent.forEach(
        (k, v) -> {
          String frozen = freezer.freezeParam(v);
          if (!frozen.equals(v)) {
            // A pact matches the query literally, so it would ask for a repository nobody seeded.
            throw new IllegalStateException(
                "State '"
                    + interaction.state()
                    + "' sent "
                    + k
                    + "="
                    + v
                    + ", which freezes to "
                    + frozen
                    + ": seed ids that are already frozen (Freezer.frozenId)");
          }
          query.put(k, frozen);
        });
    return new Recorded(freezer.freeze(body), frozenParams, query, freezer);
  }

  /**
   * The answer reduced to what the state controls: the {@code listFilteredTo} array keeps only the
   * entries mentioning an id the state created (its param values).
   */
  static JsonNode recordable(
      JsonNode body, Interaction interaction, Collection<String> createdIds) {
    JsonNode out = body.deepCopy();
    if (interaction.listFilteredTo() == null) {
      return out;
    }
    for (String path : interaction.listFilteredTo().split(",")) {
      ArrayNode list = array(out, path.strip());
      ArrayNode kept = JsonNodeFactory.instance.arrayNode();
      for (JsonNode entry : list) {
        String text = entry.toString();
        if (createdIds.stream().anyMatch(text::contains)) {
          kept.add(entry);
        }
      }
      list.removeAll();
      list.addAll(kept);
    }
    return out;
  }

  /** The array at a {@code $.a.b} path — the only JSONPath shape the table uses. */
  private static ArrayNode array(JsonNode root, String path) {
    if (!path.startsWith("$.")) {
      throw new IllegalArgumentException("Only $.a.b paths are supported: " + path);
    }
    JsonNode node = root;
    for (String segment : path.substring(2).split("\\.")) {
      node = node.path(segment);
    }
    if (!node.isArray()) {
      throw new IllegalStateException(path + " is not an array in " + root);
    }
    return (ArrayNode) node;
  }

  /** The operationIds whose operation declares a request body, read off the served openapi. */
  private static Set<String> operationsTakingABody() throws IOException {
    JsonNode paths =
        JSON.readTree(
                given()
                    .when()
                    .get("/ci/q/openapi?format=json")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString())
            .path("paths");
    Set<String> ids = new TreeSet<>();
    paths.forEach(
        path ->
            path.forEach(
                operation -> {
                  if (operation.has("operationId") && operation.has("requestBody")) {
                    ids.add(operation.get("operationId").asText());
                  }
                }));
    return ids;
  }

  /** The template with every {@code {param}} replaced by the state's value. */
  private static String expand(String template, Map<String, String> params) {
    Matcher m = TEMPLATE_PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
