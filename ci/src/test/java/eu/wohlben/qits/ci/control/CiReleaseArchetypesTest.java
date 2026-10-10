package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiReleaseArchetypes.Resolution;
import eu.wohlben.qits.ci.control.CiReleaseArchetypes.Status;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CiReleaseArchetypes} on its own: the two-step resolution and the three answers it must
 * keep apart.
 *
 * <p>The order — the repository's own recipe first, the packaged one otherwise — is the seam, and
 * every case here stages <b>both</b> sources so that the wrong order, or a fall-through that should
 * not happen, answers with a perfectly usable recipe. An {@code Optional}-shaped "found something"
 * assertion would pass all of them; what is asserted is <em>which</em> recipe came back, by the
 * script it carries and by the {@code rev}/{@code version} pair it is recorded under.
 *
 * <p><b>The packaged set is stood in here</b> ({@link #packaged}), so these cases do not depend on
 * which recipes the repository happens to ship; the real classpath is {@code
 * PackagedReleaseArchetypesTest}'s.
 *
 * <p><b>Plain JUnit and hand-wired</b>, for {@code CiRunOrderingTest}'s reason: this class reaches
 * no database and needs no application — it is a parser, a port and a resource lookup. A
 * {@code @QuarkusTest} here would be a second Quarkus start for nothing, which is what this
 * repository's test-profile budget rule is about.
 */
public class CiReleaseArchetypesTest {

  /** The revision the repository's {@code release.yml} was read at — the fold, or the tag. */
  private static final String REV = "e".repeat(40);

  private static final String VERSION = "2026.930.40058";

  /** The repository that keeps the packaged set, as production names it. */
  private static final String SOURCE_REPOSITORY = "qits-ci-service";

  /** The platform project's id, as production names it beside its slug. */
  private static final String PLATFORM_PROJECT_ID = "8273c743-e0bb-4d93-90d4-391aa10d9151";

  private static final String LOCAL =
      """
      release-request:
        - image: alpine:3
          script: echo local
      """;

  private static final String PACKAGED =
      """
      release-request:
        - image: alpine:3
          script: echo packaged
      """;

  private CiReleaseArchetypes archetypes;
  private FakeCiConfigSource config;
  private CiRepoRef repo;

  /** The stand-in packaged set: resource name to text. */
  private final Map<String, String> packaged = new HashMap<>();

  @BeforeEach
  void wire() {
    config = new FakeCiConfigSource();
    packaged.clear();
    archetypes =
        new CiReleaseArchetypes() {
          @Override
          String packagedContent(String resource) {
            return packaged.get(resource);
          }
        };
    archetypes.configSource = config;
    archetypes.slotParser = new CiReleaseSlotParser();
    archetypes.applicationVersion = Optional.of(VERSION);
    archetypes.sourceRepository = Optional.of(SOURCE_REPOSITORY);
    archetypes.sourceProjects = Optional.of(List.of(PLATFORM_PROJECT_ID, "qits"));
    repo = CiRepoRef.of("repo-1", "qits", "qits-target");
  }

  private void packageRecipe(String name, String content) {
    packaged.put(CiReleaseArchetypes.PACKAGED_DIR + name + ".yml", content);
  }

  private void commitRecipe(String name, String content) {
    config.putFile(repo.repoId(), REV, CiReleaseSlotParser.archetypePath(name), content);
  }

  private static String script(Resolution found) {
    return found.archetype().slots().releaseRequest().steps().get(0).script();
  }

  @Test
  public void theRepositorysOwnRecipeWinsOverThePackagedOneAtTheRevisionItWasAskedAt() {
    commitRecipe("java-service", LOCAL);
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(repo, REV, "java-service");

    assertEquals(Status.FOUND, found.status());
    assertEquals("echo local", script(found), "local first: a repository may shadow a recipe");
    assertEquals("java-service", found.archetype().name());
    assertEquals(CiReleaseSlotParser.archetypePath("java-service"), found.archetype().configPath());
    // rev non-null IS "shadowed locally", and a local recipe belongs to no qits-ci release.
    assertEquals(REV, found.archetype().rev());
    assertNull(found.archetype().version());
    assertEquals(found.archetype().ref().rev(), found.archetype().rev(), "ref() is the identity half");
    assertEquals(
        List.of(repo.repoId() + "@" + REV + "/" + CiReleaseSlotParser.archetypePath("java-service")),
        config.fileReads(),
        "one read, of THIS repository, at the rev it was handed — never a branch of its choosing");
  }

  @Test
  public void aRecipeTheRepositoryDoesNotCarryIsThePackagedOneRecordedByThisQitsCisVersion() {
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(repo, REV, "java-service");

    assertEquals(Status.FOUND, found.status());
    assertEquals("echo packaged", script(found));
    assertEquals(
        "ci/src/main/resources/release-archetypes/java-service.yml",
        found.archetype().configPath(),
        "recorded under its real path in qits-ci-service, not under .config/qits/");
    assertNull(found.archetype().rev(), "it was read from no revision of any repository");
    assertEquals(VERSION, found.archetype().version());
    assertEquals(1, config.fileReads().size(), "the repository was asked first: " + config.fileReads());
  }

  @Test
  public void aPackagedRecipeWhoseVersionCannotBeNamedStillComposesWithANullVersion() {
    // quarkus.application.version absent must cost a row one column, never the composition.
    archetypes.applicationVersion = Optional.empty();
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(repo, REV, "java-service");

    assertEquals(Status.FOUND, found.status());
    assertNull(found.archetype().version());
    assertNull(found.archetype().rev());
  }

  @Test
  public void aRecipeOnlyTheRepositoryCarriesResolves() {
    // An archetype of the repository's own invention: nothing packaged under that name at all.
    commitRecipe("house-recipe", LOCAL);

    Resolution found = archetypes.read(repo, REV, "house-recipe");

    assertEquals(Status.FOUND, found.status());
    assertEquals("echo local", script(found));
    assertEquals(REV, found.archetype().rev());
  }

  @Test
  public void aNameInNeitherPlaceIsUnknownRatherThanAnException() {
    // The ordinary broken declaration: release.yml names a recipe that does not exist. Final — the
    // caller records no run and settles — and never a throw, which would cost the candidates beside
    // it their evaluation.
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(repo, REV, "does-not-exist");

    assertEquals(Status.UNKNOWN, found.status());
    assertNull(found.archetype());
    assertTrue(found.detail().contains("does-not-exist"), found.detail());
    assertEquals(1, config.fileReads().size(), "it did ask, and the answer was ABSENT");
  }

  @Test
  public void aLocalReadThatCouldNotBeMadeIsUnreadableAndNotThePackagedCopy() {
    // Nothing was learned about whether the repository shadows the recipe. Answering with the
    // packaged one — which is right there — would let a git-host blip decide which of two pipelines
    // a commit gets.
    config.putFileUnreachable(repo.repoId(), REV, CiReleaseSlotParser.archetypePath("java-service"));
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(repo, REV, "java-service");

    assertEquals(Status.UNREADABLE, found.status(), "owed, not answered");
    assertNull(found.archetype());
  }

  @Test
  public void aLocalRecipeThatWillNotParseIsUnknownAndNotThePackagedCopy() {
    // A broken shadow is final. Falling through would run the pipeline the repository replaced, and
    // report it green with nothing to say the replacement was ignored.
    commitRecipe("java-service", "archetype: another\n");
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(repo, REV, "java-service");

    assertEquals(Status.UNKNOWN, found.status());
    assertNull(found.archetype());
    assertTrue(found.detail().contains("not a usable release archetype"), found.detail());
  }

  @Test
  public void aPackagedRecipeThatWillNotParseIsUnknownRatherThanAnException() {
    // A defect of the build, not of the repository — PackagedReleaseArchetypesTest is what keeps it
    // from shipping. If it did ship, it must not take the evaluation down with it.
    packageRecipe("java-service", "archetype: another\n");

    assertEquals(Status.UNKNOWN, archetypes.read(repo, REV, "java-service").status());
  }

  @Test
  public void aNameThisParserWouldNeverHaveProducedIsRefusedBeforeAnyRead() {
    // Belt and braces, and the belt is real: the value becomes a URL segment in the local read and
    // a classpath segment in the packaged one. Both sources are staged under the traversal's own
    // spelling, so a guard that came second would find a recipe.
    config.putFile(
        repo.repoId(), REV, ".config/qits/release-archetypes/../../../etc/passwd.yml", LOCAL);
    packaged.put(CiReleaseArchetypes.PACKAGED_DIR + "../../../etc/passwd.yml", PACKAGED);

    assertEquals(Status.UNKNOWN, archetypes.read(repo, REV, "../../../etc/passwd").status());
    assertEquals(Status.UNKNOWN, archetypes.read(repo, REV, "Java-Service").status());
    assertEquals(Status.UNKNOWN, archetypes.read(repo, REV, "").status());
    assertEquals(Status.UNKNOWN, archetypes.read(repo, REV, null).status());
    assertEquals(
        List.of(), config.fileReads(), "refused before a read is attempted: " + config.fileReads());
  }

  // --- the repository that keeps the packaged set (qits-1155) ------------------------------------

  private void commitSource(CiRepoRef owner, String name, String content) {
    config.putFile(owner.repoId(), REV, CiReleaseArchetypes.sourcePath(name), content);
  }

  @Test
  public void theSourceRepositoryReadsItsBranchCopyOfThePackagedSetAtItsOwnRevision() {
    // qits-ci-service's own release request must run the recipe its branch carries, so a template
    // change is exercised before it ships. Its copy is the packaged set's source file, not a
    // .config/qits/ file; a decoy there must not be what is read.
    CiRepoRef owner = CiRepoRef.of("repo-ci", "qits", SOURCE_REPOSITORY);
    commitSource(owner, "java-service", LOCAL);
    config.putFile(owner.repoId(), REV, CiReleaseSlotParser.archetypePath("java-service"), PACKAGED);
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(owner, REV, "java-service");

    assertEquals(Status.FOUND, found.status());
    assertEquals("echo local", script(found), "the branch copy, not the deployed one");
    assertEquals(CiReleaseArchetypes.sourcePath("java-service"), found.archetype().configPath());
    assertEquals(REV, found.archetype().rev(), "read at its own revision");
    assertNull(found.archetype().version());
    assertEquals(
        List.of(owner.repoId() + "@" + REV + "/" + CiReleaseArchetypes.sourcePath("java-service")),
        config.fileReads(),
        "one read, of the source file only");
  }

  @Test
  public void theSourceRepositoryIsRecognisedByTheProjectsIdAsWellAsItsSlug() {
    CiRepoRef owner = CiRepoRef.of("repo-ci", PLATFORM_PROJECT_ID, SOURCE_REPOSITORY);
    commitSource(owner, "java-service", LOCAL);
    packageRecipe("java-service", PACKAGED);

    assertEquals("echo local", script(archetypes.read(owner, REV, "java-service")));
  }

  @Test
  public void aRepositoryOfTheSameNameInAnotherProjectGetsThePackagedCopyAndNeverItsResourceFile() {
    // The name alone must never make a repository the source: its ci/src/main/resources/ is
    // outside .config/qits/, so no approval gate would see the recipe it ran.
    CiRepoRef impostor = CiRepoRef.of("repo-other", "another-project", SOURCE_REPOSITORY);
    commitSource(impostor, "java-service", LOCAL);
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(impostor, REV, "java-service");

    assertEquals("echo packaged", script(found));
    assertNull(found.archetype().rev());
    assertEquals(
        List.of(
            impostor.repoId() + "@" + REV + "/" + CiReleaseSlotParser.archetypePath("java-service")),
        config.fileReads(),
        "only the .config/qits/ path is read, never the resource file");
  }

  @Test
  public void aReferenceWithNoProjectIsNeverTheSourceEvenWhenItsIdIsTheName() {
    // Fail closed: what cannot be placed in the platform project is an ordinary repository.
    CiRepoRef unplaced = CiRepoRef.of(SOURCE_REPOSITORY);
    commitSource(unplaced, "java-service", LOCAL);
    packageRecipe("java-service", PACKAGED);

    assertEquals("echo packaged", script(archetypes.read(unplaced, REV, "java-service")));
  }

  @Test
  public void aSourceRepositoryRevisionWithoutTheFileComposesFromThePackagedSet() {
    // A revision from before the move carries no source file: the packaged recipe answers.
    CiRepoRef owner = CiRepoRef.of("repo-ci", "qits", SOURCE_REPOSITORY);
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(owner, REV, "java-service");

    assertEquals("echo packaged", script(found));
    assertNull(found.archetype().rev());
  }

  @Test
  public void anyOtherRepositoryIsNeverReadAtTheSourcePath() {
    // A file at qits-ci-service's resource path in some other repository is not a shadow: only
    // .config/qits/release-archetypes/ is, and a change there is still held for approval.
    commitSource(repo, "java-service", LOCAL);
    packageRecipe("java-service", PACKAGED);

    Resolution found = archetypes.read(repo, REV, "java-service");

    assertEquals("echo packaged", script(found));
    assertEquals(
        List.of(repo.repoId() + "@" + REV + "/" + CiReleaseSlotParser.archetypePath("java-service")),
        config.fileReads());
  }

  @Test
  public void withNoSourceRepositoryConfiguredEveryRepositoryIsAnOrdinaryOne() {
    archetypes.sourceRepository = Optional.empty();
    CiRepoRef owner = CiRepoRef.of("repo-ci", "qits", SOURCE_REPOSITORY);
    commitSource(owner, "java-service", LOCAL);
    packageRecipe("java-service", PACKAGED);

    assertEquals("echo packaged", script(archetypes.read(owner, REV, "java-service")));
  }
}
