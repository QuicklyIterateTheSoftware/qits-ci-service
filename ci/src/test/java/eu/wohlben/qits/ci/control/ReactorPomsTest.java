package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The reactor's poms decide a step's build flags, never every pom.xml in the tree (qits-1887).
 *
 * <p>cli.yml's build step and the golden-recordings-maven kind pass {@code
 * -Dqits.maven.repository.url} (and the kind {@code -Dquarkus.quinoa=false}) only when a pom reads
 * the marker, and java-service's QA verify fetches the deployed consumer pacts only when a pom
 * declares a {@code <consumer>-pacts-<repository>} package. All three once scanned every pom.xml in
 * the checkout, so a test fixture copying another repository's pom flipped the decision: live, the
 * property reached qits-bootstrap-cli's strict {@code qits} ConfigMapping and its tests failed boot
 * with SRCFG00050. Each script now walks {@code qits_reactor_poms}, the root pom and the modules it
 * declares, and this file runs the declared scripts under {@code bash} and {@code sh} in scratch
 * trees whose {@code ./mvnw} only records its arguments. java-service's half lives with its other
 * verify tests in {@link JavaServiceReleaseArchetypeTest}.
 */
public class ReactorPomsTest {

  private static final String GOLDEN_RECORDINGS =
      "ci/src/main/resources/platform-pipelines/automations/golden-recordings-maven.yml";

  private static final String REPOSITORY_MARKER =
      "<project><repositories><repository><id>qits-maven</id>"
          + "<url>${qits.maven.repository.url}</url></repository></repositories></project>\n";

  private static final String BOTH_MARKERS =
      "<project><repositories><repository><id>qits-maven</id>"
          + "<url>${qits.maven.repository.url}</url></repository></repositories>"
          + "<dependencies><dependency><groupId>io.quarkiverse.quinoa</groupId>"
          + "<artifactId>quarkus-quinoa</artifactId></dependency></dependencies></project>\n";

  private static final String REPOSITORY_FLAG =
      "-Dqits.maven.repository.url=https://registry.qits.example.invalid/artifacts/maven/maven";

  // --- the walk itself ---------------------------------------------------------------------------

  @Test
  public void theWalkIsTheSameTextInEveryScriptThatUsesIt() throws Exception {
    String cli = function(cliBuildStep().script());
    assertEquals(cli, function(goldenRecordingsScript()), "golden-recordings-maven.yml");
    assertEquals(cli, function(javaServiceVerifyStep().script()), "java-service.yml");
  }

  @Test
  public void theWalkPrintsTheRootAndEveryDeclaredModuleAndNothingElse(@TempDir Path dir)
      throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      write(
          tree.resolve("pom.xml"),
          """
          <project>
            <modules>
              <module>a</module>
              <!-- <module>commented</module> -->
              <module>
                b/
              </module>
              <module>./alt/other.xml</module>
              <module>missing</module><!-- a-b
              <module>commented</module> -->
              <module>a/../b</module>
              <module>../outside</module>
            </modules>
            <profiles><profile><modules><module>profiled</module></modules></profile></profiles>
          </project>
          """);
      // A cycle: a declares the root and itself back.
      write(tree.resolve("a/pom.xml"), "<project><modules><module>..</module><module>../a</module>"
          + "<module>nested</module></modules></project>\n");
      write(tree.resolve("a/nested/pom.xml"), "<project/>\n");
      write(tree.resolve("b/pom.xml"), "<project/>\n");
      write(tree.resolve("alt/other.xml"), "<project/>\n");
      write(tree.resolve("profiled/pom.xml"), "<project/>\n");
      write(tree.resolve("commented/pom.xml"), "<project/>\n");
      write(tree.resolve("src/test/resources/estate/x/files/pom.xml"), "<project/>\n");
      write(dir.resolve("outside/pom.xml"), "<project/>\n");

      Result run = run(tree, shell, function(cliBuildStep().script()) + "\nqits_reactor_poms\n");

