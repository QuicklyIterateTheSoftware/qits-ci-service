package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cidaemon.protocol.Ack;
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
 * qits-1728 reproduced: the daemon's leg of the edge's splice dies with no FIN or RST reaching the
 * edge, so the host's socket stays open and silent while the daemon sees its own close and re-dials.
 * {@link HalfOpenRelay} is that splice, between a real {@link FakeCiDaemon} and the real endpoint.
 *
 * <p>Before the fix the re-dial was refused 1008 {@code ALREADY_CONNECTED} for as long as the
 * half-open connection read open — the daemon's whole reconnect budget — and the run was lost. Now
 * the re-dial supersedes it and the step finishes with its own exit code.
 */
@QuarkusTest
public class CiDaemonHalfOpenTunnelTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  @Inject CiDaemonRegistry registry;

  @TestHTTPResource("/ci/daemon")
  URI endpoint;

  @Test
  public void aCutOnlyOnTheDaemonLegStillLetsTheStepFinish() throws Exception {
    String subject = "tok-ci-run-run-halfopen-1";
    List<String> chunks = Collections.synchronizedList(new ArrayList<>());
    String daemonId = registry.registerLaunch("run-halfopen", subject);
    try (HalfOpenRelay edge = new HalfOpenRelay(endpoint)) {
      URI throughTheEdge = edge.endpoint(endpoint);

      FakeCiDaemon first = FakeCiDaemon.dial(throughTheEdge, subject);
      first.hello(daemonId);
      assertInstanceOf(Ack.class, first.next(SOON));
      first.send(new Initialized());
      assertEquals(
          CiDaemonRegistry.Initialization.Status.INITIALIZED,
          registry.awaitInitialized(daemonId, SOON).status());
      String correlationId =
          registry.sendRunStep(
              daemonId,
              0,
              "make",
              600,
              java.util.Map.of(),
              (stream, seq, text) -> chunks.add(seq + ":" + text));
      assertInstanceOf(RunStep.class, first.next(SOON));
      first.send(new StepChunk(correlationId, 0, Stream.OUT, "a\n"));

      // The daemon's leg dies; the host's leg of the splice stays open and hears nothing.
      edge.splices().getFirst().cutDaemonLeg();
      // A TCP close with no close frame: no code to read, only the socket gone.
      first.awaitClose(SOON);
      assertFalse(first.isOpen(), "the daemon sees its own socket close");
      Thread.sleep(300);
      assertTrue(edge.splices().getFirst().hostLegOpen());
      assertTrue(registry.connected(daemonId), "the host never heard about it — the incident");

      // The daemon re-dials with its own token and names its launch, as the binary does.
      try (FakeCiDaemon second = FakeCiDaemon.dial(throughTheEdge, subject)) {
        second.hello(daemonId);
        assertInstanceOf(
            Ack.class, second.next(SOON), "re-dial refused: " + second.closeReason());
        RunStep again = assertInstanceOf(RunStep.class, second.next(SOON));
        assertEquals(correlationId, again.correlationId());

        second.send(new StepChunk(correlationId, 1, Stream.OUT, "b\n"));
        second.send(new StepFinished(correlationId, 0, 7, false));

        CiDaemonRegistry.Completion completion = registry.awaitFinished(daemonId, SOON);
        assertEquals(CiDaemonRegistry.Completion.Status.FINISHED, completion.status());
        assertEquals(7, completion.exitCode(), "the step's own exit, not a lost connection");
        assertFalse(completion.timedOut());
        assertEquals(List.of("0:a\n", "1:b\n"), chunks);
        assertTrue(registry.connected(daemonId));
      } finally {
        first.close();
      }
    } finally {
      registry.reap(daemonId);
    }
  }
}
