package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The branch decision of {@code maintenance-bump.yml} - continue or start, never rebuild - and the
 * group/ecosystem gate in front of it, exercised by running the packaged step script itself against
 * a local bare repository standing in for the git host.
 *
 * <p><b>There is ONE step now (qits-1133).</b> The file used to run a maven step and a node step,
 * each its own container with its own shallow clone, because the GROUP arm it also served could
 * bump a {@code pom.xml} and no image carries both toolchains. The GROUP arm - and the maven step
 * with it, and the one-commit-per-base rebuild (ownership walk, lease, {@code replaceHead}) the
 * owner later gave that arm - is retired: the only caller left, qits-maintenance's {@code
 * estate-pins} automation, sends a TARGETED bump that writes gitlinks onto a release request's own
 * source branch, a branch that already exists by the time the bump is dispatched and is simply
 * appended to, ff-only.
 *
 * <p>Needs {@code git} and {@code node} on the host, as the one step image carries them; a host
 * without them skips rather than fails. The script's {@code /tmp/} scratch paths are rewritten
 * into the test's own directory, so concurrent runs cannot share them.
 *
 * <p><b>The qits CLI is a stand-in (qits-893).</b> The pipeline declares {@code qits-cli: true}, so
 * the step script starts with the pinned CLI fetch; the step is asserted to start with exactly that
 * text and is run from just after it, with {@link #FAKE_QITS} first on {@code PATH} in place of
 * what the fetch would have put there. The fake records its arguments and the applied list it was
 * handed, and composes a message the way the real one does - a subject counting the applied lines,
 * the body, and changelog sections whose {@code # <version>} headings the commit has to keep.
 */
public class MaintenanceBumpBranchTest {

  private static final String BRANCH = "ticket/some-ticket";

  /**
   * {@code qits} for the step: {@code changelog bump-message --group <g> --applied <tsv> --body
   * <file>} records its argv, the applied list and the body under {@code $QITS_FAKE_RECORD}, and
   * prints {@code bump(<g>): <applied lines> dependencies}, the body and {@link #CHANGELOG};
   * {@code $QITS_FAKE_FAIL} makes it fail as a real CLI error would.
   */
  private static final String FAKE_QITS =
      """
      #!/bin/sh
      set -eu
      record=${QITS_FAKE_RECORD:?}
      printf '%s\\n' "$*" >> "$record/calls"
      [ "$1 $2" = "changelog bump-message" ] || { echo "fake qits: unexpected $*" >&2; exit 64; }
      shift 2
      group= applied= body=
      while [ $# -gt 0 ]; do
        case "$1" in
          --group) group=$2; shift ;;
          --applied) applied=$2; shift ;;
          --body) body=$2; shift ;;
          *) echo "fake qits: unknown option $1" >&2; exit 64 ;;
        esac
        shift
      done
      cp "$applied" "$record/applied"
      cp "$body" "$record/body"
      if [ -n "${QITS_FAKE_FAIL:-}" ]; then
        echo "fake qits: no changelog for you" >&2
        exit 3
      fi
      printf 'bump(%s): %d dependencies\\n\\n' "$group" "$(wc -l < "$applied")"
      cat "$body"
      printf '\\n# 2026.1010.1\\n\\n## Fixed\\n- a fixed thing\\n\\n#not-a-heading-either\\n'
      """;

  /** The changelog part {@link #FAKE_QITS} appends after the body. */
  private static final String CHANGELOG =
      "\n# 2026.1010.1\n\n## Fixed\n- a fixed thing\n\n#not-a-heading-either\n";

  private Path fakeBin;
  private Path record;

  @TempDir Path dir;

  private Path remote;
  private Path seed;
  private String oldHead;
  private String mainHead;

  @BeforeEach
  void anEstateWithAnExistingSourceBranch() throws Exception {
    assumeTrue(available("node"), "node is needed to run the step");
    fakeBin = Files.createDirectories(dir.resolve("fake-bin"));
    Files.writeString(fakeBin.resolve("qits"), FAKE_QITS);
    assertTrue(fakeBin.resolve("qits").toFile().setExecutable(true));
    record = Files.createDirectories(dir.resolve("fake-record"));

    remote = dir.resolve("remotes").resolve("target.git");
    Files.createDirectories(remote.getParent());
    git(dir, "init", "-q", "--bare", "--initial-branch=main", remote.toString());

    Path sibling = dir.resolve("remotes").resolve("qits-sibling");
    git(dir, "init", "-q", "--initial-branch=main", sibling.toString());
    commitAs(sibling, "person@example.com", "sibling");
    git(sibling, "tag", "v2");

    seed = dir.resolve("seed");
    git(dir, "clone", "-q", remote.toString(), seed.toString());
    Files.writeString(seed.resolve("Dockerfile"), "FROM busybox:1.0\n");
    git(seed, "add", "Dockerfile");
    commitAs(seed, "person@example.com", "init");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/main");

    // The release request's own source branch, cut from main and already carrying real work -
    // exactly what a targeted payload names, and what makes "continue" the only live case.
    Files.writeString(seed.resolve("ticket.txt"), "work in progress\n");
    git(seed, "add", "ticket.txt");
    commitAs(seed, "person@example.com", "feat: the ticket's own work");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/" + BRANCH);
    oldHead = out(seed, "rev-parse", "HEAD");

    // main moves on afterwards - irrelevant to a targeted bump, which never reads baseRef when the
    // branch already exists.
    git(seed, "checkout", "-q", "--detach", "origin/main");
    Files.writeString(seed.resolve("feature.txt"), "a person's work\n");
    git(seed, "add", "feature.txt");
    commitAs(seed, "person@example.com", "feat: something");
    git(seed, "push", "-q", "origin", "HEAD:refs/heads/main");
    mainHead = out(seed, "rev-parse", "HEAD");
  }

  @Test
  public void aTargetedBumpContinuesTheExistingBranch() throws Exception {
    Run run = step(payload("targeted", BRANCH, true));
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("continuing " + BRANCH), run.output);
    assertEquals(oldHead, rev(branchHead() + "^"), "the branch must continue from its OWN head, not main's");
    assertEquals(0, status(remote, "cat-file", "-e", branchHead() + ":ticket.txt"));
    assertTrue(lsTree(branchHead(), "components/sibling").startsWith("160000 commit"), "the gitlink was not written");
    // The changelog CLI composed the message - no printf-built subject lives here any more.
    String message = out(remote, "log", "-1", "--format=%B", branchHead());
    assertTrue(message.startsWith("bump(targeted): 2 dependencies\n\n"), message);
    assertEquals(
        "docker\tbusybox\ngitlink\tqits-sibling\n", Files.readString(record.resolve("applied")));
  }

  @Test
  public void aMissingBranchIsStartedFromBaseRef() throws Exception {
    git(remote, "update-ref", "-d", "refs/heads/" + BRANCH);
    Run run = step(payload("targeted", BRANCH, true));
    assertEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("starting " + BRANCH + " from main"), run.output);
    assertEquals(mainHead, rev(branchHead() + "^"), "a missing branch must start from baseRef");
  }

  @Test
  public void aPushIsRefusedWhenTheBranchMovedSinceItWasRead() throws Exception {
    // A pre-push hook moves the remote branch after this step decided to continue from its old
    // head and before its push lands: with no lease and no force, the plain push must be refused
    // as a non-fast-forward rather than overwriting the newer commit.
    String movedTo = mainHead;
    String hook =
        "#!/bin/sh\ngit --git-dir='" + remote + "' update-ref refs/heads/" + BRANCH + " " + movedTo + "\n";
    Run run = step(payload("targeted", BRANCH, true), hook);
    assertNotEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("continuing " + BRANCH), run.output);
    assertTrue(run.output.contains("push to " + BRANCH + " was rejected"), run.output);
    assertEquals(movedTo, branchHead());
  }

  @Test
  public void aNonTargetedGroupIsRefusedBeforeAnyBranchIsRead() throws Exception {
    Run run = step(payload("libs", BRANCH, true));
    assertNotEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("the group-branch arm"), run.output);
    assertTrue(run.output.contains("is retired"), run.output);
    assertEquals(oldHead, branchHead(), "a refused group must never touch the branch");
  }

  @Test
  public void aMavenChangeIsRefusedRatherThanSilentlyDropped() throws Exception {
    String payload =
        "{\"group\":\"targeted\",\"branch\":\"" + BRANCH + "\",\"baseRef\":\"main\",\"changes\":["
            + "{\"ecosystem\":\"maven\",\"name\":\"eu.wohlben:lib\",\"from\":\"1.0.0\",\"to\":\"1.1.0\","
            + "\"manifestPath\":\"pom.xml\",\"location\":\"property:lib.version\"}]}";
    Run run = step(payload);
    assertNotEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("refusing a maven change"), run.output);
    assertEquals(oldHead, branchHead(), "a refused maven change must never touch the branch");
  }

  @Test
  public void aChangelogCliThatFailsCommitsAndPushesNothing() throws Exception {
    Run run = step(payload("targeted", BRANCH, true), null, Map.of("QITS_FAKE_FAIL", "1"));
    assertNotEquals(0, run.exit, run.output);
    assertTrue(run.output.contains("fake qits: no changelog for you"), run.output);
    assertEquals(oldHead, branchHead(), "a message the CLI could not compose was committed anyway");
  }

  @Test
  public void anUnchangedBumpIsNotPushedAgain() throws Exception {
    String payload = payload("targeted", BRANCH, true);
    assertEquals(0, step(payload).exit);
    String first = branchHead();
    Run again = step(payload);
    assertEquals(0, again.exit, again.output);
    assertTrue(again.output.contains("nothing to commit"), again.output);
    assertEquals(first, branchHead());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private record Run(int exit, String output) {}

  private String payload(String group, String branch, boolean gitlink) {
    String changes =
        gitlink
            ? "{\"ecosystem\":\"docker\",\"name\":\"busybox\",\"from\":\"1.0\",\"to\":\"2.0\","
                + "\"manifestPath\":\"Dockerfile\",\"location\":\"\"},"
                + "{\"ecosystem\":\"gitlink\",\"name\":\"qits-sibling\",\"from\":\"" + "a".repeat(40)
                + "\",\"to\":\"v2\",\"manifestPath\":\"components/sibling\","
                + "\"location\":\"gitlink:components/sibling\"}"
            : "";
    return "{\"group\":\"" + group + "\",\"branch\":\"" + branch + "\",\"baseRef\":\"main\""
        + ",\"changes\":[" + changes + "]}";
  }

  private Run step(String payload) throws Exception {
    return step(payload, null);
  }

  private Run step(String payload, String prePushHook) throws Exception {
    return step(payload, prePushHook, Map.of());
  }

  private Run step(String payload, String prePushHook, Map<String, String> extraEnv) throws Exception {
    EventTriggerFile file =
        new CiPlatformPipelines().files().stream()
            .filter(f -> f.path().endsWith("maintenance-bump.yml"))
            .findFirst()
            .orElseThrow();
    CiPipeline.CiStepDecl declared =
        new CiEventTriggerParser().parse(file.path(), file.content()).pipeline().steps().getFirst();
    // The pinned CLI fetch qits-cli: true prepends, exactly - and then the step from just after it,
    // with the fake on PATH where the fetch would have put the real binary.
    String fetch = CiPlatformPipelines.fetch(declared.image());
    assertTrue(declared.script().startsWith(fetch), "the CLI fetch leads the step:\n" + declared.script());
    String script = declared.script().substring(fetch.length());
    Path scratch = Files.createTempDirectory(dir, "scratch");
    Path clone = Files.createTempDirectory(dir, "step");
    Files.delete(clone);
    git(dir, "clone", "-q", "--depth", "1", "file://" + remote, clone.toString());
    if (prePushHook != null) {
      Path hook = clone.resolve(".git").resolve("hooks").resolve("pre-push");
      Files.writeString(hook, prePushHook);
      assertTrue(hook.toFile().setExecutable(true));
    }
    Map<String, String> env = new HashMap<>(extraEnv);
    env.put("QITS_EVENT_PAYLOAD", payload);
    env.put("QITS_CI_REPOSITORY_URL", remote.toString());
    env.put("QITS_FAKE_RECORD", record.toString());
    env.put("PATH", fakeBin + ":" + System.getenv("PATH"));
    Process process = start(clone, env, "sh", "-c", script.replace("/tmp/", scratch + "/"));
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the step did not return");
    return new Run(process.exitValue(), output);
  }

  private String branchHead() throws Exception {
    return out(remote, "rev-parse", "refs/heads/" + BRANCH);
  }

  private String rev(String spec) throws Exception {
    return out(remote, "rev-parse", spec);
  }

  private String lsTree(String sha, String path) throws Exception {
    return out(remote, "ls-tree", sha, "--", path);
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
