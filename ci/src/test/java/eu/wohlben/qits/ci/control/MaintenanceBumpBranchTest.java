package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The branch decision of {@code maintenance-bump.yml} — continue, start or rebuild — exercised by
 * running the packaged step scripts themselves against a local bare repository standing in for the
 * git host. Each step gets its own shallow clone, as it does on a runner, and the two steps share
 * nothing but the remote, which is the whole point of the rebuild rule.
 *
 * <p>Needs {@code git}, {@code jq} and {@code node} on the host, as the two step images carry
 * them; a host without them skips rather than fails. The scripts' {@code /tmp/} scratch paths are
 * rewritten into the test's own directory, so concurrent runs cannot share them.
 */
public class MaintenanceBumpBranchTest {

  private static final String BRANCH = "maintenance/libs";
  private static final String TAG = "2026.1006.62612";
  private static final String BOT = "maintenance@qits.local";

  @TempDir Path dir;

  private Path remote;
  private Path seed;
  private String oldHead;
  private String tagCommit;

  @BeforeEach
  void anEstateWithAnOldBranchAndANewerRelease() throws Exception {
    assumeTrue(available("jq") && available("node"), "jq and node are needed to run the steps");
    remote = dir.resolve("remotes").resolve("target.git");
    Files.createDirectories(remote.getParent());
    git(dir, "init", "-q", "--bare", "--initial-branch=main", remote.toString());

    Path sibling = dir.resolve("remotes").resolve("qits-sibling");
    git(dir, "init", "-q", "--initial-branch=main", sibling.toString());
    commitAs(sibling, "person@example.com", "sibling");
    git(sibling, "tag", "v2");

    seed = dir.resolve("seed");
    git(dir, "clone", "-q", remote.toString(), seed.toString());
    Files.writeString(
        seed.resolve("pom.xml"),
        "<project>\n  <properties>\n    <lib.version>1.0.0</lib.version>\n  </properties>\n</project>\n");
    git(seed, "add", "pom.xml");
    commitAs(seed, "person@example.com", "init");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/main");

    // The old maintenance branch, cut from main and carrying one bump of ours.
    Files.writeString(seed.resolve("bumped.txt"), "old bump\n");
    git(seed, "add", "bumped.txt");
    commitAs(seed, BOT, "bump(libs): 1 dependencies");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/" + BRANCH);
    oldHead = out(seed, "rev-parse", "HEAD");

    // main moves on, and a release is cut from it: annotated, so the steps have to peel it.
    git(seed, "checkout", "-q", "--detach", "origin/main");
    Files.writeString(seed.resolve("feature.txt"), "a person's work\n");
    git(seed, "add", "feature.txt");
    commitAs(seed, "person@example.com", "feat: something");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/main");
    git(seed, "-c", "user.name=p", "-c", "user.email=person@example.com", "tag", "-a", "-m", TAG, TAG);
    git(seed, "push", "-q", "origin", "refs/tags/" + TAG);
    tagCommit = out(seed, "rev-parse", "HEAD");
  }

  @Test
  public void aRebuildIsCarriedByTheMavenStepAndContinuedByTheNodeStep() throws Exception {
    String payload = payload(oldHead, true, true);

    Run maven = step(0, payload);
    assertEquals(0, maven.exit, maven.output);
    assertTrue(
        maven.output.contains("rebuilding " + BRANCH + " on refs/tags/" + TAG + " (was " + oldHead + ")"),
        maven.output);
    String mavenCommit = branchHead();
    assertEquals(tagCommit, rev(mavenCommit + "^"), "the rebuild did not start from the tag");

    Run node = step(1, payload);
    assertEquals(0, node.exit, node.output);
    assertTrue(node.output.contains("continuing " + BRANCH), node.output);
    assertEquals(mavenCommit, rev(branchHead() + "^"), "the node step did not continue the rebuild");
    // The old bump is gone; what main had, by way of the tag, is there.
    assertNotEquals(0, status(remote, "cat-file", "-e", branchHead() + ":bumped.txt"));
    assertEquals(0, status(remote, "cat-file", "-e", branchHead() + ":feature.txt"));
  }

