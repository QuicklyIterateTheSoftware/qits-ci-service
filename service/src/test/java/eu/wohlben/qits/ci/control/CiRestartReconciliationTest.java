package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The boot sweep, against the service's real wiring.</b> A run is mid-step — its row {@code
 * RUNNING} — and the process dies with no shutdown path running at all. What the next boot owes that
 * run is that no row claims to be executing: {@code CiRunService.onStart} fails what cannot be
 * replayed and re-queues what can, and a runner reserves the re-queued run from there.
 *
 * <p>There used to be a second half — {@code CiDaemonLauncher.onStart} asking qits-containers to
 * remove this owner's step containers, ordered before the sweep by a {@code @Priority} pair. It went
 * with the in-process executor (qits-506): a step container belongs to the runner that started it,
 * and that runner's own boot sweep removes what a previous life of it left behind.
 *
 * <p>{@code onStart} skips {@code LaunchMode.TEST}, so this drives what it calls — {@link
 * CiRunService#sweepInterrupted}, package-private in this package by design.
 */
@QuarkusTest
public class CiRestartReconciliationTest {

  @Inject CiRunService service;

  @Inject CiRunRepository runs;

  @Inject SuiteRunner suiteRunner;

  /**
   * The sweep fails the push run that was executing and re-queues the event run, which the suite's
   * runner then reserves and runs to its verdict — the recovery only the row can make.
   */
  @Test
  public void aHardRestartSettlesTheRowsAndARunnerReRunsTheEventRun() throws Exception {
    String pushRunId = UUID.randomUUID().toString();
    String eventRunId = UUID.randomUUID().toString();
    insertRunningPushRun(pushRunId);
    insertRunningEventRun(eventRunId);

    suiteRunner.enable();
    try {
      service.sweepInterrupted();
      suiteRunner.awaitIdle();

      forgetLoadedEntities();

      // A push run may not be repeated: arbitrary push work is not safe to run twice.
      CiRun push = runs.findById(pushRunId);
      assertEquals(CiRunStatus.FAILED, push.status, "no row may still claim to be executing");
      assertNotNull(push.finishedAt);

      // An event run is restarted from its own immutable snapshot, which is what makes an
      // event-trigger script an at-least-once boundary. The sweep writes it QUEUED and announces the
      // backlog, so what is observable after awaitIdle is the run having been RE-EXECUTED by a
      // runner — a stronger statement than the intermediate status. Its pipeline declares no steps.
      CiRun event = runs.findById(eventRunId);
      assertEquals(CiRunStatus.SUCCESS, event.status, "an interrupted event run is re-run, not failed");
      assertNotNull(event.finishedAt);
      assertEquals(suiteRunner.id(), event.runnerId, "and a runner is what re-ran it");
    } finally {
      suiteRunner.disable();
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                runs.deleteById(pushRunId);
                runs.deleteById(eventRunId);
              });
    }
  }

  // --- the rows a dead process would have left behind ------------------------------------------

  /**
   * A leftover push run caught mid-step: claimed by a predecessor's worker, pinned to a daemon,
   * never ended. Per-push CI retired on 2026-09-05 so no live deployment writes such a row, and a
   * successor still has to say something honest about the ones already in its database — which is
   * FAILED, because its step died with its process and this engine has no worker that could replay
   * repository-authored work even if replaying it were safe.
   */
  private void insertRunningPushRun(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = "restart-reconciliation-repo";
              run.branch = "main";
              run.commitSha = "c".repeat(40);
              run.status = CiRunStatus.RUNNING;
              run.createdAt = Instant.now();
              run.triggerType = CiTriggerType.POST_RECEIVE;
              run.daemonVersion = "dead-daemon";
              run.configPath = ".config/qits/ci-post-receive.yml";
              runs.persist(run);
            });
  }

  /** An event-triggered one, carrying the snapshot that is what makes it re-runnable. */
  private void insertRunningEventRun(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = "restart-reconciliation-repo";
              run.branch = "main";
              run.commitSha = "d".repeat(40);
              run.status = CiRunStatus.RUNNING;
              run.createdAt = Instant.now();
              run.triggerType = CiTriggerType.EVENT;
              run.daemonVersion = "dead-daemon";
              run.configPath = ".config/qits/ci-event-restart.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = "SoftwareRelease";
              run.triggerEventOccurredAt = Instant.now();
              run.triggerEventPayload = "{}";
              run.triggerConfig = "event: SoftwareRelease\nsteps: []\n";
              runs.persist(run);
            });
  }

  /**
   * Drop what this thread has already loaded, so the read after the sweep really goes to the
   * database rather than to the identity map holding the row as it was inserted.
   */
  private void forgetLoadedEntities() {
    runs.getEntityManager().clear();
  }
}
