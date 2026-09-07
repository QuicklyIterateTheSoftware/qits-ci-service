package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiScmRelease;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Panache DAO for {@link CiScmRelease} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiScmReleaseRepository implements PanacheRepositoryBase<CiScmRelease, String> {

  /**
   * Whether a {@code (repository, version)} was really released, asked with the storage id alone —
   * what the boot sweep has, since an owed row is keyed by the run's repository id.
   */
  public boolean released(String repoId, String version) {
    return released(repoId, null, version);
  }

  /**
   * Whether a {@code (repository, version)} was really released, asked with <b>both</b> spellings of
   * the repository the run knows.
   *
   * <p><b>The name is the preferred half and the id is the fallback</b>, which is the whole reason
   * this is not a {@code find("repoId", …)}. After the identity cutover a run's {@code repoId} is an
   * opaque storage UUID while {@code SCMRelease} announces the platform's public name, so comparing
   * ids alone would silently never close the join; before it, id and name agree and either arm
   * answers. Every combination is compared because neither side promises which spelling it carries:
   * the event records an id and, optionally, a registered name, and the run now records the same
   * pair.
   *
   * @param repoName the run's own public name, or null when its push was id-addressed
   */
  public boolean released(String repoId, String repoName, String version) {
    if (repoName == null || repoName.isBlank()) {
      return count("(repoId = ?1 or repoName = ?1) and version = ?2", repoId, version) > 0;
    }
    return count(
            "(repoName = ?1 or repoId = ?1 or repoId = ?2 or repoName = ?2) and version = ?3",
            repoName,
            repoId,
            version)
        > 0;
  }

  /**
   * The recorded fact for one release, or empty — the idempotency read the insert is guarded by.
   *
   * <p>Named rather than a {@code find} overload: Panache's own {@code find(String, Object...)} would
   * take a two-string call, and which method a call site reaches is not something to leave to
   * overload resolution.
   */
  public Optional<CiScmRelease> findRelease(String repoId, String version) {
    return find("repoId = ?1 and version = ?2", repoId, version).firstResultOptional();
  }

  /**
   * The priority the recorded release stated, asked with the storage id alone — what the boot sweep
   * has, exactly as {@link #released(String, String)} is.
   */
  public Optional<String> priorityOf(String repoId, String version) {
    return priorityOf(repoId, null, version);
  }

  /**
   * The priority the recorded release stated, asked with <b>both</b> spellings of the repository —
   * the matcher of {@link #released(String, String, String)} verbatim, and deliberately so.
   *
   * <p>The two questions are the same question: "is there a release fact for this key, and if so what
   * did it say". A lookup that matched on fewer spellings than the gate would answer null for a
   * release the join had just closed on, and the announcement would then carry no priority for a
   * release that plainly stated one — a silent half-answer rather than a failure. So the predicate is
   * kept identical, and the only difference is what comes back.
   *
   * <p>{@link Optional#empty()} is "no such release fact"; a row that stated no priority answers an
   * empty Optional too, since the column is null and both mean the same thing to the caller — the
   * announcement carries no priority, and the key is absent on the wire. Nothing here distinguishes
   * them because nothing downstream could act on the distinction.
   *
   * @param repoName the run's own public name, or null when its push was id-addressed
   */
  public Optional<String> priorityOf(String repoId, String repoName, String version) {
    if (repoName == null || repoName.isBlank()) {
      return find("(repoId = ?1 or repoName = ?1) and version = ?2", repoId, version)
          .firstResultOptional()
          .map(release -> release.priority);
    }
    return find(
            "(repoName = ?1 or repoId = ?1 or repoId = ?2 or repoName = ?2) and version = ?3",
            repoName,
            repoId,
            version)
        .firstResultOptional()
        .map(release -> release.priority);
  }
}
