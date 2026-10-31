package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.entity.CiSlotHold;
import eu.wohlben.qits.ci.persistence.CiSlotHoldRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The durable half of {@link CiSlotHolds}: the {@code ci_slot_hold} rows, each write in a transaction
 * of its own on ci's datasource — a hold is written on a run's driver thread and released on the bus's
 * dispatch thread, a sweep's or the startup's, none of which owns a transaction on this datasource
 * ({@code RepositoryRenamedListener}'s measured reason). A seam of its own so a suite can hold the rows
 * in memory.
 */
@ApplicationScoped
public class CiSlotHoldRows {

  @Inject CiSlotHoldRepository rows;

  /** Writes one hold. Throws when it cannot — the hold is then not entered. */
  public void insert(CiSlotHolds.Hold hold) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiSlotHold row = new CiSlotHold();
              row.leaseId = hold.leaseId();
              row.repoId = hold.repoId();
              row.releaseRequestId = hold.releaseRequestId();
              row.releaseRequestQualifiedId = hold.releaseRequestQualifiedId();
              row.heldFromRunId = hold.heldFromRunId();
              row.daemonId = hold.daemonId();
              row.targetId = hold.targetId();
              row.daemonVersion = hold.daemonVersion();
              row.mergedSha = hold.mergedSha();
              row.heldAt = hold.heldAt();
              row.expiresAt = hold.expiresAt();
              rows.persist(row);
              rows.flush();
            });
  }

  /** Deletes the hold of one lease and answers what it was, or empty when there was none. */
  public Optional<CiSlotHolds.Hold> remove(UUID leaseId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiSlotHold row = rows.findById(leaseId);
              if (row == null) {
                return Optional.<CiSlotHolds.Hold>empty();
              }
              rows.delete(row);
              return Optional.of(CiSlotHolds.Hold.of(row));
            });
  }

  /** Deletes the hold of one release request and answers what it was, or empty. */
  public Optional<CiSlotHolds.Hold> remove(String repoId, String releaseRequestId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Optional<CiSlotHold> row = rows.findByReleaseRequest(repoId, releaseRequestId);
              row.ifPresent(rows::delete);
              return row.map(CiSlotHolds.Hold::of);
            });
  }

  /**
   * Deletes every hold but {@code kept} and answers them, oldest first — what a starting process
   * releases, sparing any it has entered itself meanwhile.
   */
  public List<CiSlotHolds.Hold> removeAllExcept(Set<UUID> kept) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<CiSlotHold> left =
                  rows.listOldestFirst().stream().filter(row -> !kept.contains(row.leaseId)).toList();
              left.forEach(rows::delete);
              return left.stream().map(CiSlotHolds.Hold::of).toList();
            });
  }
}
