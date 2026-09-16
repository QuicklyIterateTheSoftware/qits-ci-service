package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CiEventTriggerService#releaseCompositionAt} — what a candidate {@code release.yml} would
 * compose, beside what the ref really commits, before anybody commits anything.
 *
 * <p>What is under test is the <b>answer</b>: which slot file was composed, which side is present,
 * and which failures are reported inside a 200-shaped result rather than as "not asked at all".
 * Everything below the git host is real, as in {@code CiReleasePhaseTest}: the slot parser, the
 * archetype read through the same port, the composer, and — on both sides of every phase — the one
 * {@code CiEventTriggerParser} the engine already runs a committed file through.
 *
 * <p>The two cases worth reading first are {@link #anUnparseableCandidateIsAnAnswerAndNeverARetry}
 * and {@link #theRefsOwnSlotFileWinsOverTheCandidate}. The first is the whole reason this read
 * refuses to treat a person's draft like a git-host blip; the second is the rule that keeps it from
 * answering a question nobody asked.
 */
@QuarkusTest
public class CiReleaseCompositionTest extends CiTestSupport {

  /** A branch tip rather than a tag: this read is about a file somebody is still editing. */
  private static final String REV = "main";

  /** The hand-written pair a migration deletes — the QA half, as 46 repositories still commit it. */
  private static final String COMMITTED_QA =
      """
      event: ReleaseRequestChanged
      when:
        - repoName: { exact: qits-target }
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: qits/build-images/maven-base:latest
          script: ./mvnw -B -ntp verify
        - image: qits/build-images/maven-base:latest
          gating: false
          script: ./publish-userflows.sh
      """;

  /** And the release half, artifacts included. */
  private static final String COMMITTED_RELEASE =
      """
      event: SCMRelease
      when:
        - repository: { exact: qits-target }
      checkout:
        branch: version
        sha: commitSha
        optional: true
      artifacts:
        - { type: docker, name: qits/qits-target }
      steps:
        - image: qits/build-images/ci-base:latest
          docker: true
          script: docker build . && docker push qits/qits-target
      """;

  /** The candidate that is meant to replace the pair above. */
  private static final String CANDIDATE =
      """
      archetype: java-service
      artifacts:
        - { type: docker, name: qits/qits-target, sbom: out/sbom.json }
      """;

  /** The recipe it names, in the wrapper. */
  private static final String JAVA_SERVICE =
      """
      release-request:
        - image: qits/build-images/maven-base:latest
          script: ./mvnw -B -ntp verify
      release:
        - image: qits/build-images/ci-base:latest
          build: true
          script: buildctl build ...
      """;

  @Inject CiEventTriggerService engine;

  private String repoId;
  private String wrapperId;

  @BeforeEach
  void armTheCandidates() {
    repoId = "target-" + UUID.randomUUID().toString().substring(0, 8);
    wrapperId = "wrapper-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(
        CiRepoRef.of(repoId, "qits", "qits-target"), CiRepoRef.of(wrapperId, "qits", "qits-qits"));
    engine.platformPipelinesRepository("qits-qits");
  }

  @AfterEach
  void disarm() {
    engine.platformPipelinesRepository("");
  }

  private void seedCommittedPair() {
    fakeConfig.putFile(repoId, REV, CiEventTriggerService.LEGACY_RELEASE_REQUEST_PATH, COMMITTED_QA);
    fakeConfig.putFile(repoId, REV, CiEventTriggerService.LEGACY_RELEASE_PATH, COMMITTED_RELEASE);
  }

  private void seedArchetype(String name, String content) {
    fakeConfig.putFile(wrapperId, "main", CiReleaseSlotParser.archetypePath(name), content);
  }

  private CiEventTriggerService.ReleaseComposition composition(String candidate) {
    CiEventTriggerService.ReleaseComposition answer =
        engine.releaseCompositionAt("qits-target", REV, candidate);
    assertNotNull(answer.detail(), "every answer carries the sentence behind it");
    assertTrue(!answer.detail().isBlank(), answer.detail());
    return answer;
  }

  // --- answered: a candidate against the pair a repository really commits ------------------------

  @Test
  public void aCandidateIsComposedAgainstTheHandWrittenPairTheRefCommits() {
    seedCommittedPair();
    seedArchetype("java-service", JAVA_SERVICE);

    CiEventTriggerService.ReleaseComposition answer = composition(CANDIDATE);

    assertEquals(CiEventTriggerService.CompositionVerdict.ANSWERED, answer.verdict());
    assertEquals(CiEventTriggerService.SlotFileSource.CANDIDATE, answer.slotFileSource());

    // Both phases, both sides, and the events they are two declarations ABOUT.
    CiEventTriggerService.PhaseComparison qa = answer.releaseRequestPhase();
    assertEquals("release-request", qa.phase());
    assertEquals(CiReleaseComposer.RELEASE_REQUEST_EVENT, qa.event());
    assertNotNull(qa.composed().document(), qa.composed().detail());
    assertNotNull(qa.committed().document(), qa.committed().detail());
    assertEquals(
        CiEventTriggerService.LEGACY_RELEASE_REQUEST_PATH, qa.committed().path());
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, qa.composed().path());

    // What decides behaviour is summarised on BOTH sides by the one parser, so the two are
    // comparable field by field — which is the whole of what this read is for.
    assertEquals(
        CiReleaseComposer.RELEASE_REQUEST_EVENT, qa.composed().summary().event());
    assertEquals(qa.committed().summary().event(), qa.composed().summary().event());
    assertEquals(qa.committed().summary().selection(), qa.composed().summary().selection());
    assertTrue(
        qa.composed().summary().selection().contains("repoName exact 'qits-target'"),
        qa.composed().summary().selection());
    assertEquals("backingBranch", qa.composed().summary().checkout().branchPath());
    assertEquals("mergedSha", qa.composed().summary().checkout().shaPath());

    // The committed QA pipeline's second step is the non-gating userflows half; the archetype's is
    // one gating step. A person reads that off the summaries without opening either document.
    assertEquals(2, qa.committed().summary().steps().size());
    assertEquals(1, qa.composed().summary().steps().size());
    assertTrue(qa.committed().summary().steps().get(0).gating());
    assertTrue(!qa.committed().summary().steps().get(1).gating());

    CiEventTriggerService.PhaseComparison release = answer.releasePhase();
    assertEquals("release", release.phase());
    assertEquals(CiReleaseComposer.RELEASE_EVENT, release.event());
    assertEquals(1, release.composed().summary().artifacts().size());
    // The DECLARATION is what says what a pipeline publishes — never the script — and the sbom path
    // is the one thing the composed artifacts: block cannot carry, so it is joined by coordinate.
    CiEventTriggerService.ArtifactSummary composedArtifact =
        release.composed().summary().artifacts().get(0);
    assertEquals("docker", composedArtifact.type());
    assertEquals("qits/qits-target", composedArtifact.name());
    assertEquals("out/sbom.json", composedArtifact.sbomPath());
    // A trigger file's artifacts: grammar has no sbom: key and never did.
    assertEquals("", release.committed().summary().artifacts().get(0).sbomPath());
  }

  @Test
  public void everyStepCarriesAScriptDigestAndNoDiff() {
    // Two scripts that differ show up as two digests, and that is the whole of what this read says
    // about a script's content: no diff algorithm, and nothing derived from reading the text.
    seedCommittedPair();
    seedArchetype("java-service", JAVA_SERVICE);

    CiEventTriggerService.PhaseComparison release = composition(CANDIDATE).releasePhase();
    CiEventTriggerService.StepSummary composed = release.composed().summary().steps().get(0);
    CiEventTriggerService.StepSummary committed = release.committed().summary().steps().get(0);

    assertEquals(64, composed.scriptSha256().length(), composed.scriptSha256());
    assertNotEquals(committed.scriptSha256(), composed.scriptSha256());
    assertTrue(composed.scriptLines() > committed.scriptLines(), "the prelude is real text");
    // The flags are carried through the composition unchanged, so the difference a reader sees here
    // is the one the two documents really declare.
    assertTrue(composed.build());
    assertTrue(committed.docker());
  }

  @Test
  public void theRefsOwnSlotFileWinsOverTheCandidate() {
    // A repository that has already migrated is asking about ITSELF. Composing a candidate over the
    // top of committed bytes would answer a question nobody asked.
    fakeConfig.putFile(
        repoId,
        REV,
        CiReleaseSlotParser.CONFIG_PATH,
        """
        release:
          - image: committed-image:1
            script: ./committed.sh
        """);

    CiEventTriggerService.ReleaseComposition answer =
        composition(
            """
            release:
              - image: candidate-image:1
                script: ./candidate.sh
            """);

    assertEquals(CiEventTriggerService.SlotFileSource.COMMITTED, answer.slotFileSource());
    assertEquals(
        "committed-image:1",
        answer.releasePhase().composed().summary().steps().get(0).image(),
        "the candidate must not reach the composer when the ref commits its own slot file");
  }

  @Test
  public void aRefWithNoLegacyFilesReportsTheCommittedSideAbsentWithItsReason() {
    // The post-migration shape, and the sentence a caller shows a person: there is no hand-written
    // pipeline here, which is a fact about the ref rather than a gap in the answer.
    seedArchetype("java-service", JAVA_SERVICE);

    CiEventTriggerService.ReleaseComposition answer = composition(CANDIDATE);

    for (CiEventTriggerService.PhaseComparison phase :
        List.of(answer.releaseRequestPhase(), answer.releasePhase())) {
      assertNull(phase.committed().document(), phase.phase());
      assertNull(phase.committed().summary(), phase.phase());
      assertTrue(
          phase.committed().detail().contains(REV), phase.committed().detail());
      assertNotNull(phase.composed().document(), phase.composed().detail());
    }
  }

  @Test
  public void aPhaseNothingDeclaresIsAbsentOnTheComposedSideWithItsReason() {
    // spa-frontend's shape: no release slot on purpose, so no release run would ever be recorded.
    // Absent with a reason, never an empty document that reads like an empty pipeline.
    seedCommittedPair();
    seedArchetype(
        "spa-frontend",
        """
        release-request:
          - image: qits/build-images/node-base:latest
            script: npm ci && npm run build
        """);

    CiEventTriggerService.ReleaseComposition answer = composition("archetype: spa-frontend\n");

    assertNotNull(answer.releaseRequestPhase().composed().document());
    assertNull(answer.releasePhase().composed().document());
    assertTrue(
        answer.releasePhase().composed().detail().contains("release"),
        answer.releasePhase().composed().detail());
    // And the committed side still reports the pipeline the ref really has, which is exactly the
    // comparison that matters here: this migration would stop publishing.
    assertNotNull(answer.releasePhase().committed().document());
  }

  @Test
  public void anUnparseableCandidateIsAnAnswerAndNeverARetry() {
    // The bytes are the caller's own draft, and the parser's message is the single most useful thing
    // this read can hand back to somebody about to commit that file. A retry would hide it.
    seedCommittedPair();

    CiEventTriggerService.ReleaseComposition answer = composition("archetpye: java-service\n");

    assertEquals(CiEventTriggerService.CompositionVerdict.ANSWERED, answer.verdict());
    assertEquals(CiEventTriggerService.SlotFileSource.CANDIDATE, answer.slotFileSource());
    assertNull(answer.releaseRequestPhase().composed().document());
    assertTrue(
        answer.releaseRequestPhase().composed().detail().contains("not a usable release slot file"),
        answer.releaseRequestPhase().composed().detail());
    // The committed side is unaffected: it is the other half of the comparison and it read fine.
    assertNotNull(answer.releaseRequestPhase().committed().document());
  }

  @Test
  public void slotsThatCannotBeComposedAreAnAnswerToo() {
    // Artifacts declared with no release steps to publish them: the composer refuses the pair, and
    // that refusal is about the document exactly as a parse error is.
    seedCommittedPair();

    CiEventTriggerService.ReleaseComposition answer =
        composition(
            """
            release-request:
              - image: alpine:3
                script: ./mvnw verify
            artifacts:
              - { type: docker, name: qits/qits-target, sbom: out/sbom.json }
            """);

    assertEquals(CiEventTriggerService.CompositionVerdict.ANSWERED, answer.verdict());
    assertTrue(
        answer.releasePhase().composed().detail().contains("could not be composed"),
        answer.releasePhase().composed().detail());
  }

  // --- not answered: the question was not asked, or there was no question ------------------------

  @Test
  public void aRepositoryTheCatalogueDoesNotHoldIsUnavailable() {
    CiEventTriggerService.ReleaseComposition answer =
        engine.releaseCompositionAt("qits-nobody", REV, CANDIDATE);

    assertEquals(CiEventTriggerService.CompositionVerdict.UNAVAILABLE, answer.verdict());
    assertTrue(answer.detail().contains("qits-nobody"), answer.detail());
    assertNull(answer.releaseRequestPhase());
    assertNull(answer.slotFileSource());
  }

  @Test
  public void anUnreachableSlotFileIsUnavailable() {
    fakeConfig.putFileUnreachable(repoId, REV, CiReleaseSlotParser.CONFIG_PATH);

    CiEventTriggerService.ReleaseComposition answer = composition(CANDIDATE);

    assertEquals(CiEventTriggerService.CompositionVerdict.UNAVAILABLE, answer.verdict());
    assertTrue(answer.detail().contains(CiReleaseSlotParser.CONFIG_PATH), answer.detail());
  }

  @Test
  public void anUnreachableLegacyFileIsUnavailableAndNeverAnAbsence() {
    // The sentence this door must never say by accident: "this repository has already stopped
    // committing that file", on the strength of a git host that did not answer.
    fakeConfig.putFileUnreachable(repoId, REV, CiEventTriggerService.LEGACY_RELEASE_PATH);

    CiEventTriggerService.ReleaseComposition answer = composition(CANDIDATE);

    assertEquals(CiEventTriggerService.CompositionVerdict.UNAVAILABLE, answer.verdict());
    assertTrue(
        answer.detail().contains(CiEventTriggerService.LEGACY_RELEASE_PATH), answer.detail());
  }

  @Test
  public void anUnreadableArchetypeIsUnavailable() {
    // The candidate's own bytes are fine; the wrapper's could not be read. A statement about
    // qits-ci, so it is the one composition failure that is not an answer about the file.
    seedCommittedPair();

    CiEventTriggerService.ReleaseComposition answer =
        composition("archetype: does-not-exist\n");

    assertEquals(CiEventTriggerService.CompositionVerdict.UNAVAILABLE, answer.verdict());
    assertTrue(answer.detail().contains("does-not-exist"), answer.detail());
  }

  @Test
  public void noSlotFileAndNoCandidateIsNothingToCompare() {
    // An empty request rather than an empty answer: two absences would read as "this repository
    // composes nothing", which is a statement about the repository and not about the ask.
    seedCommittedPair();

    CiEventTriggerService.ReleaseComposition answer = composition(" ");

    assertEquals(CiEventTriggerService.CompositionVerdict.NOTHING_TO_COMPARE, answer.verdict());
    assertTrue(answer.detail().contains("no candidate"), answer.detail());
  }

  @Test
  public void theSlotFileAndTheLegacyPairAreReadAtTheRevAndTheArchetypeAtTheWrappersMain() {
    // The split every composition on this service makes: the repository's half at the rev, the
    // platform's at the wrapper's main. And exactly the four reads — nothing else is asked for.
    seedCommittedPair();
    seedArchetype("java-service", JAVA_SERVICE);

    composition(CANDIDATE);

    List<String> reads = fakeConfig.fileReads();
    assertTrue(
        reads.contains(repoId + "@" + REV + "/" + CiReleaseSlotParser.CONFIG_PATH), reads.toString());
    assertTrue(
        reads.contains(repoId + "@" + REV + "/" + CiEventTriggerService.LEGACY_RELEASE_REQUEST_PATH),
        reads.toString());
    assertTrue(
        reads.contains(repoId + "@" + REV + "/" + CiEventTriggerService.LEGACY_RELEASE_PATH),
        reads.toString());
    assertTrue(
        reads.contains(wrapperId + "@main/" + CiReleaseSlotParser.archetypePath("java-service")),
        reads.toString());
    assertEquals(4, reads.size(), reads.toString());
  }
}
