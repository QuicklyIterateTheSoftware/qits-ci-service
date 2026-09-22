package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CiReleaseArchetypes} on its own, which it had never been.
 *
 * <p>Every case below was reachable only transitively before — through a whole evaluation, a real
 * database and a worker — and two of its arms were reached by no test <b>by name</b> at all: the
 * one where this deployment has no platform-pipelines repository, and the belt-and-braces refusal of
 * a name the parser would never have produced. Both are WARN-and-empty, which is the engine's
 * standing rule that an unreadable candidate is skipped rather than run, and both are exactly the
 * kind of arm that goes quietly wrong: an empty {@code Optional} is a correct-looking value.
 *
 * <p><b>Plain JUnit and hand-wired</b>, for {@code CiRunOrderingTest}'s reason: this class holds no
 * state, reaches no database and needs no application — it is a parser and a port. A
 * {@code @QuarkusTest} here would be a second Quarkus start to assert two null checks, which is what
 * this repository's test-profile budget rule is about.
 */
public class CiReleaseArchetypesTest {

  private static final String WRAPPER_SHA = "d".repeat(40);

  /** The released wrapper version that sha is — what a recipe read is now addressed by. */
  private static final CiReleasedVersions.ReleasedVersion RELEASED =
      new CiReleasedVersions.ReleasedVersion("2026.922.161358", WRAPPER_SHA);

  private static final String RECIPE =
      """
      release-request:
        - image: alpine:3
          script: ./mvnw verify
      """;

  private CiReleaseArchetypes archetypes;
  private FakeCiConfigSource config;
  private CiRepoRef wrapper;

  @BeforeEach
  void wire() {
    config = new FakeCiConfigSource();
    archetypes = new CiReleaseArchetypes();
    archetypes.configSource = config;
    archetypes.slotParser = new CiReleaseSlotParser();
    wrapper = CiRepoRef.of("wrapper-1", "qits", "qits-qits");
  }

  @Test
  public void aRecipeIsReadAtTheReleasedVersionItWasAskedFor() {
    config.putFile(
        wrapper.repoId(), WRAPPER_SHA, CiReleaseSlotParser.archetypePath("java-service"), RECIPE);

    Optional<CiReleaseArchetypes.Archetype> found =
        archetypes.read(wrapper, RELEASED, "java-service");

    assertTrue(found.isPresent());
    assertEquals("java-service", found.get().name());
    assertEquals(CiReleaseSlotParser.archetypePath("java-service"), found.get().configPath());
    // The rev rides back out on the answer, which is the whole of what a run row records: a recipe
    // name without the revision it was read at says which recipe and not which version of it.
    assertEquals(WRAPPER_SHA, found.get().rev());
    // And the VERSION beside it, which is that revision as a person holds it. A sha alone cannot be
    // read back as "which approved wrapper release was this" — no git host answers that question.
    assertEquals("2026.922.161358", found.get().version());
    assertEquals(found.get().ref().rev(), found.get().rev(), "ref() is the identity half, verbatim");
    assertEquals(found.get().ref().version(), found.get().version());
    assertEquals(
        wrapper.repoId() + "@" + WRAPPER_SHA + "/" + CiReleaseSlotParser.archetypePath("java-service"),
        config.fileReads().get(0),
        "read at the rev it was handed, never at a branch of its own choosing");
  }

  @Test
  public void noPlatformPipelinesRepositoryIsEmptyAndReadsNOTHING() {
    // This deployment declares none, or the catalogue does not hold the one it declares. The arm is
    // reached on every deployment that has not armed the key, and what makes it worth pinning is
    // that it must not touch the rev: there is no repository to address, so there is nothing a
    // revision could be a revision OF, and a read attempted here would go out against null.
    Optional<CiReleaseArchetypes.Archetype> found =
        archetypes.read(null, RELEASED, "java-service");

    assertTrue(found.isEmpty());
    assertEquals(
        java.util.List.of(), config.fileReads(), "no wrapper, no read: " + config.fileReads());
  }

  @Test
  public void aNameThisParserWouldNeverHaveProducedIsRefusedBeforeItBecomesAPath() {
    // Belt and braces, and the belt is real: the value becomes a URL segment against ANOTHER
    // repository, so the refusal has to be here as well as in the parser rather than only there. A
    // caller that built a name some other way is the case, and traversal is the shape of it.
    config.putFile(
        wrapper.repoId(),
        WRAPPER_SHA,
        ".config/qits/release-archetypes/../../../etc/passwd.yml",
        RECIPE);

    assertTrue(archetypes.read(wrapper, RELEASED, "../../../etc/passwd").isEmpty());
    assertTrue(archetypes.read(wrapper, RELEASED, "Java-Service").isEmpty());
    assertTrue(archetypes.read(wrapper, RELEASED, "").isEmpty());
    assertTrue(archetypes.read(wrapper, RELEASED, null).isEmpty());
    assertEquals(
        java.util.List.of(),
        config.fileReads(),
        "refused before a read is attempted at all: " + config.fileReads());
  }

  @Test
  public void aRecipeThatIsNotThereIsEmptyRatherThanAnException() {
    // The ordinary broken-declaration case: release.yml names a recipe the wrapper does not carry.
    // Empty, so the caller records no run — never a throw, which would cost the candidates beside it
    // their evaluation.
    assertTrue(archetypes.read(wrapper, RELEASED, "does-not-exist").isEmpty());
    assertFalse(config.fileReads().isEmpty(), "it did ask, and the answer was ABSENT");
  }

  @Test
  public void noReleasedVersionReadsNOTHINGRatherThanFallingBack() {
    // The fail-closed belt, here rather than only in the caller: with no released wrapper version
    // there is no approved revision to read at, and the one thing this must never do is build a url
    // out of a null rev or reach for a branch name. The caller (CiEventTriggerService) states the
    // same refusal in its own terms and leaves the event owed; this is what happens if it ever
    // stops.
    config.putFile(
        wrapper.repoId(), WRAPPER_SHA, CiReleaseSlotParser.archetypePath("java-service"), RECIPE);

    assertTrue(archetypes.read(wrapper, null, "java-service").isEmpty());
    assertEquals(
        java.util.List.of(),
        config.fileReads(),
        "nothing is read at all — no null rev, no branch name: " + config.fileReads());
  }

  @Test
  public void aRecipeThatWillNotParseIsEmptyRatherThanAnException() {
    // One broken recipe must not read as "this repository declares nothing", and must not take the
    // repositories on other archetypes down with it.
    config.putFile(
        wrapper.repoId(),
        WRAPPER_SHA,
        CiReleaseSlotParser.archetypePath("java-service"),
        "archetype: another\n");

    assertTrue(archetypes.read(wrapper, RELEASED, "java-service").isEmpty());
  }
}
