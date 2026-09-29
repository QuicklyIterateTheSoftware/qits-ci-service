package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An operator decommissioned a runner: its row is gone, and with it every door and the socket it
 * could have opened here.
 *
 * <p>Published once the delete committed, and never for a refused one — a runner holding a {@code
 * RUNNING} run is a 409 and stays. What happens to its credentials at qits-idp afterwards is not part
 * of this fact: they are given back after the row goes, and a give-back that cannot reach qits-idp is
 * reaped later, so the runner is decommissioned here whichever way that goes. {@code runnerName}
 * rides so the event still says <em>what</em> went, since the id names a row nobody can read any
 * more. {@code occurredAt} is when the delete was written. A runner still connected is then told so
 * and its connection closed, which ends in {@link RunnerDisconnected} with reason {@link
 * RunnerDisconnected#DELETED}. Everything else is {@link
 * RunnerCreated}'s.
 */
public record RunnerDeleted(UUID eventId, String runnerId, String runnerName, Instant occurredAt)
    implements QitsEvent {

  public RunnerDeleted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerDeleted(String runnerId, String runnerName, Instant occurredAt) {
    this(null, runnerId, runnerName, occurredAt);
  }
}
