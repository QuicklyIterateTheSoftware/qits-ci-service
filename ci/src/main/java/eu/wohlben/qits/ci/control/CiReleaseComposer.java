package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import java.util.List;

/**
 * Compiles a repository's {@link CiReleaseSlots} plus an archetype recipe into the <b>two ordinary
 * trigger documents</b> the release cycle is: the QA pipeline that gates a release request, and the
 * release pipeline that publishes.
 *
 * <p><b>A pure function.</b> No clock, no config, no lookup, no logging — inputs in, two strings
 * out, the same two strings every time. That is what makes the golden-file tests a regression net
 * rather than a snapshot: a diff in a composed document is a reviewable fact about a change to this
 * class, and nothing else can move it.
 *
 * <h2>What is composed, and why the platform may own it</h2>
 *
 * <p>{@code event:}, {@code when:} and {@code checkout:} are identical in all 78 release files of
 * the fleet after normalising the repository's own name. That measurement is the whole licence: they
 * are platform process wearing a per-repository costume, so the composer writes them and no
 * repository restates them.
 *
 * <ul>
 *   <li>QA — {@code event: ReleaseRequestChanged}, {@code when: [{repoName: {exact: …}}]}, {@code
 *       checkout: {branch: backingBranch, sha: mergedSha}}. No {@code optional:}: a request naming
 *       no fold has nothing to gate.
 *   <li>release — {@code event: SCMRelease}, {@code when: [{repository: {exact: …}}]}, {@code
 *       checkout: {branch: version, sha: commitSha, optional: true}}. The flag stays until no event
 *       published before {@code commitSha} existed can still arrive.
 * </ul>
 *
 * <p>The two spell the repository differently — {@code repoName} and {@code repository} — because
 * the two events do, and both carry the repository's public NAME. That asymmetry is copied from the
 * fleet rather than invented here.
 *
 * <h2>The step, and the heredoc that makes a repository's script data</h2>
 *
 * <p>Every composed step is <b>platform prelude + the declared script as DATA + platform
 * postlude</b>. The script is written to {@value #SLOT_SCRIPT} through a quoted heredoc and executed
 * as a child {@code bash -eu}, which buys three things at once: the wrapper's own {@code set -eu} is
 * not something a repository can turn off, nothing in the script is expanded on its way into the
 * file, and a script that calls {@code exit 0} ends itself rather than the step — so the postlude
 * still runs, which is what makes "SBOM before green" true by construction rather than by ordering
 * discipline in 47 repositories.
 *
 * <p><b>A script containing the delimiter is a {@link CiConfigException}, never a corrupted
 * wrapper.</b> That is the one way a quoted heredoc can be escaped from, so it is refused at
 * composition, loudly, naming the file the script came from.
 *
 * <h2>What reaches a script, and what does not</h2>
 *
 * <p><b>Environment, in every case but two.</b> A step reads {@code $QITS_VERSION} (seeded by
 * {@code CiRunService} from the triggering event — the three inconsistent {@code jq} grammars in the
 * fleet die with it), {@code $QITS_CI_REPO_NAME}, {@code $QITS_ARTIFACTS_URL}, {@code
 * $QITS_ARTIFACTS_CLI_URL}, the registry variables and the commissioned pair. The two exceptions are
 * an artifact's {@code type}/{@code name} and its {@code sbom:} path, which are interpolated into
 * the postlude — held to {@link CiReleaseSlotParser#SCRIPT_SAFE} at parse time and single-quoted
 * here, so the value cannot be anything but a word.
 */
public final class CiReleaseComposer {

  /** The QA half's event: qits-projects announces one per successful re-fold of a request. */
  public static final String RELEASE_REQUEST_EVENT = CiRunService.RELEASE_REQUEST_EVENT_NAME;

  /**
   * The release half's event.
   *
   * <p>Spelled as a string for the reason {@code CiRunService.TAG_EVENT_NAME} is: {@code ci} holds
   * no compile-time knowledge of another context, and qits-projects publishes no vocabulary jar. The
   * guard is {@code bus/ScmReleaseContractTest} over in the module where the record is on the
   * classpath.
   */
  public static final String RELEASE_EVENT = "SCMRelease";

  /** Where a composed step writes the repository's own script before running it. */
  static final String SLOT_SCRIPT = "/tmp/qits-slot.sh";

  /** The quoted heredoc delimiter. A script containing it is refused — see the class javadoc. */
  static final String HEREDOC_DELIMITER = "QITS_SLOT_EOF";

