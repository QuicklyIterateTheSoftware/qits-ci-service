package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Both edges of the active listing are announced, for every reason a run has for crossing
 * one.</b> {@code RunAnnounceSeamTest} is this file's opposite number and the pair is worth reading
 * together: that one holds the two <em>verdict</em> announcements, which are selective on purpose —
 * one per finished run that says something true about its commit, and nothing at all for a
 * cancelled or superseded one. This one holds {@link RunAnnouncer#onRunStatusChanged}, which is
 * exhaustive for the opposite reason.
 *
 * <p>The reader it exists for mirrors {@code GET /ci/api/runs/active}, a listing whose entire
 * content is {@code ci_run.status}. Such a mirror is owed the arrival and the departure of every
 * run — a run that leaves by being cancelled leaves as completely as one that leaves green — so the
 * assertions below are about <b>sequences</b> rather than about single calls, and the case that
 * matters most is the one the verdict announcements structurally cannot make: a run that never
 * publishes a verdict at all still says when it entered the listing and when it left.
 *
 * <p>{@code occurredAt} is asserted against the row's own column at each transition, for {@code
 * RunAnnounceSeamTest}'s reason applied three times over: a null is a 400 from qits-events, and a
 * fresh {@code Instant.now()} at announce time would be a timestamp about the announcement rather
 * than about the run.
 */
@QuarkusTest
public class RunLifecycleAnnounceSeamTest extends CiTestSupport {

