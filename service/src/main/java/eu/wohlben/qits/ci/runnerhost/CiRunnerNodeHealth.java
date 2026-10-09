package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.RunnerNodeHealth;
import eu.wohlben.qits.cirunner.protocol.HealthCheck;
import eu.wohlben.qits.cirunner.protocol.HealthChecked;
import eu.wohlben.qits.runner.protocol.health.CheckResult;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>A runner's node health report</b> (qits-896): the {@code healthCheck} frame a connected runner
 * is sent, the {@code healthChecked} it answers with every named check it ran on its node, and the
 * report stored on {@code ci_runner.node_health} either way. qits-workspaces' {@code
 * WorkspaceRunnerHealth} is the template — the request, the claim, the pending memory and the
 * timeout are its — without its gate.
 *
 * <p><b>A diagnosis, never a decision.</b> The pseudo-build ({@code CiRunnerHealth}) is what
 * quarantines and reinstates a runner, and the infra streak is what counts toward one; nothing here
 * reads the row's standing or writes it, and a report that fails changes nothing but the report.
 *
 * <p><b>When one is asked for</b>: by the operator's door ({@code POST /runners/{id}/healthcheck},
 * beside the pseudo-build it queues) and by {@code CiRunnerHealth.sweep} whenever it queues one, which
 * reaches here through {@code CiRunnerSignals.nodeHealthCheck}. Only a runner with a serving session
 * — greeted at the pin, not draining — is asked; any other is answered null and sent nothing.
 *
 * <p><b>The image</b> the runner's {@code stepImage} check looks for is {@code
 * qits.ci.runner.healthcheck.image}, resolved exactly as the pseudo-build's step image is ({@link
 * CiRunService#resolveStepImage}) and moved to the registry's public name the way a step's launch is
 * ({@link StepAddressPlane#imageReference}), so the runner pulls what its next health check would
 * start. Unpinned: the digest is a run's, taken at its accept.
 *
 * <p><b>A pending request is memory, keyed by its {@code requestId}</b> (a UUID per frame sent), one
 * per runner. An answer naming it settles it; an answer naming none — a runner older than the field —
 * settles the runner's pending one; an answer naming another (one already settled as unanswered, or
 * never sent by this process) is logged at debug and dropped. A request sent on a session that has
 * since closed is not waited for: the next request sends a fresh one, and the old one is forgotten.
 * One still pending after {@code qits.ci.runner.node-healthcheck.timeout} is stored as a report that
 * says {@value #NO_ANSWER}, with its {@code requestId} and no checks — what a runner too old to know
 * the frame, or one that left with it unanswered, always gets. A restart forgets every pending
 * request: the row, not the memory, carries the reports.
 *
 * <p><b>Nothing here may fail its caller</b>: the door's request, the sweep or the socket's frame. A
 * report that could not be recorded is logged, and replaced by the next one.
 */
@ApplicationScoped
public class CiRunnerNodeHealth {

  private static final Logger LOG = Logger.getLogger(CiRunnerNodeHealth.class);

  /** The detail of a report whose request its runner did not answer in time. */
  public static final String NO_ANSWER = "NO_ANSWER";

  @Inject CiRunnerRegistry registry;

  @Inject CiRunners runners;

  @Inject CiRunService runService;

  @Inject RunnerAddresses addresses;

  @Inject StepContainerSettings settings;

  /** How long a sent request is waited for before it is stored {@value #NO_ANSWER}. */
  @ConfigProperty(name = "qits.ci.runner.node-healthcheck.timeout")
  Duration timeout;

  /** The pseudo-build's step image, which the runner's {@code stepImage} check is told to find. */
  @ConfigProperty(name = "qits.ci.runner.healthcheck.image")
  String healthcheckImage;

  /** A request sent and not yet settled: its id, when it went, and the session it went on. */
  record Pending(String requestId, Instant sentAt, CiRunnerRegistry.Session session) {}

  /** The one pending request per runner. */
  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

  // --- the request --------------------------------------------------------------------------------

  /**
   * Asks a connected runner for its node health report now and answers the request's id; a runner
   * with one pending on a live session is answered that one's id and sent nothing more. Null when
   * the runner holds no serving session, or the frame could not leave. Never throws.
   */
  public String request(UUID runnerId) {
    try {
      return sendUnlessPending(runnerId, Instant.now());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not ask runner %s for its node health report", runnerId);
      return null;
    }
  }

  /**
   * The image the runner's {@code stepImage} check is told to look for: the health check's step
   * image, resolved as the pseudo-build resolves it, under the registry's public name when this
   * qits-ci knows its domain.
   */
  String image() {
    String resolved = runService.resolveStepImage(healthcheckImage);
    try {
      return addresses
          .edgeOrigins()
          .map(origins -> settings.plane(origins).imageReference(resolved))
          .orElse(resolved);
    } catch (RuntimeException e) {
      LOG.debugf("Could not move %s to its public registry name: %s", resolved, e.getMessage());
      return resolved;
    }
  }

  /** The runner's row was deleted: nothing about it is waited for any more. */
  void forget(UUID runnerId) {
    pending.remove(runnerId);
  }

  // --- the answer ---------------------------------------------------------------------------------

  /** The runner answered: its pending request is settled, and the report is stored. */
  void onHealthChecked(CiRunnerRegistry.Session session, HealthChecked checked) {
    UUID runnerId = session.runnerId();
    Pending settled = claim(runnerId, checked.requestId());
    if (settled == null) {
      LOG.debugf(
          "Runner %s answered node health request %s, which is not pending — dropped",
          session.runnerName(), checked.requestId());
      return;
    }
    String requestId = checked.requestId() == null ? settled.requestId() : checked.requestId();
    String detail = checked.detail() == null ? "" : checked.detail();
    if (checked.ok()) {
      LOG.infof("Node health check %s of runner %s passed: %s", requestId, session.runnerName(), detail);
    } else {
      LOG.warnf("Node health check %s of runner %s failed: %s", requestId, session.runnerName(), detail);
    }
    record(runnerId, checked.ok(), detail, requestId, checks(checked.checks()), Instant.now());
  }

  /**
   * The pending request {@code requestId} answers, removed: the one it names, or the runner's
   * pending one when it names none. Null when it names one that is not pending, or none is.
   */
  private Pending claim(UUID runnerId, String requestId) {
    if (requestId == null) {
      return pending.remove(runnerId);
    }
    Pending[] claimed = {null};
    pending.computeIfPresent(
        runnerId,
        (id, waiting) -> {
          if (requestId.equals(waiting.requestId())) {
            claimed[0] = waiting;
            return null;
          }
          return waiting;
        });
    return claimed[0];
  }

  private static List<RunnerNodeHealth.Check> checks(List<CheckResult> results) {
    if (results == null) {
      return List.of();
    }
    return results.stream()
        .map(c -> new RunnerNodeHealth.Check(c.name(), c.ok(), c.detail(), c.data()))
        .toList();
  }

  /** Stores one report on the runner's row. Never throws. */
  private void record(
      UUID runnerId,
      boolean ok,
      String detail,
      String requestId,
      List<RunnerNodeHealth.Check> checks,
      Instant at) {
    try {
      CiRunner row =
          runners.recordNodeHealth(
              runnerId, RunnerNodeHealth.encode(ok, detail, requestId, checks), at);
      if (row == null) {
        LOG.debugf("Runner %s is gone; its node health report %s is dropped", runnerId, requestId);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Node health report %s of runner %s could not be recorded", requestId, runnerId);
    }
  }

  // --- the timeout --------------------------------------------------------------------------------

  /**
   * Every {@code qits.ci.runner.healthcheck.sweep-interval}, an {@link #expire} — the health
   * schedule's resolution, which is fine-grained enough against a five-minute deadline. Delayed by
   * one interval like that sweep, and skipped while one is still running.
   */
  @Scheduled(
      every = "{qits.ci.runner.healthcheck.sweep-interval}",
      delayed = "{qits.ci.runner.healthcheck.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void expireTick() {
    expire(Instant.now());
  }

  /**
   * One pass, as of {@code now} — package-private because the tick is stretched out of a suite's
   * way, so this is what a test drives: every request pending longer than {@code
   * qits.ci.runner.node-healthcheck.timeout} is stored as a {@value #NO_ANSWER} report carrying its
   * {@code requestId} and no checks.
   */
  void expire(Instant now) {
    for (Map.Entry<UUID, Pending> entry : List.copyOf(pending.entrySet())) {
      Pending waiting = entry.getValue();
      if (waiting.sentAt().plus(timeout).isAfter(now) || !pending.remove(entry.getKey(), waiting)) {
        continue;
      }
      LOG.warnf(
          "Node health check %s of runner %s was not answered within %s",
          waiting.requestId(), entry.getKey(), timeout);
      record(entry.getKey(), false, NO_ANSWER, waiting.requestId(), List.of(), now);
    }
  }

  // --- internals ----------------------------------------------------------------------------------

  /** The id of the runner's pending request, or null: what a suite waits on. */
  String pendingRequest(UUID runnerId) {
    Pending waiting = pending.get(runnerId);
    return waiting == null ? null : waiting.requestId();
  }

  /**
   * Sends {@code healthCheck{requestId, image}} to the runner's serving session and answers the id —
   * or the pending request's id when one is waiting on a live session; null when no session could
   * take it. The pending entry is written before the frame leaves, so an answer cannot outrun it.
   */
  private String sendUnlessPending(UUID runnerId, Instant now) {
    CiRunnerRegistry.Session session = registry.serving(runnerId);
    if (session == null) {
      return null;
    }
    Pending fresh = new Pending(UUID.randomUUID().toString(), now, session);
    Pending[] standing = {null};
    pending.compute(
        runnerId,
        (id, waiting) -> {
          if (waiting != null && waiting.session().isOpen()) {
            standing[0] = waiting;
            return waiting;
          }
          return fresh;
        });
    if (standing[0] != null) {
      return standing[0].requestId();
    }
    String image = image();
    if (!registry.send(session, new HealthCheck(fresh.requestId(), image))) {
      pending.remove(runnerId, fresh);
      return null;
    }
    LOG.infof(
        "Asked runner %s for node health check %s (step image %s)",
        session.runnerName(), fresh.requestId(), image);
    return fresh.requestId();
  }
}
