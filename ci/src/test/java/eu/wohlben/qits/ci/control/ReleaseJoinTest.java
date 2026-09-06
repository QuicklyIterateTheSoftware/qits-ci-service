package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiReleaseAnnouncement;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release join: a {@code SoftwareRelease} is announced for a {@code (repository, version)} only
 * when a green release pipeline <b>and</b> an {@code SCMRelease} for that pair both exist, in either
 * order — and never for a bootstrap replay, which produces the tag alone.
 *
 * <p>{@link ReleaseAnnounceSeamTest} is the sibling and the two divide cleanly: that one is about
 * <em>what</em> a green release pipeline announces (one event per declared artifact, with which
 * fields), this one is about <em>whether</em> it may. The four arrival orders below are the whole
 * contract, and the fifth case — a tag with no release behind it, announcing nothing, forever — is
 * the defect this class exists to close.
 *
 * <p>The release fact is fed in through {@link ReleaseJoin#onScmRelease} directly rather than off the
 * bus. The arriving is {@code ScmReleaseListener}'s and is asserted in the service module, where the
 * frame exists; what belongs here is the join itself, which is why this test can state all four
 * orders without a socket.
 */
@QuarkusTest
public class ReleaseJoinTest extends CiTestSupport {

  private static final String TAG_TRIGGER_PATH = ".config/qits/ci-event-release.yml";

  private static final String RELEASE_TRIGGER_PATH = ".config/qits/ci-event-own-release.yml";

  private static final String QA_TRIGGER_PATH = ".config/qits/ci-event-release-request.yml";

  private static final String HEAD = "c".repeat(40);

  private static final String VERSION = "2026.812.101500";

  /**
   * The release recipe as bootstrap-replay-plan.md's WP1 leaves it: it reacts to the release TAG,
   * which a replay pushes too. The version it publishes under is the tag's own name.
   */
  private static final String TAG_TRIGGER =
      """
      event: SCMPublishTag
      artifacts:
        - { type: docker, name: qits/qits-thing }
      steps:
        - image: alpine:3
          script: ./publish-tag.sh
      """;

  /** The same recipe before that switch — still in the fleet, and it must not regress. */
  private static final String RELEASE_TRIGGER =
      """
      event: SCMRelease
      artifacts:
        - { type: docker, name: qits/qits-thing }
      steps:
        - image: alpine:3
          script: ./publish-tag.sh
      """;

  /**
   * The repository's QA pipeline, as the rollout stamped it: it reacts to the release request's
   * re-fold, it is green before the release is even approved, and it declares <b>no</b> {@code
   * artifacts:} — it builds and tests a fold and pushes nothing.
   */
  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      steps:
        - image: alpine:3
          script: ./verify.sh
      """;

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject ReleaseJoin join;
  @Inject FakeReleaseAnnouncer releaseAnnouncer;

  private String repoId;

  @BeforeEach
  void resetJoinState() {
    repoId = "releaser-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    releaseAnnouncer.reset();
  }

  // --- the four arrival orders ------------------------------------------------------------------

  /**
   * The path that must not regress: every release recipe in the fleet declared {@code event:
   * SCMRelease} until WP1, and a run triggered by the release itself carries both halves of the join
   * on one row. No lookup, no owed row waiting on anything — announced at green, exactly as before
   * this class existed.
   */
  @Test
  public void anScmReleaseTriggeredRunAnnouncesAtGreen() throws Exception {
    String eventId = releaseRun(releasePayload());

    assertEquals(1, releaseAnnouncer.published().size());
    FakeReleaseAnnouncer.Published published = releaseAnnouncer.published().get(0);
    assertEquals(VERSION, published.version(), "the version comes off the SCMRelease payload");
    assertEquals("qits/qits-thing", published.packageName());
    assertEquals(eventId, published.triggerEventId());
    assertTrue(owedFor(repoId, VERSION).isEmpty(), "nothing is left owed");
  }

  /**
   * Tag first. The run publishes and the announcement waits; the {@code SCMRelease} arriving later is
   * what makes it. This is the ordinary shape of a real release once WP1 has moved the recipes onto
   * the tag: qits-workspaces pushes the tag and publishes the event, and which of the two the build
   * finishes before is a race.
   */
  @Test
  public void aTagTriggeredRunAnnouncesWhenTheReleaseArrivesAfterIt() throws Exception {
    String tagEventId = tagRun();

    assertEquals(
        List.of(),
        releaseAnnouncer.published(),
        "a green tag build alone is a restore, not a release");
    assertEquals(1, owedFor(repoId, VERSION).size(), "the announcement is owed, durably");

    releaseArrives(repoId, repoId, VERSION);

    assertEquals(1, releaseAnnouncer.published().size(), "the release closes the join");
    FakeReleaseAnnouncer.Published published = releaseAnnouncer.published().get(0);
    assertEquals(VERSION, published.version(), "the tag name IS the version string");
    assertEquals(repoId, published.repoId());
    assertEquals(
        tagEventId,
        published.triggerEventId(),
        "the parent is the event that caused the run, carried on the owed row");
    assertTrue(owedFor(repoId, VERSION).isEmpty(), "and the row records that it was made");
  }

  /**
   * Release first. The fact is durable, so the tag build that finishes minutes later finds it and
   * announces at green with nothing owed in between.
   */
  @Test
  public void aTagTriggeredRunAnnouncesAtGreenWhenTheReleaseArrivedFirst() throws Exception {
    releaseArrives(repoId, repoId, VERSION);

    tagRun();

    assertEquals(1, releaseAnnouncer.published().size());
    assertEquals(VERSION, releaseAnnouncer.published().get(0).version());
    assertTrue(owedFor(repoId, VERSION).isEmpty());
  }

  /**
   * The replay, which is the whole point. A bootstrap pushes the release tag to restore SCM state;
   * the recipe builds and publishes; no {@code SCMRelease} is ever produced, because nothing was
   * released. So nothing is announced — <b>no timeout and no fallback</b> — and the train stays
   * asleep against the half-deployed platform the replay is rebuilding.
   */
  @Test
  public void aReplayPublishesSilentlyAndNeverAnnounces() throws Exception {
    tagRun();

    // A release for the same repository at a DIFFERENT version must not close this one's join
    // either: the key is the pair, not the repository.
    releaseArrives(repoId, repoId, "2026.101.010101");
    // And a release of a different repository at this version must not either.
    releaseArrives("somebody-else", null, VERSION);

    assertEquals(List.of(), releaseAnnouncer.published());
    List<CiReleaseAnnouncement> owed = owedFor(repoId, VERSION);
    assertEquals(1, owed.size(), "the obligation stays on the record rather than being forgotten");
    assertNull(owed.get(0).announcedAt);
  }

  // --- durability -------------------------------------------------------------------------------

  /**
   * The crash window. A process that recorded the release and died before announcing what it
   * unblocked leaves an owed row and a fact row; the boot sweep is what pairs them again. It is
   * driven directly here because {@code onStart} skips test mode — the {@code sweepInterrupted}
   * arrangement, for the same reason.
   *
   * <p>The staged fact row states a priority, so this is also the third resolution path: the sweep
   * knows the repository by the run's id alone (its keys come out of the owed rows) and still reads
   * the value off the release. A process that died between the two facts must not cost the
   * announcement anything it would have carried.
   */
  @Test
  public void theBootSweepAnnouncesWhatACrashLeftOwed() throws Exception {
    tagRun();
    recordReleaseWithoutDriving();

    assertEquals(List.of(), releaseAnnouncer.published(), "staged: the fact is in, nobody drove it");

    join.sweepOwed();

    assertEquals(1, releaseAnnouncer.published().size());
    assertEquals(
        "HIGH",
        releaseAnnouncer.published().get(0).priority(),
        "the sweep reads the priority off the fact row it paired, like every other drive");
    assertTrue(owedFor(repoId, VERSION).isEmpty());
  }

  /**
   * And the sweep is not a second announcement. Everything it finds is already-owed work; a row that
   * has been announced carries the stamp and is out of the query by construction, so a boot after a
   * quiet week says nothing at all.
   */
  @Test
  public void aSecondDriveOfAClosedJoinAnnouncesNothingMore() throws Exception {
    tagRun();
    releaseArrives(repoId, repoId, VERSION);
    assertEquals(1, releaseAnnouncer.published().size());

    releaseArrives(repoId, repoId, VERSION);
    join.sweepOwed();

    assertEquals(1, releaseAnnouncer.published().size(), "announced once, whatever drives the join");
  }

  /**
   * A red release pipeline owes nothing, so a release arriving afterwards has nothing to announce.
   * The gate is additional to the terminal-transition rule rather than a replacement for it: a build
   * that failed published no artifact, and no amount of release novelty makes one exist.
   */
  @Test
  public void aRedReleasePipelineOwesNothingForAReleaseToClose() throws Exception {
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    tagRun();

    releaseArrives(repoId, repoId, VERSION);

    assertEquals(List.of(), releaseAnnouncer.published());
    assertEquals(List.of(), owedFor(repoId, VERSION));
  }

  /**
   * The registered name is the second spelling of the same repository, and the join matches either.
   * A run is named by the git host's repository id; {@code SCMRelease} carries an id and, optionally,
   * a name. They agree on this platform and the event does not promise it, so a join comparing only
   * one of them would be a release nobody announces.
   */
  @Test
  public void aReleaseNamingTheRepositoryOnlyByNameStillClosesTheJoin() throws Exception {
    tagRun();

    releaseArrives("a-uuid-nobody-here-knows", repoId, VERSION);

    assertEquals(1, releaseAnnouncer.published().size());
  }

  // --- who may satisfy the join, and when --------------------------------------------------------

  /**
   * <b>The release request's QA run is not the release pipeline, and no {@code SCMRelease} may make
   * it one.</b>
   *
   * <p>This is the shape the platform actually runs in since per-push CI retired, and it is the one
   * a false announcement would be cheapest in. qits-projects folds a request onto {@code
   * release/<id>}, publishes {@code ReleaseRequestChanged}, and the repository's QA pipeline builds
   * that fold and goes green. Minutes later the request is approved: the tag is created and {@code
   * SCMRelease} is published — while the release pipeline that builds and pushes {@code
   * qits/<app>:<version>} is still QUEUED behind the single run worker. If the arriving release
   * could close a join against <em>that</em> green run, qits-deployments would be told about an
   * image that does not exist for another half hour, and the real announcement would arrive after
   * the deployer had already recorded the version.
   *
   * <p>It cannot, and the reason is one line rather than a guard: {@code artifacts:} is a
   * DECLARATION, so a run owes an announcement only for what its own trigger file declared, and the
   * QA pipeline declares nothing. {@code CiRunService.announceRelease} returns on the null and
   * {@link ReleaseJoin} is never called at all — which is why the payload below carries a {@code
   * version} the pipeline could have been announced under. The version was never the gate; the
   * declaration is.
   */
  @Test
  public void aGreenQaRunOfTheSameRepositoryOwesNothingForAReleaseToClose() throws Exception {
    qaRun();

    releaseArrives(repoId, repoId, VERSION);

    assertEquals(
        List.of(),
        releaseAnnouncer.published(),
        "a green QA run published no artifact, so the release has nothing of its to announce");
    assertEquals(List.of(), owedFor(repoId, VERSION), "and nothing was ever owed");
  }

  /**
   * And the whole sequence, in the order a real release runs it: green QA run, then the tag and its
   * {@code SCMRelease}, then — minutes later — the release pipeline. <b>Exactly one announcement,
   * made at the finish of the run that pushed the image.</b>
   *
   * <p>The {@code finishedAt} assertion is what makes this a claim about <em>timing</em> rather than
   * about a count: the announcement carries the release pipeline run's own terminal timestamp, so an
   * announcement made at tag time could not produce it. That is the invariant qits-deployments
   * depends on — a {@code SoftwareRelease} for {@code qits/<app>:<version>} is published once, by
   * the run that really pushed it, after it succeeds.
   */
  @Test
  public void theReleaseIsAnnouncedOnceAndOnlyWhenTheReleasePipelineSucceeds() throws Exception {
    qaRun();

    // The tag moment: qits-projects created the tag and published SCMRelease. Nothing has been
    // built yet, and nothing may be announced yet.
    releaseArrives(repoId, repoId, VERSION);
    assertEquals(
        List.of(), releaseAnnouncer.published(), "no artifact exists at tag time, so no announcement");

    // And now the release pipeline, which is what actually pushes qits/<app>:<version>.
    releaseRun(releasePayload());

    assertEquals(1, releaseAnnouncer.published().size(), "announced once, by the run that built it");
    FakeReleaseAnnouncer.Published published = releaseAnnouncer.published().get(0);
    assertEquals("qits/qits-thing", published.packageName());
    assertEquals(VERSION, published.version());
    CiRun releasePipeline = releaseRunRow();
    assertEquals(
        releasePipeline.finishedAt,
        published.finishedAt(),
        "the announcement is stamped with the release pipeline's own finish, which is what makes"
            + " announcing it at tag time impossible rather than merely unusual");
    assertTrue(owedFor(repoId, VERSION).isEmpty());
  }

  // --- what the announcement carries about the repository ----------------------------------------

  /**
   * The project rides on the <b>owed row</b>, which is what makes it survive the gap the join exists
   * for.
   *
   * <p>{@code SoftwareRelease} carries {@code projectId}, {@code repoId} and {@code repoName} so a
   * deploy consumer can address the repository without asking qits-projects on its own dispatch
   * thread — and the name half is what makes the address usable at all, since the id-addressed read
   * is refused to everyone but qits-projects. The run knows all three; the announcement is made by
   * whoever closes the join, and in the tag-first order that is the
   * {@code SCMRelease} arriving later — a different thread, possibly a different process after a
   * restart, with no access to the run. So the value is copied onto the obligation at green, and this
   * is the order that proves it: had it been read from the run at announce time, this case would
   * publish a null and the {@code SCMRelease}-triggered case one directory up would still pass.
   */
  @Test
  public void theOwedAnnouncementCarriesTheProjectItWasOwedFor() throws Exception {
    fakeCandidates.setRefs(CiRepoRef.of(repoId, "p-42", "qits-thing-service"));

    tagRun();
    releaseArrives(repoId, repoId, VERSION);

    assertEquals(1, releaseAnnouncer.published().size());
    FakeReleaseAnnouncer.Published published = releaseAnnouncer.published().get(0);
    assertEquals("p-42", published.projectId(), "the project the run recorded, off the owed row");
    assertEquals(
        "qits-thing-service",
        published.repoName(),
        "and the name beside it — the two are one address, so one surviving the gap without the"
            + " other would be an address the deployer cannot use");
    assertEquals(repoId, published.repoId(), "and the repository that published it");
  }

  /**
   * An id-addressed run has neither project nor name, and the announcement says so by carrying
   * neither.
   *
   * <p>That is the shipped answer for a repository the candidate listing knows only by storage id —
   * every pre-cutover row, and any platform running without {@code qits.ci.projects-url}. The wire
   * form then omits both keys entirely (NON_NULL inclusion), which is the honest spelling of "qits-ci
   * does not know" and is what stops a consumer reading an invented id or building an address it
   * would be refused on.
   */
  @Test
  public void anIdAddressedRunAnnouncesNoProjectRatherThanAGuessedOne() throws Exception {
    releaseRun(releasePayload());

    assertEquals(1, releaseAnnouncer.published().size());
    assertNull(releaseAnnouncer.published().get(0).projectId());
    assertNull(releaseAnnouncer.published().get(0).repoName());
  }

  // --- the priority, carried through and acted on nowhere ----------------------------------------

  /**
   * <b>Tag first: the priority the release states reaches the announcement made after it.</b>
   *
   * <p>This is the order that proves the value is resolved at ANNOUNCE time. The owed row is written
   * when the run goes green — before any release exists — so nothing about the priority can be on it,
   * and the {@code SCMRelease} arriving later is both what closes the join and what says what the
   * release was worth. Had the value been carried on the obligation instead, this case would publish
   * null and the release-first case below would still pass.
   */
  @Test
  public void thePriorityAReleaseStatesReachesAnAnnouncementOwedBeforeIt() throws Exception {
    tagRun();

    join.onScmRelease(
        repoId, repoId, VERSION, UUID.randomUUID().toString(), Instant.now(), "BLOCKING");

    assertEquals(1, releaseAnnouncer.published().size());
    assertEquals(
        "BLOCKING",
        releaseAnnouncer.published().get(0).priority(),
        "the release said what it was worth, and the announcement it unblocked says the same");
  }

  /**
   * Release first, and the same answer. The fact row is durable, so a run finishing minutes later
   * announces at green with the priority read straight back off it — the arrival order is a race and
   * neither side of it may change what the wire carries.
   */
  @Test
  public void thePriorityIsCarriedWhenTheReleaseArrivedBeforeTheRun() throws Exception {
    join.onScmRelease(repoId, repoId, VERSION, UUID.randomUUID().toString(), Instant.now(), "LOW");

    tagRun();

    assertEquals(1, releaseAnnouncer.published().size());
    assertEquals("LOW", releaseAnnouncer.published().get(0).priority());
  }

  /**
   * <b>No fact row, no priority — and that is the manual door's ordinary shape rather than a
   * failure.</b>
   *
   * <p>A run whose own trigger IS the release closes the join by construction and takes no lookup at
   * all (see the class javadoc), so a hand-supplied {@code SCMRelease} — which rides no bus and
   * leaves no fact row — announces with nothing to read a priority off. Null is the honest answer,
   * and {@code CanonicalJson}'s NON_NULL inclusion turns it into an absent key rather than a value a
   * consumer could act on. The same is true of every release published before the field existed.
   */
  @Test
  public void aRunWithNoReleaseFactBehindItAnnouncesNoPriority() throws Exception {
    releaseRun(releasePayload());

    assertEquals(1, releaseAnnouncer.published().size());
    assertNull(
        releaseAnnouncer.published().get(0).priority(),
        "nothing recorded what this release was worth, so nothing may be invented for it");
  }

  /**
   * A release that states no priority is an ordinary release, and the announcement says so by
   * carrying none. The join's key is {@code (repository, version)} and the priority is no part of
   * it — so an older publisher's event closes the join exactly as a new one's does.
   */
  @Test
  public void aReleaseStatingNoPriorityAnnouncesNone() throws Exception {
    tagRun();

    releaseArrives(repoId, repoId, VERSION);

    assertEquals(1, releaseAnnouncer.published().size());
    assertNull(releaseAnnouncer.published().get(0).priority());
  }

  /**
   * <b>The column's own rule, and the only judgement this service passes on the value.</b>
   *
   * <p>{@code ci_scm_release.priority} is 32 characters; a longer one is recorded as NONE with a
   * WARN rather than truncated or thrown — {@code ci_run.release_request_id}'s rule verbatim. The
   * release fact is the point: a release must not fail to be recorded, and therefore must not fail to
   * be announced, over a field nothing here reads. Note what is NOT asserted: no value is rejected
   * for being unknown, because the vocabulary is qits-projects' and this service holds no copy of it.
   */
  @Test
  public void anOverlongPriorityIsRecordedAsNoneAndTheReleaseStillAnnounces() throws Exception {
    tagRun();

    join.onScmRelease(
        repoId,
        repoId,
        VERSION,
        UUID.randomUUID().toString(),
        Instant.now(),
        "B".repeat(ReleaseJoin.MAX_PRIORITY_LENGTH + 1));

    assertEquals(
        1, releaseAnnouncer.published().size(), "the release is recorded and the join still closes");
    assertNull(
        releaseAnnouncer.published().get(0).priority(),
        "a value the column cannot hold is none, not a truncation somebody could act on");
  }

  /**
   * A word this service has never heard of rides through untouched, which is the whole of "verbatim".
   * qits-ci has no enum for the vocabulary and compares the value to nothing, so a priority added in
   * qits-projects reaches a consumer the day it is published rather than the day this repository is
   * released again.
   */
  @Test
  public void anUnknownPriorityIsCarriedRatherThanJudged() throws Exception {
    tagRun();

    join.onScmRelease(
        repoId, repoId, VERSION, UUID.randomUUID().toString(), Instant.now(), "CATASTROPHIC");

    assertEquals("CATASTROPHIC", releaseAnnouncer.published().get(0).priority());
  }

  // --- fixtures ---------------------------------------------------------------------------------

  /** A green run of the tag-triggered release recipe, and the id of the tag event that caused it. */
  private String tagRun() throws Exception {
    return deliver(
        TAG_TRIGGER_PATH,
        TAG_TRIGGER,
        CiRunService.TAG_EVENT_NAME,
        "{\"repoId\":\"" + repoId + "\",\"tagName\":\"" + VERSION + "\"}");
  }

  /** A green run of the SCMRelease-triggered recipe — the pre-WP1 shape. */
  private String releaseRun(String payload) throws Exception {
    return deliver(
        RELEASE_TRIGGER_PATH, RELEASE_TRIGGER, ReleaseJoin.RELEASE_EVENT_NAME, payload);
  }

  /**
   * A green run of the repository's QA pipeline — the release request's fold, built and passed. It
   * declares no {@code artifacts:}, which is the whole of why it can close no release join, and its
   * payload names the version anyway so the test cannot pass for the wrong reason.
   */
  private String qaRun() throws Exception {
    return deliver(
        QA_TRIGGER_PATH,
        QA_TRIGGER,
        CiRunService.RELEASE_REQUEST_EVENT_NAME,
        "{\"repoName\":\""
            + repoId
            + "\",\"releaseRequestId\":\""
            + UUID.randomUUID()
            + "\",\"backingBranch\":\"release/r-1\",\"mergedSha\":\""
            + "f".repeat(40)
            + "\",\"version\":\""
            + VERSION
            + "\"}");
  }

  /** The run of the release recipe, of everything this repository has recorded. */
  private CiRun releaseRunRow() {
    forgetLoadedEntities();
    return runService.runsFor(repoId).stream()
        .filter(run -> RELEASE_TRIGGER_PATH.equals(run.configPath))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no release pipeline run was recorded"));
  }

  private String releasePayload() {
    return "{\"repository\":\""
        + repoId
        + "\",\"repositoryName\":\""
        + repoId
        + "\",\"branch\":\"main\",\"version\":\""
        + VERSION
        + "\"}";
  }

  private String deliver(String path, String trigger, String eventName, String payload)
      throws Exception {
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(path, trigger));
    String eventId = UUID.randomUUID().toString();
    engine.evaluate(
        new CiEventTriggerService.Arrival(
            eventId, eventName, Instant.parse("2026-08-12T09:00:00Z"), payload));
    runService.awaitIdle();
    forgetLoadedEntities();
    CiRun run = runService.runsFor(repoId).get(0);
    assertNotNull(run.finishedAt, "the run finished before anything about the join is asserted");
    return eventId;
  }

  /**
   * An {@code SCMRelease} arriving, stating no priority — the shape every case above but the priority
   * ones is about, and the shape a publisher that predates the field produces.
   */
  private void releaseArrives(String repoId, String repoName, String version) {
    join.onScmRelease(
        repoId, repoName, version, UUID.randomUUID().toString(), Instant.now(), null);
  }

  /** The release fact, written the way the listener writes it but with nothing driven off it — the
   *  state a crash between the two leaves behind. */
  private void recordReleaseWithoutDriving() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiScmRelease release = new CiScmRelease();
              release.id = UUID.randomUUID().toString();
              release.repoId = repoId;
              release.repoName = repoId;
              release.version = VERSION;
              release.eventId = UUID.randomUUID().toString();
              release.occurredAt = Instant.now();
              release.seenAt = Instant.now();
              release.priority = "HIGH";
              scmReleases.persist(release);
            });
  }

  private List<CiReleaseAnnouncement> owedFor(String repoId, String version) {
    forgetLoadedEntities();
    return QuarkusTransaction.requiringNew().call(() -> announcements.listOwed(repoId, version));
  }
}
