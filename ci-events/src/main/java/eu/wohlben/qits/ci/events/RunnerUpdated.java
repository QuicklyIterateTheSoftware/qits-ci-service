package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner's rollover completed: a connection of the pinned version ({@code toVersion}) said hello
 * while a connection of another version ({@code fromVersion}) of the same runner was still open, so
 * the new one took the row's slots and the old one is being sent {@code Retire}.
 *
 * <p><b>The moment is the {@code Retire}, not the old connection's close.</b> From here the runner
 * is served by {@code toVersion}; the old binary closing its socket once it has read the frame is its
 * own {@link RunnerDisconnected} with reason {@code RETIRED}, which follows this event. A subscriber
 * therefore reads a self-update as {@link RunnerUpdateStarted}, then this, then that close.
 *
 * <p>{@code fromVersion} is the version of the connection being retired. Should a runner ever hold
 * more than one of another version at once — a second failed attempt still open beside the first —
 * all of them are retired and {@code fromVersion} names the newest of them: one rollover is one
 * event, however many connections it ends. {@code occurredAt} is the moment the retirement was
 * decided. Everything else is {@link RunnerCreated}'s.
 */
public record RunnerUpdated(
    UUID eventId,
    String runnerId,
    String runnerName,
    String fromVersion,
    String toVersion,
    Instant occurredAt)
    implements QitsEvent {

  public RunnerUpdated {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerUpdated(
      String runnerId, String runnerName, String fromVersion, String toVersion, Instant occurredAt) {
    this(null, runnerId, runnerName, fromVersion, toVersion, occurredAt);
  }
}