  /** Where the platform prelude installs {@code qits-publish} on a release-phase step. */
  static final String CLI_DIR = "/tmp/qits-bin";

  private CiReleaseComposer() {}

  /**
   * The two composed documents. Either may be null: a phase this repository and its archetype
   * declare no steps for gets <b>no trigger document and therefore no run</b>, which is the honest
   * reading of "nothing is declared" and is exactly what an SPA frontend's missing {@code
   * ci-event-release.yml} means today.
   */
  public record Composed(String releaseRequestDocument, String releaseDocument) {}

  /**
   * Compiles one repository's release cycle.
   *
   * @param repo the candidate the documents are for — its public name is what {@code when:} matches
   * @param slots the repository's own {@code .config/qits/release.yml}
   * @param archetype the recipe {@code slots} names, already parsed, or null when it names none
   * @throws CiConfigException when the composition cannot be made — a script colliding with the
   *     heredoc delimiter, or {@code artifacts:} declared with no release steps to publish them
   */
  public static Composed compose(CiRepoRef repo, CiReleaseSlots slots, CiReleaseSlots archetype) {
    String selector = selector(repo);
    // WHOLE-SLOT OVERRIDE. A repository that declares a slot replaces the archetype's entirely, and
    // the file the steps came from travels with them so an error names the document a person edits.
    Slot qa = choose(slots, archetype, true);
    Slot release = choose(slots, archetype, false);
    List<SlotArtifact> artifacts =
        !slots.artifacts().isEmpty()
            ? slots.artifacts()
            : archetype == null ? List.of() : archetype.artifacts();
    if (release == null && !artifacts.isEmpty()) {
      throw new CiConfigException(
          slots.configPath()
              + ": declares "
              + artifacts.size()
              + " artifact(s) but neither it nor its archetype declares any 'release' step — a"
              + " declaration with no pipeline behind it announces a release nothing published");
    }
    return new Composed(
        qa == null ? null : qaDocument(slots, archetype, selector, qa),
        release == null ? null : releaseDocument(slots, archetype, selector, release, artifacts));
  }

  /** One chosen slot: the steps, and the document they were declared in. */
  private record Slot(CiPipeline pipeline, String sourcePath) {}

  private static Slot choose(CiReleaseSlots slots, CiReleaseSlots archetype, boolean qa) {
    CiPipeline own = qa ? slots.releaseRequest() : slots.release();
    if (own != null) {
      return new Slot(own, slots.configPath());
    }
    CiPipeline inherited = archetype == null ? null : qa ? archetype.releaseRequest() : archetype.release();
    return inherited == null ? null : new Slot(inherited, archetype.configPath());
  }

  /**
   * The repository as {@code when:} names it: the public name, and the storage id for a candidate
   * the catalogue could give no name for.
   *
   * <p>The id arm is the same compatibility arm every address in this engine carries — pre-cutover
   * the two agree, and post-cutover a nameless candidate is one nothing can address anyway.
   */
  private static String selector(CiRepoRef repo) {
    return repo.named() ? repo.name() : repo.repoId();
  }

  private static String qaDocument(
      CiReleaseSlots slots, CiReleaseSlots archetype, String selector, Slot qa) {
    StringBuilder out = new StringBuilder();
    header(out, slots, archetype);
    out.append("event: ").append(RELEASE_REQUEST_EVENT).append('\n');
    out.append("when:\n");
    out.append("  - repoName: { exact: ").append(scalar(selector)).append(" }\n");
    out.append("checkout:\n");
    out.append("  branch: backingBranch\n");
    out.append("  sha: mergedSha\n");
    steps(out, qa, false, List.of());
    return out.toString();
  }

  private static String releaseDocument(
      CiReleaseSlots slots,
      CiReleaseSlots archetype,
      String selector,
      Slot release,
      List<SlotArtifact> artifacts) {
    StringBuilder out = new StringBuilder();
    header(out, slots, archetype);
    out.append("event: ").append(RELEASE_EVENT).append('\n');
    out.append("when:\n");
    out.append("  - repository: { exact: ").append(scalar(selector)).append(" }\n");
    out.append("checkout:\n");
    out.append("  branch: version\n");
    out.append("  sha: commitSha\n");
    out.append("  optional: true\n");
    if (!artifacts.isEmpty()) {
      out.append("artifacts:\n");
      for (SlotArtifact artifact : artifacts) {
        out.append("  - { type: ")
            .append(scalar(artifact.artifact().type().declared()))
            .append(", name: ")
            .append(scalar(artifact.artifact().name()))
            .append(" }\n");
      }
    }
    steps(out, release, true, artifacts);
    return out.toString();
  }

