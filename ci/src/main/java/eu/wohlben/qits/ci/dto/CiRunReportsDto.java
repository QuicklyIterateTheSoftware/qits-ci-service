package eu.wohlben.qits.ci.dto;

import java.util.List;

/**
 * A run's reports, without payloads, beside the run's own coordinates and its baseline — null when
 * it has none, which is never an error (epic qits-754, Design §5).
 *
 * <p>The gate door ({@code GET /runs/{runId}/gate/reports}, qits-893) answers the same shape for the
 * QA run that gated a run's release request: the gate's coordinates, no baseline, and — when no
 * green QA run gated it — a null {@code runId} and {@code commitSha} beside the asking run's own
 * {@code releaseRequestId}, with no reports.
 *
 * <p>{@code releaseRequestQualifiedId} is that request's logical id, {@code <repository>-rr-<n>}
 * (qits-1158), what a client shows; null when the run's triggering event predates it.
 */
public record CiRunReportsDto(
    String runId,
    String commitSha,
    String releaseRequestId,
    String releaseRequestQualifiedId,
    CiReportBaselineDto baseline,
    List<CiReportSummaryDto> reports) {}
