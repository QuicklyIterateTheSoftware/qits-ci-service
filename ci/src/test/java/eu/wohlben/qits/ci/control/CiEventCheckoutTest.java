package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code checkout:} capability, end to end: a trigger that follows the event's own commit
 * records the run — and announces it — at the payload's branch and sha, refuses what the untrusted
 * payload cannot prove, and collapses a queued burst per branch the way the push path always has.
 */
@QuarkusTest
public class CiEventCheckoutTest extends CiTestSupport {

  private static final String CHECKOUT_PATH = ".config/qits/ci-event-build.yml";
  private static final String PLAIN_PATH = ".config/qits/ci-event-plain.yml";

  private static final String CHECKOUT_TRIGGER =
      """
      event: SCMPublishCommit
      checkout:
        branch: branch
        sha: sha
      steps:
        - image: alpine:3
          script: "true"
      """;

  private static final String PLAIN_TRIGGER =
      """
      event: SCMPublishCommit
      steps:
        - image: alpine:3
          script: "true"
      """;

  /**
   * A release recipe's checkout, verbatim in shape: the anchor is the tag's own NAME and the commit
   * it points at, and the pair is declared {@code optional} because the event has not always carried
   * the second one. This is what {@code .config/qits/ci-event-release.yml} spells.
   */
  private static final String RELEASE_TRIGGER =
      """
      event: SCMRelease
      checkout:
        branch: version
        sha: commitSha
        optional: true
      steps:
        - image: alpine:3
          script: "true"
      """;

  /**
   * <b>A file about somebody ELSE's release</b> — the downstream bump, and the one shape {@code
   * optional: true} still serves. {@code SoftwareRelease} is qits-ci's own announcement of a
   * published artifact and is not one of the two release events, so a run it triggers is not a
   * release run of this repository: the coordinate on the payload belongs to the repository that
   * published, and what this file builds is its own {@code main}.
   */
  private static final String UPSTREAM_RELEASE_TRIGGER =
      """
      event: SoftwareRelease
      checkout:
        branch: version
        sha: commitSha
        optional: true
      steps:
        - image: alpine:3
          script: "true"
      """;

  /**
   * A bespoke release pipeline that declares NO checkout — the escape hatch's shape, and what every
   * hand-written {@code ci-event-release.yml} on the estate looked like before {@code commitSha}
   * existed. Its run is recorded at the revision the event names all the same.
   */
  private static final String NO_CHECKOUT_RELEASE_TRIGGER =
      """
      event: SCMRelease
      steps:
        - image: alpine:3
          script: "true"
      """;

