package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner was taken out of service: from now on it takes no work but its own health check, and its
 * row's {@code slots} — its operator's number — is kept for when it is put back.
 *
 * <p>{@code reason} is the sentence the row keeps and a person reads, never a code: {@code awaiting
 * its first health check} for a runner that has just registered, {@code 3 consecutive runner failures
 * (NEVER_STARTED on run …)} for one whose steps kept failing before its build script ran, {@code
 * health check failed: <outcome>} for one whose health check went red. Published once, when the
 * quarantine begins — a runner already quarantined whose next health check fails again stays so and
 * says it with {@link RunnerHealthChecked} alone. {@code occurredAt} is the row's {@code
 * quarantined_at}. Everything else is {@link RunnerCreated}'s.
 */
public record RunnerQuarantined(
    UUID eventId, String runnerId, String runnerName, String reason, Instant occurredAt)
    implements QitsEvent {

  public RunnerQuarantined {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerQuarantined(String runnerId, String runnerName, String reason, Instant occurredAt) {
    this(null, runnerId, runnerName, reason, occurredAt);
  }
}
