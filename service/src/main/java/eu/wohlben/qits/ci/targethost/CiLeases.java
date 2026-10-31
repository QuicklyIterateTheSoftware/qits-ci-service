package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.control.CiRunOrdering;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiSlot;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.StepImages;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * <b>A CI run is one CI lease on an execution target</b> (epic qits-1481, "CI in a slot",
 * qits-1544). This class is the run's half of that: it asks for the lease, follows it, starts the
 * run's driver once it is granted, and ends it.
 *
 * <h2>Asking ({@link #request})</h2>
 *
 * <p>When a run is queued — accepted, retried, or put back by the boot sweep — {@link
 * CiRunService#leaseCandidate} answers its row and pipeline (settling a row nothing can run on the
 * way, a {@code docker: true} pipeline as {@code CONFIG_ERROR}). Then, in this order:
 *
 * <ol>
 *   <li>the run's {@code ci-run} token is minted ({@link RunCommissions#forRun} — it moved here from
 *       the run's first step, so the token exists before anything can present it);
 *   <li>the run's one launch is registered with {@link CiDaemonRegistry}, bound to that token's
 *       subject;
 *   <li>the spec is composed ({@link CiSlotSpecs}): the ci-slot image as the run pinned it at accept,
 *       the run's environment, user {@value CiSlot#USER} and the {@code qits.ci.slot.*} limits;
 *   <li>{@code POST /system/api/leases} with {@code slotType CI}, {@code ownerRef <runId>} and the
 *       priority {@link CiRunOrdering#leasePriority} gives the run;
 *   <li>the lease and the launch are recorded on the run's row, and a lease that cannot be recorded
 *       is ended again at once.
 * </ol>
 *
 * <p>Every request runs on one thread of its own ({@code ci-lease-requests}), so an accept never
 * waits for qits-system, and two requests for one run never race. A request that could not be made
 * — qits-system or qits-idp did not answer — leaves the run {@code QUEUED} with no lease, and the
 * reconcile asks again.
 *
 * <h2>Following ({@link #observe})</h2>
 *
 * <p>qits-system's {@code TargetLeaseChanged} ({@link TargetLeaseEvents}) and a reconcile of {@code
 * GET /system/api/leases} every {@code qits.ci.target-leases.reconcile-interval} (PT30S) both hand
 * a lease here, and so does the daemon's first dial ({@link CiDaemonRegistry.DaemonDialled}), which
 * is usually first of all:
 *
 * <ul>
 *   <li>{@code GRANTED} or {@code RUNNING}: the run is claimed ({@link CiRunService#claimLeased},
 *       one conditional UPDATE, so the three sources racing claim it once) and driven on a thread of
 *       its own, {@code ci-run-<runId>}. A granted lease's container is not there yet — the target
 *       pulls the ci-slot image first — so the driver's first step waits for {@code RUNNING} (or the
 *       daemon's dial) under {@code qits.ci.lease-start-timeout-seconds}, and only then starts the
 *       register clock: {@code RUNNING} and the dial are handed to {@link
 *       LeaseStepRunner#leaseRunning} (qits-1544).
 *   <li>{@code FAILED}, {@code EXITED}, or gone: the run's token is deleted, and the step seam is
 *       told ({@link LeaseStepRunner#leaseEnded}). A run that was still waiting is claimed anyway, so
 *       its first step answers {@code LAUNCH_FAILED} with the lease's detail and the run is settled
 *       — and retried automatically — like any infra failure.
 * </ul>
 *
 * <h2>Ending ({@link #end})</h2>
 *
 * <p>When the run closes, {@code DELETE /system/api/leases/{id}}. The reconcile also ends every
 * lease of this service's that no live run names, once it is older than {@link #ORPHAN_AFTER} — a
 * request that crashed between asking and recording.
 */
@ApplicationScoped
public class CiLeases {

  private static final Logger LOG = Logger.getLogger(CiLeases.class);

  /** How old an unnamed lease has to be before the reconcile ends it: a request's write window. */
  static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);

  @Inject SystemLeases system;

  @Inject CiRunService runs;

  @Inject RunCommissions commissions;

  @Inject CiDaemonRegistry daemons;

  @Inject CiSlotSettings settings;

  @Inject CiSlotAddresses addresses;

  @Inject LeaseStepRunner steps;

  @Inject CiSlotHolds holds;

  /** One run's lease and the launch its daemon names. */
  public record Launch(UUID leaseId, String daemonId, String tokenSubject) {}

  private final ConcurrentHashMap<String, Launch> launches = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, Thread> drivers = new ConcurrentHashMap<>();

  /** The one thread every lease request runs on. */
  private final ExecutorService requests =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "ci-lease-requests");
            t.setDaemon(true);
            return t;
          });

  private volatile boolean stopping;

  void onShutdown(@Observes ShutdownEvent event) {
    stopping = true;
  }

  @PreDestroy
  void shutdown() {
    stopping = true;
    requests.shutdownNow();
  }

  // --- asking -----------------------------------------------------------------------------------

  /** Ask for a queued run's lease, on the request thread. Never blocks and never throws. */
  public void request(String runId) {
    if (stopping) {
      return;
    }
    try {
      requests.execute(() -> requestNow(runId));
    } catch (RejectedExecutionException shuttingDown) {
      LOG.debugf("CI run %s: no lease asked for, this process is stopping", runId);
    }
  }

  /** {@link #request}, on the caller's thread — what the request thread runs, and what a suite calls. */
  void requestNow(String runId) {
    try {
      ask(runId);
    } catch (RuntimeException e) {
      LOG.warnf(e, "CI run %s: its lease could not be asked for; the reconcile asks again", runId);
    }
  }

  private void ask(String runId) {
    Optional<CiRunService.Reservation> candidate = runs.leaseCandidate(runId);
    if (candidate.isEmpty()) {
      return;
    }
    CiRun row = candidate.get().run();
    if (row.leaseId != null) {
      Launch held = launches.get(runId);
      if (held != null && held.leaseId().equals(row.leaseId)) {
        return; // asked already, by this process
      }
      // A lease a previous process asked for: nothing here holds its launch, so it is ended, the
      // tokens of the run that process minted are deleted, and a fresh one is asked for.
      LOG.infof("CI run %s: ending lease %s a previous process asked for", runId, row.leaseId);
      endQuietly(row.leaseId);
      commissions.revokeOrphaned(runId);
      if (!runs.forgetLease(runId, row.leaseId)) {
        return;
      }
    }
    Optional<CiSlotAddresses.Plane> plane = addresses.plane();
    if (plane.isEmpty()) {
      LOG.warnf(
          "CI run %s waits: this qits-ci knows no public domain (QITS_DOMAIN), so a lease has no"
              + " address to be told",
          runId);
      return;
    }
    IdpCommissioner.CommissionedToken token;
    try {
      token = commissions.forRun(runId, CiRunService.runEnv(candidate.get().request()));
    } catch (IdpCommissioner.CommissionFailedException notCommissioned) {
      LOG.warnf("CI run %s waits: %s", runId, notCommissioned.getMessage());
      return;
    }
    if (token == null) {
      LOG.warnf(
          "CI run %s waits: this qits-ci commissions no credentials"
              + " (quarkus.oidc-client.qits.client-enabled is off), so a lease would hold no token",
          runId);
      return;
    }
    String version = settings.daemonVersion();
    String daemonId = daemons.registerLaunch(runId, token.subject());
    SystemLeases.LeaseSpec spec =
        CiSlotSpecs.compose(
            settings.settings(),
            plane.get(),
            new CiSlotSpecs.LeaseRun(
                runId,
                CiRunService.repoRefOf(row),
                row.branch,
                row.commitSha,
                daemonId,
                settings.resolveBinaryUrl(version),
                slotImageOf(row),
                CiRunService.runEnv(candidate.get().request())),
            token);
    SystemLeases.LeaseView lease;
    try {
      lease =
          system.create(
              new SystemLeases.CreateLease(
                  SystemLeases.SLOT_TYPE, runId, CiRunOrdering.leasePriority(row), spec));
    } catch (SystemLeases.SystemUnavailable unavailable) {
      daemons.reap(daemonId);
      if (unavailable.status() == 409) {
        // LEASE_EXISTS: a live lease for this run nobody here recorded. It is ended, and the next
        // reconcile asks again.
        system.liveLeaseOf(runId).ifPresent(existing -> endQuietly(existing.id()));
      }
      LOG.warnf("CI run %s waits: %s", runId, unavailable.getMessage());
      return;
    }
    boolean recorded;
    try {
      recorded = runs.recordLease(runId, lease.id(), daemonId, token.subject(), version);
    } catch (RuntimeException unrecorded) {
      LOG.warnf("CI run %s: %s — ending lease %s", runId, unrecorded.getMessage(), lease.id());
      recorded = false;
    }
    if (!recorded) {
      // Settled or cancelled meanwhile, or the row could not be written: nothing could take this
      // lease back, so it does not stay.
      daemons.reap(daemonId);
      endQuietly(lease.id());
      commissions.release(runId);
      return;
    }
    launches.put(runId, new Launch(lease.id(), daemonId, token.subject()));
    LOG.infof(
        "CI run %s asked lease %s (priority %d, %s)",
        runId, lease.id(), CiRunOrdering.leasePriority(row), spec.image());
    observe(lease);
  }

  /**
   * The ci-slot image a run's lease is asked with: the digest the run pinned at accept, or the tag
   * when it pinned nothing. Looked up under the bare tag, as spelled (qits-757); a run pinned under
   * a registry host before that reads back under it too ({@link StepImages#decode}).
   */
  static String slotImageOf(CiRun row) {
    String pinned = StepImages.decode(row.stepImages).get(CiSlot.IMAGE_TAG);
    return pinned != null ? pinned : CiSlot.IMAGE_TAG;
  }

  // --- following --------------------------------------------------------------------------------

  /** The lease states qits-system reports, as this service reads them. */
  enum State {
    QUEUED,
    GRANTED,
    RUNNING,
    EXITED,
    FAILED,
    ENDING,
    ENDED;

    static State parse(String value) {
      if (value == null) {
        return null;
      }
      for (State state : values()) {
        if (state.name().equalsIgnoreCase(value.trim())) {
          return state;
        }
      }
      return null;
    }
  }

  /**
   * What one lease of this service's says now — from the event, the reconcile or a read.
   *
   * <p><b>Resolved by the lease id first, the owner ref last</b> (epic qits-732). A lease's {@code
   * ownerRef} is the run it was asked for and stays that for the lease's whole life, while a slot
   * held for a release ({@link CiSlotHolds}) outlives that run, and the run a held slot is adopted
   * by is another. So: the run whose launch this process holds on that lease; else the hold of it,
   * whose own death handling answers ({@link CiSlotHolds#leaseObserved}); and only when neither
   * exists the owner ref, which is every lease a run asked for and nothing has claimed yet.
   */
  void observe(SystemLeases.LeaseView lease) {
    if (lease == null || lease.id() == null) {
      return;
    }
    String runId = runHolding(lease.id());
    if (runId == null) {
      if (holds.holds(lease.id())) {
        holds.leaseObserved(lease);
        return;
      }
      runId = lease.ownerRef();
    }
    if (runId == null) {
      return;
    }
    State state = State.parse(lease.state());
    if (state == null) {
      LOG.debugf("Lease %s of run %s reports an unknown state %s", lease.id(), runId, lease.state());
      return;
    }
    switch (state) {
      case QUEUED, ENDING -> {
        /* waiting, or going: nothing for the run to do */
      }
      case GRANTED -> start(runId, lease);
      case RUNNING -> {
        running(runId, lease.id());
        start(runId, lease);
      }
      case EXITED, FAILED, ENDED -> ended(runId, lease.id(), describe(lease));
    }
  }

  /** The run whose launch this process holds on {@code leaseId}, or null. */
  private String runHolding(UUID leaseId) {
    for (Map.Entry<String, Launch> each : launches.entrySet()) {
      if (leaseId.equals(each.getValue().leaseId())) {
        return each.getKey();
      }
    }
    return null;
  }

  /**
   * The lease's container runs: the run's register clock starts now (qits-1544). Only for the lease
   * this process holds the launch of, so a late report of an earlier lease starts nothing.
   */
  private void running(String runId, UUID leaseId) {
    Launch launch = launches.get(runId);
    if (launch != null && launch.leaseId().equals(leaseId)) {
      steps.leaseRunning(runId);
    }
  }

  /** A granted lease's run: claimed once and driven. */
  private void start(String runId, SystemLeases.LeaseView lease) {
    if (drivers.containsKey(runId) || stopping) {
      return;
    }
    Optional<CiRunService.Reservation> claimed =
        runs.claimLeased(runId, lease.id(), lease.targetId());
    claimed.ifPresent(this::drive);
  }

  /** A lease that ended while its run did not. */
  private void ended(String runId, UUID leaseId, String detail) {
    Launch launch = launches.get(runId);
    if (launch == null || !launch.leaseId().equals(leaseId)) {
      return;
    }
    LOG.infof("CI run %s: its lease %s ended — %s", runId, leaseId, detail);
    // The rule for every slot type: the lease's token goes the moment the lease reports it ended.
    commissions.release(runId);
    steps.leaseEnded(runId, detail);
    // A run still waiting is claimed anyway: its first step answers LAUNCH_FAILED with the detail,
    // and the run is settled — and retried — like any infra failure.
    if (!drivers.containsKey(runId) && !stopping) {
      runs.claimLeased(runId, leaseId, null).ifPresent(this::drive);
    }
  }

  /** The lease's own words for how it ended. */
  static String describe(SystemLeases.LeaseView lease) {
    StringBuilder words = new StringBuilder("lease " + lease.id() + " " + lease.state());
    if (lease.exitCode() != null) {
      words.append(" (exit ").append(lease.exitCode()).append(')');
    }
    if (lease.targetId() != null) {
      words.append(" on target ").append(lease.targetId());
    }
    if (lease.detail() != null && !lease.detail().isBlank()) {
      words.append(": ").append(lease.detail().strip());
    }
    return words.toString();
  }

  /** The daemon of a run dialled in: its lease is running, whatever qits-system has said so far. */
  void onDialled(@Observes CiDaemonRegistry.DaemonDialled dialled) {
    Launch launch = launches.get(dialled.runId());
    if (launch != null && launch.daemonId().equals(dialled.daemonId())) {
      // A daemon that dialled runs in a container that runs: its register wait is over anyway.
      steps.leaseRunning(dialled.runId());
    }
    if (launch == null || drivers.containsKey(dialled.runId()) || stopping) {
      return;
    }
    try {
      system.get(launch.leaseId()).ifPresent(this::observe);
    } catch (RuntimeException e) {
      LOG.debugf(
          "CI run %s: its lease could not be read when its daemon dialled in (%s); the reconcile"
              + " claims it",
          dialled.runId(), e.getMessage());
    }
  }

  // --- driving ----------------------------------------------------------------------------------

  /** Runs a claimed run on a thread of its own. */
  private void drive(CiRunService.Reservation reservation) {
    String runId = reservation.run().id;
    driveOn(runId, () -> runs.executeReserved(reservation));
  }

  /** An inherited run, continued on a driver thread of its own ({@link LeaseStepRunner#inherit}). */
  void driveInherited(String runId, Runnable adopted) {
    driveOn(runId, adopted);
  }

  private void driveOn(String runId, Runnable work) {
    Thread driver =
        new Thread(
            () -> {
              try {
                work.run();
              } catch (eu.wohlben.qits.ci.control.CiStepRunner.ProcessStopping left) {
                LOG.infof("CI run %s: %s", runId, left.getMessage());
              } catch (Throwable t) {
                LOG.errorf(t, "CI run %s: its driver ended unexpectedly", runId);
              } finally {
                drivers.remove(runId, Thread.currentThread());
              }
            },
            "ci-run-" + runId);
    driver.setDaemon(true);
    if (drivers.putIfAbsent(runId, driver) != null) {
      return;
    }
    driver.start();
  }

  /**
   * A run continuing in a held slot (epic qits-732): {@code work} — the {@code Continue} and, once
   * answered, the run itself — on the run's own driver thread, {@code ci-run-<runId>}.
   */
  void driveContinued(String runId, Runnable work) {
    driveOn(runId, work);
  }

  /** Ends one lease at qits-system by its id. Never throws: the reconcile ends what this could not. */
  void endLease(UUID leaseId) {
    endQuietly(leaseId);
  }

  /** {@link CiRunService#recordContinuedLaunch}, for the seam. */
  boolean recordContinuedLaunch(String runId, UUID leaseId, String daemonId, String tokenSubject) {
    return runs.recordContinuedLaunch(runId, leaseId, daemonId, tokenSubject);
  }

  /** The address plane a lease's environment is composed from, or empty with no public domain. */
  Optional<CiSlotAddresses.Plane> plane() {
    return addresses.plane();
  }

  /** Whether a driver of this process holds the run. */
  public boolean drives(String runId) {
    return drivers.containsKey(runId);
  }

  /** The run's lease and launch, as this process holds them. */
  public Optional<Launch> launchOf(String runId) {
    return Optional.ofNullable(launches.get(runId));
  }

  /** An inherited run's launch, held again (qits-1192). */
  void adopt(String runId, UUID leaseId, String daemonId, String tokenSubject) {
    launches.put(runId, new Launch(leaseId, daemonId, tokenSubject));
  }

  /**
   * Forgets a launch an inheritance gave up on. The lease stays on the row: the run's close, or the
   * request a re-queued run makes, ends it from there.
   */
  void forgetLaunch(String runId) {
    launches.remove(runId);
  }

  // --- ending -----------------------------------------------------------------------------------

  /**
   * Ends the run's lease: the one this process holds, or the one its row names. Never throws: a lease
   * that could not be ended is the reconcile's, which ends every lease no live run names.
   */
  public void end(String runId) {
    Launch launch = launches.remove(runId);
    UUID leaseId = launch == null ? null : launch.leaseId();
    if (leaseId == null) {
      try {
        CiRun row = runs.runRow(runId);
        leaseId = row == null ? null : row.leaseId;
      } catch (RuntimeException e) {
        LOG.debugf("CI run %s: its row could not be read to end its lease: %s", runId, e.getMessage());
      }
    }
    if (leaseId != null) {
      endQuietly(leaseId);
    }
  }

  private void endQuietly(UUID leaseId) {
    try {
      system.end(leaseId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not end lease %s (%s); the reconcile ends it", leaseId, e.getMessage());
    }
  }

  // --- the reconcile ----------------------------------------------------------------------------

  @Scheduled(
      every = "{qits.ci.target-leases.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = Scheduled.ApplicationNotRunning.class)
  void reconcileOnSchedule() {
    reconcile();
  }

  /**
   * One pass: every lease of this service's, against every run that holds one. A lease that moved is
   * observed, a run whose lease is gone is told so, a lease no live run names is ended, and a queued
   * run with no lease is asked one. A listing that could not be read changes nothing — it must never
   * read as "every lease is gone".
   */
  void reconcile() {
    if (stopping) {
      return;
    }
    List<SystemLeases.LeaseView> listed;
    try {
      listed = system.list();
    } catch (RuntimeException e) {
      LOG.debugf("Could not list this service's leases; the next pass asks again: %s", e.getMessage());
      return;
    }
    Map<UUID, SystemLeases.LeaseView> byId = new HashMap<>();
    for (SystemLeases.LeaseView lease : listed) {
      if (lease.id() != null) {
        byId.put(lease.id(), lease);
      }
    }
    Map<UUID, String> named = new HashMap<>();
    // A slot held for a release names its lease, so the orphan sweep below never ends it, and a held
    // lease qits-system no longer lists is a slot that is gone (epic qits-732).
    for (UUID heldLease : holds.heldLeases()) {
      SystemLeases.LeaseView lease = byId.get(heldLease);
      if (lease == null) {
        holds.release(heldLease, CiSlotHolds.LEASE_ENDED);
      } else {
        named.put(heldLease, lease.ownerRef());
        holds.leaseObserved(lease);
      }
    }
    for (CiRun run : runs.leasedRuns()) {
      named.put(run.leaseId, run.id);
      SystemLeases.LeaseView lease = byId.get(run.leaseId);
      if (run.status == CiRunStatus.QUEUED && !launches.containsKey(run.id)) {
        // A lease a previous process asked for: the request ends it and asks a fresh one.
        request(run.id);
      } else if (lease != null) {
        observe(lease);
      } else if (launches.containsKey(run.id)
          && Objects.equals(launches.get(run.id).leaseId(), run.leaseId)) {
        ended(run.id, run.leaseId, "lease " + run.leaseId + " is gone from qits-system");
      }
    }
    Instant now = Instant.now();
    for (SystemLeases.LeaseView lease : listed) {
      State state = State.parse(lease.state());
      if (state == null || state == State.ENDED || state == State.ENDING) {
        continue;
      }
      String runId = named.get(lease.id());
      boolean young =
          lease.requestedAt() != null && lease.requestedAt().isAfter(now.minus(ORPHAN_AFTER));
      if (runId == null && !young) {
        LOG.infof("Ending lease %s of run %s: no live run names it", lease.id(), lease.ownerRef());
        endQuietly(lease.id());
      }
    }
    for (String runId : runs.queuedWithoutLease()) {
      request(runId);
    }
  }

  /** For a suite: wait until no lease request is pending on the request thread. */
  boolean awaitRequests(Duration timeout) {
    java.util.concurrent.CompletableFuture<Void> marker = new java.util.concurrent.CompletableFuture<>();
    try {
      requests.execute(() -> marker.complete(null));
      marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** For a suite: whether the run's row is RUNNING. */
  boolean running(String runId) {
    CiRun row = runs.runRow(runId);
    return row != null && row.status == CiRunStatus.RUNNING;
  }
}
