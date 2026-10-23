package eu.wohlben.qits.ci.control;

/**
 * <b>A CI run is one lease on an execution target, and one container runs every step of it</b>
 * (epic qits-1481, "CI in a slot"). That container is {@link #IMAGE_REPOSITORY}, pinned by digest
 * when the run is accepted, with {@code qits-ci-daemon} as its process; the daemon makes a worktree
 * of the run's commit from a warm mirror on the target and runs the steps one after another. There
 * is no per-step container, no per-step image and no per-step clone any more.
 *
 * <p>Two of the pipeline grammar's keys lose their meaning with that, and this class holds the
 * exact words a run is told about each:
 *
 * <ul>
 *   <li>{@code image:} is <b>ignored</b>, wherever it appears — {@link #STEP_IMAGES_IGNORED}, said
 *       once per run. Ignored rather than refused, so the repositories that still declare it need no
 *       change in the cutover release.
 *   <li>{@code docker: true} is a {@code CONFIG_ERROR}, {@link #DOCKER_UNSUPPORTED}: there is no
 *       docker socket in a slot and no docker CLI in the image. {@code build: true} stays — the
 *       target injects {@code $BUILDKIT_HOST} into every CI lease, so a builder step needs nothing
 *       it does not already have.
 * </ul>
 */
public final class CiSlot {

  /** The one image every CI lease runs, under the platform's registry. */
  public static final String IMAGE_REPOSITORY = "qits/build-images/ci-slot";

  /**
   * The reference a run pins at accept, in the registry-host spelling every other platform image
   * reference uses ({@code CiRunService.pinStepImages}); the lease is asked for its digest under the
   * registry's public name.
   */
  public static final String IMAGE_TAG = IMAGE_REPOSITORY + ":latest";

  /** The one line a run whose pipeline declares a step image is told, once. */
  public static final String STEP_IMAGES_IGNORED =
      "step images are ignored: every step runs in " + IMAGE_REPOSITORY;

  /** Why a pipeline with a {@code docker: true} step is a {@code CONFIG_ERROR}, verbatim. */
  public static final String DOCKER_UNSUPPORTED =
      "docker steps are not supported: steps run in " + IMAGE_REPOSITORY + " without Docker";

  /** The uid every CI lease runs as: the image's {@code build} user. */
  public static final String USER = "1001";

  private CiSlot() {}
}
