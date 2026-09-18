package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import java.util.List;

/**
 * Compiles a repository's {@link CiReleaseSlots} plus an archetype recipe into the <b>two ordinary
 * trigger documents</b> the release cycle is: the QA pipeline that gates a release request, and the
 * release pipeline that publishes.
 *
 * <p><b>Two documents, two PHASES of one pipeline.</b> A release is one pipeline: phase one is the
 * QA a release request is gated on, phase two is the publish at the released tag, and phase three is
 * the deploy, which is qits-deployments' own release request and is declared nowhere near here.
 * Between two phases sits a gate — CI, approval, publish, deployment — and <b>a gate delays, it does
 * not fail</b>. So the pair this class emits is the first two phases of one thing, never two
 * independent pipelines that share a file.
 *
 * <p><b>Nothing in the composed text names a phase, and that is the DSL's boundary.</b> {@link
 * CiReleaseSlots} declares slots and these documents declare events; which phase a run is, is the
 * <b>engine's</b> word, recorded when the run is accepted and derived from the triggering event
 * alone. So a run composed from {@code release:} and a run from a repository's own hand-written
 * {@code ci-event-*.yml} on the same event are the same phase — which is why a phase must never be
 * inferred here from which slot produced a document.
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
 * as a child shell under {@code -eu}, which buys three things at once: the wrapper's own {@code set
 * -eu} is not something a repository can turn off, nothing in the script is expanded on its way into
 * the file, and a script that calls {@code exit 0} ends itself rather than the step — so the
 * postlude still runs, which is what makes "SBOM before green" true by construction rather than by
 * ordering discipline in 47 repositories.
 *
 * <h2>An image without bash</h2>
 *
 * <p><b>The composer cannot see inside an image, so every dependency the wrapper has on the image's
 * contents is decided at RUN time, in the emitted text itself.</b> Nearly every step image on the
 * platform is a {@code qits/build-images/*} the platform builds and can guarantee. One repository
 * legitimately is not: qits-build-images-oci builds those images, so it cannot run on one without
 * bootstrapping itself, and runs on upstream {@code docker:28-dind} — which carries {@code /bin/sh}
 * and {@code /bin/ash} but no {@code /bin/bash}, and {@code wget} but no {@code curl}.
 *
 * <p>So the runner is {@code bash} where bash exists and {@code sh} where it does not, and the CLI
 * fetch is {@code curl} then {@code wget} then a named refusal. Both checks are {@code command -v},
 * which is POSIX and present in every shell either arm can be running under. The consequence is
 * stated rather than hidden: <b>a declared script that uses a bashism will fail on an image with no
 * bash</b>, and that is the repository's own business — the wrapper runs a script, it does not
 * translate one. A per-repository flag would have been the wrong shape twice over: it asks an author
 * to restate a fact about an image they usually did not build, and it is wrong the moment the image
 * changes underneath the declaration.
 *
 * <p><b>This is the second half of a pair, not a new idea.</b> qits-ci-daemon already probes for
 * bash and runs the step's outer script under {@code sh} when it is absent — same fallback, same
 * argument, named after the same image ({@code Workspace.probeTooling}). What was left was the text
 * INSIDE that script, which said {@code bash} and {@code curl} unconditionally and so died on the
 * one image the daemon had been taught to accept.
 *
 * <p>Nothing here needs {@code jq} any more, and that is deliberate rather than lucky: the CLI's
 * version used to be read out of qits-artifacts' daemons listing with it, and became a pom pin
 * injected as {@code $QITS_ARTIFACTS_CLI_VERSION}. There is no JSON left in the emitted text to
 * parse, so there is no {@code jq} fallback to write.
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
 * $QITS_ARTIFACTS_CLI_PACKAGE}, the registry variables and the commissioned pair. The two exceptions
 * are an artifact's {@code type}/{@code name} and its {@code sbom:} path, which are interpolated into
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

  /**
   * Where the platform prelude installs the qits CLI on a release-phase step — the binary as {@code
   * qits}, plus a {@code qits-publish} symlink for compatibility with a script written against the
   * old name.
   */
  static final String CLI_DIR = "/tmp/qits-bin";

  private CiReleaseComposer() {}

  /**
   * The two composed documents. Either may be null: a phase this repository and its archetype
   * declare no steps for gets <b>no trigger document and therefore no run</b>, which is the honest
   * reading of "nothing is declared" — an SPA frontend publishes nothing, so it declares no {@code
   * release:} slot and no release run of it is ever recorded.
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
    // The daemon runs a step with `<shell> -c` and no -e — bash where the image has it, sh where it
    // does not — so this line is what makes an early failure a failure. -u is load-bearing too: an
    // unset injected variable must stop the step rather than resolve to nothing halfway through a
    // publish.
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
      // The qits CLI (qits, which also answers to qits-publish), fetched AT THE VERSION qits-ci
      // PINS and put on PATH for the whole release phase.
      //
      // THE DOWNLOAD WAS ALREADY VERSION-ADDRESSED; what changed is where the version comes from.
      // This block used to read qits-artifacts' own daemons listing and take that package's
      // `latestVersion` — so every composed release on the platform ran whatever the last CLI
      // release had been, with nothing in any consumer's tree naming it and no line anybody could
      // revert. It is a pom pin now (eu.wohlben.qits:qits-platform-access-cli-binary), injected by
      // CiDaemonLauncher as $QITS_ARTIFACTS_CLI_VERSION, and this text simply spends it.
      //
      // Which is why the listing read, its best-effort bearer and the jq that parsed it are all
      // gone: a release step's image no longer needs jq for the CLI's sake at all.
      //
      // Soft on the package: a deployment that has switched it off still runs every recipe that does
      // not call it, and one that does gets `command not found` rather than a silent skip. The
      // postlude below demands the package outright.
      out.append("if [ -n \"${QITS_ARTIFACTS_CLI_PACKAGE:-}\" ]; then\n");
      // Hard on the version, and the message names the real cause. The launcher's constant cannot be
      // blank (PlatformAccessCliBinary refuses that at class-init) and the variable is always sent,
      // so the only way to be inside this branch without one is a qits-ci older than the pin having
      // launched this step — which a re-run against a current qits-ci fixes.
      out.append(
          "  : \"${QITS_ARTIFACTS_CLI_VERSION:?the qits CLI package is configured but no version was"
              + " injected; the qits-ci that launched this step predates the CLI pin}\"\n");
      out.append("  mkdir -p ").append(CLI_DIR).append('\n');
      // curl, then wget, then a refusal that names the image. An image with neither is a real
      // shape in the fleet's neighbourhood — docker:28-dind has wget and no curl — and the one
      // thing it must not produce is `curl: not found` from a line nobody can see the reason for.
      // Only the FETCH degrades: the CLI itself is a static binary, so everything downstream of
      // this block, the postlude's `qits artifacts publish` included, is unaffected by which arm
      // ran.
      String cliUrl =
          " \"$QITS_ARTIFACTS_URL/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"";
      out.append("  if command -v curl > /dev/null 2>&1; then\n");
      out.append("    curl -fsSL --retry 2 --retry-delay 2 -o ")
          .append(CLI_DIR)
          .append("/qits")
          .append(cliUrl)
          .append('\n');
      out.append("  elif command -v wget > /dev/null 2>&1; then\n");
      out.append("    wget -q -O ").append(CLI_DIR).append("/qits").append(cliUrl).append('\n');
      out.append("  else\n");
      out.append("    echo ")
          .append(
              shellQuote(
                  "qits-ci: the image for this step ("
                      + step.image()
                      + ") has neither curl nor wget, so the qits CLI cannot be fetched into it —"
                      + " add one to the image, or take the qits calls out of this step"))
          .append(" >&2\n");
      out.append("    exit 1\n");
      out.append("  fi\n");
      out.append("  chmod +x ").append(CLI_DIR).append("/qits\n");
      out.append("  ln -sf ").append(CLI_DIR).append("/qits ").append(CLI_DIR).append("/qits-publish\n");
      out.append(
          "  echo \"qits-ci: fetched $QITS_ARTIFACTS_CLI_PACKAGE $QITS_ARTIFACTS_CLI_VERSION\""
              + " >&2\n");
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
    // Under bash where the image has bash, under sh where it does not — decided HERE, at run time,
    // because the composer cannot see inside an image. A declared script that uses a bashism will
    // fail on an image with no bash; that is the repository's own business, and its alternative was
    // a flag asking an author to restate a fact about an image they did not build.
    out.append("if command -v bash > /dev/null 2>&1; then\n");
    out.append("  bash -eu ").append(SLOT_SCRIPT).append('\n');
    out.append("else\n");
    out.append("  sh -eu ").append(SLOT_SCRIPT).append('\n');
    out.append("fi\n");
    if (!postlude.isEmpty()) {
      out.append("# --- platform postlude --------------------------------------------------------\n");
      out.append(
          ": \"${QITS_ARTIFACTS_CLI_PACKAGE:?this release submits an SBOM, and the qits CLI is not"
              + " configured on this deployment}\"\n");
      for (SlotArtifact artifact : postlude) {
        if (!artifact.hasSbom()) {
          continue;
        }
        out.append("qits artifacts publish sbom submit --type ")
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
   * A shell single-quoted string for a value that has NOT been through {@link
   * CiReleaseSlotParser#SCRIPT_SAFE} — a step's {@code image:}, which is free text.
   *
   * <p>It appears in one place, the diagnostic that names the image when neither fetcher exists, and
   * a message is still a place a {@code "} or a {@code $} would change what the shell does. The
   * close-reopen dance is the only way out of single quotes, so a quote in an image name ends up
   * literal rather than structural.
   */
  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
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
