package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.api.MachineGuardTest;
import eu.wohlben.qits.ci.control.CiRunnerPresence;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.control.RecordingRunnerAnnouncer;
import eu.wohlben.qits.ci.control.RecordingRunnerAnnouncer.Announced;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Retire;
import eu.wohlben.qits.cirunner.protocol.Upgrade;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runner socket's four connection events, at the port, driven by a real WebSocket from a
 * scripted {@link FakeCiRunner}: every {@code Hello} taken is one {@code RunnerConnected}, every
 * connection that said one ends in exactly one {@code RunnerDisconnected} with the reason the
 * registry decided, and a self-update reads {@code RunnerUpdateStarted}, then {@code RunnerUpdated},
 * then the old connection's {@code RETIRED} — in that order, because the order is what is said.
 *
 * <p>{@link MachineGuardTest.GateOn} for {@code CiRunnerSocketTest}'s reason and reused from it: the
 * socket's identity is a claim on a validated token, and one profile is one Quarkus start. The
 * connection's own behaviour — the frames, the slots, who is served — is that class's; what is
 * asserted here is only what the platform is told about it.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class CiRunnerSocketEventsTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  private static final String RUNNER_ROLE = CiRunnerSocket.RUNNER_ROLE;

  private static final String CLIENT = "ci-runner-client-events";

  private static final String AUDIENCE = "qits-platform";

  private static final String OLD = "0.0.1-old";

  @TestHTTPResource(RunnerAddresses.SOCKET_PATH)
  URI endpoint;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunnerRegistry registry;

  @Inject CiRunnerPresence presence;

  @Inject CiRunnerPins pins;

  @Inject RecordingRunnerAnnouncer announcer;

  @Inject CiRunners runners;

  private UUID runnerId;

  @BeforeEach
  void declareARegisteredRunner() {
    runnerId = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runnerRows.deleteAll();
              CiRunner runner = new CiRunner();
              runner.id = runnerId;
              runner.name = "events-runner";
              runner.slots = 2;
              runner.plane = CiRunnerPlane.EDGE;
              runner.clientId = CLIENT;
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
            });
    announcer.reset();
  }

  @AfterEach
  void forgetTheRunner() {
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  private Hello hello(String runnerVersion, int capabilityVersion) {
    return new Hello(
        runnerVersion,
        capabilityVersion,
        1,
        new Capabilities(true, "amd64", "linux", Map.of("site", "home")));
  }

  private Hello current() {
    return hello(pins.version(), CiRunnerProtocol.CAPABILITY_VERSION);
  }

  /**
   * Waits for {@code count} announcements. A case reads its announcements through this rather than
   * straight after {@link #awaitDisconnected}: the registry drops a session before it announces the
   * end, so "no longer connected" can be observed a moment before the {@code Disconnected} is.
   */
  private List<Announced> await(int count) throws InterruptedException {
    return announcer.await(runnerId.toString(), count, SOON);
  }

  private List<String> events() {
    return announcer.eventsOf(runnerId.toString());
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aCurrentHelloIsConnectedOnceAndALostSocketHoldingRunsIsDisconnectedLost()
      throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(current());
      assertNotNull(runner.next(Ack.class, SOON));

      Announced connected = await(1).get(0);
      assertEquals("RunnerConnected", connected.event());
      assertEquals("events-runner", connected.fact("runnerName"));
      assertEquals(pins.version(), connected.fact("runnerVersion"));
      assertEquals(pins.version(), connected.fact("targetVersion"));
      assertEquals(false, connected.fact("upgradeRequired"));
      assertEquals(Boolean.TRUE, connected.fact("docker"));
      assertEquals("amd64", connected.fact("arch"));
      assertEquals("linux", connected.fact("os"));

      // Two runs bound to this connection, as a Take binds them — then the runner vanishes.
      CiRunnerRegistry.Session session = registry.current(runnerId);
      registry.hold(session, "events-run-a");
      registry.hold(session, "events-run-b");
    }
    List<Announced> announced;
    try {
      // Waited on before the runs are released: the count is taken as the session is dropped.
      announced = await(2);
      awaitDisconnected();
    } finally {
      registry.release("events-run-a");
      registry.release("events-run-b");
    }
    assertEquals(List.of("RunnerConnected", "RunnerDisconnected"), events());
    Announced lost = announced.get(1);
    assertEquals("LOST", lost.fact("reason"));
    assertEquals(2, lost.fact("heldRuns"), "the runs about to be recorded CONNECTION_LOST");
    assertEquals(pins.version(), lost.fact("runnerVersion"));
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aDeletedRunnersConnectionIsRetiredAndEndsAsDeletedAfterRunnerDeleted() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(current());
      assertNotNull(runner.next(Ack.class, SOON));
      await(1);

      runners.delete(runnerId);

      Retire retire = runner.next(Retire.class, SOON);
      assertNotNull(retire);
      assertEquals(Retire.Kind.DELETED, retire.kind());
      assertEquals((Short) (short) 1008, runner.awaitClose(SOON));
      assertEquals(CiRunnerSocket.RUNNER_DELETED, runner.closeReason());
    }
    List<Announced> announced = await(3);
    assertEquals(List.of("RunnerConnected", "RunnerDeleted", "RunnerDisconnected"), events());
    assertEquals("DELETED", announced.get(2).fact("reason"));
    assertFalse(presence.connected(runnerId));
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aHelloOfAnotherVersionIsConnectedAsNeedingAnUpgradeAndThenUpdateStarted()
      throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(OLD, CiRunnerProtocol.CAPABILITY_VERSION));
      assertNotNull(runner.next(Upgrade.class, SOON));

      List<Announced> announced = await(2);
      assertEquals(List.of("RunnerConnected", "RunnerUpdateStarted"), events());
      assertEquals(OLD, announced.get(0).fact("runnerVersion"));
      assertEquals(pins.version(), announced.get(0).fact("targetVersion"));
      assertEquals(true, announced.get(0).fact("upgradeRequired"));
      Announced started = announced.get(1);
      assertEquals(OLD, started.fact("fromVersion"));
      assertEquals(pins.version(), started.fact("toVersion"));
      assertEquals(0, started.fact("heldRuns"), "a fresh connection has taken nothing");
    }
    awaitDisconnected();
    await(3);
    assertEquals(List.of("RunnerConnected", "RunnerUpdateStarted", "RunnerDisconnected"), events());
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void anOlderProtocolsCapabilitiesAreNotReadSoNotAnnounced() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(OLD, CiRunnerProtocol.CAPABILITY_VERSION - 1));
      assertNotNull(runner.next(Upgrade.class, SOON));

      Announced connected = await(1).get(0);
      assertEquals("RunnerConnected", connected.event());
      assertEquals(null, connected.fact("docker"));
      assertEquals(null, connected.fact("arch"));
      assertEquals(null, connected.fact("os"));
    }
    awaitDisconnected();
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRolloverReadsUpdateStartedThenUpdatedThenTheOldConnectionRetired() throws Exception {
    try (FakeCiRunner old = FakeCiRunner.dial(endpoint)) {
      old.send(hello(OLD, CiRunnerProtocol.CAPABILITY_VERSION));
      assertNotNull(old.next(Upgrade.class, SOON));
      await(2);

      try (FakeCiRunner successor = FakeCiRunner.dial(endpoint)) {
        successor.send(current());
        assertNotNull(successor.next(Ack.class, SOON));
        assertNotNull(old.next(Retire.class, SOON));
        // What the runner does with a Retire: it closes its own socket and exits.
        old.close();

        List<Announced> announced = await(5);
        assertEquals(
            List.of(
                "RunnerConnected",
                "RunnerUpdateStarted",
                "RunnerConnected",
                "RunnerUpdated",
                "RunnerDisconnected"),
            events());
        Announced updated = announced.get(3);
        assertEquals(OLD, updated.fact("fromVersion"));
        assertEquals(pins.version(), updated.fact("toVersion"));
        Announced retired = announced.get(4);
        assertEquals("RETIRED", retired.fact("reason"));
        assertEquals(OLD, retired.fact("runnerVersion"));
        assertEquals(0, retired.fact("heldRuns"));
      }
    }
    awaitDisconnected();
    // The successor's own end is the sixth and last: one Disconnected per Connected.
    List<Announced> all = await(6);
    assertEquals(6, all.size());
    assertEquals("LOST", all.get(5).fact("reason"));
    assertEquals(pins.version(), all.get(5).fact("runnerVersion"));
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aSameVersionReplacementIsDisconnectedReplacedOnceEvenWhenItsCloseArrivesLater()
      throws Exception {
    try (FakeCiRunner first = FakeCiRunner.dial(endpoint)) {
      first.send(current());
      assertNotNull(first.next(Ack.class, SOON));
      try (FakeCiRunner second = FakeCiRunner.dial(endpoint)) {
        second.send(current());
        assertEquals((Short) (short) 1008, first.awaitClose(SOON));
        assertNotNull(second.next(Ack.class, SOON));

        List<Announced> announced = await(3);
        // The replaced connection's end is announced before its successor's Connected: the
        // registry drops it while it settles the newcomer's Hello.
        assertEquals(
            List.of("RunnerConnected", "RunnerDisconnected", "RunnerConnected"), events());
        assertEquals("REPLACED", announced.get(1).fact("reason"));
        // The replaced socket's own late close adds nothing.
        Thread.sleep(300);
        assertEquals(3, events().size());
      }
    }
    awaitDisconnected();
    await(4);
    assertEquals(
        List.of("RunnerConnected", "RunnerDisconnected", "RunnerConnected", "RunnerDisconnected"),
        events());
    assertEquals("LOST", announcer.of(runnerId.toString()).get(3).fact("reason"));
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aRefusedHelloIsDisconnectedRefusedAndNeverConnected() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(hello(pins.version(), CiRunnerProtocol.CAPABILITY_VERSION + 1));
      assertEquals((Short) (short) 1008, runner.awaitClose(SOON));
    }
    awaitDisconnected();

    List<Announced> announced = await(1);
    assertEquals(List.of("RunnerDisconnected"), events());
    assertEquals("REFUSED", announced.get(0).fact("reason"));
    assertEquals(pins.version(), announced.get(0).fact("runnerVersion"));
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aDialThatNeverSaysHelloAnnouncesNothing() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      Instant deadline = Instant.now().plus(SOON);
      while (!presence.connected(runnerId) && Instant.now().isBefore(deadline)) {
        Thread.sleep(20);
      }
    }
    awaitDisconnected();
    Thread.sleep(200);
    assertEquals(List.of(), events());
  }

  @Test
  @TestSecurity(user = "runner", roles = RUNNER_ROLE)
  @OidcSecurity(claims = {@Claim(key = "aud", value = AUDIENCE), @Claim(key = "sub", value = CLIENT)})
  void aStoppingProcessAnnouncesShutdownOnceAndTheLaterCloseNothing() throws Exception {
    try (FakeCiRunner runner = FakeCiRunner.dial(endpoint)) {
      runner.send(current());
      assertNotNull(runner.next(Ack.class, SOON));
      await(1);

      registry.announceShutdown();

      List<Announced> announced = await(2);
      assertEquals(List.of("RunnerConnected", "RunnerDisconnected"), events());
      assertEquals("SHUTDOWN", announced.get(1).fact("reason"));
    }
    awaitDisconnected();
    Thread.sleep(200);
    assertEquals(2, events().size(), "the close after the stop announced is not a second end");
  }

  /** The close is asynchronous on the server; the next case must not start with this runner on. */
  private void awaitDisconnected() throws InterruptedException {
    Instant deadline = Instant.now().plus(SOON);
    while (presence.connected(runnerId) && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    assertFalse(presence.connected(runnerId), "the runner's session ended with its socket");
  }
}
