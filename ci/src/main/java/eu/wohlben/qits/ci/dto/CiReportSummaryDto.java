package eu.wohlben.qits.ci.dto;

import java.time.Instant;
import java.util.List;

/**
 * One stored report without its payload — what a run's report listing carries (epic qits-754,
 * Design §5). {@code baselineRunId} and {@code baselineVersion} are what the submitter compared
 * against, null when it had none.
 */
public record CiReportSummaryDto(
    String id,
    String kind,
    int kindVersion,
    int stepIndex,
    List<CiReportHighlightDto> highlights,
    String baselineRunId,
    String baselineVersion,
    int payloadBytes,
    Instant submittedAt) {}
