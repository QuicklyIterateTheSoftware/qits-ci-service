package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigSource.RepoTag;
import java.util.List;
import java.util.Optional;

/**
 * Which of a repository's tags are <b>released versions</b>, and which of those is the newest.
 *
 * <p><b>This is the whole of what "the newest released version" means here</b>, and it is a pure
 * function over a tag listing: no IO, no clock, no CDI, exactly like {@link VersionSort} and {@link
 * CiEventSelectionEvaluator} beside it. That matters because the answer decides which bytes compose
 * every release pipeline on the platform, and a decision of that reach has to be exhaustively
 * testable without a git host.
 *
 * <p><b>A release is a TAG, and the platform's release stamp is CalVer.</b> qits-projects cuts
 * {@code refs/tags/<version>} in the same operation that finalizes a release request, and that
 * version is a {@code YYYY.MMDD.HHMMSS} whose middle and last fields carry no leading zero ({@code
 * 2026.922.34556} is the 22nd of September at 03:45:56). {@link #RELEASED_VERSION} is that shape and
 * nothing else — so a {@code v1.2.3}, a {@code latest}, a {@code release/2026.922.34556} and a
 * branch-shaped tag are all simply not released versions here. <b>Narrow on purpose</b>: this
 * decides what an unreviewed tag can do, and a repository can push a tag of any name it likes, so a
 * permissive reading would let {@code zzz} out-sort every real release and pin the whole estate's
 * recipes to it.
 *
 * <p><b>Newest is {@link VersionSort}'s order</b>, which is the comparator the release path already
 * uses to tell two versions apart ({@code CiRunService.supersedeByVersion}). A second ordering for
 * the same vocabulary would be a second thing to get backwards, and getting it backwards here is
 * every release on the platform composing from a recipe somebody superseded.
 *
 * <p><b>A tag whose commit is not a plain object id is dropped rather than refused.</b> The sha
 * becomes a path segment in a git-host read, and the standing rule for anything arriving over a wire
 * is that it is checked before it can reach a URL. Dropping the entry rather than throwing is the
 * candidate-list rule applied one seam over: one malformed advertisement line must not cost the
 * estate its recipe, and the remaining tags still answer the question honestly.
 */
public final class CiReleasedVersions {

  /**
   * The platform's release stamp: {@code YYYY.MMDD.HHMMSS} with no leading zeros on the last two
   * fields, so {@code 2026.922.34556} and {@code 2026.1231.161358} are both this shape.
   *
   * <p>Bounded at both ends by the anchors {@link String#matches} applies, so nothing longer, nothing
   * with a prefix and nothing with a suffix is a released version.
   */
  static final String RELEASED_VERSION = "[0-9]{4}\\.[0-9]{3,4}\\.[0-9]{1,6}";

  /** Same charset {@code CiIdentifiers} requires of a sha, which is where this value is spent. */
  private static final String SHA = "[0-9a-f]{7,64}";

  /**
   * One released version of a repository: the tag's name, which <b>is</b> the version, and the
   * commit it resolves to.
   *
   * <p>The two travel together for {@code CiReleaseArchetypes.ArchetypeRef}'s reason: a sha with no
   * version says which bytes but not which release, and a version with no sha is a name nothing was
   * read at.
   */
  public record ReleasedVersion(String version, String sha) {}

  private CiReleasedVersions() {}

  /** Whether one tag name is a released version of the platform's own shape. */
  public static boolean isReleasedVersion(String tagName) {
    return tagName != null && tagName.matches(RELEASED_VERSION);
  }

  /**
   * The newest released version among {@code tags}, or empty when none of them is one.
   *
   * <p><b>Empty is a real answer and never an error.</b> A repository that has never released — a
   * fresh estate's wrapper, before anybody has approved anything — carries no such tag, and the
   * caller has to decide what that means rather than being handed a default that corresponds to
   * nothing.
   */
  public static Optional<ReleasedVersion> newest(List<RepoTag> tags) {
    if (tags == null) {
      return Optional.empty();
    }
    return tags.stream()
        .filter(tag -> tag != null && isReleasedVersion(tag.name()))
        .filter(tag -> tag.commitSha() != null && tag.commitSha().matches(SHA))
        .max((a, b) -> VersionSort.compare(a.name(), b.name()))
        .map(tag -> new ReleasedVersion(tag.name(), tag.commitSha()));
  }
}
