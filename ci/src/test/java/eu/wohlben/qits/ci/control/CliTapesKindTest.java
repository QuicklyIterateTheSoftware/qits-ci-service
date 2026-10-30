package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code cli-tapes} kind file's script (epic qits-881), EXECUTED: the packaged text, run under
 * {@code bash -eu} in a scratch repository with a stand-in {@code vhs} on {@code PATH} that writes
 * its tape's one {@code Output} from the tape's body and the environment {@code tapes/prepare.sh}
 * exported. What it holds: no {@code tapes/prepare.sh} is green and plays nothing; the hook is
 * sourced, so its exports reach the tapes; every tape is played into its golden with only that
 * Output; a GIF is rendered only for a changed golden or a missing GIF; orphans are deleted; and a
 * failing vhs fails the script.
 */
public class CliTapesKindTest {

  private static final String KIND_FILE = "platform-pipelines/automations/cli-tapes.yml";

  private static final String FAKE_VHS =
      """
      #!/bin/bash
      set -eu
      tape="$1"
      echo "vhs $(sed -n '1p' "$tape")" >> "$VHS_LOG"
      if [ -n "${VHS_FAIL:-}" ]; then exit 3; fi
      if [ "$(grep -c '^Output' "$tape")" != 1 ]; then echo "more than one Output" >&2; exit 9; fi
      out=$(sed -n '1s/^Output //p' "$tape")
      { echo "greeting=$GREETING"; tail -n +2 "$tape"; } > "$out"
      """;

  private static final String PREPARE =
      """
      # sourced: what it exports reaches the tapes
      export GREETING=hello
      """;

  @TempDir Path dir;

  private Path repo;
  private Path bin;
  private Path log;

  @BeforeEach
  void setUp() throws Exception {
    repo = dir.resolve("repo");
    bin = dir.resolve("bin");
    log = dir.resolve("vhs.log");
    Files.createDirectories(repo);
    Files.createDirectories(bin);
    Path vhs = bin.resolve("vhs");
    Files.writeString(vhs, FAKE_VHS);
    Files.setPosixFilePermissions(vhs, PosixFilePermissions.fromString("rwxr-xr-x"));
    git("init", "-q", "-b", "main");
    Files.writeString(repo.resolve("README.md"), "a cli\n");
    commit("initial");
  }

