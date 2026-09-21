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
 * {@code main} resolved to for this evaluation</b> — the same repository, the same branch and the
 * same content route the {@code ci-platform-event-*.yml} files already use. So a change to the
 * release cycle is <b>one wrapper commit</b>: no qits-ci deploy, no sweep of 47 repositories,
 * effective on the next event. <b>That property is unchanged by reading at a sha</b>, because the
 * sha is the one {@code main} pointed at when the evaluation began: the next event resolves the
 * wrapper's head again and sees the commit.
 *
 * <p>What reading at a sha buys is the discipline the repository half has always had — resolve
 * once, read at what was resolved. {@code CiEventTriggerService} lists the wrapper's trigger files
 * once per evaluation and the listing answers the head it resolved; every archetype read of that
 * evaluation is made at that sha, so twenty repositories on one archetype cannot be composed from
 * two different recipes because somebody pushed to the wrapper in between.
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
   * repository asked for, the file in the wrapper it came from, and the revision it was read at.
   *
   * <p>It is a record of its own rather than three loose strings because the three only mean
   * anything together — a name without a rev says which recipe but not which version of it, and a
   * rev without a name says nothing at all — and because they travel a long way: out of this class,
   * through the composition, onto {@code ci_run}'s three columns and out to the API. All three are
   * null together on a composed run whose slot file names no archetype, which is a legitimate shape
   * ({@link CiReleaseSlots#namesArchetype()}) and never "unknown".
   */
  public record ArchetypeRef(String name, String configPath, String rev) {}

  /** One recipe: which one it is (above), and what it declares. */
  public record Archetype(String name, String configPath, String rev, CiReleaseSlots slots) {

    /** The identity half, for a caller that wants to record what was used rather than use it. */
    public ArchetypeRef ref() {
      return new ArchetypeRef(name, configPath, rev);
    }
  }

  /**
   * The recipe {@code name} names, or empty when there is none to be had.
   *
   * @param platformRepo the platform-pipelines repository, resolved against the catalogue, or null
   *     when this deployment declares none or the catalogue does not hold it
   * @param rev the wrapper's branch or sha to read at — on every live path the sha the wrapper's
   *     own trigger listing resolved {@code main} to for this evaluation, never the moving ref
   * @param name the archetype the repository's slot file asked for
   */
  public Optional<Archetype> read(CiRepoRef platformRepo, String rev, String name) {
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
    String path = CiReleaseSlotParser.archetypePath(name);
    FileLookup found = configSource.readFile(platformRepo, rev, path);
    if (found.status() != FileLookup.Status.FOUND) {
      LOG.warnf(
          "Release archetype '%s' could not be read from %s@%s (%s: %s) — no release run",
          name, platformRepo.display(), rev, path, found.status());
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Archetype(name, path, rev, slotParser.parseArchetype(path, found.content())));
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
