package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CiAutomationComposer}: what a kind file may declare, and what the composed step really does
 * with a payload. The second half runs the composed prelude and postlude under {@code bash} and
 * {@code sh} against a scratch origin, with a kind script standing in for a regeneration.
 *
 * <p>Plain JUnit: the composer is pure, and the behavioural cases need only git and jq.
 */
public class CiAutomationComposerTest {

  private static final String PATH =
      "ci/src/main/resources/platform-pipelines/automations/test-kind.yml";

  private static final String MINIMAL =
      """
      image: qits/build-images/node-browser-base:latest
      timeout-seconds: 600
      script: |
        echo regenerate
      """;

  // --- the kind file ---------------------------------------------------------------------------

  @Test
  public void anUnknownKeyIsAnErrorNamingTheFile() {
    CiConfigException error =
        assertThrows(
            CiConfigException.class,
            () -> CiAutomationComposer.compose("test-kind", PATH, MINIMAL + "event: Other\n"));
    assertTrue(error.getMessage().startsWith(PATH + ": "), error.getMessage());
    assertTrue(error.getMessage().contains("[event]"), error.getMessage());
  }

  @Test
  public void aKindDeclaredInsideTheFileIsAnUnknownKey() {
    CiConfigException error =
        assertThrows(
            CiConfigException.class,
            () -> CiAutomationComposer.compose("test-kind", PATH, MINIMAL + "kind: test-kind\n"));
    assertTrue(error.getMessage().contains("[kind]"), error.getMessage());
  }

  @Test
  public void aKindThatIsNotTheFilesNameIsAnErrorNamingTheFile() {
    CiConfigException error =
        assertThrows(
            CiConfigException.class,
            () -> CiAutomationComposer.compose("entity-diagram", PATH, MINIMAL));
    assertTrue(error.getMessage().startsWith(PATH + ": "), error.getMessage());
    assertTrue(error.getMessage().contains("'entity-diagram'"), error.getMessage());
  }

  @Test
  public void anImplausibleKindIsAnErrorNamingTheFile() {
    String path = "ci/src/main/resources/platform-pipelines/automations/Test_Kind.yml";
    CiConfigException error =
        assertThrows(
            CiConfigException.class, () -> CiAutomationComposer.compose("Test_Kind", path, MINIMAL));
    assertTrue(error.getMessage().startsWith(path + ": "), error.getMessage());
  }

  @Test
  public void eachDeclaredValueIsRequiredAndTyped() {
    for (String broken :
        List.of(
            "timeout-seconds: 600\nscript: echo\n",
            "image: x\nscript: echo\n",
            "image: x\ntimeout-seconds: 600\n",
            "image: x\ntimeout-seconds: 0\nscript: echo\n",
            "image: x\ntimeout-seconds: soon\nscript: echo\n",
            "",
            "- a list\n")) {
      CiConfigException error =
          assertThrows(
              CiConfigException.class,
              () -> CiAutomationComposer.compose("test-kind", PATH, broken),
              broken);
      assertTrue(error.getMessage().startsWith(PATH + ": "), error.getMessage());
    }
  }

  @Test
  public void aScriptCarryingTheHeredocDelimiterIsRefused() {
    String content =
        "image: x\ntimeout-seconds: 600\nscript: |\n  echo a\n  "
            + CiAutomationComposer.HEREDOC_DELIMITER
            + "\n  echo b\n";
    assertThrows(
        CiConfigException.class, () -> CiAutomationComposer.compose("test-kind", PATH, content));
  }

  @Test
  public void theComposedDocumentIsAPlatformTriggerOnTheKind() {
    String composed = CiAutomationComposer.compose("test-kind", PATH, MINIMAL);
    CiEventTrigger trigger = new CiEventTriggerParser().parse(PATH, composed);
    assertEquals(CiAutomationComposer.EVENT, trigger.eventName());
    assertEquals(null, trigger.checkout());
    assertTrue(composed.contains("when:\n  - kind: { exact: 'test-kind' }\n"), composed);
    assertEquals(1, trigger.pipeline().steps().size());
    assertEquals(600, trigger.pipeline().steps().get(0).timeoutSeconds());
    assertEquals(
        "qits/build-images/node-browser-base:latest", trigger.pipeline().steps().get(0).image());
  }

  // --- qits-cli ----------------------------------------------------------------------------------

  private static final String IMAGE = "qits/build-images/node-browser-base:latest";

  /** The block a {@code qits-cli: true} kind gets: the shared emitter, in its automation form. */
  private static String automationFetch() {
    StringBuilder fetch = new StringBuilder();
    CiReleaseComposer.cliFetch(fetch, IMAGE, CiReleaseComposer.CliFetch.AUTOMATION);
    return fetch.toString();
  }

