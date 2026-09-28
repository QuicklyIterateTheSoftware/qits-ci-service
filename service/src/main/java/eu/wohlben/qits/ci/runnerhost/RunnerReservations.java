package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.cirunner.protocol.Nothing;
import eu.wohlben.qits.cirunner.protocol.Take;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;

/**
 * A runner's {@code Reserve}, answered, and the run it reserved, driven.
 *
 * <p><b>The claim is {@link CiRunService#reserveFor}</b> — the claim loop's candidates, order and
 * compare-and-swap, narrowed to what this runner may take — and the answer is exactly one {@link
 * Take} or {@link Nothing} — always {@code Nothing} on a draining session ({@link
 * CiRunnerRegistry.Session#draining}), which holds no slot. A {@code Take} binds the run to the
 * session it went out on ({@link CiRunnerRegistry#hold}) <em>before</em> the frame leaves, so
 * nothing the runner does afterwards can arrive for a run this process does not yet know it holds.
 *
 * <p><b>Each reserved run is driven on a thread of its own, never on a {@code ci-run-worker}.</b>
 * The local pool is {@code qits.ci.concurrent-builds} threads and its size is a statement about
 * this host's capacity; a runner's run spends none of that capacity — its containers run on the
 * runner's machine — so parking it on a pool thread would take a local build slot away for
 * nothing, and the pool's liveness census would count runner work as its own. So the driver is a
 * platform thread from an unbounded cached pool, named {@code ci-runner-run-<runId>} for as long as
 * it drives that run. Unbounded is safe because the runners bound it: nothing reaches this executor
 * that a runner's slots did not first admit.
 *
 * <p><b>A run that closes is released, whatever closed it.</b> {@code Released} is the only frame
 * that frees a runner's slot, and the runner cannot infer it from anything else, so it is sent from
 * the driver's {@code finally} — after a verdict, a cancellation or an exception alike. The step
 * seam's {@code runClosed} sends it first on the ordinary path; this is the backstop, and {@link
 * CiRunnerRegistry#release} makes the second call a no-op.
 *
 * <p><b>A {@code Take} that could not be delivered still has a run behind it</b>, and it is driven
 * like any other: the session is gone, so its first step ends {@code CONNECTION_LOST} at once and
 * the run is a failed, retryable run naming the runner. Handing the row back to {@code QUEUED}
 * instead would be a backwards transition written on a guess about what the runner received.
 */
@ApplicationScoped
public class RunnerReservations {

  private static final Logger LOG = Logger.getLogger(RunnerReservations.class);

  @Inject CiRunService runService;

  @Inject CiRunnerRegistry registry;

  private final ExecutorService drivers =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "ci-runner-run");
            t.setDaemon(true);
            return t;
          });

  /** Answer one {@code Reserve}: a {@code Take} and a driver, or {@code Nothing}. */
  public void onReserve(CiRunnerRegistry.Session session) {
    if (session.draining()) {
      // Told to upgrade: it finishes what it holds and takes nothing more, whatever it asks. Its
      // Ack said 0 slots, so a Reserve here is a runner that did not listen — answered, not claimed.
      registry.send(session, new Nothing());
      return;
    }
    Optional<CiRunService.Reservation> reserved;
    try {
      reserved = runService.reserveFor(session.runner());
    } catch (RuntimeException e) {
      // A claim that could not be made is a Nothing: the runner parks until its next Backlog, and
      // the database blip that caused it is the claim loop's to live through as well.
      LOG.warnf(e, "Runner %s's reservation failed; answering Nothing", session.runnerName());
      registry.send(session, new Nothing());
      return;
    }
    if (reserved.isEmpty()) {
      registry.send(session, new Nothing());
      return;
    }
    CiRunService.Reservation reservation = reserved.orElseThrow();
    CiRun run = reservation.run();
    registry.hold(session, run.id);
    if (!registry.send(
        session,
        new Take(run.id, run.repoName != null ? run.repoName : run.repoId, run.branch, run.commitSha))) {
      LOG.warnf(
          "Runner %s reserved run %s and the Take did not reach it; the run is driven and will"
              + " record the runner as gone",
          session.runnerName(), run.id);
    }
    drive(reservation);
  }

  private void drive(CiRunService.Reservation reservation) {
    String runId = reservation.run().id;
    drivers.execute(
        () -> {
          Thread.currentThread().setName("ci-runner-run-" + runId);
          try {
            runService.executeReserved(reservation);
          } catch (Throwable fatal) {
            // executeReserved settles the row itself and rethrows only an Error; the thread is
            // this run's alone, so there is nothing left to protect but the log line.
            LOG.errorf(fatal, "The driver of runner run %s ended on %s", runId, fatal);
          } finally {
            registry.release(runId);
            Thread.currentThread().setName("ci-runner-run");
          }
        });
  }

  @PreDestroy
  void shutdown() {
    drivers.shutdownNow();
  }
}
