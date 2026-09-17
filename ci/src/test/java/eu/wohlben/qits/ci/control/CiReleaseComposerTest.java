package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The composer, against <b>golden documents</b>.
 *
 * <p>That is the point of this file rather than an implementation detail of it. The composed
 * document is what lands in {@code ci_run.trigger_config} and is what every step of every release in
 * the estate will really run; a change to the prelude is a change to 47 repositories' behaviour made
 * in one place, and the only way that stays reviewable is if the diff is <b>the text itself</b>. So
 * the expectations are files under {@code src/test/resources/composed/}, and a change to this class
 * shows up as a diff a person can read line by line.
 *
 * <p><b>Regenerating is deliberate work, not a flag.</b> A missing golden writes the composed text
 * to {@code target/composed/} and fails naming both paths; you read the produced file, satisfy
 * yourself that the change is the one you meant, and copy it in. An {@code -Dupdate.goldens} switch
 * would make "the test agrees with itself" the default outcome, which is the one thing a golden must
 * never be able to do.
 *
 * <p>Plain JUnit and no fakes: {@link CiReleaseComposer} is a pure function of three arguments.
 */
public class CiReleaseComposerTest {

  private final CiReleaseSlotParser parser = new CiReleaseSlotParser();

  private static final CiRepoRef REPO =
      CiRepoRef.of("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", "qits", "qits-ci-service");

  private CiReleaseSlots slots(String content) {
    return parser.parse(CiReleaseSlotParser.CONFIG_PATH, content);
  }

  private CiReleaseSlots archetype(String name, String content) {
    return parser.parseArchetype(CiReleaseSlotParser.archetypePath(name), content);
  }

  // --- the three shapes the fleet is made of ------------------------------------------------------

  /** 15 repositories: the whole file is one line and the archetype is the pipeline. */
  private static final String SPA_FRONTEND =
      """
      release-request:
        - image: qits/build-images/node-base:latest
          timeout-seconds: 1800
          script: |
            npm ci --no-audit --no-fund
            npm run lint
            npm run test
            npm run build
      """;

  /** 19 repositories: a QA fold and a release publish, both building through the platform builder. */
  private static final String JAVA_SERVICE =
      """
      release-request:
        - image: qits/build-images/ci-base:latest
          build: true
          timeout-seconds: 3600
          script: |
            buildctl build --frontend dockerfile.v0 --local context=. --local dockerfile=docker
      release:
        - image: qits/build-images/ci-base:latest
          build: true
          timeout-seconds: 3600
          script: |
            buildctl build --frontend dockerfile.v0 \\
              --local context=. --local dockerfile=docker \\
              --output "type=image,name=$QITS_BUILD_REGISTRY/$QITS_IMAGE_REPOSITORY/qits-ci:$QITS_VERSION,push=true"
            buildctl build --frontend dockerfile.v0 --opt target=sbom \\
              --local context=. --local dockerfile=docker --output type=local,dest=out
      """;

