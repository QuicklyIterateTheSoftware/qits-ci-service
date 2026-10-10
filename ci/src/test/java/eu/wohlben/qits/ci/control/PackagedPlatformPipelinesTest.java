package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packaged platform pipelines, as a set: every file under {@code
 * ci/src/main/resources/platform-pipelines/} is in this jar, byte for byte, parses as an event
 * trigger, and its step scripts pass {@code bash -n}.
 *
 * <p>It also carries the commit-guard check that lived in the wrapper's {@code release.yml} while
 * the bump pipeline was a wrapper file: every {@code git diff --cached --quiet} guard in a platform
 * pipeline must spell {@code --ignore-submodules=none}, because a repository whose {@code
 * .gitmodules} says {@code ignore = all} otherwise hides a staged gitlink and the step commits
 * nothing, green. The behavioural half proves that reason still holds on this host's git.
 *
 * <p>Plain JUnit: parser and classpath, no application.
 */
public class PackagedPlatformPipelinesTest {

  /** This module's own copy, relative to the {@code ci} module surefire runs in. */
  private static final Path SOURCE = Path.of("src", "main", "resources", "platform-pipelines");

  /** The release archetypes, which did not move and stay under {@code .config/qits/}. */
  private static final Path RELEASE_ARCHETYPES =
      Path.of("..", ".config", "qits", "release-archetypes");

  private final CiEventTriggerParser triggerParser = new CiEventTriggerParser();