  @Test
  public void aRepositoryWithoutPrepareShHasNoTapesAndIsGreen() throws Exception {
    Files.createDirectories(repo.resolve("tapes"));
    Files.writeString(repo.resolve("tapes/hello.tape"), "Type \"qits\"\n");
    Result run = run(Map.of());
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("this repository has no tapes"), run.output);
    assertFalse(Files.exists(log), "nothing is played");
  }

  @Test
  public void textFirstAndAGifOnlyWhenTheTextMovedOrTheGifIsMissing() throws Exception {
    Files.createDirectories(repo.resolve("tapes"));
    Files.writeString(repo.resolve("tapes/prepare.sh"), PREPARE);
    Files.writeString(
        repo.resolve("tapes/hello.tape"), "Output demo.gif\nOutput demo.txt\nType \"qits\"\n");
    commit("a tape");

    // First run: the golden and the GIF are both new.
    Result first = run(Map.of());
    assertEquals(0, first.exit, first.output);
    assertEquals(
        List.of("vhs Output tapes/golden/hello.txt", "vhs Output tapes/recordings/hello.gif"),
        plays());
    String golden = Files.readString(repo.resolve("tapes/golden/hello.txt"));
    assertEquals("greeting=hello\nType \"qits\"\n", golden, "sourced exports, no tape Output");
    assertTrue(Files.exists(repo.resolve("tapes/recordings/hello.gif")));
    assertFalse(Files.exists(repo.resolve("demo.gif")), "the tape's own Output never decides");
    assertFalse(Files.exists(repo.resolve("demo.txt")), "the tape's own Output never decides");
    commit("goldens");

    // Unchanged tape: one play into the text, no GIF render, nothing changed.
    Result second = run(Map.of());
    assertEquals(0, second.exit, second.output);
    assertEquals(List.of("vhs Output tapes/golden/hello.txt"), plays());
    assertTrue(second.output.contains("no GIF render"), second.output);
    assertEquals("", git("status", "--porcelain", "--untracked-files=all"));

    // A missing GIF is rendered even though the text did not move.
    Files.delete(repo.resolve("tapes/recordings/hello.gif"));
    Result missing = run(Map.of());
    assertEquals(0, missing.exit, missing.output);
    assertEquals(
        List.of("vhs Output tapes/golden/hello.txt", "vhs Output tapes/recordings/hello.gif"),
        plays());
    assertEquals("", git("status", "--porcelain", "--untracked-files=all"));

    // A changed tape moves the golden, and so re-renders the GIF.
    Files.writeString(repo.resolve("tapes/hello.tape"), "Type \"qits --help\"\n");
    Result changed = run(Map.of());
    assertEquals(0, changed.exit, changed.output);
    assertEquals(
        List.of("vhs Output tapes/golden/hello.txt", "vhs Output tapes/recordings/hello.gif"),
        plays());
    assertEquals(
        " M tapes/golden/hello.txt\n M tapes/hello.tape\n M tapes/recordings/hello.gif",
        git("status", "--porcelain", "--untracked-files=all"));
    commit("a changed tape");

    // A tape that is gone leaves no golden and no GIF behind.
    Files.delete(repo.resolve("tapes/hello.tape"));
    commit("tape removed");
    Result orphans = run(Map.of());
    assertEquals(0, orphans.exit, orphans.output);
    assertFalse(Files.exists(log), "no tape, nothing played");
    assertFalse(Files.exists(repo.resolve("tapes/golden/hello.txt")));
    assertFalse(Files.exists(repo.resolve("tapes/recordings/hello.gif")));
  }

  @Test
  public void aFailingVhsFailsTheScript() throws Exception {
    Files.createDirectories(repo.resolve("tapes"));
    Files.writeString(repo.resolve("tapes/prepare.sh"), PREPARE);
    Files.writeString(repo.resolve("tapes/hello.tape"), "Type \"qits\"\n");
    Result run = run(Map.of("VHS_FAIL", "1"));
    assertNotEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("vhs failed playing tapes/hello.tape"), run.output);
  }

  @Test
  public void aFailingPrepareShFailsTheScriptBeforeAnyTape() throws Exception {
    Files.createDirectories(repo.resolve("tapes"));
    Files.writeString(repo.resolve("tapes/prepare.sh"), "false\n");
    Files.writeString(repo.resolve("tapes/hello.tape"), "Type \"qits\"\n");
    Result run = run(Map.of());
    assertNotEquals(0, run.exit, run.output);
    assertFalse(Files.exists(log), "no tape played");
  }

  @Test
  public void withoutVhsTheScriptFailsSayingSo() throws Exception {
    Files.createDirectories(repo.resolve("tapes"));
    Files.writeString(repo.resolve("tapes/prepare.sh"), PREPARE);
    // An empty PATH, not /usr/bin:/bin: a CI image that carries vhs (ci-slot does) would find it
    // there. The script reaches the check through builtins alone.
    Path nothing = Files.createDirectories(dir.resolve("empty-path"));
    Result run = run(Map.of("PATH", nothing.toString()));
    assertNotEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("vhs is not on PATH"), run.output);
  }

  // --- harness -----------------------------------------------------------------------------------

  private record Result(int exit, String output) {}

  private List<String> plays() throws Exception {
    List<String> lines = Files.readAllLines(log);
    Files.delete(log);
    return lines;
  }

  private Result run(Map<String, String> extraEnv) throws Exception {
    String script;
    try (var in = getClass().getClassLoader().getResourceAsStream(KIND_FILE)) {
      Object declared =
          CiConfigSchema.load(new String(in.readAllBytes(), StandardCharsets.UTF_8), true)
              .get("script");
      script = (String) declared;
    }
    Path file = dir.resolve("cli-tapes.sh");
    Files.writeString(file, script);
    ProcessBuilder builder =
        new ProcessBuilder("bash", "-eu", file.toString())
            .directory(repo.toFile())
            .redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(name -> name.startsWith("GIT_") || name.startsWith("VHS_"));
    env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    env.put("TMPDIR", dir.toString());
    env.put("VHS_LOG", log.toString());
    env.put("GIT_CONFIG_NOSYSTEM", "1");
    env.put("GIT_CONFIG_GLOBAL", "/dev/null");
    env.putAll(extraEnv);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the script did not return");
    return new Result(process.exitValue(), output);
  }

  private void commit(String message) throws Exception {
    git("add", "-A");
    git(
        "-c",
        "user.name=test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        message);
  }

  private String git(String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
    builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    Process process = builder.start();
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), String.join(" ", command) + ":\n" + output);
    return output;
  }
}