  private static void header(StringBuilder out, CiReleaseSlots slots, CiReleaseSlots archetype) {
    out.append("# Composed by qits-ci from ").append(slots.configPath());
    if (archetype != null) {
      out.append(" and ").append(archetype.configPath());
    }
    out.append(".\n");
    out.append(
        "# Generated: this text is never committed. Edit the slot file or the archetype recipe.\n");
  }

  /**
   * The step list.
   *
   * <p><b>The declared flags are carried through unchanged</b> — {@code timeout-seconds}, {@code
   * docker}, {@code build}, {@code user}, {@code gating} — because they are the residue the whole
   * migration exists to keep per-repository, and rewriting one here would be the composer having an
   * opinion about a thing the author already stated. What the composer adds is the prelude and the
   * postlude; what it never does is reorder, drop or re-flag.
   */
  private static void steps(
      StringBuilder out, Slot slot, boolean releasePhase, List<SlotArtifact> artifacts) {
    List<CiStepDecl> declared = slot.pipeline().steps();
    int postludeAt = releasePhase ? postludeStep(declared, artifacts) : -1;
    out.append("steps:\n");
    for (int i = 0; i < declared.size(); i++) {
      CiStepDecl step = declared.get(i);
      out.append("  - image: ").append(scalar(step.image())).append('\n');
      if (step.timeoutSeconds() != null) {
        out.append("    timeout-seconds: ").append(step.timeoutSeconds()).append('\n');
      }
      if (step.docker()) {
        out.append("    docker: true\n");
      }
      if (step.build()) {
        out.append("    build: true\n");
      }
      if (!step.user().isEmpty()) {
        out.append("    user: ").append(scalar(step.user())).append('\n');
      }
      if (!step.gating()) {
        out.append("    gating: false\n");
      }
      out.append("    script: |\n");
      block(
          out,
          script(step, releasePhase, i == postludeAt ? artifacts : List.of(), slot.sourcePath()),
          "      ");
    }
  }

  /**
   * Which release step carries the SBOM submissions: the <b>last one that builds</b> — the flags a
   * publishing step declares are {@code build:} or {@code docker:} — and, failing that, the last step
   * of the pipeline.
   *
   * <p>The document is produced by the build, so submitting it from the step that produced it is
   * what keeps the file path in that step's own working directory. Last rather than first, because a
   * pipeline that builds twice (a toolchain image, then the artifact) writes the document in the
   * second one.
   *
   * <p>Answers -1 when there is nothing to submit, which is every release with no {@code sbom:} path
   * declared and therefore every release the fleet publishes today.
   */
  private static int postludeStep(List<CiStepDecl> steps, List<SlotArtifact> artifacts) {
    if (steps.isEmpty() || artifacts.stream().noneMatch(SlotArtifact::hasSbom)) {
      return -1;
    }
    for (int i = steps.size() - 1; i >= 0; i--) {
      if (steps.get(i).build() || steps.get(i).docker()) {
        return i;
      }
    }
    return steps.size() - 1;
  }

