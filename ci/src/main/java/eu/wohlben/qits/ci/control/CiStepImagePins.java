package eu.wohlben.qits.ci.control;

/**
 * <b>One image reference, pinned to the bytes it names right now.</b> The seam between a recipe's
 * floating {@code :latest} and the immutable digest a run really boots.
 *
 * <p><b>The problem, in the owner's words.</b> Every recipe step on the platform names {@code
 * qits/build-images/*:latest}, and a pipeline is composed step by step from the reference as
 * written — so two steps of ONE build can resolve that tag to two different images if
 * qits-build-images-oci publishes in between. A build that verified against one toolchain and
 * published from another is a build whose green says nothing, and nothing on the run row would say
 * which happened. The fix is not a version in the recipe YAML: a version named only there gets no
 * keep from qits-platform-maintenance's pins API, and docker's GC would eventually evict the image
 * the whole estate boots from. So the tag stays in the recipe and the <b>run</b> is what fixes it,
 * once, for every step of that run.
 *
 * <p><b>The port is here and the client is in {@code service/…/registry/}</b>, the rule {@link
 * CiConfigSource} set and every seam in this package follows: {@code ci/} names no {@code
 * java.net.http} and no address.
 *
 * <h2>Four answers, and each is a different sentence about the run</h2>
 *
 * <ul>
 *   <li>{@link Status#PINNED} — the platform's own registry answered with a digest. The reference
 *       to start is {@link Pin#reference()}, and it names bytes that cannot move.
 *   <li>{@link Status#ALREADY_PINNED} — the recipe named a digest itself. Nothing is asked of any
 *       registry, and the reference is returned unchanged: a caller that "re-resolved" it would be
 *       turning somebody's deliberate pin into whatever {@code :latest} is now, which is the whole
 *       defect wearing a fix's clothes.
 *   <li>{@link Status#FOREIGN} — the reference is not this platform's to pin. {@code alpine:3},
 *       {@code docker:28-dind} and anything naming another registry are pulled from a store qits-ci
 *       holds no credential for and no address to; asking would be a second registry's availability
 *       added to every accept. Unpinned, deliberately and visibly: the run row records no pin for
 *       it, so "this reference floated" is readable rather than assumed.
 *   <li>{@link Status#UNRESOLVED} — it IS ours and the registry did not answer, or answered that it
 *       holds no such tag. <b>Nothing was learned</b>, and a run started anyway would be a build
 *       against an unknown tool. The caller refuses the run; see {@code
 *       CiRunService.StepImageUnpinned}.
 * </ul>
 *
 * <p><b>{@code UNRESOLVED} must never be collapsed into {@code FOREIGN}</b>, which is {@code
 * CiConfigSource.CommitHeld}'s {@code UNKNOWN}/{@code GONE} rule one seam over: one says "not ours
 * to pin", the other says "ours, and we could not ask". Reading the second as the first is exactly
 * how a build ends up running against whatever {@code latest} happened to be.
 */
public interface CiStepImagePins {

  /**
   * The pin for one reference. <b>Never throws</b> — an unreachable registry is {@link
   * Status#UNRESOLVED}, the same contract {@code ContainersClient} and {@code GitHostRepoListing}
   * carry, so an infrastructure blip is an answer a caller can act on rather than an exception on a
   * trigger worker.
   *
   * @param reference the step's image as it will be pulled — {@code CiStepImage.resolve}'s output,
   *     so a platform image already carries the registry host
   */
  Pin pin(String reference);

  /** Which of the four the answer is — see the interface javadoc. */
  enum Status {
    PINNED,
    ALREADY_PINNED,
    FOREIGN,
    UNRESOLVED
  }

  /**
   * One answer: what happened, the reference to start, and the sentence behind it.
   *
   * @param reference what a step should really be launched with. The digest form on {@link
   *     Status#PINNED}, the reference as given on the other three — <b>never null</b>, so a caller
   *     that ignores the status still starts the step the recipe asked for rather than nothing.
   * @param detail why, for the two statuses a person may have to act on. Null on the happy path.
   */
  record Pin(Status status, String reference, String detail) {

    public static Pin pinned(String digestReference) {
      return new Pin(Status.PINNED, digestReference, null);
    }

    public static Pin alreadyPinned(String reference) {
      return new Pin(Status.ALREADY_PINNED, reference, null);
    }

    public static Pin foreign(String reference) {
      return new Pin(Status.FOREIGN, reference, null);
    }

    public static Pin unresolved(String reference, String detail) {
      return new Pin(Status.UNRESOLVED, reference, detail);
    }

    /** Whether this answer names bytes that cannot move under the run. */
    public boolean immutable() {
      return status == Status.PINNED || status == Status.ALREADY_PINNED;
    }
  }
}
