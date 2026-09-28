package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A quarantined runner was put back into service: it takes work again, up to its row's {@code
 * slots}, and its streak of runner failures starts from nothing.
 *
 * <p>{@code by} says what lifted it, one of two plain words spelled once here: {@link #ADMIN} — a
 * person pressed greenlight — or {@link #HEALTHCHECK}, the runner's own health check passing.
 * Published once per lifting, and never for a greenlight of a runner that was not quarantined, which
 * lifts nothing. {@code occurredAt} is when the row was written. Everything else is {@link
 * RunnerCreated}'s.
 */
public record RunnerReinstated(
    UUID eventId, String runnerId, String runnerName, String by, Instant occurredAt)
    implements QitsEvent {

  /** A person lifted it, through {@code POST /ci/api/runners/{id}/greenlight}. */
  public static final String ADMIN = "admin";

  /** Its own health check passed. */
  public static final String HEALTHCHECK = "healthcheck";

  public RunnerReinstated {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerReinstated(String runnerId, String runnerName, String by, Instant occurredAt) {
    this(null, runnerId, runnerName, by, occurredAt);
  }
}
