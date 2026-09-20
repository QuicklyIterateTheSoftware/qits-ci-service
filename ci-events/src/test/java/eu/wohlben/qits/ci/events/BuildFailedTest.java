package eu.wohlben.qits.ci.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The failure twin of {@link BuildSuccessfulTest}, and the same kind of contract: these assertions
 * are what a subscriber keeping per-commit build status binds against, so a change here that is not
 * also a change there is a cross-repo break rather than a refactor.
 */
class BuildFailedTest {

  private static final Instant FINISHED = Instant.parse("2026-07-31T12:46:03Z");

  private static BuildFailed anEvent() {
    return new BuildFailed(
        "run-1", null, "repo-uuid", "qits", "qits-ci", "main", "0123456789abcdef", null, null, "FAILED",
        FINISHED);
  }

  @Test
  void theSignatureIsTheClassNameAndTheNameFollowsIt() {
    BuildFailed event = anEvent();

    assertEquals("BuildFailed", event.signature());
    assertEquals("BuildFailed", event.name());
  }

  @Test
  void occurredAtIsWhenTheBuildFinishedRatherThanWhenItWasAnnounced() {
    assertEquals(FINISHED, anEvent().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    BuildFailed event = anEvent();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    assertNotEquals(first, anEvent().eventId());
  }

  @Test
  void theEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "environment", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("BuildFailed", json.get("name").asText());
    assertEquals("2026-07-31T12:46:03Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"FAILED\","
            + "\"projectId\":\"qits\",\"repoId\":\"repo-uuid\",\"repoName\":\"qits-ci\","
            + "\"runId\":\"run-1\"}",
        json.get("payload").asText());
    assertEquals(true, json.get("description").isNull(), "description is an explicit null");
    assertEquals(true, json.get("parentId").isNull(), "a build nothing caused is a chain root");
  }

  @Test
  void theIdentityTravelsInTheEnvelopeAndNeverInThePayload() {
    BuildFailed event = anEvent();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("occurredAt"), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void anIdAddressedPushOmitsTheNamePairRatherThanNullingIt() {
    BuildFailed idOnly =
        new BuildFailed(
            "run-3", null, "qits-ci", null, null, "main", "0123456789abcdef", null, null, "TIMED_OUT",
            FINISHED);

    String payload = CanonicalJson.payload(idOnly);

    assertFalse(payload.contains("projectId"), payload);
    assertFalse(payload.contains("repoName"), payload);
    assertFalse(payload.contains("null"), payload);
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"TIMED_OUT\","
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-3\"}",
        payload);
  }

