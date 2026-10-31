package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The name and the components a held CI slot's approval listener is written against</b> (epic
 * qits-732) — {@link RepositoryRenamedContractTest}'s mechanism for {@link
 * ApprovalRequirementChanged}, a hand-kept copy of {@code
 * components/qits-projects/qits-projects-service/service/src/main/java/eu/wohlben/qits/projects/bus/ApprovalRequirementChanged.java}
 * run through the real {@link CanonicalJson}. A change to that record over there is a change to this
 * one in the same campaign.
 */
public class ApprovalRequirementChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final Instant CHANGED_AT = Instant.parse("2026-10-31T09:20:00Z");

  static final String REQUEST_ID = "6c1f9a52-0000-4000-8000-000000000042";

  /** One requirement change, as qits-projects publishes it. */
  static ApprovalRequirementChanged changed(
      String repoId, String requestId, String mergedSha, String state) {
    return new ApprovalRequirementChanged(
        UUID.randomUUID(),
        requestId,
        "qits-ci-service-rr-42",
        "qits",
        repoId,
        "qits-ci-service",
        mergedSha,
        "req-1",
        ".config/qits/ changed",
        List.of("qits:admin"),
        true,
        List.of("someone"),
        state,
        CHANGED_AT);
  }

  /** The canonical payload of one requirement change — the bytes a frame carries. */
  static String canonicalPayload(String repoId, String requestId, String mergedSha, String state) {
    return CanonicalJson.payload(changed(repoId, requestId, mergedSha, state));
  }

  @Test
  public void theEventNameTheListenerSubscribesToIsTheOneTheEventRidesUnder() {
    assertEquals("ApprovalRequirementChanged", ApprovalRequirementChanged.class.getSimpleName());
    assertEquals(
        Set.of("ApprovalRequirementChanged"), new ApprovalRequirementChangedListener().signatures());
  }

  @Test
  public void thePayloadCarriesExactlyThePublishersComponents() throws Exception {
    JsonNode payload =
        MAPPER.readTree(canonicalPayload("r-1", REQUEST_ID, "b".repeat(40), "OPEN"));

    Set<String> keys = new LinkedHashSet<>();
    payload.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of(
            "releaseRequestId",
            "releaseRequestQualifiedId",
            "projectId",
            "repoId",
            "repoName",
            "mergedSha",
            "requirementId",
            "reason",
            "admits",
            "excludesAuthors",
            "authors",
            "state",
            "changedAt"),
        keys,
        "the transcription and the publisher's record no longer have the same components");
    assertEquals("b".repeat(40), payload.get("mergedSha").asText());
    assertEquals("OPEN", payload.get("state").asText());
    assertFalse(payload.has("eventId"));
    assertFalse(payload.has("occurredAt"));
  }

  @Test
  public void aPayloadBindsBackToTheRecordTheListenerReads() {
    ApprovalRequirementChanged out = changed("r-1", REQUEST_ID, "c".repeat(40), "OPEN");

    ApprovalRequirementChanged back =
        CanonicalJson.payloadTo(CanonicalJson.payload(out), ApprovalRequirementChanged.class);

    assertEquals(out.repoId(), back.repoId());
    assertEquals(out.releaseRequestId(), back.releaseRequestId());
    assertEquals(out.mergedSha(), back.mergedSha());
    assertEquals(out.state(), back.state());
    assertEquals(out.admits(), back.admits());
    assertEquals(CHANGED_AT, back.occurredAt(), "occurredAt is changedAt on this event");
  }

  @Test
  public void theListenerSelectsOpenAndNothingElse() {
    ApprovalRequirementChangedListener listener = new ApprovalRequirementChangedListener();
    assertTrue(listener.selects(frame(canonicalPayload("r-1", REQUEST_ID, "b".repeat(40), "OPEN"))));
    for (String state : List.of("SATISFIED", "DECLINED", "VOID")) {
      assertFalse(
          listener.selects(frame(canonicalPayload("r-1", REQUEST_ID, "b".repeat(40), state))),
          state);
    }
  }

  private static EventFrame frame(String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "ApprovalRequirementChanged",
        CHANGED_AT,
        payload,
        null,
        null,
        null);
  }
}
