package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiStepRunner;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.daemonhost.ScriptedLeaseDaemon;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.StubIdp;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.Close;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A green QA run's CI slot, held for its release</b> (epic qits-732, qits-1788): the seam's half
 * of entering a hold, every reason that ends one, and the lease bookkeeping that keeps a held lease
 * alive. Plain JUnit, {@link CiLeasesTest}'s wiring: a real {@link CiDaemonRegistry} with a scripted
 * daemon on one connection, a real {@link StubIdp} behind a real {@link RunCommissions}, a recording
 * qits-system, the run table stood in for, and the {@code ci_slot_hold} rows held in memory.
 *
 * <p>The run's half — which runs are offered, before the verdict, and that a held run is no active
 * run — is {@code CiSlotHoldOfferTest}'s, in the {@code ci} module.
 */
class CiSlotHoldTest {

  private static final String RUN = "8e1f0a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

  private static final String NEWER_RUN = "8e1f0a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5c";

  private static final String REPO = "5ca0e7e9-repo";

  private static final String REQUEST = "6c1f9a52-0000-4000-8000-000000000042";

  private static final String MERGED = "d".repeat(40);

  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

  private StubIdp idp;

  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  /** Each run's lease, by owner ref. */
  private final Map<String, UUID> leaseOf = new ConcurrentHashMap<>();

  /** What qits-system lists, for the reconcile. */
  private final List<SystemLeases.LeaseView> listed = Collections.synchronizedList(new ArrayList<>());

  private final Map<String, CiRun> rows = new ConcurrentHashMap<>();

  private final InMemoryRows holdRows = new InMemoryRows();

  private CiLeases leases;

  private LeaseStepRunner steps;

  private CiSlotHolds holds;

  private CiDaemonRegistry registry;

  private final List<ScriptedLeaseDaemon> daemons = new ArrayList<>();

  @BeforeEach
  void wire() {
    idp = new StubIdp();
    registry = ScriptedLeaseDaemon.registry(Duration.ofSeconds(30));
    RunCommissions commissions = idp.runCommissions(Duration.ofMillis(200));

    holds = new CiSlotHolds();
    holds.system = new RecordingSystem();
    holds.daemons = registry;
    holds.commissions = commissions;
    holds.rows = holdRows;
    holds.enabled = true;
    holds.timeout = Duration.ofSeconds(60);
    holds.clock = Clock.fixed(NOW, ZoneOffset.UTC);

    leases = new CiLeases();
    leases.system = holds.system;
    leases.runs = new StandInRuns();
    leases.commissions = commissions;
    leases.daemons = registry;
    leases.settings = SlotFixtures.shippedSettings();
    leases.settings.pins = new CiDaemonPins();
    leases.settings.pins.versionOverride = Optional.empty();
    leases.addresses = CiSlotAddresses.forDomain(SlotFixtures.DOMAIN);
    leases.holds = holds;

    steps = new LeaseStepRunner();
    steps.daemons = registry;
    steps.relay = new CiStepRelay();
    steps.commissions = commissions;
    steps.settings = leases.settings;
    steps.leases = leases;
    steps.holds = holds;
    leases.steps = steps;

    rows.put(RUN, queuedRun(RUN));
    rows.put(NEWER_RUN, queuedRun(NEWER_RUN));
  }

  @AfterEach
  void stop() {
    daemons.forEach(ScriptedLeaseDaemon::close);
    idp.close();
    leases.shutdown();
  }

  // --- entering ---------------------------------------------------------------------------------

