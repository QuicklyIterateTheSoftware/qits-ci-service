package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * One parsed {@code .config/qits/release.yml} — or one archetype recipe, which is the same document
 * minus {@code archetype:}.
 *
 * <p><b>It is not a trigger file and must never become one.</b> A trigger file names its own event,
 * its own selection and its own checkout; those three are identical in all 78 release files of the
 * fleet, which is the measurement that says the platform may own them. What is left is
 * configuration: which recipe, what to run for a release request, what to run for a release, what
 * gets published, and whether the repository has userflows. {@link CiReleaseComposer} turns this
 * plus an archetype into two ordinary trigger documents.
 *
 * <p><b>Every slot is nullable or empty and that is the whole grammar.</b> A repository that
 * declares nothing but {@code archetype: spa-frontend} is one line; the archetype supplies both
 * step lists. A repository that declares a slot <b>replaces the archetype's entirely</b> — whole
 * slot, never per-step merging, because a merge order is a thing nobody can read off a file.
 *
 * @param configPath the file this came from, carried for error messages and for the run row
 * @param archetype the recipe this repository asks for, or {@code ""} when it names none
 * @param releaseRequest the QA steps, or null when this document declares none
 * @param release the release steps, or null when this document declares none
 * @param artifacts what a green release run publishes, empty when the document declares none
 * @param userflows the userflow declaration, or null when the document declares none
 */
public record CiReleaseSlots(
    String configPath,
    String archetype,
    CiPipeline releaseRequest,
    CiPipeline release,
    List<SlotArtifact> artifacts,
    Userflows userflows) {

  public CiReleaseSlots {
    artifacts = List.copyOf(artifacts);
  }

  /**
   * One {@code artifacts:} entry: the declaration the composed trigger document carries verbatim,
   * plus the optional path to the CycloneDX document the release step produced for it.
   *
   * <p>The <b>sbom path never reaches the composed {@code artifacts:} block</b> — that block is the
   * existing trigger schema and is strict about unknown keys. It is spent at composition time, on
   * one {@code qits-publish sbom submit} line in the platform postlude. Which is the point: today
   * every repository derives an SBOM base URL by string-chopping some other variable and writes its
   * own PUT, and after this the path is the only thing a repository still says about it.
   *
   * @param artifact the {@code {type, name}} declaration, exactly as a trigger file spells it
   * @param sbomPath the repository-relative path to the generated document, {@code ""} when none
   */
  public record SlotArtifact(CiArtifact artifact, String sbomPath) {

    public boolean hasSbom() {
      return !sbomPath.isEmpty();
    }
  }

  /**
   * The {@code userflows:} declaration — {@code true}, or the site name the bundle is published
   * under.
   *
   * <p>It exists to replace a <em>grep</em>: qits-projects' {@code ReleaseArtifacts} reads a
   * repository's QA recipe and looks for the substring {@code @userflows/<site>} to decide whether a
   * release carries a userflow bundle. That is a search for a string inside a shell script, and it
   * stops working the moment the script is composed rather than committed. Declared here, the same
   * fact is data.
   *
   * <p><b>The composer emits no step for it</b>, deliberately: how a userflow bundle is built and
   * uploaded is the archetype's business (a {@code gating: false} step, last), and inventing one
   * here would be a guess Phase 3 has to undo. What this key is for is the reader on the other side
   * of the release — see the plan's Phase 4.
   *
   * @param site the site name, or {@code ""} for {@code userflows: true} — "the repository's own
   *     name", which only the reader can resolve, since it holds the repository and this document
   *     does not
   */
  public record Userflows(String site) {

    /** Whether the site name is the repository's own, rather than one this document spells. */
    public boolean derived() {
      return site.isEmpty();
    }
  }

  /** Whether this document asks for a wrapper recipe. */
  public boolean namesArchetype() {
    return !archetype.isEmpty();
  }
}
