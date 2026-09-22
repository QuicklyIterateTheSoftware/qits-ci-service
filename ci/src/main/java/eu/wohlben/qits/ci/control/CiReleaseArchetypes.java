package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigSource.FileLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Reads one archetype recipe out of the platform-pipelines repository.
 *
 * <p><b>The whole point of the feature lives here.</b> A recipe is
 * {@code .config/qits/release-archetypes/<name>.yml} in the wrapper repository, read at <b>the sha
 * of the newest version that repository has RELEASED</b> — the same repository and the same content
 * route the {@code ci-platform-event-*.yml} files already use, at a different revision.
 *
 * <p><b>A released version, not {@code main}'s head, and that is the rule rather than a tuning.</b>
 * These recipes contribute most of the steps of every release pipeline on the platform, so reading
 * them at {@code main} meant the steps of every release came from whatever landed on the wrapper a
 * minute ago — content nobody gated and nobody approved. A released wrapper version is the opposite
 * in every respect: it is a {@code YYYY.MMDD.HHMMSS} tag that a release request carried, CI gated
 * and a person approved, and it is immutable. Owner ruling, stated repeatedly: nothing in a release
 * pipeline may come from "whatever is on main".
 *
 * <p><b>What it costs is that a new recipe is not usable until a wrapper RELEASE carries it.</b>
 * A change to the release cycle used to be one wrapper commit, effective on the next event; it is
 * one wrapper commit plus the wrapper's own release request, effective when that release lands.
 * That is the whole of the trade and it is the point of it — the estate's release pipelines now
 * move only when somebody approves that they should.
 *
 * <p>What reading at a sha buys, on top of that, is the discipline the repository half has always
 * had — resolve once, read at what was resolved. {@code CiEventTriggerService} resolves the
 * wrapper's newest released version once per evaluation; every archetype read of that evaluation is
 * made at that version's sha, so twenty repositories on one archetype cannot be composed from two
 * different recipes because a release landed in between.
 *
 * <p><b>Which repository is not this class's to know.</b> {@code CiEventTriggerService} already
 * resolves {@code qits.ci.platform-pipelines-repository} against the candidate catalogue for the
 * platform pass, and hands the resolved reference here. A second injection point for the same key
 * would be a second thing to arm in a test and a second thing to keep in step.
 *
 * <p><b>Every failure is a WARN and an empty answer</b> — the engine's standing rule that an
 * unreadable candidate is skipped rather than run. A repository asking for a recipe that is not
 * there, or one the wrapper could not be read for, gets no release run at all; it does <em>not</em>
 * get a composition with the archetype silently missing, which would be a release pipeline that ran
 * a prelude and published nothing.
 */
@ApplicationScoped
public class CiReleaseArchetypes {

  private static final Logger LOG = Logger.getLogger(CiReleaseArchetypes.class);

  @Inject CiReleaseSlotParser slotParser;

  @Inject CiConfigSource configSource;

  /**
   * <b>Which</b> recipe a composed pipeline was built from, with no bytes attached: the name the
   * repository asked for, the file in the wrapper it came from, the revision it was read at, and
   * the released wrapper version that revision IS.
   *
   * <p>It is a record of its own rather than four loose strings because they only mean anything
   * together — a name without a rev says which recipe but not which version of it, and a rev
   * without a name says nothing at all — and because they travel a long way: out of this class,
   * through the composition, onto {@code ci_run}'s four columns and out to the API. All four are
   * null together on a composed run whose slot file names no archetype, which is a legitimate shape
   * ({@link CiReleaseSlots#namesArchetype()}) and never "unknown".
   *
   * <p><b>{@code version} is the legible half of {@code rev} and neither replaces the other.</b>
   * The sha is what was really read, and it is the only value that can be checked out again; the
   * version is the name a person approved, which is what anybody reading a run row or a release
   * request is actually holding. Recording only the sha would make "which wrapper release composed
   * this" a question answerable solely by asking the git host to name a commit's tags, which it
   * does not do.
   */
  public record ArchetypeRef(String name, String configPath, String rev, String version) {}

  /** One recipe: which one it is (above), and what it declares. */
  public record Archetype(
      String name, String configPath, String rev, String version, CiReleaseSlots slots) {

    /** The identity half, for a caller that wants to record what was used rather than use it. */
    public ArchetypeRef ref() {
      return new ArchetypeRef(name, configPath, rev, version);
    }
  }

  /**
   * The recipe {@code name} names, or empty when there is none to be had.
   *
   * @param platformRepo the platform-pipelines repository, resolved against the catalogue, or null
   *     when this deployment declares none or the catalogue does not hold it
   * @param released the wrapper's newest released version, resolved once for this evaluation — its
   *     sha is what the recipe is read at, never a branch name and never a moving ref
   * @param name the archetype the repository's slot file asked for
   */
  public Optional<Archetype> read(
      CiRepoRef platformRepo, CiReleasedVersions.ReleasedVersion released, String name) {
    String rev = released == null ? null : released.sha();
    String version = released == null ? null : released.version();
    if (platformRepo == null) {
      LOG.warnf(
          "A repository asks for release archetype '%s', but this deployment has no"
              + " platform-pipelines repository in its catalogue — no release run",
          name);
      return Optional.empty();
    }
    if (!CiReleaseSlotParser.isArchetypeName(name)) {
      // Belt and braces: the parser refuses this shape already, so reaching here means a caller
      // built a name some other way. The path is a URL segment against another repository.
      LOG.warnf("Release archetype '%s' is not a name this qits-ci will read — no release run", name);
      return Optional.empty();
    }
    if (rev == null) {
      // NO RELEASED VERSION, NO READ. The caller states this case in its own terms before it gets
      // here (CiEventTriggerService.attemptCompose), and this is the belt: reading at a null rev
      // would build a url with the literal "null" in it, and there is no other revision this could
      // fall back to that a person has approved.
      LOG.warnf(
          "Release archetype '%s' cannot be read: %s has no released version to read it at — no"
              + " release run",
          name, platformRepo.display());
      return Optional.empty();
    }
    String path = CiReleaseSlotParser.archetypePath(name);
    FileLookup found = configSource.readFile(platformRepo, rev, path);
    if (found.status() != FileLookup.Status.FOUND) {
      LOG.warnf(
          "Release archetype '%s' could not be read from %s at %s (%s) (%s: %s) — no release run",
          name, platformRepo.display(), version, rev, path, found.status());
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Archetype(
              name, path, rev, version, slotParser.parseArchetype(path, found.content())));
    } catch (CiConfigException e) {
      // Loud and per recipe: one broken archetype must not be readable as "this repository declares
      // nothing", and it must not take the repositories on other archetypes down with it either.
      LOG.warnf(
          "Release archetype '%s' (%s in %s) is not a usable recipe: %s — no release run",
          name, path, platformRepo.display(), e.getMessage());
      return Optional.empty();
    }
  }
}
