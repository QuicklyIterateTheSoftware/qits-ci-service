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
 * it — that guard is a call inside a resource method, not a filter over a path.
 *
 * <p><b>The {@code @RolesAllowed} above shuts first, and it names one role.</b> websockets-next
 * enforces it at the HTTP <em>upgrade</em>, so a dial with no identity is answered <b>401</b>, and
 * one with any role but {@code qits:ci-run} <b>403</b>, and neither ever reaches {@link #onOpen}. A
 * step's daemon dials through the platform edge presenting its run's {@code ci-run} token as {@code
 * Authorization: Bearer $QITS_TOKEN}; the edge introspects the token and forwards a JWT whose {@code
 * sub} is the token's subject and whose role is {@code qits:ci-run}. That is the one credential
 * that opens the upgrade. {@code qits:system} used to open it too, for the daemon on qits-net that
 * qits-515 deleted; it was narrowed away in qits-516, so a machine peer is refused at the handshake
 * rather than admitted to an upgrade that could only ever end in {@code WRONG_RUN}.
 *
 * <p><b>A connection is matched to its launch only by the launch id it names, bound to its run's
 * token.</b> When {@link #onOpen} returns the connection is bound to nothing. The daemon names its
 * launch in its first frame — {@code ci-daemon}'s {@link Hello#daemonId()}, which every capability
 * version carries — and {@link #onMessage} completes the admission there: the named launch must
 * exist (else {@code UNKNOWN_DAEMON}) and must be recorded against the subject this connection
 * arrived as (else 1008 {@code WRONG_RUN}). No secret is compared and no handshake header is read:
 * the {@code X-Qits-Ci-Daemon-Id}/{@code -Secret} pair a daemon on qits-net presented was deleted
 * with that plane (qits-515) — the edge strips every {@code X-Qits-*} header anyway. The token
 * proves the run, and what remains is only naming <em>which</em> launch of it this is, which is
 * not a secret.
 *
 * <p><b>The address is a cross-repo contract.</b> {@code StepAddressPlane} composes {@code
 * wss://ci.qits.<domain>/ci/daemon} as {@code $QITS_CI_DAEMON_URL} for every step container, and
 * qits-ci-daemon dials exactly that string verbatim. Move this path and that composition moves
 * with it.
 *
 * <p><b>Nothing is trusted before it is checked.</b> Identity is not in the path, deliberately:
 * the workspace control socket takes its caller's identity from a path parameter, which is its
 * known impersonation bug (migration-plan.md §9 item 22), and this socket accepts connections from
 * containers running repo-controlled code by design.
 *
 * <p>Frames are handled on virtual threads, so a step spraying output cannot occupy an event loop,
 * and an undecodable frame is caught and logged rather than allowed to kill the connection — the
 * shared codec throws on an unknown type and on an unknown {@code InitFailed} reason and NPEs on an
 * absent {@code stream}, strictness that is right for the contract and fatal if one malformed frame
 * from a container took the socket with it.
 */
@WebSocket(path = CiDaemonSocket.PATH)
@jakarta.annotation.security.RolesAllowed(CiDaemonSocket.RUN_ROLE)
public class CiDaemonSocket {

  /** The literal, {@code /ci} and all — see the class javadoc; {@code SocketBearerLifetime} reads it. */
  public static final String PATH = "/ci/daemon";

  /**
   * The role a {@code ci-run} token carries through the edge — what a step's daemon dials with, and
   * the only role this socket's upgrade admits — bound to its run by the token's subject ({@link
   * CiDaemonRegistry#admitByToken(String, String, WebSocketConnection)}).
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
   * The {@code sub} a dial arrived as, stashed at {@link #onOpen} for the one frame admission is
   * still pending on — see {@link #onMessage}.
   */
  private static final UserData.TypedKey<String> RUN_SUBJECT =
      UserData.TypedKey.forString("runSubject");

  @Inject CiDaemonRegistry registry;

  @Inject CiDaemonMessageCodec codec;

  /** The identity the upgrade was admitted as — a ci-run token's, as the edge forwarded it. */
  @Inject SecurityIdentity identity;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    // The token opened the upgrade, but it names a RUN, not a launch. The daemon names its launch
    // in its first frame (Hello.daemonId), so admission waits for it rather than happening here.
    connection.userData().put(RUN_SUBJECT, runSubject());
  }

  /**
   * The {@code sub} the caller arrived as — empty when it carries none, which matches no launch.
   * The subject is the validated token's claim when there is one, and the forward-auth user
   * otherwise: the edge names the token's subject either way.
   */
  private String runSubject() {
    if (identity == null || identity.isAnonymous()) {
      return "";
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
        // Nothing to admit it as — read nothing from it.
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
   * The admission: an unadmitted connection's first frame is its one chance to name the launch it
   * is. It must decode, and it must be a {@link Hello} — anything else names no launch, so it is
   * refused {@code UNKNOWN_DAEMON}. A decodable {@link Hello} names the launch, which {@link
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
          "Refused a ci-daemon dial from %s: its first frame did not decode"
              + " as Hello: %s",
          connection.handshakeRequest().remoteAddress(), e.getMessage());
      close(connection, CiDaemonRegistry.Admission.UNKNOWN_DAEMON.name());
      return;
    }
    if (!(decoded instanceof Hello hello)) {
      LOG.warnf(
          "Refused a ci-daemon dial from %s: its first frame was %s, not Hello",
          connection.handshakeRequest().remoteAddress(), decoded.getClass().getSimpleName());
      close(connection, CiDaemonRegistry.Admission.UNKNOWN_DAEMON.name());
      return;
    }
    CiDaemonRegistry.Admission admission =
        registry.admitByToken(hello.daemonId(), runSubject, connection);
    if (admission != CiDaemonRegistry.Admission.ADMITTED) {
      LOG.warnf(
          "Refused a ci-daemon dial from %s naming launch '%s': %s",
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
   * token that is not the launch's run's, or something claiming a launch that is already connected. A caller that could hang
   * the refusal could pin a virtual thread per dial simply by never completing the handshake.
   */
  private void close(WebSocketConnection connection, String reason) {
    CiDaemonRegistry.closeBounded(
        connection,
        new CloseReason(CiDaemonRegistry.CLOSE_UNAUTHORIZED, reason),
        "a refused ci-daemon dial");
  }
}
