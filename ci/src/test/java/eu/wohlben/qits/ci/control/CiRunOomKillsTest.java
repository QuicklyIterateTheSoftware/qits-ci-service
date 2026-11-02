package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>A run records its lease's out-of-memory kills, and only ever raises them</b> (qits-1818),
 * against the real table: {@link CiRunService#recordOomKills} is one compare-and-set, so a lower or
 * equal reading changes nothing, a reading of another lease changes nothing, the scope follows the
 * reading that raised the count, and the answer is by how much this reading raised it. A new lease
 * for a queued run starts the count over ({@code recordLease}).
 */
@QuarkusTest
public class CiRunOomKillsTest extends CiTestSupport {

  private static final UUID LEASE = UUID.fromString("3c1d0e2f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");

  @Test
  public void theCountIsOnlyEverRaisedAndTheScopeFollowsTheRaise() {
    String runId = insert(CiRunStatus.RUNNING, LEASE, null, null);

    assertEquals(2, runService.recordOomKills(runId, LEASE, 2, "NODE"));
    assertOom(runId, 2L, "NODE");

    assertEquals(0, runService.recordOomKills(runId, LEASE, 1, "LEASE"), "a lower reading");
    assertEquals(0, runService.recordOomKills(runId, LEASE, 2, "LEASE"), "an equal one");
    assertOom(runId, 2L, "NODE");

    assertEquals(3, runService.recordOomKills(runId, LEASE, 5, "LEASE"), "the rise, not the count");
    assertOom(runId, 5L, "LEASE");
  }

  @Test
  public void anotherLeasesReadingAndANonPositiveOneChangeNothing() {
    String runId = insert(CiRunStatus.RUNNING, LEASE, null, null);

    assertEquals(0, runService.recordOomKills(runId, UUID.randomUUID(), 4, "NODE"));
    assertEquals(0, runService.recordOomKills(runId, LEASE, 0, "NODE"));
    assertEquals(0, runService.recordOomKills("no-such-run", LEASE, 4, "NODE"));
    assertOom(runId, null, null);
  }

  @Test
  public void aFinishedRunStillRecordsItsLeasesLastSample() {
    String runId = insert(CiRunStatus.FAILED, LEASE, null, null);

    assertEquals(1, runService.recordOomKills(runId, LEASE, 1, "LEASE"));
    assertOom(runId, 1L, "LEASE");
  }

  @Test
  public void aFreshLeaseStartsTheCountOver() {
    String runId = insert(CiRunStatus.QUEUED, null, 3L, "NODE");

    assertEquals(
        1,
        (int)
            QuarkusTransaction.requiringNew()
                .call(() -> runs.recordLease(runId, LEASE, "daemon-1", "sub-1", "v1")));
    assertOom(runId, null, null);

    // Out of the suite target's way: it grants every queued row holding a lease.
    QuarkusTransaction.requiringNew()
        .run(() -> runs.update("status = ?1 where id = ?2", CiRunStatus.CANCELLED, runId));
  }

  private void assertOom(String runId, Long kills, String scope) {
    forgetLoadedEntities();
    CiRun row = QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
    if (kills == null) {
      assertNull(row.oomKills);
    } else {
      assertEquals(kills, row.oomKills);
    }
    assertEquals(scope, row.oomScope);
  }

  private String insert(CiRunStatus status, UUID leaseId, Long oomKills, String oomScope) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = "oom-" + UUID.randomUUID();
              run.branch = "main";
              run.commitSha = "7".repeat(40);
              run.status = status;
              run.createdAt = Instant.now();
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = TEST_TRIGGER_PATH;
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = TEST_EVENT_NAME;
              run.leaseId = leaseId;
              run.oomKills = oomKills;
              run.oomScope = oomScope;
              runs.persist(run);
            });
    return id;
  }
}
