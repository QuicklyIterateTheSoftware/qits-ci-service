package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import org.junit.jupiter.api.Test;

/**
 * The strictness matrix of {@code .config/qits/release.yml}.
 *
 * <p>Plain JUnit, no Quarkus: the parser holds no state and injects nothing, so a {@code
 * @QuarkusTest} here would buy a container start and nothing else.
 *
 * <p>The claims worth making are almost all <b>refusals</b>, and that is the file's whole design.
 * This document compiles into two pipelines that publish; a key that silently parsed to nothing is a
 * release whose SBOM was never submitted or a gate that ran nothing and went green, and neither says
 * a word about itself afterwards. So every case below asserts that a mistake is a {@link
 * CiConfigException} <em>naming the file</em>, which is the only part of a parse error a person
 * reading a WARN in production actually gets.
 */
public class CiReleaseSlotParserTest {

  private static final String PATH = CiReleaseSlotParser.CONFIG_PATH;

  private final CiReleaseSlotParser parser = new CiReleaseSlotParser();

  private CiConfigException refused(String content) {
    return assertThrows(CiConfigException.class, () -> parser.parse(PATH, content));
  }

  // --- the shapes that are the point -------------------------------------------------------------

  @Test
  public void anArchetypeNameIsAWholeFile() {
    // The 13-repository case: a spa frontend's entire release cycle, one line.
    CiReleaseSlots slots = parser.parse(PATH, "archetype: spa-frontend\n");

    assertEquals("spa-frontend", slots.archetype());
    assertTrue(slots.namesArchetype());
    assertNull(slots.releaseRequest(), "an unnamed slot is the archetype's to fill");
    assertNull(slots.release());
    assertTrue(slots.artifacts().isEmpty());
    assertNull(slots.userflows());
    assertEquals(PATH, slots.configPath());
  }

  @Test
  public void everySlotParsesAndTheStepSchemaIsTheTriggerFilesOwn() {
    CiReleaseSlots slots =
        parser.parse(
            PATH,
            """
            archetype: java-service
            release-request:
              - image: qits/build-images/maven-base:latest
                timeout-seconds: 1800
                script: ./mvnw verify
              - image: qits/build-images/node-base:latest
                gating: false
                script: npm run stories
            release:
              - image: qits/build-images/ci-base:latest
                build: true
                script: buildctl build
            artifacts:
              - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
              - { type: daemon, name: qits-ci-daemon }
            userflows: qits-ci
            """);

    assertEquals(2, slots.releaseRequest().steps().size());
    assertEquals(1800, slots.releaseRequest().steps().get(0).timeoutSeconds());
    assertTrue(slots.releaseRequest().steps().get(0).gating());
    // Per-step leniency and per-step strictness both come from CiConfigSchema, unchanged: a step
    // means the same thing here as in a trigger file, which is what makes the composition ordinary.
    assertEquals(false, slots.releaseRequest().steps().get(1).gating());
    assertTrue(slots.release().steps().get(0).build());

    assertEquals(2, slots.artifacts().size());
    SlotArtifact image = slots.artifacts().get(0);
    assertEquals(CiArtifact.Type.DOCKER, image.artifact().type());
    assertEquals("qits/qits-ci", image.artifact().name());
    assertEquals("out/sbom.json", image.sbomPath());
    assertTrue(image.hasSbom());
    // The one with no document declares none, rather than declaring an empty path.
    assertEquals("", slots.artifacts().get(1).sbomPath());
    assertEquals(false, slots.artifacts().get(1).hasSbom());

    assertEquals("qits-ci", slots.userflows().site());
    assertEquals(false, slots.userflows().derived());
  }

  @Test
  public void userflowsTrueMeansTheRepositorysOwnName() {
    // The site only the reader can resolve: this document holds no repository, so "true" says the
    // fact and leaves the name to whoever has one.
    CiReleaseSlots slots = parser.parse(PATH, "userflows: true\n");

    assertNotNull(slots.userflows());
    assertTrue(slots.userflows().derived());
    assertEquals("", slots.userflows().site());
  }

  @Test
  public void anArchetypeRecipeIsThisDocumentMinusOneKey() {
    String path = CiReleaseSlotParser.archetypePath("spa-frontend");
    CiReleaseSlots recipe =
        parser.parseArchetype(
            path,
            """
            release-request:
              - image: qits/build-images/node-base:latest
                script: npm ci && npm test
            """);

    assertEquals("", recipe.archetype());
    assertEquals(".config/qits/release-archetypes/spa-frontend.yml", recipe.configPath());
    assertEquals(1, recipe.releaseRequest().steps().size());
  }

  @Test
  public void aRecipeThatNamesARecipeIsRefused() {
    String path = CiReleaseSlotParser.archetypePath("java-service");
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () -> parser.parseArchetype(path, "archetype: spa-frontend\n"));

