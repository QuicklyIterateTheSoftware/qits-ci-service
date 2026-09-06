package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
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
 * .config/qits/release.yml} gets its two release pipelines <b>composed</b>, and the legacy trigger
 * files it may still carry are skipped rather than fired beside them.
 *
 * <p>Everything below the bus is real, exactly as in {@code CiEventTriggerServiceTest}: the slot
 * parser, the archetype read through the same {@link CiConfigSource} port the platform pipelines use,
 * the composer, the trigger parser reading the composed text back, the run service and the unique
 * constraint. What is faked is the git host and the frame.
 *
 * <p>The case that matters most is the last one: <b>a repository with no slot file behaves exactly
 * as it did before this feature existed</b>. That is what makes shipping the engine ahead of the
 * fleet safe, and it is worth an assertion rather than an argument.
 */
@QuarkusTest
public class CiReleaseSlotTriggerTest extends CiTestSupport {

  private static final String HEAD = "c".repeat(40);

  private static final String WRAPPER_HEAD = "d".repeat(40);

  private static final String LEGACY_QA_PATH = ".config/qits/ci-event-release-request.yml";

  private static final String LEGACY_RELEASE_PATH = ".config/qits/ci-event-release.yml";

  /** The shape a repository really commits today — the thing release.yml replaces. */
  private static final String LEGACY_QA =
      """
      event: ReleaseRequestChanged
      when:
        - repoName: { exact: qits-target }
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: echo legacy-qa
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
            + "f".repeat(40)
            + "\"}");
  }

  private void seedSlots(String content) {
    fakeConfig.putFile(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH, content);
  }

  private void seedArchetype(String name, String content) {
    fakeConfig.putFile(
        wrapperId, "main", CiReleaseSlotParser.archetypePath(name), content);
  }

  private void seedLegacy(String path, String content) {
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(path, content));
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
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
    assertEquals("f".repeat(40), recorded.get(0).commitSha);

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

  // --- precedence ---------------------------------------------------------------------------------

  @Test
  public void theSlotFileSupersedesTheLegacyReleaseTriggers() throws Exception {
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    seedLegacy(LEGACY_QA_PATH, LEGACY_QA);

    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    // ONE run, not two. Both files match this event and would each be a run under the ordinary
    // "two files, two pipelines" rule; the whole point of the migration window is that the legacy
    // one is skipped instead — loudly, with a WARN naming both paths.
    assertEquals(1, recorded.size());
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, recorded.get(0).configPath);
    assertTrue(
        fakeRunner.executed().get(0).image().endsWith("qits/build-images/node-base:latest"),
        fakeRunner.executed().get(0).image());
  }

  @Test
  public void anUnrelatedTriggerFileIsUnaffected() throws Exception {
    // Only the two canonical release paths are superseded. The generic mechanism survives as the
    // escape hatch it is, and a repository's bump pipeline is nobody's business but its own.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(LEGACY_QA_PATH, LEGACY_QA),
        new EventTriggerFile(
            ".config/qits/ci-event-upstream.yml",
            """
            event: ReleaseRequestChanged
            when:
              - repoName: { exact: qits-target }
            steps:
              - image: alpine:3
                script: echo bespoke
            """));

    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(2, recorded.size(), "the composed pipeline and the repository's own bespoke one");
    assertTrue(
        recorded.stream()
            .anyMatch(run -> ".config/qits/ci-event-upstream.yml".equals(run.configPath)));
    assertTrue(
        recorded.stream()
            .anyMatch(run -> CiReleaseSlotParser.CONFIG_PATH.equals(run.configPath)));
  }

  @Test
  public void anOrdinaryEventNeitherReadsTheSlotFileNorSkipsAnything() throws Exception {
    // The gate that keeps this feature free for the other 99% of the bus: no blob read at all.
    seedSlots("archetype: spa-frontend\n");
    seedLegacy(
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

  // --- no slot file: byte-identical legacy behaviour ------------------------------------------------

  @Test
  public void aRepositoryWithNoSlotFileRunsItsLegacyPipelineExactlyAsBefore() throws Exception {
    seedLegacy(LEGACY_QA_PATH, LEGACY_QA);

    deliver(releaseRequest());

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals(LEGACY_QA_PATH, recorded.get(0).configPath);
    assertEquals(LEGACY_QA, recorded.get(0).triggerConfig, "the file, verbatim, as it always was");
    assertEquals("alpine:3", fakeRunner.executed().get(0).image());
  }

  @Test
  public void anUnreadableSlotFileFallsBackToTheLegacyPipeline() throws Exception {
    // ABSENT and UNREACHABLE are not the same answer, and the direction chosen here is the one that
    // cannot cost a release request its verdict: a migrated repository has no legacy file for the
    // fallback to find, so falling back is free — while reading a blip as "release.yml exists" would
    // leave an unmigrated repository's request hanging PENDING with no QA run at all.
    fakeConfig.putFileUnreachable(repoId, HEAD, CiReleaseSlotParser.CONFIG_PATH);
    seedLegacy(LEGACY_QA_PATH, LEGACY_QA);

    deliver(releaseRequest());

    assertEquals(1, runService.runsFor(repoId).size());
    assertEquals(LEGACY_QA_PATH, runService.runsFor(repoId).get(0).configPath);
  }

  // --- the ways a slot file records nothing ---------------------------------------------------------

  @Test
  public void anUnknownArchetypeIsNoRunAndNoFallback() throws Exception {
    // The engine's standing rule: an unreadable candidate is skipped, never run. And the legacy file
    // stays superseded — a repository that has migrated must not silently start running a file it
    // has stopped maintaining because the wrapper is momentarily unreadable.
    seedSlots("archetype: does-not-exist\n");
    seedLegacy(LEGACY_QA_PATH, LEGACY_QA);

    deliver(releaseRequest());

    assertEquals(List.of(), runService.runsFor(repoId));
  }

  @Test
  public void anUnparseableSlotFileIsNoRunAndNoFallback() throws Exception {
    seedSlots("archetpye: spa-frontend\n");
    seedLegacy(LEGACY_QA_PATH, LEGACY_QA);

    deliver(releaseRequest());

    assertEquals(List.of(), runService.runsFor(repoId));
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
}
