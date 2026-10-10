package eu.wohlben.qits.ci.control;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Composes a release-request automation's <b>kind file</b> into the platform trigger that runs it.
 *
 * <p>qits-maintenance runs one automation per kind on every fold of a release request — the
 * screenshot baselines today, more regenerations after it — and every one of them has the same
 * frame: read a fold, regenerate something, commit only what the kind owns, push one maintenance
 * branch. The frame is platform process, so it is written here, once, in Java; a kind contributes
 * only what differs. A kind file, {@code ci/src/main/resources/platform-pipelines/automations/
 * <kind>.yml}, declares exactly:
 *
 * <ul>
 *   <li>{@code image} — the step's image;
 *   <li>{@code timeout-seconds} — the step's deadline;
 *   <li>{@code script} — the regeneration, with no ref handling and no commit code;
 *   <li>{@code qits-cli} — optional, and only ever {@code true}: the pinned qits CLI is fetched
 *       and put on {@code PATH} for the script. The text is {@link CiReleaseComposer#cliFetch}'s —
 *       the one download the release prelude and the QA report hook spend too — in its {@link
 *       CiReleaseComposer.CliFetch#AUTOMATION} form, which is HARD: the kind cannot run without the
 *       CLI, so no package, no version, no curl/wget or a failed download ends the step 1 with
 *       {@code the qits CLI could not be fetched}. A kind without the key is composed exactly as
 *       if the key did not exist, and pays nothing.
 *   <li>{@code commit-type} — optional, a lowercase word ({@code [a-z]+}): the kind writes its
 *       OWN commit message, under that conventional-commit type. See below; a kind without the key
 *       is composed exactly as if the key did not exist.
 * </ul>
 *
 * <p>Anything else is a {@link CiConfigException} naming the file, and so a boot error: a kind file
 * is packaged ({@link CiPlatformPipelines}), and a key nobody reads is a decision somebody thought
 * they had made.
 *
 * <h2>The composed trigger</h2>
 *
 * <p>{@code event: ReleaseRequestAutomation}, {@code when: [{kind: {exact: <kind>}}]} — the kind is
 * the file's name, never declared inside it — and one step: <b>prelude + the kind's script as DATA +
 * postlude</b>.
 *
 * <p><b>No {@code checkout:}, on purpose.</b> The step fetches the fold itself, so the run is
 * recorded at the target's {@code main} head. A run recorded at the fold sha would publish a {@code
 * BuildSuccessful} or {@code BuildFailed} that qits-projects' CI gate reads as the fold's own
 * verdict.
 *
 * <p>The <b>prelude</b> refuses every payload value rather than quoting it, in the style of {@code
 * maintenance-bump.yml}: {@code kind} is {@code [a-z0-9-]+} and this file's; {@code branch} passes
 * the ref charset and sits under {@code maintenance/automations/<kind>/}; {@code baseRef} is under
 * {@code release/}; {@code foldSha} is 40 or 64 lowercase hex; {@code workItem}, when sent, is
 * {@code [A-Za-z0-9-]+}; {@code commitPaths} is a non-empty array of plain relative paths, each
 * optionally {@code :(glob)}-prefixed, with no {@code ..} and no leading {@code /}. It then fetches
 * {@code refs/heads/<baseRef>} and refuses with {@code superseded before start} unless the fold is
 * still {@code foldSha} — a newer fold has its own automation coming — checks it out, makes {@code
 * HOME} writable, and settles the commit subject's work item (the payload's, else the newest one the
 * fold's subjects name).
 *
 * <p>The <b>kind's script</b> is written through a quoted heredoc and run as a child shell, for the
 * reasons {@link CiReleaseComposer} gives: nothing in it is expanded on the way in, it cannot unset
 * the wrapper's {@code -eu}, an {@code exit 0} ends the script rather than the step — and, here, it
 * cannot reassign {@code branch} or the staged paths the postlude spends.
 *
 * <p>The <b>postlude</b> stages <b>only</b> the payload's {@code commitPaths} ({@code git add -A
 * --}), commits {@code chore(<item>): update <kind words>} (or the kind's own message, below)
 * unless the {@code
 * --ignore-submodules=none} guard finds nothing staged — then it prints {@code unchanged} and exits
 * 0 — and makes a plain push, never forced, to {@code HEAD:refs/heads/<branch>}. A rejected push
 * fetches the branch: a tip whose one parent is the fold and whose tree is this step's is the same
 * step executed again after its first execution pushed (a runner re-dispatch), so it prints {@code
 * already pushed: <branch> at <sha> carries the same content} and is green, the branch having moved
 * as a fresh push would have moved it. Any other rejection means the fold no longer contains the
 * branch, and the step fails saying so. It ends {@code pushed <sha> to <branch>}.
 *
 * <h2>A kind that says what it changed: {@code commit-type}</h2>
 *
 * <p>{@code update <kind words>} is a true subject for a regeneration — the diagram, the baselines —
 * because what it changed is the kind. It is not one for a kind whose payload decides what moves:
 * the {@code dependency-bump} kind (qits-1133) applies N pin moves and its commit has always read
 * {@code bump(<item>): N dependencies}, the subject qits-maintenance and every reader of a
 * repository's log already know. A fixed template cannot count, so such a kind declares {@code
 * commit-type: bump} and writes the rest itself: the step exports {@value #MESSAGE_ENV} naming
 * {@value #MESSAGE_FILE}, the script writes the DESCRIPTION on its first line and, optionally, a body
 * after it, and the postlude commits {@code <type>(<item>): <description>} — {@code <type>:
 * <description>} with no item — with that body below a blank line. <b>The type and the scope stay
 * the platform's</b>: the type is the kind file's, the item is the prelude's, and a script can no
 * more pick either than it can pick the branch. A changed tree with no description is a failed
 * step naming the file, never a commit with an empty subject; the file is only read once the guard
 * has found something staged, so a run with nothing to commit needs no message at all.
 *
 * <p><b>The tickets a commit carries head its subject</b> (qits-893). A dependency bump pulls in
 * internal releases, and their changelogs name the tickets that shipped in them: the bump commit
 * should be found by those tickets the way the work itself is. So the step also exports {@value
 * #TICKETS_ENV} naming {@value #TICKETS_FILE}, and a script that knows the tickets — {@code
 * dependency-bump} reads them off {@code qits changelog bump-message} — writes them there, separated
 * by single spaces. The postlude then commits {@code chore(<id>, <id>): <type>(<item>):
 * <description>} ({@code chore(<ids>): <type>: <description>} with no item): the tickets head the
 * subject, and <b>the type and the item stay the platform's</b>, untouched behind them. The file is
 * optional — absent or empty is the plain subject — but what it says is refused rather than
 * quoted: anything but work items ({@code [A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18}}) separated by
 * single spaces fails the step with a sentence naming the file. The commit is {@code
 * --cleanup=verbatim}, because a changelog body carries {@code # <version>} headings that git's
 * default cleanup would strip as comments.
 *
 * <p>Every line of the mechanism is emitted only for a kind declaring the key, which is what keeps
 * every other kind's composed text byte-identical ({@code
 * composed/automation-screenshot-baselines.yml} holds that).
 *
 * <p><b>The payload is read with {@code jq}</b>, so a kind's image must carry it; one without it is
 * refused in the prelude with a sentence naming the image. Every platform image a kind runs on
 * today ({@code node-browser-base}, {@code maven-base}) does.
 *
 * <p>A pure function: inputs in, one string out, the same string every time.
 */
public final class CiAutomationComposer {

  /** The event qits-maintenance sends to run one automation kind on one release request's fold. */
  public static final String EVENT = "ReleaseRequestAutomation";

  /** The payload field the composed {@code when:} selects on, and the prelude re-checks. */
  public static final String KIND_FIELD = "kind";

  /** What a kind — and so a kind file's name — may spell. */
  public static final Pattern KIND = Pattern.compile("[a-z0-9-]+");

  static final String IMAGE_KEY = "image";

  static final String TIMEOUT_KEY = "timeout-seconds";

  static final String SCRIPT_KEY = "script";

  static final String QITS_CLI_KEY = "qits-cli";

  static final String COMMIT_TYPE_KEY = "commit-type";

  /** The whole kind-file vocabulary. Anything else is an error naming the file. */
  static final Set<String> KEYS =
      Set.of(IMAGE_KEY, TIMEOUT_KEY, SCRIPT_KEY, QITS_CLI_KEY, COMMIT_TYPE_KEY);

  /** What a {@code commit-type} may spell: a conventional-commit type, nothing more. */
  static final Pattern COMMIT_TYPE = Pattern.compile("[a-z]+");

  /** Where a {@code commit-type} kind's script writes its commit description and body. */
  static final String MESSAGE_FILE = "/tmp/qits-automation-message";

  /** The variable naming {@link #MESSAGE_FILE} to a {@code commit-type} kind's script. */
  static final String MESSAGE_ENV = "QITS_AUTOMATION_MESSAGE";

  /** Where a {@code commit-type} kind's script may write the tickets heading its subject. */
  static final String TICKETS_FILE = "/tmp/qits-automation-tickets";

  /** The variable naming {@link #TICKETS_FILE} to a {@code commit-type} kind's script. */
  static final String TICKETS_ENV = "QITS_AUTOMATION_TICKETS";

  /** What {@link #TICKETS_FILE} may hold: work items, separated by single spaces. */
  static final String TICKETS_ERE =
      "[A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18}( [A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18})*";

  /** Where the postlude composes a {@code commit-type} kind's whole message for {@code -F}. */
  static final String COMMIT_MESSAGE = "/tmp/qits-automation-commit";

  /** Where the composed step writes the kind's script before running it. */
  static final String KIND_SCRIPT = "/tmp/qits-automation.sh";

  /** The quoted heredoc delimiter. A script containing it as a line is refused. */
  static final String HEREDOC_DELIMITER = "QITS_AUTOMATION_EOF";

  /** Where the prelude writes the validated {@code commitPaths}, one per line, for the postlude. */
  static final String COMMIT_PATHS = "/tmp/qits-automation-paths";

  private CiAutomationComposer() {}

  /**
   * Composes one kind file.
   *
   * @param kind the kind the file is packaged as
   * @param configPath the file's path, which must be {@code .../<kind>.yml}; errors name it
   * @param content the kind file's text
   * @return the trigger document
   * @throws CiConfigException naming {@code configPath} when the kind is implausible or not the
   *     file's name, or the file declares an unknown key or an unusable value
   */
  public static String compose(String kind, String configPath, String content) {
    if (kind == null || !KIND.matcher(kind).matches()) {
      throw new CiConfigException(
          configPath + ": '" + kind + "' is not a kind — a kind file's name is [a-z0-9-]+.yml");
    }
    String fileName = configPath.substring(configPath.lastIndexOf('/') + 1);
    if (!fileName.equals(kind + ".yml")) {
      throw new CiConfigException(
          configPath
              + ": is composed as kind '"
              + kind
              + "', but a kind is its file's name — rename the file "
              + kind
              + ".yml");
    }
    Map<?, ?> root;
    try {
      root = CiConfigSchema.load(content, true);
    } catch (CiConfigException e) {
      throw new CiConfigException(configPath + ": " + e.getMessage(), e);
    }
    if (root == null) {
      throw new CiConfigException(configPath + ": an empty kind file declares no automation");
    }
    Set<String> unknown = new TreeSet<>();
    for (Object key : root.keySet()) {
      if (!(key instanceof String name) || !KEYS.contains(name)) {
        unknown.add(String.valueOf(key));
      }
    }
    if (!unknown.isEmpty()) {
      throw new CiConfigException(
          configPath
              + ": unknown key(s) "
              + unknown
              + " — a kind file declares only "
              + new TreeSet<>(KEYS)
              + "; the event, selection, refs and commit are composed by qits-ci");
    }
    String image = requireText(root, IMAGE_KEY, configPath);
    int timeout = requireTimeout(root, configPath);
    String script = requireText(root, SCRIPT_KEY, configPath);
    boolean qitsCli = optionalTrue(root, QITS_CLI_KEY, configPath);
    String commitType = optionalCommitType(root, configPath);
    for (String line : script.split("\n", -1)) {
      if (line.strip().equals(HEREDOC_DELIMITER)) {
        throw new CiConfigException(
            configPath
                + ": the script contains the line "
                + HEREDOC_DELIMITER
                + ", which would end the heredoc it is written through");
      }
    }

    StringBuilder out = new StringBuilder();
    out.append("# Composed by qits-ci from ").append(configPath).append(".\n");
    out.append("# Generated: this text is never committed. Edit the kind file.\n");
    out.append("event: ").append(EVENT).append('\n');
    out.append("when:\n");
    out.append("  - ").append(KIND_FIELD).append(": { exact: ").append(scalar(kind)).append(" }\n");
    out.append("steps:\n");
    out.append("  - image: ").append(scalar(image)).append('\n');
    out.append("    timeout-seconds: ").append(timeout).append('\n');
    out.append("    script: |\n");
    block(out, step(kind, image, script, qitsCli, commitType), "      ");
    return out.toString();
  }

  /** The one step of a kind that declares no {@code commit-type}. */
  static String step(String kind, String image, String script, boolean qitsCli) {
    return step(kind, image, script, qitsCli, null);
  }

  /**
   * The one step: prelude, the kind's script as data, postlude.
   *
   * @param commitType the kind file's {@code commit-type}, or null for {@code chore(<item>): update
   *     <kind words>}
   */
  static String step(
      String kind, String image, String script, boolean qitsCli, String commitType) {
    String words = kind.replace('-', ' ');
    StringBuilder out = new StringBuilder();
    out.append("set -eu\n");
    out.append("# --- platform prelude: the ").append(kind).append(" automation ---\n");
    out.append(
        ": \"${QITS_DOMAIN:?this step was told no QITS_DOMAIN, so it has no platform address}\"\n");
    out.append("if ! command -v jq > /dev/null 2>&1; then\n");
    out.append("  echo ")
        .append(
            shellQuote(
                "qits-ci: the image for this step ("
                    + image
                    + ") has no jq, so the automation payload cannot be read"))
        .append(" >&2\n");
    out.append("  exit 1\n");
    out.append("fi\n");
    // Every value is read as a string or not at all: a number or an object is refused by jq here,
    // and an absent field is "" and refused by the charset below - except workItem, where absent
    // is allowed.
    out.append("field() {\n");
    out.append(
        "  printf '%s' \"$QITS_EVENT_PAYLOAD\" | jq -r --arg f \"$1\" '.[$f] // \"\" | if type =="
            + " \"string\" then . else error(\"refusing a payload whose \\($f) is not a string\")"
            + " end'\n");
    out.append("}\n");
    out.append("expected_kind=").append(quote(kind)).append('\n');
    out.append("kind=$(field kind) || exit 1\n");
    out.append("branch=$(field branch) || exit 1\n");
    out.append("base=$(field baseRef) || exit 1\n");
    out.append("fold=$(field foldSha) || exit 1\n");
    out.append("item=$(field workItem) || exit 1\n");
    // Refused rather than quoted: every value below reaches an argv, a ref or a commit subject.
    out.append(
        "case \"$kind\" in ''|*[!a-z0-9-]*) echo \"refusing an implausible kind: $kind\" >&2;"
            + " exit 1 ;; esac\n");
    out.append("if [ \"$kind\" != \"$expected_kind\" ]; then\n");
    out.append(
        "  echo \"refusing a $kind payload: this step is composed from the $expected_kind kind"
            + " file\" >&2\n");
    out.append("  exit 1\n");
    out.append("fi\n");
    out.append("for ref in \"$branch\" \"$base\"; do\n");
    out.append("  case \"$ref\" in\n");
    out.append(
        "    ''|-*|*..*|*[!0-9A-Za-z._/-]*) echo \"refusing an implausible ref: $ref\" >&2;"
            + " exit 1 ;;\n");
    out.append("  esac\n");
    out.append("done\n");
    out.append("case \"$branch\" in\n");
    out.append("  \"maintenance/automations/$kind/\"?*) ;;\n");
    out.append(
        "  *) echo \"refusing an automation branch outside maintenance/automations/$kind/:"
            + " $branch\" >&2; exit 1 ;;\n");
    out.append("esac\n");
    out.append("case \"$base\" in\n");
    out.append("  release/?*) ;;\n");
    out.append(
        "  *) echo \"refusing a base that is not a release request's fold: $base\" >&2;"
            + " exit 1 ;;\n");
    out.append("esac\n");
    // A commit name and nothing else: sha-1 or sha-256, lowercase, as rev-parse prints it.
    out.append("case \"$fold\" in\n");
    out.append(
        "  ''|*[!0-9a-f]*) echo \"refusing an implausible foldSha: $fold\" >&2; exit 1 ;;\n");
    out.append("esac\n");
    out.append("if [ ${#fold} -ne 40 ] && [ ${#fold} -ne 64 ]; then\n");
    out.append("  echo \"refusing an implausible foldSha: $fold\" >&2\n");
    out.append("  exit 1\n");
    out.append("fi\n");
    out.append(
        "case \"$item\" in *[!0-9A-Za-z-]*) echo \"refusing an implausible work item: $item\""
            + " >&2; exit 1 ;; esac\n");
    // The paths this run may commit, one per line for the postlude. A string carrying a newline
    // would become two lines, so it is refused with the rest.
    out.append("printf '%s' \"$QITS_EVENT_PAYLOAD\" | jq -r '\n");
    out.append("  .commitPaths\n");
    out.append(
        "  | if type == \"array\" and length > 0"
            + " and all(.[]; type == \"string\" and (contains(\"\\n\") | not))\n");
    out.append("    then .[]\n");
    out.append(
        "    else error(\"refusing a payload whose commitPaths is not a non-empty array of"
            + " paths\") end' > ")
        .append(COMMIT_PATHS)
        .append(" || exit 1\n");
    out.append("while IFS= read -r path; do\n");
    out.append("  rest=${path#':(glob)'}\n");
    out.append("  case \"$rest\" in\n");
    out.append(
        "    ''|/*|-*|*..*|*[!0-9A-Za-z._/*-]*) echo \"refusing an implausible commit path:"
            + " $path\" >&2; exit 1 ;;\n");
    out.append("  esac\n");
    out.append("done < ").append(COMMIT_PATHS).append('\n');
    // The fold, and only the fold this run was sent for: a newer one has its own automation coming,
    // and a commit built on an older one would be pushed onto a branch the fold no longer is.
    out.append("git fetch -q \"$QITS_CI_REPOSITORY_URL\" \"refs/heads/$base\"\n");
    out.append("head=$(git rev-parse --verify 'FETCH_HEAD^{commit}')\n");
    out.append("if [ \"$head\" != \"$fold\" ]; then\n");
    out.append(
        "  echo \"superseded before start: $base is at $head, not at the fold $fold this run was"
            + " sent for\" >&2\n");
    out.append("  exit 1\n");
    out.append("fi\n");
    out.append("git checkout -q --detach FETCH_HEAD\n");
    // npm, a renderer, a generator: every regeneration runs unattended as any user and needs a
    // writable HOME. Exported, so the kind's script inherits it.
    out.append("[ -n \"${HOME:-}\" ] && [ -w \"$HOME\" ] || export HOME=/tmp\n");
    // The scope of the commit subject: the caller's work item, else the newest one the fold's own
    // commits name — the FIRST of a multi-id scope (`chore(qits-1, qits-2): x`), which used to match
    // nothing at all and fall through to an older single-id subject.
    out.append("if [ -z \"$item\" ]; then\n");
    out.append("  item=$(git log -n 200 --format=%s 2>/dev/null \\\n");
    out.append(
        "    | sed -nE 's/^[A-Za-z][A-Za-z0-9_\\/.-]*\\(([A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18})"
            + "(, *[A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18})*\\)!?: .*/\\1/p' \\\n");
    out.append("    | head -n 1)\n");
    out.append("fi\n");
    if (qitsCli) {
      out.append("# --- the qits CLI, pinned, on PATH: this kind declares qits-cli: true ---\n");
      CiReleaseComposer.cliFetch(out, image, CiReleaseComposer.CliFetch.AUTOMATION);
    }
    if (commitType != null) {
      // The kind writes its own description; a stale file from nobody is never read as one.
      out.append("rm -f ").append(MESSAGE_FILE).append('\n');
      out.append("export ").append(MESSAGE_ENV).append('=').append(MESSAGE_FILE).append('\n');
      out.append("rm -f ").append(TICKETS_FILE).append('\n');
      out.append("export ").append(TICKETS_ENV).append('=').append(TICKETS_FILE).append('\n');
    }
    out.append("# --- the kind's script, run as data ---\n");
    out.append("cat > ").append(KIND_SCRIPT).append(" <<'").append(HEREDOC_DELIMITER).append("'\n");
    out.append(script.endsWith("\n") ? script : script + "\n");
    out.append(HEREDOC_DELIMITER).append('\n');
    out.append("if command -v bash > /dev/null 2>&1; then\n");
    out.append("  bash -eu ").append(KIND_SCRIPT).append('\n');
    out.append("else\n");
    out.append("  sh -eu ").append(KIND_SCRIPT).append('\n');
    out.append("fi\n");
    out.append("# --- platform postlude: commit only the payload's paths, push plainly ---\n");
    // A path that matches nothing - no file in the tree, none in the index - would make `git add`
    // fail the whole step, so it is left out: there is nothing under it to commit. An ignored,
    // untracked file is left out the same way rather than refused.
    out.append("set --\n");
    out.append("while IFS= read -r path; do\n");
    out.append(
        "  if [ -n \"$(git ls-files --cached --others --exclude-standard -- \"$path\" | head -n"
            + " 1)\" ]; then\n");
    out.append("    set -- \"$@\" \"$path\"\n");
    out.append("  fi\n");
    out.append("done < ").append(COMMIT_PATHS).append('\n');
    out.append("if [ $# -gt 0 ]; then\n");
    out.append("  git add -A -- \"$@\"\n");
    out.append("fi\n");
    // --ignore-submodules=none for maintenance-bump.yml's reason: a repository whose .gitmodules
    // says `ignore = all` hides a staged gitlink otherwise.
    out.append("if git diff --cached --quiet --ignore-submodules=none; then\n");
    out.append("  echo \"unchanged: the ")
        .append(kind)
        .append(" regeneration changed nothing under the commit paths\"\n");
    out.append("  exit 0\n");
    out.append("fi\n");
    out.append("git diff --cached --stat --ignore-submodules=none\n");
    if (commitType == null) {
      out.append("subject=").append(quote("update " + words)).append('\n');
      out.append("if [ -n \"$item\" ]; then\n");
      out.append("  subject=\"chore($item): $subject\"\n");
      out.append("else\n");
      out.append("  subject=\"chore: $subject\"\n");
      out.append("fi\n");
      out.append(
          "git -c user.name=\"qits maintenance\" -c user.email=\"maintenance@qits.local\" \\\n");
      out.append("    commit -q -m \"$subject\"\n");
    } else {
      // The description is the script's first line, the body the rest; the type is the kind
      // file's and the scope the prelude's. Read only now: a run with nothing staged owes none.
      out.append("description=$(head -n 1 ").append(MESSAGE_FILE).append(" 2>/dev/null || true)\n");
      out.append("if [ -z \"$description\" ]; then\n");
      out.append("  echo ")
          .append(
              shellQuote(
                  "the "
                      + kind
                      + " script changed the tree but wrote no commit description to "
                      + MESSAGE_FILE))
          .append(" >&2\n");
      out.append("  exit 1\n");
      out.append("fi\n");
      out.append("if [ -n \"$item\" ]; then\n");
      out.append("  subject=\"").append(commitType).append("($item): $description\"\n");
      out.append("else\n");
      out.append("  subject=\"").append(commitType).append(": $description\"\n");
      out.append("fi\n");
      // The tickets head the subject; the type and the item behind them stay the platform's. One
      // line of work items and single spaces, or the step fails: they reach the commit subject.
      out.append("tickets=$(cat ").append(TICKETS_FILE).append(" 2>/dev/null || true)\n");
      out.append("if [ -n \"$tickets\" ]; then\n");
      out.append("  newline=$(printf '\\nx')\n");
      out.append("  newline=${newline%x}\n");
      out.append("  case \"$tickets\" in\n");
      out.append("    *\"$newline\"*) tickets_ok=no ;;\n");
      out.append("    *) if printf '%s\\n' \"$tickets\" | grep -Eqx '")
          .append(TICKETS_ERE)
          .append("'; then tickets_ok=yes; else tickets_ok=no; fi ;;\n");
      out.append("  esac\n");
      out.append("  if [ \"$tickets_ok\" != yes ]; then\n");
      out.append("    echo ")
          .append(
              shellQuote(
                  "the "
                      + kind
                      + " script wrote tickets to "
                      + TICKETS_FILE
                      + " that are not work items separated by single spaces"))
          .append(" >&2\n");
      out.append("    exit 1\n");
      out.append("  fi\n");
      out.append("  subject=\"chore($(printf '%s' \"$tickets\" | sed 's/ /, /g')): $subject\"\n");
      out.append("fi\n");
      out.append("body=$(tail -n +2 ").append(MESSAGE_FILE).append(")\n");
      out.append("{\n");
      out.append("  printf '%s\\n' \"$subject\"\n");
      out.append("  if [ -n \"$body\" ]; then printf '\\n%s\\n' \"$body\"; fi\n");
      out.append("} > ").append(COMMIT_MESSAGE).append('\n');
      out.append(
          "git -c user.name=\"qits maintenance\" -c user.email=\"maintenance@qits.local\" \\\n");
      out.append("    commit -q --cleanup=verbatim -F ").append(COMMIT_MESSAGE).append('\n');
    }
    // Plain, never forced. After the first join the fold contains the branch, so a later run on a
    // newer fold is a fast-forward of it.
    out.append("if ! git push \"$QITS_CI_REPOSITORY_URL\" \"HEAD:refs/heads/$branch\"; then\n");
    // A step executed again after its first execution pushed - a runner re-dispatch - commits the
    // same content under another sha, and that push is rejected. The branch's tip with the fold as
    // its one parent and our tree is exactly what this step produced: already pushed, and green -
    // the branch moved, which is what qits-maintenance reads as COMMITTED.
    out.append("  remote=\n");
    out.append(
        "  if git fetch -q \"$QITS_CI_REPOSITORY_URL\" \"refs/heads/$branch\" 2>/dev/null; then\n");
    out.append("    remote=$(git rev-parse --verify -q 'FETCH_HEAD^{commit}' || true)\n");
    out.append("  fi\n");
    out.append("  if [ -n \"$remote\" ] \\\n");
    out.append("      && [ \"$(git rev-list --parents -n 1 \"$remote\")\" = \"$remote $fold\" ] \\\n");
    out.append(
        "      && [ \"$(git rev-parse \"$remote^{tree}\")\" = \"$(git rev-parse 'HEAD^{tree}')\" ];"
            + " then\n");
    out.append("    echo \"already pushed: $branch at $remote carries the same content\"\n");
    out.append("    echo \"pushed $remote to $branch\"\n");
    out.append("    exit 0\n");
    out.append("  fi\n");
    out.append(
        "  echo \"push to $branch was rejected - the fold no longer contains it; remove the branch"
            + " or join it again\" >&2\n");
    out.append("  exit 1\n");
    out.append("fi\n");
    out.append("echo \"pushed $(git rev-parse HEAD) to $branch\"\n");
    return out.toString();
  }

  private static String requireText(Map<?, ?> root, String key, String configPath) {
    Object value = root.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new CiConfigException(
          configPath + ": '" + key + "' is required and must be a non-empty string");
    }
    return text;
  }

  private static int requireTimeout(Map<?, ?> root, String configPath) {
    Object value = root.get(TIMEOUT_KEY);
    if (!(value instanceof Integer seconds) || seconds <= 0) {
      throw new CiConfigException(
          configPath + ": '" + TIMEOUT_KEY + "' is required and must be a positive integer");
    }
    return seconds;
  }

  /**
   * A key that is either absent or {@code true}. Any other value — {@code false} included — is a
   * boot error naming the file: {@code false} would be a key nobody reads, and a string such as
   * {@code "true"} a decision the composer cannot tell apart from a typo.
   */
  private static boolean optionalTrue(Map<?, ?> root, String key, String configPath) {
    if (!root.containsKey(key)) {
      return false;
    }
    if (!Boolean.TRUE.equals(root.get(key))) {
      throw new CiConfigException(
          configPath
              + ": '"
              + key
              + "' may only be true (leave it out for a kind that needs no qits CLI), not '"
              + root.get(key)
              + "'");
    }
    return true;
  }

  /**
   * The {@code commit-type}, or null when the key is absent. A value that is not a lowercase word is
   * a boot error naming the file: it reaches a commit subject and the composed shell text both.
   */
  private static String optionalCommitType(Map<?, ?> root, String configPath) {
    if (!root.containsKey(COMMIT_TYPE_KEY)) {
      return null;
    }
    Object value = root.get(COMMIT_TYPE_KEY);
    if (!(value instanceof String type) || !COMMIT_TYPE.matcher(type).matches()) {
      throw new CiConfigException(
          configPath
              + ": '"
              + COMMIT_TYPE_KEY
              + "' must be a conventional-commit type, [a-z]+ (leave it out for 'chore(<item>):"
              + " update <kind words>'), not '"
              + value
              + "'");
    }
    return type;
  }

  /** A YAML single-quoted scalar. */
  private static String scalar(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /** A shell single-quoted word for a value already held to a charset without {@code '}. */
  private static String quote(String value) {
    return "'" + value + "'";
  }

  /** A shell single-quoted string for free text — the image, in one diagnostic. */
  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /** A literal block scalar's body; an empty line stays empty. The first line is {@code set -eu}. */
  private static void block(StringBuilder out, String text, String indent) {
    String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    for (String line : body.split("\n", -1)) {
      if (line.isEmpty()) {
        out.append('\n');
      } else {
        out.append(indent).append(line).append('\n');
      }
    }
  }
}