  @Test
  public void anArchetypeAloneComposesTheQaPipelineAndNoRelease() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("11111111-2222-3333-4444-555555555555", "qits", "qits-observability-frontend"),
            slots("archetype: spa-frontend\n"),
            archetype("spa-frontend", SPA_FRONTEND));

    golden("spa-frontend-release-request.yml", composed.releaseRequestDocument());
    // AND NO RELEASE DOCUMENT. An SPA frontend publishes nothing — it is built into the consuming
    // service's image — so it has no ci-event-release.yml today, and "no steps declared for a phase"
    // must mean "no run" rather than a trivially green one.
    assertNull(composed.releaseDocument());
  }

  @Test
  public void aRepositorySlotReplacesTheArchetypesEntirely() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                archetype: java-service
                release-request:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: |
                      git submodule update --init
                      ./mvnw -q verify
                  - image: qits/build-images/maven-base:latest
                    gating: false
                    script: ./mvnw -q verify -Dit.test=BuildTriggerIT
                artifacts:
                  - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
                userflows: true
                """),
            archetype("java-service", JAVA_SERVICE));

    // WHOLE SLOT, never per-step: the repository's two QA steps are the pipeline, and the
    // archetype's QA step is not merged in anywhere. The release slot it did NOT declare is the
    // archetype's, unchanged.
    golden("java-service-override-release-request.yml", composed.releaseRequestDocument());
    golden("java-service-override-release.yml", composed.releaseDocument());
  }

  @Test
  public void aBespokeRepositoryComposesFromItsOwnSlotsAlone() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("99999999-8888-7777-6666-555555555555", "qits", "qits-ci-daemon"),
            slots(
                """
                release-request:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build --frontend dockerfile.v0 --opt target=binary
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    timeout-seconds: 3600
                    script: |
                      buildctl build --frontend dockerfile.v0 --opt target=binary --output type=local,dest=out
                      qits-publish daemon submit --name qits-ci-daemon --version "$QITS_VERSION" --file out/qits-ci-daemon
                artifacts:
                  - { type: daemon, name: qits-ci-daemon, sbom: out/sbom.json }
                """),
            null);

    golden("bespoke-release-request.yml", composed.releaseRequestDocument());
    golden("bespoke-release.yml", composed.releaseDocument());
  }

  /**
   * The one repository that cannot run on a platform step image: it BUILDS them.
   *
   * <p>qits-build-images-oci would bootstrap itself on {@code qits/build-images/*}, so it runs on
   * upstream {@code docker:28-dind} — {@code /bin/sh} and {@code /bin/ash}, no {@code /bin/bash};
   * {@code wget}, no {@code curl}. Composed against that image the wrapper used to die at {@code
   * bash: not found} before building anything, in the one repository whose bad release breaks every
   * other pipeline's step images.
   */
  @Test
  public void anImageWithoutBashRunsTheDeclaredScriptUnderSh() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("77777777-6666-5555-4444-333333333333", "qits", "qits-build-images-oci"),
            slots(
                """
                release:
                  - image: docker:28-dind
                    docker: true
                    timeout-seconds: 3600
                    script: |
                      docker build -t "$QITS_BUILD_REGISTRY/qits/build-images/ci-base:$QITS_VERSION" ci-base
                      docker push "$QITS_BUILD_REGISTRY/qits/build-images/ci-base:$QITS_VERSION"
                artifacts:
                  - { type: docker, name: qits/build-images/ci-base, sbom: out/sbom.json }
                """),
            null);

    golden("no-bash-image-release.yml", composed.releaseDocument());

    // The two run-time checks, as behaviour rather than as text: nothing in the composed step may
    // reach bash or curl without having asked for them first, on ANY image — the composer cannot
    // see inside one, so there is no per-image arm here to get wrong.
    String document = composed.releaseDocument();
    assertTrue(
        document.contains("if command -v bash > /dev/null 2>&1; then")
            && document.contains("  sh -eu /tmp/qits-slot.sh"),
        document);
    assertTrue(
        document.contains("elif command -v wget > /dev/null 2>&1; then")
            && document.contains("wget -q -O /tmp/qits-bin/qits"),
        document);
    // And the refusal names the image, so an author reads a sentence about their own file rather
    // than `curl: not found` out of a line they never wrote.
    assertTrue(
        document.contains("the image for this step (docker:28-dind) has neither curl nor wget"),
        document);
    // Each named program is reached exactly once and only from inside its own test, so neither can
    // become an unguarded line again while the assertions above still pass.
    assertEquals(1, occurrences(document, "bash -eu"), document);
    assertEquals(1, occurrences(document, "curl -fsSL"), document);
    assertTrue(
        document.indexOf("command -v bash") < document.indexOf("bash -eu")
            && document.indexOf("command -v curl") < document.indexOf("curl -fsSL"),
        document);
  }

  @Test
  public void aBashImageStillRunsTheSameInvocationsItRanBefore() {
    // THE OTHER HALF, and the reason the fallback is a run-time check rather than a compose-time
    // one: for the 48 repositories whose images do have bash, the chosen arm is byte-identical to
    // what the composer emitted before the fallback existed. The goldens hold the whole documents;
    // this pins the two lines that a careless "portability" change would rewrite.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));

    assertTrue(composed.releaseRequestDocument().contains("  bash -eu /tmp/qits-slot.sh\n"));
    assertTrue(
        composed
            .releaseDocument()
            .contains(
                "curl -fsSL --retry 2 --retry-delay 2 -o /tmp/qits-bin/qits"
                    + " \"$QITS_ARTIFACTS_URL/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"\n"),
        composed.releaseDocument());
  }

  // --- the properties the goldens are there to hold ------------------------------------------------

  @Test
  public void everyComposedDocumentParsesAsAnOrdinaryTriggerFile() {
    // THE PROPERTY THE WHOLE DESIGN RESTS ON. A composed document is not a new file kind: it is the
    // existing trigger schema, so restart-reparse (which reads config_path + trigger_config off the
    // row) works on it with no code that knows it was composed.
    CiEventTriggerParser triggers = new CiEventTriggerParser();
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                archetype: java-service
                artifacts:
                  - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
                """),
            archetype("java-service", JAVA_SERVICE));

    CiEventTrigger qa =
        triggers.parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseRequestDocument());
    assertEquals(CiReleaseComposer.RELEASE_REQUEST_EVENT, qa.eventName());
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, qa.configPath());
    assertEquals("backingBranch", qa.checkout().branchPath());
    assertEquals("mergedSha", qa.checkout().shaPath());
    assertEquals(false, qa.checkout().optional(), "a request naming no fold has nothing to gate");
    assertTrue(qa.artifacts().isEmpty(), "a fold publishes nothing");

    CiEventTrigger release =
        triggers.parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseDocument());
    assertEquals(CiReleaseComposer.RELEASE_EVENT, release.eventName());
    assertEquals("version", release.checkout().branchPath());
    assertEquals("commitSha", release.checkout().shaPath());
    assertTrue(release.checkout().optional(), "the additive-commitSha transition arm");
    assertEquals(1, release.artifacts().size());
    assertEquals(CiArtifact.Type.DOCKER, release.artifacts().get(0).type());
    assertEquals("qits/qits-ci", release.artifacts().get(0).name());

    // And the round trip is byte-exact through the block scalar, which is the half a schema check
    // cannot see: a script that came back one space short would still parse and would run wrong.
    assertEquals(1, release.pipeline().steps().size());
    assertTrue(release.pipeline().steps().get(0).script().startsWith("set -eu\n"));
    assertTrue(
        release.pipeline().steps().get(0).script().contains("--output \"type=image,name="),
        "the declared script survives the heredoc and the block scalar verbatim");
  }

  @Test
  public void aScriptCarryingTheHeredocDelimiterIsRefused() {
    // The one way out of a quoted heredoc, refused at composition and named after the file a person
    // edits — never a wrapper that ends halfway through and runs the rest as shell.
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots(
                        "release-request:\n"
                            + "  - image: alpine:3\n"
                            + "    script: |\n"
                            + "      echo one\n"
                            + "      "
                            + CiReleaseComposer.HEREDOC_DELIMITER
                            + "\n"
                            + "      rm -rf /\n"),
                    null));

    assertTrue(refused.getMessage().startsWith(CiReleaseSlotParser.CONFIG_PATH), refused.getMessage());
    assertTrue(refused.getMessage().contains(CiReleaseComposer.HEREDOC_DELIMITER));
  }

  @Test
  public void theRefusalNamesTheArchetypeWhenTheScriptIsTheArchetypes() {
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots("archetype: broken\n"),
                    archetype(
                        "broken",
                        "release-request:\n  - image: alpine:3\n    script: echo "
                            + CiReleaseComposer.HEREDOC_DELIMITER
                            + "\n")));

    // A repository must never be told to fix a file it does not own.
    assertTrue(
        refused.getMessage().startsWith(".config/qits/release-archetypes/broken.yml"),
        refused.getMessage());
  }

  @Test
  public void artifactsWithNoReleasePipelineAreRefused() {
    // A declaration is a claim, and a claim with no pipeline behind it announces a SoftwareRelease
    // for bytes nothing published.
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots(
                        "release-request:\n  - {image: alpine:3, script: echo qa}\n"
                            + "artifacts:\n  - { type: docker, name: qits/qits-ci }\n"),
                    null));

    assertTrue(refused.getMessage().contains("no pipeline behind it"), refused.getMessage());
  }

  @Test
  public void anIdAddressedCandidateSelectsOnItsStorageId() {
    // The compatibility arm every address in this engine carries. Pre-cutover the id IS the name;
    // post-cutover a candidate with no name is one nothing can address anyway.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("qits-legacy-repo"),
            slots("release-request:\n  - {image: alpine:3, script: echo qa}\n"),
            null);

    assertTrue(
        composed.releaseRequestDocument().contains("- repoName: { exact: 'qits-legacy-repo' }"),
        composed.releaseRequestDocument());
  }

  @Test
  public void theSbomPostludeGoesOnTheLastBuildingStep() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build --opt target=binary
                  - image: qits/build-images/node-base:latest
                    gating: false
                    script: npm run docs
                artifacts:
                  - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
                """),
            null);

    // The document is produced by the build, so it is submitted from the step that produced it —
    // and BEFORE that step's exit code, which is what makes "SBOM before green" structural.
    String document = composed.releaseDocument();
    int build = document.indexOf("buildctl build --opt target=binary");
    int submit = document.indexOf("qits artifacts publish sbom submit");
    int docs = document.indexOf("npm run docs");
    assertTrue(build > 0 && submit > build && docs > submit, document);
  }

  // --- goldens -------------------------------------------------------------------------------------

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
      count++;
    }
    return count;
  }

  private void golden(String name, String actual) {
    assertNotNull(actual, "nothing was composed for " + name);
    String expected = read("composed/" + name);
    if (expected == null) {
      Path written = write(name, actual);
      fail(
          "No golden for "
              + name
              + ". The composed document was written to "
              + written
              + " — read it, satisfy yourself the change is the one you meant, and copy it to"
              + " ci/src/test/resources/composed/"
              + name);
      return;
    }
    assertEquals(expected, actual, name);
  }

  private static String read(String resource) {
    try (InputStream in =
        CiReleaseComposerTest.class.getClassLoader().getResourceAsStream(resource)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + resource, e);
    }
  }

  private static Path write(String name, String content) {
    try {
      Path dir = Path.of("target", "composed");
      Files.createDirectories(dir);
      Path file = dir.resolve(name);
      Files.writeString(file, content, StandardCharsets.UTF_8);
      return file.toAbsolutePath();
    } catch (IOException e) {
      throw new IllegalStateException("could not write the composed document for " + name, e);
    }
  }
}
