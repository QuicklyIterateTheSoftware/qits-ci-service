package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner's connection ended, and {@code reason} says how.
 *
 * <p><b>{@code reason} is one of five words</b>, a plain {@code String} on the wire (the constants
 * below are this vocabulary's spelling of them, not an enum a subscriber must share):
 *
 * <ul>
 *   <li>{@link #RETIRED} — qits-ci sent this connection {@code Retire}, because a connection of the
 *       pinned version of the same runner said hello beside it. The ordinary end of a self-update,
 *       after {@link RunnerUpdated}.
 *   <li>{@link #REPLACED} — a newer connection of the same runner said hello <em>in the same
 *       version</em>, and this one was closed {@code ALREADY_CONNECTED}: the runner came back
 *       before this host noticed its old socket was dead.
 *   <li>{@link #LOST} — the socket closed and qits-ci did not close it: the runner went away, its
 *       host went down, or the network did.
 *   <li>{@link #REFUSED} — qits-ci closed it at its {@code Hello} (1008): the pinned binary
 *       speaking a capability version this host does not, or a runner whose row was deleted while
 *       it dialled. The one reason that follows no {@link RunnerConnected}.
 *   <li>{@link #SHUTDOWN} — this qits-ci process is stopping. Announced for every greeted
 *       connection when the stop begins, so it does not depend on the server closing sockets
 *       politely; the runners reconnect to its successor on their own.
 * </ul>
 *
 * <p><b>{@code heldRuns} is how many runs this connection still held</b>, and it is the field an
 * operator reads first: above 0 means those runs are about to be recorded {@code CONNECTION_LOST}
 * — and that holds for every reason, {@link #RETIRED} included: a retired runner closes and exits
 * when it reads the frame, not when its runs are done. {@code runnerVersion} is what the
 * connection's {@code Hello} said.
 *
 * <p><b>Only a connection that said {@code Hello} announces its end.</b> A dial that closes before
 * its {@code Hello} was never announced, so its end is not either, and a subscriber pairing {@link
 * RunnerConnected} with this event never sees an unmatched close. {@code occurredAt} is the moment
 * qits-ci dropped the connection from its registry. Everything else is {@link RunnerCreated}'s.
 */
public record RunnerDisconnected(
    UUID eventId,
    String runnerId,
    String runnerName,
    String runnerVersion,
    String reason,
    int heldRuns,
    Instant occurredAt)
    implements QitsEvent {

  /** Sent {@code Retire}: superseded by a connection of the pinned version. */
  public static final String RETIRED = "RETIRED";

  /** Closed {@code ALREADY_CONNECTED} by a newer connection of the same version. */
  public static final String REPLACED = "REPLACED";

  /** Closed without qits-ci closing it. */
  public static final String LOST = "LOST";

  /** Closed by qits-ci at its {@code Hello}. */
  public static final String REFUSED = "REFUSED";

  /** This qits-ci is stopping. */
  public static final String SHUTDOWN = "SHUTDOWN";

  public RunnerDisconnected {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerDisconnected(
      String runnerId,
      String runnerName,
      String runnerVersion,
      String reason,
      int heldRuns,
      Instant occurredAt) {
    this(null, runnerId, runnerName, runnerVersion, reason, heldRuns, occurredAt);
  }
}