  /** One composed step script: prelude, the declared script as data, postlude. */
  private static String script(
      CiStepDecl step, boolean releasePhase, List<SlotArtifact> postlude, String sourcePath) {
    StringBuilder out = new StringBuilder();
    // The daemon runs a step with `bash -c` and no -e, so this line is what makes an early failure
    // a failure. -u is load-bearing too: an unset injected variable must stop the step rather than
    // resolve to nothing halfway through a publish.
    out.append("set -eu\n");
    out.append("# --- platform prelude ---------------------------------------------------------\n");
    if (releasePhase) {
      // THE TAG IS THE TREE. On the anchored path the run is already at the tag's commit and this
      // pair is a no-op; on the optional-checkout fallback (an SCMRelease published before
      // `commitSha` existed) it is the whole mechanism. One text, both paths.
      out.append(
          ": \"${QITS_VERSION:?the triggering release event carried no version}\"\n");
      out.append(
          "git fetch \"$QITS_CI_REPOSITORY_URL\" \"refs/tags/$QITS_VERSION:refs/tags/$QITS_VERSION\"\n");
      out.append("git checkout --detach \"$QITS_VERSION\"\n");
      // qits-publish, on PATH for the whole release phase. Soft on the URL: a deployment that has
      // not pinned the CLI yet still runs every recipe that does not call it, and one that does gets
      // `command not found` rather than a silent skip. The postlude below demands the URL outright.
      out.append("if [ -n \"${QITS_ARTIFACTS_CLI_URL:-}\" ]; then\n");
      out.append("  mkdir -p ").append(CLI_DIR).append('\n');
      out.append("  curl -fsSL -o ").append(CLI_DIR).append("/qits-publish \"$QITS_ARTIFACTS_CLI_URL\"\n");
      out.append("  chmod +x ").append(CLI_DIR).append("/qits-publish\n");
      out.append("  PATH=\"").append(CLI_DIR).append(":$PATH\"\n");
      out.append("  export PATH\n");
      out.append("fi\n");
    }
    if (step.build()) {
      // The platform builder, demanded loudly before anything is built. Unset means a
      // qits-ci/qits-containers pair too old to inject it; EMPTY is the kill switch. Either way a
      // build-mode step must fail here, naming the cause, rather than reach for a socket it is not
      // handed.
      out.append(
          ": \"${BUILDKIT_HOST:?the platform builder is off or not injected; this step builds only"
              + " through it}\"\n");
      out.append(": \"${QITS_BUILD_REGISTRY:?}\"\n");
    }
    if (step.build() || step.docker()) {
      // The commissioned pair as FILES, for a buildctl `--secret id=…,src=…` that writes no layer.
      // In a subshell, so `umask 077` bounds these two files and does not silently follow the whole
      // script — the fleet's hand-written form leaves it set for everything after it.
      out.append("(\n");
      out.append("  umask 077\n");
      out.append("  printf '%s' \"${QITS_COMMISSIONED_CLIENT_ID:-}\" > /tmp/qits-client-id\n");
      out.append("  printf '%s' \"${QITS_COMMISSIONED_CLIENT_SECRET:-}\" > /tmp/qits-client-secret\n");
      out.append(")\n");
    }
    out.append("# --- the declared step, run as data -------------------------------------------\n");
    out.append("cat > ").append(SLOT_SCRIPT).append(" <<'").append(HEREDOC_DELIMITER).append("'\n");
    out.append(slotScript(step.script(), sourcePath));
    out.append(HEREDOC_DELIMITER).append('\n');
    out.append("bash -eu ").append(SLOT_SCRIPT).append('\n');
    if (!postlude.isEmpty()) {
      out.append("# --- platform postlude --------------------------------------------------------\n");
      out.append(
          ": \"${QITS_ARTIFACTS_CLI_URL:?this release submits an SBOM, and qits-publish is not"
              + " configured on this deployment}\"\n");
      for (SlotArtifact artifact : postlude) {
        if (!artifact.hasSbom()) {
          continue;
        }
        out.append("qits-publish sbom submit --type ")
            .append(quote(artifact.artifact().type().declared()))
            .append(" --name ")
            .append(quote(artifact.artifact().name()))
            .append(" --version \"$QITS_VERSION\" --file ")
            .append(quote(artifact.sbomPath()))
            .append('\n');
      }
    }
    return out.toString();
  }

  /**
   * The declared script, ready to sit inside a quoted heredoc: verbatim, newline-terminated, and
   * refused outright when it carries the delimiter.
   */
  private static String slotScript(String script, String sourcePath) {
    if (script.contains(HEREDOC_DELIMITER)) {
      throw new CiConfigException(
          sourcePath
              + ": a step's script contains '"
              + HEREDOC_DELIMITER
              + "', which is the delimiter the composer wraps it in — rename the token in the"
              + " script. A composed step must never be able to end its own wrapper.");
    }
    return script.endsWith("\n") ? script : script + "\n";
  }

  // --- emitting YAML -----------------------------------------------------------------------------

  /**
   * A YAML single-quoted scalar. Only {@code '} needs escaping inside one, and single-quoted means
   * no escape processing at all — so every value this composer emits reads back as the string it
   * was, which is what the round-trip through {@code CiEventTriggerParser} asserts.
   */
  private static String scalar(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /** A shell single-quoted word. The charset guard has already refused every {@code '}. */
  private static String quote(String value) {
    return "'" + value + "'";
  }

  /**
   * A literal block scalar's body. Every non-empty line is indented; an empty line stays empty,
   * because an indented blank line would add trailing whitespace the round-trip would then have to
   * be lenient about.
   *
   * <p>No indentation indicator is written, and none is needed: the first line of every composed
   * script is {@code set -eu}, so a block can never open on a more-indented line.
   */
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
