package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reads behind {@link CiPactParity} (qits-2465): the gated repository's pact files at the fold,
 * and its {@code mirror.repository}'s {@code release.yml} and pact files at that repository's newest
 * release — and, since qits-2604, those same pact files at the fold of each of the counterpart's open
 * release requests. All IO, no decision — the decision is {@link CiPactParity}'s, which stays pure.
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
 *
 * <p><b>The counterpart's open release requests</b> (qits-2604) are what it offers beyond its
 * release: {@link CiPactParity} looks the gated repository's operations up in them, and never makes
 * the gated repository answer for them. They are listed through {@link CiReleaseRequestListing} by
 * the release row's storage id, and only once the release itself was read whole. A request counts
 * when its state is one of {@link CiReleaseRequestListing#OPEN_STATES} and it carries a fold sha
 * {@link CiIdentifiers} accepts; at that sha the pact paths the <em>release</em> declares are read,
 * since the release's {@code contracts} are what the check compares against. A listing that could
 * not be read, or a fold file the git host could not answer, is {@link Inputs#unreachable} like any
 * other read; a fold that does not carry a file adds nothing and fails nothing — an open fold is
 * evidence, not a promise. A deployment with no qits-projects ({@link
 * CiReleaseRequestListing#configured()} false, or no implementation at all) reads the release alone,
 * as before qits-2604, and the counterpart's {@link CiPactParity.Side#openFolds} is null.
 */
@ApplicationScoped
public class CiPactParityInputs {

  /** The tag namespace a release version is read under. */
  static final String TAGS = "refs/tags/";

  /** A pact is data, not a config: qits-landing-app's qits-projects-service pact is 1.5 MB at qits-2465. */
  static final int MAX_PACT_BYTES = 32 * 1024 * 1024;

  @Inject CiConfigSource configSource;
  @Inject CiReleaseSlotParser slotParser;
  @Inject CiScmReleaseRepository scmReleases;

  /**
   * An {@code Instance} rather than a direct injection point for the reason {@code
   * ListedAndKnownCiRepos} holds its listings so: the HTTP implementation lives in {@code
   * service/…/projects/}, and a build or a suite without it reads the release alone.
   */
  @Inject Instance<CiReleaseRequestListing> releaseRequests;

  /**
   * Both sides, or why they could not be read.
   *
   * @param gated the gated repository at the fold
   * @param counterpart its {@code mirror.repository} at its newest release, with its open folds
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
      CiConfigSource.FileLookup pact = configSource.readFile(repo, rev, path, MAX_PACT_BYTES);
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
      CiConfigSource.FileLookup pact = configSource.readFile(counterpart, tag, path, MAX_PACT_BYTES);
      if (pact.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
        return Inputs.unreachable(counterpartName + "'s " + path + " could not be read at " + tag);
      }
      theirPacts.put(path, found(pact));
    }
    if (!releaseRequests.isResolvable() || !releaseRequests.get().configured()) {
      return new Inputs(
          gated, CiPactParity.Side.read(counterpartName, at, theirs, theirPacts), null);
    }
    CiReleaseRequestListing.Listing listing =
        releaseRequests.get().releaseRequests(release.get().repoId);
    if (listing.unreachable() != null) {
      return Inputs.unreachable(
          counterpartName + "'s open release requests could not be read: " + listing.unreachable());
    }
    List<CiPactParity.OpenFold> folds = new ArrayList<>();
    for (CiReleaseRequestListing.Request request : listing.requests()) {
      String sha = openFold(request);
      if (sha == null) {
        continue;
      }
      Map<String, String> foldPacts = new LinkedHashMap<>();
      for (String path : theirPacts.keySet()) {
        CiConfigSource.FileLookup pact =
            configSource.readFile(counterpart, sha, path, MAX_PACT_BYTES);
        if (pact.status() == CiConfigSource.FileLookup.Status.UNREACHABLE) {
          return Inputs.unreachable(
              counterpartName
                  + "'s "
                  + path
                  + " could not be read at "
                  + sha
                  + ", the fold of "
                  + request.label());
        }
        if (pact.status() == CiConfigSource.FileLookup.Status.FOUND) {
          foldPacts.put(path, pact.content());
        }
      }
      folds.add(new CiPactParity.OpenFold(request.label(), foldPacts));
    }
    return new Inputs(
        gated, CiPactParity.Side.read(counterpartName, at, theirs, theirPacts, folds), null);
  }

  /**
   * The fold to read an open request at, or null when it offers none: a state outside {@link
   * CiReleaseRequestListing#OPEN_STATES}, no fold yet, or a sha {@link CiIdentifiers} refuses — it
   * reaches a URL.
   */
  private static String openFold(CiReleaseRequestListing.Request request) {
    if (request.state() == null
        || !CiReleaseRequestListing.OPEN_STATES.contains(request.state())
        || request.mergedSha() == null) {
      return null;
    }
    try {
      return CiIdentifiers.requireSha(request.mergedSha());
    } catch (RuntimeException malformed) {
      return null;
    }
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
