package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An operator changed a runner: {@code changed} names which of its settings moved, and {@code
 * slots}, {@code plane} and {@code description} are what all three are <b>now</b>.
 *
 * <p><b>The whole state, plus the list of what moved.</b> A subscriber mirroring a runner needs the
 * new values whichever of them changed, and one reacting to a change — a runner drained to 0 slots,
 * moved between planes — needs to know which, without holding the previous event to diff against.
 * Carrying both answers both. {@code changed} holds the settings' own field names, {@code "slots"},
 * {@code "plane"} and {@code "description"}, in that order; it is never empty, because a {@code
 * PATCH} that leaves every value as it was — every field absent, or each equal to what the row held
 * — changed nothing and announces nothing.
 *
 * <p>{@code description} is nullable and absent from the payload when the runner has none, which is
 * also what clearing it looks like: {@code changed} then names it and the value is gone. {@code
 * plane} is {@code CiRunnerPlane}'s word as a plain {@code String}. {@code occurredAt} is when the
 * change was written — the row keeps no {@code updatedAt}, so it is the transaction's own clock.
 * Published once that transaction committed, and never for a refused {@code PATCH} (400, 404).
 * Everything else is {@link RunnerCreated}'s.
 */
public record RunnerChanged(
    UUID eventId,
    String runnerId,
    String runnerName,
    int slots,
    String plane,
    String description,
    List<String> changed,
    Instant occurredAt)
    implements QitsEvent {

  /** The {@code changed} word for the runner's slots. */
  public static final String SLOTS = "slots";

  /** The {@code changed} word for the runner's plane. */
  public static final String PLANE = "plane";

  /** The {@code changed} word for the runner's description. */
  public static final String DESCRIPTION = "description";

  public RunnerChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    changed = changed == null ? List.of() : List.copyOf(changed);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerChanged(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      List<String> changed,
      Instant occurredAt) {
    this(null, runnerId, runnerName, slots, plane, description, changed, occurredAt);
  }
}
