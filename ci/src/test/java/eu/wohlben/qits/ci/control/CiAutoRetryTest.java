package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A run the infrastructure failed is re-fired at once and announces no red verdict (qits-440).
 *
 * <p>The property the release gate depends on is the absence of a {@code BuildFailed}: qits-projects
 * rejects a release request on the first one it hears, and it hears verdicts through nothing else.
 * So every case below asserts on {@link FakeRunAnnouncer#failed()} as well as on the rows — a retry
 * that was queued AFTER a {@code BuildFailed} went out would pass a row-only test and still reject
 * the request.
 *
 * <p>The cap is {@link CiRunService#AUTO_RETRY_MAX}; each case sets it explicitly, since a suite
 * staging infra failures for another purpose may have turned it off, and puts the old value back.
 */
@QuarkusTest
public class CiAutoRetryTest extends CiTestSupport {

  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";

  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: ./mvnw verify
        - image: alpine:3
          script: ./publish-userflows.sh
      """;

  private static final String MERGED = "c".repeat(40);

  private static final StepResult LOST =
      new StepResult(-1, false, StepOutcome.CONNECTION_LOST, "socket closed");

  @Inject CiRunService service;
  @Inject FakeRunAnnouncer announcer;
  @Inject CiRunnerRepository runnerRows;
  @Inject CiRunners runners;
  @Inject CiRunMapper mapper;

  private final CountDownLatch release = new CountDownLatch(1);

  private int suiteMax;

  @BeforeEach
  void shippedCap() {
    announcer.reset();
    suiteMax = service.autoRetryMax();
    service.autoRetryMax(2);
    otherRunnersGone();
  }

  @AfterEach
  void restore() throws Exception {
    release.countDown();
    suiteRunner.awaitIdle();
    service.autoRetryMax(suiteMax);
    otherRunnersGone();
  }

  /** Every runner row but the suite's own, which is what runs the unstaged runs here. */
  private void otherRunnersGone() {
    QuarkusTransaction.requiringNew()
        .run(() -> runnerRows.delete("id <> ?1", suiteRunner.id()));
  }

  @Test
  public void aConnectionLostFailureIsRetriedOnceAndTheGateHearsNoRed() throws Exception {
    // The retry re-enters the ordinary queue and the runner that lost the connection takes it again
    // (qits-443: a retry is not kept off the runner that failed it).
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.scriptSequence(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "exactly one retry");
    CiRun failed = all.get(0);
    CiRun retry = all.get(1);
    assertEquals(original, failed.id);
    assertEquals(CiRunStatus.FAILED, failed.status, "the failed run's own record is kept");
    assertNull(failed.retryReason);

    assertEquals(original, retry.retryOfRunId);
    assertEquals(CiRunStatus.SUCCESS, retry.status);
    assertEquals(MERGED, retry.commitSha, "the same commit");
    assertEquals("rr-a", retry.releaseRequestId, "the same release request");
    assertEquals(QA_PATH, retry.configPath, "the same pipeline");
    // A lost connection on a runner's run is the runner's disconnect, and the reason names it.
    assertEquals(
        "infra failure (runner " + SuiteRunner.NAME + " disconnected) on run " + original
            + " — automatic retry 1 of 2",
        retry.retryReason);

    // THE GATE: no red verdict for the infra failure, and the retry's green names what it supersedes.
    assertTrue(
        announcer.failed().stream().noneMatch(f -> f.runId().equals(original)),
        "an auto-retried infra failure must not announce BuildFailed");
    assertTrue(announcer.failed().isEmpty());
    FakeRunAnnouncer.Announced green =
        announcer.announced().stream()
            .filter(a -> a.runId().equals(retry.id))
            .findFirst()
            .orElseThrow();
    assertEquals(original, green.retryOfRunId());
    assertEquals("rr-a", green.releaseRequestId());
    // The row mirror still hears the failed row settle — it is about rows, not verdicts.
    List<String> statuses = announcer.statusesOf(original);
    assertTrue(statuses.contains("FAILED"), statuses.toString());

    CiStep step = service.stepsFor(original).get(0);
    assertTrue(
        step.output.endsWith(
            "[infra failure (runner " + SuiteRunner.NAME + " disconnected) — retried"
                + " automatically as run " + retry.id + "]"),
        step.output);
  }

  @Test
  public void aStepExitCodeFailureIsNotRetriedOom137Included() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.script(0, new StepResult(137, false, StepOutcome.OK, "Killed"));

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, service.runsFor(repo).size(), "a build's own exit code is a verdict");
    assertEquals(CiRunStatus.FAILED, service.requireRun(original).status);
    assertEquals(
        List.of(original), announcer.failed().stream().map(f -> f.runId()).toList(),
        "and it is announced as one");
  }

  @Test
  public void atMostTwoAutomaticRetriesThenTheFailureSettles() throws Exception {
    // Three infra failures in a row quarantine the runner that suffered them, so the person's retry
    // after them needs a runner that is still taking work.
    suiteRunner.addSpare("spare");
    String repo = "auto-" + UUID.randomUUID();
    fakeRunner.script(0, LOST);

    String original = accept(repo, "rr-a");
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(3, all.size(), "the original and two automatic retries");
    assertTrue(all.stream().allMatch(r -> r.status == CiRunStatus.FAILED));
    assertEquals(original, all.get(0).id);
    assertEquals(original, all.get(1).retryOfRunId);
    assertEquals(
        all.get(1).id, all.get(2).retryOfRunId, "a retry of a retry keeps the chain count");
    assertTrue(all.get(1).retryReason.endsWith("automatic retry 1 of 2"), all.get(1).retryReason);
    assertTrue(all.get(2).retryReason.endsWith("automatic retry 2 of 2"), all.get(2).retryReason);

    // Only the last one settles as a verdict, and it carries the lineage the gate walks.
    assertEquals(1, announcer.failed().size(), announcer.failed().toString());
    FakeRunAnnouncer.AnnouncedFailure red = announcer.failed().get(0);
    assertEquals(all.get(2).id, red.runId());
    assertEquals(all.get(1).id, red.retryOfRunId());
    String settled = service.stepsFor(all.get(2).id).get(0).output;
    assertTrue(
        settled.endsWith("[the connection to the step container was lost]"),
        "the settled run names no retry: " + settled);

    // A person re-asking is a new question with its own budget, and is not marked automatic.
    fakeRunner.reset();
    CiRun manual = service.retry(all.get(2).id);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertNull(service.requireRun(manual.id).retryReason);
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(manual.id).status);
  }

  @Test
  public void aCancelledRunIsNotRetriedWhateverItsStepSaid() throws Exception {
    String repo = "auto-" + UUID.randomUUID();
    CompletableFuture<String> reached = new CompletableFuture<>();
    CountDownLatch cancelled = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          reached.complete(spec.runId());
          try {
            cancelled.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    fakeRunner.script(0, LOST);

    accept(repo, "rr-a");
    String runId = reached.get(10, TimeUnit.SECONDS);
    service.cancel(runId);
    cancelled.countDown();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, service.runsFor(repo).size());
    assertEquals(CiRunStatus.CANCELLED, service.requireRun(runId).status);
    assertEquals(CiRunService.USER_CANCELLED, service.requireRun(runId).cancellationReason);
    assertTrue(announcer.failed().isEmpty());
  }

  @Test
  public void aRunnerDisconnectIsRetriedIntoTheOrdinaryQueueAndStillCountsAgainstTheRunner()
      throws Exception {
    occupyTheWorker();
    CiRunner runner = runner("qits-ci");
    fakeRunner.answer(spec -> LOST);
    String repo = "auto-" + UUID.randomUUID();

    String original = accept(repo, "rr-a");
    CiRunService.Reservation reservation = service.reserveFor(runner).orElseThrow();
    assertEquals(original, reservation.run().id);
    service.executeReserved(reservation);
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size());
    CiRun retry = all.get(1);
    assertEquals(runner.id, all.get(0).runnerId);
    assertEquals(CiRunStatus.QUEUED, retry.status, "the suite's runner is busy, so it waits");
    assertNull(retry.runnerId, "not pinned to the runner that failed it");
    assertNull(retry.targetRunnerId);
    assertEquals(
        "infra failure (runner qits-ci disconnected) on run " + original
            + " — automatic retry 1 of 2",
        retry.retryReason);
    assertTrue(
        service.stepsFor(original).get(0).output.endsWith(
            "[infra failure (runner qits-ci disconnected) — retried automatically as run "
                + retry.id + "]"),
        service.stepsFor(original).get(0).output);
    assertTrue(announcer.failed().isEmpty());

    // The quarantine's count is untouched by the retry.
    CiRunner counted = QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(runner.id));
    assertEquals(1, counted.infraFailures);

    // The ONLY runner is the one that failed it, and it is handed its own retry (qits-443): a retry
    // is not kept off the runner that failed the run, or with one runner nothing could ever take it.
    fakeRunner.answer(null);
    CiRunService.Reservation again = service.reserveFor(runner).orElseThrow();
    assertEquals(retry.id, again.run().id, "the same runner reserves the retry");
    service.executeReserved(again);
    forgetLoadedEntities();
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(retry.id).status);
    assertEquals(runner.id, service.requireRun(retry.id).runnerId);
  }

  @Test
  public void anIdMappingFailureIsRetriedIntoTheOrdinaryQueueAndTheGateHearsNoRed()
      throws Exception {
    occupyTheWorker();
    CiRunner narrow = runner("qits-ci");
    fakeRunner.answer(
        spec -> new StepResult(1, false, StepOutcome.OK, CiRunnerHealthTest.LCHOWN));
    String repo = "auto-" + UUID.randomUUID();

    String original = accept(repo, "rr-a");
    CiRunService.Reservation reservation = service.reserveFor(narrow).orElseThrow();
    assertEquals(original, reservation.run().id);
    service.executeReserved(reservation);
    forgetLoadedEntities();

    List<CiRun> all = chain(repo);
    assertEquals(2, all.size(), "a step that ran and exited 1 is still retried when the host failed it");
    CiRun failed = all.get(0);
    CiRun retry = all.get(1);
    assertEquals(CiRunStatus.FAILED, failed.status);
    assertEquals(CiRunStatus.QUEUED, retry.status);
    assertEquals(original, retry.retryOfRunId);
    assertEquals(
        "infra failure (the builder could not map a file owner (\"failed to Lchown\") on runner"
            + " qits-ci — its uid/gid range is too narrow) on run " + original
            + " — automatic retry 1 of 2",
        retry.retryReason);
    assertTrue(announcer.failed().isEmpty(), "no BuildFailed while an automatic retry follows");
    assertEquals(
        1,
        QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(narrow.id)).infraFailures,
        "and it counts toward the runner's quarantine");
    assertTrue(
        service.stepsFor(original).get(0).output.endsWith(
            " — retried automatically as run " + retry.id + "]"),
        service.stepsFor(original).get(0).output);

    // The retry is ordinary queued work: the runner that failed it may take it again.
    fakeRunner.answer(null);
    CiRunService.Reservation again = service.reserveFor(narrow).orElseThrow();
    assertEquals(retry.id, again.run().id);
    service.executeReserved(again);
    forgetLoadedEntities();
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(retry.id).status);
    assertEquals(narrow.id, service.requireRun(retry.id).runnerId);
  }

  @Test
  public void aPersonsRetryOfAnInfraFailureIsHandedToTheRunnerThatFailedIt() throws Exception {
    service.autoRetryMax(0);
    occupyTheWorker();
    CiRunner runner = runner("qits-ci");

    fakeRunner.answer(spec -> LOST);
    String lost = accept("auto-" + UUID.randomUUID(), "rr-a");
    service.executeReserved(service.reserveFor(runner).orElseThrow());
    CiRun manual = service.retry(lost);
    forgetLoadedEntities();
    assertNull(service.requireRun(manual.id).retryReason, "a person's, not automatic");
    assertEquals(
        manual.id,
        service.reserveFor(runner).orElseThrow().run().id,
        "the only runner takes the retry of the run it lost");

    service.cancel(manual.id);
  }

  @Test
  public void theRunDtoSaysWhetherARunWasRetriedAutomaticallyAndWhy() {
    CiRun ordinary = new CiRun();
    ordinary.id = "plain";
    CiRunDto plain = mapper.toDto(ordinary);
    assertFalse(plain.autoRetry());
    assertNull(plain.retryReason());

    CiRun automatic = new CiRun();
    automatic.id = "auto";
    automatic.retryOfRunId = "plain";
    automatic.retryReason = "infra failure (runner qits-ci disconnected) on run plain";
    CiRunDto dto = mapper.toDto(automatic);
    assertTrue(dto.autoRetry());
    assertEquals(automatic.retryReason, dto.retryReason());
    assertEquals("plain", dto.retryOfRunId());
    // And the withers carry both across.
    assertTrue(dto.withRunnerName("x").autoRetry());
    assertNotNull(dto.withSteps(List.of(), null).retryReason());
  }

  // --- fixture -----------------------------------------------------------------------------------

  /** The repository's runs, oldest first. */
  private List<CiRun> chain(String repo) {
    return service.runsFor(repo).stream()
        .sorted(Comparator.comparing((CiRun r) -> r.createdAt))
        .toList();
  }

  private void occupyTheWorker() throws Exception {
    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    fakeRunner.during(
        0,
        spec -> {
          if (inStepZero.complete(spec.runId())) {
            try {
              release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    accept("blocker-" + UUID.randomUUID(), "rr-blocker");
    inStepZero.get(20, TimeUnit.SECONDS);
  }

  private CiRunner runner(String name) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRunner runner = new CiRunner();
              runner.id = UUID.randomUUID();
              runner.name = name;
              runner.slots = 2;
              runner.plane = CiRunnerPlane.INTERNAL;
              runner.clientId = "client-" + name;
              runner.capabilities = "{\"docker\":true,\"arch\":\"amd64\"}";
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
              return runner;
            });
  }

  private String accept(String repoId, String requestId) {
    return service.onEventTrigger(
        new CiRunService.EventRun(
            CiRepoRef.of(repoId, "qits", "qits-ci-service"),
            "release/" + requestId,
            MERGED,
            new CiEventTrigger(
                QA_PATH,
                CiRunService.RELEASE_REQUEST_EVENT_NAME,
                null,
                new CiPipeline(
                    List.of(
                        new CiPipeline.CiStepDecl(
                            "alpine:3", "./mvnw verify", null, false, false, ""),
                        new CiPipeline.CiStepDecl(
                            "alpine:3", "./publish-userflows.sh", null, false, false, ""))),
                List.of(),
                new CiEventTrigger.Checkout("backingBranch", "mergedSha", false)),
            UUID.randomUUID().toString(),
            CiRunService.RELEASE_REQUEST_EVENT_NAME,
            Instant.parse("2026-09-29T09:07:06Z"),
            "{\"releaseRequestId\":\""
                + requestId
                + "\",\"backingBranch\":\"release/"
                + requestId
                + "\",\"mergedSha\":\""
                + MERGED
                + "\"}",
            QA_TRIGGER,
            null));
  }
}
