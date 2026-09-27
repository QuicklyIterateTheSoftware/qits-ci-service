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
 * The install script as rendered text, and as a script: every placeholder filled, the token in its
 * one assignment, {@code sh -n} happy with it, and — when qits-ci-runner-daemon is checked out
 * beside this repository, as it is in the qits-qits wrapper — that repository's own install
 * contract test run against a rendering. Plain JUnit: {@link RunnerInstallScript#render(
 * RunnerInstallScript.Values)} is a pure function of the template and six values.
 *
 * <p>{@link #FIXTURE} is the rendering qits-ci-runner-daemon keeps as its {@code
 * scripts/fixtures/runner-install.sh}; every run writes it to {@code
 * target/runner-install.fixture.sh}, which is the file to copy there when the template changes.
 */
class RunnerInstallScriptTest {

  static final RunnerInstallScript.Values FIXTURE =
      new RunnerInstallScript.Values(
          "http://dev-qits-ci:8080",
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "qits_tok_FIXTURE",
          2,
          "http://dev-qits-artifacts:8080",
          "0.0.0-fixture");

  /** The runner repository, where the wrapper checks it out beside this one. */
  private static final Path RUNNER_REPO =
      Path.of("").toAbsolutePath().resolve("../../qits-ci-runner-daemon").normalize();

  @Test
  void everyPlaceholderIsFilledAndTheTokenIsInItsOneAssignment() throws IOException {
    String script = RunnerInstallScript.render(FIXTURE);

    assertFalse(script.contains("{{"), script);
    assertTrue(script.startsWith("#!/bin/sh\n"));
    assertTrue(script.contains("\nset -eu\n"));
    assertTrue(script.contains("\nQITS_CI_RUNNER_URL='http://dev-qits-ci:8080'\n"));
    assertTrue(script.contains("\nQITS_CI_RUNNER_ID='00000000-0000-0000-0000-000000000001'\n"));
    assertTrue(script.contains("\nQITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_FIXTURE'\n"));
    assertTrue(script.contains("\nQITS_CI_RUNNER_SLOTS='2'\n"));
    assertTrue(
        script.contains(
            "\nQITS_CI_RUNNER_BINARY_URL='http://dev-qits-artifacts:8080/artifacts/daemons/"
                + "qits-ci-runner/0.0.0-fixture'\n"));
    assertEquals(1, occurrences(script, "qits_tok_FIXTURE"), "the token appears once");

    Path target = Path.of("target");
    Files.createDirectories(target);
    Files.writeString(target.resolve("runner-install.fixture.sh"), script, StandardCharsets.UTF_8);
  }

  @Test
  void aDrainedRowStillRendersASlotTheRunnerAccepts() {
    String script =
        RunnerInstallScript.render(
            new RunnerInstallScript.Values(
                FIXTURE.ciUrl(),
                FIXTURE.runnerId(),
                FIXTURE.registrationToken(),
                0,
                FIXTURE.artifactsUrl(),
                FIXTURE.runnerVersion()));

    assertTrue(script.contains("\nQITS_CI_RUNNER_SLOTS='1'\n"));
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
              RunnerInstallScript.render(
                  new RunnerInstallScript.Values(
                      url,
                      FIXTURE.runnerId(),
                      FIXTURE.registrationToken(),
                      2,
                      FIXTURE.artifactsUrl(),
                      FIXTURE.runnerVersion())),
          () -> "url " + url);
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            RunnerInstallScript.render(
                new RunnerInstallScript.Values(
                    FIXTURE.ciUrl(),
                    FIXTURE.runnerId(),
                    FIXTURE.registrationToken(),
                    2,
                    FIXTURE.artifactsUrl(),
                    "1.0'")));
  }

  @Test
  void shParsesTheRendering(@TempDir Path dir) throws Exception {
    assumeTrue(onPath("sh"), "no sh on PATH");
    Path script = dir.resolve("runner-install.sh");
    Files.writeString(script, RunnerInstallScript.render(FIXTURE));

    Ran sh = run(dir, "sh", "-n", script.toString());

    assertEquals(0, sh.exit(), sh.output());
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
        dir.resolve("scripts/fixtures/runner-install.sh"), RunnerInstallScript.render(FIXTURE));

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
