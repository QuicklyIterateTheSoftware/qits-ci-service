package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiSlotHold;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Panache DAO for {@link CiSlotHold} (keyed by the held lease's id). The caller owns the
 * transaction, as everywhere in this package.
 */
@ApplicationScoped
public class CiSlotHoldRepository implements PanacheRepositoryBase<CiSlotHold, UUID> {

  /** The hold of one release request, if any — at most one, by {@code ci_slot_hold_rr}. */
  public Optional<CiSlotHold> findByReleaseRequest(String repoId, String releaseRequestId) {
    return find("repoId = ?1 and releaseRequestId = ?2", repoId, releaseRequestId)
        .firstResultOptional();
  }

  /** Every hold, oldest first — what a starting process releases. */
  public List<CiSlotHold> listOldestFirst() {
    return list("order by heldAt");
  }
}
