package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records what {@link CiRunService} announces through the {@link RunAnnouncer} port. State is read
 * through <b>methods</b>, because a field read on an injected CDI client proxy sees the proxy's
 * fields rather than the bean's — the convention every fake in this package follows.
 */
@Mock
@ApplicationScoped
public class FakeRunAnnouncer implements RunAnnouncer {

  public record Announced(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      Instant finishedAt,
      String triggerEventId) {}

  public record AnnouncedFailure(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      String outcome,
      Instant finishedAt,
      String triggerEventId) {}

  /**
   * One {@link RunAnnouncer#onRunStatusChanged} call. Unlike the two above there are several per
   * run, so the list is a <b>sequence</b> and the order it is read back in is part of what a test
   * asserts.
   */
  public record AnnouncedStatus(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      String status,
      String previousStatus,
      Instant occurredAt,
      String triggerEventId) {}

  private final List<Announced> announced = Collections.synchronizedList(new ArrayList<>());
  private final List<AnnouncedFailure> failed = Collections.synchronizedList(new ArrayList<>());
  private final List<AnnouncedStatus> statuses = Collections.synchronizedList(new ArrayList<>());

  public List<Announced> announced() {
    return List.copyOf(announced);
  }

  public List<AnnouncedFailure> failed() {
    return List.copyOf(failed);
  }

  public List<AnnouncedStatus> statuses() {
    return List.copyOf(statuses);
  }

  /** The status words announced for one run, in order — what a lifecycle assertion is made of. */
  public List<String> statusesOf(String runId) {
    return statuses().stream()
        .filter(status -> status.runId().equals(runId))
        .map(AnnouncedStatus::status)
        .toList();
  }

  public void reset() {
    announced.clear();
    failed.clear();
    statuses.clear();
  }

  @Override
  public void onRunSucceeded(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      Instant finishedAt,
      String triggerEventId) {
    announced.add(
        new Announced(
            runId,
            repoId,
            projectId,
            repoName,
            branch,
            commitSha,
            gating,
            finishedAt,
            triggerEventId));
  }

  @Override
  public void onRunFailed(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      String outcome,
      Instant finishedAt,
      String triggerEventId) {
    failed.add(
        new AnnouncedFailure(
            runId,
            repoId,
            projectId,
            repoName,
            branch,
            commitSha,
            gating,
            outcome,
            finishedAt,
            triggerEventId));
  }

  @Override
  public void onRunStatusChanged(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      String status,
      String previousStatus,
      Instant occurredAt,
      String triggerEventId) {
    statuses.add(
        new AnnouncedStatus(
            runId,
            repoId,
            projectId,
            repoName,
            branch,
            commitSha,
            gating,
            status,
            previousStatus,
            occurredAt,
            triggerEventId));
  }
}