  /** The same for the QA half of the cycle. */
  private static final String NO_CHECKOUT_QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      steps:
        - image: alpine:3
          script: "true"
      """;

  private static final String HEAD = "a".repeat(40);
  private static final String PUSHED = "b".repeat(40);
  private static final String RELEASED = "c0ffee1".repeat(5) + "abcde";

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer announcer;

  private final CountDownLatch release = new CountDownLatch(1);

  private String repoId;

  @BeforeEach
  void resetTriggerState() {
    repoId = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    announcer.reset();
  }

  @AfterEach
  void releaseTheWorker() {
    release.countDown();
  }

  @Test
  public void aCheckoutTriggerRecordsAndAnnouncesTheEventsOwnCommit() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER));
    String eventId = UUID.randomUUID().toString();
    deliver(arrival(eventId, push("feature/x", PUSHED)));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    CiRun run = recorded.get(0);
    // The row IS the truth downstream: the clone env, the restart snapshot and the announcement
    // all read these two columns, so payload-resolved values here fix the whole chain.
    assertEquals("feature/x", run.branch);
    assertEquals(PUSHED, run.commitSha);
    assertEquals(eventId, run.triggerEventId);
    assertEquals(CiRunStatus.SUCCESS, run.status);

    assertEquals(1, announcer.announced().size());
    assertEquals("feature/x", announcer.announced().get(0).branch());
    assertEquals(PUSHED, announcer.announced().get(0).commitSha());
  }

  @Test
  public void aPayloadMissingTheCheckoutFieldCostsThatFileItsRunAndNothingElse() throws Exception {
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER),
        new EventTriggerFile(PLAIN_PATH, PLAIN_TRIGGER));
    // No sha field at all — nothing truthful to record a row against.
    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(arrival(UUID.randomUUID().toString(), "{\"branch\":\"feature/x\"}"));
    runService.awaitIdle();
    forgetLoadedEntities();

    // Containment is per FILE: the sibling trigger in the same repository still fired, and the
    // repository counts as read rather than skipped.
    assertEquals(1, evaluation.runIds().size());
    assertEquals(List.of(), evaluation.repositoriesSkipped());
    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals(PLAIN_PATH, recorded.get(0).configPath);
    assertEquals("main", recorded.get(0).branch, "the plain sibling builds main's head");
  }

  @Test
  public void garbagePayloadValuesAreRefusedBeforeARowOrAUrlExists() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER));
    // The payload is attacker-shaped: both values reach a clone URL and an argv, so the
    // CiIdentifiers gate fires inside the per-file loop — refused, no run, no throw, and the
    // repository is not marked skipped.
    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(
            arrival(
                UUID.randomUUID().toString(),
                "{\"branch\":\"-oProxyCommand=x\",\"sha\":\"$(x)\"}"));
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds());
    assertEquals(List.of(), evaluation.repositoriesSkipped());
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  @Test
  public void suppressCiIsAWhenConditionAndMatchesTheBooleanLiteral() throws Exception {
    // The engine gains no flag knowledge: suppression is declared in when:, and the matcher
    // compares the JSON literal — `exact: "false"` matches the boolean false. This is the shape
    // both qits-githost trigger files carry.
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            CHECKOUT_PATH,
            """
            event: SCMPublishCommit
            when:
              - suppressCi: { exact: "false" }
            checkout:
              branch: branch
              sha: sha
            steps:
              - image: alpine:3
                script: "true"
            """));
    deliver(
        arrival(
            UUID.randomUUID().toString(),
            "{\"branch\":\"main\",\"sha\":\"" + PUSHED + "\",\"suppressCi\":true}"));
    assertEquals(List.of(), runService.runsFor(repoId), "a -o qits.no-ci push stays dark");

    deliver(
        arrival(
            UUID.randomUUID().toString(),
            "{\"branch\":\"main\",\"sha\":\"" + PUSHED + "\",\"suppressCi\":false}"));
    assertEquals(1, runService.runsFor(repoId).size());
  }

  @Test
  public void aPlatformTriggerDeclaringCheckoutRecordsNoRun() throws Exception {
    String platformId = "wrapper-" + UUID.randomUUID().toString().substring(0, 8);
    String targetId = "target-" + UUID.randomUUID().toString().substring(0, 8);
    try {
      fakeCandidates.setRefs(
          CiRepoRef.of(platformId, "qits", "qits-qits"), CiRepoRef.of(targetId, "qits", "target"));
      fakeConfig.putTriggers(targetId, "main", HEAD);
      fakeConfig.putTriggers(
          platformId,
          "main",
          CiTriggerScope.PLATFORM,
          HEAD,
          new EventTriggerFile(".config/qits/ci-platform-event-build.yml", CHECKOUT_TRIGGER));
      engine.platformPipelinesRepository("qits-qits");

      deliver(
          arrival(
              UUID.randomUUID().toString(),
              "{\"repository\":\"target\",\"branch\":\"main\",\"sha\":\"" + PUSHED + "\"}"));

      assertEquals(List.of(), runService.runsFor(targetId));
      assertEquals(List.of(), runService.runsFor(platformId));
    } finally {
      engine.platformPipelinesRepository("");
    }
  }

  @Test
  public void aBurstToOneBranchCollapsesToTheNewestTipWhileOtherBranchesStand() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER));
    occupyTheWorker();

    String first = "c".repeat(40);
    String second = "d".repeat(40);
    String elsewhere = "e".repeat(40);
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/x", first)));
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/x", second)));
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/y", elsewhere)));
    release.countDown();
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(3, recorded.size());
    CiRun older = bySha(recorded, first);
    CiRun newest = bySha(recorded, second);
    CiRun otherBranch = bySha(recorded, elsewhere);
    assertEquals(
        CiRunStatus.CANCELLED,
        older.status,
        "the burst's older tip loses its queue slot — cancelled, because losing a slot is not a"
            + " verdict about the commit and a red row here is a false alarm");
    assertEquals(CiRunService.DEDUPED, older.cancellationReason, "and the reason says which");
    assertEquals(newest.id, older.supersededByRunId);
    assertNotEquals(CiRunStatus.FAILED, newest.status);
    assertNotEquals(CiRunStatus.CANCELLED, newest.status);
    assertNotEquals(CiRunStatus.FAILED, otherBranch.status, "another branch spends no slot here");
    // And the status change is a read surface only: a superseded row publishes no verdict, before
    // or after it, so qits-projects' build gate sees exactly what it always saw.
    assertTrue(
        announcer.failed().stream().noneMatch(failure -> failure.runId().equals(older.id)),
        "a superseded run announces no BuildFailed — it is bookkeeping about the queue");
    assertTrue(
        announcer.announced().stream().noneMatch(succeeded -> succeeded.runId().equals(older.id)),
        "and no BuildSuccessful either");
  }

  @Test
  public void nonCheckoutEventRunsAreNeverBranchCollapsed() throws Exception {
    // Without checkout: every event run's branch is "main" by convention, so a branch-keyed
    // collapse would dedupe runs of DISTINCT events — which the (trigger_event_id, …) contract
    // forbids. The gate is the trigger's checkout, and this is the case that pins it.
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(PLAIN_PATH, PLAIN_TRIGGER));
    occupyTheWorker();

    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/x", "c".repeat(40))));
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/y", "d".repeat(40))));
    release.countDown();
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(2, recorded.size());
    assertTrue(
        recorded.stream().noneMatch(run -> run.status == CiRunStatus.FAILED),
        "two distinct events are two runs, whatever branch convention they share: " + recorded);
  }

  // --- the release anchor: a checkout whose ref is a TAG ---------------------------------------

  /**
   * <b>A release run is anchored at the tag, and the engine learns nothing about tags to do it.</b>
   *
   * <p>{@code checkout.branch} is a path to a REF NAME — the column is called branch because that is
   * what a run row has always called its ref — so pointing it at the release event's {@code version}
   * records the run at the tag and hands the daemon {@code clone --branch <tag>}, which git resolves
   * exactly as it resolves a head. Everything the row, the clone env and the announcement carry
   * follows from these two columns, which is why this is the assertion that matters: before it, a
   * release run said {@code main@<head>} and the released tree was named only inside a step script.
   */
  @Test
  public void aReleaseTriggerRecordsTheRunAtTheTagAndTheCommitItPointsAt() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, RELEASE_TRIGGER));
    String eventId = UUID.randomUUID().toString();
    deliver(releaseEvent(eventId, "2026.905.60215", RELEASED));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    CiRun run = recorded.get(0);
    assertEquals("2026.905.60215", run.branch, "a calver is a ref name and passes the ref gate");
    assertEquals(RELEASED, run.commitSha);
    assertEquals(CiRunStatus.SUCCESS, run.status);

    assertEquals(1, announcer.announced().size());
    assertEquals("2026.905.60215", announcer.announced().get(0).branch());
    assertEquals(RELEASED, announcer.announced().get(0).commitSha());
  }

  /**
   * <b>A release event that does not carry the pair costs the file its run, {@code optional:} or
   * not.</b>
   *
   * <p>This case used to read the other way round, and the sentence it made was: {@code commitSha}
   * is additive on {@code SCMRelease}, so a release published before it existed carries none, and
   * the flag kept such a pipeline's run by recording it at {@code main}'s head with its checkout
   * stripped. The half of that which was true is still true — the field is additive — and the
   * conclusion was the defect: what the arm produced is a RELEASE run of this repository dispatched
   * at a revision the release is not about, whose verdict then travels to qits-projects' gate as if
   * it were about the released one. A pipeline that gates a revision is read from, and run at, that
   * revision (owner ruling), so the honest answer to a half-missing payload is the one every other
   * unresolvable checkout gets: no run.
   *
   * <p>The trigger here is a repository's own release pipeline spelled by hand — the shape {@code
   * .config/qits/release.yml} composes — which is why it is the arm that had to go. The other arm,
   * a file reacting to somebody ELSE's release, keeps the fallback and is
   * {@link #anUpstreamReleaseWithNoCoordinateStillRunsAtThisRepositorysMainsHead}.
   */
  @Test
  public void aReleaseCarryingNoCommitShaRecordsNoRunRatherThanBuildingMain() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, RELEASE_TRIGGER));
    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(releaseEvent(UUID.randomUUID().toString(), "2026.905.60215", null));
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds());
    assertEquals(
        List.of(),
        runService.runsFor(repoId),
        "the optional fallback dispatched this release run at main's head — a release run about a"
            + " commit nobody released");
    // Settled, not owed: the payload cannot grow the field later, so a sweep would ask a question
    // whose answer is already final and the watermark would sit behind it forever.
    assertEquals(List.of(), evaluation.repositoriesUnreadable());
  }

  /**
   * <b>A fallback run IS a checkout-less run, and the per-ref collapse is where that has teeth.</b>
   *
   * <p>Two distinct events that both fall back are both recorded at {@code main}, so a collapse
   * keyed on the ref would dedupe one of them away. The engine hands such a run on with its
   * checkout stripped rather than merely logging the fallback, so this holds for every reader keyed
   * on {@code checkout}, not only for the one we remembered. It is {@link
   * #nonCheckoutEventRunsAreNeverBranchCollapsed}'s claim, asserted for the trigger that DECLARES a
   * checkout and did not get to use it.
   *
   * <p><b>It is asserted on the DOWNSTREAM event now, and that is where the claim still lives.</b>
   * It used to stage two {@code SCMRelease}es of this repository falling back, which is the arm
   * that is gone — such an event records no run at all, so there would be nothing to collapse and
   * the property would be pinned by a test that could no longer fail. The surviving fallback is a
   * file reacting to somebody else's release, and two of those are still two runs of this
   * repository's own {@code main}.
   */
  @Test
  public void twoUpstreamReleasesFallingBackAreTwoRunsRatherThanACollapsedOne() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, UPSTREAM_RELEASE_TRIGGER));
    occupyTheWorker();

    engine.evaluate(upstreamRelease(UUID.randomUUID().toString(), "2026.905.60215"));
    engine.evaluate(upstreamRelease(UUID.randomUUID().toString(), "2026.905.70000"));
    release.countDown();
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(2, recorded.size());
    assertTrue(
        recorded.stream().noneMatch(run -> run.status == CiRunStatus.FAILED),
        "a collapsed downstream run is an upstream release this repository never reacted to: "
            + recorded);
  }

  /**
   * <b>The surviving {@code optional:} arm, and the whole of what the flag means now.</b>
   *
   * <p>Repository B declares a file on repository A's release — a downstream bump, the shape the
   * platform's {@code SoftwareRelease} consumers have — and hopes the payload carries a coordinate
   * it could build. It does not, and it never could have: the revision on that event is A's, and
   * this run is B's. So {@code main}'s head is not a fallback from a revision that exists, it is
   * the only revision the event names for B, which is exactly what every non-release event's
   * default checkout already answers.
   *
   * <p>That is the distinction the refusal above is drawn on, and it is drawn by the EVENT rather
   * than by the file: {@code SoftwareRelease} is not one of the two release events, so nothing
   * here is a release run of this repository and nothing is gating a revision it was not composed
   * from. Removing the arm altogether would cost this build with nothing gained.
   */
  @Test
  public void anUpstreamReleaseWithNoCoordinateStillRunsAtThisRepositorysMainsHead()
      throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, UPSTREAM_RELEASE_TRIGGER));

    deliver(upstreamRelease(UUID.randomUUID().toString(), "2026.905.60215"));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "a downstream bump must not cost its run");
    assertEquals("main", recorded.get(0).branch);
    assertEquals(HEAD, recorded.get(0).commitSha);
    assertEquals(CiRunStatus.SUCCESS, recorded.get(0).status);
  }

  /**
   * {@code optional:} is about ABSENCE and softens no guard. A payload that carries a hostile value
   * where the sha belongs is refused exactly as it is without the flag — the fallback would
   * otherwise be a way to make a garbage payload build something rather than nothing.
   */
  @Test
  public void anOptionalCheckoutStillRefusesAValueThatIsThereAndHostile() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, RELEASE_TRIGGER));
    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(releaseEvent(UUID.randomUUID().toString(), "2026.905.60215", "$(x)"));
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds());
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  // --- the DEFAULT checkout on a release event: the event's revision, never main -----------------

  /**
   * <b>A release pipeline that declares no {@code checkout:} is recorded at the revision the event
   * is about</b>, exactly as the composed one that declares the canonical pair is.
   *
   * <p>It used to be recorded at {@code main}@{@code head}: a run about a commit the release was not
   * about, on a branch whose head moves under it, whose clone need not even contain the released
   * commit. The pipeline that gates a revision is read from that revision and the run that gates it
   * is dispatched at it — and a file declaring nothing must not be the one exception.
   *
   * <p>Both halves are asserted, and the {@code assertNotEquals} is the one that catches the
   * regression: seeding {@code HEAD} is what a reintroduced fallback would answer with, and a suite
   * that only asserted the tag would pass against a row whose sha was main's.
   */
  @Test
  public void aReleaseTriggerWithNoCheckoutIsRecordedAtTheReleasedRevision() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(PLAIN_PATH, NO_CHECKOUT_RELEASE_TRIGGER));

    deliver(releaseEvent(UUID.randomUUID().toString(), "2026.905.60215", RELEASED));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals(
        "2026.905.60215", recorded.get(0).branch, "the tag the release names, not the tracked branch");
    assertEquals(RELEASED, recorded.get(0).commitSha, "and the commit that tag points at");
    assertNotEquals(HEAD, recorded.get(0).commitSha, "never main's head — that is the removed fallback");
    assertEquals(CiRunStatus.SUCCESS, recorded.get(0).status);
  }

  /** The same for the QA half: the fold, which is a branch nobody pushed and no head of main. */
  @Test
  public void aReleaseRequestTriggerWithNoCheckoutIsRecordedAtTheFold() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(PLAIN_PATH, NO_CHECKOUT_QA_TRIGGER));

    deliver(
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(),
            "ReleaseRequestChanged",
            Instant.parse("2026-09-05T06:02:15Z"),
            "{\"backingBranch\":\"release/r-1\",\"mergedSha\":\"" + PUSHED + "\"}"));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals("release/r-1", recorded.get(0).branch, "the fold's own branch");
    assertEquals(PUSHED, recorded.get(0).commitSha, "at the tip the request announced");
    assertNotEquals(HEAD, recorded.get(0).commitSha);
  }

  /**
   * A release event that names no revision costs such a file its run — <b>no fallback</b>.
   *
   * <p>The state is unreachable on the live path (a conflicted release request is frozen and
   * announces nothing; an {@code SCMRelease} without {@code commitSha} predates the field), which is
   * exactly why the answer is "no run" rather than a default: the alternative is composing and
   * gating {@code main} on behalf of an event that says nothing about it.
   */
  @Test
  public void aReleaseEventNamingNoRevisionCostsACheckoutlessTriggerItsRun() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(PLAIN_PATH, NO_CHECKOUT_RELEASE_TRIGGER));

    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(releaseEvent(UUID.randomUUID().toString(), "2026.905.60215", null));
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds());
    assertEquals(List.of(), runService.runsFor(repoId), "no revision is no run, never main's head");
  }

  /**
   * <b>And the other events are untouched, which is the half that must not be read as the same
   * thing.</b> An event that names no revision in this repository — here an ordinary push event the
   * file declares no checkout for — is still recorded at the tracked branch's head, because that is
   * the only revision that exists rather than a fallback from one that does.
   */
  @Test
  public void aNonReleaseEventWithNoCheckoutStillRunsAtMainsHead() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(PLAIN_PATH, PLAIN_TRIGGER));

    deliver(arrival(UUID.randomUUID().toString(), push("feature/x", PUSHED)));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals("main", recorded.get(0).branch);
    assertEquals(HEAD, recorded.get(0).commitSha, "the convention, and the only answer there is");
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** One release event, with or without the commit its tag points at. */
  private CiEventTriggerService.Arrival releaseEvent(String eventId, String version, String commitSha) {
    String payload =
        commitSha == null
            ? "{\"repository\":\"r\",\"version\":\"" + version + "\"}"
            : "{\"repository\":\"r\",\"version\":\""
                + version
                + "\",\"commitSha\":\""
                + commitSha
                + "\"}";
    return new CiEventTriggerService.Arrival(
        eventId, "SCMRelease", Instant.parse("2026-09-05T06:02:15Z"), payload);
  }

  /**
   * One upstream release: another repository published an artifact, and the payload's coordinate is
   * that repository's rather than this one's — which is why the version is there and no commit of
   * ours ever could be.
   */
  private CiEventTriggerService.Arrival upstreamRelease(String eventId, String version) {
    return new CiEventTriggerService.Arrival(
        eventId,
        "SoftwareRelease",
        Instant.parse("2026-09-05T06:02:15Z"),
        "{\"repository\":\"upstream\",\"version\":\"" + version + "\"}");
  }

  private static String push(String branch, String sha) {
    return "{\"branch\":\"" + branch + "\",\"sha\":\"" + sha + "\",\"suppressCi\":false}";
  }

  private CiEventTriggerService.Arrival arrival(String eventId, String payload) {
    return new CiEventTriggerService.Arrival(
        eventId, "SCMPublishCommit", Instant.parse("2026-08-27T12:00:00Z"), payload);
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  private static CiRun bySha(List<CiRun> runs, String sha) {
    return runs.stream()
        .filter(run -> sha.equals(run.commitSha))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no run at " + sha + " in " + runs));
  }

  /**
   * Parks an unrelated run inside its first step and returns once the worker is really in it —
   * {@code CiTagSupersedeTest}'s staging, so everything accepted afterwards is genuinely queued.
   */
  private void occupyTheWorker() throws Exception {
    String blocker = "blocker-" + UUID.randomUUID().toString().substring(0, 8);
    String sha = "f".repeat(40);
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
    runService.onEventTrigger(
        eventRun(blocker, "main", sha, "steps:\n  - image: alpine:3\n    script: \"true\"\n"));
    inStepZero.get(20, TimeUnit.SECONDS);
  }
}
