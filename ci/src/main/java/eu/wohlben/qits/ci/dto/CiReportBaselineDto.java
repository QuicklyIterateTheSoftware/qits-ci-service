package eu.wohlben.qits.ci.dto;

/**
 * A run's baseline: the newest released version of its repository, the gating QA run of the release
 * request that produced it, that request, and the sha the version's release run built (epic
 * qits-754, Design §4). {@code releaseRequestQualifiedId} is that request's logical id (qits-1158),
 * or null when nothing recorded one.
 */
public record CiReportBaselineDto(
    String version,
    String runId,
    String releaseRequestId,
    String releaseRequestQualifiedId,
    String tagSha) {}
