package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * <b>The execution target the {@code ci} suite runs its runs on</b> — the stand-in for qits-system
 * granting CI leases (epic qits-1481), now that every run is a lease and this module ships no lease
 * client of its own.
 *
 * <p>It does what the service's lease seam does for a real lease, through the same public doors:
 * told a run was queued ({@link FakeCiStepRunner#queued}), it asks {@link
 * CiRunService#leaseCandidate} for the run — which settles a row nothing can run, a {@code docker:
 * true} pipeline as {@code CONFIG_ERROR} — records a lease of its own on the row ({@link
 * CiRunService#recordLease}), and grants it once one of its {@link #slots} is free: {@link
 * CiRunService#claimLeased} claims the row and {@link CiRunService#executeReserved} runs it on a
 * driver thread, holding the run in {@link FakeCiStepRunner} until it closes. So every run in the
 * suite is claimed by the real claim and executed by the real step loop, through the scripted fake
 * step seam.
 *
 * <p><b>The grant order is this service's suggested order</b> ({@link CiRunOrdering#suggestedOrder}
 * over the queued, leased rows) — what qits-system's {@code priority desc, requested_at asc} reduces
 * to for the priorities {@link CiRunOrdering#leasePriority} asks, and the order the suites have
 * always claimed in. One slot by default, the suite's old {@code qits.ci.concurrent-builds=1}.
 *
 * <p>{@link #awaitIdle} returns once no driver is running anything and a grant pass just found
 * nothing more to start.
 */
@ApplicationScoped
public class SuiteRunner {

  private static final Logger LOG = Logger.getLogger(SuiteRunner.class);

  @Inject CiRunService service;

  @Inject CiRunRepository runRows;

  @Inject FakeCiStepRunner steps;

  private final ExecutorService drivers =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "suite-target-driver");
            t.setDaemon(true);
            return t;
          });

  /**
   * Grants wait a moment after a lease, so a burst of leases — a boot sweep's — is ranked as one
   * queue rather than granted one by one as each arrives.
   */
  private final java.util.concurrent.ScheduledExecutorService grants =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "suite-target-grants");
            t.setDaemon(true);
            return t;
          });

  static final long GRANT_DELAY_MILLIS = 50;

  private final AtomicInteger busy = new AtomicInteger();

  private final AtomicInteger running = new AtomicInteger();

  private volatile UUID targetId = UUID.randomUUID();

  private volatile int slots = 1;

  /** A fresh target with one slot, for every test (from {@code CiTestSupport}). */
  public void reset() {
    slots = 1;
    targetId = UUID.randomUUID();
  }

  /** The target every run of the suite is granted on. */
  public UUID id() {
    return targetId;
  }

  /** How many runs this target runs at once. */
  public void slots(int slots) {
    this.slots = slots;
  }

  /** A run was queued: lease it now, on the caller's thread, and grant what fits a moment later. */
  void queued(String runId) {
    lease(runId);
    busy.incrementAndGet();
    try {
      grants.schedule(
          () -> {
            try {
              grantAll();
            } finally {
              busy.decrementAndGet();
            }
          },
          GRANT_DELAY_MILLIS,
          java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (RuntimeException rejected) {
      busy.decrementAndGet();
    }
  }

  private void lease(String runId) {
    try {
      Optional<CiRunService.Reservation> candidate = service.leaseCandidate(runId);
      if (candidate.isEmpty()) {
        return;
      }
      CiRun row = candidate.get().run();
      if (row.leaseId != null) {
        return;
      }
      String version;
      try {
        version = steps.pinDaemon().version();
      } catch (RuntimeException unpinned) {
        // The driver pins again and settles the run on the throw, like any claimed run's failure.
        version = null;
      }
      service.recordLease(
          runId, UUID.randomUUID(), UUID.randomUUID().toString(), "suite-subject-" + runId, version);
    } catch (RuntimeException e) {
      LOG.debugf("Suite target: run %s could not be leased (%s)", runId, e.getMessage());
    }
  }

  /** Grants every leased, queued run that fits in a free slot, in the suggested order. */
  public void pass() {
    busy.incrementAndGet();
    try {
      drivers.execute(
          () -> {
            try {
              grantAll();
            } finally {
              busy.decrementAndGet();
            }
          });
    } catch (RuntimeException rejected) {
      busy.decrementAndGet();
    }
  }

  private synchronized void grantAll() {
    while (running.get() < slots) {
      Optional<CiRunService.Reservation> next = grantNext();
      if (next.isEmpty()) {
        return;
      }
      CiRunService.Reservation reservation = next.get();
      steps.hold(reservation.run().id);
      running.incrementAndGet();
      busy.incrementAndGet();
      drivers.execute(
          () -> {
            try {
              service.executeReserved(reservation);
            } catch (Throwable fatal) {
              LOG.warnf(fatal, "Suite target: run %s ended by a throw", reservation.run().id);
            } finally {
              running.decrementAndGet();
              // A slot freed: the next queued run may be granted now.
              pass();
              busy.decrementAndGet();
            }
          });
    }
  }

  private Optional<CiRunService.Reservation> grantNext() {
    List<CiRun> leased;
    try {
      leased =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      runRows.listQueuedOldestFirst().stream()
                          .filter(run -> run.leaseId != null)
                          .toList());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    for (CiRun candidate : CiRunOrdering.suggestedOrder(CiRunService.builds(leased))) {
      try {
        Optional<CiRunService.Reservation> claimed =
            service.claimLeased(candidate.id, candidate.leaseId, targetId);
        if (claimed.isPresent()) {
          return claimed;
        }
      } catch (RuntimeException e) {
        LOG.debugf("Suite target: run %s could not be claimed (%s)", candidate.id, e.getMessage());
      }
    }
    return Optional.empty();
  }

  /** Whether a queued run holds a lease of this target's. */
  public boolean leased(String runId) {
    CiRun row = QuarkusTransaction.requiringNew().call(() -> runRows.findById(runId));
    return row != null && row.leaseId != null && row.status == CiRunStatus.QUEUED;
  }

  /**
   * Blocks until no driver is running anything and a grant pass found nothing more to start — so a
   * queue holding only runs nothing may start (a draining process) is idle, as a real target leaves
   * it.
   */
  public void awaitIdle() throws InterruptedException {
    pass();
    while (busy.get() > 0) {
      Thread.sleep(10);
    }
  }

  @PreDestroy
  void stop() {
    grants.shutdownNow();
    drivers.shutdownNow();
  }
}
