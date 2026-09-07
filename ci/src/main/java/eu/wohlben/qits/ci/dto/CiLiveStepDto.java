package eu.wohlben.qits.ci.dto;

import java.time.Instant;

/**
 * The step a run is executing <em>right now</em>, and what it has printed so far — the in-memory
 * relay, not the database.
 *
 * <p>It exists so that a mid-run poll is legible instead of looking like a run with missing steps:
 * step rows are written only at each step's end, so between them the run has fewer step rows than
 * its config declares and this is what says which one is producing the gap.
 *
 * <p>Non-null only on the single-run endpoint, and only while the run is {@code RUNNING}. It is
 * memory and dies with the process — the persisted tail on the step row is the record, this is the
 * live convenience. Polling is the whole read path: there is no SSE and no WebSocket for it.
 *
 * <p><b>{@code startedAt} is when the host handed this step to its container's daemon</b> — the same
 * instant, taken the same way, that this step's row will carry when it ends. It is here so that a
 * client can say how far along the step is rather than only that it is running: with the run's
 * {@code expectedStepDurationsMillis} beside it, "started 40s ago, expected 90s" is a segment that
 * can be drawn.
 *
 * <p>It is <b>null while the step is being set up</b> and that is a real state rather than a gap: a
 * step is live from the moment its buffer opens, which is before its container has been asked for,
 * started, and dialled back. Host-stamped, never daemon-reported, for the reason every timestamp in
 * this service is — a container turns hostile the moment step code runs in it, and a clock is the
 * cheapest thing to forge.
 */
public record CiLiveStepDto(int stepIndex, Instant startedAt, String output) {}
