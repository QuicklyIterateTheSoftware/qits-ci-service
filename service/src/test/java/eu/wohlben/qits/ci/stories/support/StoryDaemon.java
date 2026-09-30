package eu.wohlben.qits.ci.stories.support;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.daemonhost.FakeCiDaemon;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Heartbeat;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * The step container's own {@code qits-ci-daemon}, as a story drives it — and the tap for the one
 * plane the framework ships no tap for.
 *
 * <h2>Why this is a real client and not a fixture</h2>
 *
 * <p>{@link FakeCiDaemon} is a real Vert.x WebSocket dialling the real endpoint and framing the
 * real protocol through the vendored {@code CiDaemonCodec} — the host cannot tell it from a
 * container. What this class adds is the <b>credential's provenance</b> and the <b>edges</b>. The
 * launch id and the run's token come out of the workload spec qits-ci sent a runner in a {@code
 * Launch} ({@link StoryRunner#awaitLaunch}), which is exactly where a container gets them and the
 * only place they exist: nothing in the story reads the host's launch table, so an admitted dial
 * here is evidence that the credential really travelled the way the service says it does.
 *
 * <h2>One credential, and the edge in between</h2>
 *
 * <p>A step's daemon presents its run's {@code ci-run} token — {@code $QITS_TOKEN} — to the platform
 * edge, which introspects it and forwards the caller to qits-ci as a short JWT: {@code sub} the
 * token's subject, role {@code qits:ci-run}. There is no edge in this suite, so this client arrives
 * as what the edge forwards: a JWT minted by the mock idp the launched process validates against,
 * carrying the subject the spec names as {@code $QITS_TOKEN_SUBJECT}. {@code CiDaemonSocket}'s
 * {@code @RolesAllowed} is enforced at the HTTP <b>upgrade</b>, so that JWT is what opens the
 * route; which launch this is, the daemon says in its first frame, and the host admits it only
 * when that launch was recorded against the JWT's subject. There is no per-container secret and no
 * launch header (qits-515).
 *
 * <h2>The tap, and why it is written here</h2>
 *
 * <p>The framework ships a RestAssured tap and nothing for a socket, so this plane is instrumented
 * with {@link NetworkCapture#observe} at the call sites — and every one of those calls is
 * synchronous on the <b>story thread</b>, which is the one place the framework's rule allows the
 * actor to be read. A handler on Vert.x' event loop would inherit whatever actor is current when
 * the frame lands, which is a different story's.
 *
 * <p>Two kinds, and the split is the vocabulary's own:
 *
 * <ul>
 *   <li><b>{@code socket}</b> — the dial. One edge, recorded once, for the connection the container
 *       holds open. Direction is who dialled, and the whole design of this plane is that the
 *       container dials <em>out</em>: qits-ci never dials in, which is why a step container needs
 *       no address and no inbound route.
 *   <li><b>{@code event}</b> — one per frame pushed over that connection, in whichever direction it
 *       was pushed. {@code hello}/{@code initialized}/{@code stepChunk}/{@code stepFinished} are
 *       the daemon's; {@code ack} and {@code runStep} are the host's, and {@code runStep} is the
 *       interesting one: <b>the step is the reply to the daemon's own {@code Initialized}</b>, so
 *       the arrow into the container exists only because the container asked for work.
 * </ul>
 *
 * <p>Labels are the protocol's own type names plus values that cannot vary between runs (a
 * capability version, a stream name, an exit code). A correlation id is deliberately not in any of
 * them: it is minted per step and would move the story's {@code networkHash} on every run.
 */
public final class StoryDaemon implements AutoCloseable {

  /** How the diagram names the initiator of everything on this plane. */
  public static final String ACTOR = "a build daemon";

  /** The environment variable the daemon reads its identity out of. */
  public static final String ID_VARIABLE = "QITS_CI_DAEMON_ID";

  /** …its run's {@code ci-run} token, the step's only credential. */
  public static final String TOKEN_VARIABLE = "QITS_TOKEN";

  /** …and the subject the edge forwards that token as. */
  public static final String TOKEN_SUBJECT_VARIABLE = "QITS_TOKEN_SUBJECT";

  /** The address a container dials, injected as {@code $QITS_CI_DAEMON_URL}. */
  public static final String URL_VARIABLE = "QITS_CI_DAEMON_URL";

  /** How long a frame the host owes may take to arrive. Generous: the host is a launched process. */
  private static final Duration SOON = Duration.ofSeconds(30);

  private final FakeCiDaemon socket;

  private StoryDaemon(FakeCiDaemon socket) {
    this.socket = socket;
  }

  /**
   * Dial the control socket as one step container's daemon arrives behind the edge, and record the
   * connection. {@code environment} is the workload spec's, as the runner was sent it.
   *
   * <p>The upgrade completing is not admission: the connection is bound to no launch until its
   * {@link #hello(String)} names one, and a refusal is a 1008 <b>close</b> after a successful
   * upgrade. So the edge is recorded here — the connection was made — and whether it was kept is
   * what the story's own assertions say.
   */
  public static StoryDaemon dial(URI endpoint, Map<String, String> environment) throws Exception {
    assertNotNull(environment.get(ID_VARIABLE), ID_VARIABLE + " was not in the workload spec");
    assertNotNull(environment.get(TOKEN_VARIABLE), TOKEN_VARIABLE + " was not in the workload spec");
    String subject = environment.get(TOKEN_SUBJECT_VARIABLE);
    assertNotNull(subject, TOKEN_SUBJECT_VARIABLE + " was not in the workload spec");
    FakeCiDaemon socket =
        FakeCiDaemon.dial(endpoint, Map.of("Authorization", "Bearer " + forwardedFor(subject)));
    pushed(ACTOR, StoryTarget.SERVICE, NetworkEdge.SOCKET, "CONNECT " + StoryTarget.DAEMON_PATH);
    return new StoryDaemon(socket);
  }

  /**
   * What the platform edge forwards for a run's {@code ci-run} token once it has introspected it: a
   * JWT addressed to the platform, whose {@code sub} is the token's subject and whose one role is
   * {@code qits:ci-run}.
   */
  public static String forwardedFor(String tokenSubject) {
    return MockIdp.attach()
        .token()
        .subject(tokenSubject)
        .audience(StoryIdentities.AUDIENCE)
        .groups(FakeCiDaemon.RUN_ROLE)
        .mint();
  }

  /**
   * {@code Hello} — the daemon naming the launch it is, which is what admits the connection: the
   * host checks that launch against the token subject the connection arrived as.
   */
  public void hello(String daemonId) throws Exception {
    socket.send(new Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
    fromDaemon("hello");
  }

  /** The host's answer, carrying the capability version a mismatched daemon exits on. */
  public Ack awaitAck() throws Exception {
    Ack ack = assertInstanceOf(Ack.class, next(), "the host must acknowledge a Hello");
    fromHost("ack capabilityVersion " + ack.capabilityVersion());
    return ack;
  }

  /** A liveness frame. The daemon sends them unprompted; the host owes nothing back. */
  public void heartbeat() throws Exception {
    socket.send(new Heartbeat());
    fromDaemon("heartbeat");
  }

  /** {@code Initialized} — the checkout is done and this container is ready for work. */
  public void initialized() throws Exception {
    socket.send(new Initialized());
    fromDaemon("initialized");
  }

  /** The step itself, which arrives as the reply to {@link #initialized()} and never before it. */
  public RunStep awaitRunStep() throws Exception {
    RunStep step =
        assertInstanceOf(RunStep.class, next(), "the step must arrive as the reply to Initialized");
    fromHost("runStep");
    return step;
  }

  /** One line of the step's output, on the stream it was written to. */
  public void chunk(String correlationId, long seq, Stream stream, String text) throws Exception {
    socket.send(new StepChunk(correlationId, seq, stream, text));
    fromDaemon("stepChunk " + stream.name());
  }

  /** The terminal frame: the step ended, with this exit code, and whether its own deadline fired. */
  public void finished(String correlationId, int exitCode, boolean timedOut) throws Exception {
    socket.send(new StepFinished(correlationId, exitCode, timedOut));
    fromDaemon("stepFinished exit " + exitCode);
  }

  /** The close code the host sent, or null if it did not close in time. */
  public Short awaitClose(Duration timeout) {
    return socket.awaitClose(timeout);
  }

  public boolean isOpen() {
    return socket.isOpen();
  }

  @Override
  public void close() {
    socket.close();
  }

  private CiDaemonMessage next() throws Exception {
    CiDaemonMessage message = socket.next(SOON);
    assertNotNull(message, "the host sent no frame within " + SOON);
    return message;
  }

  /** A frame this container pushed; the actor is read here, on the story thread. */
  private static void fromDaemon(String label) {
    pushed(ACTOR, StoryTarget.SERVICE, NetworkEdge.EVENT, label);
  }

  /** A frame the host pushed back down the connection the container opened. */
  private static void fromHost(String label) {
    pushed(StoryTarget.SERVICE, ACTOR, NetworkEdge.EVENT, label);
  }

  private static void pushed(String from, String to, String kind, String label) {
    NetworkCapture.observe(kind, from, to, label);
  }
}