  @Test
  public void aGreenQaRunOnACapabilityThreeDaemonKeepsItsLeaseDaemonAndToken() {
    ScriptedLeaseDaemon daemon = leasedAndDialled(RUN, 3);

    assertTrue(steps.holdSlot(candidate(RUN)), "held");

    UUID lease = leaseOf.get(RUN);
    assertTrue(holds.holds(lease));
    assertTrue(holdRows.rows.containsKey(lease), "the row is written");
    CiSlotHolds.Hold row = holdRows.rows.get(lease);
    assertEquals(RUN, row.heldFromRunId(), "still the lease's ownerRef");
    assertEquals(REQUEST, row.releaseRequestId());
    assertEquals(MERGED, row.mergedSha());
    assertEquals(NOW.plusSeconds(60), row.expiresAt());
    assertFalse(calls.contains("DELETE " + lease), "no lease ended: " + calls);
    assertFalse(sent(daemon).stream().anyMatch(Close.class::isInstance), "no Close");
    assertTrue(idp.deletedTokens.isEmpty(), "the phase-1 token is kept");
    assertTrue(leases.launchOf(RUN).isEmpty(), "the launch moved from the run to the hold");
    assertEquals(CiDaemonRegistry.Phase.INITIALIZED, registry.phaseOf(row.daemonId()));
    assertEquals(
        List.of(new Ack(3)),
        sent(daemon).stream().filter(Ack.class::isInstance).toList(),
        "a capability-3 daemon is answered in kind");
  }

  @Test
  public void aCapabilityTwoDaemonIsAckedInKindAndItsRunClosesAsBefore() {
    ScriptedLeaseDaemon daemon = leasedAndDialled(RUN, 2);

    assertEquals(
        List.of(new Ack(2)),
        sent(daemon).stream().filter(Ack.class::isInstance).toList(),
        "a v2 daemon from a pre-deploy lease exits on an Ack version it does not know");
    assertFalse(steps.holdSlot(candidate(RUN)), "a v2 launch is never held");
    steps.runClosed(RUN);

    assertTrue(calls.contains("DELETE " + leaseOf.get(RUN)), calls.toString());
    assertTrue(sent(daemon).stream().anyMatch(Close.class::isInstance));
    assertTrue(idp.deletedTokens.contains("token-1"));
    assertTrue(holdRows.rows.isEmpty());
  }

  @Test
  public void withTheSwitchOffEveryRunClosesAsBefore() {
    holds.enabled = false;
    ScriptedLeaseDaemon daemon = leasedAndDialled(RUN, 3);

    assertFalse(steps.holdSlot(candidate(RUN)));
    steps.runClosed(RUN);

    assertTrue(calls.contains("DELETE " + leaseOf.get(RUN)), calls.toString());
    assertTrue(sent(daemon).stream().anyMatch(Close.class::isInstance));
    assertTrue(holdRows.rows.isEmpty());
  }

  @Test
  public void aLaunchWhoseLeaseAlreadyEndedIsNotHeld() {
    leasedAndDialled(RUN, 3);
    steps.leaseEnded(RUN, "lease EXITED");

    assertFalse(steps.holdSlot(candidate(RUN)));
    assertTrue(holdRows.rows.isEmpty());
  }

  @Test
  public void aNewerHoldOfTheSameRequestReleasesTheOlder() {
    ScriptedLeaseDaemon older = leasedAndDialled(RUN, 3);
    assertTrue(steps.holdSlot(candidate(RUN)));
    leasedAndDialled(NEWER_RUN, 3);

    assertTrue(steps.holdSlot(candidate(NEWER_RUN)));

    assertFalse(holds.holds(leaseOf.get(RUN)), "superseded");
    assertTrue(holds.holds(leaseOf.get(NEWER_RUN)));
    assertEquals(Set.of(leaseOf.get(NEWER_RUN)), holdRows.rows.keySet());
    assertTrue(calls.contains("DELETE " + leaseOf.get(RUN)), calls.toString());
    assertFalse(calls.contains("DELETE " + leaseOf.get(NEWER_RUN)));
    assertTrue(sent(older).stream().anyMatch(Close.class::isInstance));
    assertTrue(idp.deletedTokens.contains("token-1"), "the older run's token goes");
    assertFalse(idp.deletedTokens.contains("token-2"), "the newer one's stays");
  }

