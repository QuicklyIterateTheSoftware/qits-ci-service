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
 * {@code .config/qits/release-archetypes/<name>.yml} in the wrapper repository, read at its {@code
 * main} on every evaluation — the same repository, the same branch and the same content route the
 * {@code ci-platform-event-*.yml} files already use. So a change to the release cycle is <b>one
 * wrapper commit</b>: no qits-ci deploy, no sweep of 47 repositories, effective on the next event.
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

  /** One recipe: the name a repository asked for, the file it came from, and what it declares. */
  public record Archetype(String name, String configPath, CiReleaseSlots slots) {}

  /**
   * The recipe {@code name} names, or empty when there is none to be had.
   *
   * @param platformRepo the platform-pipelines repository, resolved against the catalogue, or null
   *     when this deployment declares none or the catalogue does not hold it
   * @param rev the wrapper's branch or sha to read at — {@code main} on the live path
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
      return Optional.of(new Archetype(name, path, slotParser.parseArchetype(path, found.content())));
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
