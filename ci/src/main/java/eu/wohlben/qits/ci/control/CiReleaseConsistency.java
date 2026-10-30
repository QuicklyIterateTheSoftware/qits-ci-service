package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The release-QA consistency check (qits-1400): a fold whose {@code .config/qits/release.yml} and
 * {@code .config/qits/deployments.yml} name two different applications is refused in QA, before a
 * tag is cut.
 *
 * <h2>What it compares</h2>
 *
 * <p>The <b>application</b> is {@code deployments.yml}'s {@code application:}, or the repository's
 * name when that key is absent — the same derivation qits-deployments makes. Two declarations in
 * {@code release.yml} are bound to it:
 *
 * <ul>
 *   <li>every {@code { type: docker }} entry of {@code artifacts:} must be named {@code
 *       qits/<application>} — it is the image qits-deployments deploys as that application, so a
 *       mismatch is a release that pushes one image and deploys another;
 *   <li>{@code contracts: application:}, when present, must be {@code <application>} — it names
 *       the golden masters the deployed application's consumers verify against.
 * </ul>
 *
 * <p><b>Only what is present is compared.</b> A repository with no {@code deployments.yml} deploys
 * nothing and is skipped; a {@code release.yml} with no docker entry, or no {@code contracts:},
 * has nothing on that side to disagree with; a {@code deployments.yml} that is not a readable
 * mapping is qits-deployments' own refusal to make, not this check's.
 *
 * <h2>Where it fires, and why that is before the tag</h2>
 *
 * <p>It is evaluated when the QA half of a release cycle is composed — at the fold the request
 * announces, the commit the QA run checks out — and a refusal is composed <b>into</b> that QA
 * document ({@link CiReleaseComposer}): the first QA step prints the sentence and exits 1 before
 * anything else runs. So the request's QA run is red, the request ends REJECTED with the sentence
 * as the step's whole log, and no release is ever stamped from the fold. A composition refusal
 * ({@link CiConfigException}) was the other seam and the wrong one: it records no run at all, and a
 * release request whose CI gate waits for a verdict nobody will record hangs rather than rejects.
 *
 * <p>A pure function, like the composer: the two files' contents in, a sentence or null out.
 */
public final class CiReleaseConsistency {

  /** The deployment declaration, read at the fold beside {@link CiReleaseSlotParser#CONFIG_PATH}. */
  public static final String DEPLOYMENTS_PATH = ".config/qits/deployments.yml";

  /** The key in {@link #DEPLOYMENTS_PATH} naming the application; absent means the repository's. */
  static final String APPLICATION_KEY = "application";

  /** The namespace every application's image is pushed under. */
  static final String IMAGE_NAMESPACE = "qits/";

  private CiReleaseConsistency() {}

  /**
   * Why this fold must be refused in QA, or null when it must not.
   *
   * @param repo the repository the fold is of — its name is the application when {@code
   *     deployments.yml} names none
   * @param slots the repository's own parsed {@code release.yml} at the fold
   * @param deployments {@code deployments.yml}'s content at the fold, or null when the fold carries
   *     none
   * @return the refusal, one sentence per disagreement, or null
   */
  public static String refusal(CiRepoRef repo, CiReleaseSlots slots, String deployments) {
    if (deployments == null || slots == null) {
      return null;
    }
    Map<?, ?> root;
    try {
      root = CiConfigSchema.load(deployments, false);
    } catch (CiConfigException unreadable) {
      return null;
    }
    Object declared = root == null ? null : root.get(APPLICATION_KEY);
    boolean derived = !(declared instanceof String named) || named.isBlank();
    String application = derived ? repo.display() : ((String) declared).trim();
    String deployed =
        "deployments.yml's application is `"
            + application
            + "`"
            + (derived ? " (the repository name: it declares no `application:`)" : "");
    List<String> refusals = new ArrayList<>();
    String image = IMAGE_NAMESPACE + application;
    for (SlotArtifact artifact : slots.artifacts()) {
      if (artifact.artifact().type() == CiArtifact.Type.DOCKER
          && !image.equals(artifact.artifact().name())) {
        refusals.add(
            "release.yml's docker artifact is `"
                + artifact.artifact().name()
                + "` but "
                + deployed
                + ": they must move together ("
                + image
                + ")");
      }
    }
    CiContracts contracts = slots.contracts();
    if (contracts != null
        && contracts.application() != null
        && !application.equals(contracts.application())) {
      refusals.add(
          "release.yml's contracts application is `"
              + contracts.application()
              + "` but "
              + deployed
              + ": they must move together ("
              + application
              + ")");
    }
    return refusals.isEmpty() ? null : String.join("; ", refusals);
  }
}
