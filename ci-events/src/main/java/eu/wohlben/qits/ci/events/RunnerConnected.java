package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner's connection completed its {@code Hello}: the socket is open, the runner said which
 * version it is, and qits-ci accepted it — to serve, or to update.
 *
 * <p><b>The {@code Hello}, not the dial, is the moment.</b> A dial that never says hello is not yet
 * a runner this service knows anything about (its version decides whether it replaces, drains or is
 * served), and a dial whose {@code Hello} is refused is not connected at all — that one announces
 * only its {@link RunnerDisconnected} with reason {@code REFUSED}. So every {@code RunnerConnected}
 * is a connection that was taken, and a subscriber counting open connections pairs it with exactly
 * one {@link RunnerDisconnected}.
 *
 * <p><b>{@code runnerVersion} is what the runner said; {@code targetVersion} is the pin</b> — the
 * version this qits-ci wants every runner to be ({@code CiRunnerPins}). {@code upgradeRequired} is
 * whether they differ, and when it is true this connection is the one being updated: it holds no
 * slot, and a {@link RunnerUpdateStarted} follows it. Carried rather than left to be derived, because
 * the pin is a fact of this process that a subscriber has no other way to read.
 *
 * <p>{@code docker}, {@code arch} and {@code os} are what this {@code Hello} said about the host —
 * {@link RunnerRegistered}'s flattening, and the newer answer, since a host can change between
 * registration and any later connection. All three are absent from the payload when the runner
 * speaks a capability version this host does not, because qits-ci then reads none of it.
 *
 * <p>{@code occurredAt} is the moment qits-ci accepted the {@code Hello}. Everything else is {@link
 * RunnerCreated}'s, stated there.
 */
public record RunnerConnected(
    UUID eventId,
    String runnerId,
    String runnerName,
    String runnerVersion,
    String targetVersion,
    boolean upgradeRequired,
    Boolean docker,
    String arch,
    String os,
    Instant occurredAt)
    implements QitsEvent {

  public RunnerConnected {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerConnected(
      String runnerId,
      String runnerName,
      String runnerVersion,
      String targetVersion,
      boolean upgradeRequired,
      Boolean docker,
      String arch,
      String os,
      Instant occurredAt) {
    this(
        null, runnerId, runnerName, runnerVersion, targetVersion, upgradeRequired, docker, arch, os,
        occurredAt);
  }
}
