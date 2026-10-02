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
 * .config/qits/platform-pipelines/} is in this jar, byte for byte, parses as an event trigger, and
 * its step scripts pass {@code bash -n}.
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

  /** This repository's own copy, relative to the {@code ci} module surefire runs in. */
  private static final Path SOURCE = Path.of("..", ".config", "qits", "platform-pipelines");

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
        List.of("MaintenanceBump", "ScreenshotBaselines"),
        packaged().stream()
            .map(file -> triggerParser.parse(file.path(), file.content()).eventName())
            .toList());
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
