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
 * parser, the archetype resolution (the repository's own copy through the {@link CiConfigSource}
 * port, otherwise the recipes really packaged into this module's jar), the composer, the trigger parser reading the composed text back, the run service and the unique
 * constraint. What is faked is the git host and the frame.
 *
 * <p><b>The contrast this class exists to hold is the last two sections' against each other.</b>
 * Broken committed content — an archetype that exists nowhere, a local recipe or a slot file that
 * will not parse — is
 * no run <em>and the event is settled</em>: a person declared that, the declaration is final, and
 * retrying it forever would be asking a git host to change somebody's mind. A slot file that could
 * not be READ — or a look for a local recipe that could not be made — is no run and the event
 * <em>stays owed</em>: nothing was learned, and every repository
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
 * claimed: the half of a composed pipeline that is <em>platform process</em> — the prelude and the
 * postlude — is {@code CiReleaseComposer}'s, in Java, and no file in any repository can reach it.
 *
 * <h2>Where a recipe comes from, which is what the last sections are about</h2>
 *
 * <p><b>An archetype is the repository's own {@code .config/qits/release-archetypes/<name>.yml} at
 * the event's revision, and otherwise the recipe packaged into this qits-ci.</b> {@link
 * #seedArchetype} therefore seeds the <em>candidate</em>, at the fold and the tag — a shadow — and
 * a test that seeds none composes from the real packaged set, which is on this module's classpath
 * exactly as it is on the service's.
 *
 * <p><b>The platform-pipelines repository is never read for a recipe</b>, where until qits-583 it
 * was the only place one came from (at its newest released tag). The wrapper is still armed in every
 * fixture here, and {@link #noReadOfThePlatformPipelinesRepositoryIsEverMadeForAnArchetype} seeds
 * readable decoy recipes in it at every revision the old reads used: a regression composes
 * successfully from them, and only an assertion about WHICH bytes ran and which reads were made
 * can catch it.
 */
@QuarkusTest
public class CiReleaseSlotTriggerTest extends CiTestSupport {

  private static final String HEAD = "c".repeat(40);

  /**
   * The platform-pipelines repository's {@code main} head. Its platform trigger listing resolves
   * here; no archetype recipe is ever read from that repository, at this revision or any other.
   */
  private static final String WRAPPER_HEAD = "d".repeat(40);

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

  /**
   * A repository's OWN recipe under a name the platform also packages — a shadow. The script is one
   * the packaged {@code spa-frontend} does not contain, so which of the two composed a run is
   * readable off the run's stored document.
   */
  private static final String SPA_FRONTEND =
      """
      release-request:
        - image: qits/build-images/node-base:latest
          script: npm ci && npm run build-the-shadow
      """;

  /** What a wrapper-seeded decoy recipe runs, so a composition from one is unmistakable. */
  private static final String DECOY =
      """
      release-request:
        - image: alpine:3
          script: echo read-from-the-wrapper
      """;

  /** This qits-ci's own version, which is what a packaged recipe is recorded as. */
  private static String thisVersion() {
    return org.eclipse.microprofile.config.ConfigProvider.getConfig()
        .getOptionalValue("quarkus.application.version", String.class)
        .orElse(null);
  }

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
    // THE WRAPPER'S OWN LISTING, which is the platform TRIGGER half and the only thing the engine
    // reads that repository for: a ci-platform-event-*.yml is a trigger, discovered at main's head.
    // It is armed in every test here so that "the wrapper is never read for a recipe" is asserted
    // against a wrapper that is really there.
    fakeConfig.putTriggers(wrapperId, "main", CiTriggerScope.PLATFORM, WRAPPER_HEAD);
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
   * The repository's OWN copy of a recipe, at the two revisions the two release events name — the
   * same two {@link #seedSlots} uses, because a local recipe is read at the revision its {@code
   * release.yml} was.
   */
  private void seedArchetype(String name, String content) {
    fakeConfig.putFile(repoId, MERGED_SHA, CiReleaseSlotParser.archetypePath(name), content);
    fakeConfig.putFile(repoId, RELEASED_SHA, CiReleaseSlotParser.archetypePath(name), content);
  }

  /** The key {@code FakeCiConfigSource} records a local recipe read under. */
  private String archetypeReadAt(String rev, String name) {
    return repoId + "@" + rev + "/" + CiReleaseSlotParser.archetypePath(name);
  }

  /**
   * Every read of a RECIPE made against the platform-pipelines repository. Its own {@code
   * release.yml} is not one: the wrapper is a candidate like any other and is asked for its slot
   * file at the event's revision like any other.
   */
  private List<String> wrapperFileReads() {
    return fakeConfig.fileReads().stream()
        .filter(read -> read.startsWith(wrapperId + "@"))
        .filter(read -> read.contains(CiReleaseSlotParser.ARCHETYPE_DIR))
        .toList();
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
  public void aPackagedArchetypeRecordsItsNameItsPathAndThisQitsCisVersionAndNoRev()
      throws Exception {
    // THE ORDINARY CASE ON THE ESTATE: the slot file is one line and the repository carries no
    // recipe of its own, so the one built into this qits-ci composes. Nothing is seeded but the slot
    // file — the recipe is the real packaged spa-frontend, off this module's classpath.
    seedSlots("archetype: spa-frontend\n");

    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "the packaged recipe composes: " + fakeConfig.fileReads());
    CiRun run = recorded.get(0);
    assertEquals("spa-frontend", run.archetypeName);
    assertEquals(CiReleaseSlotParser.archetypePath("spa-frontend"), run.archetypeConfigPath);
    assertNull(run.archetypeRev, "no revision of any repository: it was not read from one");
    assertNotNull(thisVersion(), "this suite can name the application's version");
    assertEquals(
        thisVersion(),
        run.archetypeVersion,
        "and the qits-ci release whose jar carried the recipe, which is what tells two rows apart");
    assertEquals(
        List.of(archetypeReadAt(MERGED_SHA, "spa-frontend")),
        fakeConfig.fileReads().stream()
            .filter(read -> read.contains(CiReleaseSlotParser.ARCHETYPE_DIR))
            .toList(),
        "the repository is asked ONCE whether it shadows the recipe, at the fold, and nothing else"
            + " is read for it: "
            + fakeConfig.fileReads());
  }

  @Test
  public void aSlotFileNamingAnArchetypeTheRepositoryCarriesRecordsAComposedQaRunFromItsOwnCopy()
      throws Exception {
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
    // WHICH RECIPE, AND WHOSE. The repository carries its own copy, so the row records the revision
    // that copy was read at — the fold, which is the run's own commit — and NO version: a version
    // is what a packaged recipe has, and rev non-null is how a reader tells "shadowed locally".
    assertEquals("spa-frontend", run.archetypeName);
    assertEquals(CiReleaseSlotParser.archetypePath("spa-frontend"), run.archetypeConfigPath);
    assertEquals(MERGED_SHA, run.archetypeRev);
    assertEquals(run.commitSha, run.archetypeRev, "declaration and recipe are one commit's bytes");
    assertNull(run.archetypeVersion, "a local recipe is not any qits-ci release's");
    // trigger_config is the COMPOSED text, which is what restart-reparse will read back.
    assertNotNull(run.triggerConfig);
    assertTrue(run.triggerConfig.contains("event: ReleaseRequestChanged"), run.triggerConfig);
    assertTrue(
        run.triggerConfig.contains("npm ci && npm run build-the-shadow"),
        "the LOCAL recipe wins over the packaged one of the same name: " + run.triggerConfig);
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
  public void theSlotFileIsNeverReadAtMainForAReleaseEventAndALocalRecipeIsReadAtTheFold()
      throws Exception {
    // Asserted as an absence because nothing else catches the regression: a read of the
    // repository's release.yml at main passes every other test in this class the day somebody
    // re-seeds main. And the recipe half of the same invariant — a local recipe is read at the
    // revision the declaration was, never at the repository's main head.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    fakeConfig.putFile(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH, "archetype: spa-frontend\n");
    fakeConfig.putFile(repoId, HEAD, CiReleaseSlotParser.archetypePath("spa-frontend"), DECOY);

    deliver(releaseRequest());

    assertEquals(1, runService.runsFor(repoId).size());
    assertFalse(
        fakeConfig
            .fileReads()
            .contains(repoId + "@" + HEAD + "/" + CiReleaseSlotParser.CONFIG_PATH),
        "the repository's own declaration is read at the fold and nowhere else: "
            + fakeConfig.fileReads());
    assertEquals(
        List.of(archetypeReadAt(MERGED_SHA, "spa-frontend")),
        fakeConfig.fileReads().stream()
            .filter(read -> read.contains(CiReleaseSlotParser.ARCHETYPE_DIR))
            .toList(),
        "and its recipe at that same fold: " + fakeConfig.fileReads());
  }

  @Test
  public void noReadOfThePlatformPipelinesRepositoryIsEverMadeForAnArchetype() throws Exception {
    // THE WHOLE OF qits-583, asserted as an ABSENCE with decoys, because a regression here passes
    // every other test in this class. The wrapper carries a perfectly readable recipe under BOTH
    // names at every revision the old reads ever used — the branch name, main's head, the fold's
    // sha, and a released tag's commit — so an engine that still went there would compose
    // successfully, and only "which bytes ran" and "which reads were made" can tell.
    String wrapperTagSha = "a".repeat(40);
    for (String rev : List.of("main", WRAPPER_HEAD, MERGED_SHA, wrapperTagSha)) {
      fakeConfig.putFile(wrapperId, rev, CiReleaseSlotParser.archetypePath("spa-frontend"), DECOY);
      fakeConfig.putFile(wrapperId, rev, CiReleaseSlotParser.archetypePath("wrapper-only"), DECOY);
    }

    // A name the platform packages: composed from the packaged recipe, not the wrapper's decoy.
    seedSlots("archetype: spa-frontend\n");
    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertFalse(
        recorded.get(0).triggerConfig.contains("read-from-the-wrapper"),
        "the wrapper's recipe composed this run: " + recorded.get(0).triggerConfig);
    assertNull(recorded.get(0).archetypeRev);
    assertEquals(
        List.of(), wrapperFileReads(), "no recipe is read from the wrapper: " + fakeConfig.fileReads());

    // And a name ONLY the wrapper carries is no archetype at all — no run, and settled, where an
    // engine that still read the wrapper would have composed the decoy.
    seedSlots("archetype: wrapper-only\n");
    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(1, runService.runsFor(repoId).size(), "no second run: the name exists nowhere");
    assertFalse(stillOwed(arrival.eventId()), "an unknown archetype is final, not retryable");
    assertEquals(
        List.of(), wrapperFileReads(), "still none: " + fakeConfig.fileReads());
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
    // The gate that keeps this feature free for the other 99% of the bus: no blob read at all —
    // not the slot file, and so not the look for a local recipe behind it either.
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
    assertTrue(
        fakeConfig.fileReads().stream()
            .noneMatch(read -> read.contains(CiReleaseSlotParser.ARCHETYPE_DIR)),
        "and no recipe is looked for either: " + fakeConfig.fileReads());
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

  @Test
  public void aLocalArchetypeThatCannotBeLookedForIsOwedAndIsNotAnsweredFromThePackagedCopy()
      throws Exception {
    // The slot file's own case, one read later. The name IS packaged — so an engine that fell
    // through on a blip would compose a run here, from a recipe the repository may have replaced,
    // and which pipeline a commit got would depend on whether the git host answered. Nothing was
    // learned about whether the repository shadows the recipe, so nothing is composed and the event
    // stays owed.
    seedSlots("archetype: spa-frontend\n");
    fakeConfig.putFileUnreachable(
        repoId, MERGED_SHA, CiReleaseSlotParser.archetypePath("spa-frontend"));

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(
        List.of(), runService.runsFor(repoId), "NOT the packaged recipe: nothing was learned");
    assertTrue(stillOwed(arrival.eventId()), "owed, so a sweep asks the git host again");

    // The git host comes back and says the repository carries its own recipe after all.
    seedArchetype("spa-frontend", SPA_FRONTEND);
    engine.sweepOwed(Instant.now().plusSeconds(60));
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recovered = runService.runsFor(repoId);
    assertEquals(1, recovered.size(), "the QA run the release request was owed");
    assertEquals(MERGED_SHA, recovered.get(0).archetypeRev, "composed from the shadow it has");
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
    // that is neither in the repository at the fold nor packaged into this qits-ci, and it will name
    // it just as wrongly on the next sweep and the one after. No run — the engine's standing rule that an unreadable candidate is never a run — and
    // the event is settled, because retrying it is asking a git host to change somebody's mind.
    seedSlots("archetype: does-not-exist\n");

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId));
    assertFalse(stillOwed(arrival.eventId()), "broken committed content is final, not retryable");
  }

  @Test
  public void aLocalArchetypeThatDoesNotParseIsNoRunSettledAndNotThePackagedCopy()
      throws Exception {
    // A BROKEN SHADOW IS FINAL. The repository replaced spa-frontend and the replacement is not a
    // recipe; the platform packages a perfectly good one under that name. Composing from it instead
    // would run a pipeline the repository explicitly did not ask for and report it green — so: no
    // run, and settled, because these bytes are committed at the fold and a sweep cannot change them.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", "archetype: another\n");

    CiEventTriggerService.Arrival arrival = releaseRequest();
    deliverThroughTheLedger(arrival);

    assertEquals(List.of(), runService.runsFor(repoId), "not the packaged recipe in its place");
    assertFalse(stillOwed(arrival.eventId()), "broken committed content is final, not retryable");
  }

  @Test
  public void aRecipeOnlyTheRepositoryCarriesComposes() throws Exception {
    // An archetype of the repository's own invention: a name this qits-ci packages nothing under.
    seedSlots("archetype: house-recipe\n");
    seedArchetype("house-recipe", SPA_FRONTEND);

    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals("house-recipe", recorded.get(0).archetypeName);
    assertEquals(MERGED_SHA, recorded.get(0).archetypeRev);
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
  public void aRetryOfAComposedRunRecomposesAndReReadsTheLocalArchetypeAtTheRunsCommit()
      throws Exception {
    // A retry does not replay its stored document: it re-composes, so that a fix to the platform's
    // half — the prelude and postlude in CiReleaseComposer, and a packaged recipe — reaches an
    // earlier failed release. What this pins is the REPOSITORY's half of that: the slot file and the
    // local recipe are both read again, at the run's own commit and nowhere else.
    //
    // The fixture changes the recipe's bytes at that commit between the two runs, which no git host
    // can do — a commit's bytes do not move. It is the only way a test can SEE that the retry read
    // the file again rather than reusing what the source run was composed with.
    seedSlots(OWN_RELEASE_SLOT);
    seedArchetype("java-service", javaService("out/sbom.json"));

    deliver(release());
    CiRun original = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.SUCCESS, original.status);
    assertTrue(original.triggerConfig.contains("out/sbom.json"), original.triggerConfig);
    assertEquals(RELEASED_SHA, original.archetypeRev);

    seedArchetype("java-service", javaService("target/sbom.json"));
    CiRun retry = runService.retry(original.id);
    runService.awaitIdle();
    forgetLoadedEntities();

    CiRun refired = runService.requireRun(retry.id);
    assertTrue(
        refired.triggerConfig.contains("target/sbom.json"),
        "the retry was composed again, from what the commit carries now: " + refired.triggerConfig);
    assertFalse(
        refired.triggerConfig.contains("out/sbom.json"),
        "and not replayed from the source run's stored document");
    // The repository's own slot is the released commit's too.
    assertTrue(refired.triggerConfig.contains("./publish.sh"), refired.triggerConfig);
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, refired.configPath);
    assertEquals(RELEASED_SHA, refired.commitSha, "a retry still builds the commit its source built");
    assertEquals(RELEASED_SHA, refired.archetypeRev, "and reads its local recipe at that commit");
    assertNull(refired.archetypeVersion);
    assertEquals(
        2,
        fakeConfig.fileReads().stream()
            .filter(read -> read.equals(archetypeReadAt(RELEASED_SHA, "java-service")))
            .count(),
        "one read for the run, one for its retry, both at the run's commit: "
            + fakeConfig.fileReads());
  }

  @Test
  public void aRetryOfAPackagedCompositionIsComposedFromThisQitsCisRecipeAndRecordsItsVersion()
      throws Exception {
    // The platform's half of a retry is TODAY's: the packaged recipe is this process's, not
    // whatever the source run was composed with. Inside one process the two are the same bytes, so
    // what is assertable here is the provenance — the retry records its own composition (a null rev
    // and this qits-ci's version) rather than copying the source row's columns, which is what makes
    // a differing archetype_version on the two rows mean "a newer qits-ci composed the retry".
    seedSlots(
        """
        archetype: spa-frontend
        release:
          - image: alpine:3
            script: ./publish.sh
        """);

    deliver(release());
    CiRun original = runService.runsFor(repoId).get(0);
    assertNull(original.archetypeRev);
    assertEquals(thisVersion(), original.archetypeVersion);

    // The source row is rewritten to look like an older qits-ci composed it.
    QuarkusTransaction.requiringNew()
        .run(() -> runs.update("archetypeVersion = ?1 where id = ?2", "2026.101.1", original.id));
    forgetLoadedEntities();

    CiRun retry = runService.retry(original.id);
    runService.awaitIdle();
    forgetLoadedEntities();

    CiRun refired = runService.requireRun(retry.id);
    assertEquals("spa-frontend", refired.archetypeName);
    assertNull(refired.archetypeRev);
    assertEquals(
        thisVersion(), refired.archetypeVersion, "the retry names the qits-ci that composed IT");
    assertEquals("2026.101.1", runService.requireRun(original.id).archetypeVersion);
    assertEquals(RELEASED_SHA, refired.commitSha, "one commit, two compositions");
  }

  // --- what the row records ------------------------------------------------------------------------

  @Test
  public void aBespokeRunRecordsNoArchetypeAtAll() throws Exception {
    // Null is a statement here and not a gap: this run was composed from nothing, so there is no
    // recipe and no revision for it to name. The same four nulls a composed run whose slot file
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
    // A slot file that declares its slots itself, as the wrapper's own does. It composes, it runs, and it names no recipe — which must not be read as "qits-ci
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
