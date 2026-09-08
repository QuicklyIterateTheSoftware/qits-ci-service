package eu.wohlben.qits.ci.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * qits-ci's run-lifecycle event, on the wire. Plain JUnit for {@link BuildSuccessfulTest}'s reason —
 * an event class is data, and the serializer it is asserted against builds its own mapper precisely
 * so no container is needed to know what it emits.
 *
 * <p>These assertions are the contract a subscriber mirroring {@code GET /ci/api/runs/active} is
 * written against, so a change here that is not also a change there is a cross-repo break rather
 * than a refactor.
 *
 * <p>The one shape worth reading carefully is {@code occurredAt}: unlike {@link BuildSuccessful},
 * whose {@code finishedAt} is an ordinary payload field with {@link
 * eu.wohlben.qits.eventstream.QitsEvent#occurredAt()} overriding on top of it, this record names its
 * timestamp for the contract it satisfies — so {@code CanonicalJson}'s mix-in excludes it from the
 * payload and the instant rides the envelope alone. Both halves of that are asserted below, because
 * the second is exactly the kind of thing a reader would otherwise have to discover from a payload.
 */
class BuildStatusChangedTest {

  private static final Instant STARTED = Instant.parse("2026-09-08T09:15:44Z");

  private static BuildStatusChanged anEvent() {
    return new BuildStatusChanged(
        "run-1", "repo-uuid", "qits", "qits-ci", "main", "0123456789abcdef", null, "RUNNING",
        "QUEUED", STARTED);
  }

  @Test
  void theSignatureIsTheClassNameAndTheNameFollowsIt() {
    BuildStatusChanged event = anEvent();

    assertEquals("BuildStatusChanged", event.signature());
    assertEquals("BuildStatusChanged", event.name());
  }

  @Test
  void occurredAtIsTheRowsOwnTimestampRatherThanWhenItWasAnnounced() {
    assertEquals(STARTED, anEvent().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    BuildStatusChanged event = anEvent();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    // Two transitions announced with the same facts are two occurrences and must not collide.
    assertNotEquals(first, anEvent().eventId());
  }

  @Test
  void theEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "environment", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("BuildStatusChanged", json.get("name").asText());
    // The envelope is where the instant lives, and — see the payload assertion below — the only
    // place it lives.
    assertEquals("2026-09-08T09:15:44Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\",\"previousStatus\":\"QUEUED\","
            + "\"projectId\":\"qits\",\"repoId\":\"repo-uuid\",\"repoName\":\"qits-ci\","
            + "\"runId\":\"run-1\",\"status\":\"RUNNING\"}",
        json.get("payload").asText());
    assertEquals(true, json.get("description").isNull(), "description is an explicit null");
    // The causation lives one level out, where the server compares it — no event class declares a
    // parent, and the publisher hands one in per transition.
    assertEquals(true, json.get("parentId").isNull(), "a transition nothing caused is a chain root");
  }

  @Test
  void theIdentityAndTheTimestampTravelInTheEnvelopeAndNeverInThePayload() {
    BuildStatusChanged event = anEvent();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    // Everything QitsEvent declares is excluded, and here that is the timestamp itself: this record
    // names its instant occurredAt rather than carrying a second column-shaped field beside it.
    assertFalse(payload.contains("occurredAt"), payload);
    assertFalse(payload.contains(STARTED.toString()), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void aRunsFirstAnnouncementOmitsThePreviousStatusRatherThanNullingIt() {
    // Null previous is what says "this run entered the listing". Absent rather than an explicit
    // null, the convention every nullable field on this bus follows.
    BuildStatusChanged accepted =
        new BuildStatusChanged(
            "run-2", "repo-uuid", "qits", "qits-ci", "main", "0123456789abcdef", null, "QUEUED",
            null, STARTED);

    String payload = CanonicalJson.payload(accepted);

    assertFalse(payload.contains("previousStatus"), payload);
    assertFalse(payload.contains("null"), payload);
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\",\"projectId\":\"qits\","
            + "\"repoId\":\"repo-uuid\",\"repoName\":\"qits-ci\",\"runId\":\"run-2\","
            + "\"status\":\"QUEUED\"}",
        payload);
  }

  @Test
  void anIdAddressedRunOmitsTheNamePairRatherThanNullingIt() {
    BuildStatusChanged idOnly =
        new BuildStatusChanged(
            "run-3", "qits-ci", null, null, "main", "0123456789abcdef", null, "CANCELLED", "QUEUED",
            STARTED);

    String payload = CanonicalJson.payload(idOnly);

    assertFalse(payload.contains("projectId"), payload);
    assertFalse(payload.contains("repoName"), payload);
    assertFalse(payload.contains("null"), payload);
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\",\"previousStatus\":\"QUEUED\","
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-3\",\"status\":\"CANCELLED\"}",
        payload);
  }

  @Test
  void aGatingRunOmitsTheFlagAndOnlyANonGatingOneWritesIt() {
    // Null means gating, the convention BuildSuccessful set and this event carries unchanged.
    assertFalse(CanonicalJson.payload(anEvent()).contains("gating"));

    BuildStatusChanged nonGating =
        new BuildStatusChanged(
            "run-4", "qits-ci", null, null, "main", "0123456789abcdef", false, "SUCCESS", "RUNNING",
            STARTED);

    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\",\"gating\":false,"
            + "\"previousStatus\":\"RUNNING\",\"repoId\":\"qits-ci\",\"runId\":\"run-4\","
            + "\"status\":\"SUCCESS\"}",
        CanonicalJson.payload(nonGating));
  }

  @Test
  void aSubscriberReadsThePayloadBackIntoTheEvent() {
    BuildStatusChanged published = anEvent();

    BuildStatusChanged received =
        CanonicalJson.payloadTo(CanonicalJson.payload(published), BuildStatusChanged.class);

    assertEquals(published.runId(), received.runId());
    assertEquals(published.repoId(), received.repoId());
    assertEquals(published.projectId(), received.projectId());
    assertEquals(published.repoName(), received.repoName());
    assertEquals(published.branch(), received.branch());
    assertEquals(published.commitSha(), received.commitSha());
    assertEquals(published.status(), received.status());
    assertEquals(published.previousStatus(), received.previousStatus());
  }
}
