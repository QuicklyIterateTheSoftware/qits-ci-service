package eu.wohlben.qits.ci.dto;

/**
 * {@code GET /ci/api/runs/{runId}/baseline}: an envelope rather than a bare object, so that "no
 * baseline" is a 200 {@code {"baseline":null}} and never a 404 that could be mistaken for an
 * unknown run.
 */
public record CiBaselineAnswer(CiReportBaselineDto baseline) {}
