package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiReport;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Panache DAO for {@link CiReport} (keyed by its UUID row id). */
@ApplicationScoped
public class CiReportRepository implements PanacheRepositoryBase<CiReport, UUID> {

  /** A run's reports, in step order and then by kind — the order the read door answers them in. */
  public List<CiReport> listByRun(String runId) {
    return list("runId = ?1 order by stepIndex, kind", runId);
  }

  /** A run's reports of one kind, in step order. */
  public List<CiReport> listByRunAndKind(String runId, String kind) {
    return list("runId = ?1 and kind = ?2 order by stepIndex", runId, kind);
  }

  /** The one report of a kind on a step, or empty. */
  public Optional<CiReport> findByTriple(String runId, int stepIndex, String kind) {
    return find("runId = ?1 and stepIndex = ?2 and kind = ?3", runId, stepIndex, kind)
        .firstResultOptional();
  }

  /** Every report of a run; answers how many went. */
  public long deleteByRun(String runId) {
    return delete("runId = ?1", runId);
  }
}
