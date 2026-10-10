package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PACKAGED java-service recipe publishes a repository's declared {@code type: maven} modules
 * with no {@code release:} override (qits-890).
 *
 * <p>Two halves. The composition: the release slot's last step is the recipe's {@code maven-base}
 * step, so the publishing postlude — the maven upload, {@code @apidocs} — lands there and not on
 * the image step, whether or not anything is declared for it to build. And the declared script
 * itself, EXECUTED under {@code bash} and {@code sh} in a scratch tree whose {@code ./mvnw} only
 * records its arguments: what it reads out of {@code release.yml}, the one invocation it makes, the
 * no-op it is for a repository declaring no jar, and the refusal for an entry it cannot read.
 *
 * <p>Plain JUnit, {@code PackagedReleaseArchetypesTest}'s reason: parser, composer and classpath.
 */
public class JavaServiceReleaseArchetypeTest {

  private static final CiRepoRef REPO = CiRepoRef.of("repo-1", "qits", "qits-githost-service");

  /** qits-githost-service's own declaration, which is what qits-890 is about. */
  private static final String WITH_MAVEN =
      """
      archetype: java-service
      artifacts:
        - { type: docker, name: qits/qits-githost, sbom: .sbom/sbom.json }
        # prose naming - { type: maven, name: "x:y", path: ignored } is a comment, never an entry
        - { type: maven, name: "eu.wohlben.qits:qits-githost-events", path: githost-events, sbom: githost-events/target/sbom.json }
        - { type: docs, name: "@apidocs/qits-githost", path: docs/openapi.yml }
      userflows: qits-githost
      """;

  private static final String WITHOUT_MAVEN =
      """
      archetype: java-service
      artifacts:
        - { type: docker, name: qits/qits-idp, sbom: .sbom/sbom.json }
        - { type: docs, name: "@apidocs/qits-idp", path: docs/openapi.yml }
      userflows: qits-idp
      """;

  /** qits-containers-service's: two modules, the second linking nothing, declared before the image. */
  private static final String TWO_MODULES =
      """
      archetype: java-service
      artifacts:
        - { type: maven, name: "eu.wohlben.qits:qits-containers-core", path: core, sbom: core/target/sbom.json }
        - { type: maven, name: "eu.wohlben.qits:qits-containers-client", path: client, sbom: client/target/sbom.json }
        - { type: docker, name: qits/qits-containers, sbom: .sbom/sbom.json }
      """;

  private final CiReleaseSlotParser parser = new CiReleaseSlotParser();
  private final CiEventTriggerParser triggerParser = new CiEventTriggerParser();

