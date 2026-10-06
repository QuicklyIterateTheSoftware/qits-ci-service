package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * "The same report kind at the newest released version", answered once for every kind (epic
 * qits-754, Design §4 and owner decision 4): a run's baseline is the gating QA run of the release
 * request that produced its repository's newest released version.
 *
 * <ol>
 *   <li>The newest {@link CiScmRelease} of the run's repository by {@link VersionSort}, matching
 *       {@code repoId} or {@code repoName} in every combination, as the release join does.
 *   <li>The newest {@code RELEASE}-phase run of that repository whose {@code branch} is that version
 *       (a release run checks out {@code branch: version}) and whose {@code releaseRequestId} is
 *       set. Its {@code commitSha} is the version's {@code tagSha}.
 *   <li>The newest {@code SUCCESS} {@code RELEASE_REQUEST}-phase run of that repository for that
 *       request — the run that gated the version.
 * </ol>
 *
 * <p><b>The run's own release request is excluded.</b> Once a request has released, the newest
 * version is the one it produced, and comparing a run with itself is no baseline; such a version is
 * passed over and the next older release asked instead.
 *
 * <p><b>Nothing found is {@link Optional#empty()}, never an exception</b>: a first release and every
 * version from before reports existed have no baseline, and "no baseline" degrades every kind to
 * "nothing to compare with" rather than failing anything.
 */
@ApplicationScoped
public class CiReportBaselines {

  /** A run's baseline: the version, its gating QA run, that run's request, and the version's sha. */
  public record Baseline(String version, String runId, String releaseRequestId, String tagSha) {}

  @Inject CiScmReleaseRepository scmReleases;

  @Inject CiRunRepository runs;

  public Optional<Baseline> forRun(CiRun run) {
    if (run == null || run.repoId == null) {
      return Optional.empty();
    }
    List<CiScmRelease> releases =
        releasesOf(run).stream()
            .sorted(Comparator.comparing((CiScmRelease r) -> r.version, VersionSort.COMPARATOR)
                .reversed())
            .toList();
    for (CiScmRelease release : releases) {
      Optional<CiRun> releaseRun =
          newestRun(
              run,
              " and phase = ?3 and branch = ?4 and releaseRequestId is not null",
              CiRunPhase.RELEASE,
              release.version);
      if (releaseRun.isEmpty()) {
        return Optional.empty();
      }
      String requestId = releaseRun.get().releaseRequestId;
      if (requestId.equals(run.releaseRequestId)) {
        continue;
      }
      Optional<CiRun> gate =
          newestRun(
              run,
              " and phase = ?3 and status = ?4 and releaseRequestId = ?5",
              CiRunPhase.RELEASE_REQUEST,
              CiRunStatus.SUCCESS,
              requestId);
      return gate.map(
          qa -> new Baseline(release.version, qa.id, requestId, releaseRun.get().commitSha));
    }
    return Optional.empty();
  }

  /** Every release of the run's repository, matched as {@code CiScmReleaseRepository#released}. */
  private List<CiScmRelease> releasesOf(CiRun run) {
    if (run.repoName == null || run.repoName.isBlank()) {
      return scmReleases.list("repoId = ?1 or repoName = ?1", run.repoId);
    }
    return scmReleases.list(
        "repoName = ?1 or repoId = ?1 or repoId = ?2 or repoName = ?2", run.repoName, run.repoId);
  }

  /**
   * The newest run of the run's repository — by its storage id, or by its public name when it has
   * one — satisfying {@code condition}, whose parameters start at {@code ?3}.
   */
  private Optional<CiRun> newestRun(CiRun run, String condition, Object... rest) {
    Object[] parameters = new Object[rest.length + 2];
    parameters[0] = run.repoId;
    parameters[1] = run.repoName == null || run.repoName.isBlank() ? run.repoId : run.repoName;
    System.arraycopy(rest, 0, parameters, 2, rest.length);
    return runs.find(
            "(repoId = ?1 or repoName = ?2)" + condition + " order by createdAt desc, id desc",
            parameters)
        .firstResultOptional();
  }
}
