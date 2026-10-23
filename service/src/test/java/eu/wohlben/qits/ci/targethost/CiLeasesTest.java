package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.daemonhost.CiDaemonRegistry;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.daemonhost.ScriptedLeaseDaemon;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.StubIdp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A run's lease and its token</b> (epic qits-1481, qits-1544; dossier "Leases and tokens per slot
 * type"): the run's {@code ci-run} token is minted <em>before</em> its lease is asked for and rides
 * in the spec, and it is deleted at qits-idp when the run closes — and as soon as the lease reports
 * it ended, whichever comes first. Plain JUnit: a real {@link StubIdp} on a socket behind a real
 * {@link RunCommissions}, a recording qits-system, and the run table stood in for.
 */
class CiLeasesTest {

  private static final String RUN = "8e1f0a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

  private static final UUID LEASE = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");

  private static final UUID TARGET = UUID.fromString("9f8e7d6c-5b4a-4c3d-8e2f-1a0b9c8d7e6f");

  private StubIdp idp;

  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  private final List<SystemLeases.CreateLease> created = new ArrayList<>();

  private final CompletableFuture<String> executed = new CompletableFuture<>();

  private CiLeases leases;

  private LeaseStepRunner steps;

  private CiDaemonRegistry registry;

  /** The run row the stand-in run table answers. */
  private final CiRun row = queuedRun();

  @BeforeEach
  void wire() {
    idp = new StubIdp();
    registry = ScriptedLeaseDaemon.registry(Duration.ofSeconds(30));
    RunCommissions commissions = idp.runCommissions(Duration.ofMillis(200));

    leases = new CiLeases();
    leases.system = new RecordingSystem();
    leases.runs = new StandInRuns();
    leases.commissions = commissions;
    leases.daemons = registry;
    leases.settings = SlotFixtures.shippedSettings();
    leases.settings.pins = new CiDaemonPins();
    leases.settings.pins.versionOverride = Optional.empty();
    leases.addresses = CiSlotAddresses.forDomain(SlotFixtures.DOMAIN);

    steps = new LeaseStepRunner();
    steps.daemons = registry;
    steps.relay = new CiStepRelay();
    steps.commissions = commissions;
    steps.settings = leases.settings;
    steps.leases = leases;
    leases.steps = steps;
  }

  @AfterEach
  void stop() {
    idp.close();
    leases.shutdown();
  }

  @Test
  void theTokenIsMintedBeforeTheLeaseAndRidesInItsSpec() {
    leases.requestNow(RUN);

    assertEquals(
        List.of("commission a token", "POST /system/api/leases", "record lease " + LEASE),
        calls.subList(0, 3),
        "the token exists before anything can be granted to present it");
    assertEquals(1, idp.postedTokens.size());
    assertEquals(1, created.size());
    SystemLeases.CreateLease request = created.get(0);
    assertEquals("CI", request.slotType());
    assertEquals(RUN, request.ownerRef());
    assertEquals(0, request.priority(), "an ordinary run asks what every other slot asks");
    assertEquals("qits_tok_stub-1", request.spec().env().get("QITS_TOKEN"));
    assertEquals("1001", request.spec().user());
    assertTrue(
        request.spec().image().startsWith("registry.qits.example.org/qits/build-images/ci-slot"),
        request.spec().image());
    // The launch the lease's daemon will name is recorded with it, bound to the token's subject.
    CiLeases.Launch launch = leases.launchOf(RUN).orElseThrow();
    assertEquals(LEASE, launch.leaseId());
    assertEquals(request.spec().env().get("QITS_CI_DAEMON_ID"), launch.daemonId());
    assertEquals(request.spec().env().get("QITS_TOKEN_SUBJECT"), launch.tokenSubject());
    assertEquals(CiDaemonRegistry.Phase.LAUNCHED, registry.phaseOf(launch.daemonId()));
  }

  @Test
  void theRunsCloseEndsTheLeaseAndDeletesTheToken() {
    leases.requestNow(RUN);
    String tokenId = "token-1";
    assertTrue(idp.deletedTokens.isEmpty());

    steps.runClosed(RUN);

    assertTrue(calls.contains("DELETE /system/api/leases/" + LEASE), calls.toString());
    assertTrue(idp.deletedTokens.contains(tokenId), "the token is gone at qits-idp");
    assertTrue(leases.launchOf(RUN).isEmpty());
    assertEquals(0, registry.size(), "and the launch is forgotten");
  }

