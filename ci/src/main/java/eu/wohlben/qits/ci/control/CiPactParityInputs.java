package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The reads behind {@link CiPactParity} (qits-2465): the gated repository's pact files at the fold,
 * and its {@code mirror.repository}'s {@code release.yml} and pact files at that repository's newest
 * release. All IO, no decision — the decision is {@link CiPactParity}'s, which stays pure.
 *
 * <p><b>The counterpart's newest release</b> is its newest {@link CiScmRelease} by {@link
 * VersionSort}, matched by name or id as {@link CiReportBaselines} matches a run's own repository,
 * and it is read at {@code refs/tags/<version>} — a ref name, which the git host resolves like a
 * sha. Its storage id comes off that row; it is addressed by name in the gated repository's project
 * when the gated repository is, and by id otherwise, the same choice {@link CiRepoRef} makes for
 * every read.
 *
 * <p><b>Three answers, kept apart like {@link CiConfigSource.FileLookup}'s.</b> An {@code
 * UNREACHABLE} read anywhere is {@link Inputs#unreachable}: nothing was learned, so the caller keeps
 * the event owed rather than composing a QA run without the check. A counterpart with no release,
 * no {@code release.yml} at it, or one that does not parse is committed fact and becomes {@link
 * CiPactParity.Side#unavailable}, which the check fails on; a declared pact file the rev does not
 * carry is a null content, which it fails on too.
 */
@ApplicationScoped
public class CiPactParityInputs {

  /** The tag namespace a release version is read under. */
  static final String TAGS = "refs/tags/";

  @Inject CiConfigSource configSource;
  @Inject CiReleaseSlotParser slotParser;
  @Inject CiScmReleaseRepository scmReleases;

  /**
   * Both sides, or why they could not be read.
   *
   * @param gated the gated repository at the fold
   * @param counterpart its {@code mirror.repository} at its newest release
   * @param unreachable what could not be read, or null when everything could
   */
  public record Inputs(CiPactParity.Side gated, CiPactParity.Side counterpart, String unreachable) {

    static Inputs unreachable(String what) {
      return new Inputs(null, null, what);
    }
  }

  /**
   * Reads both sides for a gated repository that declares {@code contracts.mirror}.
   *
   * @param repo the gated repository
   * @param rev the fold, where {@code contracts} was read
   * @param contracts the gated repository's {@code contracts:} at the fold, declaring {@code
   *     mirror:}
   */
  public Inputs load(CiRepoRef repo, String rev, CiContracts contracts) {
    String repository = repo.display();
    Map<String, String> pacts = new LinkedHashMap<>();
    for (String path : CiPactParity.pactPaths(repository, contracts)) {
      CiConfigSource.FileLookup pact = configSource.readFile(repo, rev, path);
      if (pact.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
        return Inputs.unreachable(path + " could not be read at " + rev);
      }
      pacts.put(path, found(pact));
    }
    CiPactParity.Side gated =
        CiPactParity.Side.read(repository, "the fold " + rev, contracts, pacts);
    String counterpartName = contracts.mirror().repository();
    Optional<CiScmRelease> release = newestRelease(counterpartName);
    if (release.isEmpty()) {
      return new Inputs(
          gated,
          CiPactParity.Side.unavailable(
              counterpartName,
              counterpartName + " has no release this qits-ci recorded, so its pacts cannot be read"),
          null);
    }
    String version = release.get().version;
    String tag = TAGS + version;
    try {
      CiIdentifiers.requireBranch(tag);
    } catch (RuntimeException malformed) {
      return new Inputs(
          gated,
          CiPactParity.Side.unavailable(
              counterpartName,
              counterpartName + "'s newest release '" + version + "' is not a readable tag name"),
          null);
    }
    CiRepoRef counterpart =
        repo.named()
            ? CiRepoRef.of(release.get().repoId, repo.projectId(), counterpartName)
            : CiRepoRef.of(release.get().repoId);
    String at = "release " + version;
    CiConfigSource.FileLookup slotFile =
        configSource.readFile(counterpart, tag, CiReleaseSlotParser.CONFIG_PATH);
    if (slotFile.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
      return Inputs.unreachable(
          counterpartName + "'s " + CiReleaseSlotParser.CONFIG_PATH + " could not be read at " + tag);
    }
    if (slotFile.status() != CiConfigSource.FileLookup.Status.FOUND) {
      return new Inputs(
          gated,
          CiPactParity.Side.unavailable(
              counterpartName,
              counterpartName
                  + " carries no "
                  + CiReleaseSlotParser.CONFIG_PATH
                  + " at "
                  + at
                  + ", so it declares no pacts to compare"),
          null);
    }
    CiContracts theirs;
    try {
      CiReleaseSlots slots = slotParser.parse(CiReleaseSlotParser.CONFIG_PATH, slotFile.content());
      theirs = slots.contracts();
    } catch (CiConfigException e) {
      return new Inputs(
          gated,
          CiPactParity.Side.unavailable(
              counterpartName,
              counterpartName + "'s " + CiReleaseSlotParser.CONFIG_PATH + " at " + at
                  + " does not parse: " + e.getMessage()),
          null);
    }
    Map<String, String> theirPacts = new LinkedHashMap<>();
    for (String path : CiPactParity.pactPaths(counterpartName, theirs)) {
      CiConfigSource.FileLookup pact = configSource.readFile(counterpart, tag, path);
      if (pact.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
        return Inputs.unreachable(counterpartName + "'s " + path + " could not be read at " + tag);
      }
      theirPacts.put(path, found(pact));
    }
    return new Inputs(
        gated, CiPactParity.Side.read(counterpartName, at, theirs, theirPacts), null);
  }

  private static String found(CiConfigSource.FileLookup lookup) {
    return lookup.status() == CiConfigSource.FileLookup.Status.FOUND ? lookup.content() : null;
  }

  /** The counterpart's newest recorded release by {@link VersionSort}, by name or by id. */
  private Optional<CiScmRelease> newestRelease(String repository) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                scmReleases.list("repoName = ?1 or repoId = ?1", repository).stream()
                    .max(Comparator.comparing((CiScmRelease r) -> r.version, VersionSort.COMPARATOR)));
  }
}
