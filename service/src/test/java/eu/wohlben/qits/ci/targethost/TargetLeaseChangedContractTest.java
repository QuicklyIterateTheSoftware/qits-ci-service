package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The component list of qits-system's {@code TargetLeaseChanged}, transcribed</b>, checked
 * against the wire form it has to match — {@code RepositoryRenamedContractTest}'s mechanism for a
 * publisher that ships no vocabulary jar either. {@link TargetLeaseEvents} binds the payload to
 * {@link TargetLeaseChanged}, so a component renamed over there and not here binds to null on every
 * arrival, quietly.
 *
 * <p>The source is {@code
 * components/qits-system/qits-system-service/targets/src/main/java/eu/wohlben/qits/system/targets/events/TargetLeaseChanged.java}.
 * Its record order since qits-1818 is {@code eventId, leaseId, ownerApp, ownerRef, slotType,
 * targetId, state, exitCode, detail, oomKills, oomScope, occurredAt}; a change there is a change
 * here, in the same campaign.
 */
class TargetLeaseChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant CHANGED_AT = Instant.parse("2026-11-02T10:15:00Z");

  private static TargetLeaseChanged changed(Long oomKills, String oomScope) {
    return new TargetLeaseChanged(
        UUID.randomUUID(),
        "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d",
        SystemLeases.OWNER,
        "8e1f0a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b",
        SystemLeases.SLOT_TYPE,
        "9f8e7d6c-5b4a-4c3d-8e2f-1a0b9c8d7e6f",
        "RUNNING",
        137,
        "the step's child was killed",
        oomKills,
        oomScope,
        CHANGED_AT);
  }

  /** The record's components, in the publisher's order. */
  @Test
  void theComponentsAreThePublishersInItsOrder() {
    assertEquals("TargetLeaseChanged", TargetLeaseChanged.class.getSimpleName());
    assertEquals(
        List.of(
            "eventId",
            "leaseId",
            "ownerApp",
            "ownerRef",
            "slotType",
            "targetId",
            "state",
            "exitCode",
            "detail",
            "oomKills",
            "oomScope",
            "occurredAt"),
        Arrays.stream(TargetLeaseChanged.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList());
  }

  /** The payload: every component but the envelope's two, exactly. */
  @Test
  void thePayloadCarriesExactlyTheComponentsBesideTheEnvelopes() throws Exception {
    JsonNode payload = MAPPER.readTree(CanonicalJson.payload(changed(2L, "NODE")));

    Set<String> keys = new LinkedHashSet<>();
    payload.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of(
            "leaseId",
            "ownerApp",
            "ownerRef",
            "slotType",
            "targetId",
            "state",
            "exitCode",
            "detail",
            "oomKills",
            "oomScope"),
        keys);
    assertEquals(2, payload.get("oomKills").asLong());
    assertEquals("NODE", payload.get("oomScope").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope");
    assertFalse(payload.has("occurredAt"), "and so does the time");
  }

  /** The bind, both ways — and a payload from a qits-system before qits-1818 binds them null. */
  @Test
  void aPayloadBindsBackAndAnOlderOneReadsTheKillsNull() {
    TargetLeaseChanged out = changed(3L, "LEASE");
    TargetLeaseChanged back =
        CanonicalJson.payloadTo(CanonicalJson.payload(out), TargetLeaseChanged.class);
    assertEquals(3L, back.oomKills());
    assertEquals("LEASE", back.oomScope());
    assertEquals(out.leaseId(), back.leaseId());
    assertEquals(out.exitCode(), back.exitCode());

    TargetLeaseChanged older =
        CanonicalJson.payloadTo(CanonicalJson.payload(changed(null, null)), TargetLeaseChanged.class);
    assertNull(older.oomKills());
    assertNull(older.oomScope());
    assertEquals("RUNNING", older.state());
  }
}
