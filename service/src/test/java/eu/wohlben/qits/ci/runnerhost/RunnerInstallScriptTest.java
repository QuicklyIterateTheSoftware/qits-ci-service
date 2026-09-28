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
 * The two halves of an install: the generic script as rendered text and as a script — its image
 * filled, no token and no runner in it, {@code sh -n} happy with it, the container it starts against
 * a stub docker, and, when
 * qits-ci-runner-daemon is checked out beside this repository as it is in the qits-qits wrapper,
 * that repository's own install contract test run against a rendering — and the install line, which
 * carries the token exactly twice, parses as one sh command and hands the script its four values.
 * Plain JUnit: both are pure functions of the template and a few values.
 *
 * <p>{@link #REGISTRY} and {@link #VERSION} render the generic script qits-ci-runner-daemon keeps as
 * its {@code scripts/fixtures/runner-install.sh}; every run writes it to {@code
 * target/runner-install.fixture.sh}, which is the file to copy there when the template changes.
 */
class RunnerInstallScriptTest {

  static final String REGISTRY = "registry.qits.example.org";

  static final String IMAGE = REGISTRY + "/qits/qits-ci-runner:0.0.0-fixture";

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
    String script = RunnerInstallScript.generic(REGISTRY, VERSION);

    assertFalse(script.contains("{{"), script);
    assertFalse(script.contains("qits_tok_"), script);
    assertFalse(script.contains(LINE.runnerId().toString()), script);
    assertTrue(script.startsWith("#!/bin/sh\n"));
    assertTrue(script.contains("\nset -eu\n"));
    assertTrue(script.contains("  image='" + IMAGE + "'\n"), script);
    // A container now, and nothing of the systemd install is left in it.
    assertFalse(script.contains("systemctl"), script);
    assertFalse(script.contains("systemd"), script);
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
          () -> "registry " + url);
    }
    assertThrows(IllegalStateException.class, () -> RunnerInstallScript.generic(REGISTRY, "1.0'"));
    assertThrows(IllegalStateException.class, () -> RunnerInstallScript.generic(REGISTRY, "1.0+b"));
  }

  @Test
  void shParsesTheScriptAndTheLine(@TempDir Path dir) throws Exception {
    assumeTrue(onPath("sh"), "no sh on PATH");
    Path script = dir.resolve("runner-install.sh");
    Files.writeString(script, RunnerInstallScript.generic(REGISTRY, VERSION));
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
   * The rendered script run as the line runs it, twice — an install, then a rotation with a new
   * token — against a stub {@code docker} that records every call and answers {@code ps} with the
   * container the first run started. What is asserted is the runner's container contract: the login
   * is the registration token on stdin into a throwaway config, the pinned image is pulled before
   * anything is removed, every container labelled with this runner goes, and the one started carries
   * the name, labels, restart policy, socket, state volume and four values by name — and the token
   * is printed nowhere and on no docker argument.
   */
  @Test
  void theScriptStartsTheRunnerContainerAndARerunReplacesIt(@TempDir Path dir) throws Exception {
    assumeTrue(onPath("sh"), "no sh on PATH");
    Path stubs = Files.createDirectories(dir.resolve("stubs"));
    Path calls = dir.resolve("docker-calls");
    Path stdin = dir.resolve("docker-stdin");
    Path running = dir.resolve("running");
    Files.writeString(
        stubs.resolve("docker"),
        "#!/bin/sh\n"
            + "printf '%s\\n' \"$*\" >> '" + calls + "'\n"
            + "case \" $* \" in\n"
            + "  *' login '*) cat >> '" + stdin + "' ;;\n"
            + "  *' ps '*) [ -f '" + running + "' ] && cat '" + running + "' ;;\n"
            + "  *' run -d '*) printf 'c0ffee\\n' > '" + running + "'; printf 'c0ffee\\n' ;;\n"
            + "  *' run '*) : ;;\n"
            + "  *' rm '*) rm -f '" + running + "' ;;\n"
            + "esac\n"
            + "exit 0\n");
    assertTrue(stubs.resolve("docker").toFile().setExecutable(true));
    Path script = dir.resolve("runner-install.sh");
    Files.writeString(script, RunnerInstallScript.generic(REGISTRY, VERSION));
    String env =
        "PATH='" + stubs + "':\"$PATH\" QITS_CI_RUNNER_URL='https://ci.qits.example.org'"
            + " QITS_CI_RUNNER_ID='" + LINE.runnerId() + "' QITS_CI_RUNNER_SLOTS='2'";

    Ran first =
        run(dir, "sh", "-c", env + " QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_ONE' sh " + script);
    assertEquals(0, first.exit(), first.output());
    List<String> installed = Files.readAllLines(calls);
    Ran second =
        run(dir, "sh", "-c", env + " QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_TWO' sh " + script);
    assertEquals(0, second.exit(), second.output());
    List<String> all = Files.readAllLines(calls);
    List<String> rotated = all.subList(installed.size(), all.size());

    String name = "qits-ci-runner-00000000-0.0.0-fixture";
    assertEquals("qits-ci-runner started; watch: docker logs -f " + name + "\n", first.output());
    for (String output : List.of(first.output(), second.output())) {
      assertFalse(output.contains("qits_tok_"), output);
    }
    // The login reads the token on stdin with no trailing newline (printf '%s'), so two logins one
    // after another concatenate with nothing between them in the fixture's own stdin capture.
    assertEquals("qits_tok_ONEqits_tok_TWO", Files.readString(stdin));
    assertTrue(all.stream().noneMatch(call -> call.contains("qits_tok_")), all.toString());

    String removeClient =
        "run --rm --entrypoint rm -v qits-ci-runner-state-00000000:/var/lib/qits-ci-runner "
            + IMAGE
            + " -f /var/lib/qits-ci-runner/client.json";
    String run =
        "run -d --name " + name
            + " --restart unless-stopped"
            + " --label qits.ci.runner.process=" + LINE.runnerId()
            + " --label qits.ci.runner.version=0.0.0-fixture"
            + " -v /var/run/docker.sock:/var/run/docker.sock"
            + " -v qits-ci-runner-state-00000000:/var/lib/qits-ci-runner"
            + " -e QITS_CI_RUNNER_URL=https://ci.qits.example.org"
            + " -e QITS_CI_RUNNER_ID=" + LINE.runnerId()
            + " -e QITS_CI_RUNNER_SLOTS=2"
            + " -e QITS_CI_RUNNER_REGISTRATION_TOKEN "
            + IMAGE;
    String config = installed.get(1).replaceFirst("^--config (\\S+) login .*$", "$1");
    assertEquals(
        List.of(
            "version",
            "--config " + config + " login " + REGISTRY + " -u token --password-stdin",
            "--config " + config + " pull " + IMAGE,
            "ps -aq --filter label=qits.ci.runner.process=" + LINE.runnerId(),
            removeClient,
            run),
        installed);
    assertFalse(Files.exists(Path.of(config)), "the throwaway docker config is gone");
    // The rerun is the same install, with the container the first one started removed first, and
    // client.json cleared from the kept state volume so the new token is the one that registers.
    assertEquals("rm -f c0ffee", rotated.get(4));
    assertEquals(removeClient, rotated.get(5));
    assertEquals(run, rotated.get(6));
  }

  @Test
  void aHostWhoseDockerDoesNotAnswerIsRefusedBeforeAnything(@TempDir Path dir) throws Exception {
    assumeTrue(onPath("sh"), "no sh on PATH");
    Path stubs = Files.createDirectories(dir.resolve("stubs"));
    Files.writeString(stubs.resolve("docker"), "#!/bin/sh\nexit 1\n");
    assertTrue(stubs.resolve("docker").toFile().setExecutable(true));
    Path script = dir.resolve("runner-install.sh");
    Files.writeString(script, RunnerInstallScript.generic(REGISTRY, VERSION));

    Ran ran =
        run(
            dir,
            "sh",
            "-c",
            "PATH='" + stubs + "':\"$PATH\" QITS_CI_RUNNER_URL='https://ci.qits.example.org'"
                + " QITS_CI_RUNNER_ID='" + LINE.runnerId() + "' QITS_CI_RUNNER_SLOTS='2'"
                + " QITS_CI_RUNNER_REGISTRATION_TOKEN='qits_tok_X' sh " + script);

    assertEquals(1, ran.exit(), ran.output());
    assertTrue(ran.output().contains("docker version failed"), ran.output());
  }

  /**
   * qits-ci-runner-daemon's {@code scripts/test-install-contract.sh} against this rendering: the
   * test copied into a temp tree of that repository's shape, with the rendering as its fixture, so
   * the runner repository's working tree is never touched. Skipped where that repository is not
   * checked out beside this one — a clone-alone build — which is why the rendering is also that
   * repository's committed fixture, run by its own gate.
   */
  @Test
  void theRunnerRepositorysInstallContractPasses(@TempDir Path dir) throws Exception {
    Path contract = RUNNER_REPO.resolve("scripts/test-install-contract.sh");
    assumeTrue(Files.isRegularFile(contract), "qits-ci-runner-daemon is not beside this repository");
    assumeTrue(onPath("sh"), "no sh on PATH");
    Files.createDirectories(dir.resolve("scripts/fixtures"));
    Files.copy(contract, dir.resolve("scripts/test-install-contract.sh"));
    Files.writeString(
        dir.resolve("scripts/fixtures/runner-install.sh"),
        RunnerInstallScript.generic(REGISTRY, VERSION));

    Ran test = run(dir, "sh", "scripts/test-install-contract.sh");

    assertEquals(0, test.exit(), test.output());
    assertTrue(test.output().contains("PASS"), test.output());
  }

  /**
   * The reference script's own header states the contract in words: the template is written to
   * match {@code scripts/fixtures/runner-install.sh} EXACTLY, with only the rendered {@code image=}
   * and {@code version=} lines differing. This asserts that literally, line for line, against a
   * rendering built with the fixture's own {@link #REGISTRY} and {@link #VERSION} — so the two
   * files are compared as the same document rather than merely both passing the shell-level
   * contract test above. Skipped where qits-ci-runner-daemon is not checked out beside this
   * repository, exactly as {@link #theRunnerRepositorysInstallContractPasses} is.
   */
  @Test
  void theTemplateRendersTheRunnerRepositorysReferenceScriptExactly() throws IOException {
    Path fixture = RUNNER_REPO.resolve("scripts/fixtures/runner-install.sh");
    assumeTrue(Files.isRegularFile(fixture), "qits-ci-runner-daemon is not beside this repository");

    List<String> rendered = RunnerInstallScript.generic(REGISTRY, VERSION).lines().toList();
    List<String> reference = Files.readString(fixture, StandardCharsets.UTF_8).lines().toList();

    assertEquals(reference.size(), rendered.size(), "line count differs from the fixture");
    for (int i = 0; i < reference.size(); i++) {
      String referenceLine = reference.get(i);
      String renderedLine = rendered.get(i);
      if (isRenderedValueLine(referenceLine) && isRenderedValueLine(renderedLine)) {
        continue;
      }
      assertEquals(referenceLine, renderedLine, "line " + (i + 1) + " differs from the fixture");
    }
  }

  /** {@code image='...'} or {@code version=...}: the two lines the template is allowed to render. */
  private static boolean isRenderedValueLine(String line) {
    String trimmed = line.trim();
    return trimmed.startsWith("image=") || trimmed.startsWith("version=");
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
