package eu.wohlben.qits.ci.contracts;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  /** Run ids the states wrote, removed again by {@link #cleanUp()}. */
  private final List<String> seededRuns = new ArrayList<>();

  public ProviderStates() {
    states.put(
        A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST, this::aRepositoryWithTheRunsOfARequest);
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

  /** Removes the runs the states wrote. Callers run this after each recording or verification. */
  public void cleanUp() {
    if (seededRuns.isEmpty()) {
      return;
    }
    List<String> ids = List.copyOf(seededRuns);
    seededRuns.clear();
    QuarkusTransaction.requiringNew().run(() -> runs.delete("id in ?1", ids));
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
