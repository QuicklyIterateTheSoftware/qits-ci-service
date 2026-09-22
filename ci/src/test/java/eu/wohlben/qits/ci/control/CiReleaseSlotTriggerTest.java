package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine's half of the release-slot feature: a repository that commits {@code
 * .config/qits/release.yml} gets its two release pipelines <b>composed</b>, and nothing else about
 * the generic trigger grammar changes around it.
 *
 * <p>Everything below the bus is real, exactly as in {@code CiEventTriggerServiceTest}: the slot
 * parser, the archetype read through the same {@link CiConfigSource} port the platform pipelines use,
 * the composer, the trigger parser reading the composed text back, the run service and the unique
 * constraint. What is faked is the git host and the frame.
 *
 * <p><b>The contrast this class exists to hold is the last two sections' against each other.</b>
 * Broken committed content — an archetype that does not exist, a slot file that will not parse — is
 * no run <em>and the event is settled</em>: a person declared that, the declaration is final, and
 * retrying it forever would be asking a git host to change somebody's mind. A slot file that could
 * not be READ is no run and the event <em>stays owed</em>: nothing was learned, and every repository
 * in the estate now keeps its whole release cycle in that one file, so settling on a blip is what
 * silently costs a release request its QA verdict.
 *
 * <h2>Which revision a fixture seeds, and why it is not {@code main}</h2>
 *
 * <p><b>The pipeline that gates a revision is read from that revision.</b> A repository's {@code
 * .config/qits/release.yml} is read at the commit the arriving release event is about — the
 * request's fold for a {@code ReleaseRequestChanged}, the released tag's commit for an {@code
 * SCMRelease} — which is the same commit the composed run checks out. So {@link #seedSlots} seeds
 * the file at those two revisions and <b>deliberately not at {@code main}</b>: a fixture that seeded
 * main would keep passing against the old read and say nothing about the new one.
 *
 * <p>This class used to argue the opposite — "decide at main, so a release request cannot alter the
 * CI that gates it" — and that rule is <b>withdrawn by owner ruling</b>, because it was neither true
 * of what it protected nor free. It was not free: a repository could never ship its own first
 * release cycle (the tag declared a {@code release:} slot, qits-projects stamped the request
 * publish-gated from that tag, and the run composed from a {@code main} with no such file — no run,
 * event settled, request RELEASED forever; measured on qits-landing-app), and no change to {@code
 * release.yml} was ever exercised by the release that carried it. And it did not protect what it
 * claimed: the half of a composed pipeline that is <em>platform process</em> — the prelude, the
 * postlude, the shared recipes — is the archetype's, and that is read from the WRAPPER repository,
 * which no release request of another repository can touch. That split is still here, is asserted
 * below, and is what "a branch cannot rewrite the platform's half of its own gate" really rests on.
 *
 * <h2>And which revision of the WRAPPER, which is what the last three sections are about</h2>
 *
 * <p><b>The archetype recipe is read at the newest version the wrapper has RELEASED</b> — a {@code
 * YYYY.MMDD.HHMMSS} tag that a release request carried, CI gated and a person approved — and never
 * at the wrapper's {@code main} head. Those recipes contribute most of the steps of every release
 * pipeline on the estate, so reading them at {@code main} meant the steps of every release came from
 * whatever landed a minute ago; owner ruling, stated repeatedly: nothing in a release pipeline may
 * come from "whatever is on main". {@link #WRAPPER_HEAD} is therefore seeded all over this class as
 * a <em>decoy</em>: the fixtures put readable recipes at the wrong revisions on purpose, so a
 * regression composes successfully and only an assertion about WHICH bytes ran can catch it.
 *
 * <p><b>Both ways there is no released version leave the event OWED</b>, and the second of them is
 * the decision worth knowing: a git host that could not be asked, obviously; and a wrapper that has
 * genuinely never released, because that is not a person's declaration about this repository — it is
 * a state of the estate that the wrapper's own next release changes, with nothing in the candidate
 * repository having moved. Settling it would answer "no QA" to a release request waiting for exactly
 * that QA. It does not deadlock a fresh estate, and the test that says why is
 * {@code aSlotFileNamingNoArchetypeComposesEvenWhenTheWrapperHasNeverReleased}: the wrapper's own
 * slot file names no archetype, so its first release needs no recipe to exist.
 */
@QuarkusTest
public class CiReleaseSlotTriggerTest extends CiTestSupport {

  private static final String HEAD = "c".repeat(40);

  /**
   * The wrapper's {@code main} head — <b>a wrong revision now</b>, and seeded throughout this class
   * precisely so that it is. The platform trigger listing still resolves it (that is where a {@code
   * ci-platform-event-*.yml} is read from, and it is a trigger), but no archetype recipe may ever be
   * read at it: the recipes come from a version somebody released.
   */
  private static final String WRAPPER_HEAD = "d".repeat(40);

  /** The newest version the wrapper has RELEASED — a tag a person approved, and immutable. */
  private static final String WRAPPER_VERSION = "2026.922.161358";

  /** The commit that tag names, which is the one revision an archetype recipe is ever read at. */
  private static final String WRAPPER_RELEASED_SHA = "a".repeat(40);

  /** The commit the release event names, and therefore the ref a composed release run builds. */
  private static final String RELEASED_SHA = "f".repeat(40);

  /**
   * The fold a release request announces — the commit its QA run builds, and therefore the commit
   * its QA pipeline is composed from.
   */
  private static final String MERGED_SHA = "e".repeat(40);

  /**
   * A repository's own hand-written trigger file, which is the escape hatch the generic grammar
   * still is: a bespoke pipeline on a release event, declared on purpose, beside whatever {@code
   * release.yml} composes. Nothing supersedes it and nothing ever did except the two canonical
   * release paths, which no repository commits any more.
   */
  private static final String BESPOKE_PATH = ".config/qits/ci-event-upstream.yml";

  private static final String BESPOKE =
      """
      event: ReleaseRequestChanged
      when:
        - repoName: { exact: qits-target }
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: echo bespoke
      """;

  private static final String SPA_FRONTEND =
      """
      release-request:
        - image: qits/build-images/node-base:latest
          script: npm ci && npm run build
      """;

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer announcer;

  private String repoId;
  private String wrapperId;

  @BeforeEach
  void armTheCandidates() {
    repoId = "target-" + UUID.randomUUID().toString().substring(0, 8);
    wrapperId = "wrapper-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(
        CiRepoRef.of(repoId, "qits", "qits-target"),
        CiRepoRef.of(wrapperId, "qits", "qits-qits"));
    fakeConfig.putTriggers(repoId, "main", HEAD);
    fakeConfig.putTriggers(wrapperId, "main", WRAPPER_HEAD);
    // THE WRAPPER'S OWN LISTING, which is the PLATFORM TRIGGER half and is still read at main: a
    // ci-platform-event-*.yml is a trigger, and every trigger on this platform is discovered at
    // main's head. It has nothing to do with archetypes any more.
    fakeConfig.putTriggers(wrapperId, "main", CiTriggerScope.PLATFORM, WRAPPER_HEAD);
    // AND THE WRAPPER'S RELEASED VERSION, which is what makes an archetype readable at all. The
    // engine resolves the newest released version of the platform repository once per evaluation and
    // every archetype read of that evaluation is made at that version's sha — so a fixture that
    // seeds a recipe seeds it under WRAPPER_RELEASED_SHA, and a wrapper with no released version has
    // no approved revision to read one at.
    fakeConfig.putReleasedVersion(wrapperId, WRAPPER_VERSION, WRAPPER_RELEASED_SHA);
    engine.platformPipelinesRepository("qits-qits");
    announcer.reset();
  }

  @AfterEach
  void disarm() {
    engine.platformPipelinesRepository("");
  }

  private CiEventTriggerService.Arrival releaseRequest() {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(),
        CiReleaseComposer.RELEASE_REQUEST_EVENT,
        Instant.parse("2026-09-06T09:00:00Z"),
        "{\"repoName\":\"qits-target\",\"backingBranch\":\"release/abc\",\"mergedSha\":\""
            + MERGED_SHA
            + "\",\"releaseRequestId\":\"a1b2c3\"}");
  }

  private CiEventTriggerService.Arrival release() {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(),
        CiReleaseComposer.RELEASE_EVENT,
        Instant.parse("2026-09-06T10:00:00Z"),
        "{\"repository\":\"qits-target\",\"version\":\"2026.906.100732\",\"commitSha\":\""
            + RELEASED_SHA
            + "\"}");
  }

  /**
   * The repository's release cycle, <b>at the two revisions the two release events name</b> — the
   * fold and the released tag — and at neither {@code main} nor anything else.
   *
   * <p>That is the whole fixture-level statement of the invariant: the file is only ever where the
   * run will look, so a read that went back to a branch head composes nothing and every test in this
   * class goes red rather than one of them.
   */
  private void seedSlots(String content) {
    fakeConfig.putFile(repoId, MERGED_SHA, CiReleaseSlotParser.CONFIG_PATH, content);
    fakeConfig.putFile(repoId, RELEASED_SHA, CiReleaseSlotParser.CONFIG_PATH, content);
  }

  /**
   * A recipe in the wrapper, <b>at the sha of the wrapper's newest RELEASED version</b> — never at
   * the branch name and never at main's head, which is the whole of what this ticket removed.
   */
  private void seedArchetype(String name, String content) {
    fakeConfig.putFile(
        wrapperId, WRAPPER_RELEASED_SHA, CiReleaseSlotParser.archetypePath(name), content);
  }

  /** The same recipe at the wrapper's MAIN head — a revision nothing may ever read one at. */
  private void seedArchetypeAtMainHead(String name, String content) {
    fakeConfig.putFile(wrapperId, WRAPPER_HEAD, CiReleaseSlotParser.archetypePath(name), content);
  }

  /** The read a composed run's archetype must have come from, in {@code FakeCiConfigSource}'s key. */
  private String archetypeReadAt(String rev, String name) {
    return wrapperId + "@" + rev + "/" + CiReleaseSlotParser.archetypePath(name);
  }

  private void seedTrigger(String path, String content) {
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(path, content));
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  /**
   * The same evaluation <b>through the owed-event ledger</b>, which is the only way to see whether
   * an event was settled.
   *
   * <p>{@link #deliver} calls {@code evaluate} directly and the ledger never hears about it — right
   * for every case that is about which runs were recorded, and useless for the ones below that are
   * about whether the event is still owed afterwards. This goes in at {@code onEvent}, which is the
   * door the bus listener uses: the row is written before the acceptance is reported, the evaluation
   * happens on {@code ci-trigger-worker}, and whether the row survives is the assertion.
   */
  private void deliverThroughTheLedger(CiEventTriggerService.Arrival arrival) throws Exception {
    assertTrue(engine.onEvent(arrival), "the accept writes the owed row and reports it");
    engine.awaitIdle();
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  /** Whether the ledger still owes this event — the whole subject of the last two sections. */
  private boolean stillOwed(String eventId) {
    return QuarkusTransaction.requiringNew().call(() -> owedEvents.findById(eventId) != null);
  }

  // --- the composed run ---------------------------------------------------------------------------

  @Test
  public void aSlotFileNamingAnArchetypeRecordsAComposedQaRun() throws Exception {
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    CiEventTriggerService.Arrival arrival = releaseRequest();

    deliver(arrival);

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    CiRun run = recorded.get(0);
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(CiTriggerType.EVENT, run.triggerType);
    assertEquals(arrival.eventId(), run.triggerEventId);
    // THE ROW NAMES THE SLOT FILE, not a composed pseudo-path. It is one third of the dedupe key and
    // it is what a person reading the run sees, so it has to be the file somebody can open.
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, run.configPath);
    // Composed FROM the fold and built AT the fold — one revision, which is the invariant. The
    // checkout resolves out of the payload exactly as a committed trigger's does, through the same
    // code, and the slot file the document came from was read at that same sha.
    assertEquals("release/abc", run.branch);
    assertEquals(MERGED_SHA, run.commitSha);
    assertTrue(
        fakeConfig
            .fileReads()
            .contains(repoId + "@" + MERGED_SHA + "/" + CiReleaseSlotParser.CONFIG_PATH),
        "the slot file is read at the fold: " + fakeConfig.fileReads());
    assertEquals("a1b2c3", run.releaseRequestId, "the provenance column is unaffected by composition");
    // WHICH RECIPE, AND WHICH VERSION OF IT. The rev is the sha of the wrapper release the recipe
    // was read from and the version is that release's name, so the row says what composed it
    // rather than leaving a reader to guess at the wrapper's history.
    assertEquals("spa-frontend", run.archetypeName);
    assertEquals(CiReleaseSlotParser.archetypePath("spa-frontend"), run.archetypeConfigPath);
    assertEquals(WRAPPER_RELEASED_SHA, run.archetypeRev);
    assertEquals(
        WRAPPER_VERSION,
        run.archetypeVersion,
        "and the APPROVED wrapper release it is, which no sha alone can be read back as");
    // trigger_config is the COMPOSED text, which is what restart-reparse will read back.
    assertNotNull(run.triggerConfig);
    assertTrue(run.triggerConfig.contains("event: ReleaseRequestChanged"), run.triggerConfig);
    assertTrue(run.triggerConfig.contains("npm ci && npm run build"), run.triggerConfig);
    // And it really is the archetype's step that ran.
    // The resolved reference, not the recipe's shorthand: CiStepImage prefixes a platform image
    // with the deployment's registry, and the composer changes nothing about that.
    assertTrue(
        fakeRunner.executed().get(0).image().endsWith("qits/build-images/node-base:latest"),
        fakeRunner.executed().get(0).image());
  }

  @Test
  public void theComposedReleaseRunAnchorsAtTheTagAndSeedsTheVersion() throws Exception {
    seedSlots(
        """
        release:
          - image: qits/build-images/ci-base:latest
            build: true
            script: buildctl build
        artifacts:
          - { type: docker, name: qits/qits-target, sbom: out/sbom.json }
        """);

    deliver(release());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals("2026.906.100732", recorded.get(0).branch, "the tag's own name is the run's ref");
    assertEquals(RELEASED_SHA, recorded.get(0).commitSha);

    Map<String, String> env = fakeRunner.executed().get(0).env();
    // The one thing 30 files in the estate re-parse for themselves, seeded once by the platform.
    assertEquals("2026.906.100732", env.get("QITS_VERSION"));

    // And the declaration survives composition, so the green run announces the release.
    assertEquals(1, announcer.announced().size());
  }

  @Test
  public void bothReleaseEventsRecordARunAgainstTheOneSlotFile() throws Exception {
    // THE DEDUPE QUESTION, asserted rather than argued. config_path is the same string for both
    // derived runs, and the unique key is (trigger_event_id, repo_id, config_path) — so the two
    // runs coexist because they come from two DIFFERENT events, which is exactly what a repository
    // with two legacy files already relied on.
    seedSlots(
        """
        release-request:
          - image: alpine:3
            script: echo qa
        release:
          - image: alpine:3
            script: echo publish
        """);

    deliver(releaseRequest());
    deliver(release());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(2, recorded.size());
    assertTrue(
        recorded.stream().allMatch(run -> CiReleaseSlotParser.CONFIG_PATH.equals(run.configPath)));
    assertEquals(
        2, recorded.stream().map(run -> run.triggerEventName).distinct().count());
  }

  // --- the revision the pipeline is read from ------------------------------------------------------

  @Test
  public void aRepositoryWhoseMainCarriesNoSlotFileStillGetsItsQaPipelineFromTheFold()
      throws Exception {
    // THE DEADLOCK, exactly. A repository that has never released carries no .config/qits/release.yml
    // on main and carries one on the branch that is asking to be released. Reading main composed
    // nothing, so the QA run the gate was waiting for was never recorded, the event settled with
    // nothing left to re-drive it, and the request could never finalize — so main never moved and
    // the file could never arrive there. A repository could not ship its own first release cycle.
    // Measured 2026-09-22 on qits-landing-app.
    fakeConfig.putFile(
        repoId,
        MERGED_SHA,
        CiReleaseSlotParser.CONFIG_PATH,
        """
        release-request:
          - image: alpine:3
            script: echo qa
        """);
    // main declares nothing, and is left that way on purpose: this is the whole fixture.

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "the QA pipeline the fold declares: " + fakeConfig.fileReads());
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, recorded.get(0).configPath);
    assertEquals(MERGED_SHA, recorded.get(0).commitSha, "composed from and built at one revision");
    assertFalse(stillOwed(arrival.eventId()), "and the event is settled: it was fully evaluated");
  }

  @Test
  public void aTagDeclaringAReleaseSlotMainLacksStillGetsItsPublishPipeline() throws Exception {
    // The publish half of the same defect, and the half qits-projects' gate sees first: it asks
    // releasePhaseAt(repo, refs/tags/<version>), which has always composed at the rev it was asked
    // about, and stamps the request publish-gated on the strength of the TAG's declaration. The run
    // composition read main, found no `release:` slot there, recorded nothing — and the request sat
    // RELEASED behind a gate nothing would ever answer. The two sides read one revision now.
    fakeConfig.putFile(
        repoId,
        RELEASED_SHA,
        CiReleaseSlotParser.CONFIG_PATH,
        """
        release:
          - image: alpine:3
            script: echo publish
        """);

    CiEventTriggerService.Arrival arrival = release();
    deliverThroughTheLedger(arrival);

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(
        1, recorded.size(), "the publish pipeline the tag declares: " + fakeConfig.fileReads());
    assertEquals(RELEASED_SHA, recorded.get(0).commitSha);
    assertEquals("2026.906.100732", recorded.get(0).branch, "the tag's own name is the run's ref");
    assertFalse(stillOwed(arrival.eventId()));
  }

  @Test
  public void theSlotFileIsNeverReadAtMainForAReleaseEventAndTheWrapperOnlyEverIs()
      throws Exception {
    // BOTH HALVES OF THE SPLIT, asserted as absences because nothing else catches either regression.
    // A read of the repository's release.yml at main passes every other test in this class the day
    // somebody re-seeds main; and a read of the ARCHETYPE at the fold would pass them all too, while
    // quietly handing a release request the power to rewrite the platform prelude that gates it.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    // Every wrong revision is seeded, so a regression in any direction SUCCEEDS and only these
    // assertions stand between it and a green suite: the repository's declaration at its main head,
    // the recipe at the fold, and the recipe at the WRAPPER'S OWN MAIN HEAD — the last being the
    // read this ticket removed.
    fakeConfig.putFile(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH, "archetype: spa-frontend\n");
    fakeConfig.putFile(
        wrapperId, MERGED_SHA, CiReleaseSlotParser.archetypePath("spa-frontend"), SPA_FRONTEND);
    seedArchetypeAtMainHead("spa-frontend", SPA_FRONTEND);

    deliver(releaseRequest());

    assertEquals(1, runService.runsFor(repoId).size());
    assertFalse(
        fakeConfig
            .fileReads()
            .contains(repoId + "@" + HEAD + "/" + CiReleaseSlotParser.CONFIG_PATH),
        "the repository's own declaration is read at the fold and nowhere else: "
            + fakeConfig.fileReads());
    assertEquals(
        List.of(archetypeReadAt(WRAPPER_RELEASED_SHA, "spa-frontend")),
        fakeConfig.fileReads().stream()
            .filter(read -> read.contains(CiReleaseSlotParser.archetypePath("spa-frontend")))
            .toList(),
        "and the wrapper's recipe at the wrapper's newest RELEASED version — never at the fold,"
            + " which a release request could move, and never at the wrapper's main head, which"
            + " nobody approved: "
            + fakeConfig.fileReads());
  }

  // --- the composed pipeline beside a repository's own ---------------------------------------------

  @Test
  public void anUnrelatedTriggerFileIsUnaffected() throws Exception {
    // A composed pipeline supersedes nothing. The generic mechanism survives as the escape hatch it
    // is — two files, two declared pipelines, two runs — and a repository's bespoke pipeline on a
    // release event is nobody's business but its own.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    seedTrigger(BESPOKE_PATH, BESPOKE);

    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(2, recorded.size(), "the composed pipeline and the repository's own bespoke one");
    assertTrue(recorded.stream().anyMatch(run -> BESPOKE_PATH.equals(run.configPath)));
    assertTrue(
        recorded.stream()
            .anyMatch(run -> CiReleaseSlotParser.CONFIG_PATH.equals(run.configPath)));
  }

  @Test
  public void anOrdinaryEventNeitherReadsTheSlotFileNorSkipsAnything() throws Exception {
    // The gate that keeps this feature free for the other 99% of the bus: no blob read at all, and
    // — since the wrapper's released version is resolved on first use rather than up front — no tag
    // read either. A resolution hoisted to the top of an evaluation would be one extra git-host call
    // per frame on the bus, on the single-threaded trigger worker, for a question nothing asked.
    seedSlots("archetype: spa-frontend\n");
    seedTrigger(
        ".config/qits/ci-event-upstream.yml",
        """
        event: BuildSuccessful
        steps:
          - image: alpine:3
            script: echo bump
        """);

    deliver(
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.parse("2026-09-06T11:00:00Z"),
            "{}"));

    assertEquals(1, runService.runsFor(repoId).size());
    assertTrue(
        fakeConfig.fileReads().stream()
            .noneMatch(read -> read.contains(CiReleaseSlotParser.CONFIG_PATH)),
        "a BuildSuccessful must cost exactly the reads it cost before this feature existed: "
            + fakeConfig.fileReads());
    assertEquals(
        List.of(),
        fakeConfig.tagReads(),
        "and the wrapper's tags are not read either — the released version is resolved when a"
            + " repository actually asks for a recipe: "
            + fakeConfig.tagReads());
  }

  // --- no slot file: the generic grammar, untouched -------------------------------------------------

  @Test
  public void aRepositoryWithNoSlotFileStillRunsItsOwnTriggerFiles() throws Exception {
    // release.yml is additive and always was: a repository that commits none is evaluated by the
    // generic grammar exactly as it was before the feature existed, and its file reaches the row
    // verbatim rather than through a composer.
    seedTrigger(BESPOKE_PATH, BESPOKE);

    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals(BESPOKE_PATH, recorded.get(0).configPath);
    assertEquals(BESPOKE, recorded.get(0).triggerConfig, "the file, verbatim, as it always was");
    assertEquals("alpine:3", fakeRunner.executed().get(0).image());
  }

  // --- a read that did not happen: no run, and the event STAYS OWED ---------------------------------

  @Test
  public void anUnreadableSlotFileRecordsNoRunAndLeavesTheEventOwedForTheSweep() throws Exception {
    // THE CASE THE SPLIT PIPELINE'S RETIREMENT TURNED INTO A DEFECT. While every repository still
    // committed a hand-written pair, an UNREACHABLE read of release.yml was read as "this repository
    // has not migrated" and the evaluation fell through to those files — costing a migrated
    // repository nothing, because it had no such file for the fallback to find. There is no fallback
    // and no such file anywhere now: release.yml IS the release pipeline, so the old reading answers
    // "this repository declares no QA" about a repository whose release request is at that moment
    // waiting for exactly that QA's verdict, settles the owed row, and hangs it PENDING forever with
    // nothing anywhere to re-drive it. One blip, one release.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    fakeConfig.putFileUnreachable(repoId, MERGED_SHA, CiReleaseSlotParser.CONFIG_PATH);

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId), "nothing was learned, so nothing ran");
    assertTrue(
        stillOwed(arrival.eventId()),
        "and the event is NOT settled — the whole point: a sweep is what recovers the QA run");

    // The git host comes back, and the sweep is what a deployed qits-ci runs at boot and on a tick.
    seedSlots("archetype: spa-frontend\n");
    engine.sweepOwed(Instant.now().plusSeconds(60));
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recovered = runService.runsFor(repoId);
    assertEquals(1, recovered.size(), "the composed QA run the release request was owed");
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, recovered.get(0).configPath);
    assertEquals(arrival.eventId(), recovered.get(0).triggerEventId, "under the original event");
    assertFalse(stillOwed(arrival.eventId()), "and the ledger is clear again");
  }

  // --- an event that names no revision: nothing is read, nothing composes, and it IS settled -------

  /**
   * <b>A release event with no sha composes nothing and reads nothing</b>, where it used to compose
   * a release pipeline out of {@code main}.
   *
   * <p>That fallback invented the scenario it then resolved wrongly. qits-projects announces a
   * release request from the fold path alone, and a request whose fold could not be made is
   * CONFLICTED — frozen, never re-folded, never re-announced until a push clears it — so nothing on
   * the live path emits an event with no revision. What the fallback did was turn "nothing should
   * run" into "a pipeline composed from {@code main} ran against a tree this release is not, and
   * reported a verdict about it".
   *
   * <p>The assertion is therefore an ABSENCE as much as a count: no read at {@code main}, no read
   * anywhere, no run. A fallback passes the run count of a suite that only asserts what ran.
   */
  @Test
  public void aReleaseEventThatNamesNoShaComposesNothingAndIsSettled() throws Exception {
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    // And the slot file at main too, so a fallback to main's head would SUCCEED and compose a run.
    // Only the assertions below stand between that and a green suite.
    fakeConfig.putFile(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH, "archetype: spa-frontend\n");

    CiEventTriggerService.Arrival arrival =
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(),
            CiReleaseComposer.RELEASE_REQUEST_EVENT,
            Instant.parse("2026-09-06T09:00:00Z"),
            "{\"repoName\":\"qits-target\",\"backingBranch\":\"release/abc\","
                + "\"releaseRequestId\":\"a1b2c3\"}");
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId), "no revision, no pipeline, no run");
    assertFalse(
        fakeConfig
            .fileReads()
            .contains(repoId + "@" + HEAD + "/" + CiReleaseSlotParser.CONFIG_PATH),
        "and NOTHING is read at main's head — the fallback this removed: " + fakeConfig.fileReads());
    assertTrue(
        fakeConfig.fileReads().stream()
            .noneMatch(read -> read.contains(CiReleaseSlotParser.CONFIG_PATH)),
        "there is nowhere to read the declaration AT, so it is not read at all: "
            + fakeConfig.fileReads());
    assertFalse(
        stillOwed(arrival.eventId()),
        "and the event is settled: a payload cannot grow the field later, so an owed row for it"
            + " would be a row nothing could ever clear");
  }

  /**
   * The same answer for a sha that is THERE and refused, which is the half a "the field is missing"
   * check would miss. {@code CiIdentifiers.requireSha} refuses it, and a refused value must not be
   * quietly downgraded to {@code main}'s head — that is the fallback arriving through the other
   * door, and it would let a hostile payload choose to have main gated in place of the commit it
   * claims to be about.
   */
  @Test
  public void aReleaseEventWhoseShaIsMalformedComposesNothingAndIsSettled() throws Exception {
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    fakeConfig.putFile(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH, "archetype: spa-frontend\n");

    CiEventTriggerService.Arrival arrival =
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(),
            CiReleaseComposer.RELEASE_EVENT,
            Instant.parse("2026-09-06T10:00:00Z"),
            "{\"repository\":\"qits-target\",\"version\":\"2026.906.100732\","
                + "\"commitSha\":\"$(rm -rf /)\"}");
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId), "a refused sha is no revision at all");
    assertFalse(
        fakeConfig
            .fileReads()
            .contains(repoId + "@" + HEAD + "/" + CiReleaseSlotParser.CONFIG_PATH),
        "and never at main's head instead: " + fakeConfig.fileReads());
    assertFalse(stillOwed(arrival.eventId()), "settled, for the same reason the missing half is");
  }

  // --- broken committed content: no run, and the event IS settled -----------------------------------

  @Test
  public void anUnknownArchetypeIsNoRunAndIsSettled() throws Exception {
    // The contrast with the case above, and it is the whole reason that one needs a ledger seam to
    // be asserted at all. This failure is a person's declaration: the slot file names an archetype
    // the wrapper does not carry, and it will name it just as wrongly on the next sweep and the one
    // after. No run — the engine's standing rule that an unreadable candidate is never a run — and
    // the event is settled, because retrying it is asking a git host to change somebody's mind.
    seedSlots("archetype: does-not-exist\n");

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId));
    assertFalse(stillOwed(arrival.eventId()), "broken committed content is final, not retryable");
  }

  @Test
  public void anUnparseableSlotFileIsNoRunAndIsSettled() throws Exception {
    // The same contrast, one failure earlier: these bytes are committed and will not parse today or
    // on any sweep. The fix is a commit, and a commit arrives as its own event.
    seedSlots("archetpye: spa-frontend\n");

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId));
    assertFalse(stillOwed(arrival.eventId()), "broken committed content is final, not retryable");
  }

  @Test
  public void aSlotFileDeclaringNothingForThisEventRecordsNothingForIt() throws Exception {
    // An SPA frontend publishes nothing, so its composed release document does not exist — and "no
    // document" has to mean "no run" rather than a trivially green release that announces a version.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);

    deliver(release());

    assertEquals(List.of(), runService.runsFor(repoId));
  }

  // --- retrying a composed run ----------------------------------------------------------------------

  /** The repository's own release slot: one step, one script, and nothing platform-shaped in it. */
  private static final String OWN_RELEASE_SLOT =
      """
      archetype: java-service
      release:
        - image: alpine:3
          script: ./publish.sh
      """;

  private static String javaService(String sbomPath) {
    return "artifacts:\n  - { type: docker, name: qits/qits-target, sbom: "
        + sbomPath
        + " }\n";
  }

  @Test
  public void aRetryOfAComposedRunRecomposesThePlatformHalfAndKeepsTheRepositorysScript()
      throws Exception {
    // The whole of the ticket: the platform prelude and postlude on a stored composed document are
    // as they were when the run was FIRST composed, so a platform fix could never heal an earlier
    // failed release by retry. Here the wrapper recipe moves between the two runs — one qits-ci-side
    // change to every migrated repository's pipeline — and the retry has to be composed with it.
    seedSlots(OWN_RELEASE_SLOT);
    // The tag the run builds carries the same declaration, which is where the retry reads it: those
    // bytes are part of the released commit and cannot move under the retry.
    fakeConfig.putFile(repoId, RELEASED_SHA, CiReleaseSlotParser.CONFIG_PATH, OWN_RELEASE_SLOT);
    seedArchetype("java-service", javaService("out/sbom.json"));

    deliver(release());
    CiRun original = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.SUCCESS, original.status);
    assertTrue(original.triggerConfig.contains("out/sbom.json"), original.triggerConfig);

    seedArchetype("java-service", javaService("target/sbom.json"));
    CiRun retry = runService.retry(original.id);
    runService.awaitIdle();
    forgetLoadedEntities();

    CiRun refired = runService.requireRun(retry.id);
    assertTrue(
        refired.triggerConfig.contains("target/sbom.json"),
        "the retry carries today's platform postlude: " + refired.triggerConfig);
    assertFalse(
        refired.triggerConfig.contains("out/sbom.json"),
        "and not the one the source run was composed with");
    // The repository's half is the released commit's and does not move with the platform's.
    assertTrue(refired.triggerConfig.contains("./publish.sh"), refired.triggerConfig);
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, refired.configPath);
    assertEquals(RELEASED_SHA, refired.commitSha, "a retry still builds the commit its source built");
  }

  @Test
  public void aRetryWhoseWrapperHasRELEASEDAgainRecordsTheNewVersionWhileTheSourceKeepsTheOld()
      throws Exception {
    // The pair of rows IS the record of the platform fix. The retry re-composes against the wrapper
    // as it is NOW — deliberately, and that escape hatch is not being pinned to the source run's
    // revision — so the only way anybody can see which recipe each of the two runs really ran is
    // that each row names its own.
    //
    // WHAT "NOW" MEANS CHANGED WITH THIS TICKET AND THE TEST SAYS SO. It used to be the wrapper's
    // main head, so a merged fix healed an earlier failure the moment it landed; it is the wrapper's
    // newest RELEASED version, so the fix heals it once the wrapper release carrying it has landed.
    // The fixture therefore moves the wrapper by RELEASING a newer version, and it moves main's head
    // as well — with the fixed recipe on it — so that a regression to the old reading would find the
    // fix at main and pass this test on the wrong evidence.
    seedSlots(OWN_RELEASE_SLOT);
    fakeConfig.putFile(repoId, RELEASED_SHA, CiReleaseSlotParser.CONFIG_PATH, OWN_RELEASE_SLOT);
    seedArchetype("java-service", javaService("out/sbom.json"));

    deliver(release());
    CiRun original = runService.runsFor(repoId).get(0);
    assertEquals("java-service", original.archetypeName);
    assertEquals(
        CiReleaseSlotParser.archetypePath("java-service"), original.archetypeConfigPath);
    assertEquals(WRAPPER_RELEASED_SHA, original.archetypeRev);
    assertEquals(WRAPPER_VERSION, original.archetypeVersion);

    // The wrapper RELEASES the fix: a newer version, at a commit of its own, carrying the fixed
    // recipe. Main's head moves too and carries the same fix — the decoy.
    String releasedSha = "9".repeat(40);
    String newerVersion = "2026.923.101500";
    fakeConfig.putReleasedVersion(wrapperId, newerVersion, releasedSha);
    fakeConfig.putFile(
        wrapperId,
        releasedSha,
        CiReleaseSlotParser.archetypePath("java-service"),
        javaService("target/sbom.json"));
    seedArchetypeAtMainHead("java-service", javaService("target/sbom.json"));

    CiRun retry = runService.retry(original.id);
    runService.awaitIdle();
    forgetLoadedEntities();

    CiRun refired = runService.requireRun(retry.id);
    assertEquals(releasedSha, refired.archetypeRev, "the retry records the wrapper as it is NOW");
    assertEquals(
        newerVersion, refired.archetypeVersion, "which is a RELEASE, and the row names it");
    assertEquals("java-service", refired.archetypeName);
    assertTrue(refired.triggerConfig.contains("target/sbom.json"), refired.triggerConfig);
    // And the source row is untouched, which is what makes the comparison possible at all.
    CiRun sourceAgain = runService.requireRun(original.id);
    assertEquals(WRAPPER_RELEASED_SHA, sourceAgain.archetypeRev);
    assertEquals(WRAPPER_VERSION, sourceAgain.archetypeVersion);
    assertEquals(RELEASED_SHA, refired.commitSha, "one commit, two recipes — the whole point");
  }

  // --- one evaluation, one wrapper revision ---------------------------------------------------------

  @Test
  public void twoCandidatesOnOneArchetypeReadItOnceAtTheResolvedSha() throws Exception {
    // The discipline this ticket is about, from both ends. The rev is the sha of ONE released
    // wrapper version — NOT the string "main", and not a version resolved per candidate — and two
    // repositories on one archetype in one evaluation cannot see two recipes, because the version is
    // resolved once for the evaluation and the read is made once at its sha.
    String secondId = "second-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(
        CiRepoRef.of(repoId, "qits", "qits-target"),
        CiRepoRef.of(secondId, "qits", "qits-second"),
        CiRepoRef.of(wrapperId, "qits", "qits-qits"));
    String secondHead = "b".repeat(40);
    fakeConfig.putTriggers(secondId, "main", secondHead);
    seedSlots("archetype: spa-frontend\n");
    // The second candidate's slot file at the SAME revision, because the revision comes from the
    // event rather than from the repository: every candidate of one release event is read at the
    // commit that event names. In production only the repository the event is about holds it, and
    // every other candidate answers ABSENT — which is the same "no document, no run" it reaches
    // through its `when:` a moment later.
    fakeConfig.putFile(
        secondId, MERGED_SHA, CiReleaseSlotParser.CONFIG_PATH, "archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);

    // One event both repositories' composed QA pipelines select: the composer's `when:` is each
    // repository's own name, so the payload has to name one — the second is the one that matters
    // here, and the first still reads the archetype on its way to not matching.
    deliver(releaseRequest());

    List<String> archetypeReads =
        fakeConfig.fileReads().stream()
            .filter(read -> read.contains(CiReleaseSlotParser.archetypePath("spa-frontend")))
            .toList();
    assertEquals(
        List.of(archetypeReadAt(WRAPPER_RELEASED_SHA, "spa-frontend")),
        archetypeReads,
        "one read, at the resolved sha, for both candidates: " + fakeConfig.fileReads());
    assertFalse(
        fakeConfig.fileReads().contains(archetypeReadAt("main", "spa-frontend")),
        "and never at the moving ref: " + fakeConfig.fileReads());
    // AND THE VERSION ITSELF IS RESOLVED ONCE. Reading the wrapper's tags per candidate would answer
    // the same sha in this fixture and a different one the day a release lands mid-evaluation, which
    // is exactly the state nothing on a row could explain afterwards.
    assertEquals(
        List.of(wrapperId),
        fakeConfig.tagReads(),
        "one tag read for the whole evaluation: " + fakeConfig.tagReads());
  }

  // --- the recipe comes from a RELEASE, never from the wrapper's main head --------------------------

  @Test
  public void theArchetypeIsReadAtTheNewestReleasedVersionAndNotAtTheWrappersMainHead()
      throws Exception {
    // THE WHOLE OF THIS TICKET, and it is asserted with the two revisions carrying DIFFERENT
    // recipes, because that is the only shape that can tell them apart: a fixture where main and the
    // released tag agree passes whichever one the engine reads.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype(
        "spa-frontend",
        """
        release-request:
          - image: qits/build-images/node-base:latest
            script: echo approved
        """);
    // The wrapper's main carries a recipe somebody merged a minute ago and nobody released. It is a
    // perfectly readable file, and reading it is the defect.
    seedArchetypeAtMainHead(
        "spa-frontend",
        """
        release-request:
          - image: qits/build-images/node-base:latest
            script: echo unreviewed
        """);

    deliver(releaseRequest());

    CiRun run = runService.runsFor(repoId).get(0);
    assertTrue(run.triggerConfig.contains("echo approved"), run.triggerConfig);
    assertFalse(
        run.triggerConfig.contains("echo unreviewed"),
        "the steps of a release pipeline may not come from whatever is on the wrapper's main: "
            + run.triggerConfig);
    assertEquals(WRAPPER_RELEASED_SHA, run.archetypeRev);
    assertEquals(WRAPPER_VERSION, run.archetypeVersion);
    assertFalse(
        fakeConfig.fileReads().contains(archetypeReadAt(WRAPPER_HEAD, "spa-frontend")),
        "and the wrapper's main head is never even READ for a recipe: " + fakeConfig.fileReads());
  }

  @Test
  public void theNEWESTReleasedVersionWinsAndUnversionedTagsAreNotReleases() throws Exception {
    // Which tag is "the newest release" is a decision with two ways to get it wrong, and both are
    // staged here. `98` sorts after `161358` as TEXT and before it as a VERSION, which is what
    // VersionSort exists for; and `latest`/`v2` are tags anybody can push, so admitting them would
    // let an unreviewed name out-sort every real release and pin the estate's recipes to it.
    String olderSha = "1".repeat(40);
    String newestSha = "2".repeat(40);
    String decoySha = "3".repeat(40);
    fakeConfig.putTags(
        wrapperId,
        new CiConfigSource.RepoTag("2026.922.98", olderSha),
        new CiConfigSource.RepoTag("2026.922.161358", newestSha),
        new CiConfigSource.RepoTag("latest", decoySha),
        new CiConfigSource.RepoTag("v2", decoySha),
        new CiConfigSource.RepoTag("release/2026.999.999999", decoySha));
    seedSlots("archetype: spa-frontend\n");
    fakeConfig.putFile(
        wrapperId, newestSha, CiReleaseSlotParser.archetypePath("spa-frontend"), SPA_FRONTEND);
    // The two losers carry a readable recipe as well, so picking either of them composes a run and
    // only the assertions below say which one was picked.
    fakeConfig.putFile(
        wrapperId, olderSha, CiReleaseSlotParser.archetypePath("spa-frontend"), SPA_FRONTEND);
    fakeConfig.putFile(
        wrapperId, decoySha, CiReleaseSlotParser.archetypePath("spa-frontend"), SPA_FRONTEND);

    deliver(releaseRequest());

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(newestSha, run.archetypeRev, "the highest VERSION, not the highest string");
    assertEquals("2026.922.161358", run.archetypeVersion);
  }

  // --- no released wrapper version at all: fail closed, never back to "main" -------------------------

  @Test
  public void aWrapperWhoseTagsCannotBeReadIsNoRunAndNoReadAtMain() throws Exception {
    // A TRANSPORT FAILURE IS OWED, which is the easy half of the decision: nothing was learned about
    // the wrapper, and a sweep can learn it. The assertion is also an ABSENCE, because a fallback to
    // the branch name or to main's head passes every other test in this suite.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    seedArchetypeAtMainHead("spa-frontend", SPA_FRONTEND);
    fakeConfig.putFile(
        wrapperId, "main", CiReleaseSlotParser.archetypePath("spa-frontend"), SPA_FRONTEND);
    fakeConfig.putTagsUnreachable(wrapperId);

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId), "no released version, no recipe, no run");
    assertFalse(
        fakeConfig.fileReads().contains(archetypeReadAt("main", "spa-frontend")),
        "NOTHING may be read at the literal 'main' — a silent fallback is the regression: "
            + fakeConfig.fileReads());
    assertFalse(
        fakeConfig.fileReads().contains(archetypeReadAt(WRAPPER_HEAD, "spa-frontend")),
        "nor at the sha main resolves to: " + fakeConfig.fileReads());
    assertTrue(
        stillOwed(arrival.eventId()),
        "and the event is owed: nothing was learned about the wrapper, so a sweep asks again");
  }

  @Test
  public void aWrapperThatHasNEVERReleasedIsNoRunAndTheEventIsOWED() throws Exception {
    // THE DECISION THIS TICKET HAD TO MAKE, and it is the interesting half. A wrapper whose tags
    // read perfectly well and carry no released version is not a transport failure — but it is not a
    // person's declaration either, which is what "settled" is reserved for here. Nobody wrote "this
    // estate has no approved recipes"; the estate has simply not released its wrapper yet, and the
    // very next wrapper release makes the same question answer differently with nothing in the
    // candidate repository having moved. So it is OWED, exactly like an unreadable release.yml:
    // settling it would answer "no release run" to a release request waiting for that run's verdict,
    // with nothing anywhere to re-drive it.
    seedSlots("archetype: spa-frontend\n");
    seedArchetypeAtMainHead("spa-frontend", SPA_FRONTEND);
    fakeConfig.putTags(wrapperId, new CiConfigSource.RepoTag("nightly", "7".repeat(40)));

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId), "no approved recipe, no release run");
    assertFalse(
        fakeConfig.fileReads().contains(archetypeReadAt(WRAPPER_HEAD, "spa-frontend")),
        "and the unreleased recipe on main is NOT the fallback: " + fakeConfig.fileReads());
    assertTrue(
        stillOwed(arrival.eventId()),
        "owed, not settled: the wrapper's first release is what makes this answerable, and the"
            + " release request must still be there to receive its verdict when it lands");

    // And that is exactly what recovery looks like: the wrapper releases, the sweep runs, the run
    // the release request was owed is recorded under the original event.
    fakeConfig.putReleasedVersion(wrapperId, WRAPPER_VERSION, WRAPPER_RELEASED_SHA);
    seedArchetype("spa-frontend", SPA_FRONTEND);
    engine.sweepOwed(Instant.now().plusSeconds(60));
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recovered = runService.runsFor(repoId);
    assertEquals(1, recovered.size(), "the first wrapper release unblocks the estate");
    assertEquals(WRAPPER_VERSION, recovered.get(0).archetypeVersion);
    assertFalse(stillOwed(arrival.eventId()));
  }

  @Test
  public void aSlotFileNamingNoArchetypeComposesEvenWhenTheWrapperHasNeverReleased()
      throws Exception {
    // THE BOOTSTRAP ESCAPE, and it is why "no released wrapper version" does not deadlock an estate.
    // Only a repository that ASKS for a recipe needs one. The wrapper's own release.yml declares its
    // single QA slot itself and names no archetype: — so the wrapper's own release request composes,
    // gates and can be approved with no archetype read anywhere, and that first wrapper release is
    // what makes every other repository's release pipeline composable.
    fakeConfig.putTags(wrapperId);
    seedSlots(
        """
        release-request:
          - image: alpine:3
            script: echo estate declaration
        """);

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "a slot file that asks for no recipe needs no released version");
    assertNull(recorded.get(0).archetypeVersion);
    assertFalse(stillOwed(arrival.eventId()), "and nothing is left owed on the wrapper's account");
  }

  // --- the wrapper could not be listed: the PLATFORM TRIGGER half, which is still read at main ------

  @Test
  public void aWrapperThatCannotBeListedStillComposesFromItsReleasedVersion() throws Exception {
    // THE TWO READS OF THE WRAPPER ARE INDEPENDENT NOW, and this is what says so. The platform
    // trigger listing is made at main and answers which ci-platform-event-*.yml files exist; the
    // archetype recipe is read at the newest released version. A listing that could not be made
    // costs the platform trigger pass and nothing else — the recipe is somewhere the listing was
    // never consulted about, and refusing to compose over it would be refusing on unrelated
    // evidence.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    fakeConfig.putTriggersUnreachable(wrapperId, "main", CiTriggerScope.PLATFORM);

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "the release pipeline composes from the approved recipe");
    assertEquals(WRAPPER_VERSION, recorded.get(0).archetypeVersion);
  }

  // --- what the row records ------------------------------------------------------------------------

  @Test
  public void aBespokeRunRecordsNoArchetypeAtAll() throws Exception {
    // Null is a statement here and not a gap: this run was composed from nothing, so there is no
    // recipe and no revision for it to name. The same three nulls a composed run whose slot file
    // declares its own slots carries — see below — and never "unknown".
    seedTrigger(BESPOKE_PATH, BESPOKE);

    deliver(releaseRequest());

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(BESPOKE_PATH, run.configPath);
    assertNull(run.archetypeName);
    assertNull(run.archetypeConfigPath);
    assertNull(run.archetypeRev);
    assertNull(run.archetypeVersion);
  }

  @Test
  public void aComposedRunNamingNoArchetypeRecordsNoneEither() throws Exception {
    // The shape four repositories on the estate are in: a slot file that declares both its halves
    // itself. It composes, it runs, and it names no recipe — which must not be read as "qits-ci
    // could not work out which recipe this was".
    seedSlots(
        """
        release-request:
          - image: alpine:3
            script: echo qa
        """);

    deliver(releaseRequest());

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, run.configPath);
    assertNull(run.archetypeName, "a composition with no archetype names none");
    assertNull(run.archetypeConfigPath);
    assertNull(run.archetypeRev);
    assertNull(run.archetypeVersion);
  }

  @Test
  public void aRetryOfAComposedRunWhoseSlotFileCannotBeReadReplaysTheStoredPipeline()
      throws Exception {
    // A retry that refused because the platform could not recompose is worse than a retry of the old
    // document: the run being re-fired is the one thing the caller definitely has.
    seedSlots(OWN_RELEASE_SLOT);
    seedArchetype("java-service", javaService("out/sbom.json"));

    deliver(release());
    CiRun original = runService.runsFor(repoId).get(0);
    String composed = original.triggerConfig;

    fakeConfig.putFileUnreachable(repoId, RELEASED_SHA, CiReleaseSlotParser.CONFIG_PATH);
    CiRun retry = runService.retry(original.id);
    assertEquals(
        CiRunStatus.QUEUED,
        retry.status,
        "the retry is accepted regardless — the fallback is the stored document, not a refusal");

    runService.awaitIdle();
    forgetLoadedEntities();
    CiRun refired = runService.requireRun(retry.id);
    assertEquals(composed, refired.triggerConfig, "byte for byte, the pipeline the source ran");
    assertEquals(CiRunStatus.SUCCESS, refired.status);
  }
}
