package eu.wohlben.qits.ci.dto;

import java.util.List;

/**
 * A run's reports, without payloads, beside the run's own coordinates and its baseline — null when
 * it has none, which is never an error (epic qits-754, Design §5).
 */
public record CiRunReportsDto(
    String runId,
    String commitSha,
    String releaseRequestId,
    CiReportBaselineDto baseline,
    List<CiReportSummaryDto> reports) {}
