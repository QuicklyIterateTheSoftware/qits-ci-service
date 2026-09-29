package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records every call made on the {@link RunnerAnnouncer} port, in order, as the event name it
 * becomes and the facts it carried. State is read through <b>methods</b>, because a field read on an
 * injected CDI client proxy sees the proxy's fields rather than the bean's — the convention every
 * fake in this package follows.
 *
 * <p><b>An additional implementation, not a replacement</b> — no {@code @Mock}: the bus announcer
 * stays in the set beside it (dark in {@code %test}), so what is recorded here is exactly what
 * {@link RunnerAnnouncements} hands every announcer, and a suite asserting "published exactly once"
 * is asserting it of the port rather than of a fake that stood in for it. The application is shared
 * by every class under one profile, so a case reads {@link #of} its own runner and never the whole
 * list.
 */
@ApplicationScoped
public class RecordingRunnerAnnouncer implements RunnerAnnouncer {

  /** One port call: the event it becomes, the runner, and every other argument by its name. */
  public record Announced(String event, String runnerId, Map<String, Object> facts) {

    public Object fact(String name) {
      return facts.get(name);
    }
  }

  private final List<Announced> announced = Collections.synchronizedList(new ArrayList<>());

  public void reset() {
    announced.clear();
  }

  /** Everything announced about any runner, in order — for a refusal that never had an id. */
  public List<Announced> all() {
    synchronized (announced) {
      return List.copyOf(announced);
    }
  }

  /** Everything announced about one runner, in the order it was announced. */
  public List<Announced> of(String runnerId) {
    synchronized (announced) {
      return announced.stream().filter(a -> a.runnerId().equals(runnerId)).toList();
    }
  }

  /** The event names announced about one runner, in order. */
  public List<String> eventsOf(String runnerId) {
    return of(runnerId).stream().map(Announced::event).toList();
  }

  /** Waits until {@code count} announcements about the runner have been made, then answers them. */
  public List<Announced> await(String runnerId, int count, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (of(runnerId).size() < count && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    return of(runnerId);
  }

  private void record(String event, String runnerId, Object... namesAndValues) {
    Map<String, Object> facts = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      facts.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    announced.add(new Announced(event, runnerId, Collections.unmodifiableMap(facts)));
  }

  @Override
  public void onRunnerCreated(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      String stepMemoryLimit,
      Instant createdAt) {
    record(
        "RunnerCreated", runnerId, "runnerName", runnerName, "slots", slots, "plane", plane,
        "description", description, "stepMemoryLimit", stepMemoryLimit, "occurredAt", createdAt);
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
    record(
        "RunnerRegistered", runnerId, "runnerName", runnerName, "clientId", clientId, "docker",
        docker, "arch", arch, "os", os, "occurredAt", registeredAt);
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
    record(
        "RunnerConnected", runnerId, "runnerName", runnerName, "runnerVersion", runnerVersion,
        "targetVersion", targetVersion, "upgradeRequired", upgradeRequired, "docker", docker,
        "arch", arch, "os", os, "occurredAt", occurredAt);
  }

  @Override
  public void onRunnerDisconnected(
      String runnerId,
      String runnerName,
      String runnerVersion,
      String reason,
      int heldRuns,
      Instant occurredAt) {
    record(
        "RunnerDisconnected", runnerId, "runnerName", runnerName, "runnerVersion", runnerVersion,
        "reason", reason, "heldRuns", heldRuns, "occurredAt", occurredAt);
  }

  @Override
  public void onRunnerUpdateStarted(
      String runnerId,
      String runnerName,
      String fromVersion,
      String toVersion,
      int heldRuns,
      Instant occurredAt) {
    record(
        "RunnerUpdateStarted", runnerId, "runnerName", runnerName, "fromVersion", fromVersion,
        "toVersion", toVersion, "heldRuns", heldRuns, "occurredAt", occurredAt);
  }

  @Override
  public void onRunnerUpdated(
      String runnerId, String runnerName, String fromVersion, String toVersion, Instant occurredAt) {
    record(
        "RunnerUpdated", runnerId, "runnerName", runnerName, "fromVersion", fromVersion,
        "toVersion", toVersion, "occurredAt", occurredAt);
  }

  @Override
  public void onRunnerChanged(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      String stepMemoryLimit,
      List<String> changed,
      Instant occurredAt) {
    record(
        "RunnerChanged", runnerId, "runnerName", runnerName, "slots", slots, "plane", plane,
        "description", description, "stepMemoryLimit", stepMemoryLimit, "changed", changed,
        "occurredAt", occurredAt);
  }

  @Override
  public void onRunnerDeleted(String runnerId, String runnerName, Instant occurredAt) {
    record("RunnerDeleted", runnerId, "runnerName", runnerName, "occurredAt", occurredAt);
  }

  @Override
  public void onRunnerQuarantined(
      String runnerId, String runnerName, String reason, Instant occurredAt) {
    record(
        "RunnerQuarantined", runnerId, "runnerName", runnerName, "reason", reason, "occurredAt",
        occurredAt);
  }

  @Override
  public void onRunnerReinstated(
      String runnerId, String runnerName, String by, Instant occurredAt) {
    record(
        "RunnerReinstated", runnerId, "runnerName", runnerName, "by", by, "occurredAt",
        occurredAt);
  }

  @Override
  public void onRunnerHealthChecked(
      String runnerId,
      String runnerName,
      String runId,
      String result,
      String detail,
      Instant occurredAt) {
    record(
        "RunnerHealthChecked", runnerId, "runnerName", runnerName, "runId", runId, "result",
        result, "detail", detail, "occurredAt", occurredAt);
  }
}
