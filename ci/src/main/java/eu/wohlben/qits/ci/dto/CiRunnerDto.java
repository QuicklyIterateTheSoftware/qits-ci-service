package eu.wohlben.qits.ci.dto;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner as an operator reads it. Never a credential: the registration token's value and the
 * commissioned client's secret each leave this service exactly once, in the answer to the request
 * that made them, and nothing here can carry either.
 *
 * <p>{@code registered} is whether the register door has answered the runner. {@code connected} is
 * whether it holds a socket to this process right now — in-memory state, not a column, supplied by
 * {@code CiRunnerPresence}; a restarted qits-ci reads every runner disconnected until it dials back.
 * {@code heldRuns} is how many {@code RUNNING} runs carry this runner, and a runner with any is one
 * that cannot be deleted. {@code lastSeenAt} is host-stamped whenever the runner is heard from, null
 * until it first is. {@code capabilities} is the object the runner registered with, verbatim, and
 * null before registration.
 *
 * <p>{@code runnerVersion}, {@code targetVersion} and {@code updating} are presence too (qits-465):
 * the version the runner's current connection said it is (null while it has not said), the version
 * this deployment pins and so tells every runner to become, and whether a connection told to
 * upgrade is still open — a self-update in flight, or one that is not completing.
 *
 * <p>{@code quarantined}, {@code quarantineReason} and {@code quarantinedAt} say whether the runner is
 * out of service and why (qits-466): a quarantined runner takes no work but its own health check,
 * whatever {@code slots} says — which stays what its operator configured, and is what it gets back.
 * {@code lastHealthcheck} is its newest settled health check, null until it has one.
 *
 * <p>{@code stepMemoryLimit} is the memory cap this runner's step containers get, a docker size such
 * as {@code 6g}; null means the platform's own {@code qits.ci.memory-limit}, which is what every
 * runner that never set one runs its steps under.
 */
public record CiRunnerDto(
    UUID id,
    String name,
    String description,
    int slots,
    CiRunnerPlane plane,
    String stepMemoryLimit,
    JsonNode capabilities,
    boolean registered,
    boolean connected,
    long heldRuns,
    Instant lastSeenAt,
    Instant createdAt,
    String runnerVersion,
    String targetVersion,
    boolean updating,
    boolean quarantined,
    String quarantineReason,
    Instant quarantinedAt,
    CiRunnerHealthcheckDto lastHealthcheck) {}
