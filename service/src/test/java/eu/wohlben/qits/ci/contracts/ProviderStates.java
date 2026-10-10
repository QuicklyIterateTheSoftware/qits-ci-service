package eu.wohlben.qits.ci.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.SuiteRunnerPresence;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.entity.CiReleaseAnnouncement;
import eu.wohlben.qits.ci.entity.CiReport;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.githost.FakeGitHostRepoListing;
import eu.wohlben.qits.ci.githost.StubGitHost;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.ScriptedRunTokens;
import eu.wohlben.qits.ci.idp.StubIdp;
import eu.wohlben.qits.ci.persistence.CiReleaseAnnouncementRepository;
import eu.wohlben.qits.ci.persistence.CiReportRepository;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import eu.wohlben.qits.ci.runnerhost.CiRunnerPins;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * <b>The provider states qits-ci answers for</b> (epic qits-112, ticket qits-1149): a state name → a
 * setup that seeds rows and returns the state's parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>The ids are fixed, and each one is already its frozen form</b> ({@link Freezer#frozenId}).
 * A consumer asks {@code GET /ci/api/runs?repositoryId=<id>} with the id the golden master recorded,
 * as a literal query value: a query cannot carry a provider-state expression. So the state must
 * seed that exact repository. The params are numbered in sorted-key order, the order the recorder
 * seeds the {@link Freezer} in, so freezing changes none of them ({@link #ids}).
 *
 * <p><b>A state removes what it wrote again</b> ({@link #cleanUp}): the suite shares one database,
 * and a {@code RUNNING} row left behind would show in every other class's queue. That includes what
 * the recorded call itself wrote — a retry's run, a runner a create declared, a health check a
 * registration queued — so the clean-up goes by repository and by runner, not only by seeded id.
 *
 * <p><b>Test seams a state arms, and disarms in {@link #cleanUp}</b>: {@link ContractCandidateRepos}
 * (the trigger reads only the state's repository), {@link ContractBearers} (a consumer's bearer
 * becomes the machine identity the door judges), {@link SuiteRunnerPresence} (a runner counts as
 * connected), a {@link StubIdp} behind {@link IdpCommissioner} and {@link ScriptedRunTokens} behind
 * {@link RunCommissions} — the last two through {@code QuarkusMock}, which holds for the test method
 * the state runs in.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST =
      "a repository with the runs of a release request";

  public static final String A_RUN_WITH_REPORTS_FAILING_TESTS_AND_COVERAGE =
      "a run with reports: failing tests and coverage";

  public static final String A_GREEN_RELEASE_RUN = "a green release run";

  public static final String A_FAILED_RUN_THAT_RETRIES_ANOTHER =
      "a failed run that retries another";

  public static final String A_RUNNING_RUN = "a running run";

  public static final String A_REPOSITORY_WHOSE_RELEASE_RECIPE_SELECTS_SCM_RELEASE =
      "a repository whose release recipe selects SCMRelease";

  public static final String THE_MACHINE_GATE_IS_ON = "the machine gate is on";

  public static final String NO_RUNNERS = "no runners";

  public static final String AN_UNREGISTERED_RUNNER = "an unregistered runner";

  public static final String A_REGISTERED_RUNNER = "a registered runner";

  public static final String A_CONNECTED_RUNNER = "a connected runner";

  public static final String A_QUARANTINED_RUNNER = "a quarantined runner";

  public static final String RUNNERS_WITH_FREE_SLOTS = "runners with free slots";

  public static final String A_PINNED_DAEMON = "a pinned daemon";

  public static final String A_RELEASED_VERSION_WITH_ARTIFACT_DECISIONS =
      "a released version with artifact decisions";

  public static final String A_RELEASE_REQUEST_WITH_RUNS_IN_FLIGHT =
      "a release request with runs in flight";

  public static final String A_RELEASE_REQUEST_WHOSE_QA_RUN_FAILED =
      "a release request whose QA run failed";

  public static final String A_REPOSITORY_THAT_DECLARES_A_RELEASE_PHASE =
      "a repository that declares a release phase";

  public static final String A_COMMIT_WITH_A_RUN_IN_FLIGHT = "a commit with a run in flight";

  public static final String A_RELEASE_RUN_WHOSE_GATE_RUN_HAS_REPORTS =
      "a release run whose gate run has reports";

  /** When the seeded runs were accepted: a fixed base, a minute apart per run. */
  private static final Instant SEEDED_AT = Instant.parse("2026-01-01T00:00:00Z");

  /** The runner every runner state seeds — the name qits-bootstrap-cli declares on a workstation. */
  static final String RUNNER_NAME = "localhost";

  /** The released version the git-backed states tag, and the version their payloads name. */
  static final String VERSION = "2026.1001.1";

  /**
   * The bearer a registration token is presented as, and the subject the edge forwards for it — what
   * the runner row records as its registration token's subject.
   */
  static final String REGISTRATION_BEARER = "Bearer qits_tok_contract-registration";

  static final String REGISTRATION_SUBJECT = "tok-ci-runner-registration-contract";

  /** The bearer a run's ci-run token is presented as, and its subject. */
  static final String RUN_BEARER = "Bearer qits_tok_contract-ci-run";

  static final String RUN_SUBJECT = "tok-ci-run-contract";

  /** A bearer no idp issued — what the machine gate's state refuses. */
  static final String JUNK_BEARER = "Bearer not-a-token-any-idp-issued";

  /**
   * What a state hands back.
   *
   * @param params the state's parameters, keys sorted — what a pact {@code @State} method returns
   * @param uniqueTokens strings the answer holds that differ per build — frozen to {@code frozen-token-N} (the
   *     daemon pin's version and the runner version a runner view targets, which every
   *     dependency bump moves)
   */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject CiRunRepository runs;

  @Inject CiStepRepository steps;

  @Inject CiReportRepository reports;

  @Inject CiScmReleaseRepository scmReleases;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiReleaseAnnouncementRepository announcements;

  @Inject CiStepRelay relay;

  @Inject ContractCandidateRepos candidates;

  @Inject ContractBearers bearers;

  @Inject SuiteRunnerPresence presence;

  @Inject FakeGitHostRepoListing gitHostListing;

  @Inject CiDaemonPins daemonPins;

  /** The runner version every runner view targets — moved by every bump, so frozen as a token. */
  @Inject CiRunnerPins runnerPins;

  @Inject ObjectMapper json;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  /** Run ids the states wrote, removed again by {@link #cleanUp()} with their steps and reports. */
  private final List<String> seededRuns = new ArrayList<>();

  /** Repositories the states wrote runs for: every run of theirs goes, the recorded call's too. */
  private final List<String> seededRepos = new ArrayList<>();

  /** Release rows the states wrote, removed again by {@link #cleanUp()}. */
  private final List<String> seededReleases = new ArrayList<>();

  /** Whether a runner state ran: every runner named {@link #RUNNER_NAME} goes, and its runs. */
  private boolean seededRunners;

  /** The idp a runner state stood up, closed by {@link #cleanUp()}. */
  private StubIdp idp;

  public ProviderStates() {
    states.put(
        A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST, this::aRepositoryWithTheRunsOfARequest);
    states.put(
        A_RUN_WITH_REPORTS_FAILING_TESTS_AND_COVERAGE, this::aRunWithReportsFailingTestsAndCoverage);
    states.put(A_GREEN_RELEASE_RUN, this::aGreenReleaseRun);
    states.put(A_FAILED_RUN_THAT_RETRIES_ANOTHER, this::aFailedRunThatRetriesAnother);
    states.put(A_RUNNING_RUN, this::aRunningRun);
    states.put(
        A_REPOSITORY_WHOSE_RELEASE_RECIPE_SELECTS_SCM_RELEASE,
        this::aRepositoryWhoseReleaseRecipeSelectsScmRelease);
    states.put(THE_MACHINE_GATE_IS_ON, this::theMachineGateIsOn);
    states.put(NO_RUNNERS, this::noRunners);
    states.put(AN_UNREGISTERED_RUNNER, this::anUnregisteredRunner);
    states.put(A_REGISTERED_RUNNER, this::aRegisteredRunner);
    states.put(A_CONNECTED_RUNNER, this::aConnectedRunner);
    states.put(A_QUARANTINED_RUNNER, this::aQuarantinedRunner);
    states.put(RUNNERS_WITH_FREE_SLOTS, this::runnersWithFreeSlots);
    states.put(A_PINNED_DAEMON, this::aPinnedDaemon);
    states.put(
        A_RELEASED_VERSION_WITH_ARTIFACT_DECISIONS, this::aReleasedVersionWithArtifactDecisions);
    states.put(A_RELEASE_REQUEST_WITH_RUNS_IN_FLIGHT, this::aReleaseRequestWithRunsInFlight);
    states.put(A_RELEASE_REQUEST_WHOSE_QA_RUN_FAILED, this::aReleaseRequestWhoseQaRunFailed);
    states.put(
        A_REPOSITORY_THAT_DECLARES_A_RELEASE_PHASE, this::aRepositoryThatDeclaresAReleasePhase);
    states.put(A_COMMIT_WITH_A_RUN_IN_FLIGHT, this::aCommitWithARunInFlight);
    states.put(A_RELEASE_RUN_WHOSE_GATE_RUN_HAS_REPORTS, this::aReleaseRunWhoseGateRunHasReports);
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
   * Removes what the states wrote and disarms the seams they armed. Callers run this after each
   * recording or verification.
   */
  public void cleanUp() {
    List<String> ids = List.copyOf(seededRuns);
    List<String> repos = List.copyOf(seededRepos);
    List<String> releases = List.copyOf(seededReleases);
    boolean runnersToo = seededRunners;
    seededRuns.clear();
    seededRepos.clear();
    seededReleases.clear();
    seededRunners = false;
    ids.forEach(relay::drop);
    candidates.unpin();
    bearers.reset();
    gitHostListing.set();
    if (idp != null) {
      idp.close();
      idp = null;
    }
    if (ids.isEmpty() && repos.isEmpty() && releases.isEmpty() && !runnersToo) {
      return;
    }
    QuarkusTransaction.requiringNew().run(() -> remove(ids, repos, releases, runnersToo));
  }

  /** Deletes the rows; joins the caller's transaction. */
  private void remove(
      List<String> runIds, List<String> repoIds, List<String> releaseIds, boolean runnersToo) {
    List<String> all = new ArrayList<>(runIds);
    if (!repoIds.isEmpty()) {
      runs.list("repoId in ?1", repoIds).forEach(run -> all.add(run.id));
      announcements.delete("repoId in ?1", repoIds);
    }
    if (runnersToo) {
      List<UUID> runnerIds =
          runnerRows.list("name", RUNNER_NAME).stream().map(r -> r.id).toList();
      if (!runnerIds.isEmpty()) {
        runs.list("targetRunnerId in ?1 or runnerId in ?1", runnerIds)
            .forEach(run -> all.add(run.id));
        runnerIds.forEach(presence::forget);
        runnerRows.delete("id in ?1", runnerIds);
      }
    }
    if (!all.isEmpty()) {
      steps.delete("runId in ?1", all);
      reports.delete("runId in ?1", all);
      runs.delete("id in ?1", all);
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

  /**
   * The params, each id-valued key given the frozen id the recorder would give it: the keys sorted,
   * the id keys numbered in that order from 1. The other values stay as given.
   */
  private static Map<String, String> ids(Map<String, String> fixed, String... idKeys) {
    Map<String, String> params = new TreeMap<>(fixed);
    for (String key : idKeys) {
      params.put(key, null);
    }
    int n = 0;
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (entry.getValue() == null) {
        entry.setValue(Freezer.frozenId(++n));
      }
    }
    return params;
  }

  private static Setup setup(Map<String, String> params, String... uniqueTokens) {
    return new Setup(Collections.unmodifiableMap(params), List.of(uniqueTokens));
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
   * <p>The baseline run carries a {@code test-results} report of its own, which is what a step
   * reads to compare with ({@code GET /runs/{runId}/baseline/reports/test-results}).
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
                    highlight(
                        "warn", "Entities changed since " + version + ": +0 ~1 \u22120 tables",
                        "entities.tables.changed", 1.0, null),
                    highlight("warn", "Columns removed or narrowed: 1",
                        "entities.columns.narrowed", 1.0, null))),
            // The baseline's own test results, which a step compares with
            // (listRunBaselineReports). The id is no param: the freezer numbers it after them.
            report(Freezer.frozenId(9), baselineRun.id, "test-results", testResults(baselineRun),
                null, null,
                List.of(highlight("info", "128 tests", "tests.total", 128.0, null))));

    List<String> ids = List.of(baselineRun.id, run.id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // Rows a failed earlier setup left behind would collide on the fixed ids.
              remove(ids, List.of(), List.of(release.id), false);
              runs.persist(baselineRun);
              runs.persist(run);
              scmReleases.persist(release);
              seeded.forEach(reports::persist);
            });
    seededRuns.addAll(ids);
    seededReleases.add(release.id);
    return new Setup(Collections.unmodifiableMap(params), List.of());
  }

  // --- the run states (qits-1149) --------------------------------------------------------------

  /**
   * One repository whose newest run is a green release run: an {@code EVENT} run of {@code
   * .config/qits/release.yml} for {@code SCMRelease}, {@code SUCCESS}, at {@code commitSha}.
   */
  private Setup aGreenReleaseRun() {
    String sha = sha(7);
    Map<String, String> params = ids(Map.of("commitSha", sha), "runId", "repositoryId");
    CiRun release = run(
        params.get("runId"), params.get("repositoryId"), VERSION, 7, CiRunStatus.SUCCESS);
    release.commitSha = sha;
    release.phase = CiRunPhase.RELEASE;
    release.triggerEventName = "SCMRelease";
    release.configPath = ".config/qits/release.yml";
    persist(List.of(release), List.of());
    return setup(params);
  }

  /**
   * A finished run that failed, and that is itself qits-ci's automatic retry of an earlier failed
   * run: {@code retryOfRunId} names that run, {@code autoRetry} is true. Two steps: step 0 succeeded,
   * step 1 failed with exit code 1 and its output. The earlier run is in the listing too.
   *
   * <p>Its stored pipeline is a plain {@code ci-event-*.yml}, so a retry ({@code retryRun}) re-fires
   * exactly that and needs no git host: the answer is a new run's id, which stays {@code QUEUED}.
   */
  private Setup aFailedRunThatRetriesAnother() {
    Map<String, String> params = ids(Map.of(), "repositoryId", "retryOfRunId", "runId");
    String repo = params.get("repositoryId");
    CiRun first = run(params.get("retryOfRunId"), repo, "main", 1, CiRunStatus.FAILED);
    eventPipeline(first);
    CiRun retry = run(params.get("runId"), repo, "main", 2, CiRunStatus.FAILED);
    eventPipeline(retry);
    retry.retryOfRunId = first.id;
    retry.retryReason = "the runner lost its connection during step 1";
    persist(
        List.of(first, retry),
        List.of(
            step(first, 0, CiStepStatus.FAILED, 1, "the runner went away\n"),
            step(retry, 0, CiStepStatus.SUCCESS, 0, "Cloning into 'contract-service'...\n"),
            step(retry, 1, CiStepStatus.FAILED, 1, "[ERROR] Tests run: 128, Failures: 1\n")));
    return setup(params);
  }

  /**
   * A run in flight: {@code RUNNING}, step 0 finished, step 1 live — its output so far is in {@code
   * live}. The run holds a ci-run token, presented as the {@code authorization} param, so its step
   * may submit a report ({@code putRunStepReport}).
   */
  private Setup aRunningRun() {
    Map<String, String> params =
        ids(Map.of("authorization", RUN_BEARER), "repositoryId", "runId");
    CiRun running = run(
        params.get("runId"), params.get("repositoryId"), "main", 3, CiRunStatus.RUNNING);
    eventPipeline(running);
    running.finishedAt = null;
    persist(
        List.of(running),
        List.of(step(running, 0, CiStepStatus.SUCCESS, 0, "Cloning into 'contract-service'...\n")));
    relay.begin(running.id, 1);
    relay.started(running.id, running.startedAt.plusSeconds(10));
    relay.append(running.id, "[INFO] Building contract-service\n");
    ScriptedRunTokens tokens = new ScriptedRunTokens(RUN_SUBJECT);
    tokens.forRun(running.id, Map.of());
    QuarkusMock.installMockForType(tokens, RunCommissions.class);
    bearers.grant(RUN_BEARER, RUN_SUBJECT, "qits:ci-run");
    return setup(params);
  }

  /**
   * A repository whose {@code .config/qits/release.yml} declares a {@code release:} slot, tagged
   * {@link #VERSION} at {@code commitSha} on the git host. An {@code SCMRelease} naming it records
   * one release run, which stays {@code QUEUED}. The trigger reads this repository alone ({@link
   * ContractCandidateRepos}).
   */
  private Setup aRepositoryWhoseReleaseRecipeSelectsScmRelease() {
    Map<String, String> params =
        ids(
            Map.of("repositoryName", "contract-service", "version", VERSION),
            "projectId",
            "repositoryId");
    String sha = seedReleasedRepository(params.get("repositoryId"));
    params.put("commitSha", sha);
    params = new TreeMap<>(params);
    // Named, as qits-projects' catalogue names it: a release selects the payload's
    // repositoryName, and the git host is read name-addressed.
    StubGitHost.alias(
        params.get("projectId"), params.get("repositoryName"), params.get("repositoryId"));
    candidates.pin(
        CiRepoRef.of(
            params.get("repositoryId"), params.get("projectId"), params.get("repositoryName")));
    seededRepos.add(params.get("repositoryId"));
    return setup(params);
  }

  /** The machine gate on: a bearer no idp issued is refused 401. */
  private Setup theMachineGateIsOn() {
    bearers.refuseUnknown();
    return setup(new TreeMap<>(Map.of("authorization", JUNK_BEARER)));
  }

  // --- the runner states -----------------------------------------------------------------------

  /** No runner named {@link #RUNNER_NAME}, and an idp that commissions its registration token. */
  private Setup noRunners() {
    clearRunners();
    standUpIdp();
    return setup(new TreeMap<>(), runnerPins.version());
  }

  /**
   * Runner {@link #RUNNER_NAME}, declared and never registered or seen; its registration token is
   * presented as the {@code authorization} param. The idp commissions what a rotation or a
   * registration asks for.
   */
  private Setup anUnregisteredRunner() {
    Map<String, String> params = ids(Map.of("authorization", REGISTRATION_BEARER), "runnerId");
    CiRunner runner = runner(params.get("runnerId"));
    persistRunner(runner);
    standUpIdp();
    bearers.grant(REGISTRATION_BEARER, REGISTRATION_SUBJECT, "qits:ci-runner-registration");
    return setup(params, runnerPins.version());
  }

  /** Runner {@link #RUNNER_NAME}, registered already: its registration token opens nothing now. */
  private Setup aRegisteredRunner() {
    Map<String, String> params = ids(Map.of("authorization", REGISTRATION_BEARER), "runnerId");
    CiRunner runner = runner(params.get("runnerId"));
    registered(runner);
    persistRunner(runner);
    bearers.grant(REGISTRATION_BEARER, REGISTRATION_SUBJECT, "qits:ci-runner-registration");
    return setup(params, runnerPins.version());
  }

  /** Runner {@link #RUNNER_NAME}, registered and connected, not quarantined. */
  private Setup aConnectedRunner() {
    Map<String, String> params = ids(Map.of(), "runnerId");
    CiRunner runner = runner(params.get("runnerId"));
    registered(runner);
    persistRunner(runner);
    presence.declareConnected(runner.id);
    return setup(params, runnerPins.version());
  }

  /** Runner {@link #RUNNER_NAME}, registered and connected, quarantined after failed runs. */
  private Setup aQuarantinedRunner() {
    Map<String, String> params = ids(Map.of(), "runnerId");
    CiRunner runner = runner(params.get("runnerId"));
    registered(runner);
    runner.quarantinedAt = SEEDED_AT.plus(Duration.ofMinutes(30));
    runner.quarantineReason = "3 infrastructure failures in a row";
    persistRunner(runner);
    presence.declareConnected(runner.id);
    return setup(params, runnerPins.version());
  }

  /**
   * Runner {@link #RUNNER_NAME}, connected with two slots: one holds a running run, one is free, and
   * one run waits in the queue.
   */
  private Setup runnersWithFreeSlots() {
    Map<String, String> params = ids(
        Map.of(), "queuedRunId", "repositoryId", "runnerId", "runningRunId");
    CiRunner runner = runner(params.get("runnerId"));
    registered(runner);
    runner.slots = 2;
    persistRunner(runner);
    presence.declareConnected(runner.id);
    String repo = params.get("repositoryId");
    CiRun running = run(params.get("runningRunId"), repo, "main", 1, CiRunStatus.RUNNING);
    eventPipeline(running);
    running.finishedAt = null;
    running.runnerId = runner.id;
    CiRun queued = run(
        params.get("queuedRunId"), repo, "feature/csv-export", 2, CiRunStatus.QUEUED);
    eventPipeline(queued);
    queued.startedAt = null;
    queued.finishedAt = null;
    persist(List.of(running, queued), List.of());
    return setup(params, runnerPins.version());
  }

  /** The daemon pin, as configured: nothing to seed. Its version moves with every bump. */
  private Setup aPinnedDaemon() {
    return setup(new TreeMap<>(), daemonPins.answer().version());
  }

  // --- the release request states (qits-projects' calls) --------------------------------------

  /**
   * Release {@link #VERSION} of one repository, with a decision recorded for each of its four
   * artifacts: an image published, a maven artifact unchanged since an earlier version, an npm
   * package published, and a docs bundle still owed.
   */
  private Setup aReleasedVersionWithArtifactDecisions() {
    Map<String, String> params =
        ids(
            Map.of("version", VERSION, "repositoryName", "contract-service"), "repositoryId", "runId");
    String repo = params.get("repositoryId");
    Instant finished = SEEDED_AT.plus(Duration.ofMinutes(10));
    List<CiReleaseAnnouncement> rows =
        List.of(
            announcement(
                params, 0, "docker", "qits/contract-service", null, "PUBLISHED", null, finished),
            announcement(params, 1, "maven", "eu.wohlben.qits:contract-service-golden-masters",
                "if-changed", "UNCHANGED", "2026.901.1", finished),
            announcement(params, 2, "npm", "@qits/contract-service-golden-masters", "if-changed",
                "PUBLISHED", null, finished),
            announcement(params, 3, "docs", "@apidocs/contract-service", null, null, null, null));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              announcements.delete("repoId", repo);
              rows.forEach(announcements::persist);
            });
    seededRepos.add(repo);
    return setup(params);
  }

  /**
   * A release request with a QA run waiting in the queue: the cancellation reaches it at once, as
   * no runner holds it.
   */
  private Setup aReleaseRequestWithRunsInFlight() {
    Map<String, String> params = ids(Map.of(), "queuedRunId", "releaseRequestId", "repositoryId");
    String repo = params.get("repositoryId");
    String request = params.get("releaseRequestId");
    CiRun queued = run(
        params.get("queuedRunId"), repo, "release/" + request, 2, CiRunStatus.QUEUED);
    qa(queued, request);
    queued.startedAt = null;
    queued.finishedAt = null;
    persist(List.of(queued), List.of());
    return setup(params);
  }

  /** A release request whose newest QA run failed: the QA phase may be asked again. */
  private Setup aReleaseRequestWhoseQaRunFailed() {
    Map<String, String> params = ids(Map.of(), "releaseRequestId", "repositoryId", "runId");
    String request = params.get("releaseRequestId");
    CiRun failed = run(
        params.get("runId"), params.get("repositoryId"), "release/" + request, 1, CiRunStatus.FAILED);
    qa(failed, request);
    failed.triggerConfig =
        "event: ReleaseRequestChanged\nsteps:\n  - image: alpine:3\n    script: ./mvnw verify\n";
    failed.triggerEventPayload = "{}";
    persist(
        List.of(failed), List.of(step(failed, 0, CiStepStatus.FAILED, 1, "[ERROR] BUILD FAILURE\n")));
    return setup(params);
  }

  /**
   * A repository on the git host whose {@code release.yml} at tag {@link #VERSION} declares a
   * {@code release:} slot; {@code rev} is that tag's ref.
   */
  private Setup aRepositoryThatDeclaresAReleasePhase() {
    Map<String, String> params = ids(Map.of("rev", "refs/tags/" + VERSION), "repositoryId");
    seedReleasedRepository(params.get("repositoryId"));
    gitHostListing.set(params.get("repositoryId"));
    return setup(params);
  }

  /** One commit of one repository with a run in flight: {@code RUNNING} at {@code commitSha}. */
  private Setup aCommitWithARunInFlight() {
    String sha = sha(5);
    Map<String, String> params = ids(Map.of("commitSha", sha), "repositoryId", "runId");
    CiRun running = run(
        params.get("runId"), params.get("repositoryId"), "main", 5, CiRunStatus.RUNNING);
    eventPipeline(running);
    running.finishedAt = null;
    persist(List.of(running), List.of());
    return setup(params);
  }

  /**
   * A release request's publish run in flight ({@code runId}: phase {@code RELEASE}, {@code
   * RUNNING}) and the green QA run that gated the request ({@code gateRunId}), with two reports
   * from its step 1: {@code test-results} ({@code reportId}) and {@code coverage} ({@code
   * coverageReportId}). An earlier QA run of the request failed; it is not the gate. What a publish
   * run's changelog reads ({@code listRunGateReports}).
   */
  private Setup aReleaseRunWhoseGateRunHasReports() {
    Map<String, String> params =
        ids(
            Map.of(),
            "coverageReportId",
            "failedRunId",
            "gateRunId",
            "releaseRequestId",
            "reportId",
            "repositoryId",
            "runId");
    String repo = params.get("repositoryId");
    String request = params.get("releaseRequestId");
    CiRun failed = run(params.get("failedRunId"), repo, "release/" + request, 1, CiRunStatus.FAILED);
    qa(failed, request);
    CiRun gate = run(params.get("gateRunId"), repo, "release/" + request, 2, CiRunStatus.SUCCESS);
    qa(gate, request);
    CiRun release = run(params.get("runId"), repo, VERSION, 3, CiRunStatus.RUNNING);
    release.phase = CiRunPhase.RELEASE;
    release.releaseRequestId = request;
    release.triggerEventName = "SCMRelease";
    release.configPath = ".config/qits/release.yml";
    release.finishedAt = null;
    persist(List.of(failed, gate, release), List.of());
    List<CiReport> seeded =
        List.of(
            report(params.get("reportId"), gate.id, "test-results", testResults(gate), null, null,
                List.of(highlight("info", "128 tests", "tests.total", 128.0, null))),
            report(params.get("coverageReportId"), gate.id, "coverage", coverage(VERSION), null,
                null,
                List.of(highlight("info", "coverage 81.3%", "coverage.total", 81.3, null))));
    QuarkusTransaction.requiringNew().run(() -> seeded.forEach(reports::persist));
    return setup(params);
  }

  // --- seeding helpers ---------------------------------------------------------------------------

  /** Writes the runs and steps, replacing any a failed earlier setup left under the same ids. */
  private void persist(List<CiRun> seeded, List<CiStep> seededSteps) {
    List<String> ids = seeded.stream().map(r -> r.id).toList();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              steps.delete("runId in ?1", ids);
              reports.delete("runId in ?1", ids);
              runs.delete("id in ?1", ids);
              seeded.forEach(runs::persist);
              seededSteps.forEach(steps::persist);
            });
    seededRuns.addAll(ids);
    seeded.stream().map(r -> r.repoId).distinct().forEach(seededRepos::add);
  }

  /** A pipeline a retry can re-fire as stored: a plain {@code ci-event-*.yml} with one step. */
  private static void eventPipeline(CiRun run) {
    run.triggerEventName = "SoftwareRelease";
    run.configPath = ".config/qits/ci-event-build.yml";
    run.triggerEventPayload = "{}";
    run.triggerConfig =
        "event: SoftwareRelease\nsteps:\n  - image: alpine:3\n    script: ./mvnw verify\n";
  }

  /** A finished step, a minute after its run was accepted, under an id derived from the run's. */
  private static CiStep step(
      CiRun run, int index, CiStepStatus status, int exitCode, String output) {
    CiStep step = new CiStep();
    step.id = UUID.nameUUIDFromBytes(
        (run.id + "/" + index).getBytes(StandardCharsets.UTF_8)).toString();
    step.runId = run.id;
    step.stepIndex = index;
    step.image = "alpine:3";
    step.status = status;
    step.exitCode = exitCode;
    step.startedAt = run.startedAt.plusSeconds(index * 10L);
    step.finishedAt = step.startedAt.plusSeconds(9);
    step.output = output;
    return step;
  }

  /** Runner {@link #RUNNER_NAME} as a create declares it: one slot, never registered or seen. */
  private static CiRunner runner(String id) {
    CiRunner runner = new CiRunner();
    runner.id = UUID.fromString(id);
    runner.name = RUNNER_NAME;
    runner.description = "the workstation's own runner";
    runner.slots = 1;
    runner.plane = CiRunnerPlane.EDGE;
    runner.registrationTokenId = "token-contract-registration";
    runner.registrationTokenSubject = REGISTRATION_SUBJECT;
    runner.createdAt = SEEDED_AT;
    return runner;
  }

  /** The runner, registered with its client and seen a minute later. */
  private static void registered(CiRunner runner) {
    runner.clientId = "dev-qits-ci-runner-" + runner.id;
    runner.capabilities = "{\"arch\":\"amd64\",\"docker\":true}";
    runner.registeredAt = SEEDED_AT.plus(Duration.ofMinutes(1));
    runner.lastSeenAt = SEEDED_AT.plus(Duration.ofMinutes(2));
  }

  private void clearRunners() {
    seededRunners = true;
    QuarkusTransaction.requiringNew().run(() -> runnerRows.delete("name", RUNNER_NAME));
  }

  private void persistRunner(CiRunner runner) {
    clearRunners();
    QuarkusTransaction.requiringNew().run(() -> runnerRows.persist(runner));
  }

  /** qits-idp's commissioning surface, scripted, behind {@link IdpCommissioner}. */
  private void standUpIdp() {
    if (idp != null) {
      idp.close();
    }
    idp = new StubIdp();
    QuarkusMock.installMockForType(idp.commissioner(Duration.ofSeconds(2)), IdpCommissioner.class);
  }

  private static CiReleaseAnnouncement announcement(
      Map<String, String> params,
      int index,
      String type,
      String name,
      String publish,
      String decision,
      String unchangedSince,
      Instant announcedAt) {
    CiReleaseAnnouncement row = new CiReleaseAnnouncement();
    row.id = UUID.nameUUIDFromBytes(
        (params.get("runId") + "/" + index).getBytes(StandardCharsets.UTF_8)).toString();
    row.runId = params.get("runId");
    row.repoId = params.get("repositoryId");
    row.repoName = params.get("repositoryName");
    row.version = params.get("version");
    row.packageType = type;
    row.packageName = name;
    row.artifactIndex = index;
    row.createdAt = SEEDED_AT.plus(Duration.ofMinutes(5));
    row.finishedAt = row.createdAt;
    row.publish = publish;
    row.decision = decision;
    row.unchangedSince = unchangedSince;
    row.announcedAt = announcedAt;
    return row;
  }

  /** A 40-hex commit sha, {@code n} in its last digits — the form {@link #run} gives its runs. */
  private static String sha(int n) {
    return String.format("%040x", n);
  }

  /**
   * A bare on the stub git host under {@code repoId}: one commit, made at a fixed instant by a fixed
   * author so its sha is the same every run, holding a {@code release.yml} with a {@code release:}
   * slot, and tagged {@link #VERSION}. Answers the commit's sha.
   */
  private static String seedReleasedRepository(String repoId) {
    try {
      Path seed = Files.createTempDirectory("ci-contract-seed");
      git(seed, "init", "-q", "-b", "main");
      Path slot = seed.resolve(".config/qits/release.yml");
      Files.createDirectories(slot.getParent());
      Files.writeString(
          slot,
          """
          release-request:
            - image: alpine:3
              script: ./mvnw verify
          release:
            - image: alpine:3
              script: ./publish.sh
          """);
      git(seed, "add", ".");
      git(seed, "commit", "-q", "-m", "Declare the release slot");
      git(seed, "tag", VERSION);
      Path origin = StubGitHost.ROOT.resolve("git").resolve(repoId);
      deleteRecursively(origin);
      Files.createDirectories(origin.getParent());
      git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
      String sha = git(seed, "rev-parse", "HEAD").strip();
      deleteRecursively(seed);
      return sha;
    } catch (IOException e) {
      throw new IllegalStateException("could not seed the contract repository", e);
    }
  }

  private static String git(Path cwd, String... args) throws IOException {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of("-c", "user.email=ci@contract.test", "-c", "user.name=contract"));
    command.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    // A fixed instant for author and committer, so the commit — and so its sha — is reproducible.
    pb.environment().put("GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z");
    pb.environment().put("GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
      if (p.waitFor() != 0) {
        throw new IOException("git " + String.join(" ", args) + " failed:\n" + out);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    return out;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
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
    coordinates.put(
        "file", "service/src/test/java/eu/wohlben/qits/contract/BillingServiceTest.java");
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
