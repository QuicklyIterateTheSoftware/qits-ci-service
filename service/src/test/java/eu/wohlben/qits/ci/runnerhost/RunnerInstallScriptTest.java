package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two halves of an install: the generic script as rendered text and as a script — both
 * placeholders filled, no token and no runner in it, {@code sh -n} happy with it, and, when
 * qits-ci-runner-daemon is checked out beside this repository as it is in the qits-qits wrapper,
 * that repository's own install contract test run against a rendering — and the install line, which
 * carries the token exactly twice, parses as one sh command and hands the script its four values.
 * Plain JUnit: both are pure functions of the template and a few values.
 *
 * <p>{@link #ARTIFACTS} and {@link #VERSION} render the generic script qits-ci-runner-daemon keeps as
 * its {@code scripts/fixtures/runner-install.sh}; every run writes it to {@code
 * target/runner-install.fixture.sh}, which is the file to copy there when the template changes.
 */
class RunnerInstallScriptTest {

  static final String ARTIFACTS = "https://registry.qits.example.org";

  static final String VERSION = "0.0.0-fixture";

  static final RunnerInstallScript.Line LINE =
      new RunnerInstallScript.Line(
          "https://ci.qits.example.org",
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "qits_tok_FIXTURE",
          2);

  /** The runner repository, where the wrapper checks it out beside this one. */
  private static final Path RUNNER_REPO =
      Path.of("").toAbsolutePath().resolve("../../qits-ci-runner-daemon").normalize();

  @Test
  void theGenericScriptCarriesNoSecretNoRunnerAndNoPlaceholder() throws IOException {
    String script = RunnerInstallScript.generic(ARTIFACTS, VERSION);

    assertFalse(script.contains("{{"), script);
    assertFalse(script.contains("qits_tok_"), script);
    assertFalse(script.contains(LINE.runnerId().toString()), script);
    assertTrue(script.startsWith("#!/bin/sh\n"));
    assertTrue(script.contains("\nset -eu\n"));
    assertTrue(
        script.contains(
            "binary_url='https://registry.qits.example.org/artifacts/daemons/qits-ci-runner/"
                + "0.0.0-fixture'\n"),
        script);
    // The four values come from the environment, each refused by name when missing.
    for (String name :
        List.of(
            "QITS_CI_RUNNER_URL",
            "QITS_CI_RUNNER_ID",
            "QITS_CI_RUNNER_REGISTRATION_TOKEN",
            "QITS_CI_RUNNER_SLOTS")) {
      assertTrue(script.contains("require " + name + " \"${" + name + ":-}\""), name);
    }
    // Everything runs from the last line, so a download cut short runs nothing.
    assertTrue(script.endsWith("\nmain \"$@\"\n"), script);

    Path target = Path.of("target");
    Files.createDirectories(target);
    Files.writeString(target.resolve("runner-install.fixture.sh"), script, StandardCharsets.UTF_8);
  }

  @Test
  void theLineIsOneCommandCarryingTheTokenTwice() {
    String line = RunnerInstallScript.line(LINE);

    assertEquals(
        "curl -fsSL -H 'Authorization: Bearer qits_tok_FIXTURE'"
            + " https://ci.qits.example.org/ci/api/runners/install.sh"
            + " | sudo env QITS_CI_RUNNER_URL='https://ci.qits.example.org'"
            + " QITS_CI_RUNNER_ID='00000000-0000-0000-0000-000000000001'"
            + " QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_FIXTURE'"
            + " QITS_CI_RUNNER_SLOTS='2' sh",
        line);
    assertFalse(line.contains("\n"), "one line");
    assertEquals(2, occurrences(line, "qits_tok_FIXTURE"), "the token appears twice");
  }

  @Test
  void aDrainedRowStillRendersASlotTheRunnerAccepts() {
    String line =
        RunnerInstallScript.line(
            new RunnerInstallScript.Line(
                LINE.ciUrl(), LINE.runnerId(), LINE.registrationToken(), 0));

    assertTrue(line.contains(" QITS_CI_RUNNER_SLOTS='1' sh"), line);
  }

  @Test
  void aValueThatWouldBreakTheQuotingIsRefused() {
    for (String token : List.of("qits_tok_'x", "qits_tok_ x", "qits_tok_$x", "qits_tok_\nx", "")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> RunnerInstallScript.requireCarriable(token),
          () -> "token " + token);
    }
    for (String url :
        List.of("http://dev-qits-ci:8080'; rm -rf /", "ftp://dev-qits-ci", "http://h/{{X}}", "")) {
      assertThrows(
          IllegalStateException.class,
          () ->
              RunnerInstallScript.line(
                  new RunnerInstallScript.Line(
                      url, LINE.runnerId(), LINE.registrationToken(), 2)),
          () -> "url " + url);
      assertThrows(
          IllegalStateException.class,
          () -> RunnerInstallScript.generic(url, VERSION),
          () -> "artifacts url " + url);
    }
    assertThrows(IllegalStateException.class, () -> RunnerInstallScript.generic(ARTIFACTS, "1.0'"));
  }

  @Test
  void shParsesTheScriptAndTheLine(@TempDir Path dir) throws Exception {
    assumeTrue(onPath("sh"), "no sh on PATH");
    Path script = dir.resolve("runner-install.sh");
    Files.writeString(script, RunnerInstallScript.generic(ARTIFACTS, VERSION));
    Path line = dir.resolve("line.sh");
    Files.writeString(line, RunnerInstallScript.line(LINE) + "\n");

    Ran scriptParse = run(dir, "sh", "-n", script.toString());
    Ran lineParse = run(dir, "sh", "-n", line.toString());

    assertEquals(0, scriptParse.exit(), scriptParse.output());
    assertEquals(0, lineParse.exit(), lineParse.output());
  }

  /**
   * The line as a shell runs it, with {@code curl} and {@code sudo} stubbed: curl is asked for the
   * install script with the token as its bearer, and what it answers is run by {@code sh} with the
   * four values in its environment.
   */
  @Test
  void theLineFetchesTheScriptAndHandsItTheFourValues(@TempDir Path dir) throws Exception {
    assumeTrue(onPath("sh"), "no sh on PATH");
    Path stubs = Files.createDirectories(dir.resolve("stubs"));
    Files.writeString(
        stubs.resolve("curl"),
        "#!/bin/sh\n"
            + "printf '%s\\n' \"$@\" > \""
            + dir.resolve("curl-args")
            + "\"\n"
            + "printf '%s\\n' 'echo \"$QITS_CI_RUNNER_URL|$QITS_CI_RUNNER_ID|"
            + "$QITS_CI_RUNNER_REGISTRATION_TOKEN|$QITS_CI_RUNNER_SLOTS\"'\n");
    Files.writeString(stubs.resolve("sudo"), "#!/bin/sh\nexec \"$@\"\n");
    for (String stub : List.of("curl", "sudo")) {
      assertTrue(stubs.resolve(stub).toFile().setExecutable(true));
    }

    Ran ran =
        run(
            dir,
            "sh",
            "-c",
            "PATH='" + stubs + "':\"$PATH\"; " + RunnerInstallScript.line(LINE));

    assertEquals(0, ran.exit(), ran.output());
    assertEquals(
        "https://ci.qits.example.org|00000000-0000-0000-0000-000000000001|qits_tok_FIXTURE|2\n",
        ran.output());
    assertEquals(
        List.of(
            "-fsSL",
            "-H",
            "Authorization: Bearer qits_tok_FIXTURE",
            "https://ci.qits.example.org/ci/api/runners/install.sh"),
        Files.readAllLines(dir.resolve("curl-args")));
  }

  /**
   * qits-ci-runner-daemon's {@code scripts/test-install-contract.sh} against this rendering: the
   * test and the unit copied into a temp tree of that repository's shape, with the rendering as its
   * fixture, so the runner repository's working tree is never touched. Skipped where that
   * repository is not checked out beside this one — a clone-alone build — which is why the rendering
   * is also that repository's committed fixture, run by its own gate.
   */
  @Test
  void theRunnerRepositorysInstallContractPasses(@TempDir Path dir) throws Exception {
    Path contract = RUNNER_REPO.resolve("scripts/test-install-contract.sh");
    Path unit = RUNNER_REPO.resolve("packaging/qits-ci-runner.service");
    assumeTrue(Files.isRegularFile(contract), "qits-ci-runner-daemon is not beside this repository");
    assumeTrue(onPath("sh"), "no sh on PATH");
    Files.createDirectories(dir.resolve("scripts/fixtures"));
    Files.createDirectories(dir.resolve("packaging"));
    Files.copy(contract, dir.resolve("scripts/test-install-contract.sh"));
    Files.copy(unit, dir.resolve("packaging/qits-ci-runner.service"));
    Files.writeString(
        dir.resolve("scripts/fixtures/runner-install.sh"),
        RunnerInstallScript.generic(ARTIFACTS, VERSION));

    Ran test = run(dir, "sh", "scripts/test-install-contract.sh");

    assertEquals(0, test.exit(), test.output());
    assertTrue(test.output().contains("PASS"), test.output());
  }

  private record Ran(int exit, String output) {}

  /** Runs {@code command} in {@code dir}, stdout and stderr together, through a file. */
  private static Ran run(Path dir, String... command) throws Exception {
    Path log = Files.createTempFile("runner-install", ".log");
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(dir.toFile())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("timed out: " + String.join(" ", command));
      }
      return new Ran(process.exitValue(), Files.readString(log));
    } finally {
      Files.deleteIfExists(log);
    }
  }

  private static boolean onPath(String tool) {
    String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    for (String entry : path.split(File.pathSeparator)) {
      if (Files.isExecutable(Path.of(entry, tool))) {
        return true;
      }
    }
    return false;
  }

  private static int occurrences(String text, String needle) {
    return text.split(Pattern.quote(needle), -1).length - 1;
  }
}
