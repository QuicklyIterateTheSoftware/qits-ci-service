package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The runner event port in the {@code ci} module's suite, which ships no bus announcer: every call,
 * in order, as {@code "<Event> <runnerId> <fact>"}. An additional implementation (no {@code
 * @Mock}), like the service suite's {@code RecordingRunnerAnnouncer}, whose fuller shape is not worth
 * a second copy here — the quarantine's three events are what this module's tests assert.
 */
@ApplicationScoped
public class RecordingRunnerEvents implements RunnerAnnouncer {

  private final List<String> events = Collections.synchronizedList(new ArrayList<>());

  public void reset() {
    events.clear();
  }

  /** Every event about one runner, in order, as {@code "<Event> <fact>"}. */
  public List<String> of(String runnerId) {
    synchronized (events) {
      String marker = " " + runnerId + " ";
      return events.stream()
          .filter(e -> e.contains(marker))
          .map(e -> e.substring(0, e.indexOf(marker)) + " " + e.substring(e.indexOf(marker) + marker.length()))
          .toList();
    }
  }

  private void record(String event, String runnerId, String fact) {
    events.add(event + " " + runnerId + " " + fact);
  }

  @Override
  public void onRunnerCreated(
      String runnerId,
      String runnerName,
      int slots,
      String plane,
      String description,
      String stepMemoryLimit,
      Instant at) {
    record("RunnerCreated", runnerId, runnerName);
  }

  @Override
  public void onRunnerRegistered(
      String runnerId,
      String runnerName,
      String clientId,
      Boolean docker,
      String arch,
      String os,
      Instant at) {
    record("RunnerRegistered", runnerId, clientId);
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
      Instant at) {
    record("RunnerConnected", runnerId, runnerVersion);
  }

  @Override
  public void onRunnerDisconnected(
      String runnerId, String runnerName, String runnerVersion, String reason, int held, Instant at) {
    record("RunnerDisconnected", runnerId, reason);
  }

  @Override
  public void onRunnerUpdateStarted(
      String runnerId, String runnerName, String from, String to, int held, Instant at) {
    record("RunnerUpdateStarted", runnerId, to);
  }

  @Override
  public void onRunnerUpdated(
      String runnerId, String runnerName, String from, String to, Instant at) {
    record("RunnerUpdated", runnerId, to);
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
      Instant at) {
    record("RunnerChanged", runnerId, String.valueOf(changed));
  }

  @Override
  public void onRunnerDeleted(String runnerId, String runnerName, Instant at) {
    record("RunnerDeleted", runnerId, runnerName);
  }

  @Override
  public void onRunnerQuarantined(String runnerId, String runnerName, String reason, Instant at) {
    record("RunnerQuarantined", runnerId, reason);
  }

  @Override
  public void onRunnerReinstated(String runnerId, String runnerName, String by, Instant at) {
    record("RunnerReinstated", runnerId, by);
  }

  @Override
  public void onRunnerHealthChecked(
      String runnerId, String runnerName, String runId, String result, String detail, Instant at) {
    record("RunnerHealthChecked", runnerId, result + (detail == null ? "" : " " + detail));
  }
}
