package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What the claim loop really does with {@link CiRunOrdering}'s answer.</b> {@code
 * CiRunOrderingTest} owns the function; this class owns the queue it drives — accepted rows, a real
 * worker, a real database, and the order the fake step runner was actually asked to run things in.
 *
 * <p>Every case is staged the way {@code CiQueuedRunTest} stages its own: one run parks inside its
 * first step with {@code fakeRunner.during(0, …)}, and since {@code qits.ci.concurrent-builds=1} in
 * this suite that run really is holding the sole worker. Everything accepted afterwards is genuinely
 * {@code QUEUED} at one instant the test controls, and releasing the latch is what lets the loop
 * choose among them.
 *
 * <p><b>Acceptance order is the wrong answer in every case here, deliberately.</b> Each queue is
 * accepted in the order a FIFO would have run it, so an assertion that passes is an assertion about
 * the ordering rather than about the accepts happening to line up.
 */
@QuarkusTest
public class CiRunClaimOrderTest extends CiTestSupport {

  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";
  private static final String SHA = "a".repeat(40);

  @Inject CiRunService service;

  /** Opened by the test, awaited on the worker inside the blocking run's first step. */
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void releaseTheWorker() throws Exception {
    release.countDown();
    service.awaitIdle();
  }

  // --- staging ------------------------------------------------------------------------------------

  /**
   * Accepts a run that parks inside its first step until {@link #release}, and returns once the
   * worker is really inside it. Everything accepted after this call is genuinely queued.
   */
  private void occupyTheWorker() throws Exception {
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
    accept("blocker", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload(null));
    inStepZero.get(20, TimeUnit.SECONDS);
  }

  /** The trigger file every run here declares: one green step, matched on the given event name. */
  private static String triggerFor(String eventName) {
    return "event: "
        + eventName
        + """

        steps:
          - image: alpine:3
            script: echo qa
        """;
  }

  /**
   * A release-request payload. {@code null} for the priority and no downstream names leave the
   * respective KEYS out entirely, which is the wire form {@code CanonicalJson}'s NON_NULL inclusion
   * produces and the one every event published before this campaign has.
   */
  private static String payload(String priority, String... downstream) {
    StringBuilder json = new StringBuilder("{\"releaseRequestId\":\"rr-1\"");
    if (priority != null) {
      json.append(",\"").append(CiRunService.PRIORITY_FIELD).append("\":\"").append(priority)
          .append('"');
    }
    if (downstream.length > 0) {
      json.append(",\"").append(CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD).append("\":[");
      for (int i = 0; i < downstream.length; i++) {
        json.append(i == 0 ? "" : ",").append('"').append(downstream[i]).append('"');
      }
      json.append(']');
    }
    return json.append('}').toString();
  }

  /** Accepts one run for a repository named {@code repoName}, and answers its storage id. */
  private String accept(String repoName, String eventName, String payload) {
    String repoId = "claim-" + UUID.randomUUID();
    String content = triggerFor(eventName);
    service.onEventTrigger(
        new CiRunService.EventRun(
            CiRepoRef.of(repoId, "qits", repoName),
            "main",
            SHA,
            triggerParser.parse(QA_PATH, content),
            UUID.randomUUID().toString(),
            eventName,
            Instant.now(),
            payload,
            content));
    return repoId;
  }

  /** The repositories the worker really ran, in the order it ran them, minus the blocking run. */
  private List<String> claimed() {
    return fakeRunner.executed().stream()
        .map(spec -> spec.repo().name())
        .filter(name -> !"blocker".equals(name))
        .toList();
  }

  private CiRun soleRun(String repoId) {
    forgetLoadedEntities();
    List<CiRun> all = service.runsFor(repoId);
    assertEquals(1, all.size(), "expected exactly one recorded run for " + repoId);
    return all.get(0);
  }

  // --- priority -----------------------------------------------------------------------------------

  @Test
  public void amongQueuedReleaseRequestsTheMoreUrgentOneIsClaimedFirst() throws Exception {
    occupyTheWorker();
    // Accepted least-urgent first, so a FIFO would produce exactly the reverse of the assertion.
    accept("renovate", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload("LOWEST"));
    accept("ordinary", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload(null));
    accept("hotfix", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload("BLOCKING"));

    release.countDown();
    service.awaitIdle();

    // And the run that stated nothing sits in the middle, where MEDIUM is — not behind the renovate,
    // which is what "absent means unknown, not lowest" buys a repository whose publisher predates
    // the field.
    assertEquals(List.of("hotfix", "ordinary", "renovate"), claimed());
  }

  // --- kind ---------------------------------------------------------------------------------------

  @Test
  public void aQueuedReleaseRunJumpsEveryReleaseRequestThatWasAcceptedBeforeIt() throws Exception {
    occupyTheWorker();
    accept("first-request", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload("BLOCKING"));
    accept("second-request", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload("HIGHER"));
    // Accepted last, states no priority at all, and still goes first: kind is the outermost
    // criterion and a release is the artifact everything behind it is about to renovate onto.
    accept("release", ReleaseJoin.RELEASE_EVENT_NAME, "{\"version\":\"2026.907.101500\"}");

    release.countDown();
    service.awaitIdle();

    assertEquals(List.of("release", "first-request", "second-request"), claimed());
  }

  // --- topology -----------------------------------------------------------------------------------

