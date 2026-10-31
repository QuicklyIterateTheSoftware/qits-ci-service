package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The {@code ci_slot_hold} rows against the real schema</b> (epic qits-732): what {@link
 * CiSlotHolds} writes and deletes, each in a transaction of its own, round-trips through V42 —
 * including the one-hold-per-request index, which is what makes a supersede necessary at all.
 * {@code CiSlotHoldTest} holds the rows in memory; this is the half only a database can answer.
 */
@QuarkusTest
public class CiSlotHoldRowsTest {

  private static final Instant HELD_AT = Instant.parse("2026-10-31T12:00:00Z");

  @Inject CiSlotHoldRows rows;

  @AfterEach
  void empty() {
    rows.removeAllExcept(Set.of());
  }

  @Test
  public void aHoldRoundTripsAndIsRemovedOnceByEitherKey() {
    CiSlotHolds.Hold hold = hold("repo-a", "rr-1");
    rows.insert(hold);

    CiSlotHolds.Hold back = rows.remove("repo-a", "rr-1").orElseThrow();
    assertEquals(hold.leaseId(), back.leaseId());
    assertEquals("run-rr-1", back.heldFromRunId());
    assertEquals("qits-target-rr-1", back.releaseRequestQualifiedId());
    assertEquals(hold.targetId(), back.targetId());
    assertEquals(HELD_AT, back.heldAt().truncatedTo(ChronoUnit.SECONDS));
    assertEquals(null, back.tokenSubject(), "a row never carries the token's subject");
    assertTrue(rows.remove("repo-a", "rr-1").isEmpty(), "removed once");

    rows.insert(hold);
    assertEquals(hold.leaseId(), rows.remove(hold.leaseId()).orElseThrow().leaseId());
    assertTrue(rows.remove(hold.leaseId()).isEmpty());
  }

  @Test
  public void oneHoldPerRequestAndAStartingProcessTakesTheRest() {
    CiSlotHolds.Hold first = hold("repo-a", "rr-1");
    rows.insert(first);
    assertThrows(RuntimeException.class, () -> rows.insert(hold("repo-a", "rr-1")));

    CiSlotHolds.Hold other = hold("repo-a", "rr-2");
    rows.insert(other);
    List<CiSlotHolds.Hold> left = rows.removeAllExcept(Set.of(other.leaseId()));

    assertEquals(List.of(first.leaseId()), left.stream().map(CiSlotHolds.Hold::leaseId).toList());
    assertTrue(rows.remove(other.leaseId()).isPresent(), "the spared one stays");
  }

  private static CiSlotHolds.Hold hold(String repoId, String requestId) {
    return new CiSlotHolds.Hold(
        UUID.randomUUID(),
        repoId,
        requestId,
        "qits-target-" + requestId,
        "run-" + requestId,
        "ci-daemon-" + requestId,
        "subject-not-stored",
        UUID.randomUUID(),
        "2026.1031.732000",
        "b".repeat(40),
        HELD_AT,
        HELD_AT.plusSeconds(60));
  }
}
