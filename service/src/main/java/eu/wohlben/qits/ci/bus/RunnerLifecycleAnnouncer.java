package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.control.RunnerAnnouncer;
import eu.wohlben.qits.ci.events.RunnerChanged;
import eu.wohlben.qits.ci.events.RunnerConnected;
import eu.wohlben.qits.ci.events.RunnerCreated;
import eu.wohlben.qits.ci.events.RunnerDeleted;
import eu.wohlben.qits.ci.events.RunnerDisconnected;
import eu.wohlben.qits.ci.events.RunnerRegistered;
import eu.wohlben.qits.ci.events.RunnerUpdateStarted;
import eu.wohlben.qits.ci.events.RunnerUpdated;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Turns a runner's lifecycle into the platform's runner events and hands them to the bus — {@link
 * BuildAnnouncer}'s job for the {@link RunnerAnnouncer} port, one event per method and every
 * component passed straight through, null with it.
 *
 * <p><b>It does not publish on the caller's thread, and that is the one way it differs from {@link
 * BuildAnnouncer}.</b> {@link QitsEventBus#publish} never throws but can block for its publish
 * timeout when qits-events is unreachable, and {@link BuildAnnouncer} accepted that because its
 * caller is a run worker between one run and the next. Half of this port's callers are a runner
 * socket's frame handlers, where the same wait would be a {@code Hello} left without its {@code Ack}
 * — a runner held off its slots by an outage of something it never talks to — and the other half are
 * an operator's requests. So each event is built on the caller's thread, which is what fixes its
 * facts and its instant, and handed to <b>one</b> publishing thread; the call returns at once.
 *
 * <p><b>One thread, not a pool, because the order is part of what is said.</b> A self-update is
 * {@code RunnerUpdateStarted}, then {@code RunnerUpdated}, then the old connection's {@code
 * RunnerDisconnected}; a pool could put the close on the wire before the rollover it follows. A
 * single thread publishes in the order the calls were made. The envelope's {@code occurredAt} is the
 * event's own and does not depend on this ordering — the ordering is for a subscriber reading the
 * stream live.
 *
 * <p><b>The cause is read on the caller's thread and passed on explicitly</b>, because a thread-local
 * does not follow work onto another thread (the reason {@link BuildAnnouncer} takes its parent as an
 * argument too). An operator's request made under a causation scope keeps its edge; everything else
 * — every socket event — publishes a root, which is what it is.
 *
 * <p><b>What stopping costs.</b> On shutdown the queue gets a few seconds to drain — long enough for
 * the {@code SHUTDOWN} disconnections the registry announces as the process stops — and anything
 * still queued after that is dropped with a WARN, like any announcement that could not be made. An
 * event already handed to the bus is the outbox's from there.
 */
@ApplicationScoped
public class RunnerLifecycleAnnouncer implements RunnerAnnouncer {

  private static final Logger LOG = Logger.getLogger(RunnerLifecycleAnnouncer.class);

  /** How long a stopping process waits for the queue to drain. */
  private static final long DRAIN_SECONDS = 5;

  @Inject QitsEventBus bus;

  private final ExecutorService publisher =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "ci-runner-events");
            t.setDaemon(true);
            return t;
          });

  @Override
  public void onRunnerCreated(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      Instant createdAt) {
    publish(new RunnerCreated(runnerId, runnerName, slots, plane, description, createdAt));
  }

  @Override
  public void onRunnerRegistered(
      String runnerId,
      String runnerName,
      String clientId,
      Boolean docker,
      String arch,
      String os,
      Instant registeredAt) {
    publish(new RunnerRegistered(runnerId, runnerName, clientId, docker, arch, os, registeredAt));
  }

  @Override
  public void onRunnerConnected(
      String runnerId,
      String runnerName,
      String runnerVersion,
      String targetVersion,
      boolean upgradeRequired,
      Boolean docker,
      String arch,
      String os,
      Instant occurredAt) {
    publish(
        new RunnerConnected(
            runnerId, runnerName, runnerVersion, targetVersion, upgradeRequired, docker, arch, os,
            occurredAt));
  }

  @Override
  public void onRunnerDisconnected(
      String runnerId,
      String runnerName,
      String runnerVersion,
      String reason,
      int heldRuns,
      Instant occurredAt) {
    publish(
        new RunnerDisconnected(runnerId, runnerName, runnerVersion, reason, heldRuns, occurredAt));
  }

  @Override
  public void onRunnerUpdateStarted(
      String runnerId,
      String runnerName,
      String fromVersion,
      String toVersion,
      int heldRuns,
      Instant occurredAt) {
    publish(
        new RunnerUpdateStarted(
            runnerId, runnerName, fromVersion, toVersion, heldRuns, occurredAt));
  }

  @Override
  public void onRunnerUpdated(
      String runnerId, String runnerName, String fromVersion, String toVersion, Instant occurredAt) {
    publish(new RunnerUpdated(runnerId, runnerName, fromVersion, toVersion, occurredAt));
  }

  @Override
  public void onRunnerChanged(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      List<String> changed,
      Instant occurredAt) {
    publish(
        new RunnerChanged(runnerId, runnerName, slots, plane, description, changed, occurredAt));
  }

  @Override
  public void onRunnerDeleted(String runnerId, String runnerName, Instant occurredAt) {
    publish(new RunnerDeleted(runnerId, runnerName, occurredAt));
  }

  /** Queue one event for the publishing thread, with the cause read here, on the caller's thread. */
  private void publish(QitsEvent event) {
    UUID parent = CausationScope.current();
    try {
      publisher.execute(() -> bus.publish(event, parent));
    } catch (RejectedExecutionException stopped) {
      LOG.warnf(
          "%s %s not published: this process is stopping", event.signature(), event.eventId());
    }
  }

  @PreDestroy
  void drain() {
    publisher.shutdown();
    try {
      if (!publisher.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
        LOG.warnf(
            "%d runner event(s) still queued after %ds; dropped",
            publisher.shutdownNow().size(), DRAIN_SECONDS);
      }
    } catch (InterruptedException e) {
      publisher.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