  @Test
  public void qitsCliTrueAddsTheSharedFetchBeforeTheScript() {
    String without = CiAutomationComposer.compose("test-kind", PATH, MINIMAL);
    String with = CiAutomationComposer.compose("test-kind", PATH, MINIMAL + "qits-cli: true\n");
    assertFalse(without.contains(CiReleaseComposer.CLI_DOWNLOAD_BASE), without);
    assertTrue(with.contains(CiReleaseComposer.CLI_DOWNLOAD_BASE), with);

    String fetch = automationFetch();
    String step = CiAutomationComposer.step("test-kind", IMAGE, "echo regenerate\n", true);
    assertTrue(step.contains(fetch), "the shared emitter's block, verbatim");
    assertTrue(
        step.indexOf(fetch) > step.indexOf("git checkout -q --detach FETCH_HEAD"),
        "fetched into the fold's step, after the prelude");
    assertTrue(
        step.indexOf(fetch) < step.indexOf("cat > " + CiAutomationComposer.KIND_SCRIPT),
        "on PATH before the kind's script runs");
    // The fetch is the ONLY difference the key makes.
    assertEquals(
        CiAutomationComposer.step("test-kind", IMAGE, "echo regenerate\n", false),
        step.replace(
            "# --- the qits CLI, pinned, on PATH: this kind declares qits-cli: true ---\n" + fetch,
            ""));
  }

  @Test
  public void theAutomationFetchIsTheReleaseDownloadMadeHard() {
    String fetch = automationFetch();
    StringBuilder release = new StringBuilder();
    CiReleaseComposer.cliFetch(release, IMAGE, CiReleaseComposer.CliFetch.RELEASE);
    // One store, one pinned address, one bearer source, one destination — the release prelude's.
    String url =
        "\""
            + CiReleaseComposer.CLI_DOWNLOAD_BASE
            + "$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"";
    for (String shared :
        List.of(url, "-o " + CiReleaseComposer.CLI_DIR + "/qits", "Authorization: Bearer $QITS_TOKEN")) {
      assertTrue(release.toString().contains(shared), "release: " + shared);
      assertTrue(fetch.contains(shared), "automation: " + shared);
    }
    // Hard: the package is demanded, and every failure ends the step naming what it died of.
    assertTrue(fetch.startsWith("if [ -z \"${QITS_ARTIFACTS_CLI_PACKAGE:-}\" ]; then\n"), fetch);
    assertTrue(fetch.contains("the qits CLI could not be fetched"), fetch);
    assertTrue(fetch.endsWith("PATH=\"" + CiReleaseComposer.CLI_DIR + ":$PATH\"\nexport PATH\n"));
    assertFalse(fetch.contains("set --"), "the postlude's positional list is its own");
  }

  @Test
  public void anyQitsCliValueButTrueIsABootErrorNamingTheFile() {
    for (String value : List.of("false", "yes please", "'true'", "1", "", "[true]")) {
      CiConfigException error =
          assertThrows(
              CiConfigException.class,
              () -> CiAutomationComposer.compose("test-kind", PATH, MINIMAL + "qits-cli: " + value + "\n"),
              value);
      assertTrue(error.getMessage().startsWith(PATH + ": "), error.getMessage());
      assertTrue(error.getMessage().contains("'qits-cli'"), error.getMessage());
    }
  }

  @Test
  public void theScreenshotBaselinesKindIsComposedByteIdenticallyToBefore() throws Exception {
    // composed/automation-screenshot-baselines.yml is the composition from before qits-cli: the
    // key is additive, so a kind that does not set it must not move by a byte.
    String path = "ci/src/main/resources/platform-pipelines/automations/screenshot-baselines.yml";
    String kindFile = Files.readString(Path.of("..").resolve(path));
    try (var in =
        getClass().getClassLoader().getResourceAsStream("composed/automation-screenshot-baselines.yml")) {
      assertEquals(
          new String(in.readAllBytes(), StandardCharsets.UTF_8),
          CiAutomationComposer.compose("screenshot-baselines", path, kindFile));
    }
  }

  // --- the composed step, run --------------------------------------------------------------------

  private static final String BRANCH = "maintenance/automations/test-kind/abc";

  private static final String WRITES =
      "mkdir -p out\necho \"generated at $(git rev-parse --short HEAD)\" > out/file.txt\n"
          + "echo stray > stray.txt\n";