    // Recipes do not chain: a chain needs a depth limit, a cycle check and an override order, and
    // saying no once is cheaper than any of the three.
    assertTrue(refused.getMessage().contains(path), refused.getMessage());
    assertTrue(refused.getMessage().contains("archetype"), refused.getMessage());
  }

  // --- the refusals ------------------------------------------------------------------------------

  @Test
  public void anEmptyFileIsRefusedRatherThanReadAsDeclaringNothing() {
    assertTrue(refused("").getMessage().startsWith(PATH));
    assertTrue(refused("# only a comment\n").getMessage().startsWith(PATH));
  }

  @Test
  public void anUnknownTopLevelKeyIsRefused() {
    // The trigger file's own asymmetry, one file over: unknown TOP-LEVEL keys are refused because
    // what they cost is correctness, while unknown per-STEP keys stay lenient because what they cost
    // is a feature that was not there yet.
    CiConfigException refused = refused("archetpye: java-service\n");

    assertTrue(refused.getMessage().contains("archetpye"), refused.getMessage());
    assertTrue(refused.getMessage().startsWith(PATH), refused.getMessage());
  }

  @Test
  public void aNonMappingRootIsRefused() {
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, "- java-service\n"));
  }

  @Test
  public void aDuplicateKeyIsRefused() {
    // Strict for the trigger file's reason: a silently dropped slot is a release that publishes
    // nothing, which is the same class of failure as a silently widened selection.
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "release:\n  - {image: a, script: b}\nrelease:\n  - {image: c, script: d}\n"));
  }

  @Test
  public void aSlotThatIsNotAListIsRefused() {
    assertTrue(refused("release: ./mvnw verify\n").getMessage().contains("release"));
  }

  @Test
  public void anEmptySlotIsRefusedRatherThanReadAsInheriting() {
    // Omitting the key already says "inherit the archetype's". A declared-but-empty list reads like
    // an override that erased them on purpose, and it would not do that.
    assertTrue(refused("archetype: java-service\nrelease-request: []\n").getMessage().contains("release-request"));
  }

  @Test
  public void aBrokenStepIsRefusedNamingTheFileAndTheSlot() {
    CiConfigException refused = refused("release:\n  - image: alpine:3\n");

    assertTrue(refused.getMessage().startsWith(PATH), refused.getMessage());
    assertTrue(refused.getMessage().contains("'release'"), refused.getMessage());
    assertTrue(refused.getMessage().contains("script"), refused.getMessage());
  }

  @Test
  public void theStepSchemaStillRefusesBranches() {
    // Not this parser's rule — CiConfigSchema's, reached verbatim. A per-step filter over the run's
    // branch is inert decoration or a step that can never run, in a slot file exactly as in a
    // trigger file, and the run's branch here is the composer's decision.
    assertTrue(
        refused("release:\n  - image: alpine:3\n    script: x\n    branches: [main]\n")
            .getMessage()
            .contains("branches"));
  }

  @Test
  public void anUnknownArtifactKeyIsRefused() {
    assertTrue(
        refused("artifacts:\n  - { type: docker, name: qits/x, sbmo: a.json }\n")
            .getMessage()
            .contains("sbmo"));
  }

  @Test
  public void anEmptyArtifactListIsRefused() {
    assertTrue(refused("artifacts: []\n").getMessage().contains("artifacts"));
  }

  @Test
  public void anUnknownArtifactTypeIsRefused() {
    assertTrue(
        refused("artifacts:\n  - { type: helm, name: qits/x }\n").getMessage().contains("helm"));
  }

  @Test
  public void anArtifactNameOutsideTheComposableCharsetIsRefused() {
    // THE CHARSET GUARD, and it is the whole reason interpolation is safe. This value is written
    // into a generated shell script; anything that could stop being a word is refused here rather
    // than escaped there, because an escape is a claim about every shell that will ever read it.
    for (String hostile :
        new String[] {
          "qits/'; rm -rf /; '",
          "qits/$(id)",
          "qits/`id`",
          "qits/a b",
          "qits/\"x\"",
          "qits/x$HOME"
        }) {
      // Quoted as a YAML single-quoted scalar so the fixture itself is well-formed whatever the
      // value carries — the refusal under test is the parser's, never SnakeYAML's.
      CiConfigException refused =
          refused(
              "artifacts:\n  - { type: docker, name: '" + hostile.replace("'", "''") + "' }\n");
      assertTrue(refused.getMessage().contains("composable"), refused.getMessage());
    }
  }

  @Test
  public void everyCoordinateTheEstatePublishesIsComposable() {
    // The guard has to be an allow-list AND wide enough for the real fleet, or it is a migration
    // blocker rather than a safety property.
    for (String real :
        new String[] {
          "@qits/ui-components", "qits/qits-stt", "eu.wohlben.qits:qits-eventstream", "qits-ci-daemon"
        }) {
      CiReleaseSlots slots =
          parser.parse(
              PATH,
              "release:\n  - {image: a, script: b}\nartifacts:\n  - { type: npm, name: \""
                  + real
                  + "\" }\n");
      assertEquals(real, slots.artifacts().get(0).artifact().name());
    }
  }

  @Test
  public void anSbomPathMustPointInsideTheReleasesOwnCheckout() {
    assertTrue(
        refused("artifacts:\n  - { type: docker, name: qits/x, sbom: /etc/passwd }\n")
            .getMessage()
            .contains("downwards"));
    assertTrue(
        refused("artifacts:\n  - { type: docker, name: qits/x, sbom: ../other/sbom.json }\n")
            .getMessage()
            .contains("downwards"));
  }

  @Test
  public void anArchetypeNameThatIsNotAPathSegmentIsRefused() {
    // It becomes a URL segment against ANOTHER repository, so what it may contain is decided here.
    for (String hostile : new String[] {"../../etc/passwd", "Java-Service", "java service", "java/service"}) {
      assertTrue(
          refused("archetype: \"" + hostile + "\"\n").getMessage().contains("slug"),
          hostile);
    }
    assertEquals(false, CiReleaseSlotParser.isArchetypeName(null));
  }

  @Test
  public void userflowsFalseIsRefusedRatherThanReadAsAbsence() {
    // Two spellings of one fact is how two spellings drift. Omitting the key is the spelling.
    assertTrue(refused("userflows: false\n").getMessage().contains("omit the key"));
  }

  @Test
  public void aUserflowsSiteOutsideTheComposableCharsetIsRefused() {
    assertTrue(refused("userflows: \"qits ci\"\n").getMessage().contains("composable"));
  }
}
