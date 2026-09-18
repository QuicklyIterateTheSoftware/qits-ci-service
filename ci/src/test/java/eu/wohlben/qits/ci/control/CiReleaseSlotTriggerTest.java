package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 */
@QuarkusTest
public class CiReleaseSlotTriggerTest extends CiTestSupport {

  private static final String HEAD = "c".repeat(40);

  private static final String WRAPPER_HEAD = "d".repeat(40);

  /** The commit the release event names, and therefore the ref a composed release run builds. */
  private static final String RELEASED_SHA = "f".repeat(40);

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
            + "e".repeat(40)
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

  private void seedSlots(String content) {
    fakeConfig.putFile(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH, content);
  }

  private void seedArchetype(String name, String content) {
    fakeConfig.putFile(
        wrapperId, "main", CiReleaseSlotParser.archetypePath(name), content);
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
    // Decided at main, built at the fold — the composed checkout resolves out of the payload exactly
    // as a committed one does, through the same code.
    assertEquals("release/abc", run.branch);
    assertEquals("e".repeat(40), run.commitSha);
    assertEquals("a1b2c3", run.releaseRequestId, "the provenance column is unaffected by composition");
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
    // The gate that keeps this feature free for the other 99% of the bus: no blob read at all.
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
    fakeConfig.putFileUnreachable(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH);

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
