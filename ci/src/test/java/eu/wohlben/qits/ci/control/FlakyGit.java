package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A git host having a bad moment (qits-1253), for a step script run with {@link #bin} first on
 * {@code PATH}: a {@code git} that answers the invocations of a subcommand it is told to fail with
 * the 503 run 135af4fc met, and hands every other one to the real git; and a {@code sleep} that
 * records how long it was asked to wait and returns at once, so a retry costs the suite nothing and
 * the delays the production text asks for are still asserted.
 *
 * <p>Failing is per subcommand and per invocation number: {@code fail("push", "all")}, {@code
 * fail("fetch", "1")}, {@code fail("fetch", "2 3 4")}.
 */
final class FlakyGit {

  static final String FAILURE =
      "fatal: unable to access 'x': The requested URL returned error: 503";

  final Path bin;
  final Path state;

  FlakyGit(Path root) throws Exception {
    bin = Files.createDirectories(root.resolve("flaky-bin"));
    state = Files.createDirectories(root.resolve("flaky-state"));
    String realGit = realGit();
    Files.writeString(
        bin.resolve("git"),
        """
        #!/bin/sh
        state=${FLAKY_GIT_STATE:?}
        sub=
        skip=
        for arg in "$@"; do
          if [ -n "$skip" ]; then skip=; continue; fi
          case "$arg" in
            -c|-C) skip=1 ;;
            -*) ;;
            *) sub=$arg; break ;;
          esac
        done
        if [ -n "$sub" ]; then
          echo "$sub" >> "$state/calls"
          n=$(grep -cxF "$sub" "$state/calls")
          fail=$(cat "$state/fail-$sub" 2>/dev/null || true)
          case " $fail " in
            *" all "*|*" $n "*) echo "%s" >&2; exit 128 ;;
          esac
        fi
        exec '%s' "$@"
        """
            .formatted(FAILURE, realGit));
    Files.writeString(
        bin.resolve("sleep"),
        """
        #!/bin/sh
        echo "$*" >> "${FLAKY_GIT_STATE:?}/sleeps"
        """);
    assertTrue(bin.resolve("git").toFile().setExecutable(true));
    assertTrue(bin.resolve("sleep").toFile().setExecutable(true));
  }

  /** Fails these invocations of {@code subcommand}: numbers from 1, or {@code all}. */
  FlakyGit fail(String subcommand, String invocations) throws Exception {
    Files.writeString(state.resolve("fail-" + subcommand), invocations);
    return this;
  }

  /** What the step's environment needs: this {@code PATH} first, and where to keep count. */
  Map<String, String> env() {
    return Map.of("PATH", bin + ":" + System.getenv("PATH"), "FLAKY_GIT_STATE", state.toString());
  }

  /** How often the step ran {@code git <subcommand>}, failed or not. */
  long calls(String subcommand) throws Exception {
    Path calls = state.resolve("calls");
    if (!Files.exists(calls)) {
      return 0;
    }
    return Files.readAllLines(calls).stream().filter(subcommand::equals).count();
  }

  /** Every {@code sleep} the step asked for, in order. */
  List<String> sleeps() throws Exception {
    Path sleeps = state.resolve("sleeps");
    return Files.exists(sleeps) ? Files.readAllLines(sleeps) : List.of();
  }

  private static String realGit() throws Exception {
    Process process = new ProcessBuilder("sh", "-c", "command -v git").start();
    String path = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), "no git on PATH");
    assertTrue(path.startsWith("/"), "git resolved to " + path);
    return path;
  }
}
