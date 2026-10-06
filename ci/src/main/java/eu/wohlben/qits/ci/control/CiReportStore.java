package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.dto.CiReportDto;
import eu.wohlben.qits.ci.dto.CiReportHighlightDto;
import eu.wohlben.qits.ci.dto.CiReportSubmission;
import eu.wohlben.qits.ci.dto.CiReportSummaryDto;
import eu.wohlben.qits.ci.entity.CiReport;
import eu.wohlben.qits.ci.persistence.CiReportRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The release-report store (epic qits-754, Design §4): one row per {@code (run, step, kind)}, kind,
 * kind version, an opaque JSON payload and opaque highlights.
 *
 * <p><b>Kind-agnostic, and that is the whole design.</b> Nothing here reads a field of a payload or
 * knows what a kind means — highlights and deltas are computed by the CLI at submit time — so a new
 * report kind is no change to this class, the table or the doors.
 *
 * <p>The door's checks (the kind's name, the run's binding, its status, the size and the highlight
 * count) are {@code CiReportController}'s, and happen before anything reaches here; what this class
 * is handed is already admitted.
 */
@ApplicationScoped
public class CiReportStore {

  private static final TypeReference<List<CiReportHighlightDto>> HIGHLIGHTS =
      new TypeReference<>() {};

  @Inject CiReportRepository reports;

  @Inject ObjectMapper objectMapper;

  /**
   * Store a step's report of one kind, <b>replacing</b> any earlier one for the same triple in the
   * same transaction — a re-submit is a correction, and "the" report of a kind on a step is one row.
   * The replacement gets a fresh id, so a reader holding the old id is answered 404 rather than a
   * different document under a name it already resolved.
   *
   * @return the row as stored
   */
  @Transactional
  public CiReport submit(String runId, int stepIndex, String kind, CiReportSubmission submission) {
    Objects.requireNonNull(submission.kindVersion(), "kindVersion");
    Objects.requireNonNull(submission.payload(), "payload");
    String payload = write(submission.payload());
    String highlights =
        write(submission.highlights() == null ? List.of() : submission.highlights());
    reports.findByTriple(runId, stepIndex, kind).ifPresent(reports::delete);
    // The delete has to reach the database before the insert does: Hibernate orders inserts ahead
    // of deletes at flush, and the unique key on the triple would refuse the new row.
    reports.flush();
    CiReport row = new CiReport();
    row.id = UUID.randomUUID();
    row.runId = runId;
    row.stepIndex = stepIndex;
    row.kind = kind;
    row.kindVersion = submission.kindVersion();
    row.payload = payload;
    row.highlights = highlights;
    row.baselineRunId = submission.baseline() == null ? null : submission.baseline().runId();
    row.baselineVersion = submission.baseline() == null ? null : submission.baseline().version();
    row.payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
    row.submittedAt = Instant.now();
    reports.persist(row);
    return row;
  }

  /** A run's reports, in step order and then by kind. Empty for a run with none, or no such run. */
  public List<CiReport> forRun(String runId) {
    return reports.listByRun(runId);
  }

  /** A run's reports of one kind, in step order. */
  public List<CiReport> forRun(String runId, String kind) {
    return reports.listByRunAndKind(runId, kind);
  }

  /**
   * One report of one run. Empty when the id is not a UUID, names no report, or names another run's
   * — a report is addressed under its run, and the run in the path is part of its identity.
   */
  public Optional<CiReport> byId(String runId, String reportId) {
    UUID id;
    try {
      id = UUID.fromString(reportId);
    } catch (IllegalArgumentException | NullPointerException e) {
      return Optional.empty();
    }
    return reports.findByIdOptional(id).filter(report -> report.runId.equals(runId));
  }

  /**
   * Every report of a run, beside wherever the run's steps are deleted ({@code
   * CiRunService.discardRun} and the boot sweep's orphan restart). Joins the caller's transaction.
   */
  @Transactional
  public long deleteForRun(String runId) {
    return reports.deleteByRun(runId);
  }

  /** The row without its payload, as the listing answers it. */
  public CiReportSummaryDto summary(CiReport report) {
    return new CiReportSummaryDto(
        report.id.toString(),
        report.kind,
        report.kindVersion,
        report.stepIndex,
        highlightsOf(report),
        report.baselineRunId,
        report.baselineVersion,
        report.payloadBytes,
        report.submittedAt);
  }

  /** The row with its payload. */
  public CiReportDto full(CiReport report) {
    JsonNode payload;
    try {
      payload = objectMapper.readTree(report.payload);
    } catch (JsonProcessingException e) {
      // Only ever written by submit(), from a parsed document; unreadable is a broken row.
      throw new IllegalStateException("ci_report " + report.id + " holds unreadable JSON", e);
    }
    return new CiReportDto(
        report.id.toString(),
        report.kind,
        report.kindVersion,
        report.stepIndex,
        highlightsOf(report),
        report.baselineRunId,
        report.baselineVersion,
        report.payloadBytes,
        report.submittedAt,
        payload);
  }

  private List<CiReportHighlightDto> highlightsOf(CiReport report) {
    try {
      return objectMapper.readValue(report.highlights, HIGHLIGHTS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("ci_report " + report.id + " holds unreadable highlights", e);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("A report could not be written as JSON", e);
    }
  }
}
