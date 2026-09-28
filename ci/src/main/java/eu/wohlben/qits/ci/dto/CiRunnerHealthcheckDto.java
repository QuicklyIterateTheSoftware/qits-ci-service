package eu.wohlben.qits.ci.dto;

import java.time.Instant;

/**
 * A runner's newest health check, as its read shape carries it — or, by the whole object being null
 * on the runner, none yet.
 *
 * <p>{@code at} is when it settled, {@code result} is {@code PASSED} or {@code FAILED}, {@code runId}
 * is the pseudo-build's own run (readable at {@code GET /ci/api/runs/{runId}}, which is how a runner's
 * page links it — the run is in no listing), and {@code detail} is what it said: the step's outcome
 * and the head of its output, or why it never ran.
 */
public record CiRunnerHealthcheckDto(Instant at, String result, String runId, String detail) {}
