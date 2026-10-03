package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>One repository builds one release request at a time, and the newest gating build wins</b>
 * (qits-552): accepting a {@link CiRunPhase#RELEASE_REQUEST} run for a repository supersedes every
 * other request's unfinished run of that phase there, queued or running.
 *
 * <p>Three properties are the point of the file, and each is a way the rule could be quietly wrong:
 *
 * <ul>
 *   <li><b>the older build stops and says why</b> — {@code CANCELLED}, {@link
 *       CiRunService#SUPERSEDED_BY_RELEASE_REQUEST} and the winner's id, whether it was still
 *       queued or already on a runner;
 *   <li><b>a superseded build publishes no verdict</b> — qits-projects' gate reads {@code
 *       BuildFailed} as "not releasable", and a request outranked by a newer one has not failed;
 *   <li><b>nothing else is touched</b> — a publish run, a run that is no part of a release, another
 *       repository's build and the same request's other runs all keep going.
 * </ul>
 *
 * <p>Queued and running states are staged against the suite runner's one slot, {@code
 * CiRunCancelAndRetryTest}'s arrangement: a run parked inside its first step holds everything
 * accepted after it in the queue.
 */
@QuarkusTest
public class CiReleaseBuildSupersedeTest extends CiTestSupport {

  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";

  /** A second QA file, so one request can have two runs the per-branch collapse leaves alone. */
  private static final String SECOND_QA_PATH = ".config/qits/ci-event-release-request-extra.yml";

