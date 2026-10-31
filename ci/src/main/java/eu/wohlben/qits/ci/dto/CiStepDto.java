package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiStepStatus;
import java.time.Instant;

/**
 * One step of a run as returned to clients. {@code output} is the bounded, tail-truncated combined
 * stdout+stderr — populated only on the single-run endpoint, null in run listings.
 *
 * <p>A step only ever appears here already finished: while it runs it has no row, and the run's
 * {@code live} object carries its output instead. The two timestamps are host-stamped, and null on
 * a skipped step.
 *
 * <p>{@code name} is the step's declared {@code name:} (qits-757), null when it declared none — the
 * label a client shows on the step's tile, falling back to {@code image}. {@code image} is the
 * image as spelled, with no registry host added by qits-ci.
 */
public record CiStepDto(
    int stepIndex,
    String image,
    CiStepStatus status,
    Integer exitCode,
    Instant startedAt,
    Instant finishedAt,
    String output,
    String name) {}
