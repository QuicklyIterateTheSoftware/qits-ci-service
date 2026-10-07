package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.dto.CiReportDto;
import eu.wohlben.qits.ci.dto.CiReportHighlightDto;
import eu.wohlben.qits.ci.dto.CiReportSubmission;
import eu.wohlben.qits.ci.dto.CiReportSummaryDto;
import eu.wohlben.qits.ci.entity.CiReport;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The release-report store (qits-983): a re-submit of the same {@code (run, step, kind)} replaces
 * the row, and a run's reports go wherever its steps go — including the boot sweep's restart of an
 * orphaned run, which must not leave the previous attempt's reports behind its fresh steps.
 */
@QuarkusTest
public class CiReportStoreTest extends CiTestSupport {

  @Inject CiReportStore store;

  @Inject ObjectMapper objectMapper;

  @Test
  public void aResubmitOfTheSameStepAndKindReplacesTheRow() throws Exception {
    String runId = UUID.randomUUID().toString();
    CiReport first =
        store.submit(runId, 1, "test-results", submission(1, "{\"totals\":{\"tests\":3}}", "bad"));
    CiReport second =
        store.submit(runId, 1, "test-results", submission(1, "{\"totals\":{\"tests\":4}}", "good"));
    // Another step's report of the same kind, and another kind on the same step, are other rows.
    store.submit(runId, 2, "test-results", submission(1, "{}", "info"));
    store.submit(runId, 1, "coverage", submission(2, "{\"total\":null}", "info"));

    List<CiReport> rows = store.forRun(runId);
    assertEquals(3, rows.size(), "the resubmit replaced rather than added");
    assertNotEquals(first.id, second.id, "the replacement is a new row");
    assertTrue(store.byId(runId, first.id.toString()).isEmpty(), "the first is gone");

    CiReportDto full = store.full(store.byId(runId, second.id.toString()).orElseThrow());
    assertEquals(4, full.payload().path("totals").path("tests").asInt());
    assertEquals(List.of(new CiReportHighlightDto("good", "good", "m", 1.0, -0.5)), full.highlights());
    assertEquals("baseline-run", full.baselineRunId());
    assertEquals("2026.1003.52637", full.baselineVersion());
    assertEquals(
        objectMapper.writeValueAsString(full.payload()).getBytes().length, full.payloadBytes());

    // Step order, then kind.
    List<CiReportSummaryDto> summaries = rows.stream().map(store::summary).toList();
    assertEquals(List.of(1, 1, 2), summaries.stream().map(CiReportSummaryDto::stepIndex).toList());
    assertEquals(
        List.of("coverage", "test-results", "test-results"),
        summaries.stream().map(CiReportSummaryDto::kind).toList());
    assertEquals(1, store.forRun(runId, "coverage").size());
    assertTrue(store.byId("another-run", second.id.toString()).isEmpty(), "addressed under its run");
    assertTrue(store.byId(runId, "not-a-uuid").isEmpty());
  }

  @Test
  public void noBaselineAndNoHighlightsAreStoredAsSuch() {
    String runId = UUID.randomUUID().toString();
    CiReport row =
        store.submit(
            runId,
            0,
            "coverage",
            new CiReportSubmission(1, null, null, objectMapper.createObjectNode()));
    CiReportSummaryDto summary = store.summary(row);
    assertEquals(List.of(), summary.highlights());
    assertNull(summary.baselineRunId());
    assertNull(summary.baselineVersion());
  }

  @Test
  public void deleteForRunTakesEveryReportOfThatRunAndNoOther() {
    String runId = UUID.randomUUID().toString();
    String other = UUID.randomUUID().toString();
    store.submit(runId, 0, "test-results", submission(1, "{}", "info"));
    store.submit(runId, 1, "coverage", submission(1, "{}", "info"));
    store.submit(other, 0, "test-results", submission(1, "{}", "info"));

    assertEquals(2, store.deleteForRun(runId));
    assertTrue(store.forRun(runId).isEmpty());
    assertEquals(1, store.forRun(other).size());
  }

  @Test
  public void anOrphanRestartedByTheBootSweepLosesItsReportsWithItsSteps() throws Exception {
    String runId = insertOrphan();
    store.submit(runId, 0, "test-results", submission(1, "{}", "bad"));

    runService.sweepInterrupted();
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertTrue(
        store.forRun(runId).isEmpty(), "a restarted run's reports do not outlive its old steps");
  }

  private CiReportSubmission submission(int kindVersion, String payload, String severity) {
    try {
      return new CiReportSubmission(
          kindVersion,
          List.of(new CiReportHighlightDto(severity, severity, "m", 1.0, -0.5)),
          new CiReportSubmission.Baseline("baseline-run", "2026.1003.52637"),
          objectMapper.readTree(payload));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A RUNNING event run a previous process left behind, restartable from its own row. */
  private String insertOrphan() {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = "report-orphan-" + id;
              run.branch = "main";
              run.commitSha = "e".repeat(40);
              run.status = CiRunStatus.RUNNING;
              run.createdAt = Instant.now();
              run.triggerType = CiTriggerType.EVENT;
              run.daemonVersion = "dead-daemon";
              run.configPath = ".config/qits/ci-event-upstream.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = "BuildSuccessful";
              run.triggerEventOccurredAt = Instant.parse("2026-08-02T15:40:31Z");
              run.triggerEventPayload = "{\"version\":\"2026.802.154030\"}";
              run.triggerConfig =
                  """
                  event: BuildSuccessful
                  steps:
                    - image: alpine:3
                      script: echo recovered
                  """;
              runs.persist(run);
            });
    return id;
  }
}
