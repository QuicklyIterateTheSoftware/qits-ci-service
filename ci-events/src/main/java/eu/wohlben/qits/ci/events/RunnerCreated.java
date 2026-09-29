package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An operator declared a runner: this row exists now, with these slots, on this plane — and nothing
 * has registered as it yet.
 *
 * <p><b>The first of eleven runner lifecycle events, and the one that states the family's
 * conventions</b>, the way {@link BuildSuccessful} states the build events'. The eleven are {@code
 * RunnerCreated}, {@link RunnerRegistered}, {@link RunnerConnected}, {@link RunnerDisconnected},
 * {@link RunnerUpdateStarted}, {@link RunnerUpdated}, {@link RunnerChanged}, {@link RunnerDeleted},
 * and the quarantine's three — {@link RunnerQuarantined}, {@link RunnerReinstated} and {@link
 * RunnerHealthChecked}; each is one fact, and each of the others names this class for everything
 * below rather than restating it.
 *
 * <p><b>One event per lifecycle fact, deliberately not one {@code RunnerStatusChanged}</b> — and
 * that is the opposite of {@link BuildStatusChanged}'s choice, for a reason worth keeping straight.
 * A run is a row with one {@code status} column, so what moved is that column and one event says it.
 * A runner has no such column: that it exists, that it registered, that a connection is open, that
 * it is updating and what an operator set are five different facts with five different shapes, held
 * in two different places (the row, and the socket registry in memory). Folding them into one event
 * would make every field nullable and every subscriber decode a {@code kind} to learn which half of
 * the payload is real. A subscriber that wants one fact subscribes to one name.
 *
 * <p><b>{@code Runner…}, not {@code CiRunner…}.</b> A signature is the class's simple name and the
 * bus is shared by every service, so the name was checked against the whole estate's vocabulary:
 * nothing else on the platform publishes a {@code Runner*} event, and the platform's event names are
 * domain nouns with no service prefix ({@code BuildSuccessful}, {@code ProjectCreated}, {@code
 * DeploymentStarted}). The prefixed spelling would also have collided inside this repository: {@code
 * CiRunnerCreated} is already the create door's response schema, and so a name in {@code
 * docs/openapi.yml} and in the SPA's generated client.
 *
 * <p><b>The common fields, once.</b> {@code runnerId} is the row's uuid carried as a {@code String} —
 * a runner is an id across this boundary, never a reference into this service's tables — and {@code
 * runnerName} is the operator's name for it, carried on every event so a log reader needs no second
 * lookup (and so a {@link RunnerDeleted} still says what went). {@code eventId} is {@link
 * BuildSuccessful}'s: generated when absent, final once set, travelling in the envelope and never in
 * the payload. {@code occurredAt} is when the thing happened — here the row's own {@code createdAt}
 * — never when {@code publish()} was called, and it is named for the contract it satisfies, so
 * {@code CanonicalJson} excludes it from the payload and the instant rides the envelope alone, as
 * {@link BuildStatusChanged}'s does. Every nullable field is omitted from the canonical payload
 * rather than written as an explicit null.
 *
 * <p><b>Plain strings, never this service's enums</b>: {@code plane} is {@code CiRunnerPlane}'s word
 * ({@code EDGE} or {@code INTERNAL}), and a wire vocabulary that imported the enum would make every
 * subscriber depend on qits-ci's storage model — {@link BuildFailed#outcome}'s rule.
 *
 * <p><b>Published only once the row committed</b>, and never for a refused create: a malformed
 * request (400), a taken name (409) and a registration token qits-idp would not mint (502) all leave
 * no row, and so no event. {@code description} is nullable — an operator need not write one — and so
 * is {@code stepMemoryLimit}, the docker size ({@code 6g}) this runner's steps are capped at, null
 * while it takes the platform's own {@code qits.ci.memory-limit}.
 */
public record RunnerCreated(
    UUID eventId,
    String runnerId,
    String runnerName,
    int slots,
    String plane,
    String description,
    String stepMemoryLimit,
    Instant occurredAt)
    implements QitsEvent {

  public RunnerCreated {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerCreated(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      String stepMemoryLimit,
      Instant occurredAt) {
    this(null, runnerId, runnerName, slots, plane, description, stepMemoryLimit, occurredAt);
  }
}
