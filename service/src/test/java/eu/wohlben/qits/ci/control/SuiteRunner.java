package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiRun;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * <b>The execution target the service suite's runs execute on</b>, for the suites that want a run to
 * actually run — the ci module's {@code SuiteRunner}, duplicated because the modules share no test
 * classpath (the {@link FakeCiStepRunner} trade).
 *
 * <p>A run is a CI lease (epic qits-1481). This plays qits-system granting one through the same
 * public doors {@code targethost/CiLeases} uses: told a run was queued, it asks {@link
 * CiRunService#leaseCandidate} for it, records a lease of its own ({@link CiRunService#recordLease})
 * and, once its one slot is free, claims it ({@link CiRunService#claimLeased}) and runs it with
 * {@link CiRunService#executeReserved} on a driver thread, holding the run in {@link
 * FakeCiStepRunner} until it closes — so its steps are the fake's scripts, while a run whose lease the
 * real seam asked for goes through the real one.
 *
 * <p><b>Off unless a suite turns it on</b> ({@link #enable}, and {@link #disable} after). On, it is
 * one slot — the old {@code qits.ci.concurrent-builds=1} — so a run parked in its first step still
 * holds everything accepted behind it {@code QUEUED}.
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

  private final ScheduledExecutorService grants =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "suite-target-grants");
            t.setDaemon(true);
            return t;
          });

  private final AtomicInteger busy = new AtomicInteger();

  private final AtomicInteger running = new AtomicInteger();

  private volatile UUID targetId;

  /** The target the suite's runs are granted on, or null while it is off. */
  public UUID id() {
    return targetId;
  }

  /** Whether the suite's target takes runs. */
  public boolean enabled() {
    return targetId != null;
  }

  /** Brings the target up, and leases whatever is already queued. */
  public void enable() {
    targetId = UUID.randomUUID();
    List<String> queued =
        QuarkusTransaction.requiringNew()
            .call(() -> runRows.listQueuedOldestFirst().stream().map(run -> run.id).toList());
    queued.forEach(this::queued);
  }

  /** Waits for what is running to finish, then takes the target down. */
  public void disable() throws InterruptedException {
    awaitIdle();
    targetId = null;
  }

  /** A run was queued: lease it now, and grant what fits a moment later. */
  void queued(String runId) {
    if (targetId == null) {
      return;
    }
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
          50,
          TimeUnit.MILLISECONDS);
    } catch (RuntimeException rejected) {
      busy.decrementAndGet();
    }
  }

  private void lease(String runId) {
    try {
      Optional<CiRunService.Reservation> candidate = service.leaseCandidate(runId);
      if (candidate.isEmpty() || candidate.get().run().leaseId != null) {
        return;
      }
      service.recordLease(
          runId,
          UUID.randomUUID(),
          UUID.randomUUID().toString(),
          "suite-subject-" + runId,
          steps.pinDaemon().version());
    } catch (RuntimeException e) {
      LOG.debugf("Suite target: run %s could not be leased (%s)", runId, e.getMessage());
    }
  }

  private void pass() {
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
    UUID target = targetId;
    while (target != null && running.get() < 1) {
      Optional<CiRunService.Reservation> next = grantNext(target);
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
              pass();
              busy.decrementAndGet();
            }
          });
    }
  }

  private Optional<CiRunService.Reservation> grantNext(UUID target) {
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
            service.claimLeased(candidate.id, candidate.leaseId, target);
        if (claimed.isPresent()) {
          return claimed;
        }
      } catch (RuntimeException e) {
        LOG.debugf("Suite target: run %s could not be claimed (%s)", candidate.id, e.getMessage());
      }
    }
    return Optional.empty();
  }

  /**
   * Blocks until no driver is running anything and a grant pass found nothing more to start. A
   * disabled target is idle at once.
   */
  public void awaitIdle() throws InterruptedException {
    if (targetId != null) {
      pass();
    }
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
