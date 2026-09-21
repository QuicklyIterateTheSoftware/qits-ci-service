package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * the archetype read through the same port, and the composer. What is faked is the git host and the
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

  /** The sha the wrapper's {@code main} resolves to when this door asks — never the branch name. */
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
    // This door resolves the wrapper's head itself, with one listing of its own, and reads the
    // recipe at whatever that answers. A fixture that never lists the wrapper leaves no revision to
    // read at, which is the fail-closed case rather than the ordinary one.
    fakeConfig.putTriggers(wrapperId, "main", CiTriggerScope.PLATFORM, WRAPPER_HEAD);
    engine.platformPipelinesRepository("qits-qits");
  }

  @AfterEach
  void disarm() {
    engine.platformPipelinesRepository("");
  }

  private void seedSlots(String content) {
    fakeConfig.putFile(repoId, REV, CiReleaseSlotParser.CONFIG_PATH, content);
  }

  private void seedArchetype(String name, String content) {
    fakeConfig.putFile(wrapperId, WRAPPER_HEAD, CiReleaseSlotParser.archetypePath(name), content);
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

  // --- not declared --------------------------------------------------------------------------------

  @Test
  public void aPublishFreeArchetypeIsNotDeclared() {
    // spa-frontend and cli: no release slot, on purpose. A PUBLISH gate here is one nobody will ever
    // answer, which is the release request that hung RELEASED forever.
    seedSlots("archetype: spa-frontend\n");
    seedArchetype("spa-frontend", SPA_FRONTEND);

    assertEquals(CiEventTriggerService.Verdict.NOT_DECLARED, phase().verdict());
  }

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
  public void anUnreadableArchetypeIsUnknown() {
    // The repository's own bytes are fine; the wrapper's could not be read. That is a statement
    // about qits-ci, so it is the one failure that must not be reported as the repository's answer.
    seedSlots("archetype: does-not-exist\n");

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.UNKNOWN, answer.verdict());
    assertTrue(answer.detail().contains("does-not-exist"), answer.detail());
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
  public void theSlotFileIsReadAtTheRevAndTheArchetypeAtTheWrappersResolvedHead() {
    // The split every composition on this service makes, asserted rather than argued: the
    // repository's half is the tag's immutable bytes, the platform's is today's recipe — so the
    // answer is about the pipeline as it composes NOW, which is how the run that would satisfy the
    // gate would be composed too.
    //
    // "Now" is a SHA and no longer the branch name. This door lists the wrapper itself and reads at
    // what that listing resolved, which is the same discipline the repository half has always had;
    // the absence assertion is what stands between that and a silent fall back to the moving ref.
    seedSlots("archetype: java-service\n");
    seedArchetype("java-service", JAVA_SERVICE);

    assertEquals(CiEventTriggerService.Verdict.DECLARED, phase().verdict());
    assertTrue(
        fakeConfig.fileReads().contains(repoId + "@" + REV + "/" + CiReleaseSlotParser.CONFIG_PATH),
        fakeConfig.fileReads().toString());
    assertTrue(
        fakeConfig
            .fileReads()
            .contains(
                wrapperId
                    + "@"
                    + WRAPPER_HEAD
                    + "/"
                    + CiReleaseSlotParser.archetypePath("java-service")),
        fakeConfig.fileReads().toString());
    assertFalse(
        fakeConfig
            .fileReads()
            .contains(wrapperId + "@main/" + CiReleaseSlotParser.archetypePath("java-service")),
        fakeConfig.fileReads().toString());
  }

  @Test
  public void aWrapperThatCannotBeListedIsUnknown() {
    // Fail closed, and the same answer an unreadable recipe file gets: there is no revision to read
    // the recipe at, so the question was not asked and the caller must retry rather than be told
    // something about the repository. Reading at the literal branch name instead would answer
    // confidently from whatever main happened to be, which is the moving ref this read left behind.
    seedSlots("archetype: java-service\n");
    seedArchetype("java-service", JAVA_SERVICE);
    fakeConfig.putTriggersUnreachable(wrapperId, "main", CiTriggerScope.PLATFORM);

    CiEventTriggerService.ReleasePhase answer = phase();
    assertEquals(CiEventTriggerService.Verdict.UNKNOWN, answer.verdict());
    assertTrue(answer.detail().contains("java-service"), answer.detail());
  }
}