  @Test
  public void aDownstreamRunWaitsForItsQueuedUpstreamHoweverUrgentItIs() throws Exception {
    occupyTheWorker();
    // The downstream is accepted first AND is the urgent one; the upstream is accepted second, is
    // the least urgent thing in the queue, and names the downstream in its closure.
    accept("qits-ci-frontend", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload("BLOCKING"));
    accept(
        "qits-ui-components-jslib",
        CiRunService.RELEASE_REQUEST_EVENT_NAME,
        payload("LOWEST", "qits-ci-frontend"));

    release.countDown();
    service.awaitIdle();

    assertEquals(List.of("qits-ui-components-jslib", "qits-ci-frontend"), claimed());
  }

  // --- what the loop skips ------------------------------------------------------------------------

  @Test
  public void aRunCancelledWhileQueuedIsPassedOverAndTheNextCandidateIsClaimed() throws Exception {
    occupyTheWorker();
    String cancelledRepo = accept("cancelled", CiRunService.RELEASE_REQUEST_EVENT_NAME,
        payload("BLOCKING"));
    accept("survivor", CiRunService.RELEASE_REQUEST_EVENT_NAME, payload("LOWEST"));
    String cancelledId = soleRun(cancelledRepo).id;

    service.cancel(cancelledId);
    release.countDown();
    service.awaitIdle();

    // The cancelled run ranked FIRST, so a loop that stopped at a candidate it could not claim would
    // have left the survivor queued forever. It moves on to the next candidate instead.
    assertEquals(List.of("survivor"), claimed());
    assertEquals(CiRunStatus.CANCELLED, soleRun(cancelledRepo).status);
    assertFalse(
        fakeRunner.executed().stream().anyMatch(spec -> spec.runId().equals(cancelledId)),
        "the claim reads the status back inside its own transaction");
  }

  // --- what the accept records --------------------------------------------------------------------

  @Test
  public void aReleaseRequestRunRecordsBothOrderingInputsOffItsOwnPayload() throws Exception {
    String repoId =
        accept(
            "qits-ui-components-jslib",
            CiRunService.RELEASE_REQUEST_EVENT_NAME,
            payload("HIGHER", "qits-ci-frontend", "qits-ci-service"));
    service.awaitIdle();

    CiRun run = soleRun(repoId);
    assertEquals("HIGHER", run.priority);
    // Verbatim: the canonical array text, unparsed and un-normalised, exactly as trigger_event_payload
    // is stored. The ordering parses it once per pass and nothing else ever looks at it.
    assertEquals("[\"qits-ci-frontend\",\"qits-ci-service\"]", run.downstreamRepos);
  }

  @Test
  public void aReleaseRunRecordsAPriorityAndNoClosureBecauseItsEventCarriesNone() throws Exception {
    // Both gates in one case. SCMRelease carries the same `priority` field and it is read; it
    // carries no closure, and a downstreamTechnicalComponents on it would not be read either —
    // the field is ReleaseRequestChanged's alone, which is what the second assertion pins.
    String repoId =
        accept(
            "qits-ci-service",
            ReleaseJoin.RELEASE_EVENT_NAME,
            "{\"version\":\"2026.907.101500\",\"priority\":\"HIGH\","
                + "\"downstreamTechnicalComponents\":[\"qits-ci-frontend\"]}");
    service.awaitIdle();

    CiRun run = soleRun(repoId);
    assertEquals("HIGH", run.priority);
    assertNull(run.downstreamRepos, "a closure is ReleaseRequestChanged's field and nothing else's");
  }

  @Test
  public void anEventOfAnyOtherKindRecordsNeitherInputHoweverItSpellsThem() throws Exception {
    // The gate that keeps a provenance column from reading any field of any payload. `priority` and
    // `downstreamTechnicalComponents` are qits-projects' words about release work; the same
    // spellings on somebody else's event are somebody else's words.
    String repoId =
        accept(
            "bystander",
            "BuildSuccessful",
            "{\"priority\":\"BLOCKING\",\"downstreamTechnicalComponents\":[\"qits-ci-service\"]}");
    service.awaitIdle();

    CiRun run = soleRun(repoId);
    assertNull(run.priority);
    assertNull(run.downstreamRepos);
  }

  @Test
  public void aPriorityTooLongForTheColumnIsRecordedAsNoneAndTheRunStillRuns() throws Exception {
    // ci_run.release_request_id's rule verbatim, and ci_scm_release.priority's: the RUN is the
    // point. A payload that cannot name a priority within the column's width is not naming one this
    // platform issued, and a truncated value would be a word nobody wrote ordering a queue.
    String repoId =
        accept(
            "verbose",
            CiRunService.RELEASE_REQUEST_EVENT_NAME,
            payload("B".repeat(CiRunService.MAX_PRIORITY_LENGTH + 1)));
    service.awaitIdle();

    CiRun run = soleRun(repoId);
    assertNull(run.priority, "recorded as none, never truncated");
    assertEquals(CiRunStatus.SUCCESS, run.status, "and the run itself was never in question");
  }

  @Test
  public void aClosureThatIsNotAnArrayIsReadAsAbsentRatherThanAsAnError() throws Exception {
    // Absent, null and "the publisher sent something else" are one answer — unknown — because they
    // are indistinguishable in what they let this service conclude, and none of them is worth a
    // WARN per release request forever.
    String repoId =
        accept(
            "confused",
            CiRunService.RELEASE_REQUEST_EVENT_NAME,
            "{\"releaseRequestId\":\"rr-1\",\"downstreamTechnicalComponents\":\"qits-ci-service\"}");
    service.awaitIdle();

    assertNull(soleRun(repoId).downstreamRepos);
  }
}
