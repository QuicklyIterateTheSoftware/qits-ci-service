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
 * The branch rule of {@code maintenance-bump.yml}: a maintenance branch is always exactly one
 * commit on its base, rebuilt by every bump, and never rebuilt over somebody else's commit. Run by
 * executing the packaged step script against a local bare repository standing in for the git host,
 * from a depth-1 clone as on a runner.
 *
 * <p>Needs {@code git} and {@code node} on the host, as the step image carries them; a host
 * without them skips rather than fails. The script's {@code /tmp/} scratch paths are rewritten into
 * the test's own directory, so concurrent runs cannot share them.
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
    assumeTrue(available("node"), "node is needed to run the step");
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
  public void aRebuildIsOneCommitOnTheBase() throws Exception {
    Run run = step(payload(oldHead, true, true));
    assertEquals(0, run.exit, run.output);
    assertTrue(
        run.output.contains("rebuilding " + BRANCH + " on refs/tags/" + TAG + " (was " + oldHead + ")"),
        run.output);
    assertEquals(tagCommit, rev(branchHead() + "^"), "the branch is not one commit on the tag");
    // The old bump is gone; what main had, by way of the tag, is there.
    assertNotEquals(0, status(remote, "cat-file", "-e", branchHead() + ":bumped.txt"));
    assertEquals(0, status(remote, "cat-file", "-e", branchHead() + ":feature.txt"));
    assertEquals("160000", out(remote, "ls-tree", branchHead(), "components/sibling").split(" ")[0]);
    String message = out(remote, "log", "-1", "--format=%B", branchHead());
    assertTrue(message.startsWith("bump(libs): 2 dependencies\n\n"), message);
    assertTrue(message.contains("- maven eu.wohlben:lib 1.0.0 -> 1.1.0 (pom.xml)"), message);
    assertTrue(message.contains("- gitlink qits-sibling "), message);
    assertEquals(BOT, out(remote, "log", "-1", "--format=%ae", branchHead()));
  }

  @Test
  public void aStackOfOurOwnCommitsIsSquashedIntoOne() throws Exception {
    // The shape this replaces: one commit per bump, stacked.
    git(seed, "checkout", "-q", "--detach", oldHead);
    for (int i = 0; i < 3; i++) {
      Files.writeString(seed.resolve("bumped.txt"), "bump " + i + "\n");
      git(seed, "add", "bumped.txt");
      commitAs(seed, BOT, "bump(libs): 5 dependencies");
    }
    git(seed, "push", "-q", "-f", "origin", "HEAD:refs/heads/" + BRANCH);
    String stacked = out(seed, "rev-parse", "HEAD");

    Run run = step(payload(stacked, true, false));
    assertEquals(0, run.exit, run.output);
    assertEquals(tagCommit, rev(branchHead() + "^"));
    assertEquals("1", out(remote, "rev-list", "--count", branchHead(), "^" + tagCommit));
  }

  @Test
  public void theSubjectCountsOnlyWhatDiffersFromTheBase() throws Exception {
    String base = release("1.1.0", null, "2026.1006.70500");
    Run run = step(payload(oldHead, true, true).replace(TAG, "2026.1006.70500"));
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("eu.wohlben:lib is already 1.1.0 in pom.xml"), run.output);
    assertEquals(base, rev(branchHead() + "^"));
    String message = out(remote, "log", "-1", "--format=%B", branchHead());
    assertTrue(message.startsWith("bump(libs): 1 dependencies\n\n"), message);
    assertTrue(!message.contains("maven"), message);
  }

  @Test
  public void anUnchangedCommitIsNotPushedAgain() throws Exception {
    String payload = payload(oldHead, true, true);
    assertEquals(0, step(payload).exit);
    String first = branchHead();
    Run again = step(payload.replace(oldHead, first));
    assertEquals(0, again.exit, again.output);
    assertTrue(again.output.contains("nothing to push"), again.output);
    assertEquals(first, branchHead());
  }

  @Test
  public void aBaseThatCarriesEveryChangeResetsTheBranchToIt() throws Exception {
    String base = release("1.1.0", "FROM busybox:2.0\n", "2026.1006.70511");
    String payload =
        "{\"group\":\"libs\",\"branch\":\"" + BRANCH + "\",\"baseRef\":\"refs/tags/2026.1006.70511\""
            + ",\"replaceHead\":\"" + oldHead + "\""
            + ",\"changes\":["
            + "{\"ecosystem\":\"maven\",\"name\":\"eu.wohlben:lib\",\"from\":\"1.0.0\",\"to\":\"1.1.0\","
            + "\"manifestPath\":\"pom.xml\",\"location\":\"property:lib.version\"},"
            + "{\"ecosystem\":\"docker\",\"name\":\"busybox\",\"from\":\"1.0\",\"to\":\"2.0\","
            + "\"manifestPath\":\"Dockerfile\",\"location\":\"\"}"
            + "]}";
    Run run = step(payload);
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("reset " + BRANCH), run.output);
    assertEquals(base, branchHead());
  }

  @Test
  public void aHandWrittenCommitOnTopIsNeverRebuilt() throws Exception {
    git(seed, "checkout", "-q", "--detach", oldHead);
    commitAs(seed, "person@example.com", "fix: by hand");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/" + BRANCH);
    String hand = out(seed, "rev-parse", "HEAD");

    Run run = step(payload(hand, true, false));
    assertEquals(42, run.exit, run.output);
    assertTrue(run.output.contains("error: " + hand), run.output);
    assertEquals(hand, branchHead());
  }

  @Test
  public void aHandWrittenCommitUnderOursIsNeverRebuilt() throws Exception {
    git(seed, "checkout", "-q", "--detach", oldHead);
    commitAs(seed, "person@example.com", "fix: by hand");
    String hand = out(seed, "rev-parse", "HEAD");
    commitAs(seed, BOT, "bump(libs): 1 dependencies");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/" + BRANCH);
    String top = out(seed, "rev-parse", "HEAD");

    Run run = step(payload(top, true, false));
    assertEquals(42, run.exit, run.output);
    assertTrue(run.output.contains("error: " + hand), run.output);
    assertEquals(top, branchHead());
  }

  @Test
  public void aBranchThatMovesBeforeThePushIsRefused() throws Exception {
    // A pre-push hook in the step's clone moves the remote branch after the step decided to
    // rebuild and before its push lands: the lease on the head it read must refuse it.
    String moved = out(remote, "rev-parse", "refs/heads/main");
    String hook = "#!/bin/sh\ngit --git-dir='" + remote + "' update-ref refs/heads/" + BRANCH + " " + moved + "\n";
    Run run = step(payload(oldHead, true, false), hook);
    assertEquals(42, run.exit, run.output);
    assertTrue(run.output.contains("rebuilding " + BRANCH), run.output);
    assertTrue(run.output.contains(BRANCH + " moved to " + moved), run.output);
    assertEquals(moved, branchHead());
  }

  @Test
  public void withoutReplaceHeadOurBranchIsStillRebuilt() throws Exception {
    Run run = step(payload(null, true, false));
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("rebuilding " + BRANCH), run.output);
    assertEquals(tagCommit, rev(branchHead() + "^"));
  }

  @Test
  public void aMissingBranchIsStartedFromTheFullRef() throws Exception {
    git(remote, "update-ref", "-d", "refs/heads/" + BRANCH);
    Run run = step(payload(oldHead, true, false));
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("starting " + BRANCH + " from refs/tags/" + TAG), run.output);
    assertEquals(tagCommit, rev(branchHead() + "^"));
  }

  @Test
  public void anImplausibleReplaceHeadIsRefused() throws Exception {
    for (String bad : List.of("ABCDEF" + "0".repeat(34), "0".repeat(39), "HEAD", "0".repeat(40) + " x")) {
      Run run = step(payload(bad, true, true));
      assertNotEquals(0, run.exit, run.output);
      assertTrue(run.output.contains("refusing an implausible replaceHead"), run.output);
    }
    assertEquals(oldHead, branchHead());
  }

  @Test
  public void aRequestsOwnSourceBranchIsAppendedToNeverRebuilt() throws Exception {
    // The estate-pins automation: group `targeted`, a ticket branch somebody else owns, and a
    // `kind` in the payload. Their commit stays; ours lands on top, ff-only.
    git(seed, "checkout", "-q", "--detach", tagCommit);
    commitAs(seed, "person@example.com", "feat: the ticket's work");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/ticket/x");
    String ticket = out(seed, "rev-parse", "HEAD");
    String payload =
        payload(null, true, false)
            .replace("\"group\":\"libs\"", "\"group\":\"targeted\",\"kind\":\"estate-pins\"")
            .replace(BRANCH, "ticket/x");

    Run run = step(payload);
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("appending to ticket/x"), run.output);
    String head = out(remote, "rev-parse", "refs/heads/ticket/x");
    assertEquals(ticket, rev(head + "^"), "the ticket's commit must stay under ours");
    assertTrue(out(remote, "log", "-1", "--format=%s", head).startsWith("bump(targeted): 1 dependencies"));

    Run again = step(payload);
    assertEquals(0, again.exit, again.output);
    assertTrue(again.output.contains("nothing to commit"), again.output);
    assertEquals(head, out(remote, "rev-parse", "refs/heads/ticket/x"));
  }

  // --- the dependency-bump pre-run kind (qits-1133) --------------------------------------------

  private static final String REQUEST = "0b6f1c2e-1d2a-4c3b-9e8f-7a6b5c4d3e2f";
  private static final String RR_BRANCH = "maintenance/automations/dependency-bump/" + REQUEST;

  /** The request's fold on {@code release/<request>}: main plus a person's commit. */
  private String fold() throws Exception {
    return fold("feat(qits-1133): the person's work");
  }

  private String fold(String message) throws Exception {
    git(seed, "checkout", "-q", "--detach", tagCommit);
    commitAs(seed, "person@example.com", message);
    git(seed, "push", "-q", "-f", "origin", "HEAD:refs/heads/release/" + REQUEST);
    return out(seed, "rev-parse", "HEAD");
  }

  private String dependencyBump(String fold, String extra) {
    return payload(null, true, true)
        .replace("\"group\":\"libs\"", "\"group\":\"dependencies\",\"kind\":\"dependency-bump\""
            + ",\"requestId\":\"" + REQUEST + "\",\"foldSha\":\"" + fold + "\"" + extra)
        .replace(BRANCH, RR_BRANCH)
        .replace("refs/tags/" + TAG, "main");
  }

  @Test
  public void aDependencyBumpIsOneCommitOnMainOnTheRequestsOwnBranch() throws Exception {
    String fold = fold();
    Run run = step(dependencyBump(fold, ",\"workItem\":\"qits-1133\""));
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("starting " + RR_BRANCH + " from main"), run.output);
    String head = rev("refs/heads/" + RR_BRANCH);
    assertEquals(tagCommit, rev(head + "^"), "one commit on main, never on the fold");
    String message = out(remote, "log", "-1", "--format=%B", head);
    assertTrue(message.startsWith("bump(qits-1133): 2 dependencies\n\n"), message);
    assertEquals(BOT, out(remote, "log", "-1", "--format=%ae", head));

    // The same step again (a re-dispatch): already this one commit on main, nothing pushed.
    String next = out(remote, "rev-parse", "refs/heads/release/" + REQUEST);
    Run again = step(dependencyBump(next, ""));
    assertEquals(0, again.exit, again.output);
    assertTrue(again.output.contains("nothing to push"), again.output);
    assertEquals(head, rev("refs/heads/" + RR_BRANCH));
  }

  @Test
  public void aDependencyBumpWithoutAWorkItemSaysDependencies() throws Exception {
    Run run = step(dependencyBump(fold(), ""));
    assertEquals(0, run.exit, run.output);
    String message = out(remote, "log", "-1", "--format=%s", "refs/heads/" + RR_BRANCH);
    assertEquals("bump(dependencies): 2 dependencies", message);
  }

  @Test
  public void aDependencyBumpStackIsSquashedOnMain() throws Exception {
    String fold = fold();
    git(seed, "checkout", "-q", "--detach", tagCommit);
    for (int i = 0; i < 2; i++) {
      commitAs(seed, BOT, "bump(dependencies): 1 dependencies");
    }
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/" + RR_BRANCH);

    Run run = step(dependencyBump(fold, ""));
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("rebuilding " + RR_BRANCH + " on main"), run.output);
    String head = rev("refs/heads/" + RR_BRANCH);
    assertEquals("1", out(remote, "rev-list", "--count", head, "^" + tagCommit));
  }

  @Test
  public void aDependencyBumpOverAHandWrittenCommitIsNeverRebuilt() throws Exception {
    String fold = fold();
    git(seed, "checkout", "-q", "--detach", tagCommit);
    commitAs(seed, "person@example.com", "fix: by hand");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/" + RR_BRANCH);
    String hand = out(seed, "rev-parse", "HEAD");

    Run run = step(dependencyBump(fold, ""));
    assertEquals(42, run.exit, run.output);
    assertEquals(hand, rev("refs/heads/" + RR_BRANCH));
  }

  @Test
  public void aDependencyBumpOnAMovedFoldIsSupersededBeforeStart() throws Exception {
    String stale = fold();
    fold("feat(qits-1133): the person pushed again");
    Run run = step(dependencyBump(stale, ""));
    assertEquals(1, run.exit, run.output);
    assertTrue(run.output.contains("superseded before start"), run.output);
    assertNotEquals(0, status(remote, "rev-parse", "--verify", "-q", "refs/heads/" + RR_BRANCH));
  }

  @Test
  public void aDependencyBumpPayloadOutsideItsShapeIsRefused() throws Exception {
    String fold = fold();
    String good = dependencyBump(fold, "");
    for (String bad :
        List.of(
            good.replace(RR_BRANCH, "maintenance/automations/dependency-bump/other"),
            good.replace(RR_BRANCH, "maintenance/dependencies"),
            good.replace(REQUEST + "\",\"foldSha", "a b\",\"foldSha"),
            good.replace(fold, fold.substring(0, 12)),
            dependencyBump(fold, ",\"workItem\":\"a b\""))) {
      Run run = step(bad);
      assertEquals(1, run.exit, run.output);
      assertTrue(run.output.contains("refusing"), run.output);
    }
    assertNotEquals(0, status(remote, "rev-parse", "--verify", "-q", "refs/heads/" + RR_BRANCH));
  }

  /** A newer main, released as {@code tag}: pom.xml at {@code lib}, and a Dockerfile if given. */
  private String release(String lib, String dockerfile, String tag) throws Exception {
    git(seed, "checkout", "-q", "--detach", tagCommit);
    Files.writeString(
        seed.resolve("pom.xml"),
        "<project>\n  <properties>\n    <lib.version>" + lib + "</lib.version>\n  </properties>\n</project>\n");
    git(seed, "add", "pom.xml");
    if (dockerfile != null) {
      Files.writeString(seed.resolve("Dockerfile"), dockerfile);
      git(seed, "add", "Dockerfile");
    }
    commitAs(seed, "person@example.com", "chore: a newer main");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/main");
    git(seed, "-c", "user.name=p", "-c", "user.email=person@example.com", "tag", "-a", "-m", tag, tag);
    git(seed, "push", "-q", "origin", "refs/tags/" + tag);
    return out(seed, "rev-parse", "HEAD");
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

  private Run step(String payload) throws Exception {
    return step(payload, null);
  }

  private Run step(String payload, String prePushHook) throws Exception {
    EventTriggerFile file =
        new CiPlatformPipelines().files().stream()
            .filter(f -> f.path().endsWith("maintenance-bump.yml"))
            .findFirst()
            .orElseThrow();
    String script =
        new CiEventTriggerParser().parse(file.path(), file.content()).pipeline().steps().getFirst().script();
    Path scratch = Files.createTempDirectory(dir, "scratch");
    Path clone = Files.createTempDirectory(dir, "step");
    Files.delete(clone);
    // Depth 1, as a runner clones: an older base has to be deepened before it can be asked.
    git(dir, "clone", "-q", "--depth", "1", "file://" + remote, clone.toString());
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
