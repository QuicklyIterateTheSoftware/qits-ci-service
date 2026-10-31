package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.targethost.CiSlotHolds;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The name and the components a held CI slot's gate listener is written against</b> (epic
 * qits-732), checked against the wire form they have to match — {@link
 * RepositoryRenamedContractTest}'s mechanism for {@link ReleaseRequestStateChanged}.
 *
 * <p>{@link ReleaseRequestStateChanged} is a hand-kept copy of the component list of {@code
 * components/qits-projects/qits-projects-service/service/src/main/java/eu/wohlben/qits/projects/bus/ReleaseRequestStateChanged.java}.
 * The bytes below are produced by the real {@link CanonicalJson}, so every rule about the wire form
 * — alphabetical keys, {@code NON_NULL} inclusion, the accessors the mix-in hides — stays the
 * library's. A change to that record over there is a change to this one in the same campaign: a
 * renamed {@code state}, {@code repoId} or {@code releaseRequestId} leaves this suite green only if
 * nobody updates this file, and every held slot would then sit out its window instead of ending at
 * its gate.
 */
public class ReleaseRequestStateChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final Instant CHANGED_AT = Instant.parse("2026-10-31T09:14:02Z");

  static final String REQUEST_ID = "6c1f9a52-0000-4000-8000-000000000042";

  /** One state change, every component stated, as qits-projects publishes it. */
  static ReleaseRequestStateChanged changed(String repoId, String requestId, String state) {
    return new ReleaseRequestStateChanged(
        UUID.randomUUID(),
        requestId,
        "qits-ci-service-rr-42",
        "qits",
        repoId,
        "qits-ci-service",
        "release/" + requestId,
        state,
        "PENDING",
        CHANGED_AT,
        "b".repeat(40),
        "2026.1031.91402",
        "the QA run failed",
        List.of("ticket/qits-1788"));
  }

  /** The canonical payload of one state change — the bytes a frame carries. */
  static String canonicalPayload(String repoId, String requestId, String state) {
    return CanonicalJson.payload(changed(repoId, requestId, state));
  }

  @Test
  public void theEventNameTheListenerSubscribesToIsTheOneTheEventRidesUnder() {
    assertEquals("ReleaseRequestStateChanged", ReleaseRequestStateChanged.class.getSimpleName());
    assertEquals(
        Set.of("ReleaseRequestStateChanged"), new ReleaseRequestStateChangedListener().signatures());
  }

  @Test
  public void thePayloadCarriesExactlyThePublishersComponents() throws Exception {
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", REQUEST_ID, "REJECTED"));

    Set<String> keys = new LinkedHashSet<>();
    payload.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of(
            "releaseRequestId",
            "releaseRequestQualifiedId",
            "projectId",
            "repoId",
            "repoName",
            "backingBranch",
            "state",
            "previousState",
            "changedAt",
            "mergedSha",
            "version",
            "detail",
            "branches"),
        keys,
        "the transcription and the publisher's record no longer have the same components");
    assertEquals("r-1", payload.get("repoId").asText());
    assertEquals(REQUEST_ID, payload.get("releaseRequestId").asText());
    assertEquals("REJECTED", payload.get("state").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope");
    assertFalse(payload.has("occurredAt"), "and so does the time; changedAt is the component");
  }

  @Test
  public void aNullComponentIsAnAbsentKeyAndTheListenerStillReadsTheRest() throws Exception {
    // The publisher omits nulls: a request with no version, detail or branches yet.
    ReleaseRequestStateChanged sparse =
        new ReleaseRequestStateChanged(
            REQUEST_ID, null, "qits", "r-1", null, null, "WITHDRAWN", null, CHANGED_AT, null, null,
            null, null);
    JsonNode payload = MAPPER.readTree(CanonicalJson.payload(sparse));
    assertFalse(payload.has("version"));
    assertFalse(payload.has("branches"));

    ReleaseRequestStateChanged back =
        CanonicalJson.payloadTo(CanonicalJson.payload(sparse), ReleaseRequestStateChanged.class);
    assertEquals("WITHDRAWN", back.state());
    assertEquals("r-1", back.repoId());
  }

  @Test
  public void aPayloadBindsBackToTheRecordTheListenerReads() {
    ReleaseRequestStateChanged out = changed("r-1", REQUEST_ID, "OBSOLETE");

    ReleaseRequestStateChanged back =
        CanonicalJson.payloadTo(CanonicalJson.payload(out), ReleaseRequestStateChanged.class);

    assertEquals(out.repoId(), back.repoId());
    assertEquals(out.releaseRequestId(), back.releaseRequestId());
    assertEquals(out.state(), back.state());
    assertEquals(out.branches(), back.branches());
    assertEquals(CHANGED_AT, back.occurredAt(), "occurredAt is changedAt on this event");
  }

  @Test
  public void aComponentThePublisherAddsLaterDoesNotStopTheBind() {
    String grown =
        "{\"newComponent\":1,\"releaseRequestId\":\"" + REQUEST_ID + "\",\"repoId\":\"r-1\","
            + "\"state\":\"FAILED\"}";
    assertEquals(
        "FAILED", CanonicalJson.payloadTo(grown, ReleaseRequestStateChanged.class).state());
  }

  @Test
  public void theListenerSelectsTheGatesUnmetStatesAndNothingElse() {
    ReleaseRequestStateChangedListener listener = new ReleaseRequestStateChangedListener();
    for (String state : CiSlotHolds.GATES_UNMET) {
      assertTrue(listener.selects(frame(canonicalPayload("r-1", REQUEST_ID, state))), state);
    }
    for (String state : List.of("PENDING", "READY", "RELEASED", "FINALIZED", "APPROVED")) {
      assertFalse(listener.selects(frame(canonicalPayload("r-1", REQUEST_ID, state))), state);
    }
    assertFalse(listener.selects(frame("not json")), "an unreadable payload ends nothing");
  }

  private static EventFrame frame(String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "ReleaseRequestStateChanged",
        CHANGED_AT,
        payload,
        null,
        null,
        null);
  }
}