  @Test
  public void aRebuildWithNoMavenChangesIsCarriedByTheNodeStep() throws Exception {
    String payload = payload(oldHead, false, true);

    Run maven = step(0, payload);
    assertEquals(0, maven.exit, maven.output);
    assertTrue(maven.output.contains("no maven changes"), maven.output);
    assertEquals(oldHead, branchHead());

    Run node = step(1, payload);
    assertEquals(0, node.exit, node.output);
    assertTrue(node.output.contains("rebuilding " + BRANCH), node.output);
    assertEquals(tagCommit, rev(branchHead() + "^"));
  }

  @Test
  public void aRebuildWithNothingToCommitStillMovesTheBranch() throws Exception {
    // Cut a second, newer release whose tree already carries every pin this bump would write -
    // the maven step's edit and the node step's docker edit are both no-ops against it. Neither
    // step has anything to commit, so only the node step's "push what we started from" arm can
    // move the stale branch off replaceHead at all.
    Files.writeString(
        seed.resolve("pom.xml"),
        "<project>\n  <properties>\n    <lib.version>1.1.0</lib.version>\n  </properties>\n</project>\n");
    Files.writeString(seed.resolve("Dockerfile"), "FROM busybox:2.0\n");
    git(seed, "add", "pom.xml", "Dockerfile");
    commitAs(seed, "person@example.com", "chore: already at the target pins");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/main");
    String secondTag = "2026.1006.70511";
    git(
        seed, "-c", "user.name=p", "-c", "user.email=person@example.com", "tag", "-a", "-m", secondTag, secondTag);
    git(seed, "push", "-q", "origin", "refs/tags/" + secondTag);
    String base = out(seed, "rev-parse", "HEAD");

    String payload =
        "{\"group\":\"libs\",\"branch\":\"" + BRANCH + "\",\"baseRef\":\"refs/tags/" + secondTag + "\""
            + ",\"replaceHead\":\"" + oldHead + "\""
            + ",\"changes\":["
            + "{\"ecosystem\":\"maven\",\"name\":\"eu.wohlben:lib\",\"from\":\"1.0.0\",\"to\":\"1.1.0\","
            + "\"manifestPath\":\"pom.xml\",\"location\":\"property:lib.version\"},"
            + "{\"ecosystem\":\"docker\",\"name\":\"busybox\",\"from\":\"1.0\",\"to\":\"2.0\","
            + "\"manifestPath\":\"Dockerfile\",\"location\":\"\"}"
            + "]}";

    Run maven = step(0, payload);
    assertEquals(0, maven.exit, maven.output);
    assertTrue(maven.output.contains("rebuilding " + BRANCH), maven.output);
    assertTrue(maven.output.contains("nothing to commit"), maven.output);
    assertEquals(oldHead, branchHead(), "the maven step must never push on nothing to commit");

    Run node = step(1, payload);
    assertEquals(0, node.exit, node.output);
    assertTrue(node.output.contains("rebuilding " + BRANCH), node.output);
    assertTrue(
        node.output.contains("rebuilt " + BRANCH + " on refs/tags/" + secondTag + " with nothing to commit"),
        node.output);
    assertEquals(base, branchHead(), "a rebuild with nothing to commit must still move the branch");
  }

  @Test
  public void aMavenOnlyRebuildWithNothingToCommitStillMovesTheBranch() throws Exception {
    // A maven-only payload: the node step's own change list is empty from the start, not merely
    // a no-op after applying it. Its early "nothing for me" exit must still give way to the
    // branch decision when replaceHead says a rebuild may be owed - otherwise this bump leaves
    // the branch exactly where aRebuildWithNothingToCommitStillMovesTheBranch would have, with
    // neither step ever looking at it.
    Files.writeString(
        seed.resolve("pom.xml"),
        "<project>\n  <properties>\n    <lib.version>1.1.0</lib.version>\n  </properties>\n</project>\n");
    git(seed, "add", "pom.xml");
    commitAs(seed, "person@example.com", "chore: already at the target pin");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/main");
    String secondTag = "2026.1006.70522";
    git(
        seed, "-c", "user.name=p", "-c", "user.email=person@example.com", "tag", "-a", "-m", secondTag, secondTag);
    git(seed, "push", "-q", "origin", "refs/tags/" + secondTag);
    String base = out(seed, "rev-parse", "HEAD");

    String payload =
        "{\"group\":\"libs\",\"branch\":\"" + BRANCH + "\",\"baseRef\":\"refs/tags/" + secondTag + "\""
            + ",\"replaceHead\":\"" + oldHead + "\""
            + ",\"changes\":["
            + "{\"ecosystem\":\"maven\",\"name\":\"eu.wohlben:lib\",\"from\":\"1.0.0\",\"to\":\"1.1.0\","
            + "\"manifestPath\":\"pom.xml\",\"location\":\"property:lib.version\"}"
            + "]}";

    Run maven = step(0, payload);
    assertEquals(0, maven.exit, maven.output);
    assertTrue(maven.output.contains("rebuilding " + BRANCH), maven.output);
    assertTrue(maven.output.contains("nothing to commit"), maven.output);
    assertEquals(oldHead, branchHead(), "the maven step must never push on nothing to commit");

    Run node = step(1, payload);
    assertEquals(0, node.exit, node.output);
    assertTrue(node.output.contains("rebuilding " + BRANCH), node.output);
    assertTrue(
        node.output.contains("rebuilt " + BRANCH + " on refs/tags/" + secondTag + " with nothing to commit"),
        node.output);
    assertEquals(base, branchHead(), "a maven-only rebuild with nothing to commit must still move the branch");
  }

