package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner is being updated: its connection said hello as {@code fromVersion}, the pin is {@code
 * toVersion}, qits-ci sent it {@code Upgrade}, and that connection is draining — it holds no slot,
 * reserves nothing, and only finishes what it already holds.
 *
 * <p>Published right after the {@link RunnerConnected} of the same connection (whose {@code
 * upgradeRequired} is true), and ended by {@link RunnerUpdated} when the successor says hello. A
 * {@code RunnerUpdateStarted} with no {@link RunnerUpdated} after it is a runner that never completes
 * its update, which is exactly what an operator wants to be able to find. "Older" and "newer" are
 * not implied: the pin is the authority, so {@code toVersion} may be below {@code fromVersion} after
 * a rollback.
 *
 * <p>{@code heldRuns} is how many runs the draining connection holds — the work that has to finish
 * over the old binary. It is 0 whenever the {@code Upgrade} answers a fresh connection's {@code
 * Hello}, which is today's only path (a run is held by the connection that took it, and a fresh one
 * has taken nothing), and it is carried for the day the pin can move under a live connection.
 * {@code occurredAt} is the moment the {@code Upgrade} was decided. Everything else is {@link
 * RunnerCreated}'s.
 */
public record RunnerUpdateStarted(
    UUID eventId,
    String runnerId,
    String runnerName,
    String fromVersion,
    String toVersion,
    int heldRuns,
    Instant occurredAt)
    implements QitsEvent {

  public RunnerUpdateStarted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerUpdateStarted(
      String runnerId,
      String runnerName,
      String fromVersion,
      String toVersion,
      int heldRuns,
      Instant occurredAt) {
    this(null, runnerId, runnerName, fromVersion, toVersion, heldRuns, occurredAt);
  }
}
