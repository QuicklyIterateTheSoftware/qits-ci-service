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
    // The PACKAGED recipe still declares `image:` on disk — tolerated there rather than read, kept
    // only for the qits-ci version that judges a fold against the old parser (qits-1544) — but the
    // COMPOSED document never repeats it: every step runs in the run's one ci-slot container, so the
    // composer drops a declared image on the way out, and it never reaches this re-parsed pipeline.
    assertEquals(null, image.image(), "the composed document carries no image");
    assertTrue(image.build());
    assertEquals(null, jars.image(), "the composed document carries no image");
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
    // See the comment on the same assertion above (qits-1544).
    assertEquals(null, steps.get(1).image(), "the composed document carries no image");
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

  // --- the QA verify step: every deployed consumer's pact (qits-1765) ----------------------------

  /** A provider's module pom: it pins a consumer's pact package for this repository. */
  private static final String PROVIDER_POM =
      """
      <project>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-projects-service-pacts-qits-githost-service</artifactId>
          </dependency>
        </dependencies>
      </project>
      """;

  /** A consumer's: golden masters and a pact package naming ANOTHER provider, never this one. */
  private static final String CONSUMER_POM =
      """
      <project>
        <dependencies>
          <dependency><artifactId>qits-projects-service-golden-masters</artifactId></dependency>
          <dependency><artifactId>qits-ci-service-pacts-qits-projects-service</artifactId></dependency>
        </dependencies>
      </project>
      """;

  @Test
  public void theVerifyStepDeclaresTheQitsCli() throws Exception {
    List<CiStepDecl> steps = recipe().releaseRequest().steps();
    CiStepDecl verify = steps.get(steps.size() - 1);
    assertTrue(verify.qitsCli(), "the verify step calls qits, so it asks for the CLI itself");
    assertTrue(verify.script().contains("qits artifacts fetch deployed-pacts"), verify.script());
  }

  @Test
  public void aProviderFetchesTheDeployedPactsIntoAFreshAbsoluteDirAndHandsItToTheVerify(
      @TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      Files.createDirectories(tree.resolve("service"));
      Files.writeString(tree.resolve("pom.xml"), "<project/>\n");
      Files.writeString(tree.resolve("service/pom.xml"), PROVIDER_POM);
      // Left over from an earlier attempt in the same checkout: it must not be verified as deployed.
      Path stale = tree.resolve("target/deployed-pacts/old-consumer/1/old_qits-githost-service.json");
      Files.createDirectories(stale.getParent());
      Files.writeString(stale, "{}");

      VerifyRun run = verify(tree, shell, 0);

      Path out = tree.toRealPath().resolve("target/deployed-pacts");
      assertEquals(
          List.of(
              "artifacts fetch deployed-pacts --provider qits-githost-service --out " + out),
          run.qits(),
          shell + ":\n" + run.output());
      // PROVIDER_POM declares only qits-projects-service, so the other two fetched consumers go.
      assertEquals(
          List.of(
              "QITS_DEPLOYED_PACTS_DIR=" + out,
              "files=./qits-projects-service/2026.1.1/qits-projects-service_qits-githost-service.json"),
          run.mvnw(),
          shell + ":\n" + run.output());
      assertEquals(7, run.exit(), "the step ends with the verify's own code:\n" + run.output());
    }
  }

  @Test
  public void aFetchedConsumerNoPomDeclaresIsRemovedBeforeTheVerifyAndADeclaredOneStays(
      @TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      Files.createDirectories(tree.resolve("service"));
      // Two poms, two declared consumers: the scan collects them across the whole reactor.
      Files.writeString(
          tree.resolve("pom.xml"),
          "<project><dependencyManagement><dependencies><dependency>"
              + "<artifactId>qits-deploy-app-pacts-qits-githost-service</artifactId>"
              + "</dependency></dependencies></dependencyManagement></project>\n");
      Files.writeString(tree.resolve("service/pom.xml"), PROVIDER_POM);

      VerifyRun run = verify(tree, shell, 0);

      assertEquals(
          List.of(
              "QITS_DEPLOYED_PACTS_DIR=" + tree.toRealPath().resolve("target/deployed-pacts"),
              "files=./qits-deploy-app/2026.1.1/qits-deploy-app_qits-githost-service.json"
                  + " ./qits-projects-service/2026.1.1/qits-projects-service_qits-githost-service.json"),
          run.mvnw(),
          shell + ":\n" + run.output());
      // A consumer that stopped calling this provider: its last pact is fetched, never verified.
      assertTrue(
          run.output()
              .contains(
                  "deployed pacts: qits-landing-app is not a consumer this repository pins; not"
                      + " verified"),
          run.output());
      assertFalse(run.output().contains("qits-deploy-app is not a consumer"), run.output());
      assertFalse(run.output().contains("qits-projects-service is not a consumer"), run.output());
    }
  }

  @Test
  public void aRepositoryThatVerifiesNoPactOfItsOwnFetchesNothingAndSetsNoVariable(
      @TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      Files.createDirectories(tree.resolve("service/target"));
      Files.writeString(tree.resolve("pom.xml"), CONSUMER_POM);
      // Build output is not a declaration: a copied pom under target/ does not make a provider.
      Files.writeString(tree.resolve("service/target/pom.xml"), PROVIDER_POM);

      VerifyRun run = verify(tree, shell, 0);

      assertEquals(List.of(), run.qits(), shell + ":\n" + run.output());
      assertEquals(
          List.of("QITS_DEPLOYED_PACTS_DIR=<unset>", "files="), run.mvnw(), shell + ":\n" + run.output());
      assertFalse(Files.exists(tree.resolve("target/deployed-pacts")), shell);
    }
  }

  @Test
  public void aFailedFetchFailsTheStepBeforeTheVerifyRuns(@TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      Files.createDirectories(tree);
      Files.writeString(tree.resolve("pom.xml"), PROVIDER_POM);

      // 1 is the CLI's failure code; an unknown verb (a CLI older than the fetch) fails the same way.
      VerifyRun run = verify(tree, shell, 1);

      assertEquals(1, run.exit(), shell + ":\n" + run.output());
      assertEquals(List.of(), run.mvnw(), "no verify without the deployed pacts");
      assertTrue(
          run.output().contains("could not fetch the deployed consumers' pacts for qits-githost-service"),
          run.output());
    }
  }

  private record VerifyRun(int exit, String output, List<String> qits, List<String> mvnw) {}

  /**
   * The recipe's QA verify step, run as the composed runner runs it — {@code <shell> -eu} on the
   * declared text — with stubs on {@code PATH}: {@code qits} records its arguments and, exiting
   * {@code qitsExit}, writes one pact each for three consumers into {@code --out} first
   * ({@code qits-projects-service}, {@code qits-deploy-app}, {@code qits-landing-app}); {@code curl}/{@code wget} fail, so no
   * coverage agent is fetched. {@code ./mvnw} records the variable and the files it can see, then
   * exits 7, which ends the step before the userflow bundle.
   */
  private VerifyRun verify(Path tree, String shell, int qitsExit) throws Exception {
    List<CiStepDecl> steps = recipe().releaseRequest().steps();
    CiStepDecl verify = steps.get(steps.size() - 1);
    Path bin = Files.createDirectories(tree.resolveSibling(tree.getFileName() + "-bin"));
    Path qitsCalls = bin.resolve("qits-calls");
    Path mvnwCalls = bin.resolve("mvnw-calls");
    Path qits = bin.resolve("qits");
    Files.writeString(
        qits,
        "#!/bin/sh\n"
            + "echo \"$*\" >> \""
            + qitsCalls
            + "\"\n"
            + "[ \"$6\" = --out ] || exit 2\n"
            + "for c in qits-projects-service qits-deploy-app qits-landing-app; do\n"
            + "  mkdir -p \"$7/$c/2026.1.1\"\n"
            + "  echo '{}' > \"$7/$c/2026.1.1/${c}_qits-githost-service.json\"\n"
            + "done\n"
            + "exit "
            + qitsExit
            + "\n");
    for (String fetcher : List.of("curl", "wget")) {
      Files.writeString(bin.resolve(fetcher), "#!/bin/sh\nexit 1\n");
      assertTrue(bin.resolve(fetcher).toFile().setExecutable(true));
    }
    assertTrue(qits.toFile().setExecutable(true));
    Path mvnw = tree.resolve("mvnw");
    Files.writeString(
        mvnw,
        "#!/bin/sh\n"
            + "{\n"
            + "  echo \"QITS_DEPLOYED_PACTS_DIR=${QITS_DEPLOYED_PACTS_DIR-<unset>}\"\n"
            + "  if [ -n \"${QITS_DEPLOYED_PACTS_DIR:-}\" ]; then\n"
            + "    echo \"files=$(cd \"$QITS_DEPLOYED_PACTS_DIR\" && find . -type f | sort | tr '\\n' ' ' | sed 's/ $//')\"\n"
            + "  else\n"
            + "    echo files=\n"
            + "  fi\n"
            + "} >> \""
            + mvnwCalls
            + "\"\n"
            + "exit 7\n");
    assertTrue(mvnw.toFile().setExecutable(true));
    Path script = tree.resolveSibling(tree.getFileName() + "-step.sh");
    Files.writeString(script, verify.script());
    ProcessBuilder builder =
        new ProcessBuilder(shell, "-eu", script.toString())
            .directory(tree.toFile())
            .redirectErrorStream(true);
    builder.environment().keySet().removeIf(key -> key.startsWith("QITS_"));
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().remove("PWD");
    builder.environment().put("QITS_DOMAIN", "example.invalid");
    builder.environment().put("QITS_CI_REPO_NAME", "qits-githost-service");
    builder.environment().put("PATH", bin + ":" + builder.environment().get("PATH"));
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), shell + " did not return");
    return new VerifyRun(
        process.exitValue(),
        output,
        Files.exists(qitsCalls) ? Files.readAllLines(qitsCalls) : List.of(),
        Files.exists(mvnwCalls) ? Files.readAllLines(mvnwCalls) : List.of());
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
