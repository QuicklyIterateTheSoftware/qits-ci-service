package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * <b>A reserved run is settled whatever happens on it, and one poison row costs one row.</b> {@code
 * CiRunClaimOrderTest} owns which row a runner takes and {@code CiQueuedRunTest} owns what a row
 * means; this class owns what a run's end leaves behind for the runs after it.
 *
 * <p>It began as the in-process claim loop's resilience suite (2026-09-07: every run an instance
 * accepted sat {@code QUEUED} after a redeploy, because the pool's loops had ended one at a time).
 * The loop is deleted (qits-506) — every run is a runner's reservation — and so are the cases that
 * were only about the loop's own thread (a leaked interrupt flag, the census). What stayed is what
 * still decides whether the queue moves:
 *
 * <ul>
 *   <li>an {@code Error} — and in a native image a missing reflection registration <em>is</em> one —
 *       still settles the claimed run, so the runner's slot is freed and its next run runs;
 *   <li>a throw before the claimed run's own try must not leave the row {@code RUNNING} with
 *       nothing to settle it;
 *   <li>a {@code QUEUED} row whose snapshot no longer parses is settled by the reservation that
 *       walks past it, so everything the ordering put behind it still runs.
 * </ul>
 *
 * <p>Every case runs on {@link SuiteRunner}'s one slot, so the sole runner really is the whole of CI,
 * and a run accepted after the damage is genuinely a run the queue would have lost.
 */
@QuarkusTest
public class CiReservedRunResilienceTest extends CiTestSupport {

  private static final String CONFIG_ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo one
      """;

  @Inject CiRunService service;

  private String seedRepo() {
    return "resilient-" + UUID.randomUUID();
  }

  private static String shaOf(String repoId) {
    return String.format("%08x", repoId.hashCode()).repeat(5);
  }

  private void accept(String repoId) {
    service.onEventTrigger(eventRun(repoId, "main", shaOf(repoId), CONFIG_ONE_STEP));
  }

  private CiRun soleRun(String repoId) {
    forgetLoadedEntities();
    List<CiRun> all = service.runsFor(repoId);
    assertEquals(1, all.size(), "expected exactly one recorded run for " + repoId);
    return all.get(0);
  }

  // --- a run that dies of an Error ------------------------------------------------------------

  @Test
  public void aRunThatSuffersAnErrorIsSettledAndItsRunnerTakesTheNextRun() throws Exception {
    // NoClassDefFoundError rather than a made-up Error, because it is the one this deployable really
    // risks: service/ compiles to a native image, and a type nobody registered surfaces at runtime,
    // in the binary, as exactly this — while the JVM suite stays green.
    AtomicBoolean thrown = new AtomicBoolean();
    fakeRunner.during(
        0,
        spec -> {
          if (thrown.compareAndSet(false, true)) {
            throw new NoClassDefFoundError("a type this image never registered");
          }
        });

    String exploding = seedRepo();
    accept(exploding);
    suiteRunner.awaitIdle();

    // The row is terminal rather than stranded RUNNING: an Error is settled like any other way a
    // claimed run can end, because the run is over either way and the row has to say so.
    CiRun died = soleRun(exploding);
    assertEquals(CiRunStatus.FAILED, died.status);
    assertNotNull(died.finishedAt);
    assertTrue(fakeRunner.closed().contains(died.id), "the run closed, so its slot was given back");

    String next = seedRepo();
    accept(next);
    suiteRunner.awaitIdle();

    assertEquals(
        CiRunStatus.SUCCESS,
        soleRun(next).status,
        "the runner's slot came back and it took the next run");
  }

  // --- one poison row costs one row ------------------------------------------------------------

  @Test
  public void aRowThatCannotBeReconstructedIsSettledAndTheRunsBehindItStillRun() throws Exception {
    // The poison row is the OLDEST and both rows state nothing else, so CiRunOrdering's last
    // tie-break puts it first — which is what made it a wedge once: a scan that stopped there left
    // every row behind it unreachable, on every pass, and the boot sweep handed the same row back.
    // The reservation walks past it, takes the row behind, and settles it after its own claim.
    String poisonRepo = "poison-" + UUID.randomUUID();
    String poison = insertUnreadableQueuedRow(poisonRepo, Instant.now().minusSeconds(60));
    String behind = seedRepo();

    accept(behind);
    suiteRunner.awaitIdle();

    forgetLoadedEntities();
    CiRun settled = service.requireRun(poison);
    assertEquals(CiRunStatus.CANCELLED, settled.status, "a row nobody can run is settled, not left");
    assertEquals(
        CiRunService.TRIGGER_UNREADABLE,
        settled.cancellationReason,
        "its own reason: nobody cancelled it, its snapshot stopped parsing");
    assertNotNull(settled.finishedAt);
    assertNull(settled.startedAt, "it was never claimed — there was nothing to claim it for");
    assertEquals(0, service.stepsFor(poison).size());

    assertEquals(
        CiRunStatus.SUCCESS,
        soleRun(behind).status,
        "and the row the ordering put behind it was reserved and ran");

    // The other half of the wedge: a settled row is not QUEUED, so the sweep a successor runs has
    // nothing to hand back.
    service.sweepInterrupted();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
    assertEquals(
        CiRunStatus.CANCELLED,
        service.requireRun(poison).status,
        "a boot sweep does not resurrect it");
  }

  // --- a throw between the claim and the run ---------------------------------------------------

  @Test
  public void aDaemonPinThatThrowsSettlesTheClaimedRunRatherThanStrandingItRunning()
      throws Exception {
    // The reservation has already flipped the row RUNNING by the time the pin is resolved — so this
    // throw used to happen with the row RUNNING, no steps, no finishedAt and no handler above it. Nothing short of the next
    // process's boot sweep could settle such a row; a person had to do it in SQL.
    fakeRunner.failPin(new IllegalStateException("the daemon pin ladder could not be read"));

    String repoId = seedRepo();
    accept(repoId);
    suiteRunner.awaitIdle();

    CiRun run = soleRun(repoId);
    assertEquals(CiRunStatus.FAILED, run.status, "settled like any other way a claimed run ends");
    assertNotNull(run.startedAt, "it really was claimed — this is the window the fix closes");
    assertNotNull(run.finishedAt, "and it is not RUNNING for a successor to find");
    assertNull(run.daemonVersion, "nothing was pinned, so nothing is recorded as pinned");
    assertEquals(0, service.stepsFor(run.id).size(), "it never reached a step");
  }

  // --- staging ---------------------------------------------------------------------------------

  /**
   * A {@code QUEUED} event row a runner can look at and never run: its {@code trigger_config} is not
   * a trigger file at all, so {@code CiEventTriggerParser} refuses it.
   *
   * <p>Written straight through the repository, because the engine cannot produce one — the snapshot
   * parsed once, at accept, which is exactly why this row is worth staging by hand.
   */
  private String insertUnreadableQueuedRow(String repoId, Instant createdAt) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = repoId;
              run.branch = "main";
              run.commitSha = shaOf(repoId);
              run.status = CiRunStatus.QUEUED;
              run.createdAt = createdAt;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-unreadable.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = "BuildSuccessful";
              run.triggerEventOccurredAt = createdAt;
              run.triggerEventPayload = "{}";
              // Not null — runnable() would answer false and this would take the retirement path
              // instead. It is a document the parser reads and refuses, which is the case under
              // test: the row looks runnable right up to the moment it is read.
              run.triggerConfig = "event: BuildSuccessful\nsteps: not-a-list-of-steps\n";
              runs.persist(run);
            });
    return id;
  }
}
