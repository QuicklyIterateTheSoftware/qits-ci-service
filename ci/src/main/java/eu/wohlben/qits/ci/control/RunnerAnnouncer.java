package eu.wohlben.qits.ci.control;

import java.time.Instant;
import java.util.List;

/**
 * The port a runner's lifecycle is announced to the <b>platform at large</b> through — {@link
 * RunAnnouncer}'s shape, for the runner rather than the run. One method per lifecycle fact, because
 * the events are one per fact: see {@code RunnerCreated} in {@code ci-events/} for why they are
 * eight and not one.
 *
 * <p><b>Two kinds of caller, and one rule for both: announce what already happened.</b> {@link
 * CiRunners} calls the four row methods — created, registered, changed, deleted — after the
 * transaction that wrote the row has committed, so a subscriber reading the runner back sees what it
 * was told and a refused request announces nothing. The runner socket's registry ({@code
 * service/…/runnerhost/CiRunnerRegistry}) calls the four connection methods when its own state
 * changes — a {@code Hello} taken, a connection dropped, an {@code Upgrade} or a {@code Retire}
 * decided — which is in memory and has no transaction to wait for.
 *
 * <p><b>Every parameter is a plain {@code String}, {@code int} or {@code boolean}</b>, {@link
 * RunAnnouncer}'s reason: this module names foreign things by their id and nothing else, and it keeps
 * {@code ci/} free of every eventstream type. {@code runnerId} is the row's uuid as its string; a
 * plane is {@code CiRunnerPlane}'s word; a disconnection's {@code reason} is one of the five words
 * {@code RunnerDisconnected} spells, chosen by the registry, which is the only caller that knows why
 * a socket ended.
 *
 * <p><b>The instant is always the caller's</b> — the row's {@code createdAt} or {@code
 * registeredAt}, the clock inside the transaction that wrote a change or a delete, the moment the
 * registry changed state — and never read at announce time, for the reason {@link
 * RunAnnouncer#onRunSucceeded} gives: what belongs in an event log is when the thing happened.
 *
 * <p><b>An implementation must not block its caller and must not fail it</b>, and here that is
 * stricter than {@link RunAnnouncer}'s "briefly": half of these calls are made on a runner socket's
 * frame handler, where a wait on qits-events is a {@code Hello} left unanswered. The bus
 * implementation ({@code service/…/bus/RunnerLifecycleAnnouncer}) hands each event to one ordered
 * publishing thread and returns. What the callers add is the other half — each call goes through
 * {@link RunnerAnnouncements}, which catches and logs whatever an implementation throws — so a runner
 * is created, registered and served whatever any announcement does. Zero implementations is a
 * supported configuration, and announces nothing at all.
 */
public interface RunnerAnnouncer {

  /** An operator's create committed: the row as written. */
  void onRunnerCreated(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      Instant createdAt);

  /**
   * The register door's write committed. {@code docker}, {@code arch} and {@code os} are read off
   * what the runner registered with, each null when it did not say it.
   */
  void onRunnerRegistered(
      String runnerId,
      String runnerName,
      String clientId,
      Boolean docker,
      String arch,
      String os,
      Instant registeredAt);

  /**
   * A connection's {@code Hello} was taken. {@code targetVersion} is the pin, and {@code
   * upgradeRequired} whether the runner is some other version; the host facts are null when the
   * runner speaks a capability version this host does not read.
   */
  void onRunnerConnected(
      String runnerId,
      String runnerName,
      String runnerVersion,
      String targetVersion,
      boolean upgradeRequired,
      Boolean docker,
      String arch,
      String os,
      Instant occurredAt);

  /**
   * A connection that said {@code Hello} is gone from the registry. {@code heldRuns} is how many
   * runs it still held at that moment.
   */
  void onRunnerDisconnected(
      String runnerId,
      String runnerName,
      String runnerVersion,
      String reason,
      int heldRuns,
      Instant occurredAt);

  /** {@code Upgrade} was decided for a connection, which now drains. */
  void onRunnerUpdateStarted(
      String runnerId,
      String runnerName,
      String fromVersion,
      String toVersion,
      int heldRuns,
      Instant occurredAt);

  /** A connection of the pinned version took over and the other version's is being retired. */
  void onRunnerUpdated(
      String runnerId, String runnerName, String fromVersion, String toVersion, Instant occurredAt);

  /**
   * An operator's change committed and moved at least one setting: the three as they now are, and
   * {@code changed} naming which moved.
   */
  void onRunnerChanged(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      List<String> changed,
      Instant occurredAt);

  /** An operator's delete committed. */
  void onRunnerDeleted(String runnerId, String runnerName, Instant occurredAt);

  /**
   * The runner was taken out of service — its quarantine began, and {@code reason} is the sentence
   * its row now keeps. Called by {@link CiRunnerHealth} after the write committed; a runner already
   * quarantined is not announced again.
   */
  void onRunnerQuarantined(String runnerId, String runnerName, String reason, Instant occurredAt);

  /** A quarantine was lifted — {@code by} is {@code admin} or {@code healthcheck}. */
  void onRunnerReinstated(String runnerId, String runnerName, String by, Instant occurredAt);

  /**
   * A health check settled, {@code PASSED} or {@code FAILED}; {@code detail} is null on a pass with
   * nothing to add.
   */
  void onRunnerHealthChecked(
      String runnerId,
      String runnerName,
      String runId,
      String result,
      String detail,
      Instant occurredAt);
}