  private static final String CONFIG_ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo ok
      """;

  private static final String TAG_EVENT = "SCMPublishTag";
  private static final String TAG_TRIGGER = ".config/qits/ci-event-release.yml";
  private static final String HEAD = "e".repeat(40);

  @Inject CiRunService service;
  @Inject FakeRunAnnouncer announcer;

  /** Opened by the test, awaited on the worker inside the parked run's first step. */
  private final CountDownLatch release = new CountDownLatch(1);

  private String repoId;
  private String sha;

  @BeforeEach
  void mintRepository() {
    repoId = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8);
    sha = UUID.randomUUID().toString().replace("-", "");
    announcer.reset();
  }

  @AfterEach
  void releaseTheWorker() throws Exception {
    release.countDown();
    service.awaitIdle();
  }

  private CiRun theRun() {
    forgetLoadedEntities();
    return service.runsFor(repoId).get(0);
  }

  /**
   * The announcements for one run, in the order the seam saw them. Filtered by run id because a
   * case that stages a queue has a second run on the same fake.
   */
  private List<FakeRunAnnouncer.AnnouncedStatus> statusesOf(String runId) {
    return announcer.statuses().stream()
        .filter(status -> status.runId().equals(runId))
        .toList();
  }

  private List<String> wordsOf(String runId) {
    return announcer.statusesOf(runId);
  }

  /**
   * The row's own instant and the announced one, to within the microsecond the column rounds to —
   * {@code RunAnnounceSeamTest}'s assertion and its reasoning: the announcement carries the
   * nanosecond value the transaction produced, the row carries what a {@code timestamp(6)} could
   * hold, and a fresh clock read would be tens of microseconds out at the very best.
   */
  private void assertSameInstant(String what, Instant onTheRow, Instant announced) {
    assertNotNull(announced, "an event with no occurredAt is a 400 on the wire (" + what + ")");
    assertTrue(
        Duration.between(onTheRow, announced).abs().toNanos() < 1_000,
        "expected " + what + "'s own " + onTheRow + ", got " + announced);
  }

  // --- the ordinary lifecycle ---

  @Test
  public void aGreenRunAnnouncesQueuedThenRunningThenSuccessWithItsOwnTimestamps() {
    executePipeline(repoId, "main", sha, CONFIG_ONE_STEP);

    CiRun run = theRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(List.of("QUEUED", "RUNNING", "SUCCESS"), wordsOf(run.id));

    List<FakeRunAnnouncer.AnnouncedStatus> announced = statusesOf(run.id);
    // Null previous is what says "this run entered the listing" rather than moved within it, and
    // every later transition names the state it left.
    assertNull(announced.get(0).previousStatus(), "a run's first announcement leaves nothing");
    assertEquals("QUEUED", announced.get(1).previousStatus());
    assertEquals("RUNNING", announced.get(2).previousStatus());

    assertSameInstant("createdAt", run.createdAt, announced.get(0).occurredAt());
    assertSameInstant("startedAt", run.startedAt, announced.get(1).occurredAt());
    assertSameInstant("finishedAt", run.finishedAt, announced.get(2).occurredAt());

    // The coordinates ride every transition, not only the terminal one: a mirror addresses the
    // repository off the announcement it is holding, whichever one that is.
    for (FakeRunAnnouncer.AnnouncedStatus status : announced) {
      assertEquals(repoId, status.repoId());
      assertEquals("main", status.branch());
      assertEquals(sha, status.commitSha());
      assertTrue(status.gating(), "a file that declares nothing is gating");
      assertNotNull(status.triggerEventId(), "an event-triggered run names what caused it");
    }
  }

  @Test
  public void aRedRunEndsFailedOnTheSameSeam() {
    fakeRunner.script(
        0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));

    executePipeline(repoId, "main", sha, CONFIG_ONE_STEP);

    CiRun run = theRun();
    assertEquals(List.of("QUEUED", "RUNNING", "FAILED"), wordsOf(run.id));
    assertSameInstant("finishedAt", run.finishedAt, statusesOf(run.id).get(2).occurredAt());
  }

  // --- the departures no verdict is published for ---

  @Test
  public void aCancelledQueuedRunAnnouncesQueuedAndThenCancelled() throws Exception {
    occupyTheWorker();
    String runId = service.onEventTrigger(eventRun(repoId, "main", sha, CONFIG_ONE_STEP));

    // It really is queued: the worker is parked inside another run's first step, so this row is
    // waiting rather than racing.
    assertEquals(List.of("QUEUED"), wordsOf(runId));

    service.cancel(runId, "not wanted after all");

    assertEquals(List.of("QUEUED", "CANCELLED"), wordsOf(runId));
    // And the run left the listing having published no verdict whatsoever, which is precisely the
    // departure the two build events are contractually unable to report.
    assertEquals(List.of(), announcer.announced());
    assertEquals(List.of(), announcer.failed());

    forgetLoadedEntities();
    CiRun cancelled = service.requireRun(runId);
    assertEquals(CiRunStatus.CANCELLED, cancelled.status);
    assertSameInstant("finishedAt", cancelled.finishedAt, statusesOf(runId).get(1).occurredAt());
    assertEquals("QUEUED", statusesOf(runId).get(1).previousStatus());
  }

  @Test
  public void aQueuedRunSupersededByANewerTagAnnouncesItsDeparture() throws Exception {
    occupyTheWorker();
    String older = service.onEventTrigger(tagRun("2026.810.98"));
    assertEquals(List.of("QUEUED"), wordsOf(older));

    String newer = service.onEventTrigger(tagRun("2026.811.10"));

    // The newer tag entered the listing and the older one left it, both announced after the one
    // transaction that did the two things.
    assertEquals(List.of("QUEUED"), wordsOf(newer));
    assertEquals(List.of("QUEUED", "CANCELLED"), wordsOf(older));
    assertEquals("QUEUED", statusesOf(older).get(1).previousStatus());
  }

  @Test
  public void aRunInsertedAndSupersededInOneTransactionAnnouncesCancelledAndNeverQueued()
      throws Exception {
    // The out-of-order half of a multi-tag push: the newest tag arrives first, so the run accepted
    // second is beaten inside the very transaction that inserted it. It was QUEUED in no state any
    // reader could observe, and announcing it as such would put a run into a mirror's listing that
    // nothing afterwards would ever take out again — no worker claims it, no terminal write happens,
    // its row is already final. The worker is parked so the newest tag's run is still QUEUED when
    // the older one arrives, which is what a supersede has to compare against.
    occupyTheWorker();
    String newer = service.onEventTrigger(tagRun("2026.811.10"));
    assertNotNull(newer);

    String older = service.onEventTrigger(tagRun("2026.810.98"));

    assertEquals(List.of("CANCELLED"), wordsOf(older), "a run that was never queued never says so");
    forgetLoadedEntities();
    CiRun loser = service.requireRun(older);
    assertEquals(CiRunStatus.CANCELLED, loser.status);
    assertEquals(CiRunService.DEDUPED, loser.cancellationReason);
    assertSameInstant("finishedAt", loser.finishedAt, statusesOf(older).get(0).occurredAt());
    assertNull(
        statusesOf(older).get(0).previousStatus(),
        "it is still the run's first announcement, whatever the status");
  }

  // --- staging ---

  /**
   * Parks an unrelated run inside its first step and returns once the worker is really in it —
   * {@code CiTagSupersedeTest}'s arrangement, and for its reason: the worker is single-threaded in
   * this suite, so everything accepted afterwards is genuinely {@code QUEUED} at an instant the test
   * controls rather than one it hopes to catch.
   */
  private void occupyTheWorker() throws Exception {
    String blocker = "blocker-" + UUID.randomUUID().toString().substring(0, 8);
    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    fakeRunner.during(
        0,
        spec -> {
          inStepZero.complete(spec.runId());
          try {
            release.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    service.onEventTrigger(eventRun(blocker, "main", "b".repeat(40), CONFIG_ONE_STEP));
    inStepZero.get(20, TimeUnit.SECONDS);
  }

  /** One tag announcement for this repository's release trigger, ready for the accept path. */
  private CiRunService.EventRun tagRun(String tagName) {
    String content = "event: " + TAG_EVENT + "\n" + CONFIG_ONE_STEP;
    return new CiRunService.EventRun(
        CiRepoRef.of(repoId),
        "main",
        HEAD,
        triggerParser.parse(TAG_TRIGGER, content),
        UUID.randomUUID().toString(),
        TAG_EVENT,
        Instant.now(),
        "{\"tagName\":\"" + tagName + "\"}",
        content);
  }
}
