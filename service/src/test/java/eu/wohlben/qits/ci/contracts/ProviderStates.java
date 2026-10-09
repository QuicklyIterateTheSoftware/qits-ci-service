package eu.wohlben.qits.ci.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.entity.CiReport;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiReportRepository;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>The provider states qits-ci answers for</b> (epic qits-112): a state name → a setup that seeds
 * runs and returns the state's parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>The ids are fixed, and each one is already its frozen form</b> ({@link Freezer#frozenId}).
 * A consumer asks {@code GET /ci/api/runs?repositoryId=<id>} with the id the golden master recorded,
 * as a literal query value: a query cannot carry a provider-state expression. So the state must
 * seed that exact repository. The params are numbered in sorted-key order, the order the recorder
 * seeds the {@link Freezer} in, so freezing changes none of them.
 *
 * <p><b>A state removes its rows again</b> ({@link #cleanUp}): the suite shares one database, and a
 * {@code RUNNING} row left behind would show in every other class's queue.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST =
      "a repository with the runs of a release request";

  public static final String A_RUN_WITH_REPORTS_FAILING_TESTS_AND_COVERAGE =
      "a run with reports: failing tests and coverage";

  /** When the seeded runs were accepted: a fixed base, a minute apart per run. */
  private static final Instant SEEDED_AT = Instant.parse("2026-01-01T00:00:00Z");

  /**
   * What a state hands back.
   *
   * @param params the state's parameters, keys sorted — what a pact {@code @State} method returns
   * @param uniqueTokens random tokens the state put into names; none here, the ids are fixed
   */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject CiRunRepository runs;

  @Inject CiReportRepository reports;

  @Inject CiScmReleaseRepository scmReleases;

  @Inject ObjectMapper json;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  /** Run ids the states wrote, removed again by {@link #cleanUp()} with their reports. */
  private final List<String> seededRuns = new ArrayList<>();

  /** Release rows the states wrote, removed again by {@link #cleanUp()}. */
  private final List<String> seededReleases = new ArrayList<>();

  public ProviderStates() {
    states.put(
        A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST, this::aRepositoryWithTheRunsOfARequest);
    states.put(
        A_RUN_WITH_REPORTS_FAILING_TESTS_AND_COVERAGE, this::aRunWithReportsFailingTestsAndCoverage);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /**
   * Removes the runs, reports and releases the states wrote. Callers run this after each recording
   * or verification.
   */
  public void cleanUp() {
    if (seededRuns.isEmpty() && seededReleases.isEmpty()) {
      return;
    }
    List<String> ids = List.copyOf(seededRuns);
    List<String> releases = List.copyOf(seededReleases);
    seededRuns.clear();
    seededReleases.clear();
    QuarkusTransaction.requiringNew().run(() -> remove(ids, releases));
  }

  /** Deletes the rows; joins the caller's transaction. */
  private void remove(List<String> runIds, List<String> releaseIds) {
    if (!runIds.isEmpty()) {
      reports.delete("runId in ?1", runIds);
      runs.delete("id in ?1", runIds);
    }
    if (!releaseIds.isEmpty()) {
      scmReleases.delete("id in ?1", releaseIds);
    }
  }

  /**
   * The state's slug: the name lower-cased, every run of non-alphanumeric characters replaced by
   * {@code -}. It names the state's directory under {@code golden-masters/}.
   */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * One repository with five runs, newest first as the listing answers:
   *
   * <ol>
   *   <li>an automation run on {@code main} ({@code ReleaseRequestAutomation}), succeeded. It names
   *       no request: the request names it;
   *   <li>the request's release run ({@code SCMRelease}, phase {@code RELEASE}), running;
   *   <li>the request's third QA run (phase {@code RELEASE_REQUEST}), succeeded;
   *   <li>its second QA run, cancelled — a newer fold superseded it;
   *   <li>its first QA run, failed.
   * </ol>
   *
   * <p>Every request run names the request by {@code releaseRequestId}. Together they show every
   * status the release request page draws: running, succeeded, failed and cancelled.
   */
  private Setup aRepositoryWithTheRunsOfARequest() {
    // Sorted-key order, so each id is the frozen form the recorder would give it.
    Map<String, String> params = new TreeMap<>();
    params.put("automationRunId", Freezer.frozenId(1));
    params.put("qaCancelledRunId", Freezer.frozenId(2));
    params.put("qaFailedRunId", Freezer.frozenId(3));
    params.put("qaSucceededRunId", Freezer.frozenId(4));
    params.put("releaseRequestId", Freezer.frozenId(5));
    params.put("releaseRunId", Freezer.frozenId(6));
    params.put("repositoryId", Freezer.frozenId(7));
    String repo = params.get("repositoryId");
    String request = params.get("releaseRequestId");
    String backing = "release/" + request;

    List<CiRun> seeded = new ArrayList<>();
    CiRun failed = run(params.get("qaFailedRunId"), repo, backing, 1, CiRunStatus.FAILED);
    qa(failed, request);
    seeded.add(failed);

    CiRun cancelled = run(params.get("qaCancelledRunId"), repo, backing, 2, CiRunStatus.CANCELLED);
    qa(cancelled, request);
    cancelled.cancellationReason = "superseded by a newer fold of the request";
    seeded.add(cancelled);

    CiRun succeeded = run(params.get("qaSucceededRunId"), repo, backing, 3, CiRunStatus.SUCCESS);
    qa(succeeded, request);
    seeded.add(succeeded);

    CiRun release =
        run(params.get("releaseRunId"), repo, "2026.101.120000", 4, CiRunStatus.RUNNING);
    release.phase = CiRunPhase.RELEASE;
    release.releaseRequestId = request;
    release.triggerEventName = "SCMRelease";
    release.configPath = ".config/qits/release.yml";
    release.finishedAt = null;
    seeded.add(release);

    CiRun automation = run(params.get("automationRunId"), repo, "main", 5, CiRunStatus.SUCCESS);
    automation.triggerEventName = "ReleaseRequestAutomation";
    automation.configPath = ".config/qits/ci-event-automation-dependencies.yml";
    seeded.add(automation);

    List<String> ids = seeded.stream().map(r -> r.id).toList();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // A run a failed earlier setup left behind would collide on the fixed id.
              runs.delete("id in ?1", ids);
              seeded.forEach(runs::persist);
            });
    seededRuns.addAll(ids);
    return new Setup(Collections.unmodifiableMap(params), List.of());
  }

  /**
   * One failed QA run of a release request with three reports from its step 1, and a baseline to
   * compare with: the repository released {@code 2026.101.120000} from another request, whose QA run
   * succeeded.
   *
   * <ul>
   *   <li>{@code test-results}: 128 tests, one assertion failure in {@code
   *       eu.wohlben.qits.contract.BillingServiceTest#refundsTheFullAmount} at {@code
   *       BillingServiceTest.java:42}, and one suite.
   *   <li>{@code coverage}: 81.3% of lines (−1.2 against the baseline's 82.5%), diff coverage 60%
   *       (6 of 10 changed lines), two uncovered changed ranges in one file.
   *   <li>{@code entity-changes}: one table changed since the baseline, with its highlights.
   * </ul>
   *
   * <p>{@code reportId} is the test-results report, the one whose payload is recorded.
   */
  private Setup aRunWithReportsFailingTestsAndCoverage() {
    // Sorted-key order, so each id is the frozen form the recorder would give it.
    Map<String, String> params = new TreeMap<>();
    params.put("baselineRequestId", Freezer.frozenId(1));
    params.put("baselineRunId", Freezer.frozenId(2));
    params.put("coverageReportId", Freezer.frozenId(3));
    params.put("entityChangesReportId", Freezer.frozenId(4));
    params.put("releaseRequestId", Freezer.frozenId(5));
    params.put("reportId", Freezer.frozenId(6));
    params.put("repositoryId", Freezer.frozenId(7));
    params.put("runId", Freezer.frozenId(8));
    String repo = params.get("repositoryId");
    String version = "2026.101.120000";

    CiRun baselineRun =
        run(
            params.get("baselineRunId"),
            repo,
            "release/" + params.get("baselineRequestId"),
            1,
            CiRunStatus.SUCCESS);
    qa(baselineRun, params.get("baselineRequestId"));

    CiScmRelease release = new CiScmRelease();
    release.id = "contract-release-" + version;
    release.repoId = repo;
    release.repoName = "contract-service";
    release.version = version;
    release.eventId = "contract-release-event";
    release.occurredAt = SEEDED_AT.plus(Duration.ofMinutes(2));
    release.seenAt = release.occurredAt;
    release.releaseRequestId = params.get("baselineRequestId");
    release.commitSha = String.format("%040x", 2);

    CiRun run =
        run(
            params.get("runId"),
            repo,
            "release/" + params.get("releaseRequestId"),
            3,
            CiRunStatus.FAILED);
    qa(run, params.get("releaseRequestId"));

    List<CiReport> seeded =
        List.of(
            report(params.get("reportId"), run.id, "test-results", testResults(run), version,
                baselineRun.id,
                List.of(
                    highlight("bad", "1 test failed", "tests.failed", 1.0, null),
                    highlight("info", "+3 tests vs " + version, "tests.total", 128.0, 3.0))),
            report(params.get("coverageReportId"), run.id, "coverage", coverage(version), version,
                baselineRun.id,
                List.of(
                    highlight("warn", "diff coverage 60.0% (6/10 changed lines)",
                        "coverage.diff", 60.0, null),
                    highlight("info", "coverage 81.3% (-1.2)", "coverage.total", 81.3, -1.2))),
            report(params.get("entityChangesReportId"), run.id, "entity-changes",
                entityChanges(version), version, baselineRun.id,
                List.of(
                    highlight("warn", "Entities changed since " + version + ": +0 ~1 \u22120 tables",
                        "entities.tables.changed", 1.0, null),
                    highlight("warn", "Columns removed or narrowed: 1",
                        "entities.columns.narrowed", 1.0, null))));

    List<String> ids = List.of(baselineRun.id, run.id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // Rows a failed earlier setup left behind would collide on the fixed ids.
              remove(ids, List.of(release.id));
              runs.persist(baselineRun);
              runs.persist(run);
              scmReleases.persist(release);
              seeded.forEach(reports::persist);
            });
    seededRuns.addAll(ids);
    seededReleases.add(release.id);
    return new Setup(Collections.unmodifiableMap(params), List.of());
  }

  /** A report row as {@code CiReportStore.submit} writes one, under a fixed id, from step 1. */
  private CiReport report(
      String id,
      String runId,
      String kind,
      Map<String, Object> payload,
      String baselineVersion,
      String baselineRunId,
      List<Map<String, Object>> highlights) {
    CiReport row = new CiReport();
    row.id = UUID.fromString(id);
    row.runId = runId;
    row.stepIndex = 1;
    row.kind = kind;
    row.kindVersion = 1;
    row.payload = write(payload);
    row.highlights = write(highlights);
    row.baselineRunId = baselineRunId;
    row.baselineVersion = baselineVersion;
    row.payloadBytes = row.payload.getBytes(StandardCharsets.UTF_8).length;
    row.submittedAt = SEEDED_AT.plus(Duration.ofMinutes(4));
    return row;
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** One highlight, as the CLI writes it ({@code Highlight}: severity good/info/warn/bad). */
  private static Map<String, Object> highlight(
      String severity, String text, String metric, Double value, Double delta) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("severity", severity);
    out.put("text", text);
    out.put("metric", metric);
    out.put("value", value);
    out.put("delta", delta);
    return out;
  }

  /**
   * A map that keeps its keys in the order given, {@code key, value, key, value}: a stored payload
   * must serialise the same way every run, which {@code Map.of} does not promise.
   */
  private static Map<String, Object> ordered(Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return out;
  }

  /** A {@code test-results} v1 payload, shaped as the CLI's {@code TestResults}. */
  private static Map<String, Object> testResults(CiRun run) {
    Map<String, Object> coordinates = new LinkedHashMap<>();
    coordinates.put("language", "java");
    coordinates.put("tool", "surefire");
    coordinates.put("repository", ordered("projectId", run.projectId, "name", run.repoName));
    coordinates.put("commitSha", run.commitSha);
    coordinates.put("file", "service/src/test/java/eu/wohlben/qits/contract/BillingServiceTest.java");
    coordinates.put("className", "eu.wohlben.qits.contract.BillingServiceTest");
    coordinates.put("testName", "refundsTheFullAmount");
    coordinates.put("lineStart", 42);
    coordinates.put("lineEnd", 58);
    Map<String, Object> failure = new LinkedHashMap<>();
    failure.put("coordinates", coordinates);
    failure.put("shape", "ASSERTION");
    failure.put("failureType", "org.opentest4j.AssertionFailedError");
    failure.put("message", "expected: <100.00> but was: <90.00>");
    failure.put(
        "stackTrace",
        "org.opentest4j.AssertionFailedError: expected: <100.00> but was: <90.00>\n"
            + "\tat eu.wohlben.qits.contract.BillingServiceTest.refundsTheFullAmount"
            + "(BillingServiceTest.java:51)");
    failure.put("durationMs", 37);
    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("tests", 128);
    totals.put("passed", 125);
    totals.put("failed", 1);
    totals.put("errored", 0);
    totals.put("skipped", 2);
    totals.put("durationMs", 44400);
    Map<String, Object> suite = new LinkedHashMap<>();
    suite.put("language", "java");
    suite.put("tool", "surefire");
    suite.put("module", "service");
    suite.put("tests", 128);
    suite.put("failed", 1);
    suite.put("errored", 0);
    suite.put("skipped", 2);
    suite.put("durationMs", 44400);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("totals", totals);
    out.put("suites", List.of(suite));
    out.put("failures", List.of(failure));
    out.put("truncated", false);
    return out;
  }

  /** A {@code coverage} v1 payload, shaped as the CLI's {@code Coverage}. */
  private static Map<String, Object> coverage(String version) {
    String file = "service/src/main/java/eu/wohlben/qits/contract/BillingService.java";
    Map<String, Object> total = new LinkedHashMap<>();
    total.put("linesCovered", 813);
    total.put("linesTotal", 1000);
    total.put("percent", 81.3);
    Map<String, Object> baseline = new LinkedHashMap<>();
    baseline.put("version", version);
    baseline.put("percent", 82.5);
    Map<String, Object> diff = new LinkedHashMap<>();
    diff.put("baselineVersion", version);
    diff.put("linesChanged", 10);
    diff.put("linesCovered", 6);
    diff.put("percent", 60.0);
    diff.put("uncovered", List.of(ordered("file", file, "ranges", List.of(List.of(57, 58),
        List.of(64, 65)))));
    Map<String, Object> fileCoverage = new LinkedHashMap<>();
    fileCoverage.put("file", file);
    fileCoverage.put("linesCovered", 40);
    fileCoverage.put("linesTotal", 52);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sources", List.of(ordered("language", "java", "tool", "jacoco")));
    out.put("total", total);
    out.put("baselineTotal", baseline);
    out.put("diff", diff);
    out.put("files", List.of(fileCoverage));
    return out;
  }

  /** An {@code entity-changes} v1 payload, shaped as the CLI's {@code EntityChanges}. */
  private static Map<String, Object> entityChanges(String version) {
    Map<String, Object> change = new LinkedHashMap<>();
    change.put("name", "amount");
    change.put("before", "numeric(12,2)");
    change.put("after", "numeric(10,2)");
    Map<String, Object> columns = new LinkedHashMap<>();
    columns.put("added", List.of("refunded_at"));
    columns.put("removed", List.of());
    columns.put("changed", List.of(change));
    Map<String, Object> table = new LinkedHashMap<>();
    table.put("name", "refund");
    table.put("status", "CHANGED");
    table.put("origin", "flyway");
    table.put("columns", columns);
    Map<String, Object> unit = new LinkedHashMap<>();
    unit.put("file", "service/src/main/resources/db/migration");
    unit.put("unit", "billing");
    unit.put("status", "CHANGED");
    unit.put("tables", List.of(table));
    unit.put("relations", ordered("added", List.of(), "removed", List.of()));
    unit.put("before", null);
    unit.put("after", null);
    unit.put("notes", List.of());
    Map<String, Object> side = new LinkedHashMap<>();
    side.put("version", version);
    side.put("tagSha", String.format("%040x", 2));
    side.put("hadDiagram", true);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("baseline", side);
    out.put("units", List.of(unit));
    out.put("truncated", false);
    return out;
  }

  /** A QA run of the request: phase {@code RELEASE_REQUEST}, from {@code ReleaseRequestChanged}. */
  private static void qa(CiRun run, String request) {
    run.phase = CiRunPhase.RELEASE_REQUEST;
    run.releaseRequestId = request;
    run.triggerEventName = "ReleaseRequestChanged";
    run.configPath = ".config/qits/release-request.yml";
  }

  /** A finished event-triggered run, {@code minute} minutes after {@link #SEEDED_AT}. */
  private static CiRun run(String id, String repo, String branch, int minute, CiRunStatus status) {
    Instant at = SEEDED_AT.plus(Duration.ofMinutes(minute));
    CiRun run = new CiRun();
    run.id = id;
    run.repoId = repo;
    run.projectId = "contract-project";
    run.repoName = "contract-service";
    run.branch = branch;
    run.commitSha = String.format("%040x", minute);
    run.status = status;
    run.triggerType = CiTriggerType.EVENT;
    run.triggerEventId = String.format("contract-event-%d", minute);
    run.createdAt = at;
    run.startedAt = at.plusSeconds(5);
    run.finishedAt = at.plusSeconds(50);
    return run;
  }
}
