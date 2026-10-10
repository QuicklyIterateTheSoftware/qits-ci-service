package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CiEventTriggerService#releasePhaseAt} — the read qits-projects asks before it raises a
 * PUBLISH gate, and the whole of what is under test is that its <b>three</b> answers stay apart.
 *
 * <p>Everything below the git host is real, as in {@code CiReleaseSlotTriggerTest}: the slot parser,
 * the archetype resolution (the repository's own copy through the same port, otherwise the recipes
 * really packaged into this module's jar), and the composer. What is faked is the git host and the
 * candidate catalogue, which is what lets a read failure be staged at all.
 *
 * <p>The two cases worth reading first are {@link #anUnreachableSlotFileIsUnknownAndNeverFalse} and
 * {@link #aRepositoryOverridingAPublishFreeArchetypeDeclaresARelease}. The first is the bug this
 * read exists to prevent in the direction that publishes a release nothing gated; the second is the
 * whole reason the caller cannot answer from the file itself.
 */
@QuarkusTest
public class CiReleasePhaseTest extends CiTestSupport {

  /** What the caller really sends: a released tag's ref, never a branch. */
  private static final String REV = "refs/tags/2026.916.114057";

  /** The platform-pipelines repository's {@code main} head. No recipe is read from that repository. */
  private static final String WRAPPER_HEAD = "d".repeat(40);

  /** An archetype that publishes — a service's shape. */
  private static final String JAVA_SERVICE =
      """
      release-request:
        - image: alpine:3
          script: ./mvnw verify
      release:
        - image: alpine:3
          script: ./mvnw deploy
      """;

  /** An archetype that does not — and the shape this whole read was written for. */
  private static final String SPA_FRONTEND =
      """
      release-request:
        - image: qits/build-images/node-base:latest
          script: npm ci && npm run build
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
    // The wrapper is armed and listable, so that "this door reads no recipe from it" is asserted
    // against a wrapper that is really there.
    fakeConfig.putTriggers(wrapperId, "main", CiTriggerScope.PLATFORM, WRAPPER_HEAD);
    engine.platformPipelines(true);
  }

  @AfterEach
  void disarm() {
    engine.platformPipelines(false);
  }

  private void seedSlots(String content) {
    fakeConfig.putFile(repoId, REV, CiReleaseSlotParser.CONFIG_PATH, content);
  }

  /** The repository's OWN copy of a recipe, at the rev the door is asked about — a shadow. */
  private void seedArchetype(String name, String content) {
    fakeConfig.putFile(repoId, REV, CiReleaseSlotParser.archetypePath(name), content);
  }

  private CiEventTriggerService.ReleasePhase phase() {
    CiEventTriggerService.ReleasePhase answer = engine.releasePhaseAt("qits-target", REV);
    assertNotNull(answer.detail(), "every answer carries the sentence behind it");
    assertTrue(!answer.detail().isBlank(), answer.detail());
    return answer;
  }

  // --- declared ------------------------------------------------------------------------------------

  @Test
  public void anArchetypeWithAReleaseSlotIsDeclared() {
    seedSlots("archetype: java-service\n");
    seedArchetype("java-service", JAVA_SERVICE);

    assertEquals(CiEventTriggerService.Verdict.DECLARED, phase().verdict());
  }

  @Test
  public void aPackagedArchetypeWithAReleaseSlotIsDeclared() {
    // The ordinary case on the estate: a one-line slot file, no recipe in the repository, and the
    // real java-service packaged into this jar — which publishes an image.
    seedSlots("archetype: java-service\n");

    assertEquals(CiEventTriggerService.Verdict.DECLARED, phase().verdict());
  }

  @Test
  public void aBrokenOrUnknownArchetypeIsDeclaredWithADetailNamingIt() {
    // Committed bytes, like an unparseable slot file: the name is neither in the repository at the
    // rev nor packaged, so no amount of asking again composes this pipeline, and the fix is a
    // commit. It was UNKNOWN (a 503, retried forever) while the recipe came from another repository
    // and "not there" could still mean "not released yet".
    seedSlots("archetype: does-not-exist\n");

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.DECLARED, answer.verdict());
    assertTrue(answer.detail().contains("does-not-exist"), answer.detail());

    // And the same arm for a local recipe that does not parse — never the packaged one instead,
    // whose composition is a different pipeline from the one the repository wrote.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", "archetype: another\n");

    answer = phase();
    assertEquals(CiEventTriggerService.Verdict.DECLARED, answer.verdict());
    assertTrue(answer.detail().contains("spa-frontend"), answer.detail());
  }

  @Test
  public void aRepositoryOverridingAPublishFreeArchetypeDeclaresARelease() {
    // THE CASE THE CALLER CANNOT ANSWER ITSELF. The file names spa-frontend, which declares no
    // release slot at all — read the file alone and the honest answer is "no publish". The
    // composer's whole-slot override says otherwise, and the composer is the only one who knows.
    seedSlots(
        """
        archetype: spa-frontend
        release:
          - image: alpine:3
            script: ./publish.sh
        """);
    seedArchetype("spa-frontend", SPA_FRONTEND);

    assertEquals(CiEventTriggerService.Verdict.DECLARED, phase().verdict());
  }

  @Test
  public void anUnparseableSlotFileIsDeclared() {
    // Not a failure to be waved through: the bytes are the repository's, the fix is a commit, and a
    // gate that waits is recoverable while a release published past an unchecked pipeline is not.
    seedSlots("archetpye: spa-frontend\n");

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.DECLARED, answer.verdict());
    assertTrue(
        answer.detail().contains("not a usable release slot file"),
        "the detail has to say it was the file rather than the pipeline: " + answer.detail());
  }

  @Test
  public void slotsThatCannotBeComposedAreDeclared() {
    // Artifacts declared with no release steps to publish them: the composer refuses the pair, and
    // that refusal is about the repository's own document exactly as a parse error is.
    seedSlots(
        """
        release-request:
          - image: alpine:3
            script: ./mvnw verify
        artifacts:
          - { type: docker, name: qits/qits-target, sbom: out/sbom.json }
        """);

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.DECLARED, answer.verdict());
    assertTrue(answer.detail().contains("could not be composed"), answer.detail());
  }

  @Test
  public void anArchetypeWithNoReleaseSlotIsDeclaredForItsChangelog() {
    // spa-frontend and cli declare no release slot, and used to answer NOT_DECLARED. Every release
    // publishes a changelog now (qits-893), so the composer synthesises a release half for them and
    // the PUBLISH gate has a run to wait for.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);

    assertEquals(CiEventTriggerService.Verdict.DECLARED, phase().verdict());
  }

  @Test
  public void aPackagedSpaFrontendIsDeclaredForItsChangelog() {
    // The same answer from the recipe this qits-ci really ships, which is the verification the
    // deployed binary is asked for: an spa-frontend tag IS publish-gated, on its changelog.
    seedSlots("archetype: spa-frontend\n");

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.DECLARED, answer.verdict());
    assertTrue(answer.detail().contains("composes a release pipeline"), answer.detail());
  }

  // --- not declared --------------------------------------------------------------------------------

  @Test
  public void noSlotFileAtTheRevIsNotDeclared() {
    // ABSENT at a rev the host resolved is an honest answer and not a gap: nothing composes at an
    // immutable tag, so there is no release run to wait for and no later reading of it to fear.
    assertEquals(CiEventTriggerService.Verdict.NOT_DECLARED, phase().verdict());
  }

  // --- unknown: the question was not asked ---------------------------------------------------------

  @Test
  public void anUnreachableSlotFileIsUnknownAndNeverFalse() {
    // The bug, in the direction that is not recoverable: a blip reading the file would otherwise
    // publish a release whose pipeline nobody composed and nothing afterwards re-asks.
    fakeConfig.putFileUnreachable(repoId, REV, CiReleaseSlotParser.CONFIG_PATH);

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.UNKNOWN, answer.verdict());
    assertTrue(answer.detail().contains(REV), answer.detail());
  }

  @Test
  public void aLocalArchetypeThatCannotBeLookedForIsUnknownAndNotAnsweredFromThePackagedCopy() {
    // The repository could not be asked whether it carries its own spa-frontend. Answering from the
    // packaged one would decide on a blip which of two pipelines gates the release.
    seedSlots("archetype: spa-frontend\n");
    fakeConfig.putFileUnreachable(repoId, REV, CiReleaseSlotParser.archetypePath("spa-frontend"));

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.UNKNOWN, answer.verdict());
    assertTrue(answer.detail().contains("spa-frontend"), answer.detail());
  }

  @Test
  public void aRepositoryTheCatalogueDoesNotHoldIsUnknown() {
    // An empty or unreachable catalogue looks exactly like a repository that is not there, and the
    // candidate list's standing rule is that a read failure never shrinks the set observably.
    CiEventTriggerService.ReleasePhase answer = engine.releasePhaseAt("qits-nobody", REV);

    assertEquals(CiEventTriggerService.Verdict.UNKNOWN, answer.verdict());
    assertTrue(answer.detail().contains("qits-nobody"), answer.detail());
  }

  @Test
  public void theSlotFileAndALocalArchetypeAreReadAtTheRevAndTheWrapperIsNeverRead() {
    // Both of the repository's reads are the tag's immutable bytes, and nothing is read from the
    // platform-pipelines repository — which carries a readable, publish-FREE decoy under the same
    // name at every revision the old read used. Since every composition has a release half
    // (qits-893) the decoy would answer DECLARED too, so the reads below are what tell them apart.
    seedSlots("archetype: java-service\n");
    seedArchetype("java-service", JAVA_SERVICE);
    for (String rev : java.util.List.of("main", WRAPPER_HEAD, "a".repeat(40))) {
      fakeConfig.putFile(
          wrapperId, rev, CiReleaseSlotParser.archetypePath("java-service"), SPA_FRONTEND);
    }

    assertEquals(CiEventTriggerService.Verdict.DECLARED, phase().verdict());
    assertEquals(
        java.util.List.of(
            repoId + "@" + REV + "/" + CiReleaseSlotParser.CONFIG_PATH,
            repoId + "@" + REV + "/" + CiReleaseSlotParser.archetypePath("java-service")),
        fakeConfig.fileReads(),
        "two reads, both of the repository, both at the rev asked about");
    assertEquals(
        java.util.List.of(),
        fakeConfig.triggerReads(),
        "and not even a listing of the wrapper: this door has no use for it");
  }
}