  @Test
  void theCanonicalPayloadCarriesNoGatingKeyAtAll() {
    // There was a `gating` component riding a null-means-gating convention, and this case asserted
    // both arms of it. The concept is gone (ticket 9441bc6e) — every step of a pipeline gates, so a
    // red run is a red verdict — and what is pinned now is the absence: no spelling of the key
    // reaches the wire, on a fully-populated event as much as on a bare one.
    assertFalse(CanonicalJson.payload(anEvent()).contains("gating"));

    BuildFailed full =
        new BuildFailed(
            "run-4", null, "repo-uuid", "qits", "qits-ci", "main", "0123456789abcdef", null, null,
            "FAILED", FINISHED);
    String payload = CanonicalJson.payload(full);
    assertFalse(payload.contains("gating"), payload);
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"FAILED\","
            + "\"projectId\":\"qits\",\"repoId\":\"repo-uuid\",\"repoName\":\"qits-ci\","
            + "\"runId\":\"run-4\"}",
        payload);
  }

  @Test
  void aRunThatIsNoPartOfAReleaseOmitsThePairAndAReleaseRunWritesBoth() {
    // BuildSuccessful's rule, unchanged: a null is omitted, so an ordinary red build's payload is
    // what it always was, and a release phase's run carries the word and the release request id
    // beside it. The publish half is the one that proves the id had to be a field: this run's branch
    // is the version, so there is no release/<id> in it for a consumer to parse a correlation out of.
    assertFalse(CanonicalJson.payload(anEvent()).contains("phase"));
    assertFalse(CanonicalJson.payload(anEvent()).contains("releaseRequestId"));

    BuildFailed publish =
        new BuildFailed(
            "run-5", null, "qits-ci", null, null, "2026.916.101112", "0123456789abcdef", "RELEASE", "rr-1",
            "FAILED", FINISHED);
    assertEquals(
        "{\"branch\":\"2026.916.101112\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"FAILED\","
            + "\"phase\":\"RELEASE\",\"releaseRequestId\":\"rr-1\","
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-5\"}",
        CanonicalJson.payload(publish));
  }

  @Test
  void thePhaseAndTheReleaseRequestAreNullTogetherOrSetTogether() {
    // The invariant the three events state and a consumer may rely on: qits-ci derives the phase
    // from the release request id it has already read, so neither is ever carried without the other.
    String ordinary = CanonicalJson.payload(anEvent());
    assertFalse(ordinary.contains("phase"), ordinary);
    assertFalse(ordinary.contains("releaseRequestId"), ordinary);

    String qa =
        CanonicalJson.payload(
            new BuildFailed(
                "run-6", null, "qits-ci", null, null, "release/rr-1", "0123456789abcdef",
                "RELEASE_REQUEST", "rr-1", "FAILED", FINISHED));
    assertTrue(qa.contains("\"phase\":\"RELEASE_REQUEST\""), qa);
    assertTrue(qa.contains("\"releaseRequestId\":\"rr-1\""), qa);
  }

  @Test
  void aRunThatIsNoPartOfAReleaseIsByteIdenticalToWhatShippedBeforeEitherComponentExisted() {
    // An ordinary id-addressed red build carries exactly the keys it carried before phase and
    // releaseRequestId were components, because a null is omitted rather than written.
    BuildFailed ordinary =
        new BuildFailed(
            "run-7", null, "qits-ci", null, null, "main", "0123456789abcdef", null, null, "FAILED",
            FINISHED);

    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"FAILED\","
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-7\"}",
        CanonicalJson.payload(ordinary));
  }

  @Test
  void anOrdinaryRunOmitsTheRetryLineageAndARetryNamesTheRunItReFires() {
    // {@link BuildSuccessfulTest}'s case, and the one that matters most on this event: a red run is
    // what somebody retries, so this is the verdict a consumer has to be able to supersede. Null is
    // the ordinary value and is omitted rather than written.
    assertFalse(CanonicalJson.payload(anEvent()).contains("retryOfRunId"));

    BuildFailed retry =
        new BuildFailed(
            "run-8", "run-7", "qits-ci", null, null, "release/rr-1", "0123456789abcdef",
            "RELEASE_REQUEST", "rr-1", "FAILED", FINISHED);
    assertEquals(
        "{\"branch\":\"release/rr-1\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"FAILED\","
            + "\"phase\":\"RELEASE_REQUEST\",\"releaseRequestId\":\"rr-1\","
            + "\"repoId\":\"qits-ci\",\"retryOfRunId\":\"run-7\",\"runId\":\"run-8\"}",
        CanonicalJson.payload(retry));
  }

  @Test
  void aSubscriberReadsThePayloadBackIntoTheEvent() {
    BuildFailed published = anEvent();

    BuildFailed received =
        CanonicalJson.payloadTo(CanonicalJson.payload(published), BuildFailed.class);

    assertEquals(published.runId(), received.runId());
    assertEquals(published.retryOfRunId(), received.retryOfRunId());
    assertEquals(published.repoId(), received.repoId());
    assertEquals(published.projectId(), received.projectId());
    assertEquals(published.repoName(), received.repoName());
    assertEquals(published.branch(), received.branch());
    assertEquals(published.commitSha(), received.commitSha());
    assertEquals(published.outcome(), received.outcome());
    assertEquals(published.finishedAt(), received.finishedAt());
  }
}