  @Test
  public void aMavenOnlyRebuildThatPushedARealCommitIsContinuedByTheNodeStep() throws Exception {
    // A maven-only payload whose edit is a REAL change this time (the tag's pom.xml is still at
    // 1.0.0, as the shared fixture leaves it): the maven step carries the rebuild itself, and the
    // node step - finding head != replaceHead, and nothing of its own either way - must simply
    // continue and exit clean, never touching the branch again.
    String payload = payload(oldHead, true, false);

    Run maven = step(0, payload);
    assertEquals(0, maven.exit, maven.output);
    assertTrue(
        maven.output.contains("rebuilding " + BRANCH + " on refs/tags/" + TAG + " (was " + oldHead + ")"),
        maven.output);
    String mavenCommit = branchHead();
    assertEquals(tagCommit, rev(mavenCommit + "^"), "the rebuild did not start from the tag");

    Run node = step(1, payload);
    assertEquals(0, node.exit, node.output);
    assertTrue(node.output.contains("continuing " + BRANCH), node.output);
    assertTrue(node.output.contains("nothing to commit"), node.output);
    assertEquals(mavenCommit, branchHead(), "the node step must not push again when it has nothing of its own");
  }

  @Test
  public void aRebuildIsRefusedWhenTheBranchMovesBeforeItsPush() throws Exception {
    // A pre-push hook in the step's clone moves the remote branch after the step decided to
    // rebuild and before its push lands: the lease on replaceHead must refuse the overwrite.
    String moved = out(remote, "rev-parse", "refs/heads/main");
    String hook = "#!/bin/sh\ngit --git-dir='" + remote + "' update-ref refs/heads/" + BRANCH + " " + moved + "\n";
    Run maven = step(0, payload(oldHead, true, false), hook);
    assertEquals(1, maven.exit, maven.output);
    assertTrue(maven.output.contains("rebuilding " + BRANCH), maven.output);
    assertTrue(maven.output.contains("push to " + BRANCH + " was rejected"), maven.output);
    assertEquals(moved, branchHead());
  }

  @Test
  public void aBranchThatMovedSinceItWasReadIsContinuedNotRebuilt() throws Exception {
    Run maven = step(0, payload("0".repeat(40), true, false));
    assertEquals(0, maven.exit, maven.output);
    assertTrue(maven.output.contains("continuing " + BRANCH), maven.output);
    assertEquals(oldHead, rev(branchHead() + "^"));
  }

  @Test
  public void withoutReplaceHeadAnExistingBranchIsContinuedAsBefore() throws Exception {
    Run maven = step(0, payload(null, true, false));
    assertEquals(0, maven.exit, maven.output);
    assertTrue(maven.output.contains("continuing " + BRANCH), maven.output);
    assertEquals(oldHead, rev(branchHead() + "^"));
  }

  @Test
  public void aMissingBranchIsStartedFromTheFullRef() throws Exception {
    git(remote, "update-ref", "-d", "refs/heads/" + BRANCH);
    Run maven = step(0, payload(oldHead, true, false));
    assertEquals(0, maven.exit, maven.output);
    assertTrue(maven.output.contains("starting " + BRANCH + " from refs/tags/" + TAG), maven.output);
    assertEquals(tagCommit, rev(branchHead() + "^"));
  }

