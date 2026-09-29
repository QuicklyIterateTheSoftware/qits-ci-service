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
 * <b>The runner the {@code ci} suite runs its runs on</b> — the stand-in for a connected runner's
 * socket, now that every run is a runner's (qits-506) and this process has no claim loop of its own.
 *
 * <p>It does exactly what the service's runner socket does for a real runner, through the same two
 * public doors: told the backlog moved ({@link CiBacklogListener}, the {@code Backlog} a runner
 * receives), it asks {@link CiRunService#reserveFor} for its own row — the {@code Reserve} — and
 * runs what it is handed with {@link CiRunService#executeReserved} on a driver thread of its own,
 * holding the run in {@link FakeCiStepRunner} from the {@code Take} until the run closes, the way the
 * socket's registry does. So every run in the suite is claimed by the real reservation (ordering,
 * slots, quarantine) and executed by the real step loop, through the scripted fake
 * step seam. Nothing here decides anything a production runner would not.
 *
 * <p><b>Its row is re-made for every test</b> ({@link #reset}, from {@code CiTestSupport}): one slot
 * — the suite's old {@code qits.ci.concurrent-builds=1} — docker-capable, full id range, INTERNAL,
 * and connected in {@link FakeRunnerPresence}. A fresh row carries no infra streak and no
 * quarantine, so a test that fails the runner by the infrastructure cannot poison the next one. A test about runner rows that deletes them all simply leaves this one
 * with nothing to reserve for.
 *
 * <p><b>A spare runner is one call away</b> ({@link #addSpare}). A retry is not kept off the runner
 * that failed it (qits-443), so one runner is enough to watch an automatic retry run; but a runner
 * that fails run after run by the infrastructure is quarantined, and a suite that exhausts the retry
 * budget needs another runner for whatever it accepts afterwards — exactly as production does.
 *
 * <p>{@link #awaitIdle} is what replaced {@code CiRunService.awaitIdle}: it returns once no driver
 * is running anything and a reservation just came back empty — which is also true of a queue that
 * holds only rows this runner may not take (a draining process), exactly as
 * a real runner would leave them.
 */
@ApplicationScoped
public class SuiteRunner implements CiBacklogListener {

  private static final Logger LOG = Logger.getLogger(SuiteRunner.class);

  /** The name its row is registered under. */
  public static final String NAME = "suite-runner";

  @Inject CiRunService service;

  @Inject CiRunnerRepository runnerRows;

  @Inject FakeCiStepRunner steps;

  @Inject FakeRunnerPresence presence;

  private final ExecutorService drivers =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "suite-runner-driver");
            t.setDaemon(true);
            return t;
          });

  /**
   * Passes in flight plus runs executing: incremented before a task is submitted and decremented
   * when it is over, and a finishing run submits the next pass before it decrements — so zero means
   * the last pass found nothing and nothing is running.
   */
  private final AtomicInteger busy = new AtomicInteger();

  private volatile UUID runnerId;

  /** Further rows this runner also reserves for, after its own — see {@link #addSpare}. */
  private final java.util.List<UUID> spares = new java.util.concurrent.CopyOnWriteArrayList<>();

  private volatile int slots = 1;

  /** Deletes the previous test's row and registers a fresh one — see the class javadoc. */
  public void reset() {
    slots = 1;
    UUID previous = runnerId;
    java.util.List<UUID> previousSpares = java.util.List.copyOf(spares);
    spares.clear();
    UUID fresh = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (previous != null) {
                runnerRows.deleteById(previous);
              }
              previousSpares.forEach(runnerRows::deleteById);
              runnerRows.delete("name", NAME);
              runnerRows.persist(row(fresh, NAME, slots));
            });
    runnerId = fresh;
    presence.connect(fresh);
  }

  /**
   * Registers one more connected runner row that this driver also reserves for, and answers its id.
   * Gone again at the next {@link #reset}.
   */
  public UUID addSpare(String name) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew().run(() -> runnerRows.persist(row(id, name, 1)));
    spares.add(id);
    presence.connect(id);
    return id;
  }

  private static CiRunner row(UUID id, String name, int slots) {
    CiRunner row = new CiRunner();
    row.id = id;
    row.name = name;
    row.slots = slots;
    row.plane = CiRunnerPlane.INTERNAL;
    row.clientId = "client-" + name + "-" + id;
    row.capabilities = "{\"docker\":true,\"arch\":\"amd64\"}";
    row.registeredAt = Instant.now();
    row.createdAt = Instant.now();
    return row;
  }

  /** This test's row id. */
  public UUID id() {
    return runnerId;
  }

  /** How many runs it holds at once from now on — its row's slots. */
  public void slots(int slots) {
    this.slots = slots;
    UUID id = runnerId;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRunner row = runnerRows.findById(id);
              if (row != null) {
                row.slots = slots;
              }
            });
  }

  @Override
  public void backlogChanged(int queued) {
    if (queued > 0) {
      pass();
    }
  }

  /** One {@code Reserve} pass, on a driver thread: take what may be taken, until nothing is. */
  public void pass() {
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
    java.util.List<UUID> ids = new java.util.ArrayList<>();
    if (runnerId != null) {
      ids.add(runnerId);
    }
    ids.addAll(spares);
    for (UUID id : ids) {
      reserveAllFor(id);
    }
  }

  private void reserveAllFor(UUID id) {
    CiRunner row = QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(id));
    if (row == null) {
      return;
    }
    Optional<CiRunService.Reservation> next;
    while ((next = reserve(row)).isPresent()) {
      CiRunService.Reservation reservation = next.get();
      // The Take: the run is this runner's from now until it closes.
      steps.hold(reservation.run().id);
      busy.incrementAndGet();
      drivers.execute(
          () -> {
            try {
              service.executeReserved(reservation);
            } catch (Throwable fatal) {
              LOG.warnf(fatal, "Suite runner: run %s ended by a throw", reservation.run().id);
            } finally {
              // A slot freed: the next queued run may be ours now, as a Released-then-Reserve is.
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

  /**
   * Waits until no driver is running anything and the last reservation came back empty. One pass
   * is started first, so a row accepted while nothing listened — or while the process was draining,
   * which announces nothing — is still looked at.
   */
  public void awaitIdle() throws InterruptedException {
    pass();
    while (busy.get() > 0) {
      Thread.sleep(10);
    }
  }

  @PreDestroy
  void stop() {
    drivers.shutdownNow();
  }
}
