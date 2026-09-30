package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * <b>The runner the service suite's runs execute on</b>, for the suites that want a run to actually
 * run — the ci module's {@code SuiteRunner}, duplicated because the modules share no test classpath
 * (the {@link FakeCiStepRunner} trade).
 *
 * <p>Since qits-506 there is no claim loop in {@code CiRunService}: a run is a runner's reservation
 * or nothing. This plays a connected runner through the same two public doors the runner socket
 * uses — told the backlog moved, it asks {@link CiRunService#reserveFor} for its own row and runs
 * what it is handed with {@link CiRunService#executeReserved} on a driver thread of its own, holding
 * the run in {@link FakeCiStepRunner} from the reservation until it closes, so its steps are the
 * fake's scripts while a real socket runner's go through the real seam.
 *
 * <p><b>Off unless a suite turns it on</b> ({@link #enable}, and {@link #disable} after): its row is
 * a real {@code ci_runner} row, and the runner suites here assert on exactly which rows exist. On,
 * it is one slot and docker-capable — the old {@code qits.ci.concurrent-builds=1} — so a
 * run parked in its first step still holds everything accepted behind it {@code QUEUED}.
 */
@ApplicationScoped
public class SuiteRunner implements CiBacklogListener {

  private static final Logger LOG = Logger.getLogger(SuiteRunner.class);

  public static final String NAME = "suite-runner";

  @Inject CiRunService service;

  @Inject CiRunnerRepository runnerRows;

  @Inject FakeCiStepRunner steps;

  private final ExecutorService drivers =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "suite-runner-driver");
            t.setDaemon(true);
            return t;
          });

  private final AtomicInteger busy = new AtomicInteger();

  private volatile UUID runnerId;

  /** Its row's id while enabled, else null. */
  public UUID id() {
    return runnerId;
  }

  /** Registers the runner's row — fresh, so no streak or quarantine carries over — and passes once. */
  public void enable() {
    UUID fresh = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runnerRows.delete("name", NAME);
              CiRunner row = new CiRunner();
              row.id = fresh;
              row.name = NAME;
              row.slots = 1;
              row.plane = CiRunnerPlane.EDGE;
              row.clientId = "client-" + NAME + "-" + fresh;
              row.capabilities = "{\"docker\":true,\"arch\":\"amd64\"}";
              row.registeredAt = Instant.now();
              row.createdAt = Instant.now();
              runnerRows.persist(row);
            });
    runnerId = fresh;
    pass();
  }

  /** Waits out what is running and removes the row, so nothing more is reserved for it. */
  public void disable() throws InterruptedException {
    awaitIdle();
    UUID id = runnerId;
    runnerId = null;
    if (id != null) {
      QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteById(id));
    }
  }

  @Override
  public void backlogChanged(int queued) {
    if (queued > 0 && runnerId != null) {
      pass();
    }
  }

  private void pass() {
    busy.incrementAndGet();
    try {
      drivers.execute(
          () -> {
            try {
              reserveAll();
            } finally {
              busy.decrementAndGet();
            }
          });
    } catch (RuntimeException rejected) {
      busy.decrementAndGet();
    }
  }

  private void reserveAll() {
    UUID id = runnerId;
    if (id == null) {
      return;
    }
    CiRunner row = QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(id));
    if (row == null) {
      return;
    }
    Optional<CiRunService.Reservation> next;
    while ((next = reserve(row)).isPresent()) {
      CiRunService.Reservation reservation = next.get();
      steps.hold(reservation.run().id);
      busy.incrementAndGet();
      drivers.execute(
          () -> {
            try {
              service.executeReserved(reservation);
            } catch (Throwable fatal) {
              LOG.warnf(fatal, "Suite runner: run %s ended by a throw", reservation.run().id);
            } finally {
              pass();
              busy.decrementAndGet();
            }
          });
    }
  }

  /**
   * One {@code Reserve} at a time, as one runner connection asks: two concurrent reservations for
   * one row would each read the other's claim as uncommitted and could hold more runs than its
   * slots.
   */
  private synchronized Optional<CiRunService.Reservation> reserve(CiRunner row) {
    try {
      return service.reserveFor(row);
    } catch (RuntimeException e) {
      LOG.debugf("Suite runner: a reservation failed (%s)", e.getMessage());
      return Optional.empty();
    }
  }

  /** Waits until nothing it runs is running and its last reservation came back empty. */
  public void awaitIdle() throws InterruptedException {
    if (runnerId != null) {
      pass();
    }
    while (busy.get() > 0) {
      Thread.sleep(10);
    }
  }

  @PreDestroy
  void stop() {
    drivers.shutdownNow();
  }
}
