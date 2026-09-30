package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Heartbeat;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The control socket and the registry behind it, driven by a real WebSocket from a scripted {@link
 * FakeCiDaemon}. No docker and no daemon binary: the host cannot tell this client from a container,
 * so everything about admission, framing, dispatch and the blocking bridge is provable here and only
 * the round trip through the real daemon binary is {@code CiDaemonPinIT}'s.
 *
 * <p>Addresses the socket through {@code @TestHTTPResource} rather than a hard-coded port, and
 * through its <b>absolute</b> path — {@code /ci/daemon} is a literal that does not follow {@code
 * quarkus.rest.path}, and it is the path {@code StepAddressPlane} composes into every container's daemon url, so
 * a test that addressed it relatively would not catch a segment regression.
 */
@QuarkusTest
public class CiDaemonSocketTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  @Inject CiDaemonRegistry registry;

  @TestHTTPResource("/ci/daemon")
  URI endpoint;

  /** The subject a run's {@code ci-run} token carries — what its daemon arrives as. */
  private static String subject(String run) {
    return "tok-ci-run-" + run + "-1";
  }

  /** A launch of {@code run}'s step, bound to that run's token subject. */
  private String launch(String run) {
    return registry.registerLaunch(run, 0, subject(run), null);
  }

  /** A daemon of {@code run} that named {@code daemonId} in its Hello and was answered an Ack. */
  private FakeCiDaemon admitted(String run, String daemonId) throws Exception {
    FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, subject(run));
    daemon.hello(daemonId);
    Ack ack = assertInstanceOf(Ack.class, daemon.next(SOON));
    // The version must travel back: a daemon reading no version reads 0, mismatches, and exits.
    assertEquals(CiDaemonProtocol.CAPABILITY_VERSION, ack.capabilityVersion());
    assertTrue(registry.awaitRegistered(daemonId, SOON));
    return daemon;
  }

  // --- admission: the launch a connection names, bound to its run's token (qits-477, qits-515) --

  @Test
  public void aDaemonOfTheLaunchsOwnRunIsAdmittedAndAckedWithTheHostsCapabilityVersion()
      throws Exception {
    String daemonId = launch("run-mine");
    try (FakeCiDaemon daemon = admitted("run-mine", daemonId)) {
      assertEquals(CiDaemonRegistry.Phase.CONNECTED, registry.phaseOf(daemonId));
      assertTrue(daemon.isOpen());
    } finally {
      registry.reap(daemonId);
    }
  }

  @Test
  public void aDaemonOfAnotherRunIsClosedWrongRunAtItsHello() throws Exception {
    String daemonId = launch("run-edge-mine");
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, subject("theirs"))) {
      // The token is what it cannot forge — the whole reason the launch is bound to it — and the
      // launch id it names is only a claim: it can name any launch, and naming this one is still
      // refused.
      daemon.hello(daemonId);
      assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
      assertEquals("WRONG_RUN", daemon.closeReason());
      assertFalse(registry.awaitRegistered(daemonId, Duration.ofMillis(200)));
    } finally {
      registry.reap(daemonId);
    }
  }

  @Test
  public void aDaemonNamingAnUnknownLaunchInItsHelloIsClosedUnknownDaemon() throws Exception {
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, subject("none"))) {
      daemon.hello("no-such-launch");
      assertEquals(
          (Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED,
          daemon.awaitClose(SOON),
          "a dial the registry has no launch record for must be refused");
      assertEquals("UNKNOWN_DAEMON", daemon.closeReason());
    }
  }

  @Test
  public void aFirstFrameThatIsNotAHelloIsClosedUnknownDaemon() throws Exception {
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, subject("notfirst"))) {
      // Anything else — even a well-formed frame — never names a launch.
      daemon.send(new Heartbeat());
      assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
      assertEquals("UNKNOWN_DAEMON", daemon.closeReason());
    }
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, subject("notfirst"))) {
      daemon.sendRaw("not json at all");
      assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
      assertEquals("UNKNOWN_DAEMON", daemon.closeReason());
    }
  }

  @Test
  public void aDialThatNeverNamesALaunchIsNeverRegistered() throws Exception {
    String daemonId = launch("run-silent-dial");
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, subject("run-silent-dial"))) {
      // The upgrade is open and bound to nothing: the host's register wait is what ends it.
      assertFalse(registry.awaitRegistered(daemonId, Duration.ofMillis(300)));
      assertEquals(CiDaemonRegistry.Phase.LAUNCHED, registry.phaseOf(daemonId));
    } finally {
      registry.reap(daemonId);
    }
  }

  /**
   * qits-515: the header path is gone. The pair a daemon on qits-net presented —
   * {@code X-Qits-Ci-Daemon-Id} and {@code -Secret} under a self-asserted {@code qits:system} — is
   * read by nothing: the upgrade still opens on that role (its removal from the socket is qits-516),
   * and the connection is admitted to no launch, by its headers or by a Hello.
   */
  @Test
  public void theDeletedHeaderHandshakeAdmitsNoLaunch() throws Exception {
    String daemonId = launch("run-headers");
    java.util.Map<String, String> onQitsNet =
        java.util.Map.of(
            FakeCiDaemon.USER_HEADER, "qits-ci-daemon",
            FakeCiDaemon.ROLES_HEADER, CiDaemonSocket.SYSTEM_ROLE,
            "X-Qits-Ci-Daemon-Id", daemonId,
            "X-Qits-Ci-Daemon-Secret", "anything-at-all");
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, onQitsNet)) {
      // The headers alone register nothing...
      assertFalse(registry.awaitRegistered(daemonId, Duration.ofMillis(300)));
      // ...and naming the launch as a caller that is not its run's token is WRONG_RUN.
      daemon.hello(daemonId);
      assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
      assertEquals("WRONG_RUN", daemon.closeReason());
      assertFalse(registry.awaitRegistered(daemonId, Duration.ofMillis(200)));
    } finally {
      registry.reap(daemonId);
    }
  }

  @Test
  public void aSecondDialForAnAlreadyConnectedDaemonIsClosed() throws Exception {
    String daemonId = launch("run-redial");
    try (FakeCiDaemon first = admitted("run-redial", daemonId);
        FakeCiDaemon second = FakeCiDaemon.dial(endpoint, subject("run-redial"))) {
      second.hello(daemonId);
      assertEquals(
          (Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED,
          second.awaitClose(SOON),
          "a re-dial for a connected launch is a claim on it, not a reconnect");
      assertEquals("ALREADY_CONNECTED", second.closeReason());
      assertTrue(first.isOpen(), "the connection that got there first keeps the launch");
    } finally {
      registry.reap(daemonId);
    }
  }

  @Test
  public void aLaterHelloClaimingAnotherDaemonIsClosedRatherThanBelieved() throws Exception {
    String daemonId = launch("run-claim");
    try (FakeCiDaemon daemon = admitted("run-claim", daemonId)) {
      daemon.send(new Hello("somebody-elses-daemon", CiDaemonProtocol.CAPABILITY_VERSION));
      assertEquals(
          (Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED,
          daemon.awaitClose(SOON),
          "the daemonId on the wire is a claim the host checks, not an identity it accepts");
    } finally {
      registry.reap(daemonId);
    }
  }

  @Test
  public void anUndecodableFrameCostsTheFrameAndNotTheConnection() throws Exception {
    String daemonId = launch("run-garbage");
    try (FakeCiDaemon daemon = admitted("run-garbage", daemonId)) {
      // Each of these throws inside the shared codec: an unknown type, a missing type, an unknown
      // InitFailed reason, and an absent stream on a chunk. The strictness is deliberate there and
      // must be caught here, or one malformed frame from a container takes the socket with it.
      daemon.sendRaw("{\"type\":\"somethingElse\"}");
      daemon.sendRaw("{\"nope\":1}");
      daemon.sendRaw("{\"type\":\"initFailed\",\"reason\":\"COSMIC_RAYS\"}");
      daemon.sendRaw("{\"type\":\"stepChunk\",\"correlationId\":\"c\",\"seq\":0,\"text\":\"x\"}");
      daemon.sendRaw("not json at all");

      // Still speaking: the connection survived all five.
      daemon.hello(daemonId);
      assertInstanceOf(Ack.class, daemon.next(SOON));
      assertTrue(daemon.isOpen());
    } finally {
      registry.reap(daemonId);
    }
  }

  @Test
  public void theWholeStepLifecycleRunsThroughTheBlockingBridge() throws Exception {
    List<String> chunks = Collections.synchronizedList(new ArrayList<>());
    String daemonId =
        registry.registerLaunch(
            "run-lifecycle",
            3,
            subject("run-lifecycle"),
            (stream, seq, text) -> chunks.add(stream + ":" + seq + ":" + text));
    try (FakeCiDaemon daemon = admitted("run-lifecycle", daemonId)) {
      daemon.send(new Heartbeat());
      daemon.send(new Initialized());

      CiDaemonRegistry.Initialization initialization = registry.awaitInitialized(daemonId, SOON);
      assertEquals(
          CiDaemonRegistry.Initialization.Status.INITIALIZED, initialization.status());
      assertEquals(CiDaemonRegistry.Phase.INITIALIZED, registry.phaseOf(daemonId));

      // The step arrives as the answer to Initialized — the host initiates nothing.
      String correlationId = registry.sendRunStep(daemonId, "echo hi", 60);
      RunStep runStep = assertInstanceOf(RunStep.class, daemon.next(SOON));
      assertEquals(correlationId, runStep.correlationId());
      assertEquals("echo hi", runStep.script());
      assertEquals(60, runStep.timeoutSeconds());

      daemon.send(new StepChunk(correlationId, 0, Stream.OUT, "hi\n"));
      daemon.send(new StepChunk(correlationId, 1, Stream.ERR, "warn\n"));
      daemon.send(new StepFinished(correlationId, 0, false));

      CiDaemonRegistry.Completion completion = registry.awaitFinished(daemonId, SOON);
      assertEquals(CiDaemonRegistry.Completion.Status.FINISHED, completion.status());
      assertEquals(0, completion.exitCode());
      assertFalse(completion.timedOut());
      assertEquals(List.of("OUT:0:hi\n", "ERR:1:warn\n"), chunks);
    } finally {
      registry.reap(daemonId);
    }
    assertNull(registry.phaseOf(daemonId), "a reaped launch leaves no record");
  }

  @Test
  public void aStructuredSetupFailureReachesTheAwaitWithItsReason() throws Exception {
    String daemonId = launch("run-shagone");
    try (FakeCiDaemon daemon = admitted("run-shagone", daemonId)) {
      daemon.send(new InitFailed(InitFailed.Reason.SHA_GONE, "fatal: reference is not a tree"));

      CiDaemonRegistry.Initialization initialization = registry.awaitInitialized(daemonId, SOON);
      assertEquals(CiDaemonRegistry.Initialization.Status.INIT_FAILED, initialization.status());
      // SHA_GONE is what carries the force-push semantic the orchestrator acts on; it must not
      // arrive as a generic failure with a suspicious exit code.
      assertEquals(InitFailed.Reason.SHA_GONE, initialization.reason());
      assertNotNull(initialization.detail());
    } finally {
      registry.reap(daemonId);
    }
  }

  @Test
  public void aSocketLostMidStepCompletesTheAwaitAsConnectionLostRatherThanTimingOut()
      throws Exception {
    String daemonId = launch("run-drop");
    try {
      FakeCiDaemon daemon = admitted("run-drop", daemonId);
      daemon.send(new Initialized());
      assertEquals(
          CiDaemonRegistry.Initialization.Status.INITIALIZED,
          registry.awaitInitialized(daemonId, SOON).status());
      registry.sendRunStep(daemonId, "sleep 600", 600);
      assertInstanceOf(RunStep.class, daemon.next(SOON));

      daemon.close();

      // A generous deadline that must NOT be spent: the close resolves the await immediately, which
      // is the difference between a distinguishable outcome and a run that looks merely slow.
      long start = System.nanoTime();
      CiDaemonRegistry.Completion completion =
          registry.awaitFinished(daemonId, Duration.ofSeconds(30));
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertEquals(CiDaemonRegistry.Completion.Status.CONNECTION_LOST, completion.status());
      assertTrue(elapsedMs < 10_000, "the lost socket must resolve the await, not expire it");
    } finally {
      registry.reap(daemonId);
    }
  }
}
