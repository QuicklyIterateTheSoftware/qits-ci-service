package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.RepoTag;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which tag is "the newest released version", exhaustively and with no git host.
 *
 * <p>{@link CiReleasedVersions} is pure, so every case that decides which bytes compose the estate's
 * release pipelines can be stated here as data — the reason {@code VersionSortTest} and {@code
 * CiEventSelectionEvaluatorTest} are what they are. The engine's use of the answer is {@code
 * CiReleaseSlotTriggerTest}'s; what is here is the judgement itself.
 */
public class CiReleasedVersionsTest {

  private static RepoTag tag(String name) {
    return new RepoTag(name, "a".repeat(40));
  }

  @Test
  public void aPlatformReleaseStampIsAReleasedVersion() {
    assertTrue(CiReleasedVersions.isReleasedVersion("2026.922.161358"));
    // The two fields that lose a leading zero, which is what the real tags look like.
    assertTrue(CiReleasedVersions.isReleasedVersion("2026.922.34556"));
    assertTrue(CiReleasedVersions.isReleasedVersion("2026.1231.5"));
  }

  @Test
  public void anythingElseIsNot() {
    // Every one of these is a tag somebody can push, and admitting any of them would let an
    // unreviewed name decide which recipe composes every release on the platform.
    assertFalse(CiReleasedVersions.isReleasedVersion("latest"));
    assertFalse(CiReleasedVersions.isReleasedVersion("v2026.922.161358"));
    assertFalse(CiReleasedVersions.isReleasedVersion("2026.922.161358-rc1"));
    assertFalse(CiReleasedVersions.isReleasedVersion("release/2026.922.161358"));
    assertFalse(CiReleasedVersions.isReleasedVersion("26.9.1"));
    assertFalse(CiReleasedVersions.isReleasedVersion(""));
    assertFalse(CiReleasedVersions.isReleasedVersion(null));
  }

  @Test
  public void theNewestIsTheHighestVERSIONAndNotTheHighestSTRING() {
    // The whole reason VersionSort exists, restated where it now decides an estate-wide input:
    // "161358" sorts BEFORE "98" as text, and after it as a number.
    Optional<CiReleasedVersions.ReleasedVersion> newest =
        CiReleasedVersions.newest(List.of(tag("2026.922.98"), tag("2026.922.161358")));

    assertEquals("2026.922.161358", newest.orElseThrow().version());
  }

  @Test
  public void theNewestIgnoresTagsThatAreNotReleasedVersions() {
    Optional<CiReleasedVersions.ReleasedVersion> newest =
        CiReleasedVersions.newest(
            List.of(tag("zzz-latest"), tag("2026.921.85624"), tag("v99"), tag("2026.922.34556")));

    assertEquals("2026.922.34556", newest.orElseThrow().version());
  }

  @Test
  public void theShaTravelsWithTheVersionBecauseThatIsWhatIsReadAt() {
    Optional<CiReleasedVersions.ReleasedVersion> newest =
        CiReleasedVersions.newest(
            List.of(
                new RepoTag("2026.921.85624", "b".repeat(40)),
                new RepoTag("2026.922.161358", "c".repeat(40))));

    assertEquals("c".repeat(40), newest.orElseThrow().sha());
  }

  @Test
  public void aTagWhoseShaIsNotAnObjectIdIsDroppedRatherThanSpent() {
    // The sha becomes a path segment in a git-host read, so a value that could not be one is not a
    // candidate at all — and dropping it rather than throwing is what keeps one malformed
    // advertisement line from costing the estate its recipe. The older tag still answers.
    Optional<CiReleasedVersions.ReleasedVersion> newest =
        CiReleasedVersions.newest(
            List.of(
                new RepoTag("2026.922.161358", "../../etc/passwd"),
                new RepoTag("2026.921.85624", "d".repeat(40))));

    assertEquals("2026.921.85624", newest.orElseThrow().version());
  }

  @Test
  public void noReleasedVersionIsAnEmptyAnswerAndNeverAGuess() {
    // A fresh estate's wrapper: tags, none of them a release. Answering anything here — the newest
    // tag whatever it is, or main — would compose a release pipeline out of content nobody approved.
    assertTrue(CiReleasedVersions.newest(List.of()).isEmpty());
    assertTrue(CiReleasedVersions.newest(List.of(tag("nightly"), tag("latest"))).isEmpty());
    assertTrue(CiReleasedVersions.newest(null).isEmpty());
  }
}
