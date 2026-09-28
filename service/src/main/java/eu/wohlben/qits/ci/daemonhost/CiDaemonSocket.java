package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.auth.MachineIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The endpoint each step container's {@code qits-ci-daemon} dials on boot. It owns only the
 * WebSocket lifecycle and JSON framing — {@link CiDaemonRegistry} owns the launch table, the state
 * and the correlated traffic — which is the {@code DaemonControlSocket} split in qits-workspaces,
 * kept deliberately.
 *
 * <p><b>The path literal carries {@code /ci} itself.</b> A {@code @WebSocket} path registers
 * straight onto the router and does <em>not</em> follow {@code quarkus.rest.path}, so the segment
 * that every route of this service must serve has to be spelled here. {@code daemon} is a
 * second-level segment beside {@code api} because this is not a JSON API. No machine guard reaches
 * it — the intake's guard is a call inside a resource method, not a filter over a path — and that is
 * correct rather than an oversight: this socket's callers are step containers holding no idp client,
 * and its authentication is the per-container secret below.
 *
 * <p><b>The {@code @RolesAllowed} above is not that guard, and it shuts one door earlier than the
 * secret does.</b> websockets-next enforces it at the HTTP <em>upgrade</em>, so a dial with no
 * identity is answered <b>401 and never reaches {@link #onOpen}</b>. The step container's daemon
 * satisfies it by asserting the forward-auth pair itself — {@code X-Qits-User: qits-ci-daemon},
 * {@code X-Qits-Roles: qits:system}, see {@code qits-ci-daemon}'s {@code ControlSocket.connect} —
 * which it may because it dials this service directly on {@code qits.ci.network} and never crosses
 * the edge that strips the {@code X-Qits-*} namespace. So the two credentials do different jobs and
 * both are required: the role opens the route, and the secret says <em>which launch</em> this is.
 * That is also the one thing a caller of this endpoint must not forget — a client sending only the
 * two {@code X-Qits-Ci-Daemon-*} headers gets a 401 that looks nothing like the 1008 below.
 *
 * <p><b>A daemon on an EDGE runner holds the other role</b> (epic qits-441). It dials through the
 * edge, which strips {@code X-Qits-*}, so it presents its run's {@code ci-run} token as {@code
 * Authorization: Bearer $QITS_TOKEN} and sends <em>no</em> {@code X-Qits-Ci-Daemon-Id}/{@code
 * -Secret} headers at all — the edge would have stripped them, so a header a daemon on this plane
 * sent would never arrive, and the per-container secret buys nothing a token-bound connection does
 * not already have. The edge introspects the token and forwards a JWT whose {@code sub} is the
 * token's subject and whose role is {@code qits:ci-run}; that alone opens the upgrade, and the
 * <em>connection</em> is not yet bound to a launch when {@link #onOpen} returns. It names its
 * launch in its first frame instead — {@code ci-daemon}'s {@link Hello#daemonId()}, which every
 * capability version already carries — and {@link #onMessage} completes the admission there: the
 * named launch must exist (else {@code UNKNOWN_DAEMON}) and must be recorded against a run whose
 * {@code ci-run} token subject is this connection's (else 1008 {@code WRONG_RUN}). No secret is
 * compared on this path — the token already proves the run, and what remains is only naming
 * <em>which</em> launch of it this is, which is not a secret.
 *
 * <p><b>The address is a cross-repo contract.</b> {@code CiDaemonLauncher} injects {@code
 * qits.ci.container-daemon-url} (default {@code ws://qits-ci:8080/ci/daemon}) as {@code
 * $QITS_CI_DAEMON_URL} into every step container, and qits-ci-daemon dials exactly that string
 * verbatim. Move this path and that default moves with it. It is dialled directly on {@code
 * qits.ci.network} at this service's own port: a daemon is never a gateway route — one process per
 * container with a lifetime of one step has no stable address to configure.
 *
 * <p><b>Nothing is trusted before it is checked, and which check runs depends on the plane.</b> A
 * {@code qits:system} dial (INTERNAL, no token) is checked at {@link #onOpen}: {@code
 * X-Qits-Ci-Daemon-Id} and {@code -Secret} against the launch table, closing 1008 on an unknown id,
 * a wrong secret, or a re-dial for a launch already connected — before a single frame is processed.
 * A {@code qits:ci-run} dial (EDGE, a token) is checked at its first frame instead, for the reason
 * above. Identity is not in the path either way, deliberately: the workspace control socket takes
 * its caller's identity from a path parameter, which is its known impersonation bug
 * (migration-plan.md §9 item 22), and this socket accepts connections from containers running
 * repo-controlled code by design.
 *
 * <p>Frames are handled on virtual threads, so a step spraying output cannot occupy an event loop,
 * and an undecodable frame is caught and logged rather than allowed to kill the connection — the
 * shared codec throws on an unknown type and on an unknown {@code InitFailed} reason and NPEs on an
 * absent {@code stream}, strictness that is right for the contract and fatal if one malformed frame
 * from a container took the socket with it.
 */
@WebSocket(path = "/ci/daemon")
@jakarta.annotation.security.RolesAllowed({CiDaemonSocket.SYSTEM_ROLE, CiDaemonSocket.RUN_ROLE})
public class CiDaemonSocket {

  /** The forward-auth role a daemon on qits-net asserts for itself. */
  static final String SYSTEM_ROLE = "qits:system";

  /**
   * The role a {@code ci-run} token carries through the edge — what an EDGE step's daemon dials with,
   * bound to its run by the token's subject ({@link CiDaemonRegistry#admitByToken(String, String,
   * WebSocketConnection)}).
   */
  static final String RUN_ROLE = "qits:ci-run";

  private static final Logger LOG = Logger.getLogger(CiDaemonSocket.class);

  /**
   * The daemon id this connection was admitted under, stashed on the connection once admission
   * completes. The alternative — scanning the launch table for a matching connection id on every
   * frame — would make a chatty step's cost depend on how many containers are in flight.
   */
  private static final UserData.TypedKey<String> DAEMON_ID = UserData.TypedKey.forString("daemonId");

  /**
   * The {@code sub} an EDGE-plane dial's {@code ci-run} token arrived as, stashed at {@link
   * #onOpen} for the one frame admission is still pending on. Present exactly when {@link
   * #DAEMON_ID} is absent and the connection is a token dial rather than an unadmitted (and about
   * to be closed) header one — see {@link #onMessage}.
   */
  private static final UserData.TypedKey<String> RUN_SUBJECT =
      UserData.TypedKey.forString("runSubject");

  @Inject CiDaemonRegistry registry;

  @Inject CiDaemonMessageCodec codec;

  /** The identity the upgrade was admitted as — the forward-auth daemon's, or a ci-run token's. */
  @Inject SecurityIdentity identity;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    String runSubject = runSubject();
    if (runSubject != null) {
      // EDGE plane: the token opened the upgrade, but it names a RUN, not a launch, and the two
      // headers that would have named the launch never arrive here — qits-edge strips them. The
      // daemon names its launch in its first frame instead (Hello.daemonId), so admission waits for
      // it rather than happening here.
      connection.userData().put(RUN_SUBJECT, runSubject);
      return;
    }
    String daemonId = connection.handshakeRequest().header(CiDaemonRegistry.HEADER_ID);
    String secret = connection.handshakeRequest().header(CiDaemonRegistry.HEADER_SECRET);
    CiDaemonRegistry.Admission admission = registry.admit(daemonId, secret, connection);
    if (admission != CiDaemonRegistry.Admission.ADMITTED) {
      // Deliberately the same close code and no detail for all three: a caller that guessed wrong
      // learns that it was wrong, not which half of the credential it got right.
      LOG.warnf(
          "Refused a ci-daemon dial from %s as '%s': %s",
          connection.handshakeRequest().remoteAddress(), daemonId, admission);
      close(connection, admission.name());
      return;
    }
    connection.userData().put(DAEMON_ID, daemonId);
  }

  /**
   * The {@code sub} a {@code qits:ci-run} caller arrived as — empty when it carries none — or null
   * for a caller that holds {@code qits:system}, which is judged by the launch pair alone as it
   * always was. The subject is the validated token's claim when there is one, and the forward-auth
   * user otherwise: the edge names the token's subject either way.
   */
  private String runSubject() {
    if (identity == null
        || identity.isAnonymous()
        || identity.hasRole(SYSTEM_ROLE)
        || !identity.hasRole(RUN_ROLE)) {
      return null;
    }
    return MachineIdentity.claim(identity, "sub")
        .or(
            () ->
                java.util.Optional.ofNullable(
                    identity.getPrincipal() == null ? null : identity.getPrincipal().getName()))
        .orElse("");
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    String daemonId = connection.userData().get(DAEMON_ID);
    if (daemonId == null) {
      String runSubject = connection.userData().get(RUN_SUBJECT);
      if (runSubject == null) {
        // Not admitted (the close from @OnOpen may still be in flight) — read nothing from it.
        return;
      }
      admitByFirstFrame(message, runSubject, connection);
      return;
    }
    CiDaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf("Dropped an undecodable frame from ci-daemon %s: %s", daemonId, e.getMessage());
      return;
    }
    if (!registry.onMessage(daemonId, connection, decoded)) {
      close(connection, "IMPERSONATION");
    }
  }

  /**
   * The EDGE plane's admission: an unadmitted, token-bound connection's first frame is its one
   * chance to name the launch it is. It must decode, and it must be a {@link Hello} — anything else
   * is exactly as unidentifiable as a header dial with no id at all, so it is refused the same way,
   * {@code UNKNOWN_DAEMON}. A decodable {@link Hello} names the launch, which {@link
   * CiDaemonRegistry#admitByToken} then checks belongs to this connection's run.
   *
   * <p>Once admitted, this frame IS the daemon's {@code Hello} — it is handed to {@link
   * CiDaemonRegistry#onMessage} exactly as an already-admitted connection's would be, rather than
   * being consumed silently, so the {@code Ack} and the capability-version bookkeeping happen in the
   * one place they always have.
   */
  private void admitByFirstFrame(String message, String runSubject, WebSocketConnection connection) {
    CiDaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Refused a token-authenticated ci-daemon dial from %s: its first frame did not decode"
              + " as Hello: %s",
          connection.handshakeRequest().remoteAddress(), e.getMessage());
      close(connection, CiDaemonRegistry.Admission.UNKNOWN_DAEMON.name());
      return;
    }
    if (!(decoded instanceof Hello hello)) {
      LOG.warnf(
          "Refused a token-authenticated ci-daemon dial from %s: its first frame was %s, not Hello",
          connection.handshakeRequest().remoteAddress(), decoded.getClass().getSimpleName());
      close(connection, CiDaemonRegistry.Admission.UNKNOWN_DAEMON.name());
      return;
    }
    CiDaemonRegistry.Admission admission =
        registry.admitByToken(hello.daemonId(), runSubject, connection);
    if (admission != CiDaemonRegistry.Admission.ADMITTED) {
      LOG.warnf(
          "Refused a token-authenticated ci-daemon dial from %s naming launch '%s': %s",
          connection.handshakeRequest().remoteAddress(), hello.daemonId(), admission);
      close(connection, admission.name());
      return;
    }
    connection.userData().put(DAEMON_ID, hello.daemonId());
    if (!registry.onMessage(hello.daemonId(), connection, hello)) {
      close(connection, "IMPERSONATION");
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    String daemonId = connection.userData().get(DAEMON_ID);
    if (daemonId != null) {
      registry.onClose(daemonId, connection);
    }
  }

  /**
   * Refuse a dial, with a deadline on the refusal.
   *
   * <p>Bounded through {@link CiDaemonRegistry#closeBounded} rather than {@code closeAndAwait} for
   * the package's one rule: that convenience is {@code close().await().indefinitely()}, and the peer
   * being refused here is by definition one this host has no reason to trust — an unknown id, a
   * wrong secret, or something claiming a launch that is already connected. A caller that could hang
   * the refusal could pin a virtual thread per dial simply by never completing the handshake.
   */
  private void close(WebSocketConnection connection, String reason) {
    CiDaemonRegistry.closeBounded(
        connection,
        new CloseReason(CiDaemonRegistry.CLOSE_UNAUTHORIZED, reason),
        "a refused ci-daemon dial");
  }
}
