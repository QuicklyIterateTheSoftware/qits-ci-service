package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code CiDaemonRegistryTimeoutTest}'s rule for the runner side: a run's driver parks in this
 * package on every launch and every reap, so a wait with no deadline here wedges a run forever and
 * holds the runner's slot with it. The pattern is that test's, copied rather than shared because a
 * test class is not an API; the two must stay the same shape.
 */
class CiRunnerRegistryTimeoutTest {

  private static final Pattern UNTIMED =
      Pattern.compile("\\.(get|join|indefinitely)\\(\\)|[A-Za-z]+AndAwait\\s*\\(");

  @Test
  void aFutureNobodyCompletesIsAnsweredAtItsDeadline() {
    long start = System.nanoTime();
    String answer =
        CiRunnerRegistry.await(new CompletableFuture<String>(), Duration.ofMillis(150), "late");
    assertEquals("late", answer);
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 5_000);
  }

  @Test
  void noAwaitInTheRunnerhostPackageIsUntimed() throws IOException {
    Path pkg = Path.of("src/main/java/eu/wohlben/qits/ci/runnerhost");
    assertTrue(Files.isDirectory(pkg), "expected the runnerhost sources at " + pkg.toAbsolutePath());
    List<String> offences = new ArrayList<>();
    try (Stream<Path> sources = Files.list(pkg)) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        List<String> lines = Files.readAllLines(source);
        for (int i = 0; i < lines.size(); i++) {
          String code = lines.get(i);
          int comment = code.indexOf("//");
          if (comment >= 0) {
            code = code.substring(0, comment);
          }
          if (code.strip().startsWith("*") || !UNTIMED.matcher(code).find()) {
            continue;
          }
          offences.add(source.getFileName() + ":" + (i + 1) + " " + lines.get(i).strip());
        }
      }
    }
    assertEquals(
        List.of(),
        offences,
        "untimed waits in runnerhost — a run's driver parks here, so one of these wedges the run"
            + " and holds its runner's slot. Spell the bounded form instead.");
  }
}