  @Test
  void aGrantedLeaseClaimsTheRunAndDrivesIt() throws Exception {
    leases.requestNow(RUN);
    leases.observe(
        new SystemLeases.LeaseView(LEASE, RUN, "RUNNING", TARGET, null, null, Instant.now()));

    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS));
    assertTrue(calls.contains("claim " + RUN + " on " + TARGET), calls.toString());
  }

  @Test
  void aLeaseThatFailsBeforeTheRunStartedDeletesTheTokenAndFailsTheRunsFirstStep()
      throws Exception {
    leases.requestNow(RUN);
    leases.observe(
        new SystemLeases.LeaseView(
            LEASE, RUN, "FAILED", TARGET, null, "spec.env.HOME is set by the slot's policy", null));

    assertTrue(idp.deletedTokens.contains("token-1"), "the token goes when the lease ends");
    // Claimed anyway, so the run's first step answers LAUNCH_FAILED with the lease's words.
    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS));
    assertTrue(calls.contains("claim " + RUN + " on null"), calls.toString());
    assertTrue(
        steps.endedLeases().get(RUN).contains("spec.env.HOME is set by the slot's policy"),
        steps.endedLeases().toString());
  }

  @Test
  void aLeaseThatCannotBeRecordedIsEndedAgainAndItsTokenGiven() {
    ((StandInRuns) leases.runs).refuseRecord = true;
    leases.requestNow(RUN);

    assertTrue(calls.contains("DELETE /system/api/leases/" + LEASE), calls.toString());
    assertTrue(idp.deletedTokens.contains("token-1"));
    assertTrue(leases.launchOf(RUN).isEmpty());
    assertEquals(0, registry.size());
  }

  @Test
  void aSystemThatDoesNotAnswerLeavesTheRunQueuedForTheReconcile() {
    ((RecordingSystem) leases.system).down = true;
    leases.requestNow(RUN);

    assertTrue(leases.launchOf(RUN).isEmpty());
    assertFalse(calls.stream().anyMatch(call -> call.startsWith("record lease")));
    assertEquals(0, registry.size(), "no launch is left waiting for a lease that does not exist");
  }

  @Test
  void theLeaseDescribesHowItEnded() {
    assertEquals(
        "lease " + LEASE + " EXITED (exit 3) on target " + TARGET + ": the daemon exited",
        CiLeases.describe(
            new SystemLeases.LeaseView(LEASE, RUN, "EXITED", TARGET, 3, " the daemon exited ", null)));
    assertNull(CiLeases.State.parse("BOGUS"));
  }

  // --- stand-ins --------------------------------------------------------------------------------

  private static CiRun queuedRun() {
    CiRun run = new CiRun();
    run.id = RUN;
    run.repoId = "5ca0e7e9-repo";
    run.projectId = "qits";
    run.repoName = "qits-ci-service";
    run.branch = "main";
    run.commitSha = "d".repeat(40);
    run.status = CiRunStatus.QUEUED;
    run.triggerType = CiTriggerType.EVENT;
    run.triggerEventName = "ReleaseRequestChanged";
    run.createdAt = Instant.now();
    return run;
  }

  /** qits-system, recording what it is asked, in the order it is asked. */
  private final class RecordingSystem extends SystemLeases {
    volatile boolean down;

    @Override
    public LeaseView create(CreateLease request) {
      if (down) {
        throw new SystemUnavailable("POST /system/api/leases did not answer", 0);
      }
      // The token must already exist when the lease is asked for.
      if (!idp.postedTokens.isEmpty()) {
        calls.add("commission a token");
      }
      calls.add("POST /system/api/leases");
      created.add(request);
      return new LeaseView(LEASE, request.ownerRef(), "QUEUED", null, null, null, Instant.now());
    }

    @Override
    public Optional<LeaseView> get(UUID leaseId) {
      return Optional.empty();
    }

    @Override
    public List<LeaseView> list() {
      return List.of();
    }

    @Override
    public Optional<LeaseView> end(UUID leaseId) {
      calls.add("DELETE /system/api/leases/" + leaseId);
      return Optional.empty();
    }
  }

  /** The run table, answering for one queued run. */
  private final class StandInRuns extends CiRunService {
    volatile boolean refuseRecord;

    @Override
    public Optional<Reservation> leaseCandidate(String runId) {
      return Optional.of(new Reservation(row, request()));
    }

    @Override
    public String resolveStepImage(String image) {
      return "dev-qits-artifacts:8080/" + image;
    }

    @Override
    public boolean recordLease(
        String runId, UUID leaseId, String daemonId, String tokenSubject, String daemonVersion) {
      calls.add("record lease " + leaseId);
      if (refuseRecord) {
        return false;
      }
      row.leaseId = leaseId;
      return true;
    }

    @Override
    public Optional<Reservation> claimLeased(String runId, UUID leaseId, UUID targetId) {
      calls.add("claim " + runId + " on " + targetId);
      return Optional.of(new Reservation(row, request()));
    }

    @Override
    public void executeReserved(Reservation reservation) {
      executed.complete(reservation.run().id);
    }

    @Override
    public CiRun runRow(String runId) {
      return row;
    }

    private EventRun request() {
      return new EventRun(
          CiRepoRef.of(row.repoId, row.projectId, row.repoName),
          row.branch,
          row.commitSha,
          null,
          "0b5f3c1e-0000-4000-8000-000000000001",
          "ReleaseRequestChanged",
          Instant.parse("2026-10-23T10:00:00Z"),
          "{\"releaseRequestId\":\"rr-1\"}",
          "",
          null);
    }
  }
}
