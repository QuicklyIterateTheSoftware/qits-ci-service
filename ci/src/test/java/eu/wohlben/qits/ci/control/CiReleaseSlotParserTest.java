package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import java.util.List;
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
                script: npm run stories
            release:
              - image: qits/build-images/ci-base:latest
                build: true
                script: buildctl build
            artifacts:
              - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
              - { type: daemon, name: qits-ci-daemon, sbom: out/daemon-sbom.json }
              - { type: docs, name: "@apidocs/qits-ci" }
            userflows: qits-ci
            """);

    assertEquals(2, slots.releaseRequest().steps().size());
    assertEquals(1800, slots.releaseRequest().steps().get(0).timeoutSeconds());
    // Per-step leniency and per-step strictness both come from CiConfigSchema, unchanged: a step
    // means the same thing here as in a trigger file, which is what makes the composition ordinary
    // — and it is why `gating:` is refused in a slot file too (ticket 9441bc6e), asserted below.
    assertTrue(slots.release().steps().get(0).build());

    assertEquals(3, slots.artifacts().size());
    SlotArtifact image = slots.artifacts().get(0);
    assertEquals(CiArtifact.Type.DOCKER, image.artifact().type());
    assertEquals("qits/qits-ci", image.artifact().name());
    assertEquals("out/sbom.json", image.sbomPath());
    assertTrue(image.hasSbom());
    assertEquals("out/daemon-sbom.json", slots.artifacts().get(1).sbomPath());
    assertTrue(slots.artifacts().get(1).hasSbom());
    // docs is the one type sbom: is not required on, and the one with no document declares none,
    // rather than declaring an empty path (qits-664).
    assertEquals("", slots.artifacts().get(2).sbomPath());
    assertEquals(false, slots.artifacts().get(2).hasSbom());

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
  public void aStepDeclaringGatingIsRefusedHereToo() {
    // The step schema is CiConfigSchema's verbatim, so a slot file is held to exactly the trigger
    // file's rule: `gating:` is a parse error (ticket 9441bc6e), and the refusal names the file and
    // the slot rather than saying "Step 1" about one of two lists.
    CiConfigException refused =
        refused(
            "release-request:\n  - {image: a, script: b}\n"
                + "  - {image: c, script: d, gating: false}\n");
    assertTrue(refused.getMessage().contains("release-request"), refused.getMessage());
    assertTrue(refused.getMessage().contains("gating"), refused.getMessage());
    assertTrue(refused.getMessage().contains("9441bc6e"), refused.getMessage());
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
                  + "\", sbom: sbom.json }\n");
      assertEquals(real, slots.artifacts().get(0).artifact().name());
    }
  }

  // --- announce: is deleted (qits-648) ----------------------------------------------------------

  @Test
  public void announceIsAnUnknownKeyNamingTheEntryAndWhatReplacedIt() {
    for (String value : new String[] {"if-published", "always"}) {
      String message =
          refused(
                  "artifacts:\n  - { type: maven, name: \"eu.wohlben.qits:x\", sbom: s.json }\n  -"
                      + " { type: npm,"
                      + " name: \"@qits/x\", sbom: s.json, announce: "
                      + value
                      + " }\n")
              .getMessage();
      assertTrue(
          message.startsWith(PATH + ": artifact 1 declares an unknown key 'announce'"), message);
      assertTrue(
          message.contains("announce: was deleted (qits-648); publish: if-changed replaced it"),
          message);
    }
  }

  @Test
  public void everyMavenAndNpmEntryIsUploadedNow() {
    CiReleaseSlots slots =
        parser.parse(
            PATH,
            """
            artifacts:
              - { type: maven, name: "eu.wohlben.qits:x-golden-masters", sbom: target/sbom.json }
              - { type: npm, name: "@qits/x", sbom: sbom.json }
              - { type: docker, name: qits/x, sbom: out/sbom.json }
            """);
    assertTrue(slots.artifacts().get(0).uploaded());
    assertTrue(slots.artifacts().get(1).uploaded());
    assertFalse(slots.artifacts().get(2).uploaded());
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

  // --- the qits-620 vocabulary: path, link, publish, include, contracts -------------------------

  @Test
  public void pathAndLinkAreAcceptedOnMavenAndNpmAndDefaultSensibly() {
    CiReleaseSlots slots =
        parser.parse(
            PATH,
            """
            artifacts:
              - { type: maven, name: "eu.wohlben.qits:qits-blobstore", path: blobstore, sbom: blobstore/target/sbom.json }
              - { type: maven, name: "eu.wohlben.qits:qits-registries-common", path: common, link: [qits-blobstore], sbom: common/target/sbom.json }
              - { type: maven, name: "eu.wohlben.qits:qits-registries-npm", link: [qits-blobstore, qits-registries-common], sbom: target/sbom.json }
              - { type: npm, name: "@qits/ui-components", path: dist/qits-spa-ui-components, sbom: sbom.json }
              - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
            """);

    SlotArtifact blobstore = slots.artifacts().get(0);
    assertEquals("blobstore", blobstore.path());
    assertTrue(blobstore.link().isEmpty());
    assertEquals(List.of("qits-blobstore"), slots.artifacts().get(1).link());
    assertEquals(
        List.of("qits-blobstore", "qits-registries-common"),
        slots.artifacts().get(2).link(),
        "declared order is kept");
    assertEquals(".", slots.artifacts().get(2).path(), "a maven entry naming no path is the root");
    assertEquals("dist/qits-spa-ui-components", slots.artifacts().get(3).path());
    assertEquals("", slots.artifacts().get(4).path(), "a docker entry has nothing to upload from");
    for (SlotArtifact artifact : slots.artifacts()) {
      assertEquals(CiArtifact.Publish.ALWAYS, artifact.artifact().publish());
    }
  }

  @Test
  public void aPathMustPointInsideTheReleasesOwnCheckout() {
    String message =
        refused(
                "artifacts:\n  - { type: maven, name: \"a:b\", sbom: s.json, path: ../sibling }\n")
            .getMessage();
    assertTrue(message.contains("path") && message.contains("downwards"), message);
    refused("artifacts:\n  - { type: npm, name: \"@qits/x\", sbom: s.json, path: /dist }\n");
  }

  @Test
  public void aPathOnADockerOrDaemonEntryIsRefusedNamingTheEntry() {
    for (String type : new String[] {"docker", "daemon"}) {
      String message =
          refused(
                  "artifacts:\n  - { type: "
                      + type
                      + ", name: qits-thing, sbom: out/sbom.json, path: out }\n")
              .getMessage();
      assertTrue(message.startsWith(PATH + ": artifact 0 ({ type: " + type), message);
      assertTrue(message.contains("qits-thing"), message);
      assertTrue(message.contains("only a maven or npm entry"), message);
    }
  }

  @Test
  public void anApidocsEntryNamesItsOpenApiFile() {
    CiReleaseSlots slots =
        parser.parse(
            PATH,
            """
            artifacts:
              - { type: docs, name: "@apidocs/qits-projects", path: docs/openapi.yml }
              - { type: docs, name: "@apidocs/qits-ci", path: api/openapi.json }
              - { type: docs, name: "@apidocs/qits-x", path: openapi.yaml }
              - { type: docs, name: "@qits/ui-components" }
            """);
    assertEquals("docs/openapi.yml", slots.artifacts().get(0).path());
    assertTrue(slots.artifacts().get(0).publishesApidocs());
    assertEquals("api/openapi.json", slots.artifacts().get(1).path());
    assertEquals("openapi.yaml", slots.artifacts().get(2).path());
    assertEquals("", slots.artifacts().get(3).path(), "a docs entry naming no path is declared-only");
    assertFalse(slots.artifacts().get(3).publishesApidocs());
  }

  @Test
  public void aDocsPathOutsideApidocsOrNotAnOpenApiDocumentIsRefused() {
    String outside =
        refused("artifacts:\n  - { type: docs, name: \"@qits/ui\", path: docs/openapi.yml }\n")
            .getMessage();
    assertTrue(outside.startsWith(PATH + ": artifact 0 ({ type: docs, name: @qits/ui })"), outside);
    assertTrue(
        outside.contains(
            "only a maven or npm entry, or an @apidocs docs entry naming its OpenAPI file"),
        outside);
    String notOpenApi =
        refused("artifacts:\n  - { type: docs, name: \"@apidocs/x\", path: docs/site.tgz }\n")
            .getMessage();
    assertTrue(
        notOpenApi.contains(
            "@apidocs path 'docs/site.tgz' is not an OpenAPI document (.yml, .yaml or .json)"),
        notOpenApi);
    assertTrue(
        refused("artifacts:\n  - { type: docs, name: \"@apidocs/x\", path: ../openapi.yml }\n")
            .getMessage()
            .contains("downwards"));
  }

  @Test
  public void aLinkOnANonMavenEntryIsRefused() {
    String message =
        refused(
                """
                artifacts:
                  - { type: maven, name: "eu.wohlben.qits:qits-blobstore", sbom: target/sbom.json }
                  - { type: npm, name: "@qits/x", sbom: sbom.json, link: [qits-blobstore] }
                """)
            .getMessage();
    assertTrue(message.contains("artifact 1") && message.contains("@qits/x"), message);
    assertTrue(message.contains("only a maven entry bundles"), message);
  }

  @Test
  public void aLinkToAnUnknownArtifactIdIsRefusedNamingBoth() {
    String message =
        refused(
                """
                artifacts:
                  - { type: maven, name: "eu.wohlben.qits:qits-registries-common", sbom: target/sbom.json, link: [qits-blobstor] }
                """)
            .getMessage();
    assertTrue(message.contains("eu.wohlben.qits:qits-registries-common"), message);
    assertTrue(message.contains("'qits-blobstor'"), message);
    assertTrue(message.contains("not a maven entry of this release.yml"), message);
  }

  @Test
  public void aLinkToANonMavenEntryIsRefused() {
    // The npm package and the docker image end in the very word linked; only a MAVEN entry's
    // artifactId is a link target.
    String message =
        refused(
                """
                artifacts:
                  - { type: npm, name: "@qits/qits-blobstore", sbom: sbom.json }
                  - { type: docker, name: qits/qits-blobstore, sbom: out/sbom.json }
                  - { type: maven, name: "eu.wohlben.qits:qits-registries-common", sbom: target/sbom.json, link: [qits-blobstore] }
                """)
            .getMessage();
    assertTrue(message.contains("artifact 2"), message);
    assertTrue(message.contains("'qits-blobstore'"), message);
    assertTrue(message.contains("not a maven entry"), message);
  }

  @Test
  public void anEntryLinkingItselfIsRefused() {
    String message =
        refused(
                "artifacts:\n  - { type: maven, name: \"eu.wohlben.qits:qits-blobstore\", sbom:"
                    + " target/sbom.json, link: [qits-blobstore] }\n")
            .getMessage();
    assertTrue(message.contains("eu.wohlben.qits:qits-blobstore"), message);
    assertTrue(message.endsWith("links itself"), message);
  }

  @Test
  public void aLinkCycleIsRefusedNamingIt() {
    String message =
        refused(
                """
                artifacts:
                  - { type: maven, name: "g:a", sbom: a/sbom.json, link: [b] }
                  - { type: maven, name: "g:b", sbom: b/sbom.json, link: [c] }
                  - { type: maven, name: "g:c", sbom: c/sbom.json, link: [a] }
                """)
            .getMessage();
    assertTrue(message.contains("forms a cycle (a → b → c → a)"), message);

    String two =
        refused(
                """
                artifacts:
                  - { type: maven, name: "g:root", sbom: root/sbom.json }
                  - { type: maven, name: "g:a", sbom: a/sbom.json, link: [root, b] }
                  - { type: maven, name: "g:b", sbom: b/sbom.json, link: [a] }
                """)
            .getMessage();
    assertTrue(two.contains("artifact 1") && two.contains("(a → b → a)"), two);
  }

  @Test
  public void aLinkIsANonEmptyListOfDistinctNames() {
    refused("artifacts:\n  - { type: maven, name: \"g:a\", sbom: a/sbom.json, link: [] }\n");
    refused("artifacts:\n  - { type: maven, name: \"g:a\", sbom: a/sbom.json, link: b }\n");
    assertTrue(
        refused(
                "artifacts:\n  - { type: maven, name: \"g:b\", sbom: b/sbom.json }\n  - { type:"
                    + " maven, name: \"g:a\", sbom: a/sbom.json, link: [b, b] }\n")
            .getMessage()
            .contains("twice"));
  }

  @Test
  public void publishIfChangedIsAcceptedOnMavenAndNpmWithAnSbom() {
    CiReleaseSlots slots =
        parser.parse(
            PATH,
            """
            artifacts:
              - { type: maven, name: "g:a", sbom: target/sbom.json, publish: if-changed, include: ["eu/wohlben/**", "*.properties"] }
              - { type: npm, name: "@qits/x", path: dist/x, sbom: sbom.json, publish: if-changed }
              - { type: maven, name: "g:b", sbom: b/sbom.json, publish: always }
            """);
    assertEquals(CiArtifact.Publish.IF_CHANGED, slots.artifacts().get(0).artifact().publish());
    assertEquals(List.of("eu/wohlben/**", "*.properties"), slots.artifacts().get(0).include());
    assertEquals(CiArtifact.Publish.IF_CHANGED, slots.artifacts().get(1).artifact().publish());
    assertTrue(slots.artifacts().get(1).include().isEmpty());
    assertEquals(CiArtifact.Publish.ALWAYS, slots.artifacts().get(2).artifact().publish());
  }

  @Test
  public void publishIsRefusedOnEveryOtherTypeEvenSpelledAlways() {
    for (String type : new String[] {"docker", "daemon", "docs"}) {
      for (String value : new String[] {"if-changed", "always"}) {
        String message =
            refused(
                    "artifacts:\n  - { type: "
                        + type
                        + ", name: qits/x, sbom: s.json, publish: "
                        + value
                        + " }\n")
                .getMessage();
        assertTrue(
            message.startsWith(PATH + ": artifact 0 ({ type: " + type + ", name: qits/x })"),
            message);
        assertTrue(
            message.contains(
                "declares publish: "
                    + value
                    + " — only a maven or npm entry is published by the platform; a docker,"
                    + " daemon or docs entry is published by its own step"),
            message);
      }
    }
  }

  @Test
  public void anUnknownPublishValueIsRefused() {
    String message =
        refused("artifacts:\n  - { type: maven, name: \"a:b\", sbom: s.json, publish: sometimes }\n")
            .getMessage();
    assertTrue(message.startsWith(PATH + ": artifact 0 ({ type: maven, name: a:b })"), message);
    assertTrue(
        message.contains("declares publish 'sometimes' — it is 'always' (the default) or 'if-changed'"),
        message);
  }

  @Test
  public void ifChangedWithoutAnSbomIsRefused() {
    String message =
        refused("artifacts:\n  - { type: npm, name: \"@qits/x\", publish: if-changed }\n")
            .getMessage();
    assertTrue(message.contains("@qits/x"), message);
    assertTrue(
        message.contains(
            "declares publish: if-changed and no sbom — the change decision hashes the SBOM with"
                + " the content"),
        message);
  }

  // --- qits-664: sbom: is mandatory on every type but docs ---------------------------------------

  @Test
  public void aMavenNpmDockerOrDaemonEntryWithNoSbomIsRefused() {
    for (String type : new String[] {"maven", "npm", "docker", "daemon"}) {
      String name = type.equals("maven") ? "eu.wohlben.qits:x" : "qits/x";
      String message =
          refused(
                  "artifacts:\n  - { type: "
                      + type
                      + ", name: \""
                      + name
                      + "\" }\n  - { type: "
                      + type
                      + ", name: \""
                      + name
                      + "2\" }\n")
              .getMessage();
      assertTrue(message.startsWith(PATH), message);
      assertTrue(
          message.contains("artifact 0 (" + type + " " + name + ") declares no sbom:"), message);
      assertTrue(
          message.contains("every software artifact needs one; contracts go under contracts:"),
          message);
    }
  }

  @Test
  public void aDocsEntryWithNoSbomIsAccepted() {
    // docs is the one type sbom: is not required on — it names no CycloneDX document, it names an
    // OpenAPI file (@apidocs) or nothing at all.
    CiReleaseSlots slots =
        parser.parse(PATH, "artifacts:\n  - { type: docs, name: \"@qits/ui-components\" }\n");
    assertEquals(1, slots.artifacts().size());
    assertFalse(slots.artifacts().get(0).hasSbom());
  }

  @Test
  public void aContractsEntryTakesNoSbomAndIsNotHitByTheArtifactsRule() {
    // contracts: is parsed by CiContracts, a path that never reaches parseArtifact, so the
    // mandatory-sbom rule above has nothing to say about it.
    CiReleaseSlots slots =
        parser.parse(
            PATH,
            "contracts:\n  application: qits-projects\n  golden-masters: { from:"
                + " golden-masters/, packages: [maven, npm] }\n");
    assertNotNull(slots.contracts());
    assertTrue(slots.artifacts().isEmpty());
  }

  @Test
  public void includeIsRefusedOffAnIfChangedMavenOrNpmEntry() {
    String onDocker =
        refused(
                "artifacts:\n  - { type: docker, name: qits/x, sbom: out/sbom.json, include:"
                    + " [\"a/**\"] }\n")
            .getMessage();
    assertTrue(onDocker.contains("declares include — only a maven or npm entry is hashed"), onDocker);
    String onAlways =
        refused(
                "artifacts:\n  - { type: npm, name: \"@qits/x\", sbom: sbom.json, include:"
                    + " [\"dist/**\"] }\n")
            .getMessage();
    assertTrue(onAlways.contains("@qits/x"), onAlways);
    assertTrue(
        onAlways.contains(
            "declares include, which only narrows the if-changed hash; it means nothing on a"
                + " publish: always entry"),
        onAlways);
    refused(
        "artifacts:\n  - { type: npm, name: \"@qits/x\", sbom: s.json, publish: if-changed,"
            + " include: [] }\n");
    assertTrue(
        refused(
                "artifacts:\n  - { type: npm, name: \"@qits/x\", sbom: s.json, publish:"
                    + " if-changed, include: [\"{a,b}/**\"] }\n")
            .getMessage()
            .contains("not composable"));
  }

  @Test
  public void contractsAreParsedInAFileAndRefusedInARecipe() {
    String declaration =
        "contracts:\n  application: qits-projects\n  golden-masters: { from: golden-masters/,"
            + " packages: [maven, npm] }\n";
    CiContracts contracts = parser.parse(PATH, declaration).contracts();
    assertEquals("qits-projects", contracts.application());
    assertEquals("golden-masters/", contracts.goldenMasters().from());
    assertNull(parser.parse(PATH, "archetype: java-service\n").contracts());
    CiConfigException recipe =
        assertThrows(
            CiConfigException.class,
            () -> parser.parseArchetype(".config/qits/release-archetypes/x.yml", declaration));
    assertTrue(
        recipe.getMessage().startsWith(".config/qits/release-archetypes/x.yml: an archetype recipe"
            + " may not declare 'contracts'"),
        recipe.getMessage());
  }

  @Test
  public void theVocabularyMessagesNameEveryKey() {
    assertTrue(
        refused("artefacts: []\n")
            .getMessage()
            .contains(
                "'archetype', 'release-request', 'release', 'artifacts', 'userflows' and"
                    + " 'contracts'"));
    String bogus = refused("artifacts:\n  - { type: maven, name: \"a:b\", bogus: 1 }\n").getMessage();
    assertTrue(
        bogus.endsWith(
            "{ type, name, sbom[, publish][, path][, link][, include] } ({ type, name } for a"
                + " docs entry, which needs no sbom)"),
        "no retired-key hint on a key that never existed: " + bogus);
  }
}
