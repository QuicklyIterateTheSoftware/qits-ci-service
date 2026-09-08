package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * <b>The claim loop outlives what happens on it.</b> {@code CiRunClaimOrderTest} owns which row a
 * worker takes and {@code CiQueuedRunTest} owns what a row means; this class owns the one question
 * neither of them asks — <b>is there still a worker afterwards</b>.
 *
 * <p>It exists because the answer was measured to be "no". After a redeploy on 2026-09-07 every run
 * this instance accepted sat {@code QUEUED} indefinitely: the fixed pool's claim loops had ended,
 * one at a time and silently, nothing resubmitted them, no health check said so, and only a process
 * restart healed it. Four ways they ended, and one row that stopped every surviving worker anyway:
 *
 * <ul>
 *   <li>a helper on the run path caught an {@code InterruptedException} and <b>restored the flag</b>
 *       before returning its fallback, which the loop then read as a shutdown;
 *   <li>an {@code Error} — and in a native image a missing reflection registration <em>is</em> one —
 *       killed a pool thread the pool never refills;
 *   <li>a throw before the claimed run's own try left the row {@code RUNNING} with nothing to settle
 *       it;
 *   <li>a {@code QUEUED} row whose snapshot no longer parses abandoned the scan, so everything the
 *       ordering put behind it was unreachable by every worker, forever.
 * </ul>
 *
 * <p>Every case here is staged the way this suite's siblings stage theirs — {@code
 * qits.ci.concurrent-builds=1}, so the sole worker really is the whole of CI, and a run accepted
 * after the damage is genuinely a run the queue would have lost.
 */
@QuarkusTest
public class CiRunWorkerResilienceTest extends CiTestSupport {

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

  /** The census after the damage, which is the assertion every case here shares. */
  private void assertTheQueueStillHasItsWorker() {
    CiRunService.WorkerCensus census = service.workerCensus();
    assertEquals(
        census.configured(),
        census.live(),
        "every configured claim loop is still live — the pool was refilled or never emptied");
  }

  // --- a worker that dies of an Error ----------------------------------------------------------

  @Test
  public void aWorkerThatSuffersAnErrorMidRunStillClaimsTheNextRun() throws Exception {
    // NoClassDefFoundError rather than a made-up Error, because it is the one this deployable really
    // risks: service/ compiles to a native image, and a type nobody registered surfaces at runtime,
    // in the binary, as exactly this — while the JVM suite stays green. The loop used to catch
    // RuntimeException only, so one of these cost a build slot for the life of the process.
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
    service.awaitIdle();

    // The row is terminal rather than stranded RUNNING: an Error is settled like any other way a
    // claimed run can end, because the run is over either way and the row has to say so.
    CiRun died = soleRun(exploding);
    assertEquals(CiRunStatus.FAILED, died.status);
    assertNotNull(died.finishedAt);
    assertTheQueueStillHasItsWorker();

    String next = seedRepo();
    accept(next);
    service.awaitIdle();

    assertEquals(
        CiRunStatus.SUCCESS,
        soleRun(next).status,
        "the worker survived the Error and claimed the next run");
    assertTheQueueStillHasItsWorker();
  }

  // --- a worker whose interrupt flag was restored under it -------------------------------------

  @Test
  public void aLeakedInterruptFlagDoesNotRetireTheWorker() throws Exception {
    // Exactly what CiDaemonRegistry.await, CiDaemonLauncher's sleep, IdpCommissioner,
    // HttpGitConfigSource and DbRetry each do when their wait is interrupted: restore the flag and
    // return a fallback. Every one of them is locally correct; the loop is what has to tell the
    // restored flag from a shutdown, and it does it by asking whether this process is stopping.
    AtomicBoolean leaked = new AtomicBoolean();
    fakeRunner.during(
        0,
        spec -> {
          if (leaked.compareAndSet(false, true)) {
            Thread.currentThread().interrupt();
          }
        });

    String leaky = seedRepo();
    accept(leaky);
    service.awaitIdle();

    assertTrue(leaked.get(), "the flag really was restored on the run worker");
    assertNotEquals(
        CiRunStatus.QUEUED, soleRun(leaky).status, "the run was claimed with the flag raised");
    assertTheQueueStillHasItsWorker();

    String next = seedRepo();
    accept(next);
    service.awaitIdle();

    assertEquals(
        CiRunStatus.SUCCESS,
        soleRun(next).status,
        "a restored flag costs a step, never the claim loop");
    assertTheQueueStillHasItsWorker();
  }

  // --- one poison row costs one row ------------------------------------------------------------

  @Test
  public void aRowThatCannotBeReconstructedIsSettledAndTheRunsBehindItStillRun() throws Exception {
    // The regression the queue's own notes recorded as "the follow-up". The poison row is the OLDEST
    // and both rows state nothing else, so CiRunOrdering's last tie-break puts it first — which is
    // what made it a wedge rather than a curiosity: a loop that abandoned the scan there left every
    // row behind it unreachable by every worker, on every pass, and the boot sweep handed the same
    // row straight back.
    String poisonRepo = "poison-" + UUID.randomUUID();
    String poison = insertUnreadableQueuedRow(poisonRepo, Instant.now().minusSeconds(60));
    String behind = seedRepo();

    accept(behind);
    service.awaitIdle();

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
        "and the row the ordering put behind it ran in the same pass");

    // The other half of the wedge: a settled row is not QUEUED, so the sweep a successor runs has
    // nothing to hand back.
    service.sweepInterrupted();
    service.awaitIdle();
    forgetLoadedEntities();
    assertEquals(
        CiRunStatus.CANCELLED,
        service.requireRun(poison).status,
        "a boot sweep does not resurrect it");
    assertTheQueueStillHasItsWorker();
  }

  // --- a throw between the claim and the run ---------------------------------------------------

  @Test
  public void aDaemonPinThatThrowsSettlesTheClaimedRunRatherThanStrandingItRunning()
      throws Exception {
    // startQueued has already flipped the row RUNNING by the time the pin is resolved, and the pin
    // ladder's answer() is deliberately not DbRetry-wrapped — so this throw used to happen with the
    // row RUNNING, no steps, no finishedAt and no handler above it. Nothing short of the next
    // process's boot sweep could settle such a row; a person had to do it in SQL.
    fakeRunner.failPin(new IllegalStateException("the daemon pin ladder could not be read"));

    String repoId = seedRepo();
    accept(repoId);
    service.awaitIdle();

    CiRun run = soleRun(repoId);
    assertEquals(CiRunStatus.FAILED, run.status, "settled like any other way a claimed run ends");
    assertNotNull(run.startedAt, "it really was claimed — this is the window the fix closes");
    assertNotNull(run.finishedAt, "and it is not RUNNING for a successor to find");
    assertNull(run.daemonVersion, "nothing was pinned, so nothing is recorded as pinned");
    assertEquals(0, service.stepsFor(run.id).size(), "it never reached a step");
    assertTheQueueStillHasItsWorker();
  }

  // --- staging ---------------------------------------------------------------------------------

  /**
   * A {@code QUEUED} event row a worker can look at and never run: its {@code trigger_config} is not
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