  private static final String PUBLISH_PATH = ".config/qits/ci-event-release.yml";

  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: ./mvnw verify
      """;

  private static final String PUBLISH_TRIGGER =
      """
      event: SCMRelease
      checkout:
        branch: version
        sha: commitSha
      steps:
        - image: alpine:3
          script: ./publish.sh
      """;

  private static final String MERGED = "b".repeat(40);
  private static final String RELEASED = "c".repeat(40);

  @Inject CiRunService service;
  @Inject FakeRunAnnouncer announcer;

  /** Opened by the test, awaited on the driver inside the parked run's first step. */
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void forgetAnnouncements() {
    announcer.reset();
  }

  @AfterEach
  void releaseTheRunner() throws Exception {
    release.countDown();
    suiteRunner.awaitIdle();
  }

  @Test
  public void aNewerRequestsBuildSupersedesAnOlderRequestsQueuedBuild() throws Exception {
    occupyTheRunner();
    String repo = "supersede-" + UUID.randomUUID();
    String older = accept(qa(repo, "rr-a", QA_PATH));
    String newer = accept(qa(repo, "rr-b", QA_PATH));

    forgetLoadedEntities();
    CiRun loser = service.requireRun(older);
    assertEquals(CiRunStatus.CANCELLED, loser.status);
    assertEquals(CiRunService.SUPERSEDED_BY_RELEASE_REQUEST, loser.cancellationReason);
    assertEquals(newer, loser.supersededByRunId, "the row links to the build that took its place");
    assertNull(loser.startedAt, "a build superseded in the queue never started");
    assertEquals(CiRunStatus.QUEUED, service.requireRun(newer).status);
    assertEquals(
        List.of("QUEUED", "CANCELLED"),
        announcer.statusesOf(older),
        "a mirror is told the superseded run left the listing");

    release.countDown();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertFalse(
        fakeRunner.executed().stream().anyMatch(spec -> spec.runId().equals(older)),
        "no runner reserves a superseded run");
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(newer).status, "the newest build stands");
    assertNoVerdict(older);
  }

  @Test
  public void aNewerRequestsBuildStopsAnOlderRequestsRunningBuild() throws Exception {
    String repo = "supersede-" + UUID.randomUUID();
    CompletableFuture<String> parked = new CompletableFuture<>();
    AtomicBoolean first = new AtomicBoolean(true);
    fakeRunner.during(
        0,
        spec -> {
          if (first.compareAndSet(true, false)) {
            parked.complete(spec.runId());
            await(release);
          }
        });
    String older = accept(qa(repo, "rr-a", QA_PATH));
    assertEquals(older, parked.get(20, TimeUnit.SECONDS));

    String newer = accept(qa(repo, "rr-b", QA_PATH));

    assertTrue(
        fakeRunner.cancelled().contains(older), "the runner holding the older build is asked to stop");
    forgetLoadedEntities();
    CiRun stopping = service.requireRun(older);
    assertEquals(CiRunService.SUPERSEDED_BY_RELEASE_REQUEST, stopping.cancellationReason);
    assertEquals(newer, stopping.supersededByRunId);

    release.countDown();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    CiRun loser = service.requireRun(older);
    assertEquals(CiRunStatus.CANCELLED, loser.status, "a superseded build is not a failed one");
    assertEquals(CiRunService.SUPERSEDED_BY_RELEASE_REQUEST, loser.cancellationReason);
    assertEquals(newer, loser.supersededByRunId);
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(newer).status);
    assertNoVerdict(older);
  }

  @Test
  public void aPublishRunAnOrdinaryRunAnotherRepositoryAndTheSameRequestAreLeftAlone()
      throws Exception {
    occupyTheRunner();
    String repo = "supersede-" + UUID.randomUUID();
    String otherRepo = "supersede-" + UUID.randomUUID();

    String publish = accept(publish(repo, "rr-a"));
    String ordinary = accept(eventRun(repo, "main", "a".repeat(40), "steps: []\n"));
    String elsewhere = accept(qa(otherRepo, "rr-a", QA_PATH));
    String sameRequest = accept(qa(repo, "rr-b", QA_PATH));
    String newest = accept(qa(repo, "rr-b", SECOND_QA_PATH));

    forgetLoadedEntities();
    assertEquals(CiRunPhase.RELEASE, service.requireRun(publish).phase);
    assertEquals(
        CiRunStatus.QUEUED,
        service.requireRun(publish).status,
        "a publish run already won its request's gate and must finish");
    assertNull(service.requireRun(ordinary).phase);
    assertEquals(
        CiRunStatus.QUEUED,
        service.requireRun(ordinary).status,
        "a run that is no part of a release is not a release build");
    assertEquals(
        CiRunStatus.QUEUED,
        service.requireRun(elsewhere).status,
        "another repository's build is folded and built on its own");
    assertEquals(
        CiRunStatus.QUEUED,
        service.requireRun(sameRequest).status,
        "the same request's other runs are the collapse's and the cancellation door's business");
    assertEquals(CiRunStatus.QUEUED, service.requireRun(newest).status);
  }

  @Test
  public void aRetryOfAnOlderRequestsBuildSupersedesTheNewerOnesUnfinishedBuild()
      throws Exception {
    String repo = "supersede-" + UUID.randomUUID();
    fakeRunner.scriptSequence(
        0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "red"));
    String older = accept(qa(repo, "rr-a", QA_PATH));
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.FAILED, service.requireRun(older).status);

    occupyTheRunner();
    String newer = accept(qa(repo, "rr-b", QA_PATH));
    CiRun retry = service.retry(older);

    forgetLoadedEntities();
    CiRun loser = service.requireRun(newer);
    assertEquals(CiRunStatus.CANCELLED, loser.status, "a re-fire is as new as a fresh fold");
    assertEquals(CiRunService.SUPERSEDED_BY_RELEASE_REQUEST, loser.cancellationReason);
    assertEquals(retry.id, loser.supersededByRunId);
    assertEquals(CiRunStatus.QUEUED, service.requireRun(retry.id).status);
    assertEquals("rr-a", service.requireRun(retry.id).releaseRequestId);

    release.countDown();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(retry.id).status);
    assertNoVerdict(newer);
  }

  // --- fixture -----------------------------------------------------------------------------------

  /** Neither green nor red: a superseded build answered no question about its commit. */
  private void assertNoVerdict(String runId) {
    assertTrue(
        announcer.announced().stream().noneMatch(a -> a.runId().equals(runId)),
        "a superseded build is not a passing build");
    assertTrue(
        announcer.failed().stream().noneMatch(f -> f.runId().equals(runId)),
        "and it is not a failing one either — the gate must never read it as a verdict");
  }

  /**
   * Accepts a run in a repository of its own that parks inside its first step until {@link
   * #release}, so everything accepted after this call is genuinely queued.
   */
  private void occupyTheRunner() throws Exception {
    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    fakeRunner.during(
        0,
        spec -> {
          if (inStepZero.complete(spec.runId())) {
            await(release);
          }
        });
    accept(qa("blocker-" + UUID.randomUUID(), "rr-blocker", QA_PATH));
    inStepZero.get(20, TimeUnit.SECONDS);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String accept(CiRunService.EventRun request) {
    return service.onEventTrigger(request);
  }

  /** One matched {@code ReleaseRequestChanged}: the QA phase of {@code requestId}'s fold. */
  private CiRunService.EventRun qa(String repoId, String requestId, String configPath) {
    return new CiRunService.EventRun(
        CiRepoRef.of(repoId, "qits", "qits-supersede-target"),
        "release/" + requestId,
        MERGED,
        triggerParser.parse(configPath, QA_TRIGGER),
        UUID.randomUUID().toString(),
        CiRunService.RELEASE_REQUEST_EVENT_NAME,
        Instant.parse("2026-10-03T09:00:00Z"),
        "{\"releaseRequestId\":\""
            + requestId
            + "\",\"backingBranch\":\"release/"
            + requestId
            + "\",\"mergedSha\":\""
            + MERGED
            + "\"}",
        QA_TRIGGER,
        null);
  }

  /** One matched {@code SCMRelease} naming {@code requestId}: that request's publish phase. */
  private CiRunService.EventRun publish(String repoId, String requestId) {
    return new CiRunService.EventRun(
        CiRepoRef.of(repoId, "qits", "qits-supersede-target"),
        "2026.1003.90000",
        RELEASED,
        triggerParser.parse(PUBLISH_PATH, PUBLISH_TRIGGER),
        UUID.randomUUID().toString(),
        ReleaseJoin.RELEASE_EVENT_NAME,
        Instant.parse("2026-10-03T10:00:00Z"),
        "{\"repository\":\"qits-supersede-target\",\"version\":\"2026.1003.90000\",\"commitSha\":\""
            + RELEASED
            + "\",\"releaseRequestId\":\""
            + requestId
            + "\"}",
        PUBLISH_TRIGGER,
        null);
  }
}