  private static Set<String> namesOnDisk() throws Exception {
    try (Stream<Path> files = Files.list(SOURCE)) {
      Set<String> names = new TreeSet<>();
      files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".yml"))
          .forEach(name -> names.add(name.substring(0, name.length() - ".yml".length())));
      return names;
    }
  }

  @Test
  public void thePackagedSetIsExactlyTheFilesOnDisk() throws Exception {
    assertEquals(namesOnDisk(), new TreeSet<>(CiPlatformPipelines.PACKAGED));
  }

  @Test
  public void everyPipelineIsOnTheClasspathByteForByte() throws Exception {
    for (String name : CiPlatformPipelines.PACKAGED) {
      String resource = CiPlatformPipelines.PACKAGED_DIR + name + ".yml";
      try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
        assertNotNull(in, resource + " is not packaged");
        assertEquals(
            Files.readString(SOURCE.resolve(name + ".yml")),
            new String(in.readAllBytes(), StandardCharsets.UTF_8),
            resource + " differs from its file: Maven must not filter a pipeline");
      }
    }
  }

  @Test
  public void theAutomationSetIsExactlyTheKindFilesOnDisk() throws Exception {
    Set<String> kinds = new TreeSet<>();
    try (Stream<Path> files = Files.list(SOURCE.resolve("automations"))) {
      files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".yml"))
          .forEach(name -> kinds.add(name.substring(0, name.length() - ".yml".length())));
    }
    assertEquals(kinds, new TreeSet<>(CiPlatformPipelines.AUTOMATIONS));
  }

  @Test
  public void everyKindFileIsOnTheClasspathByteForByte() throws Exception {
    for (String kind : CiPlatformPipelines.AUTOMATIONS) {
      String resource =
          CiPlatformPipelines.PACKAGED_DIR + CiPlatformPipelines.AUTOMATIONS_DIR + kind + ".yml";
      try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
        assertNotNull(in, resource + " is not packaged: ci/pom.xml must include automations/");
        assertEquals(
            Files.readString(SOURCE.resolve("automations").resolve(kind + ".yml")),
            new String(in.readAllBytes(), StandardCharsets.UTF_8),
            resource + " differs from its file: Maven must not filter a kind file");
      }
    }
  }

  @Test
  public void everyPipelineParsesAndItsScriptsPassBashN(@TempDir Path dir) throws Exception {
    for (EventTriggerFile file : packaged()) {
      CiEventTrigger trigger = triggerParser.parse(file.path(), file.content());
      assertFalse(trigger.pipeline().steps().isEmpty(), file.path() + " declares no step");
      assertEquals(null, trigger.checkout(), file.path() + ": platform pipelines take no checkout:");
      for (CiStepDecl step : trigger.pipeline().steps()) {
        syntax(dir, step.script(), file.path());
      }
    }
  }

  @Test
  public void eachPipelineAnswersItsOwnEvent() {
    assertEquals(
        List.of(
            "MaintenanceBump",
            "ReleaseRequestAutomation",
            "ReleaseRequestAutomation",
            "ReleaseRequestAutomation"),
        packaged().stream()
            .map(file -> triggerParser.parse(file.path(), file.content()).eventName())
            .toList());
  }

  // --- the composed screenshot-baselines automation ---------------------------------------------

  private static final String SCREENSHOT_KIND_PATH =
      "ci/src/main/resources/platform-pipelines/automations/screenshot-baselines.yml";

  private static String composedScreenshotBaselines() {
    return packaged().stream()
        .filter(file -> file.path().equals(SCREENSHOT_KIND_PATH))
        .findFirst()
        .orElseThrow(() -> new AssertionError(SCREENSHOT_KIND_PATH + " is not in the set"))
        .content();
  }

  @Test
  public void theScreenshotBaselinesKindIsComposedIntoOneAutomationStep(@TempDir Path dir)
      throws Exception {
    String composed = composedScreenshotBaselines();
    CiEventTrigger trigger = triggerParser.parse(SCREENSHOT_KIND_PATH, composed);
    assertEquals("ReleaseRequestAutomation", trigger.eventName());
    assertEquals(null, trigger.checkout(), "recorded at main's head, never at the fold");
    assertTrue(composed.contains("  - kind: { exact: 'screenshot-baselines' }\n"), composed);
    assertEquals(1, trigger.pipeline().steps().size());
    CiStepDecl step = trigger.pipeline().steps().get(0);
    assertEquals("qits/build-images/node-browser-base:latest", step.image());
    assertEquals(1800, step.timeoutSeconds());
    // The kind's own script, between the prelude and the postlude, and valid shell on its own.
    String script = step.script();
    int body = script.indexOf("cat > " + CiAutomationComposer.KIND_SCRIPT);
    int npmCi = script.indexOf("npm ci --no-audit --no-fund");
    int prune = script.indexOf("npm run --if-present screenshots:prune");
    int add = script.indexOf("git add -A --");
    assertTrue(script.indexOf("superseded before start") < body, "the prelude comes first");
    assertTrue(body < script.indexOf("no test:browser script"));
    assertTrue(script.indexOf("no test:browser script") < npmCi);
    assertTrue(npmCi < script.indexOf("UPDATE_SNAPSHOT=all npm run test:browser"));
    assertTrue(script.indexOf("UPDATE_SNAPSHOT=all npm run test:browser") < prune);
    assertTrue(prune < add, "the postlude stages after the kind's script");
    String kindScript =
        script.substring(
            script.indexOf('\n', body) + 1,
            script.indexOf("\n" + CiAutomationComposer.HEREDOC_DELIMITER + "\n"));
    syntax(dir, kindScript, SCREENSHOT_KIND_PATH + " (the kind's script)");
  }

  @Test
  public void noKindFileCarriesRefHandlingOrCommitCode() throws Exception {
    for (String kind : CiPlatformPipelines.AUTOMATIONS) {
      String kindFile = Files.readString(SOURCE.resolve("automations/" + kind + ".yml"));
      List<String> forbidden =
          new java.util.ArrayList<>(
              List.of("git checkout", "git add", "git commit", "git push", "git reset"));
      // dependency-bump is the one kind whose payload says what to change, and the one whose
      // gitlinks are fetched from a sibling: both are allowed it, confined, and asserted below.
      if (!kind.equals(DEPENDENCY_BUMP)) {
        forbidden.addAll(List.of("git fetch", "QITS_EVENT"));
      }
      for (String word : forbidden) {
        assertFalse(
            kindFile.contains(word),
            kind + ": the composer owns refs and commits; the kind file says " + word);
      }
    }
  }

  @Test
  public void theComposedCommitGuardSeesAStagedGitlink() {
    Matcher matcher = GUARD.matcher(composedScreenshotBaselines());
    assertTrue(matcher.find(), "the composed automation has no commit guard");
    assertTrue(matcher.group().contains("--ignore-submodules=none"), matcher.group());
  }

  @Test
  public void theComposedAutomationNeverForcesAPush() {
    String composed = composedScreenshotBaselines();
    assertFalse(composed.contains("--force"), "a forced push in the composed automation");
    assertFalse(composed.contains("push -f"), "a forced push in the composed automation");
    assertFalse(composed.contains("+HEAD:"), "a forced refspec in the composed automation");
    assertTrue(composed.contains("git push \"$QITS_CI_REPOSITORY_URL\" \"HEAD:refs/heads/$branch\""));
    // A rejection is checked for a re-executed step's own push before it is called a failure.
    int already = composed.indexOf("already pushed: $branch at $remote carries the same content");
    assertTrue(already > 0, "the composed automation is not idempotent under re-execution");
    assertTrue(already < composed.indexOf("the fold no longer contains it"), composed);
  }

  @Test
  public void theComposedAutomationStagesOnlyThePayloadsPaths() {
    String composed = composedScreenshotBaselines();
    // Exactly one `git add`, and what it names is the positional list built from the validated
    // payload paths — never a literal path, never the whole tree.
    Matcher adds = Pattern.compile("git add[^\n]*").matcher(composed);
    List<String> found = new java.util.ArrayList<>();
    while (adds.find()) {
      found.add(adds.group().strip());
    }
    assertEquals(List.of("git add -A -- \"$@\""), found);
    assertTrue(composed.contains("jq -r '\n"), composed);
    assertTrue(composed.contains("  .commitPaths\n"), composed);
    assertTrue(composed.contains("done < " + CiAutomationComposer.COMMIT_PATHS));
    assertFalse(composed.contains("__screenshots__"), "a path the payload did not name");
  }

  // --- the composed entity-diagram automation (qits-760) ----------------------------------------

  private static final String ENTITY_KIND_PATH =
      "ci/src/main/resources/platform-pipelines/automations/entity-diagram.yml";

  private static String composedEntityDiagram() {
    return packaged().stream()
        .filter(file -> file.path().equals(ENTITY_KIND_PATH))
        .findFirst()
        .orElseThrow(() -> new AssertionError(ENTITY_KIND_PATH + " is not in the set"))
        .content();
  }

  @Test
  public void theEntityDiagramKindFetchesTheCliBeforeItsScript(@TempDir Path dir)
      throws Exception {
    String composed = composedEntityDiagram();
    CiEventTrigger trigger = triggerParser.parse(ENTITY_KIND_PATH, composed);
    assertEquals("ReleaseRequestAutomation", trigger.eventName());
    assertEquals(null, trigger.checkout(), "recorded at main's head, never at the fold");
    assertTrue(composed.contains("  - kind: { exact: 'entity-diagram' }\n"), composed);
    assertEquals(1, trigger.pipeline().steps().size());
    CiStepDecl step = trigger.pipeline().steps().get(0);
    assertEquals("qits/build-images/maven-base:latest", step.image());
    assertEquals(1800, step.timeoutSeconds());

    String script = step.script();
    StringBuilder fetch = new StringBuilder();
    CiReleaseComposer.cliFetch(fetch, step.image(), CiReleaseComposer.CliFetch.AUTOMATION);
    int fetched = script.indexOf(fetch.toString());
    int body = script.indexOf("cat > " + CiAutomationComposer.KIND_SCRIPT);
    int noPom = script.indexOf("no pom.xml: only JPA/Hibernate entity diagrams are generated today");
    int maven = script.indexOf("test-compile dependency:build-classpath");
    int diagram = script.indexOf("qits database diagram --root . --out docs/database");
    int add = script.indexOf("git add -A --");
    assertTrue(fetched > 0, "the shared hard CLI fetch is composed in:\n" + script);
    assertTrue(script.indexOf("superseded before start") < fetched, "the prelude comes first");
    assertTrue(fetched < body, "the CLI is on PATH before the kind's script");
    assertTrue(body < noPom && noPom < maven && maven < diagram, script);
    assertTrue(diagram < add, "the postlude stages after the kind's script");
    for (String line :
        List.of(
            "qits_domain=${QITS_DOMAIN:?this step was told no QITS_DOMAIN}",
            "export QITS_MAVEN_REPOSITORY_URL=\"https://registry.qits.$qits_domain/artifacts/maven/maven\"",
            "export QITS_MAVEN_CENTRAL_URL=\"https://mirror.qits.$qits_domain/mirror/maven/central\"",
            "-Dmdep.outputFile=target/qits-classpath.txt",
            "-DincludeScope=runtime",
            "-Dmaven.test.skip=true -Dquarkus.quinoa=false")) {
      assertTrue(script.contains(line), line);
    }
    String kindScript =
        script.substring(
            script.indexOf('\n', body) + 1,
            script.indexOf("\n" + CiAutomationComposer.HEREDOC_DELIMITER + "\n"));
    syntax(dir, kindScript, ENTITY_KIND_PATH + " (the kind's script)");
  }

  @Test
  public void theEntityDiagramKindReadsTheMavenAddressesAsJavaServicesQaStepDoes()
      throws Exception {
    String javaService =
        Files.readString(RELEASE_ARCHETYPES.resolve("java-service.yml"));
    String kindFile = Files.readString(SOURCE.resolve("automations/entity-diagram.yml"));
    for (String line :
        List.of(
            "qits_domain=${QITS_DOMAIN:?this step was told no QITS_DOMAIN}",
            "export QITS_MAVEN_REPOSITORY_URL=\"https://registry.qits.$qits_domain/artifacts/maven/maven\"",
            "export QITS_MAVEN_CENTRAL_URL=\"https://mirror.qits.$qits_domain/mirror/maven/central\"")) {
      assertTrue(javaService.contains(line), "java-service no longer says: " + line);
      assertTrue(kindFile.contains(line), "entity-diagram does not say: " + line);
    }
  }

  @Test
  public void theEntityDiagramAutomationStagesOnlyThePayloadsPaths() {
    String composed = composedEntityDiagram();
    Matcher adds = Pattern.compile("git add[^\n]*").matcher(composed);
    List<String> found = new java.util.ArrayList<>();
    while (adds.find()) {
      found.add(adds.group().strip());
    }
    assertEquals(List.of("git add -A -- \"$@\""), found);
    assertTrue(composed.contains("done < " + CiAutomationComposer.COMMIT_PATHS));
    // docs/database is the payload's to name (qits-maintenance sends it), never the composition's.
    assertFalse(composed.contains("docs/database/**"), "a path the payload did not name");
  }

  @Test
  public void theEntityDiagramAutomationNeverForcesAndGuardsGitlinks() {
    String composed = composedEntityDiagram();
    assertFalse(composed.contains("--force"), "a forced push in the composed automation");
    assertFalse(composed.contains("push -f"), "a forced push in the composed automation");
    assertFalse(composed.contains("+HEAD:"), "a forced refspec in the composed automation");
    Matcher matcher = GUARD.matcher(composed);
    assertTrue(matcher.find(), "the composed automation has no commit guard");
    assertTrue(matcher.group().contains("--ignore-submodules=none"), matcher.group());
  }

  @Test
  public void theEntityDiagramKindNeverReadsWhatItCommits() throws Exception {
    // The carry-over invariant: docs/database/** is committed and never this kind's input, so the
    // re-fold its own commit causes is carried with no second run. The one mention allowed in the
    // script is the generator's --out.
    String kindFile = Files.readString(SOURCE.resolve("automations/entity-diagram.yml"));
    String script = kindFile.substring(kindFile.indexOf("\nscript: |\n"));
    int mentions = script.split("docs/database", -1).length - 1;
    assertEquals(1, mentions, script);
    assertTrue(script.contains("--out docs/database"), script);
  }

  // --- the composed dependency-bump automation (qits-1133) --------------------------------------

  private static final String DEPENDENCY_BUMP = "dependency-bump";

  private static final String BUMP_KIND_PATH =
      "ci/src/main/resources/platform-pipelines/automations/dependency-bump.yml";

  private static String composedDependencyBump() {
    return packaged().stream()
        .filter(file -> file.path().equals(BUMP_KIND_PATH))
        .findFirst()
        .orElseThrow(() -> new AssertionError(BUMP_KIND_PATH + " is not in the set"))
        .content();
  }

  @Test
  public void theDependencyBumpKindAppliesEveryEcosystemInOneStepAndCommitsAsABump(
      @TempDir Path dir) throws Exception {
    String composed = composedDependencyBump();
    CiEventTrigger trigger = triggerParser.parse(BUMP_KIND_PATH, composed);
    assertEquals("ReleaseRequestAutomation", trigger.eventName());
    assertEquals(null, trigger.checkout(), "recorded at main's head, never at the fold");
    assertTrue(composed.contains("  - kind: { exact: 'dependency-bump' }\n"), composed);
    assertEquals(1, trigger.pipeline().steps().size(), "maven, npm, docker and gitlink: ONE step");
    CiStepDecl step = trigger.pipeline().steps().get(0);
    // The image with node, npm, jq, git and awk; no ecosystem here runs maven.
    assertEquals("qits/build-images/node-browser-base:latest", step.image());
    assertEquals(1800, step.timeoutSeconds());

    String script = step.script();
    int body = script.indexOf("cat > " + CiAutomationComposer.KIND_SCRIPT);
    assertTrue(script.indexOf("superseded before start") < body, "the prelude comes first");
    for (String ecosystem : List.of("maven)", "npm)", "docker)", "gitlink)")) {
      assertTrue(script.indexOf(ecosystem, body) > body, "the script applies " + ecosystem);
    }
    // bump(<item>): N dependencies, never chore(<item>): update dependency bump.
    assertFalse(script.contains("update dependency bump"), script);
    assertTrue(script.contains("  subject=\"bump($item): $description\"\n"), script);
    assertTrue(script.contains("printf '%d dependencies\\n' \"$count\""), script);
    assertTrue(
        script.indexOf("export " + CiAutomationComposer.MESSAGE_ENV) < body,
        "the message file is named before the script runs");
    String kindScript =
        script.substring(
            script.indexOf('\n', body) + 1,
            script.indexOf("\n" + CiAutomationComposer.HEREDOC_DELIMITER + "\n"));
    syntax(dir, kindScript, BUMP_KIND_PATH + " (the kind's script)");
  }

  @Test
  public void theDependencyBumpKindReadsOnlyItsChangesAndFetchesOnlyASiblingsTag()
      throws Exception {
    String kindFile = Files.readString(SOURCE.resolve("automations/dependency-bump.yml"));
    String script = kindFile.substring(kindFile.indexOf("\nscript: |\n"));
    // The payload: one read, of `.changes`; branch, baseRef and foldSha stay the prelude's.
    assertEquals(1, script.split("QITS_EVENT_PAYLOAD", -1).length - 1, script);
    assertTrue(script.contains("printf '%s' \"$QITS_EVENT_PAYLOAD\" | jq -r '\n    .changes\n"));
    for (String field : List.of(".branch", ".baseRef", ".foldSha", ".commitPaths", ".workItem")) {
      assertFalse(script.contains(field), "the kind reads the prelude's " + field);
    }
    // The one fetch: a gitlink's sibling, at a tag — never a ref of this repository.
    Matcher fetches = Pattern.compile("git fetch[^\n]*").matcher(script);
    List<String> found = new java.util.ArrayList<>();
    while (fetches.find()) {
      found.add(fetches.group().strip());
    }
    assertEquals(List.of("git fetch -q \"$sibling\" \"refs/tags/$to\"; then"), found);
  }

  @Test
  public void theDependencyBumpAutomationStagesOnlyThePayloadsPathsAndNeverForces() {
    String composed = composedDependencyBump();
    Matcher adds = Pattern.compile("git add[^\n]*").matcher(composed);
    List<String> found = new java.util.ArrayList<>();
    while (adds.find()) {
      found.add(adds.group().strip());
    }
    assertEquals(List.of("git add -A -- \"$@\""), found, "the apply stages nothing itself");
    assertFalse(composed.contains("--force"), "a forced push in the composed automation");
    assertFalse(composed.contains("push -f"), "a forced push in the composed automation");
    Matcher matcher = GUARD.matcher(composed);
    assertTrue(matcher.find(), "the composed automation has no commit guard");
    assertTrue(matcher.group().contains("--ignore-submodules=none"), matcher.group());
  }

  @Test
  public void theDependencyBumpKindAppliesWhatTheRetiredMavenStepApplied() throws Exception {
    // `bump-property.awk` and `bump-dependency.awk` were lifted from maintenance-bump.yml's own
    // maven step when the GROUP arm moved here (qits-1133) - a per-repository dependency bump is
    // this kind now, not a `maintenance/<group>` branch. That step is gone from
    // maintenance-bump.yml, which applies no maven change any more (its one caller, estate-pins,
    // sends gitlinks only), so there is nothing left there to compare these two against: this kind
    // file is their only copy on the platform now.
    String kindFile = Files.readString(SOURCE.resolve("automations/dependency-bump.yml"));
    for (String program : List.of("bump-property.awk", "bump-dependency.awk")) {
      assertTrue(kindFile.contains("cat > /tmp/" + program + " <<'AWK'\n"), program + " is not in " + kindFile);
    }
  }

  @Test
  public void theDependencyBumpKindAppliesWhatMaintenanceBumpStillApplies() throws Exception {
    // The two docker programs are maintenance-bump.yml's own node step, which the targeted path
    // still runs: lifted, not rewritten, so the two files cannot drift on how a docker pin is
    // edited.
    String bump = Files.readString(SOURCE.resolve("maintenance-bump.yml"));
    String kindFile = Files.readString(SOURCE.resolve("automations/dependency-bump.yml"));
    for (String program : List.of("bump-from.awk", "bump-arg.awk")) {
      assertEquals(
          awkProgram(bump, program, "      "),
          awkProgram(kindFile, program, "  "),
          program + " drifted from maintenance-bump.yml");
    }
  }

  /** The body of one {@code cat > /tmp/<name> <<'AWK'} heredoc, with its indentation removed. */
  private static String awkProgram(String file, String name, String indent) {
    int start = file.indexOf("cat > /tmp/" + name + " <<'AWK'\n");
    assertTrue(start >= 0, name + " is not in the file");
    int from = file.indexOf('\n', start) + 1;
    int end = file.indexOf("\n" + indent + "AWK\n", from);
    StringBuilder out = new StringBuilder();
    for (String line : file.substring(from, end).split("\n", -1)) {
      String bare = line.startsWith(indent) ? line.substring(indent.length()) : line;
      // A comment is the file's own prose; the code is what has to agree.
      if (!bare.strip().startsWith("#")) {
        out.append(bare).append('\n');
      }
    }
    return out.toString();
  }

  // --- the commit guard ------------------------------------------------------------------------

  private static final Pattern GUARD = Pattern.compile("git diff --cached --quiet[^\\n;]*");

  @Test
  public void everyCommitGuardSeesAStagedGitlink() {
    int guards = 0;
    for (EventTriggerFile file : packaged()) {
      Matcher matcher = GUARD.matcher(file.content());
      while (matcher.find()) {
        guards++;
        assertTrue(
            matcher.group().contains("--ignore-submodules=none"),
            file.path() + ": a commit guard lacks --ignore-submodules=none, so a staged gitlink"
                + " move would be dropped silently: " + matcher.group());
      }
    }
    assertTrue(guards > 0, "no commit guard found in any platform pipeline");
  }

  @Test
  public void aStagedGitlinkIsInvisibleWithoutTheFlagAndVisibleWithIt(@TempDir Path work)
      throws Exception {
    Path sub = work.resolve("sub");
    Path sup = work.resolve("super");
    git(work, "init", "-q", sub.toString());
    commit(sub, "one");
    String old = out(sub, "rev-parse", "HEAD");
    commit(sub, "two");
    String moved = out(sub, "rev-parse", "HEAD");

    git(work, "init", "-q", sup.toString());
    Files.writeString(
        sup.resolve(".gitmodules"),
        "[submodule \"sub\"]\n\tpath = sub\n\turl = ../sub\n\tignore = all\n");
    git(sup, "add", ".gitmodules");
    git(sup, "update-index", "--add", "--cacheinfo", "160000," + old + ",sub");
    commit(sup, "base");

    assertEquals(0, status(sup, "diff", "--cached", "--quiet", "--ignore-submodules=none"));
    git(sup, "update-index", "--add", "--cacheinfo", "160000," + moved + ",sub");
    assertEquals(
        0,
        status(sup, "diff", "--cached", "--quiet"),
        "a bare guard now sees a staged gitlink under ignore = all; the flag may be redundant");
    assertEquals(1, status(sup, "diff", "--cached", "--quiet", "--ignore-submodules=none"));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static List<EventTriggerFile> packaged() {
    return new CiPlatformPipelines().files();
  }

  private static void commit(Path repo, String message) throws Exception {
    git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", message);
  }

  private static void git(Path dir, String... args) throws Exception {
    assertEquals(0, status(dir, args), "git " + String.join(" ", args));
  }

  private static String out(Path dir, String... args) throws Exception {
    Process process = start(dir, args);
    String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    return text;
  }

  private static int status(Path dir, String... args) throws Exception {
    Process process = start(dir, args);
    process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    return process.exitValue();
  }

  private static Process start(Path dir, String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    return new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
  }

  private static void syntax(Path dir, String script, String what) throws Exception {
    Path file = Files.createTempFile(dir, "script", ".sh");
    Files.writeString(file, script);
    Process process = new ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash -n did not return for " + what);
    assertEquals(0, process.exitValue(), "bash -n refused a step of " + what + ":\n" + output);
  }
}
