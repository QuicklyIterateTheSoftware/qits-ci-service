package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * qits-system's {@code TargetLeaseChanged} (epic qits-1481), transcribed rather than depended on —
 * the wire contract, not a type, crosses from another context, and qits-system publishes no
 * vocabulary jar. The record is spelled exactly as qits-system's ({@code
 * components/qits-system/qits-system-service/targets/src/main/java/eu/wohlben/qits/system/targets/events/TargetLeaseChanged.java}):
 * its simple name is the event's name on the bus, and its components are the payload's keys. A
 * change there is a change here, in the same campaign; {@code TargetLeaseChangedContractTest} holds
 * the component list, and qits-workspaces carries the same transcription.
 *
 * <p>Published on every state change of a lease. {@code targetId} is null while QUEUED, {@code
 * exitCode} is set for EXITED, {@code detail} names why a lease waits or failed. Only the leases whose
 * owner is {@code qits-ci} are this service's ({@link TargetLeaseEvents}).
 */
public record TargetLeaseChanged(
    UUID eventId,
    String leaseId,
    String ownerApp,
    String ownerRef,
    String slotType,
    String targetId,
    String state,
    Integer exitCode,
    String detail,
    Instant occurredAt)
    implements QitsEvent {}