  // --- ending -----------------------------------------------------------------------------------

  @Test
  public void noEventWithinTheWindowEndsTheLease() {
    ScriptedLeaseDaemon daemon = held(RUN);
    UUID lease = leaseOf.get(RUN);

    holds.sweep();
    assertTrue(holds.holds(lease), "not yet");

    holds.clock = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);
    holds.sweep();

    assertReleased(lease, daemon, "token-1");
  }

  @Test
  public void aGatesUnmetStateEndsTheHoldAtOnce() {
    ScriptedLeaseDaemon daemon = held(RUN);
    UUID lease = leaseOf.get(RUN);

    holds.onReleaseRequestStateChanged(REPO, REQUEST, "RELEASED");
    holds.onReleaseRequestStateChanged(REPO, REQUEST, "READY");
    holds.onReleaseRequestStateChanged("another-repo", REQUEST, "REJECTED");
    holds.onReleaseRequestStateChanged(REPO, "another-request", "REJECTED");
    assertTrue(holds.holds(lease), "a request that releases, or another one, ends nothing");

    holds.onReleaseRequestStateChanged(REPO, REQUEST, "REJECTED");

    assertReleased(lease, daemon, "token-1");
    // Idempotent: a second arrival ends nothing a second time.
    calls.clear();
    holds.onReleaseRequestStateChanged(REPO, REQUEST, "REJECTED");
    assertTrue(calls.isEmpty(), calls.toString());
  }

  @Test
  public void anOpenApprovalOnTheHeldFoldEndsTheHoldAndAnotherFoldsDoesNot() {
    ScriptedLeaseDaemon daemon = held(RUN);
    UUID lease = leaseOf.get(RUN);

    holds.onApprovalRequirementChanged(REPO, REQUEST, "e".repeat(40), "OPEN");
    holds.onApprovalRequirementChanged(REPO, REQUEST, MERGED, "SATISFIED");
    assertTrue(holds.holds(lease), "another fold's requirement, or a satisfied one, ends nothing");

    holds.onApprovalRequirementChanged(REPO, REQUEST, MERGED, "OPEN");

    assertReleased(lease, daemon, "token-1");
  }

  @Test
  public void aHeldLeaseSeenExitedEndsTheHold() {
    ScriptedLeaseDaemon daemon = held(RUN);
    UUID lease = leaseOf.get(RUN);

    // Its ownerRef is still the phase-1 run, which this process no longer drives.
    leases.observe(
        new SystemLeases.LeaseView(lease, RUN, "RUNNING", null, null, null, NOW.minusSeconds(300)));
    assertTrue(holds.holds(lease));

    leases.observe(
        new SystemLeases.LeaseView(lease, RUN, "EXITED", null, 0, "the daemon exited", null));

    assertReleased(lease, daemon, "token-1");
    assertTrue(steps.endedLeases().isEmpty(), "no run was told its lease ended: the run is over");
  }

  @Test
  public void aLostDaemonEndsTheHold() {
    ScriptedLeaseDaemon daemon = held(RUN);
    UUID lease = leaseOf.get(RUN);
    String daemonId = holdRows.rows.get(lease).daemonId();

    registry.reap(daemonId);
    holds.sweep();

    assertFalse(holds.holds(lease));
    assertTrue(calls.contains("DELETE " + lease), calls.toString());
    assertTrue(holdRows.rows.isEmpty());
    assertTrue(daemon.closed());
  }

  @Test
  public void theReconcileNeverEndsAHeldLeaseOlderThanTheOrphanWindow() {
    held(RUN);
    UUID lease = leaseOf.get(RUN);
    listed.add(
        new SystemLeases.LeaseView(
            lease, RUN, "RUNNING", null, null, null, NOW.minus(CiLeases.ORPHAN_AFTER).minusSeconds(600)));

    leases.reconcile();

    assertFalse(calls.contains("DELETE " + lease), "a held lease is named: " + calls);
    assertTrue(holds.holds(lease));

    // And a held lease qits-system no longer lists is a slot that is gone.
    listed.clear();
    leases.reconcile();

    assertFalse(holds.holds(lease));
    assertTrue(holdRows.rows.isEmpty());
  }

  @Test
  public void aStartingProcessEndsEveryHoldItFinds() {
    UUID leftover = UUID.fromString("00000000-0000-4000-8000-000000000732");
    holdRows.rows.put(
        leftover,
        new CiSlotHolds.Hold(
            leftover, REPO, REQUEST, "qits-ci-service-rr-42", RUN, "ci-daemon-gone", null, null,
            null, MERGED, NOW.minusSeconds(30), NOW.plusSeconds(30)));

    holds.releaseLeftovers();

    assertTrue(holdRows.rows.isEmpty());
    assertTrue(calls.contains("DELETE " + leftover), calls.toString());
  }

  @Test
  public void aTakenHoldIsHandedOverWithoutATeardown() {
    ScriptedLeaseDaemon daemon = held(RUN);
    UUID lease = leaseOf.get(RUN);

    Optional<CiSlotHolds.Hold> taken = holds.take(REPO, REQUEST);

    assertEquals(lease, taken.orElseThrow().leaseId());
    assertTrue(taken.get().tokenSubject() != null, "what an adoption re-keys the launch with");
    assertFalse(holds.holds(lease));
    assertTrue(holdRows.rows.isEmpty());
    assertFalse(calls.contains("DELETE " + lease));
    assertFalse(sent(daemon).stream().anyMatch(Close.class::isInstance));
    assertTrue(holds.take(REPO, REQUEST).isEmpty(), "taken once");
    assertFalse(holds.release(lease, "late"), "and nothing is left to release");
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** A run leased, its daemon dialled announcing {@code capability}, and its worktree ready. */
  private ScriptedLeaseDaemon leasedAndDialled(String runId, int capability) {
    leases.requestNow(runId);
    CiLeases.Launch launch = leases.launchOf(runId).orElseThrow();
    ScriptedLeaseDaemon daemon =
        ScriptedLeaseDaemon.dial(registry, launch.daemonId(), launch.tokenSubject(), capability);
    daemons.add(daemon);
    return daemon;
  }

  /** {@link #leasedAndDialled} on capability 3, then held. */
  private ScriptedLeaseDaemon held(String runId) {
    ScriptedLeaseDaemon daemon = leasedAndDialled(runId, 3);
    assertTrue(steps.holdSlot(candidate(runId)));
    calls.removeIf(call -> call.startsWith("DELETE"));
    return daemon;
  }

  private static CiStepRunner.SlotHold candidate(String runId) {
    return new CiStepRunner.SlotHold(
        runId, REPO, REQUEST, "qits-ci-service-rr-42", MERGED, null, "2026.1031.732000");
  }

  private void assertReleased(UUID lease, ScriptedLeaseDaemon daemon, String token) {
    assertFalse(holds.holds(lease), "the hold is gone");
    assertFalse(holdRows.rows.containsKey(lease), "and its row");
    assertTrue(calls.contains("DELETE " + lease), "the lease is ended: " + calls);
    assertTrue(sent(daemon).stream().anyMatch(Close.class::isInstance), "the daemon is told Close");
    assertTrue(daemon.closed(), "and its launch reaped");
    assertTrue(idp.deletedTokens.contains(token), "the phase-1 token is deleted");
  }

  private static List<CiDaemonMessage> sent(ScriptedLeaseDaemon daemon) {
    return daemon.received();
  }

  private static CiRun queuedRun(String id) {
    CiRun run = new CiRun();
    run.id = id;
    run.repoId = REPO;
    run.projectId = "qits";
    run.repoName = "qits-ci-service";
    run.branch = "release/" + REQUEST;
    run.commitSha = MERGED;
    run.status = CiRunStatus.QUEUED;
    run.triggerType = CiTriggerType.EVENT;
    run.triggerEventName = "ReleaseRequestChanged";
    run.createdAt = NOW;
    return run;
  }

  /** qits-system: one lease per run, ended on request, listed as the test says. */
  private final class RecordingSystem extends SystemLeases {
    @Override
    public LeaseView create(CreateLease request) {
      UUID id = UUID.nameUUIDFromBytes(request.ownerRef().getBytes());
      leaseOf.put(request.ownerRef(), id);
      return new LeaseView(id, request.ownerRef(), "QUEUED", null, null, null, NOW);
    }

    @Override
    public Optional<LeaseView> get(UUID leaseId) {
      return Optional.empty();
    }

    @Override
    public List<LeaseView> list() {
      synchronized (listed) {
        return List.copyOf(listed);
      }
    }

    @Override
    public Optional<LeaseView> end(UUID leaseId) {
      calls.add("DELETE " + leaseId);
      return Optional.empty();
    }
  }

  /** The run table, answering for the two queued runs and leasing nothing else. */
  private final class StandInRuns extends CiRunService {
    @Override
    public Optional<Reservation> leaseCandidate(String runId) {
      CiRun row = rows.get(runId);
      return row == null ? Optional.empty() : Optional.of(new Reservation(row, request(row)));
    }

    @Override
    public boolean recordLease(
        String runId, UUID leaseId, String daemonId, String tokenSubject, String daemonVersion) {
      rows.get(runId).leaseId = leaseId;
      return true;
    }

    @Override
    public Optional<Reservation> claimLeased(String runId, UUID leaseId, UUID targetId) {
      return Optional.empty();
    }

    @Override
    public CiRun runRow(String runId) {
      return rows.get(runId);
    }

    @Override
    public List<CiRun> leasedRuns() {
      return List.of();
    }

    @Override
    public List<String> queuedWithoutLease() {
      return List.of();
    }

    private EventRun request(CiRun row) {
      return new EventRun(
          CiRepoRef.of(row.repoId, row.projectId, row.repoName),
          row.branch,
          row.commitSha,
          null,
          "0b5f3c1e-0000-4000-8000-" + row.id.substring(row.id.length() - 12),
          "ReleaseRequestChanged",
          NOW,
          "{\"releaseRequestId\":\"" + REQUEST + "\"}",
          "",
          null);
    }
  }

  /** The {@code ci_slot_hold} rows, in memory, unique per request as the index is. */
  private static final class InMemoryRows extends CiSlotHoldRows {
    final Map<UUID, CiSlotHolds.Hold> rows = new ConcurrentHashMap<>();

    @Override
    public void insert(CiSlotHolds.Hold hold) {
      boolean taken =
          rows.values().stream()
              .anyMatch(
                  h ->
                      h.repoId().equals(hold.repoId())
                          && h.releaseRequestId().equals(hold.releaseRequestId()));
      if (taken || rows.putIfAbsent(hold.leaseId(), hold) != null) {
        throw new IllegalStateException("ci_slot_hold_rr");
      }
    }

    @Override
    public Optional<CiSlotHolds.Hold> remove(UUID leaseId) {
      return Optional.ofNullable(rows.remove(leaseId));
    }

    @Override
    public Optional<CiSlotHolds.Hold> remove(String repoId, String releaseRequestId) {
      Optional<CiSlotHolds.Hold> found =
          rows.values().stream()
              .filter(h -> h.repoId().equals(repoId) && h.releaseRequestId().equals(releaseRequestId))
              .findFirst();
      found.ifPresent(h -> rows.remove(h.leaseId()));
      return found;
    }

    @Override
    public List<CiSlotHolds.Hold> removeAllExcept(Set<UUID> kept) {
      List<CiSlotHolds.Hold> left =
          rows.values().stream().filter(h -> !kept.contains(h.leaseId())).toList();
      left.forEach(h -> rows.remove(h.leaseId()));
      return left;
    }
  }
}
