package eu.wohlben.qits.ci.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The body of {@code PUT /ci/api/runs/{runId}/steps/{stepIndex}/reports/{kind}}: one report a step
 * submits about itself (epic qits-754, Design §5).
 *
 * @param kindVersion the kind's payload schema version; required
 * @param highlights at most ten; null is read as none
 * @param baseline what the submitting CLI compared against, or null when it had no baseline
 * @param payload the kind's JSON document, stored verbatim; required
 */
public record CiReportSubmission(
    Integer kindVersion,
    List<CiReportHighlightDto> highlights,
    Baseline baseline,
    JsonNode payload) {

  /** The baseline the submitter compared against: its run and that run's released version. */
  public record Baseline(String runId, String version) {}
}
