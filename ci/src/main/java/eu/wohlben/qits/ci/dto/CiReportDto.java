package eu.wohlben.qits.ci.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * One stored report with its payload: {@link CiReportSummaryDto}'s fields plus {@code payload}, the
 * kind's JSON document exactly as it was submitted (epic qits-754, Design §5).
 */
public record CiReportDto(
    String id,
    String kind,
    int kindVersion,
    int stepIndex,
    List<CiReportHighlightDto> highlights,
    String baselineRunId,
    String baselineVersion,
    int payloadBytes,
    Instant submittedAt,
    JsonNode payload) {}