  @Test
  public void anImplausibleReplaceHeadIsRefusedByBothSteps() throws Exception {
    for (String bad : List.of("ABCDEF" + "0".repeat(34), "0".repeat(39), "HEAD", "0".repeat(40) + " x")) {
      String payload = payload(bad, true, true);
      for (int step = 0; step < 2; step++) {
        Run run = step(step, payload);
        assertNotEquals(0, run.exit, run.output);
        assertTrue(run.output.contains("refusing an implausible replaceHead"), run.output);
      }
    }
    assertEquals(oldHead, branchHead());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private record Run(int exit, String output) {}

  private String payload(String replaceHead, boolean maven, boolean gitlink) {
    List<String> changes = new ArrayList<>();
    if (maven) {
      changes.add(
          "{\"ecosystem\":\"maven\",\"name\":\"eu.wohlben:lib\",\"from\":\"1.0.0\",\"to\":\"1.1.0\","
              + "\"manifestPath\":\"pom.xml\",\"location\":\"property:lib.version\"}");
    }
    if (gitlink) {
      changes.add(
          "{\"ecosystem\":\"gitlink\",\"name\":\"qits-sibling\",\"from\":\"" + "a".repeat(40) + "\",\"to\":\"v2\","
              + "\"manifestPath\":\"components/sibling\",\"location\":\"gitlink:components/sibling\"}");
    }
    return "{\"group\":\"libs\",\"branch\":\"" + BRANCH + "\",\"baseRef\":\"refs/tags/" + TAG + "\""
        + (replaceHead == null ? "" : ",\"replaceHead\":\"" + replaceHead + "\"")
        + ",\"changes\":[" + String.join(",", changes) + "]}";
  }

  private Run step(int index, String payload) throws Exception {
    return step(index, payload, null);
  }

  private Run step(int index, String payload, String prePushHook) throws Exception {
    EventTriggerFile file =
        new CiPlatformPipelines().files().stream()
            .filter(f -> f.path().endsWith("maintenance-bump.yml"))
            .findFirst()
            .orElseThrow();
    String script =
        new CiEventTriggerParser().parse(file.path(), file.content()).pipeline().steps().get(index).script();
    Path scratch = Files.createTempDirectory(dir, "scratch");
    Path clone = Files.createTempDirectory(dir, "step");
    Files.delete(clone);
    git(dir, "clone", "-q", "--depth", "50", "file://" + remote, clone.toString());
    if (prePushHook != null) {
      Path hook = clone.resolve(".git").resolve("hooks").resolve("pre-push");
      Files.writeString(hook, prePushHook);
      assertTrue(hook.toFile().setExecutable(true));
    }
    Process process =
        start(clone, Map.of("QITS_EVENT_PAYLOAD", payload, "QITS_CI_REPOSITORY_URL", remote.toString()),
            "sh", "-c", script.replace("/tmp/", scratch + "/"));
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "a step did not return");
    return new Run(process.exitValue(), output);
  }

  private String branchHead() throws Exception {
    return out(remote, "rev-parse", "refs/heads/" + BRANCH);
  }

  private String rev(String spec) throws Exception {
    return out(remote, "rev-parse", spec);
  }

  private void commitAs(Path repo, String email, String message) throws Exception {
    git(repo, "-c", "user.name=t", "-c", "user.email=" + email, "commit", "-q", "--allow-empty", "-m", message);
  }

  private void git(Path at, String... args) throws Exception {
    assertEquals(0, status(at, args), "git " + String.join(" ", args));
  }

  private String out(Path at, String... args) throws Exception {
    Process process = start(at, Map.of(), prepend("git", args));
    String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    return text;
  }

  private int status(Path at, String... args) throws Exception {
    Process process = start(at, Map.of(), prepend("git", args));
    process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    return process.exitValue();
  }

  private static String[] prepend(String first, String... rest) {
    String[] all = new String[rest.length + 1];
    all[0] = first;
    System.arraycopy(rest, 0, all, 1, rest.length);
    return all;
  }

  /** Every process is isolated from the host's git configuration, credential helpers included. */
  private Process start(Path at, Map<String, String> env, String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command).directory(at.toFile()).redirectErrorStream(true);
    builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().put("HOME", dir.toString());
    builder.environment().putAll(env);
    return builder.start();
  }

  private static boolean available(String tool) {
    try {
      Process process = new ProcessBuilder("sh", "-c", "command -v " + tool).start();
      process.getInputStream().readAllBytes();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
