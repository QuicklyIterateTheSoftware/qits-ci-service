package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>The platform's half of a composed pipeline is THIS CLASS, and that is what lets a recipe be
 * read from the revision under test.</b> The prelude and the postlude are emitted here, in Java,
 * and no file in any repository contributes to them. An archetype recipe contributes only what a
 * slot file can: slot steps, {@code artifacts:} and {@code userflows:} — every one of which a
 * repository may already replace wholesale in its own {@code release.yml}. So a repository carrying
 * its own copy of a recipe ({@link CiReleaseArchetypes}) gains nothing over one that overrides a
 * slot, and the recipe this class is handed may be the repository's own or the one packaged into
 * qits-ci without the composition caring which.
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
 *       checkout: {branch: version, sha: commitSha}}. No {@code optional:} either, and the removal
 *       is the paragraph below.
 * </ul>
 *
 * <p><b>{@code optional: true} used to be on the release half and is GONE.</b> The comment is kept
 * rather than deleted with the line, because what it said was true when it was written: {@code
 * commitSha} is an additive component of {@code SCMRelease}, so a release published before it
 * existed carries none, and the flag made such an event fall back to {@code main}'s head rather
 * than cost a release pipeline its run. Two things ended it.
 *
 * <p>The compatibility it advertised is <b>unreachable for a composed document</b>. A composed
 * pipeline is read at the revision the event names — {@code CiEventTriggerService.releaseRevision},
 * since the release-slots fix — so an event with no usable {@code commitSha} reads no {@code
 * release.yml}, composes no document, and there is no {@code checkout:} for a flag to soften. The
 * flag could only ever fire on the half-missing payload (a usable {@code commitSha} and no usable
 * {@code version}), which is not the case it was written for.
 *
 * <p>And what it did in that case was the defect one layer down: dispatch a RELEASE run at {@code
 * main}'s head with its checkout stripped. <b>A release pipeline must be composed from, and run
 * against, the revision actually being built</b> (owner ruling), and {@code main} is a different
 * revision wearing the event's name. So the composed release half declares the pair plainly and an
 * event that does not carry it gets the same answer every other unresolvable checkout gets: one
 * WARN and no run.
 *
 * <p><b>The flag itself is not retired</b> — {@code CiEventTriggerParser} still accepts it, and a
 * hand-written {@code ci-event-*.yml} in repository B reacting to repository A's {@code
 * SoftwareRelease} still uses it. That is not a release run of B: B builds its own {@code main},
 * which is the only revision the event names for it. Nothing composed declares it.
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
 * <p><b>Environment, in every case but the declarations.</b> A step reads {@code $QITS_VERSION}
 * (seeded by {@code CiRunService} from the triggering event — the three inconsistent {@code jq}
 * grammars in the fleet die with it), {@code $QITS_CI_REPO_NAME}, {@code $QITS_DOMAIN} (the one
 * address input: every platform host is code under it, qits-731), {@code
 * $QITS_ARTIFACTS_CLI_PACKAGE} and the run's credential files. The exceptions
 * are what {@code release.yml} declares about its artifacts and contracts — an artifact's {@code
 * type}/{@code name}, its {@code sbom:}, {@code path:}, {@code link:} and {@code include:}, and a
 * contract tree's application, provider and {@code from:} — which are interpolated into the
 * postlude: held to {@link CiReleaseSlotParser#SCRIPT_SAFE} (or {@link
 * CiReleaseSlotParser#INCLUDE_SAFE} for a glob) at parse time and single-quoted here, so the value
 * cannot be anything but a word.
 *
 * <h2>The publishing postlude (qits-620)</h2>
 *
 * <p><b>The platform uploads every maven and npm artifact, packs every contract and publishes every
 * {@code @apidocs} document; a recipe only builds.</b> On the LAST step of the release slot, after the
 * declared script, the postlude calls the qits CLI: one {@code qits artifacts publish maven|npm} per
 * maven or npm entry in {@code link:} dependency order, then one {@code contract} per contract
 * package, then {@code contract-docs} when golden masters are declared, then one {@code docs submit
 * --openapi} per {@code @apidocs} entry naming its file. Each call fails the step on a non-zero
 * exit.
 *
 * <h2>The SBOM postlude (qits-621)</h2>
 *
 * <p><b>Every release step submits every declared SBOM it holds, and the last step checks that each
 * one arrived.</b> The composer cannot see which step writes a document: an image or daemon SBOM
 * ({@code .sbom/sbom.json}, {@code out/sbom.json}) comes out of a {@code build: true} step, a jar's
 * ({@code <module>/target/sbom.json}) out of the last step, which runs maven — so no single step
 * reaches both. Each step's postlude therefore carries one {@code qits artifacts publish sbom
 * submit}, guarded by {@code [ -f <path> ]}, per entry declaring {@code sbom:}; a re-submit is
 * idempotent (first write wins). The LAST step then asks {@code qits artifacts publish exists sbom}
 * for every such entry and fails, naming the entry and its declared path, when no step produced it —
 * which is what keeps the file guard from turning a missing SBOM into a silent green.
 *
 * <p>The postlude runs only after the declared script exited 0 (the wrapper is {@code set -eu}), as
 * it always has; it is the release phase's alone, and a QA step's report hook (below) is not one. On the last step the publishes come first, so a submitted SBOM always describes
 * something that was uploaded. An {@code if-changed} entry is the exception to "every step": its
 * submit and its presence check sit on the publishing step alone and run only when its publish
 * answered {@code published <v>} — {@code unchanged since <v>} published nothing at this version,
 * so there is nothing to submit and nothing to find.
 *
 * <h2>The QA report hook (qits-754)</h2>
 *
 * <p><b>Every QA step submits its reports whatever its exit code, and the exit code stays the
 * declared script's.</b> A {@code release-request:} step runs the declared script between {@code set
 * +e} and {@code set -e}, keeps its code in {@code qits_step_exit}, writes {@value #REPORT_HOOK}
 * through a quoted heredoc and runs it as {@code sh <hook> "$qits_step_exit" || echo …}, then ends
 * with {@code exit "$qits_step_exit"}. A red build is exactly the one whose test results somebody
 * wants to read, so the hook cannot wait for green; and nothing the hook does — a CLI that cannot be
 * fetched, an image with neither curl nor wget, a refused upload — can move the verdict, because the
 * {@code ||} absorbs its failure and the last line re-states the captured code.
 *
 * <p>The hook fetches the pinned CLI the way the release prelude does — the same store, the same
 * {@code $QITS_ARTIFACTS_CLI_PACKAGE}/{@code $QITS_ARTIFACTS_CLI_VERSION}, the run's {@code
 * $QITS_TOKEN} as bearer, curl then wget — but <b>soft</b>: every failure is a printed line and a
 * non-zero exit of the hook, never of the step. It goes into {@value #REPORT_CLI_DIR}, not onto
 * {@code PATH}, and then calls {@code qits ci report submit --exit-code "$1"}; the CLI finds the run
 * and the step in {@code $QITS_CI_RUN_ID}/{@code $QITS_CI_STEP_INDEX} and its bearer through {@code
 * $QITS_PUBLISH_TOKEN_COMMAND}, all inherited from the step's environment. With no CLI package
 * configured it says reports were skipped and succeeds. Release-phase documents carry no hook and
 * are unchanged: their postlude still runs only after the declared script exited 0.
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

  /**
   * The four payload paths the composed {@code checkout:} blocks name — the QA half's pair and the
   * release half's.
   *
   * <p><b>Constants rather than literals because a second reader arrived.</b> The engine has to know
   * which revision an arriving release event is <em>about</em> before it can compose anything at
   * all: the repository's {@code release.yml} is read at the rev the composed run will check out,
   * which is the rev these paths resolve to in the payload. Spelling them twice — once in the text
   * emitted here, once in that resolution — is two things that must always agree and nothing to make
   * them agree. See {@code CiEventTriggerService.releaseRevision}.
   */
  public static final String RELEASE_REQUEST_BRANCH_PATH = "backingBranch";

  /** @see #RELEASE_REQUEST_BRANCH_PATH */
  public static final String RELEASE_REQUEST_SHA_PATH = "mergedSha";

  /**
   * The release half's ref path: a <b>tag name</b>, which is a ref name like any other — the engine
   * and the daemon hold no concept of a tag.
   *
   * @see #RELEASE_REQUEST_BRANCH_PATH
   */
  public static final String RELEASE_BRANCH_PATH = "version";

  /** @see #RELEASE_REQUEST_BRANCH_PATH */
  public static final String RELEASE_SHA_PATH = "commitSha";

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

  /**
   * Where a QA step's report hook is written before it runs (qits-754) — a file, like {@link
   * #SLOT_SCRIPT}, so its {@code exit} and its positional parameters are its own.
   */
  static final String REPORT_HOOK = "/tmp/qits-report-hook.sh";

  /** The quoted heredoc delimiter the report hook is written through. Platform text only. */
  static final String REPORT_HOOK_DELIMITER = "QITS_REPORT_HOOK";

  /**
   * Where a QA step's report hook fetches the qits CLI: {@link #CLI_DIR}'s layout (the binary as
   * {@code qits}) in a directory of its own, so it neither lands on nor shadows anything a declared
   * script put on its own {@code PATH}.
   */
  static final String REPORT_CLI_DIR = "/tmp/qits-cli";

  /**
   * Where a release-phase prelude downloads the qits CLI from: the daemons store of qits-artifacts'
   * public name, {@code registry.qits.$QITS_DOMAIN} (qits-731). The host is code under the domain,
   * never a variable a step is handed, so nothing in a step's environment can move the download.
   * Public for one reader: {@code QitsCliPinIT} substitutes its stub store for exactly this text in
   * the composed script it runs, which is a seam no step can reach.
   */
  public static final String CLI_DOWNLOAD_BASE =
      "https://registry.qits.$QITS_DOMAIN/artifacts/daemons/";

  /**
   * Where every composed step's prelude writes the lockfile origin check before running it — see
   * {@link #lockfileOriginCheck()}. A file rather than inline text so a recipe that materialises a
   * submodule after the prelude has run (java-service's webui) can run the same check again over the
   * tree it now has.
   */
  public static final String LOCKFILE_CHECK = "/tmp/qits-lockfile-origins.sh";

  /** The quoted heredoc delimiter the check is written through. Platform text, never a script's. */
  static final String LOCKFILE_CHECK_DELIMITER = "QITS_LOCKFILE_EOF";

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
   *     heredoc delimiter, {@code artifacts:} or {@code contracts:} declared with no release steps to
   *     publish them
   */
  public static Composed compose(CiRepoRef repo, CiReleaseSlots slots, CiReleaseSlots archetype) {
    String selector = selector(repo);
    // WHOLE-SLOT OVERRIDE. A repository that declares a slot replaces the archetype's entirely, and
    // the file the steps came from travels with them so an error names the document a person edits.
    Slot qa = choose(slots, archetype, true);
    Slot release = choose(slots, archetype, false);
    boolean ownArtifacts = !slots.artifacts().isEmpty() || archetype == null;
    List<SlotArtifact> artifacts = ownArtifacts ? slots.artifacts() : archetype.artifacts();
    String artifactsPath = ownArtifacts ? slots.configPath() : archetype.configPath();
    // Contracts are a repository's own facts: an archetype recipe cannot declare them.
    CiContracts contracts = slots.contracts();
    if (release == null && !artifacts.isEmpty()) {
      throw new CiConfigException(
          slots.configPath()
              + ": declares "
              + artifacts.size()
              + " artifact(s) but neither it nor its archetype declares any 'release' step — a"
              + " declaration with no pipeline behind it announces a release nothing published");
    }
    if (release == null && contracts != null) {
      throw new CiConfigException(
          slots.configPath()
              + ": declares contracts but neither it nor its archetype declares any 'release' step —"
              + " the platform publishes contract packages from the release slot's last step, so"
              + " with no release slot nothing would ever publish them");
    }
    Postlude postlude = new Postlude(artifacts, artifactsPath, contracts, selector);
    return new Composed(
        qa == null ? null : qaDocument(slots, archetype, selector, qa),
        release == null ? null : releaseDocument(slots, archetype, selector, release, postlude));
  }

  /**
   * What the release phase's postlude spends: the declared artifacts, the file they came from (for
   * error messages), the contracts, and the repository's name, which names its pact packages.
   */
  private record Postlude(
      List<SlotArtifact> artifacts, String artifactsPath, CiContracts contracts, String repository) {

    /** The contract packages, pact packages named by this repository. */
    List<CiContracts.Package> contractPackages() {
      return contracts == null ? List.of() : contracts.packages(repository);
    }


    /** Whether anything is published by the platform, which is what places the publish block. */
    boolean publishes() {
      return contracts != null
          || artifacts.stream().anyMatch(a -> a.uploaded() || a.publishesApidocs());
    }

    /**
     * Every entry the composed {@code artifacts:} block carries, and so every row the join owes:
     * the declared artifacts, then each contract package as an ordinary {@code if-changed} entry.
     */
    List<CiArtifact> announced() {
      List<CiArtifact> announced = new ArrayList<>();
      artifacts.forEach(artifact -> announced.add(artifact.artifact()));
      if (contracts != null) {
        Set<String> declared = new HashSet<>();
        artifacts.forEach(a -> declared.add(a.artifact().type() + " " + a.artifact().name()));
        for (CiContracts.Package contract : contractPackages()) {
          if (declared.contains(contract.ecosystem().type() + " " + contract.name())) {
            throw new CiConfigException(
                artifactsPath
                    + ": declares the "
                    + contract.ecosystem().declared()
                    + " artifact '"
                    + contract.name()
                    + "', which is the coordinate its contracts: section already publishes —"
                    + " drop the artifacts: entry, the platform packs and publishes it");
          }
          announced.add(contract.artifact());
        }
      }
      return announced;
    }
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
    out.append("  branch: ").append(RELEASE_REQUEST_BRANCH_PATH).append('\n');
    out.append("  sha: ").append(RELEASE_REQUEST_SHA_PATH).append('\n');
    steps(out, qa, false, null);
    return out.toString();
  }

  private static String releaseDocument(
      CiReleaseSlots slots,
      CiReleaseSlots archetype,
      String selector,
      Slot release,
      Postlude postlude) {
    StringBuilder out = new StringBuilder();
    header(out, slots, archetype);
    out.append("event: ").append(RELEASE_EVENT).append('\n');
    out.append("when:\n");
    out.append("  - repository: { exact: ").append(scalar(selector)).append(" }\n");
    out.append("checkout:\n");
    out.append("  branch: ").append(RELEASE_BRANCH_PATH).append('\n');
    // NO `optional: true`. See the class javadoc: the compatibility it advertised cannot be reached
    // by a composed document any more, and what it really did was dispatch a release run at main's
    // head with its checkout stripped.
    out.append("  sha: ").append(RELEASE_SHA_PATH).append('\n');
    List<CiArtifact> announced = postlude.announced();
    if (!announced.isEmpty()) {
      out.append("artifacts:\n");
      for (CiArtifact artifact : announced) {
        out.append("  - { type: ")
            .append(scalar(artifact.type().declared()))
            .append(", name: ")
            .append(scalar(artifact.name()));
        // The policy is emitted only when it is not the default, so every document composed
        // before the key existed composes byte-for-byte as it did (the goldens hold that). It has
        // to reach the composed block at all because the join reads the run's trigger document,
        // not release.yml. path:, link:, include: and sbom: do not: they reach only the postlude.
        if (artifact.publishIfChanged()) {
          out.append(", ")
              .append(CiArtifact.PUBLISH_KEY)
              .append(": ")
              .append(scalar(artifact.publish().declared()));
        }
        // A contract package is marked so the join can say which section declared it (qits-666);
        // an artifacts: entry carries nothing, which keeps every other document byte-identical.
        if (artifact.section() == CiArtifact.Section.CONTRACTS) {
          out.append(", ")
              .append(CiArtifact.SECTION_KEY)
              .append(": ")
              .append(scalar(artifact.section().declared()));
        }
        out.append(" }\n");
      }
    }
    steps(out, release, true, postlude);
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
   * docker}, {@code build}, {@code user} — because they are the residue the whole migration exists
   * to keep per-repository, and rewriting one here would be the composer having an opinion about a
   * thing the author already stated. What the composer adds is the prelude and the postlude; what it
   * never does is reorder, drop or re-flag.
   *
   * <p>{@code gating: false} used to be on that list and is not a flag any more — the key is a parse
   * error at both scopes now (ticket 9441bc6e), so emitting it would compose a document this
   * service's own parser refuses.
   */
  private static void steps(StringBuilder out, Slot slot, boolean releasePhase, Postlude postlude) {
    List<CiStepDecl> declared = slot.pipeline().steps();
    int last = releasePhase ? publishStep(declared) : -1;
    int publishAt = releasePhase && postlude.publishes() ? last : -1;
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
      out.append("    script: |\n");
      block(
          out,
          script(
              step,
              releasePhase,
              i == publishAt ? postlude : null,
              releasePhase ? postlude : null,
              i == last,
              slot.sourcePath()),
          "      ");
    }
  }

  /**
   * Which release step carries the publish block and the SBOM presence checks: the <b>last</b> one.
   *
   * <p>Last, and not the last building step, because every release slot that builds a maven module
   * builds it in its last step — a {@code maven-base} step after any image build, java-service's own
   * recipe included since qits-890 — and the CLI needs that step's {@code target/} to upload from. The presence checks go there for a different
   * reason: it is the one point at which every step that could have produced a declared SBOM has
   * already run.
   */
  static int publishStep(List<CiStepDecl> steps) {
    return steps.size() - 1;
  }

  /**
   * One composed step script: prelude, the declared script as data, postlude.
   *
   * @param publish what this step publishes, or null when it is not the publishing step
   * @param release the release phase's declarations, or null outside the release phase
   * @param last whether this is the release slot's last step, which carries the presence checks
   */
  private static String script(
      CiStepDecl step,
      boolean releasePhase,
      Postlude publish,
      Postlude release,
      boolean last,
      String sourcePath) {
    StringBuilder out = new StringBuilder();
    // The daemon runs a step with `<shell> -c` and no -e — bash where the image has it, sh where it
    // does not — so this line is what makes an early failure a failure. -u is load-bearing too: an
    // unset injected variable must stop the step rather than resolve to nothing halfway through a
    // publish.
    out.append("set -eu\n");
    out.append("# --- platform prelude ---------------------------------------------------------\n");
    // THE ONE ADDRESS INPUT (qits-731). Every platform address a step reaches is code under the
    // bare public domain — `registry.qits.<d>`, `mirror.qits.<d>` — so the domain is demanded of
    // every step, before anything here or in the declared script spends it. The qits-ci composing
    // this text is the one that launches the step and always sends it; unset means a step launched
    // by something else, and it must stop here naming the cause rather than dial `registry.qits.`.
    out.append(
        ": \"${QITS_DOMAIN:?this step was told no QITS_DOMAIN, so it has no platform address}\"\n");
    if (releasePhase) {
      // THE TAG IS THE TREE. Every composed release run is now anchored at the tag's own commit —
      // the document declares `checkout: { branch: version, sha: commitSha }` with no `optional:`,
      // and an event that does not carry the pair records no run at all — so this pair is a belt
      // over a checkout that has already happened rather than a second mechanism. It used to be the
      // whole mechanism on the optional-checkout fallback, which is the path that is gone: there is
      // no composed release run at main's head left for it to fetch the released tree into.
      out.append(
          ": \"${QITS_VERSION:?the triggering release event carried no version}\"\n");
      out.append(
          "git fetch \"$QITS_CI_REPOSITORY_URL\" \"refs/tags/$QITS_VERSION:refs/tags/$QITS_VERSION\"\n");
      out.append("git checkout --detach \"$QITS_VERSION\"\n");
    }
    // THE LOCKFILES, checked before the declared script can install from one (qits-731). npm
    // fetches every tarball by the `resolved` URL its lockfile pins and never asks the configured
    // registry, so a lockfile is an address list. The platform used to REWRITE those addresses in
    // every recipe, a sed per pipeline, because they were generated on a host that named internal
    // services; it rewrites nothing now. A lockfile is committed resolving against the two public
    // registries, and this is what holds that: one naming anything else fails the step here, naming
    // the file and the entries, rather than `npm ci` dying on a connection refused three minutes in.
    // After the release checkout, so it reads the tree the step really builds.
    out.append("cat > ")
        .append(LOCKFILE_CHECK)
        .append(" <<'")
        .append(LOCKFILE_CHECK_DELIMITER)
        .append("'\n");
    out.append(lockfileOriginCheck());
    out.append(LOCKFILE_CHECK_DELIMITER).append('\n');
    out.append("sh ").append(LOCKFILE_CHECK).append('\n');
    if (releasePhase) {
      // The qits CLI (qits, which also answers to qits-publish), fetched AT THE VERSION qits-ci
      // PINS and put on PATH for the whole release phase.
      //
      // THE DOWNLOAD WAS ALREADY VERSION-ADDRESSED; what changed is where the version comes from.
      // This block used to read qits-artifacts' own daemons listing and take that package's
      // `latestVersion` — so every composed release on the platform ran whatever the last CLI
      // release had been, with nothing in any consumer's tree naming it and no line anybody could
      // revert. It is a pom pin now (eu.wohlben.qits:qits-platform-access-cli-binary), injected by
      // StepContainerSettings as $QITS_ARTIFACTS_CLI_VERSION, and this text simply spends it.
      //
      // Which is why the listing read, its best-effort bearer and the jq that parsed it are all
      // gone: a release step's image no longer needs jq for the CLI's sake at all.
      //
      // Soft on the package: a deployment that has switched it off still runs every recipe that does
      // not call it, and one that does gets `command not found` rather than a silent skip. The
      // postlude below demands the package outright.
      cliFetch(out, step, false);
    }
    if (step.build()) {
      // The platform builder, demanded loudly before anything is built. Unset means a
      // qits-ci/runner pair too old to inject it; EMPTY is the kill switch. Either way a
      // build-mode step must fail here, naming the cause, rather than reach for a socket it is not
      // handed.
      out.append(
          ": \"${BUILDKIT_HOST:?the platform builder is off or not injected; this step builds only"
              + " through it}\"\n");
      // Where it pushes needs no line of its own: the registry is `registry.qits.$QITS_DOMAIN`
      // (qits-731), and the domain is demanded at the top of every step's prelude.
    }
    if (step.build() || step.docker()) {
      // The run's credential as two FILES, for a buildctl `--secret id=…,src=…` that writes no
      // layer: a Dockerfile's `--secret id=qits-client-id`/`qits-client-secret` is consumed as
      // `QITS_MAVEN_AUTH_USR`/`PSW` for the maven mirror. In a subshell, so `umask 077` bounds these
      // two files and does not silently follow the whole script.
      //
      // The file NAMES are history: they carried a commissioned client's id and secret while a step
      // ran on qits-net. A step holds `$QITS_TOKEN` and nothing else now (qits-515), so the id is
      // `$QITS_TOKEN_SUBJECT` — this run's own subject, sent alongside the token by
      // `StepWorkloadSpecs` — and the secret is the token, which is what the edge's Basic realm
      // introspects. With no token the files are empty.
      out.append("(\n");
      out.append("  umask 077\n");
      out.append("  if [ -n \"${QITS_TOKEN:-}\" ]; then\n");
      out.append(
          "    printf '%s' \"${QITS_TOKEN_SUBJECT:-qits-ci-run}\" > /tmp/qits-client-id\n");
      out.append("    printf '%s' \"$QITS_TOKEN\" > /tmp/qits-client-secret\n");
      out.append("  else\n");
      out.append("    printf '' > /tmp/qits-client-id\n");
      out.append("    printf '' > /tmp/qits-client-secret\n");
      out.append("  fi\n");
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
    if (!releasePhase) {
      // THE QA REPORT HOOK (qits-754). The declared script's exit code is CAPTURED rather than
      // allowed to end the step, so the hook runs whatever it was — a red build is exactly the one
      // whose test results somebody wants to read — and the step then exits with that code, byte
      // for byte: the verdict stays the declared script's. A QA step has no postlude to skip (the
      // release-phase postlude is the only one, and `release` is null here), so nothing that used
      // to run "only on exit 0" moves.
      out.append("set +e\n");
    }
    out.append("if command -v bash > /dev/null 2>&1; then\n");
    out.append("  bash -eu ").append(SLOT_SCRIPT).append('\n');
    out.append("else\n");
    out.append("  sh -eu ").append(SLOT_SCRIPT).append('\n');
    out.append("fi\n");
    if (!releasePhase) {
      out.append("qits_step_exit=$?\n");
      out.append("set -e\n");
      out.append(
          "# --- platform report hook: always runs, never changes the step's verdict ---\n");
      // A FILE run by a child `sh`, through a quoted heredoc like the declared script: nothing in
      // it is expanded on the way in, `exit` inside it ends the hook rather than the step, and its
      // positional parameters are its own — `$1` is the exit code, which is why the fetch in it
      // builds the bearer in a named variable rather than with `set --`. The environment (the run's
      // token, QITS_PUBLISH_TOKEN_COMMAND, QITS_CI_RUN_ID and QITS_CI_STEP_INDEX) is inherited.
      out.append("cat > ")
          .append(REPORT_HOOK)
          .append(" <<'")
          .append(REPORT_HOOK_DELIMITER)
          .append("'\n");
      out.append(reportHook(step));
      out.append(REPORT_HOOK_DELIMITER).append('\n');
      // `||` and not a bare call: under the wrapper's `set -e` a failing hook would otherwise end
      // the step with the HOOK's code, which is the one outcome this block exists to rule out.
      out.append("sh ")
          .append(REPORT_HOOK)
          .append(" \"$qits_step_exit\" || echo \"qits-ci: reports were not submitted; the step's"
              + " verdict is unchanged\" >&2\n");
      out.append("exit \"$qits_step_exit\"\n");
      return out.toString();
    }
    List<SlotArtifact> artifacts = release == null ? List.of() : release.artifacts();
    // An if-changed entry's SBOM is the publishing step's alone (see sbomSubmit), so an earlier
    // step carrying nothing else gets no postlude at all.
    boolean sboms =
        artifacts.stream()
            .anyMatch(a -> a.hasSbom() && (last || !a.artifact().publishIfChanged()));
    if (publish != null || sboms) {
      out.append("# --- platform postlude --------------------------------------------------------\n");
      // The SBOM-only wording is kept where nothing is published, so every document composed
      // before the publishing postlude existed stays byte-identical (the goldens hold that).
      out.append(
          publish != null
              ? ": \"${QITS_ARTIFACTS_CLI_PACKAGE:?this release publishes or submits an SBOM, and the"
                  + " qits CLI is not configured on this deployment}\"\n"
              : ": \"${QITS_ARTIFACTS_CLI_PACKAGE:?this release submits an SBOM, and the qits CLI is"
                  + " not configured on this deployment}\"\n");
      if (publish != null) {
        publishBlock(out, publish);
      }
      for (int i = 0; i < artifacts.size(); i++) {
        sbomSubmit(out, artifacts.get(i), i, publish != null);
      }
      if (last) {
        for (int i = 0; i < artifacts.size(); i++) {
          sbomPresence(out, artifacts.get(i), i, publish != null, release.artifactsPath());
        }
      }
    }
    return out.toString();
  }

  /**
   * The report hook's text (qits-754): a soft fetch of the pinned qits CLI into {@value
   * #REPORT_CLI_DIR}, then {@code qits ci report submit --exit-code "$1"}. Run as {@code sh <file>
   * <exit code>}; every way it can fail prints a line and exits non-zero, and the caller's {@code ||}
   * turns that into one more line. With no CLI package configured it prints that reports were
   * skipped and succeeds — a deployment with the CLI switched off has nothing to fail at.
   */
  private static String reportHook(CiStepDecl step) {
    StringBuilder out = new StringBuilder();
    out.append("# qits-ci report hook (qits-754). $1 is the declared script's exit code.\n");
    out.append("set -u\n");
    out.append("if [ -z \"${QITS_ARTIFACTS_CLI_PACKAGE:-}\" ]; then\n");
    out.append("  echo \"qits-ci: reports skipped: no qits CLI configured\" >&2\n");
    out.append("  exit 0\n");
    out.append("fi\n");
    cliFetch(out, step, true);
    out.append(REPORT_CLI_DIR)
        .append("/qits ci report submit --exit-code \"$1\"\n");
    return out.toString();
  }

  /**
   * The qits CLI download, one text for both its callers.
   *
   * <p><b>Hard</b> ({@code soft == false}) is the release prelude: inside {@code if
   * $QITS_ARTIFACTS_CLI_PACKAGE is set}, into {@value #CLI_DIR}, a missing fetcher {@code exit 1}s
   * the step, and the binary goes on {@code PATH}. Its bytes are what the release goldens hold.
   *
   * <p><b>Soft</b> is the QA report hook's: top level of the hook file (the package check is the
   * hook's own), into {@value #REPORT_CLI_DIR}, and every failure — no version, no directory, no
   * fetcher, a failed download, no chmod — echoes what happened and exits the HOOK non-zero. The
   * bearer is a named variable spent through {@code ${v:+…}} rather than {@code set --}, because
   * the hook's {@code $1} is the exit code it reports.
   */
  private static void cliFetch(StringBuilder out, CiStepDecl step, boolean soft) {
    String dir = soft ? REPORT_CLI_DIR : CLI_DIR;
    // The store is qits-artifacts' public name, code under the domain (qits-731): no URL
    // variable is read, so nothing in a step's environment decides where its CLI comes from.
    String cliUrl =
        " \"" + CLI_DOWNLOAD_BASE + "$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"";
    String noFetcher =
        shellQuote(
            "qits-ci: the image for this step ("
                + step.image()
                + ") has neither curl nor wget, so the qits CLI cannot be fetched into it —"
                + (soft
                    ? " add one to the image to get its reports submitted"
                    : " add one to the image, or take the qits calls out of this step"));
    if (soft) {
      out.append("if [ -z \"${QITS_ARTIFACTS_CLI_VERSION:-}\" ]; then\n");
      out.append(
          "  echo \"qits-ci: the qits CLI package is configured but no version was injected; the"
              + " qits-ci that launched this step predates the CLI pin\" >&2\n");
      out.append("  exit 1\n");
      out.append("fi\n");
      out.append("mkdir -p ")
          .append(dir)
          .append(" || { echo \"qits-ci: could not create ")
          .append(dir)
          .append("\" >&2; exit 1; }\n");
      // The edge answers an anonymous read with a 401, so the bearer is this run's token, as in
      // the release prelude — held in a NAMED variable: `${v:+--header} ${v:+"$v"}` is two words
      // with a token and none without, on dash, bash and busybox ash alike.
      out.append("qits_cli_bearer=\n");
      out.append("if [ -n \"${QITS_TOKEN:-}\" ]; then\n");
      out.append("  qits_cli_bearer=\"Authorization: Bearer $QITS_TOKEN\"\n");
      out.append("fi\n");
      String header = " ${qits_cli_bearer:+--header} ${qits_cli_bearer:+\"$qits_cli_bearer\"}";
      String failed =
          " || { echo \"qits-ci: could not fetch $QITS_ARTIFACTS_CLI_PACKAGE"
              + " $QITS_ARTIFACTS_CLI_VERSION\" >&2; exit 1; }\n";
      out.append("if command -v curl > /dev/null 2>&1; then\n");
      out.append("  curl -fsSL --retry 2 --retry-delay 2")
          .append(header)
          .append(" -o ")
          .append(dir)
          .append("/qits")
          .append(cliUrl)
          .append(failed);
      out.append("elif command -v wget > /dev/null 2>&1; then\n");
      out.append("  wget -q")
          .append(header)
          .append(" -O ")
          .append(dir)
          .append("/qits")
          .append(cliUrl)
          .append(failed);
      out.append("else\n");
      out.append("  echo ").append(noFetcher).append(" >&2\n");
      out.append("  exit 1\n");
      out.append("fi\n");
      out.append("chmod +x ")
          .append(dir)
          .append("/qits || { echo \"qits-ci: could not make ")
          .append(dir)
          .append("/qits executable\" >&2; exit 1; }\n");
      out.append(
          "echo \"qits-ci: fetched $QITS_ARTIFACTS_CLI_PACKAGE $QITS_ARTIFACTS_CLI_VERSION\""
              + " >&2\n");
      return;
    }
    out.append("if [ -n \"${QITS_ARTIFACTS_CLI_PACKAGE:-}\" ]; then\n");
    // Hard on the version, and the message names the real cause. The launcher's constant cannot be
    // blank (PlatformAccessCliBinary refuses that at class-init) and the variable is always sent,
    // so the only way to be inside this branch without one is a qits-ci older than the pin having
    // launched this step — which a re-run against a current qits-ci fixes.
    out.append(
        "  : \"${QITS_ARTIFACTS_CLI_VERSION:?the qits CLI package is configured but no version was"
            + " injected; the qits-ci that launched this step predates the CLI pin}\"\n");
    out.append("  mkdir -p ").append(dir).append('\n');
    // curl, then wget, then a refusal that names the image. An image with neither is a real
    // shape in the fleet's neighbourhood — docker:28-dind has wget and no curl — and the one
    // thing it must not produce is `curl: not found` from a line nobody can see the reason for.
    // Only the FETCH degrades: the CLI itself is a static binary, so everything downstream of
    // this block, the postlude's `qits artifacts publish` included, is unaffected by which arm
    // ran.
    // The download goes through the public edge, which answers an anonymous read with a 401.
    // `$QITS_TOKEN` is this run's ci-run token, exactly as `StepContainerSettings.BOOTSTRAP` reads
    // it for the daemon binary's own download, and the idiom is the same: a local `set --` builds
    // the bearer header as a positional list, spent as `"$@"` on both arms and never interpolated
    // into the url. `set --` is safe here because nothing else the release prelude, the declared
    // script's invocation or the postlude emits reads `$@`/`$1`/`$2` — check that before adding a
    // second such block. (The QA report hook's `$1` is its own file's, which is why the soft arm
    // above uses a named variable instead.) With no token `"$@"` expands to nothing.
    out.append("  set --\n");
    out.append("  if [ -n \"${QITS_TOKEN:-}\" ]; then\n");
    out.append("    set -- --header \"Authorization: Bearer $QITS_TOKEN\"\n");
    out.append("  fi\n");
    out.append("  if command -v curl > /dev/null 2>&1; then\n");
    out.append("    curl -fsSL --retry 2 --retry-delay 2 \"$@\" -o ")
        .append(dir)
        .append("/qits")
        .append(cliUrl)
        .append('\n');
    out.append("  elif command -v wget > /dev/null 2>&1; then\n");
    out.append("    wget -q \"$@\" -O ").append(dir).append("/qits").append(cliUrl).append('\n');
    out.append("  else\n");
    out.append("    echo ").append(noFetcher).append(" >&2\n");
    out.append("    exit 1\n");
    out.append("  fi\n");
    out.append("  chmod +x ").append(dir).append("/qits\n");
    out.append("  ln -sf ").append(dir).append("/qits ").append(dir).append("/qits-publish\n");
    out.append(
        "  echo \"qits-ci: fetched $QITS_ARTIFACTS_CLI_PACKAGE $QITS_ARTIFACTS_CLI_VERSION\""
            + " >&2\n");
    out.append("  PATH=\"").append(dir).append(":$PATH\"\n");
    out.append("  export PATH\n");
    out.append("fi\n");
  }

  /**
   * One entry's SBOM submit on one step: <b>every release step carries it</b>, guarded on the
   * declared file being there, because the composer cannot see which step writes it — an image or
   * daemon SBOM comes out of a {@code build: true} step, a jar's out of the last step that runs maven
   * — and no single step reaches both. A re-submit is idempotent (qits-artifacts answers {@code
   * alreadyPublished}, first write wins), so a file two steps both hold costs a second PUT and
   * nothing else.
   *
   * <p>An {@code if-changed} entry is the exception: it is submitted only on the publishing step,
   * and only after its publish answered {@code published <v>}. An {@code unchanged since <v>}
   * published nothing at {@code $QITS_VERSION}, so an SBOM PUT there would describe a version that
   * does not exist — and an earlier step cannot know the answer yet. The publish itself already read
   * the file ({@code --sbom}, the hash covers it), so that step holding it is not a guess.
   */
  private static void sbomSubmit(
      StringBuilder out, SlotArtifact artifact, int index, boolean publishing) {
    if (!artifact.hasSbom()) {
      return;
    }
    String type = quote(artifact.artifact().type().declared());
    String name = quote(artifact.artifact().name());
    String file = quote(artifact.sbomPath());
    if (artifact.artifact().publishIfChanged()) {
      if (publishing) {
        out.append("case \"$")
            .append(publishedVariable(index))
            .append("\" in published\\ *) qits artifacts publish sbom submit --type ")
            .append(type)
            .append(" --name ")
            .append(name)
            .append(" --version \"$QITS_VERSION\" --file ")
            .append(file)
            .append(" ;; esac\n");
      }
      return;
    }
    out.append("if [ -f ").append(file).append(" ]; then\n");
    out.append("  qits artifacts publish sbom submit --type ")
        .append(type)
        .append(" --name ")
        .append(name)
        .append(" \\\n");
    out.append("    --version \"$QITS_VERSION\" --file ").append(file).append('\n');
    out.append("fi\n");
  }

  /**
   * One entry's presence check, on the <b>last</b> release step only: after every step that could
   * have produced the declared SBOM has run and submitted it, the store must hold it at {@code
   * $QITS_VERSION}, or the step fails naming the entry and the path. This is what turns the per-step
   * {@code [ -f … ]} guard from "silently nothing" into "nothing, loudly, before the release goes
   * green".
   *
   * <p>{@code qits artifacts publish exists sbom <packageType>/<packageName> <version>} is the CLI's
   * one existence question for an SBOM; exit 1 is absent and exit 2 is "could not ask", and both
   * fail the step — a release whose SBOM nobody can confirm is not one to call green.
   *
   * <p>An {@code if-changed} entry is checked only when its publish answered {@code published <v>}:
   * {@code unchanged since <v>} published nothing at this version, so there is nothing to find.
   */
  private static void sbomPresence(
      StringBuilder out,
      SlotArtifact artifact,
      int index,
      boolean publishing,
      String artifactsPath) {
    if (!artifact.hasSbom()) {
      return;
    }
    String type = artifact.artifact().type().declared();
    String name = artifact.artifact().name();
    String check =
        "qits artifacts publish exists sbom "
            + quote(type + "/" + name)
            + " \"$QITS_VERSION\" \\\n"
            + "  || { echo "
            + shellQuote(
                artifactsPath
                    + " declares sbom: "
                    + artifact.sbomPath()
                    + " for "
                    + type
                    + " "
                    + name
                    + ", and no release step produced it")
            + " >&2; exit 1; }\n";
    if (artifact.artifact().publishIfChanged()) {
      if (!publishing) {
        return;
      }
      out.append("case \"$")
          .append(publishedVariable(index))
          .append("\" in published\\ *)\n");
      block(out, check, "  ");
      out.append("  ;;\n");
      out.append("esac\n");
      return;
    }
    out.append(check);
  }

  /** The shell variable an {@code if-changed} entry's publish answer is kept in. */
  private static String publishedVariable(int index) {
    return "qits_published_" + index;
  }

  /**
   * The publish calls, in the order the class javadoc states. An {@code if-changed} entry's answer
   * is captured — {@code var=$(…)} on its own line still fails the step under {@code set -e} on a
   * non-zero exit — echoed, and read by its SBOM submit; every other call simply runs.
   */
  private static void publishBlock(StringBuilder out, Postlude postlude) {
    List<SlotArtifact> artifacts = postlude.artifacts();
    Map<String, String> mavenByArtifactId = new HashMap<>();
    for (SlotArtifact artifact : artifacts) {
      if (artifact.artifact().type() == CiArtifact.Type.MAVEN) {
        mavenByArtifactId.put(
            CiReleaseSlotParser.artifactId(artifact.artifact().name()), artifact.artifact().name());
      }
    }
    for (int i : publishOrder(artifacts)) {
      SlotArtifact artifact = artifacts.get(i);
      StringBuilder call = new StringBuilder("qits artifacts publish ");
      call.append(artifact.artifact().type().declared())
          .append(" --name ")
          .append(quote(artifact.artifact().name()))
          .append(" --path ")
          .append(quote(artifact.path()));
      if (artifact.hasSbom()) {
        call.append(" --sbom ").append(quote(artifact.sbomPath()));
      }
      for (String glob : artifact.include()) {
        call.append(" --include ").append(quote(glob));
      }
      for (String link : artifact.link()) {
        call.append(" --link ").append(quote(mavenByArtifactId.get(link)));
      }
      if (artifact.artifact().publishIfChanged()) {
        call.append(" --if-changed");
      }
      call.append(" --version \"$QITS_VERSION\"");
      if (artifact.artifact().publishIfChanged()) {
        out.append(publishedVariable(i)).append("=$(").append(call).append(")\n");
        out.append("echo \"$").append(publishedVariable(i)).append("\"\n");
      } else {
        out.append(call).append('\n');
      }
    }
    CiContracts contracts = postlude.contracts();
    if (contracts != null) {
      for (CiContracts.Package contract : postlude.contractPackages()) {
        out.append("qits artifacts publish contract --kind ")
            .append(quote(contract.kind().declared()))
            .append(" --ecosystem ")
            .append(quote(contract.ecosystem().declared()))
            .append(" --name ")
            .append(quote(contract.name()))
            .append(" --application ")
            .append(quote(contracts.application()));
        if (!contract.provider().isEmpty()) {
          out.append(" --provider ").append(quote(contract.provider()));
        }
        out.append(" --from ")
            .append(quote(contract.from()))
            .append(" --version \"$QITS_VERSION\"\n");
      }
      if (contracts.goldenMasters() != null) {
        out.append("qits artifacts publish contract-docs --application ")
            .append(quote(contracts.application()))
            .append(" --from ")
            .append(quote(contracts.goldenMasters().from()));
        for (CiContracts.Package contract : contracts.goldenMasterPackages()) {
          out.append(" --package ")
              .append(quote(contract.ecosystem().declared() + "=" + contract.name()));
        }
        out.append(" --version \"$QITS_VERSION\"").append(META).append('\n');
      }
    }
    for (SlotArtifact artifact : artifacts) {
      if (artifact.publishesApidocs()) {
        out.append("qits artifacts publish docs submit --site ")
            .append(quote(artifact.artifact().name()))
            .append(" --openapi ")
            .append(quote(artifact.path()))
            .append(" --version \"$QITS_VERSION\"")
            .append(META)
            .append('\n');
      }
    }
  }

  /** The provenance a docs bundle is published with, read from the step's own environment. */
  private static final String META =
      " --meta git.commit.hash=\"$QITS_CI_SHA\" --meta git.repository.name=\"$QITS_CI_REPO_NAME\"";

  /**
   * The indices of the maven and npm entries in publish order: <b>a linked sibling before every
   * entry linking it</b>, and declared order wherever {@code link:} says nothing. Each round takes
   * the earliest-declared entry whose links are all decided, so the order is stable. The parser has
   * already refused a cycle and a link to anything but another maven entry of the same file.
   */
  static List<Integer> publishOrder(List<SlotArtifact> artifacts) {
    List<Integer> pending = new ArrayList<>();
    for (int i = 0; i < artifacts.size(); i++) {
      if (artifacts.get(i).uploaded()) {
        pending.add(i);
      }
    }
    Set<String> decided = new HashSet<>();
    List<Integer> order = new ArrayList<>();
    while (!pending.isEmpty()) {
      Integer next = null;
      for (Integer candidate : pending) {
        if (decided.containsAll(artifacts.get(candidate).link())) {
          next = candidate;
          break;
        }
      }
      if (next == null) {
        throw new IllegalStateException(
            "link: forms a cycle the parser should have refused: " + pending);
      }
      pending.remove(next);
      order.add(next);
      SlotArtifact chosen = artifacts.get(next);
      if (chosen.artifact().type() == CiArtifact.Type.MAVEN) {
        decided.add(CiReleaseSlotParser.artifactId(chosen.artifact().name()));
      }
    }
    return order;
  }

  /**
   * The declared script, ready to sit inside a quoted heredoc: verbatim, newline-terminated, and
   * refused outright when it carries the delimiter.
   */
  /**
   * <b>The lockfile origin check</b> (qits-731): a POSIX {@code sh} script that fails, naming the
   * file and up to five entries, when any {@code package-lock.json} under the working directory pins
   * a {@code resolved} URL outside {@code https://registry.qits.$QITS_DOMAIN/} and {@code
   * https://mirror.qits.$QITS_DOMAIN/}.
   *
   * <p><b>{@code find}, not {@code git ls-files}</b>, because a submodule's files are not tracked by
   * the repository that holds the gitlink: a lockfile under {@code service/src/main/webui} is in the
   * tree once the submodule is materialised and in no index the parent can list. {@code
   * node_modules} is skipped — an installed package's own lockfile is that package's business.
   *
   * <p><b>Only a value carrying {@code ://} is an address.</b> npm also writes {@code resolved} for a
   * workspace or {@code file:} link, as a relative path; that names no host and is left alone. A
   * {@code git+https://} or {@code git+ssh://} entry is an address outside both registries and is
   * refused like any other.
   *
   * <p><b>grep, sed and awk, nothing else</b> — the tools every step image has, docker:28-dind's
   * busybox included, which is the floor the class javadoc sets for the prelude. No {@code node}:
   * an image that installs nothing has none, and must still be able to run this and pass. {@code
   * grep -o} rather than a line-anchored read, so a lockfile written on one line is read the same.
   * The prefixes are compared with awk's {@code index}, never a regex, so the dots in a domain
   * match only dots.
   *
   * <p>Package-private for {@code CiReleaseComposerTest}, which runs it under {@code sh} against a
   * good lockfile and a bad one.
   */
  static String lockfileOriginCheck() {
    return """
        # qits-ci: every npm lockfile here resolves from the platform's two registries (qits-731).
        set -eu
        : "${QITS_DOMAIN:?this step was told no QITS_DOMAIN, so no lockfile origin can be checked}"
        registry="https://registry.qits.$QITS_DOMAIN/"
        mirror="https://mirror.qits.$QITS_DOMAIN/"
        find . -name package-lock.json ! -path '*/node_modules/*' | {
          refused=0
          while IFS= read -r lock; do
            foreign=$(grep -o '"resolved": *"[^"]*"' "$lock" \\
              | sed -e 's/^"resolved": *"//' -e 's/"$//' \\
              | awk -v r="$registry" -v m="$mirror" \\
                'index($0, "://") && index($0, r) != 1 && index($0, m) != 1')
            if [ -n "$foreign" ]; then
              count=$(printf '%s\\n' "$foreign" | wc -l | tr -d ' ')
              echo "qits-ci: $lock resolves $count package(s) outside $registry and $mirror:" >&2
              printf '%s\\n' "$foreign" | head -n 5 | sed 's/^/  /' >&2
              refused=1
            fi
          done
          if [ "$refused" -ne 0 ]; then
            echo "qits-ci: no lockfile is rewritten on this platform. Regenerate it against the" \\
              "public registries (npm_config_registry=${mirror}npm/npmjs/," \\
              "npm_config_@qits:registry=${registry}artifacts/npm/npm/) and commit it." >&2
            exit 1
          fi
        }
        """;
  }

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
