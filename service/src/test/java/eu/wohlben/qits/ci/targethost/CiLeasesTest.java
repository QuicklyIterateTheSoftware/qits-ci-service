package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

  private static final UUID OTHER_TARGET = UUID.fromString("2d3e4f5a-6b7c-4d8e-9f0a-1b2c3d4e5f6a");

  private StubIdp idp;

  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  private final List<SystemLeases.CreateLease> created = new ArrayList<>();

  private final CompletableFuture<String> executed = new CompletableFuture<>();

  /** Every rise the stand-in run table recorded. */
  private final List<Long> oomRises = Collections.synchronizedList(new ArrayList<>());

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
    // Holding nothing: a hold is CiSlotHoldTest's (epic qits-732).
    leases.holds = new CiSlotHolds();

    steps = new LeaseStepRunner();
    steps.daemons = registry;
    steps.relay = ScriptedLeaseDaemon.relay(4096);
    steps.commissions = commissions;
    steps.settings = leases.settings;
    steps.leases = leases;
    steps.registerTimeoutSeconds = 5;
    steps.leaseStartTimeoutSeconds = 5;
    steps.initTimeoutSeconds = 5;
    steps.stepTimeoutGraceSeconds = 5;
    steps.builderWaitMaxSeconds = 60;
    leases.steps = steps;
  }

  @AfterEach
  void stop() {
    idp.close();
    leases.shutdown();
  }

  @Test
  void theSlotImageIsThePinRecordedUnderTheBareTag() {
    // qits-757: a run's pin is keyed by the tag as spelled, with no registry host.
    String digest = "@sha256:" + "c".repeat(64);
    CiRun pinned = new CiRun();
    pinned.stepImages =
        eu.wohlben.qits.ci.entity.StepImages.encode(
            java.util.Map.of(
                eu.wohlben.qits.ci.control.CiSlot.IMAGE_TAG,
                "registry.qits.example.org/qits/build-images/ci-slot" + digest));
    assertEquals(
        "registry.qits.example.org/qits/build-images/ci-slot" + digest,
        CiLeases.slotImageOf(pinned));
    // A run accepted before the upgrade pinned under the retired host, and still finds its pin.
    CiRun older = new CiRun();
    older.stepImages =
        "{\"registry.dev.localhost:8080/qits/build-images/ci-slot:latest\":"
            + "\"registry.dev.localhost:8080/qits/build-images/ci-slot"
            + digest
            + "\"}";
    assertEquals(
        "registry.dev.localhost:8080/qits/build-images/ci-slot" + digest,
        CiLeases.slotImageOf(older),
        "the pin is found; the address plane moves its host to the public name");
    assertEquals(eu.wohlben.qits.ci.control.CiSlot.IMAGE_TAG, CiLeases.slotImageOf(new CiRun()));
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

  /**
   * qits-1809: a run whose daemon never dialled back has its lease ended with that reason, which
   * qits-system counts against the target; every other end of a lease names none.
   */
  @Test
  void aLeaseIsEndedWithAReasonOnlyWhenItsDaemonNeverDialledBack() {
    leases.requestNow(RUN);

    leases.end(RUN, SystemLeases.NEVER_DIALLED_BACK);

    assertTrue(
        calls.contains("DELETE /system/api/leases/" + LEASE + "?reason=never-dialled-back"),
        calls.toString());
    assertFalse(calls.contains("DELETE /system/api/leases/" + LEASE), calls.toString());
  }

  /** The reason travels as {@code ?reason=}, and a bare end sends no query at all. */
  @Test
  void theReasonIsTheEndsQueryAndABareEndSendsNone() throws Exception {
    List<String> requested = Collections.synchronizedList(new ArrayList<>());
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requested.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    try {
      SystemLeases client =
          SystemLeasesClients.against(
              "http://127.0.0.1:" + server.getAddress().getPort(), Optional.empty());

      assertTrue(client.end(LEASE, SystemLeases.NEVER_DIALLED_BACK).isEmpty());
      assertTrue(client.end(LEASE).isEmpty());
      assertTrue(client.end(LEASE, null).isEmpty());
    } finally {
      server.stop(0);
    }

    assertEquals(
        List.of(
            "DELETE /system/api/leases/" + LEASE + "?reason=never-dialled-back",
            "DELETE /system/api/leases/" + LEASE,
            "DELETE /system/api/leases/" + LEASE),
        requested);
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
  void aGrantedLeaseDrivesTheRunButOnlyRunningStartsItsRegisterClock() throws Exception {
    leases.requestNow(RUN);
    leases.observe(
        new SystemLeases.LeaseView(LEASE, RUN, "GRANTED", TARGET, null, null, Instant.now()));

    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS), "claimed and driven at the grant");
    assertNull(
        steps.leaseRunningSince(RUN),
        "granted is not started: the target is still pulling the ci-slot image (qits-1544)");

    // A RUNNING report of some other lease of the run's starts nothing.
    leases.observe(
        new SystemLeases.LeaseView(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
            RUN, "RUNNING", TARGET, null, null, Instant.now()));
    assertNull(steps.leaseRunningSince(RUN));

    leases.observe(
        new SystemLeases.LeaseView(LEASE, RUN, "RUNNING", TARGET, null, null, Instant.now()));
    assertNotNull(steps.leaseRunningSince(RUN), "RUNNING starts the register clock");
  }

  // --- a lease granted, queued again and regranted elsewhere (qits-1829) -------------------------

  /**
   * qits-system can move a {@code GRANTED} lease it holds back to {@code QUEUED} — the target
   * disconnected before it started the lease, or its runner stopped acting — and grant the same
   * lease id again, possibly on another target. The run's driver, already running, keeps running
   * throughout: it is claimed once, never fails and never retries, and the row's own idea of its
   * target follows the regrant.
   */
  @Test
  void aClaimedRunsLeaseRegrantedElsewhereRidesToRunningOnTheNewTarget() throws Exception {
    ((StandInRuns) leases.runs).holdDriver = new CountDownLatch(1);
    leases.requestNow(RUN);

    leases.observe(
        new SystemLeases.LeaseView(LEASE, RUN, "GRANTED", TARGET, null, null, Instant.now()));
    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS), "claimed and driven at the first grant");
    assertEquals(TARGET, row.targetId);
    assertNull(steps.leaseRunningSince(RUN), "granted alone never starts the register clock");

    // qits-system moves the lease back to QUEUED: the target went away before it started it.
    leases.observe(
        new SystemLeases.LeaseView(
            LEASE,
            RUN,
            "QUEUED",
            null,
            null,
            "target " + TARGET + " went away before it started the lease ... queued again",
            Instant.now()));
    assertNull(steps.leaseRunningSince(RUN), "still not running");

    // The same lease id, granted again, elsewhere.
    leases.observe(
        new SystemLeases.LeaseView(LEASE, RUN, "GRANTED", OTHER_TARGET, null, null, Instant.now()));
    assertEquals(OTHER_TARGET, row.targetId, "the row follows the regrant to the new target");
    assertNull(steps.leaseRunningSince(RUN), "granted on the new target still has not started it");

    leases.observe(
        new SystemLeases.LeaseView(LEASE, RUN, "RUNNING", OTHER_TARGET, null, null, Instant.now()));
    assertNotNull(steps.leaseRunningSince(RUN), "RUNNING starts the register clock, on the new target");
    assertEquals(OTHER_TARGET, row.targetId);

    assertEquals(
        1,
        calls.stream().filter(call -> call.startsWith("claim ")).count(),
        "claimed once, never a second time for the regrant: " + calls);
    assertFalse(steps.endedLeases().containsKey(RUN), "no LAUNCH_FAILED anywhere in this sequence");

    ((StandInRuns) leases.runs).holdDriver.countDown();
  }

  /**
   * A lease id that is not the one the launch this process holds names — stale, or another run's —
   * must never move the row's target, however it is reported.
   */
  @Test
  void aGrantedObservationOfAStaleLeaseIdLeavesTheTargetAlone() throws Exception {
    ((StandInRuns) leases.runs).holdDriver = new CountDownLatch(1);
    leases.requestNow(RUN);
    leases.observe(
        new SystemLeases.LeaseView(LEASE, RUN, "GRANTED", TARGET, null, null, Instant.now()));
    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS));
    assertEquals(TARGET, row.targetId);

    UUID stale = UUID.fromString("00000000-0000-4000-8000-000000000002");
    leases.observe(
        new SystemLeases.LeaseView(stale, RUN, "GRANTED", OTHER_TARGET, null, null, Instant.now()));

    assertEquals(
        TARGET, row.targetId, "a lease id that is not the launch's own must never move the target");
    assertFalse(
        calls.stream().anyMatch(call -> call.startsWith("regrant")),
        "a stale lease id never reaches the repository at all: " + calls);

    ((StandInRuns) leases.runs).holdDriver.countDown();
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

  // --- the reconcile's stale listing (qits-1827) -------------------------------------------------

  /**
   * The live incident: the listing was taken before the lease was asked, so it lacks a lease the
   * run's row and launch already name. The read of the lease answers it {@code QUEUED}, and the run
   * is left to wait for it — not claimed, not failed, its token and its lease kept.
   */
  @Test
  void aLeaseTheListingPredatesIsReadAndAQueuedOneLeftAlone() {
    leases.requestNow(RUN);
    ((RecordingSystem) leases.system).read =
        Optional.of(
            new SystemLeases.LeaseView(LEASE, RUN, "QUEUED", null, null, null, Instant.now()));

    leases.reconcile();

    assertTrue(calls.contains("GET /system/api/leases/" + LEASE), calls.toString());
    assertFalse(calls.stream().anyMatch(call -> call.startsWith("claim")), calls.toString());
    assertFalse(calls.stream().anyMatch(call -> call.startsWith("DELETE")), calls.toString());
    assertFalse(executed.isDone(), "nothing drives the run");
    assertEquals(LEASE, leases.launchOf(RUN).orElseThrow().leaseId(), "the launch is still held");
    assertTrue(idp.deletedTokens.isEmpty(), "the token is kept");
    assertFalse(steps.endedLeases().containsKey(RUN), "the step seam is told nothing");
  }

  /** Not listed, and the read answers 404: the lease really is gone, and the run is told so. */
  @Test
  void aLeaseNeitherListedNorReadableIsGone() throws Exception {
    leases.requestNow(RUN);

    leases.reconcile();

    assertTrue(calls.contains("GET /system/api/leases/" + LEASE), calls.toString());
    assertTrue(idp.deletedTokens.contains("token-1"), "the token goes with the lease");
    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS), "claimed, so its first step fails");
    assertTrue(calls.contains("claim " + RUN + " on null"), calls.toString());
    assertTrue(
        steps.endedLeases().get(RUN).contains("lease " + LEASE + " is gone from qits-system"),
        steps.endedLeases().toString());
  }

  /** Not listed, and the read answers it ended: the run ends in the lease's own words. */
  @Test
  void aLeaseTheReadFindsEndedEndsTheRunInItsOwnWords() throws Exception {
    leases.requestNow(RUN);
    SystemLeases.LeaseView ended =
        new SystemLeases.LeaseView(LEASE, RUN, "ENDED", TARGET, 1, "the target went away", null);
    ((RecordingSystem) leases.system).read = Optional.of(ended);

    leases.reconcile();

    assertTrue(idp.deletedTokens.contains("token-1"));
    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS));
    assertEquals(CiLeases.describe(ended), steps.endedLeases().get(RUN));
  }

  /** Not listed, and the read does not answer: nothing about the run changes this pass. */
  @Test
  void aLeaseThatCannotBeReadIsNeverReadAsGone() {
    leases.requestNow(RUN);
    ((RecordingSystem) leases.system).readDown = true;

    leases.reconcile();

    assertTrue(calls.contains("GET /system/api/leases/" + LEASE), calls.toString());
    assertFalse(calls.stream().anyMatch(call -> call.startsWith("claim")), calls.toString());
    assertFalse(calls.stream().anyMatch(call -> call.startsWith("DELETE")), calls.toString());
    assertFalse(executed.isDone());
    assertTrue(leases.launchOf(RUN).isPresent());
    assertTrue(idp.deletedTokens.isEmpty());
    assertFalse(steps.endedLeases().containsKey(RUN));
  }

  // --- out-of-memory kills (qits-1818) -----------------------------------------------------------

  @Test
  void aRunningLeasesOutOfMemoryKillsAreRecordedOnItsRun() throws Exception {
    leases.requestNow(RUN);

    leases.observe(running(2L, "NODE"));

    assertEquals(2L, row.oomKills);
    assertEquals("NODE", row.oomScope);
    assertEquals(RUN, executed.get(10, TimeUnit.SECONDS), "and the run is claimed as ever");
  }

  @Test
  void aLowerLaterReadingDoesNotLowerTheRunsCount() {
    leases.requestNow(RUN);
    leases.observe(running(2L, "NODE"));

    leases.observe(running(1L, "LEASE"));
    leases.observe(
        new SystemLeases.LeaseView(
            LEASE, RUN, "EXITED", TARGET, 137, null, null, 2L, "lease"));

    assertEquals(2L, row.oomKills);
    assertEquals("NODE", row.oomScope, "the scope follows the reading that raised the count");
    assertEquals(List.of(2L), oomRises, "only the first reading raised anything");
  }

  @Test
  void aLeaseReportingNoKillsChangesNothing() {
    leases.requestNow(RUN);

    leases.observe(running(null, null));
    leases.observe(running(0L, null));
    leases.observe(new SystemLeases.LeaseView(LEASE, RUN, "RUNNING", TARGET, null, null, null));

    assertNull(row.oomKills);
    assertNull(row.oomScope);
    assertTrue(oomRises.isEmpty());
    assertFalse(calls.stream().anyMatch(call -> call.startsWith("oom")), calls.toString());
  }

  @Test
  void aRiseWhileAStepRunsIsNotedOnThatStep() throws Exception {
    leases.requestNow(RUN);
    leases.observe(running(null, null));
    CiLeases.Launch launch = leases.launchOf(RUN).orElseThrow();
    try (ScriptedLeaseDaemon daemon =
        ScriptedLeaseDaemon.dial(registry, launch.daemonId(), launch.tokenSubject())) {
      daemon.holdSteps();
      CompletableFuture<CiStepRunner.StepResult> result =
          CompletableFuture.supplyAsync(() -> steps.run(step(0), new Quiet()));
      awaitLive("step 0 ran");

      leases.observe(running(2L, "NODE"));
      String note = "[the out-of-memory killer killed 2 processes in this run's lease (scope NODE)]";
      assertTrue(liveOutput().contains(note), "a live read shows it at once: " + liveOutput());

      daemon.releaseSteps();
      CiStepRunner.StepResult finished = result.get(10, TimeUnit.SECONDS);
      assertEquals("step 0 ran\n" + note + "\n", finished.output(), "and the step's row keeps it");

      // Between steps nothing is in flight: a later rise is the run's alone.
      leases.observe(running(3L, "LEASE"));
      assertEquals(3L, row.oomKills);
      assertEquals("LEASE", row.oomScope);
      assertFalse(steps.noteInFlight(RUN, "[anything]"));
    }
  }

  @Test
  void aLeaseWithoutTheFieldsReadsThemNull() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    SystemLeases.LeaseView older =
        mapper.readValue(
            "{\"id\":\"" + LEASE + "\",\"ownerRef\":\"" + RUN + "\",\"state\":\"RUNNING\"}",
            SystemLeases.LeaseView.class);
    assertNull(older.oomKills());
    assertNull(older.oomScope());
    SystemLeases.LeaseView newer =
        mapper.readValue(
            "{\"id\":\"" + LEASE + "\",\"state\":\"RUNNING\",\"oomKills\":2,\"oomScope\":\"NODE\","
                + "\"memoryPeakBytes\":1024,\"oomKilled\":false}",
            SystemLeases.LeaseView.class);
    assertEquals(2L, newer.oomKills());
    assertEquals("NODE", newer.oomScope());
  }

  @Test
  void theLeaseDescribesItsOutOfMemoryKills() {
    assertEquals(
        "lease " + LEASE + " EXITED (exit 137) on target " + TARGET
            + ", the out-of-memory killer killed 2 processes (scope NODE): the daemon exited",
        CiLeases.describe(
            new SystemLeases.LeaseView(
                LEASE, RUN, "EXITED", TARGET, 137, "the daemon exited", null, 2L, "NODE")));
    assertEquals(
        "lease " + LEASE + " FAILED, the out-of-memory killer killed 1 process",
        CiLeases.describe(
            new SystemLeases.LeaseView(LEASE, RUN, "FAILED", null, null, null, null, 1L, null)));
    assertEquals(
        "[the out-of-memory killer killed 1 process in this run's lease (scope LEASE)]",
        CiLeases.oomNote(1, "LEASE"));
  }

  private static SystemLeases.LeaseView running(Long oomKills, String oomScope) {
    return new SystemLeases.LeaseView(
        LEASE, RUN, "RUNNING", TARGET, null, null, null, oomKills, oomScope);
  }

  private static CiStepRunner.StepSpec step(int index) {
    return new CiStepRunner.StepSpec(
        RUN,
        index,
        CiRepoRef.of("5ca0e7e9-repo", "qits", "qits-ci-service"),
        "main",
        "d".repeat(40),
        SlotFixtures.PINNED,
        "./verify.sh",
        "/artifacts/daemons/qits-ci-daemon/2026.1",
        60,
        false,
        false,
        "",
        java.util.Map.of());
  }

  private String liveOutput() {
    return steps.relay.snapshot(RUN).map(CiStepRelay.Snapshot::output).orElse("");
  }

  private void awaitLive(String text) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!liveOutput().contains(text)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("the step never printed " + text + ": " + liveOutput());
      }
      Thread.sleep(20);
    }
  }

  /** A step listener that records nothing. */
  private static final class Quiet implements CiStepRunner.StepListener {
    @Override
    public void onStarted() {}

    @Override
    public void onChunk(String text) {}

    @Override
    public void onFinished() {}

    @Override
    public void onLaunched(String daemonId, String tokenSubject) {}
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

    /** What a read of one lease answers: empty is a 404. */
    volatile Optional<LeaseView> read = Optional.empty();

    /** A read of one lease that does not answer at all. */
    volatile boolean readDown;

    @Override
    public Optional<LeaseView> get(UUID leaseId) {
      calls.add("GET /system/api/leases/" + leaseId);
      if (readDown) {
        throw new SystemUnavailable("GET /system/api/leases/" + leaseId + " did not answer", 0);
      }
      return read;
    }

    /** The listing: never this run's lease, as a listing taken before it was asked would be. */
    @Override
    public List<LeaseView> list() {
      return List.of();
    }

    @Override
    public Optional<LeaseView> end(UUID leaseId) {
      calls.add("DELETE /system/api/leases/" + leaseId);
      return Optional.empty();
    }

    @Override
    public Optional<LeaseView> end(UUID leaseId, String reason) {
      calls.add("DELETE /system/api/leases/" + leaseId + "?reason=" + reason);
      return Optional.empty();
    }
  }

  /** The run table, answering for one queued run. */
  private final class StandInRuns extends CiRunService {
    volatile boolean refuseRecord;

    /**
     * Set only by a test that drives more lease observations at a run while its driver is meant to
     * still be registered (qits-1829): {@link #executeReserved} blocks on it rather than returning
     * at once, which is what a real run's driver does for the whole of its execution.
     */
    volatile CountDownLatch holdDriver;

    @Override
    public Optional<Reservation> leaseCandidate(String runId) {
      return Optional.of(new Reservation(row, request()));
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
      // The real claim writes RUNNING and the target in the one conditional UPDATE it is.
      row.status = CiRunStatus.RUNNING;
      row.targetId = targetId;
      return Optional.of(new Reservation(row, request()));
    }

    /**
     * {@link CiRunService#regrantTarget}'s contract on the one row (qits-1829): a conditional write,
     * only while the row is {@code RUNNING} on the lease it names, and only when the target really
     * moved.
     */
    @Override
    public boolean regrantTarget(String runId, UUID leaseId, UUID targetId) {
      calls.add("regrant " + runId + " to " + targetId);
      if (!RUN.equals(runId)
          || row.status != CiRunStatus.RUNNING
          || !leaseId.equals(row.leaseId)
          || targetId == null
          || targetId.equals(row.targetId)) {
        return false;
      }
      row.targetId = targetId;
      return true;
    }

    @Override
    public void executeReserved(Reservation reservation) {
      executed.complete(reservation.run().id);
      CountDownLatch hold = holdDriver;
      if (hold != null) {
        try {
          hold.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public CiRun runRow(String runId) {
      return row;
    }

    /** The one row, once it holds a lease — what the reconcile walks. */
    @Override
    public List<CiRun> leasedRuns() {
      return row.leaseId == null ? List.of() : List.of(row);
    }

    @Override
    public List<String> queuedWithoutLease() {
      return List.of();
    }

    /** {@link CiRunService#recordOomKills}'s contract on the one row: raised only, this lease only. */
    @Override
    public synchronized long recordOomKills(String runId, UUID leaseId, long kills, String scope) {
      calls.add("oom " + kills + " " + scope);
      if (!RUN.equals(runId) || !leaseId.equals(row.leaseId)) {
        return 0;
      }
      long seen = row.oomKills == null ? 0 : row.oomKills;
      if (kills <= seen) {
        return 0;
      }
      row.oomKills = kills;
      row.oomScope = scope;
      oomRises.add(kills - seen);
      return kills - seen;
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
