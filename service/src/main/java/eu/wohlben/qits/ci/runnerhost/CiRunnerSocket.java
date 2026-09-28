package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.Heartbeat;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The endpoint a registered runner holds open for as long as its host is up. It owns only the
 * WebSocket lifecycle and the framing; {@link CiRunnerRegistry} owns the sessions and the correlated
 * traffic — {@code CiDaemonSocket}'s split, kept deliberately.
 *
 * <p><b>Identity is the bearer, and only the bearer.</b> {@code @RolesAllowed("qits:ci-runner")} is
 * enforced at the HTTP upgrade, so a dial without a runner's role is answered 401 and never reaches
 * {@link #onOpen}. There the token's {@code sub} — which qits-idp sets to the client id for a {@code
 * client_credentials} token (its {@code TokenService} mints {@code .subject(client.clientId())}) —
 * is looked up against {@code ci_runner.client_id}. No row is a 1008. Nothing on the wire names a
 * runner: a frame that claimed to be one would only be a claim to check against this.
 *
 * <p><b>The subject is read off the validated token and nowhere else</b>, which means the machine
 * gate ({@code qits.auth.machine.required}) must be on for a runner to connect at all. With it off
 * there is no token to read — the forward-auth headers are the identity, and those are exactly what
 * a step container asserts about itself on the daemon socket beside this one. Falling back to the
 * principal name would let anything on the network that can spell {@code X-Qits-User} claim to be
 * any runner, so the fallback is a 1008 instead. The register door makes the same choice for the
 * same reason.
 *
 * <p><b>The path literal carries {@code /ci} itself</b>, the daemon socket's rule: a {@code
 * @WebSocket} path does not follow {@code quarkus.rest.path}. It is {@link
 * RunnerAddresses#SOCKET_PATH}, the one spelling the register answer and the install script compose
 * a runner's {@code socketUrl} from, and {@code quarkus.quinoa.ignored-path-prefixes=/ci} already
 * keeps the SPA fallback off it.
 *
 * <p>Frames are handled on virtual threads, and an undecodable one is dropped and logged rather
 * than allowed to close the socket: a runner one capability ahead of this host sends frames this
 * host cannot decode, and a dropped frame is the right price for that where a dropped runner is not.
 */
@WebSocket(path = RunnerAddresses.SOCKET_PATH)
@RolesAllowed(CiRunnerSocket.RUNNER_ROLE)
public class CiRunnerSocket {

  private static final Logger LOG = Logger.getLogger(CiRunnerSocket.class);

  /** The role qits-idp grants a {@code ci-runner} client, and the only one this socket admits. */
  public static final String RUNNER_ROLE = "qits:ci-runner";

  /** Why a dial whose bearer names no registered runner was closed. */
  public static final String UNKNOWN_RUNNER = "UNKNOWN_RUNNER";

  /** Why a runner speaking another capability version was closed. */
  public static final String CAPABILITY_MISMATCH = "CAPABILITY_MISMATCH";

  /** The session this connection was admitted as, stashed at open like the daemon socket's id. */
  private static final UserData.TypedKey<CiRunnerRegistry.Session> SESSION =
      new UserData.TypedKey<>("runnerSession");

  @Inject CiRunnerRegistry registry;

  @Inject CiRunnerMessageCodec codec;

  @Inject CiRunners runners;

  @Inject RunnerReservations reservations;

  @Inject SecurityIdentity identity;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    Optional<String> subject = MachineIdentity.claim(identity, "sub");
    Optional<CiRunner> runner = subject.flatMap(runners::findByClientId);
    if (runner.isEmpty()) {
      // One code and one reason whether the token had no subject or named nobody: a caller that
      // guessed wrong learns that it was wrong, not which half.
      LOG.warnf(
          "Refused a runner dial from %s: subject %s is no registered runner",
          connection.handshakeRequest().remoteAddress(), subject.orElse("(none)"));
      refuse(connection, UNKNOWN_RUNNER);
      return;
    }
    connection.userData().put(SESSION, registry.admit(runner.orElseThrow(), connection));
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    CiRunnerRegistry.Session session = connection.userData().get(SESSION);
    if (session == null) {
      // Not admitted (the close from @OnOpen may still be in flight) — read nothing from it.
      return;
    }
    CiRunnerMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable frame from runner %s: %s", session.runnerName(), e.getMessage());
      return;
    }
    switch (decoded) {
      case Hello hello -> {
        switch (registry.onHello(session, hello)) {
          case GREETED -> {}
          case VERSION_MISMATCH -> refuse(connection, CAPABILITY_MISMATCH);
          case RUNNER_GONE -> refuse(connection, UNKNOWN_RUNNER);
        }
      }
      case Heartbeat ignored -> registry.onHeartbeat(session);
      case Reserve ignored -> onReserve(session);
      case Launched launched -> registry.onLaunched(session, launched);
      case LaunchFailed failed -> registry.onLaunchFailed(session, failed);
      case Reaped reaped -> registry.onReaped(session, reaped);
      default ->
          // Host → runner frames are never received here; ignored rather than trusted.
          LOG.debugf(
              "Runner %s sent a host frame %s — ignored",
              session.runnerName(), decoded.getClass().getSimpleName());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    CiRunnerRegistry.Session session = connection.userData().get(SESSION);
    if (session != null) {
      registry.onClose(session);
    }
  }

  /**
   * A runner has a free slot and asks for work: the claim, and the driver if it won one. Answered on
   * this frame's own virtual thread — the claim is one short transaction — while the run itself is
   * driven elsewhere, so the socket goes straight back to reading frames.
   */
  private void onReserve(CiRunnerRegistry.Session session) {
    reservations.onReserve(session);
  }

  private void refuse(WebSocketConnection connection, String reason) {
    CiRunnerRegistry.closeBounded(
        connection,
        new CloseReason(CiRunnerRegistry.CLOSE_POLICY, reason),
        "a refused runner dial");
  }
}