  private CiReleaseSlots recipe() throws Exception {
    String resource = CiReleaseArchetypes.PACKAGED_DIR + "java-service.yml";
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, resource + " is not on the classpath");
      return parser.parseArchetype(
          CiReleaseArchetypes.sourcePath("java-service"),
          new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private List<CiStepDecl> composedReleaseSteps(String releaseYml) throws Exception {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, parser.parse(CiReleaseSlotParser.CONFIG_PATH, releaseYml), recipe());
    return triggerParser
        .parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseDocument())
        .pipeline()
        .steps();
  }

  // --- the composition --------------------------------------------------------------------------

  @Test
  public void theReleaseSlotEndsOnTheMavenStepWhichCarriesTheWholePublishingPostlude()
      throws Exception {
    List<CiStepDecl> steps = composedReleaseSteps(WITH_MAVEN);

    assertEquals(2, steps.size());
    CiStepDecl image = steps.get(0);
    CiStepDecl jars = steps.get(1);
    assertEquals("qits/build-images/node-docker-base:latest", image.image());
    assertTrue(image.build());
    assertEquals("qits/build-images/maven-base:latest", jars.image());
    assertFalse(jars.build());
    assertEquals("", jars.user(), "maven-library's release step runs as the image's user too");

    // The publishes are the LAST step's, and only its.
    String publishJar =
        "qits artifacts publish maven --name 'eu.wohlben.qits:qits-githost-events' --path"
            + " 'githost-events' --sbom 'githost-events/target/sbom.json'";
    assertTrue(jars.script().contains(publishJar), jars.script());
    assertTrue(
        jars.script().contains("qits artifacts publish docs submit --site '@apidocs/qits-githost'"),
        jars.script());
    assertFalse(image.script().contains("qits artifacts publish maven"), image.script());
    assertFalse(image.script().contains("qits artifacts publish docs"), image.script());

    // Every step submits each SBOM its tree holds; the last checks both arrived.
    assertTrue(image.script().contains("--file '.sbom/sbom.json'"), image.script());
    assertTrue(jars.script().contains("--file 'githost-events/target/sbom.json'"), jars.script());
    assertTrue(
        jars.script().contains("qits artifacts publish exists sbom 'docker/qits/qits-githost'"),
        jars.script());
    assertTrue(
        jars.script()
            .contains(
                "qits artifacts publish exists sbom 'maven/eu.wohlben.qits:qits-githost-events'"),
        jars.script());
    assertFalse(image.script().contains("publish exists sbom"), image.script());

    // The CLI is on PATH for the maven step as for every release step, whatever its image.
    assertTrue(jars.script().contains("PATH=\"" + CiReleaseComposer.CLI_DIR + ":$PATH\""));
  }

  @Test
  public void withNoMavenEntryTheSameTwoStepsComposeAndTheApidocsStillPublishFromTheLast()
      throws Exception {
    List<CiStepDecl> steps = composedReleaseSteps(WITHOUT_MAVEN);

    assertEquals(2, steps.size());
    assertEquals("qits/build-images/maven-base:latest", steps.get(1).image());
    assertFalse(steps.get(1).script().contains("qits artifacts publish maven"));
    assertTrue(
        steps.get(1).script().contains("qits artifacts publish docs submit --site '@apidocs/qits-idp'"));
    assertTrue(
        steps.get(1).script().contains("qits artifacts publish exists sbom 'docker/qits/qits-idp'"));
  }

  // --- the declared script, executed --------------------------------------------------------------

  @Test
  public void theMavenStepBuildsTheDeclaredModuleAndItsParentInOneInvocation(@TempDir Path dir)
      throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Run run = run(dir.resolve("githost-" + shell), shell, WITH_MAVEN, true);
      assertEquals(0, run.exit(), shell + ":\n" + run.output());
      assertEquals(
          List.of(
              "-B -ntp -s .qits-maven-settings.xml -pl githost-events -am -DskipTests package"
                  + " org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom -DoutputFormat=json"
                  + " -DoutputName=sbom -DschemaVersion=1.6"
                  + " -Dqits.maven.repository.url=https://registry.qits.example.invalid/artifacts/maven/maven"),
          run.mvnw(),
          shell);
    }
  }

  @Test
  public void twoModulesAreOneCommaJoinedProjectList(@TempDir Path dir) throws Exception {
    Run run = run(dir, "bash", TWO_MODULES, false);
    assertEquals(0, run.exit(), run.output());
    assertEquals(1, run.mvnw().size(), run.output());
    // No settings file in this tree, so none is passed — maven-library's guard on an older tag.
    assertTrue(run.mvnw().get(0).startsWith("-B -ntp -pl core,client -am -DskipTests package"));
  }

  @Test
  public void anEntryWithNoPathIsTheReactorRoot(@TempDir Path dir) throws Exception {
    Run run =
        run(
            dir,
            "bash",
            "archetype: java-service\nartifacts:\n"
                + "  - { type: maven, name: \"g:a\", sbom: target/sbom.json }\n",
            false);
    assertEquals(0, run.exit(), run.output());
    assertTrue(run.mvnw().get(0).contains(" -pl . -am "), run.mvnw().toString());
  }

  @Test
  public void noMavenEntryIsOneLineAndExitZeroWithNoBuild(@TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Run run = run(dir.resolve(shell), shell, WITHOUT_MAVEN, true);
      assertEquals(0, run.exit(), run.output());
      assertEquals(List.of(), run.mvnw(), "nothing to build, so maven is never started");
      assertEquals(
          "release.yml declares no maven artifact, so this java-service release builds no jar",
          run.output().strip());
    }
  }

  @Test
  public void aMavenEntryTheExtractorCannotReadFailsTheStepNamingIt(@TempDir Path dir)
      throws Exception {
    Run block =
        run(
            dir.resolve("block"),
            "bash",
            "archetype: java-service\nartifacts:\n  - type: maven\n    name: \"g:a\"\n    path: core\n",
            false);
    assertNotEquals(0, block.exit());
    assertEquals(List.of(), block.mvnw());
    assertTrue(block.output().contains("one flow line"), block.output());

    for (String path : List.of("\"core\"", "../elsewhere", "/abs")) {
      Run bad =
          run(
              dir.resolve("path" + Math.abs(path.hashCode())),
              "bash",
              "archetype: java-service\nartifacts:\n"
                  + "  - { type: maven, name: \"g:a\", path: "
                  + path
                  + " }\n",
              false);
      assertNotEquals(0, bad.exit(), path);
      assertEquals(List.of(), bad.mvnw(), path);
      assertTrue(bad.output().contains("not a module under the reactor root"), bad.output());
    }
  }

  private record Run(int exit, String output, List<String> mvnw) {}

  /**
   * The recipe's last release step, run as the composed runner runs it — {@code <shell> -eu} on the
   * declared text — in a tree holding {@code release.yml} and a {@code ./mvnw} that records one line
   * of arguments per call.
   */
  private Run run(Path tree, String shell, String releaseYml, boolean settingsFile)
      throws Exception {
    List<CiStepDecl> release = recipe().release().steps();
    CiStepDecl jars = release.get(release.size() - 1);
    Files.createDirectories(tree.resolve(".config/qits"));
    Files.writeString(tree.resolve(".config/qits/release.yml"), releaseYml);
    if (settingsFile) {
      Files.writeString(tree.resolve(".qits-maven-settings.xml"), "<settings/>\n");
    }
    Path calls = tree.resolve("mvnw-calls");
    Path mvnw = tree.resolve("mvnw");
    Files.writeString(mvnw, "#!/bin/sh\necho \"$*\" >> \"" + calls + "\"\n");
    assertTrue(mvnw.toFile().setExecutable(true));
    Path script = tree.resolve("step.sh");
    Files.writeString(script, jars.script());
    ProcessBuilder builder =
        new ProcessBuilder(shell, "-eu", script.toString())
            .directory(tree.toFile())
            .redirectErrorStream(true);
    builder.environment().keySet().removeIf(key -> key.startsWith("QITS_"));
    builder.environment().put("QITS_DOMAIN", "example.invalid");
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), shell + " did not return");
    List<String> lines =
        Files.exists(calls) ? Files.readAllLines(calls) : List.of();
    // The step's own banner precedes a build; keep only what the step itself printed.
    return new Run(
        process.exitValue(),
        output.replaceAll("(?m)^building the declared maven module\\(s\\): .*\\n", ""),
        lines);
  }
}