  @Test
  public void aRunCommitsOnlyThePayloadsPathsAndPushesPlainly(@TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Scratch scratch = new Scratch(dir.resolve(shell));
      Result run = scratch.run(shell, WRITES, scratch.payload(BRANCH, scratch.fold, "\":(glob)out/**\""));

      assertEquals(0, run.exit, shell + ":\n" + run.output);
      String pushed = scratch.originRev("refs/heads/" + BRANCH);
      assertTrue(run.output.contains("pushed " + pushed + " to " + BRANCH), run.output);
      assertEquals(
          "chore(qits-978): update test kind",
          scratch.git(scratch.origin, "log", "-1", "--format=%s", pushed));
      assertEquals(
          "out/file.txt",
          scratch.git(scratch.origin, "diff-tree", "--no-commit-id", "--name-only", "-r", pushed),
          "only the payload's paths are committed");
      assertEquals(scratch.fold, scratch.git(scratch.origin, "rev-parse", pushed + "^"));
    }
  }

  @Test
  public void thePayloadsWorkItemNamesTheCommit(@TempDir Path dir) throws Exception {
    Scratch scratch = new Scratch(dir);
    String payload =
        scratch
            .payload(BRANCH, scratch.fold, "\"out/file.txt\"")
            .replace("}", ",\"workItem\":\"qits-1030\"}");
    Result run = scratch.run("bash", WRITES, payload);

    assertEquals(0, run.exit, run.output);
    assertEquals(
        "chore(qits-1030): update test kind",
        scratch.git(scratch.origin, "log", "-1", "--format=%s", "refs/heads/" + BRANCH));
  }

  @Test
  public void nothingChangedUnderThePathsIsUnchangedAndGreen(@TempDir Path dir) throws Exception {
    Scratch scratch = new Scratch(dir);
    Result run =
        scratch.run("bash", "echo stray > stray.txt\n", scratch.payload(BRANCH, scratch.fold, "\"out/**\""));

    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("unchanged"), run.output);
    assertEquals("", scratch.originRevOrEmpty("refs/heads/" + BRANCH), "nothing was pushed");
  }

  @Test
  public void aFoldThatMovedIsSupersededBeforeStart(@TempDir Path dir) throws Exception {
    Scratch scratch = new Scratch(dir);
    String stale = scratch.fold;
    scratch.advanceFold();
    Result run = scratch.run("bash", WRITES, scratch.payload(BRANCH, stale, "\"out/**\""));

    assertEquals(1, run.exit, run.output);
    assertTrue(run.output.contains("superseded before start"), run.output);
    assertFalse(run.output.contains("generated"), "the kind's script never ran");
    assertEquals("", scratch.originRevOrEmpty("refs/heads/" + BRANCH));
  }

  @Test
  public void aBranchTheFoldNoLongerContainsIsARejectedPush(@TempDir Path dir) throws Exception {
    Scratch scratch = new Scratch(dir);
    // The branch exists already and is not an ancestor of the fold: a plain push cannot move it.
    String stranger =
        scratch.git(
            scratch.seed,
            "-c", "user.name=t", "-c", "user.email=t@t",
            "commit-tree", "HEAD^{tree}", "-m", "a branch the fold dropped");
    scratch.git(
        scratch.seed, "push", "-q", scratch.origin.toString(), stranger + ":refs/heads/" + BRANCH);
    String before = scratch.originRev("refs/heads/" + BRANCH);
    Result run = scratch.run("bash", WRITES, scratch.payload(BRANCH, scratch.fold, "\"out/**\""));

    assertEquals(1, run.exit, run.output);
    assertTrue(run.output.contains("the fold no longer contains it"), run.output);
    assertEquals(before, scratch.originRev("refs/heads/" + BRANCH), "never forced");
  }

  @Test
  public void aStepExecutedAgainAfterItsPushIsAlreadyPushedAndGreen(@TempDir Path dir)
      throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Scratch scratch = new Scratch(dir.resolve(shell));
      String payload = scratch.payload(BRANCH, scratch.fold, "\":(glob)out/**\"");
      Result first = scratch.run(shell, WRITES, payload);
      assertEquals(0, first.exit, shell + ":\n" + first.output);
      String pushed = scratch.originRev("refs/heads/" + BRANCH);

      // The re-dispatched execution commits the same content at another instant: another sha, so
      // its plain push is rejected as non-fast-forward.
      Result again =
          scratch.run(
              shell,
              WRITES,
              payload,
              false,
              null,
              Map.of(
                  "GIT_AUTHOR_DATE", "2001-01-01T00:00:00Z",
                  "GIT_COMMITTER_DATE", "2001-01-01T00:00:00Z"));

      assertEquals(0, again.exit, shell + ":\n" + again.output);
      assertTrue(
          again.output.contains(
              "already pushed: " + BRANCH + " at " + pushed + " carries the same content"),
          again.output);
      assertTrue(again.output.contains("pushed " + pushed + " to " + BRANCH), again.output);
      assertFalse(again.output.contains("the fold no longer contains it"), again.output);
      assertEquals(pushed, scratch.originRev("refs/heads/" + BRANCH), "no second commit");
      assertEquals(scratch.fold, scratch.git(scratch.origin, "rev-parse", pushed + "^"));
    }
  }

  @Test
  public void aBranchOnTheFoldHoldingOtherContentIsStillARejectedPush(@TempDir Path dir)
      throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Scratch scratch = new Scratch(dir.resolve(shell));
      String payload = scratch.payload(BRANCH, scratch.fold, "\":(glob)out/**\"");
      // Somebody else's commit on the very same fold, writing the same path differently.
      Result other = scratch.run(shell, "mkdir -p out\necho other > out/file.txt\n", payload);
      assertEquals(0, other.exit, shell + ":\n" + other.output);
      String before = scratch.originRev("refs/heads/" + BRANCH);

      Result run = scratch.run(shell, WRITES, payload);

      assertEquals(1, run.exit, shell + ":\n" + run.output);
      assertTrue(run.output.contains("the fold no longer contains it"), run.output);
      assertFalse(run.output.contains("already pushed"), run.output);
      assertEquals(before, scratch.originRev("refs/heads/" + BRANCH), "never forced");
    }
  }

  @Test
  public void anImplausiblePayloadIsRefusedBeforeAnythingIsFetched(@TempDir Path dir)
      throws Exception {
    Scratch scratch = new Scratch(dir);
    String good = scratch.payload(BRANCH, scratch.fold, "\"out/**\"");
    Map<String, String> refusals =
        Map.ofEntries(
            Map.entry("another kind", good.replace("\"kind\":\"test-kind\"", "\"kind\":\"other\"")),
            Map.entry("a kind's case", good.replace("\"kind\":\"test-kind\"", "\"kind\":\"Test\"")),
            Map.entry(
                "another kind's branch",
                good.replace(BRANCH, "maintenance/automations/other/abc")),
            Map.entry("the baselines branch", good.replace(BRANCH, "maintenance/baselines/abc")),
            Map.entry("main", good.replace(BRANCH, "main")),
            Map.entry("a dotted ref", good.replace(BRANCH, BRANCH + "/../../main")),
            Map.entry("a bare prefix", good.replace(BRANCH, "maintenance/automations/test-kind/")),
            Map.entry("a base outside release/", good.replace("release/abc", "main")),
            Map.entry("a short sha", good.replace(scratch.fold, scratch.fold.substring(0, 12))),
            Map.entry("an upper-case sha", good.replace(scratch.fold, scratch.fold.toUpperCase())),
            Map.entry("a bad work item", good.replace("}", ",\"workItem\":\"a b\"}")),
            Map.entry("a numeric work item", good.replace("}", ",\"workItem\":42}")),
            Map.entry("no paths", good.replace("[\"out/**\"]", "[]")),
            Map.entry("paths not a list", good.replace("[\"out/**\"]", "\"out/**\"")),
            Map.entry("an absolute path", good.replace("out/**", "/etc/passwd")),
            Map.entry("a climbing path", good.replace("out/**", ":(glob)../x")),
            Map.entry("other pathspec magic", good.replace("out/**", ":(top)out")),
            Map.entry("a flag as a path", good.replace("out/**", "-A")),
            Map.entry("a path with a space", good.replace("out/**", "out dir")));
    for (Map.Entry<String, String> refusal : refusals.entrySet()) {
      Result run = scratch.run("bash", WRITES, refusal.getValue());
      assertEquals(1, run.exit, refusal.getKey() + ":\n" + run.output);
      assertTrue(
          run.output.contains("refusing"), refusal.getKey() + " was not refused:\n" + run.output);
      assertFalse(run.output.contains("generated"), refusal.getKey() + ": the script ran");
    }
    assertEquals("", scratch.originRevOrEmpty("refs/heads/" + BRANCH));
  }

  // --- the qits CLI fetch, run ------------------------------------------------------------------

  private static final String DIAGRAM =
      "mkdir -p out\nqits database diagram --root . --out out > out/file.txt\n";

  @Test
  public void aQitsCliKindRunsTheScriptWithThePinnedCliOnPath(@TempDir Path dir) throws Exception {
    Path store = dir.resolve("store");
    Files.createDirectories(store.resolve("qits"));
    Files.writeString(store.resolve("qits/9.9.9"), "#!/bin/sh\necho \"stub qits $*\"\n");
    for (String shell : List.of("bash", "sh")) {
      Scratch scratch = new Scratch(dir.resolve(shell));
      Result run =
          scratch.run(
              shell,
              DIAGRAM,
              scratch.payload(BRANCH, scratch.fold, "\":(glob)out/**\""),
              true,
              "file://" + store + "/",
              Map.of("QITS_ARTIFACTS_CLI_PACKAGE", "qits", "QITS_ARTIFACTS_CLI_VERSION", "9.9.9"));

      assertEquals(0, run.exit, shell + ":\n" + run.output);
      assertTrue(run.output.contains("qits-ci: fetched qits 9.9.9"), run.output);
      String pushed = scratch.originRev("refs/heads/" + BRANCH);
      assertEquals(
          "stub qits database diagram --root . --out out",
          scratch.git(scratch.origin, "show", pushed + ":out/file.txt"),
          "the kind's script called the fetched CLI");
    }
  }

  @Test
  public void aQitsCliKindWhoseFetchFailsNeverRunsItsScript(@TempDir Path dir) throws Exception {
    Path empty = dir.resolve("empty-store");
    Files.createDirectories(empty);
    Map<String, Map<String, String>> failures =
        Map.of(
            "no package", Map.of(),
            "no version", Map.of("QITS_ARTIFACTS_CLI_PACKAGE", "qits"),
            "a failed download",
                Map.of("QITS_ARTIFACTS_CLI_PACKAGE", "qits", "QITS_ARTIFACTS_CLI_VERSION", "9.9.9"));
    Scratch scratch = new Scratch(dir.resolve("scratch"));
    for (Map.Entry<String, Map<String, String>> failure : failures.entrySet()) {
      Result run =
          scratch.run(
              "bash",
              DIAGRAM,
              scratch.payload(BRANCH, scratch.fold, "\"out/**\""),
              true,
              "file://" + empty + "/",
              failure.getValue());
      assertEquals(1, run.exit, failure.getKey() + ":\n" + run.output);
      assertTrue(
          run.output.contains("the qits CLI could not be fetched"),
          failure.getKey() + ":\n" + run.output);
      assertFalse(run.output.contains("stub qits"), failure.getKey() + ": the script ran");
    }
    assertEquals("", scratch.originRevOrEmpty("refs/heads/" + BRANCH), "nothing was pushed");
  }

  // --- commit-type (qits-1133) ------------------------------------------------------------------

  @Test
  public void anyCommitTypeButALowercaseWordIsABootErrorNamingTheFile() {
    for (String value : List.of("Bump", "bump!", "''", "true", "[bump]", "bump it", "chore(x)")) {
      CiConfigException error =
          assertThrows(
              CiConfigException.class,
              () ->
                  CiAutomationComposer.compose(
                      "test-kind", PATH, MINIMAL + "commit-type: " + value + "\n"),
              value);
      assertTrue(error.getMessage().startsWith(PATH + ": "), error.getMessage());
      assertTrue(error.getMessage().contains("'commit-type'"), error.getMessage());
    }
  }

  @Test
  public void aCommitTypeChangesTheCommitAndNothingBeforeIt() {
    String without = CiAutomationComposer.step("test-kind", IMAGE, "echo regenerate\n", false);
    String with = CiAutomationComposer.step("test-kind", IMAGE, "echo regenerate\n", false, "bump");
    assertEquals(without, CiAutomationComposer.step("test-kind", IMAGE, "echo regenerate\n", false, null));
    assertFalse(without.contains(CiAutomationComposer.MESSAGE_FILE), "a kind without the key pays nothing");
    // The prelude is the same text up to the kind's script; the key adds the file, exported.
    String announce =
        "rm -f "
            + CiAutomationComposer.MESSAGE_FILE
            + "\nexport "
            + CiAutomationComposer.MESSAGE_ENV
            + "="
            + CiAutomationComposer.MESSAGE_FILE
            + "\n";
    int script = without.indexOf("# --- the kind's script, run as data ---");
    assertEquals(without.substring(0, script) + announce, with.substring(0, script + announce.length()));
    assertFalse(with.contains("update test kind"), "the template subject is gone");
    assertTrue(with.contains("  subject=\"bump($item): $description\"\n"), with);
    assertTrue(with.contains("commit -q -F " + CiAutomationComposer.COMMIT_MESSAGE), with);
    // Composed through the file, the key reaches the step.
    String composed =
        CiAutomationComposer.compose("test-kind", PATH, MINIMAL + "commit-type: bump\n");
    assertTrue(composed.contains("subject=\"bump($item): $description\""), composed);
  }

  private static final String WRITES_A_MESSAGE =
      WRITES + "printf '2 dependencies\\n- one\\n- two\\n' > \"$QITS_AUTOMATION_MESSAGE\"\n";

  @Test
  public void aCommitTypeKindCommitsItsOwnDescriptionAndBodyUnderTheTypeAndTheItem(
      @TempDir Path dir) throws Exception {
    for (String shell : List.of("bash", "sh")) {
      Scratch scratch = new Scratch(dir.resolve(shell), "test-kind", "bump");
      Result run =
          scratch.run(shell, WRITES_A_MESSAGE, scratch.payload(BRANCH, scratch.fold, "\"out/**\""));

      assertEquals(0, run.exit, shell + ":\n" + run.output);
      String pushed = scratch.originRev("refs/heads/" + BRANCH);
      assertEquals(
          "bump(qits-978): 2 dependencies",
          scratch.git(scratch.origin, "log", "-1", "--format=%s", pushed),
          "the type is the kind file's, the scope the prelude's, the description the script's");
      assertEquals("- one\n- two", scratch.git(scratch.origin, "log", "-1", "--format=%b", pushed));
      assertEquals(
          "out/file.txt",
          scratch.git(scratch.origin, "diff-tree", "--no-commit-id", "--name-only", "-r", pushed),
          "the message file is no commit path");
    }
  }

  @Test
  public void aCommitTypeKindThatChangedTheTreeButWroteNoDescriptionFails(@TempDir Path dir)
      throws Exception {
    Scratch scratch = new Scratch(dir, "test-kind", "bump");
    Result run = scratch.run("bash", WRITES, scratch.payload(BRANCH, scratch.fold, "\"out/**\""));

    assertEquals(1, run.exit, run.output);
    assertTrue(run.output.contains("wrote no commit description"), run.output);
    assertEquals("", scratch.originRevOrEmpty("refs/heads/" + BRANCH), "nothing was pushed");
  }

  @Test
  public void aCommitTypeKindWithNothingToCommitOwesNoDescription(@TempDir Path dir)
      throws Exception {
    Scratch scratch = new Scratch(dir, "test-kind", "bump");
    Result run =
        scratch.run("bash", "echo stray > stray.txt\n", scratch.payload(BRANCH, scratch.fold, "\"out/**\""));

    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("unchanged"), run.output);
  }

  // --- the dependency-bump kind, run (qits-1133) -------------------------------------------------

  private static final String BUMP_PATH =
      "ci/src/main/resources/platform-pipelines/automations/dependency-bump.yml";

  private static final String BUMP_BRANCH = "maintenance/automations/dependency-bump/abc";

  private static final String POM =
      """
      <project>
        <properties>
          <quarkus.version>3.1.0</quarkus.version>
        </properties>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-eventstream</artifactId>
            <version>2026.1.1</version>
          </dependency>
        </dependencies>
      </project>
      """;

  private static final String DOCKERFILE =
      """
      ARG BASE=registry.example:5000/qits/workspace:2026.1.1
      FROM ${BASE}
      FROM qits/build-images/node-base:1.0 AS build
      """;

  /** The packaged kind file's own step, composed exactly as the platform composes it. */
  private static String dependencyBumpStep() throws Exception {
    String kindFile = Files.readString(Path.of("..").resolve(BUMP_PATH));
    String composed = CiAutomationComposer.compose("dependency-bump", BUMP_PATH, kindFile);
    return new CiEventTriggerParser().parse(BUMP_PATH, composed).pipeline().steps().get(0).script();
  }

  private static String bumpPayload(Scratch scratch, String changes, String paths) {
    return scratch
        .payload(BUMP_BRANCH, scratch.fold, paths)
        .replace("}", ",\"changes\":" + changes + "}");
  }

  @Test
  public void everyEcosystemIsAppliedInOneCommitOnTheAutomationBranch(@TempDir Path dir)
      throws Exception {
    Scratch scratch = new Scratch(dir.resolve("scratch"), "dependency-bump", "bump");
    // The sibling a gitlink names, beside the origin: its url is derived from the run's own.
    Path sibling = dir.resolve("scratch").resolve("qits-sibling");
    scratch.git(dir, "init", "-q", "-b", "main", sibling.toString());
    scratch.git(sibling, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "one");
    String old = scratch.git(sibling, "rev-parse", "HEAD");
    scratch.git(sibling, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "two");
    String released = scratch.git(sibling, "rev-parse", "HEAD");
    // Annotated, so the entry has to be peeled to the commit.
    scratch.git(sibling, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "-m", "r", "2026.2.2");
    scratch.foldWith(Map.of("pom.xml", POM, "Dockerfile", DOCKERFILE), Map.of("libs/old", old));

    String changes =
        "["
            + "{\"ecosystem\":\"maven\",\"manifestPath\":\"pom.xml\",\"name\":\"io.quarkus:quarkus-bom\","
            + "\"from\":\"3.1.0\",\"to\":\"3.2.0\",\"location\":\"property:quarkus.version\"},"
            + "{\"ecosystem\":\"maven\",\"manifestPath\":\"pom.xml\","
            + "\"name\":\"eu.wohlben.qits:qits-eventstream\",\"from\":\"2026.1.1\",\"to\":\"2026.2.2\","
            + "\"location\":\"dependency:eu.wohlben.qits:qits-eventstream\"},"
            + "{\"ecosystem\":\"docker\",\"manifestPath\":\"Dockerfile\",\"name\":\"qits/workspace\","
            + "\"from\":\"2026.1.1\",\"to\":\"2026.2.2\",\"location\":\"arg:BASE\"},"
            + "{\"ecosystem\":\"docker\",\"manifestPath\":\"Dockerfile\","
            + "\"name\":\"qits/build-images/node-base\",\"from\":\"1.0\",\"to\":\"1.1\","
            + "\"location\":\"line:3\"},"
            + "{\"ecosystem\":\"gitlink\",\"manifestPath\":\"libs/old\",\"name\":\"qits-sibling\","
            + "\"from\":\""
            + old
            + "\",\"to\":\"2026.2.2\",\"location\":\"gitlink:libs/old\"},"
            // No `from` and no `location`: two empty fields, one mid-record, which a tab-separated
            // read would collapse and shift.
            + "{\"ecosystem\":\"gitlink\",\"manifestPath\":\"libs/new\",\"name\":\"qits-sibling\","
            + "\"to\":\"2026.2.2\"}"
            + "]";
    String paths = "\"pom.xml\",\"Dockerfile\",\"libs/old\",\"libs/new\"";
    Result run =
        scratch.runStep("bash", dependencyBumpStep(), bumpPayload(scratch, changes, paths), Map.of());

    assertEquals(0, run.exit, run.output);
    String pushed = scratch.originRev("refs/heads/" + BUMP_BRANCH);
    assertEquals(scratch.fold, scratch.git(scratch.origin, "rev-parse", pushed + "^"), "one commit");
    assertEquals(
        "bump(qits-978): 6 dependencies",
        scratch.git(scratch.origin, "log", "-1", "--format=%s", pushed));
    String body = scratch.git(scratch.origin, "log", "-1", "--format=%b", pushed);
    assertEquals(6, body.lines().count(), body);
    assertTrue(body.contains("- maven io.quarkus:quarkus-bom 3.1.0 -> 3.2.0 (pom.xml)"), body);
    assertTrue(body.contains("- gitlink qits-sibling  -> 2026.2.2 (libs/new)"), body);
    assertEquals(
        "Dockerfile\nlibs/new\nlibs/old\npom.xml",
        scratch.git(scratch.origin, "diff-tree", "--no-commit-id", "--name-only", "-r", pushed),
        "exactly the manifests, nothing the run wrote under /tmp or elsewhere");

    String pom = scratch.git(scratch.origin, "show", pushed + ":pom.xml");
    assertTrue(pom.contains("<quarkus.version>3.2.0</quarkus.version>"), pom);
    assertTrue(pom.contains("<version>2026.2.2</version>"), pom);
    String dockerfile = scratch.git(scratch.origin, "show", pushed + ":Dockerfile");
    assertTrue(
        dockerfile.contains("ARG BASE=registry.example:5000/qits/workspace:2026.2.2\n"), dockerfile);
    assertTrue(dockerfile.contains("FROM qits/build-images/node-base:1.1 AS build"), dockerfile);
    assertTrue(dockerfile.contains("FROM ${BASE}\n"), dockerfile);
    // Both gitlinks at the tag's COMMIT: the moved one, and the new one that only kept its entry
    // through the postlude's `git add -A` because the script gave it a directory.
    assertEquals(
        "160000 commit " + released + "\tlibs/new\n160000 commit " + released + "\tlibs/old",
        scratch.git(scratch.origin, "ls-tree", pushed, "libs/"));
  }

  @Test
  public void aChangeThatCannotBeAppliedFailsTheStepAndPushesNothing(@TempDir Path dir)
      throws Exception {
    Scratch scratch = new Scratch(dir, "dependency-bump", "bump");
    scratch.foldWith(
        Map.of(
            "pom.xml", POM,
            "web/package.json", "{\"dependencies\":{\"left-pad\":\"latest\"}}\n",
            "web/package-lock.json", "{}\n"),
        Map.of());
    String step = dependencyBumpStep();
    Map<String, String> refusals =
        Map.of(
            "a property the pom does not hold",
            "[{\"ecosystem\":\"maven\",\"manifestPath\":\"pom.xml\",\"name\":\"x:y\",\"to\":\"1\","
                + "\"location\":\"property:nope.version\"}]",
            "an npm specifier a bump cannot move",
            "[{\"ecosystem\":\"npm\",\"manifestPath\":\"web/package.json\",\"name\":\"left-pad\","
                + "\"to\":\"1.3.0\"}]",
            "an unknown ecosystem",
            "[{\"ecosystem\":\"cargo\",\"manifestPath\":\"pom.xml\",\"name\":\"x\",\"to\":\"1\"}]",
            "an implausible version",
            "[{\"ecosystem\":\"maven\",\"manifestPath\":\"pom.xml\",\"name\":\"x:y\",\"to\":\"1;rm\","
                + "\"location\":\"property:quarkus.version\"}]",
            "a climbing manifest",
            "[{\"ecosystem\":\"docker\",\"manifestPath\":\"../Dockerfile\",\"name\":\"x\",\"to\":\"1\"}]",
            "no changes",
            "[]",
            "a change missing its version",
            "[{\"ecosystem\":\"maven\",\"manifestPath\":\"pom.xml\",\"name\":\"x:y\"}]");
    for (Map.Entry<String, String> refusal : refusals.entrySet()) {
      Result run =
          scratch.runStep(
              "bash",
              step,
              bumpPayload(scratch, refusal.getValue(), "\"pom.xml\",\"web/package.json\""),
              Map.of());
      // Non-zero rather than 1: a refusal jq raises exits with jq's own status.
      assertTrue(run.exit != 0, refusal.getKey() + ":\n" + run.output);
      assertFalse(run.output.contains("pushed "), refusal.getKey() + ":\n" + run.output);
    }
    assertEquals("", scratch.originRevOrEmpty("refs/heads/" + BUMP_BRANCH), "nothing was pushed");
  }

  // --- the scratch origin ------------------------------------------------------------------------

  private record Result(int exit, String output) {}

  /** An origin holding {@code main} and a fold on {@code release/abc}, and a clone of main. */
  private static final class Scratch {
    final Path root;
    final Path origin;
    final Path seed;
    final Path work;
    final Path home;
    final String kind;
    final String commitType;
    String fold;

    Scratch(Path root) throws Exception {
      this(root, "test-kind", null);
    }

    /** The same origin, for a step composed as {@code kind} with this {@code commit-type}. */
    Scratch(Path root, String kind, String commitType) throws Exception {
      assumeTrue(available("jq"), "jq is not installed on this host");
      this.root = root;
      this.kind = kind;
      this.commitType = commitType;
      Files.createDirectories(root);
      origin = root.resolve("origin.git");
      seed = root.resolve("seed");
      work = root.resolve("work");
      home = root.resolve("home");
      Files.createDirectories(home);
      git(root, "init", "-q", "--bare", "--initial-branch=main", origin.toString());
      git(root, "init", "-q", "-b", "main", seed.toString());
      Files.writeString(seed.resolve("README.md"), "main\n");
      git(seed, "add", "README.md");
      commit(seed, "chore: start");
      git(seed, "push", "-q", origin.toString(), "main");
      git(seed, "checkout", "-q", "-b", "release/abc");
      Files.writeString(seed.resolve("feature.txt"), "folded\n");
      git(seed, "add", "feature.txt");
      commit(seed, "feat(qits-978): fold a source branch");
      git(seed, "push", "-q", origin.toString(), "release/abc");
      fold = git(seed, "rev-parse", "HEAD");
      // The step's own clone is of main: the run is recorded at main's head.
      git(root, "clone", "-q", "-b", "main", origin.toString(), work.toString());
    }

    void advanceFold() throws Exception {
      Files.writeString(seed.resolve("feature.txt"), "refolded\n");
      git(seed, "add", "feature.txt");
      commit(seed, "feat(qits-978): fold again");
      git(seed, "push", "-q", origin.toString(), "release/abc");
      fold = git(seed, "rev-parse", "HEAD");
    }

    /**
     * One more commit on the fold, writing {@code files} and pointing {@code gitlinks} (path to
     * commit sha) — a gitlink is an index entry, so the seed needs none of the sibling's objects.
     */
    void foldWith(Map<String, String> files, Map<String, String> gitlinks) throws Exception {
      for (Map.Entry<String, String> file : files.entrySet()) {
        Path target = seed.resolve(file.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.getValue());
        git(seed, "add", file.getKey());
      }
      for (Map.Entry<String, String> link : gitlinks.entrySet()) {
        git(seed, "update-index", "--add", "--cacheinfo", "160000," + link.getValue() + "," + link.getKey());
      }
      commit(seed, "feat(qits-978): fold the manifests");
      git(seed, "push", "-q", origin.toString(), "release/abc");
      fold = git(seed, "rev-parse", "HEAD");
    }

    String payload(String branch, String foldSha, String paths) {
      return "{\"kind\":\"" + kind + "\",\"repository\":\"qits-target\",\"requestId\":\"abc\","
          + "\"foldSha\":\""
          + foldSha
          + "\",\"baseRef\":\"release/abc\",\"branch\":\""
          + branch
          + "\",\"commitPaths\":["
          + paths
          + "]}";
    }

    Result run(String shell, String kindScript, String payload) throws Exception {
      return run(shell, kindScript, payload, false, null, Map.of());
    }

    /**
     * Runs the composed step. With {@code qitsCli}, the download base is replaced by {@code store}
     * — the seam {@code QitsCliPinIT} uses, since no step can reach it — and {@code env} is added.
     */
    Result run(
        String shell,
        String kindScript,
        String payload,
        boolean qitsCli,
        String store,
        Map<String, String> extraEnv)
        throws Exception {
      String step =
          CiAutomationComposer.step(kind, "test-image", kindScript, qitsCli, commitType);
      if (store != null) {
        step = step.replace(CiReleaseComposer.CLI_DOWNLOAD_BASE, store);
      }
      return runStep(shell, step, payload, extraEnv);
    }

    /** Runs a step's whole text, composed elsewhere — a packaged kind file's, say. */
    Result runStep(String shell, String step, String payload, Map<String, String> extraEnv)
        throws Exception {
      Path script = Files.createTempFile(root, "step", ".sh");
      Files.writeString(script, step, StandardCharsets.UTF_8);
      ProcessBuilder builder =
          new ProcessBuilder(shell, script.toString()).directory(work.toFile()).redirectErrorStream(true);
      Map<String, String> env = builder.environment();
      env.keySet().removeIf(name -> name.startsWith("QITS_") || name.startsWith("GIT_"));
      env.put("HOME", home.toString());
      env.put("GIT_CONFIG_NOSYSTEM", "1");
      env.put("GIT_CONFIG_GLOBAL", "/dev/null");
      env.put("QITS_DOMAIN", "example.invalid");
      env.put("QITS_CI_REPOSITORY_URL", origin.toString());
      env.put("QITS_EVENT_NAME", CiAutomationComposer.EVENT);
      env.put("QITS_EVENT_PAYLOAD", payload);
      env.putAll(extraEnv);
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the step did not return");
      // Every run starts again from the clone's main, as a fresh step container would.
      git(work, "checkout", "-q", "--detach", "origin/main");
      git(work, "reset", "-q", "--hard");
      git(work, "clean", "-qfdx");
      return new Result(process.exitValue(), output);
    }

    String originRev(String ref) throws Exception {
      return git(origin, "rev-parse", "--verify", ref);
    }

    String originRevOrEmpty(String ref) throws Exception {
      Process process = start(origin, "rev-parse", "--verify", "-q", ref);
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      return process.exitValue() == 0 ? out : "";
    }

    String git(Path dir, String... args) throws Exception {
      Process process = start(dir, args);
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), "git " + String.join(" ", args) + ":\n" + out);
      return out;
    }

    private void commit(Path repo, String message) throws Exception {
      git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message);
    }

    private static Process start(Path dir, String... args) throws Exception {
      List<String> command = new ArrayList<>();
      command.add("git");
      command.addAll(List.of(args));
      ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
      builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
      builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
      return builder.start();
    }

    private static boolean available(String program) {
      try {
        Process process = new ProcessBuilder(program, "--version").redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  }
}
