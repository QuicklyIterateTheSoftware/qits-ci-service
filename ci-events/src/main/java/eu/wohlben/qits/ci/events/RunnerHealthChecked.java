package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner's health check settled: {@code runId} is the pseudo-build, and {@code result} is {@link
 * #PASSED} or {@link #FAILED}.
 *
 * <p><b>This is the ONLY event a health check makes about itself.</b> Its run is a pseudo-build whose
 * verdict is about the runner rather than any commit, so it publishes none of {@link
 * BuildSuccessful}, {@link BuildFailed} or {@link BuildStatusChanged} — a subscriber holding one
 * verdict per commit, or mirroring the active listing, never sees it. What a runner's standing does
 * because of it is its own event: {@link RunnerReinstated} when a pass lifts a quarantine, {@link
 * RunnerQuarantined} when a failure begins one. Published for every settled check, pass or fail,
 * quarantined runner or not; a check that was cancelled settled nothing and publishes nothing.
 *
 * <p>{@code detail} is what the row keeps as {@code last_healthcheck_detail}: the step's outcome and
 * the head of its output, or why the check never ran ({@code runner not connected}). Absent on a pass
 * that had nothing to add. {@code occurredAt} is when it settled. Everything else is {@link
 * RunnerCreated}'s.
 */
public record RunnerHealthChecked(
    UUID eventId,
    String runnerId,
    String runnerName,
    String runId,
    String result,
    String detail,
    Instant occurredAt)
    implements QitsEvent {

  /** The pseudo-build ran green. */
  public static final String PASSED = "PASSED";

  /** It did not — red, timed out, never started, or never taken at all. */
  public static final String FAILED = "FAILED";

  public RunnerHealthChecked {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerHealthChecked(
      String runnerId,
      String runnerName,
      String runId,
      String result,
      String detail,
      Instant occurredAt) {
    this(null, runnerId, runnerName, runId, result, detail, occurredAt);
  }
}
