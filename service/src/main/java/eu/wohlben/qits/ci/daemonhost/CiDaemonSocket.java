package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
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
 * Authorization: Bearer $QITS_TOKEN}; the edge introspects it and forwards a JWT whose {@code sub}
 * is the token's subject and whose role is {@code qits:ci-run}. That opens the upgrade too, and the
 * identity is then held to the launch: its subject must be the one recorded for the launch's run
 * ({@code RunCommissions} holds it, {@code CiDaemonRegistry.registerLaunch} records it beside the
 * secret), or the dial is closed 1008 {@code WRONG_RUN} before its {@code Hello}. The per-container
 * pair is checked first either way. A daemon that speaks the bearer form ships in a qits-ci-daemon
 * release after 2026-09-28; until {@code qits.ci-daemon-protocol.version} names it, an EDGE step's
 * daemon cannot pass the edge.
 *
 * <p><b>The address is a cross-repo contract.</b> {@code CiDaemonLauncher} injects {@code
 * qits.ci.container-daemon-url} (default {@code ws://qits-ci:8080/ci/daemon}) as {@code
 * $QITS_CI_DAEMON_URL} into every step container, and qits-ci-daemon dials exactly that string
 * verbatim. Move this path and that default moves with it. It is dialled directly on {@code
 * qits.ci.network} at this service's own port: a daemon is never a gateway route — one process per
 * container with a lifetime of one step has no stable address to configure.
 *
 * <p><b>Nothing is trusted before the headers are.</b> {@code @OnOpen} validates {@code
 * X-Qits-Ci-Daemon-Id} and {@code X-Qits-Ci-Daemon-Secret} out of the handshake against the launch
 * table and closes 1008 on an unknown id, a wrong secret, or a re-dial for a launch already
 * connected — before a single frame is processed. Identity is not in the path, deliberately: the
 * workspace control socket takes its caller's identity from a path parameter, which is its known
 * impersonation bug (migration-plan.md §9 item 22), and this socket accepts connections from
 * containers running repo-controlled code by design.
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
   * bound to its run by the token's subject ({@link CiDaemonRegistry#admit(String, String, String,
   * WebSocketConnection)}).
   */
  static final String RUN_ROLE = "qits:ci-run";

  private static final Logger LOG = Logger.getLogger(CiDaemonSocket.class);

  /**
   * The daemon id this connection was admitted under, stashed on the connection at open. The
   * alternative — scanning the launch table for a matching connection id on every frame — would make
   * a chatty step's cost depend on how many containers are in flight.
   */
  private static final UserData.TypedKey<String> DAEMON_ID = UserData.TypedKey.forString("daemonId");

  @Inject CiDaemonRegistry registry;

  @Inject CiDaemonMessageCodec codec;

  /** The identity the upgrade was admitted as — the forward-auth daemon's, or a ci-run token's. */
  @Inject SecurityIdentity identity;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    String daemonId = connection.handshakeRequest().header(CiDaemonRegistry.HEADER_ID);
    String secret = connection.handshakeRequest().header(CiDaemonRegistry.HEADER_SECRET);
    CiDaemonRegistry.Admission admission =
        registry.admit(daemonId, secret, runSubject(), connection);
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
      // Not admitted (the close from @OnOpen may still be in flight) — read nothing from it.
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