      assertEquals(0, run.exit(), shell + ":\n" + run.output());
      assertEquals(
          List.of(
              "./pom.xml",
              "./a/pom.xml",
              "./b/pom.xml",
              "./alt/other.xml",
              "./profiled/pom.xml",
              "./a/nested/pom.xml"),
          run.output().lines().toList(),
          shell);
    }
  }

  @Test
  public void noRootPomIsNoReactor(@TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = Files.createDirectories(dir.resolve(shell));
      write(tree.resolve("m/pom.xml"), REPOSITORY_MARKER);
      Result run = run(tree, shell, function(cliBuildStep().script()) + "\nqits_reactor_poms\n");
      assertEquals(0, run.exit(), shell + ":\n" + run.output());
      assertEquals("", run.output(), shell);
    }
  }

  // --- cli.yml's build and test step -------------------------------------------------------------

  @Test
  public void aCliFixturePomReadingTheRepositoryDoesNotPassTheProperty(@TempDir Path dir)
      throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      // qits-bootstrap-cli's shape: its own poms never read the property, a copied fixture does.
      write(
          tree.resolve("pom.xml"),
          "<project><modules><!-- <module>commented</module> --></modules></project>\n");
      write(tree.resolve("src/test/resources/estate/x/files/pom.xml"), REPOSITORY_MARKER);
      write(tree.resolve("commented/pom.xml"), REPOSITORY_MARKER);

      Result run = cliBuild(tree, shell);

      assertEquals(0, run.exit(), shell + ":\n" + run.output());
      assertEquals(1, run.mvnw().size(), run.output());
      assertTrue(run.mvnw().get(0).contains(" verify"), run.mvnw().get(0));
      assertFalse(run.mvnw().get(0).contains("-Dqits.maven.repository.url"), run.mvnw().get(0));
    }
  }

  @Test
  public void aCliModuleReadingTheRepositoryPassesTheProperty(@TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      write(tree.resolve("pom.xml"), "<project><modules><module>m</module></modules></project>\n");
      write(tree.resolve("m/pom.xml"), REPOSITORY_MARKER);

      Result run = cliBuild(tree, shell);

      assertEquals(0, run.exit(), shell + ":\n" + run.output());
      assertEquals(1, run.mvnw().size(), run.output());
      assertTrue(run.mvnw().get(0).endsWith(" verify " + REPOSITORY_FLAG), run.mvnw().get(0));
    }
  }

  // --- the golden-recordings-maven kind ----------------------------------------------------------

  @Test
  public void goldenRecordingsFixturePomsPassNeitherProperty(@TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      write(tree.resolve("pom.xml"), "<project/>\n");
      write(tree.resolve("src/test/resources/estate/x/files/pom.xml"), BOTH_MARKERS);

      Result run = goldenRecordings(tree, shell);

      assertEquals(0, run.exit(), shell + ":\n" + run.output());
      assertEquals(List.of("-B -ntp test -Dgolden.update=true"), run.mvnw(), run.output());
    }
  }

  @Test
  public void goldenRecordingsProfileModulePassesBothProperties(@TempDir Path dir)
      throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Path tree = dir.resolve(shell);
      write(
          tree.resolve("pom.xml"),
          "<project><profiles><profile><id>all</id><modules><module>service</module></modules>"
              + "</profile></profiles></project>\n");
      write(tree.resolve("service/pom.xml"), BOTH_MARKERS);

      Result run = goldenRecordings(tree, shell);

      assertEquals(0, run.exit(), shell + ":\n" + run.output());
      assertEquals(
          List.of(
              "-B -ntp test " + REPOSITORY_FLAG + " -Dquarkus.quinoa=false -Dgolden.update=true"),
          run.mvnw(),
          run.output());
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Result(int exit, String output, List<String> mvnw) {}

  /** The text of {@code qits_reactor_poms}, from its first line to its closing brace. */
  private static String function(String script) {
    int start = script.indexOf("qits_reactor_poms() {\n");
    assertTrue(start >= 0, "no qits_reactor_poms in:\n" + script);
    int end = script.indexOf("\n}\n", start);
    assertTrue(end > start, "qits_reactor_poms is not closed in:\n" + script);
    return script.substring(start, end + 2);
  }

  private static CiStepDecl cliBuildStep() throws Exception {
    return packagedRecipe("cli").releaseRequest().steps().stream()
        .filter(step -> "build and test".equals(step.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("cli.yml has no `build and test` step"));
  }

  private static CiStepDecl javaServiceVerifyStep() throws Exception {
    List<CiStepDecl> steps = packagedRecipe("java-service").releaseRequest().steps();
    return steps.get(steps.size() - 1);
  }

  private static CiReleaseSlots packagedRecipe(String name) throws Exception {
    String resource = CiReleaseArchetypes.PACKAGED_DIR + name + ".yml";
    try (InputStream in = ReactorPomsTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, resource + " is not on the classpath");
      return new CiReleaseSlotParser()
          .parseArchetype(
              CiReleaseArchetypes.sourcePath(name),
              new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /** The kind's own script, as the composed step writes it out before running it. */
  private static String goldenRecordingsScript() throws Exception {
    String composed =
        CiAutomationComposer.compose(
            "golden-recordings-maven",
            GOLDEN_RECORDINGS,
            Files.readString(Path.of("..").resolve(GOLDEN_RECORDINGS)));
    String script =
        new CiEventTriggerParser()
            .parse(GOLDEN_RECORDINGS, composed)
            .pipeline()
            .steps()
            .get(0)
            .script();
    int body = script.indexOf("cat > " + CiAutomationComposer.KIND_SCRIPT);
    return script.substring(
        script.indexOf('\n', body) + 1,
        script.indexOf("\n" + CiAutomationComposer.HEREDOC_DELIMITER + "\n"));
  }

  private Result cliBuild(Path tree, String shell) throws Exception {
    return run(tree, shell, cliBuildStep().script());
  }

  private Result goldenRecordings(Path tree, String shell) throws Exception {
    return run(tree, shell, goldenRecordingsScript());
  }

  private static void write(Path file, String content) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  /**
   * {@code script} run as the composed runner runs a step — {@code <shell> -eu} — in {@code tree},
   * with {@code curl}/{@code wget} failing (no coverage agent) and a {@code ./mvnw} that records one
   * line of arguments per call.
   */
  private static Result run(Path tree, String shell, String script) throws Exception {
    Files.createDirectories(tree);
    Path bin = Files.createDirectories(tree.resolveSibling(tree.getFileName() + "-bin"));
    for (String fetcher : List.of("curl", "wget")) {
      Files.writeString(bin.resolve(fetcher), "#!/bin/sh\nexit 1\n");
      assertTrue(bin.resolve(fetcher).toFile().setExecutable(true));
    }
    Path calls = bin.resolve("mvnw-calls");
    Path mvnw = tree.resolve("mvnw");
    Files.writeString(mvnw, "#!/bin/sh\necho \"$*\" >> \"" + calls + "\"\n");
    assertTrue(mvnw.toFile().setExecutable(true));
    Path file = tree.resolveSibling(tree.getFileName() + "-step.sh");
    Files.writeString(file, script);
    ProcessBuilder builder =
        new ProcessBuilder(shell, "-eu", file.toString())
            .directory(tree.toFile())
            .redirectErrorStream(true);
    builder.environment().keySet().removeIf(key -> key.startsWith("QITS_"));
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().remove("PWD");
    builder.environment().put("QITS_DOMAIN", "example.invalid");
    builder.environment().put("PATH", bin + ":" + builder.environment().get("PATH"));
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), shell + " did not return");
    return new Result(
        process.exitValue(),
        output,
        Files.exists(calls) ? Files.readAllLines(calls) : List.of());
  }
}
