package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.control.CiStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.entity.CiSlotHold;
import eu.wohlben.qits.ci.idp.RunCommissions;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>CI slots held for a release</b> (epic qits-732, qits-1788). A run is one CI lease — one ci-slot
 * container, one qits-ci-daemon. A release request's QA run (phase 1) that settles {@code SUCCESS}
 * may keep its lease open, so that the release (phase 2, on {@code SCMRelease}) can continue in the
 * same container instead of pulling, cloning and building the same tree a second time.
 *
 * <h2>Entering ({@link #enter})</h2>
 *
 * <p>{@code CiRunService} offers a green QA run composed from {@code release.yml} to its seam after
 * the terminal row is written and before {@code BuildSuccessful} leaves; {@link
 * LeaseStepRunner#holdSlot} checks the slot's half — the launch still alive, its daemon having
 * announced capability {@value #MIN_CAPABILITY} or more — and hands it here. With {@code
 * qits.ci.release-hold.enabled} on, the {@code ci_slot_hold} row is written, the launch moves from
 * the run to the hold, and nothing is closed: no {@code Close}, no lease ended, the phase-1 token
 * kept. A newer hold for the same request releases the older one first. The run itself is settled and
 * announced exactly as before — the hold is no run status, and {@code /runs/active} never lists it.
 *
 * <h2>Ending ({@link #release})</h2>
 *
 * <p>One method, idempotent, and the run's close made late: the row deleted, {@code Close} to the
 * daemon, the launch reaped, the lease ended at qits-system and the run's token deleted, then one INFO
 * line naming why. It fires on the first of:
 *
 * <ul>
 *   <li>{@code gates-unmet:<STATE>} — {@code ReleaseRequestStateChanged} in {@link #GATES_UNMET};
 *   <li>{@code approval-pending} — {@code ApprovalRequirementChanged} {@code OPEN} on the held fold;
 *   <li>{@code timeout} — {@code qits.ci.release-hold.timeout} after the hold, swept every 5s;
 *   <li>{@code lease-ended} — the lease seen {@code EXITED}, {@code FAILED}, {@code ENDING} or {@code
 *       ENDED}, or gone from qits-system's listing ({@link CiLeases});
 *   <li>{@code daemon-lost} — the daemon's socket gone past {@code
 *       qits.ci.daemon.reconnect-grace-seconds};
 *   <li>{@code restart} — every row a previous process left: the launch a hold needs is in-memory;
 *   <li>{@code superseded} — a newer hold of the same request.
 * </ul>
 *
 * <h2>Taking ({@link #take})</h2>
 *
 * <p>The adoption's door (qits-1789): the hold of one request, removed atomically and handed over
 * <em>without</em> a teardown, so a phase-2 run can continue in the slot. Nothing calls it yet.
 *
 * <p><b>The in-memory table is this process's truth</b>, and the row its durable record. Rows are
 * written only beside an entry here and every row a previous process left is released at startup,
 * so within one process the two agree; the lookups read memory, and {@link CiLeases}' reconcile
 * names exactly these leases — a row a failed startup could not delete names nothing, so its lease
 * is the orphan sweep's like any other.
 */
@ApplicationScoped
public class CiSlotHolds {

  private static final Logger LOG = Logger.getLogger(CiSlotHolds.class);

  /** The daemon capability that can be held: the first to speak {@code Continue} (qits-ci-daemon). */
  public static final int MIN_CAPABILITY = 3;

  /** The {@code ReleaseRequestStateChanged} states that end a hold: the release is not coming. */
  public static final Set<String> GATES_UNMET =
      Set.of("REJECTED", "FAILED", "CONFLICTED", "WITHDRAWN", "OBSOLETE");

  /** The {@code ApprovalRequirementChanged} state that ends a hold: a person has to act first. */
  public static final String APPROVAL_OPEN = "OPEN";

  static final String GATES_UNMET_PREFIX = "gates-unmet:";
  static final String APPROVAL_PENDING = "approval-pending";
  static final String TIMEOUT = "timeout";
  static final String LEASE_ENDED = "lease-ended";
  static final String DAEMON_LOST = "daemon-lost";
  static final String RESTART = "restart";
  static final String SUPERSEDED = "superseded";

  @Inject SystemLeases system;

  @Inject CiDaemonRegistry daemons;

  @Inject RunCommissions commissions;

  @Inject CiSlotHoldRows rows;

  @ConfigProperty(name = "qits.ci.release-hold.enabled")
  boolean enabled;

  @ConfigProperty(name = "qits.ci.release-hold.timeout")
  Duration timeout;

  /** The hold window's clock; a suite moves it. */
  Clock clock = Clock.systemUTC();

  /**
   * One held slot. {@code tokenSubject} is the phase-1 token's subject the slot's daemon dials as —
   * what an adoption re-keys the launch with — and null on a hold read back from a row, which never
   * carries it.
   */
  public record Hold(
      UUID leaseId,
      String repoId,
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String heldFromRunId,
      String daemonId,
      String tokenSubject,
      UUID targetId,
      String daemonVersion,
      String mergedSha,
      Instant heldAt,
      Instant expiresAt) {

    static Hold of(CiSlotHold row) {
      return new Hold(
          row.leaseId,
          row.repoId,
          row.releaseRequestId,
          row.releaseRequestQualifiedId,
          row.heldFromRunId,
          row.daemonId,
          null,
          row.targetId,
          row.daemonVersion,
          row.mergedSha,
          row.heldAt,
          row.expiresAt);
    }

    /** The request as a person reads it: its qualified id, else its UUID. */
    public String requestName() {
      return releaseRequestQualifiedId != null ? releaseRequestQualifiedId : releaseRequestId;
    }
  }

  private final ConcurrentHashMap<UUID, Hold> held = new ConcurrentHashMap<>();

  /** Whether this deployment holds slots at all ({@code qits.ci.release-hold.enabled}). */
  public boolean enabled() {
    return enabled;
  }

  // --- entering ---------------------------------------------------------------------------------

  /**
   * Holds {@code candidate}'s slot, whose launch is {@code launch}: the older hold of the same
   * request released first, the row written, the hold remembered. The slot's half of the conditions
   * is the caller's ({@link LeaseStepRunner#holdSlot}). Never throws.
   *
   * @return true when the slot is now held, false when it is not and the run should close as ever
   */
  boolean enter(CiStepRunner.SlotHold candidate, CiLeases.Launch launch) {
    if (!enabled) {
      return false;
    }
    Instant now = clock.instant();
    Hold hold =
        new Hold(
            launch.leaseId(),
            candidate.repoId(),
            candidate.releaseRequestId(),
            candidate.releaseRequestQualifiedId(),
            candidate.runId(),
            launch.daemonId(),
            launch.tokenSubject(),
            candidate.targetId(),
            candidate.daemonVersion(),
            candidate.mergedSha(),
            now,
            now.plus(timeout));
    try {
      heldFor(hold.repoId(), hold.releaseRequestId())
          .ifPresent(older -> release(older.leaseId(), SUPERSEDED));
      // A row no entry here names — a previous process's that its startup could not delete — would
      // refuse the insert on ci_slot_hold_rr; it is superseded the same way.
      rows.remove(hold.repoId(), hold.releaseRequestId())
          .ifPresent(stale -> teardown(stale, SUPERSEDED));
      rows.insert(hold);
    } catch (RuntimeException e) {
      LOG.warnf(
          "CI run %s: its slot could not be held for %s (%s); it closes",
          candidate.runId(), hold.requestName(), e.getMessage());
      return false;
    }
    held.put(hold.leaseId(), hold);
    LOG.infof(
        "CI slot hold %s of %s entered: run %s, daemon %s, until %s",
        hold.leaseId(), hold.requestName(), hold.heldFromRunId(), hold.daemonId(), hold.expiresAt());
    return true;
  }

  // --- reading ----------------------------------------------------------------------------------

  /** The hold of one release request, if this process holds one. */
  public Optional<Hold> heldFor(String repoId, String releaseRequestId) {
    if (repoId == null || releaseRequestId == null) {
      return Optional.empty();
    }
    return held.values().stream()
        .filter(h -> repoId.equals(h.repoId()) && releaseRequestId.equals(h.releaseRequestId()))
        .findFirst();
  }

  /** Whether this process holds the lease. */
  public boolean holds(UUID leaseId) {
    return leaseId != null && held.containsKey(leaseId);
  }

  /** Every lease this process holds — what the reconcile counts as named. */
  public Set<UUID> heldLeases() {
    return Set.copyOf(held.keySet());
  }

  // --- taking (the adoption's door, qits-1789) --------------------------------------------------

  /**
   * Removes the hold of one release request, atomically, and answers it <b>without</b> ending
   * anything: the lease, the daemon's launch and the phase-1 token are the taker's from here. Empty
   * when nothing is held for it, or when another caller took or released it first.
   */
  public Optional<Hold> take(String repoId, String releaseRequestId) {
    Optional<Hold> found = heldFor(repoId, releaseRequestId);
    if (found.isEmpty() || !held.remove(found.get().leaseId(), found.get())) {
      return Optional.empty();
    }
    try {
      rows.remove(found.get().leaseId());
    } catch (RuntimeException e) {
      LOG.warnf(
          "CI slot hold %s of %s: its row could not be deleted (%s); the next startup releases it",
          found.get().leaseId(), found.get().requestName(), e.getMessage());
    }
    return found;
  }

  // --- ending -----------------------------------------------------------------------------------

  /**
   * Ends the hold of one lease: the row deleted, {@code Close} to its daemon, the launch reaped, the
   * lease ended and the phase-1 run's token deleted — the close the run did not make — and one INFO
   * line naming {@code reason}. Idempotent and never throws: a second call, or one for a lease
   * nothing holds, does nothing.
   *
   * @return true when this call ended the hold
   */
  public boolean release(UUID leaseId, String reason) {
    if (leaseId == null) {
      return false;
    }
    Hold hold = held.remove(leaseId);
    Optional<Hold> row;
    try {
      row = rows.remove(leaseId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "CI slot hold %s: its row could not be deleted (%s); the next startup releases it",
          leaseId, e.getMessage());
      row = Optional.empty();
    }
    if (hold == null) {
      if (row.isEmpty()) {
        return false;
      }
      hold = row.get();
    }
    teardown(hold, reason);
    return true;
  }

  /**
   * What {@code LeaseStepRunner.runClosed} does for a run, for a hold — also for one {@link #take}
   * handed out that is given back unused. Never throws.
   */
  void teardown(Hold hold, String reason) {
    try {
      daemons.sendClose(hold.daemonId());
      daemons.reap(hold.daemonId());
    } catch (RuntimeException e) {
      LOG.debugf("CI slot hold %s: its daemon could not be closed: %s", hold.leaseId(), e.getMessage());
    }
    try {
      system.end(hold.leaseId());
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not end held lease %s (%s); the reconcile ends it", hold.leaseId(), e.getMessage());
    }
    String runId = hold.heldFromRunId();
    commissions.endAdoption(runId);
    commissions.release(runId);
    commissions.revokeOrphaned(runId);
    LOG.infof("CI slot hold %s of %s released: %s", hold.leaseId(), hold.requestName(), reason);
  }

  // --- the reasons ------------------------------------------------------------------------------

  /** A {@code ReleaseRequestStateChanged}: a state in {@link #GATES_UNMET} ends the request's hold. */
  public void onReleaseRequestStateChanged(String repoId, String releaseRequestId, String state) {
    if (state == null || !GATES_UNMET.contains(state)) {
      return;
    }
    heldFor(repoId, releaseRequestId)
        .ifPresent(hold -> release(hold.leaseId(), GATES_UNMET_PREFIX + state));
  }

  /**
   * An {@code ApprovalRequirementChanged}: {@code OPEN} on the fold the hold built ends it — a person
   * has to approve before anything releases. Another fold's requirement is a re-fold's business.
   */
  public void onApprovalRequirementChanged(
      String repoId, String releaseRequestId, String mergedSha, String state) {
    if (!APPROVAL_OPEN.equals(state) || mergedSha == null) {
      return;
    }
    heldFor(repoId, releaseRequestId)
        .filter(hold -> mergedSha.equals(hold.mergedSha()))
        .ifPresent(hold -> release(hold.leaseId(), APPROVAL_PENDING));
  }

  /** A held lease's state, from the event or the reconcile ({@link CiLeases#observe}). */
  void leaseObserved(SystemLeases.LeaseView lease) {
    CiLeases.State state = CiLeases.State.parse(lease.state());
    if (state == CiLeases.State.EXITED
        || state == CiLeases.State.FAILED
        || state == CiLeases.State.ENDING
        || state == CiLeases.State.ENDED) {
      release(lease.id(), LEASE_ENDED);
    }
  }

  @Scheduled(
      every = "5s",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = Scheduled.ApplicationNotRunning.class)
  void sweepOnSchedule() {
    sweep();
  }

  /** One pass: every hold past its window, or whose daemon is lost, is released. */
  void sweep() {
    Instant now = clock.instant();
    for (Hold hold : List.copyOf(held.values())) {
      if (!now.isBefore(hold.expiresAt())) {
        release(hold.leaseId(), TIMEOUT);
      } else if (daemons.lost(hold.daemonId())) {
        release(hold.leaseId(), DAEMON_LOST);
      }
    }
  }

  // --- a restart --------------------------------------------------------------------------------

  /**
   * At boot, off the startup thread: every hold a previous process left is released. Skipped in
   * test mode, {@code CiRunService.onStart}'s convention: a suite drives {@link #releaseLeftovers}
   * itself, and a boot thread racing a test's own rows would be a flake.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Thread leftovers = new Thread(this::releaseLeftovers, "ci-slot-hold-restart");
    leftovers.setDaemon(true);
    leftovers.start();
  }

  /** Releases every row there is ({@code restart}). Never throws. */
  void releaseLeftovers() {
    List<Hold> leftovers;
    try {
      leftovers = rows.removeAllExcept(heldLeases());
    } catch (RuntimeException e) {
      LOG.warnf("The CI slot holds a previous process left could not be read: %s", e.getMessage());
      return;
    }
    for (Hold leftover : leftovers) {
      teardown(leftover, RESTART);
    }
  }
}
