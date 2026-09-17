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
 * qits-ci's one event, on the wire. Plain JUnit — an event class is data, and the serializer it is
 * asserted against builds its own mapper precisely so no container is needed to know what it emits.
 *
 * <p>These assertions are the contract the qits-events side was written against, so a change here
 * that is not also a change there is a cross-repo break rather than a refactor.
 */
class BuildSuccessfulTest {

  private static final Instant FINISHED = Instant.parse("2026-07-31T12:46:03Z");

  private static BuildSuccessful anEvent() {
    return new BuildSuccessful(
        "run-1", "repo-uuid", "qits", "qits-ci", "main", "0123456789abcdef", "sha256:deadbeef", null,
        null, null, FINISHED);
  }

  @Test
  void theSignatureIsTheClassNameAndTheNameFollowsIt() {
    BuildSuccessful event = anEvent();

    assertEquals("BuildSuccessful", event.signature());
    assertEquals("BuildSuccessful", event.name());
  }

  @Test
  void occurredAtIsWhenTheBuildFinishedRatherThanWhenItWasAnnounced() {
    assertEquals(FINISHED, anEvent().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    BuildSuccessful event = anEvent();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    // Two announcements of the same facts are two occurrences and must not collide on one id.
    assertNotEquals(first, anEvent().eventId());
  }

  @Test
  void theEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "environment", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("BuildSuccessful", json.get("name").asText());
    assertEquals("2026-07-31T12:46:03Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"imageDigest\":\"sha256:deadbeef\","
            + "\"projectId\":\"qits\",\"repoId\":\"repo-uuid\",\"repoName\":\"qits-ci\","
            + "\"runId\":\"run-1\"}",
        json.get("payload").asText());
    assertEquals(true, json.get("description").isNull(), "description is an explicit null");
    // parentId joined the envelope when causation landed. BuildSuccessful itself never declares a
    // parent — no event class does — so the causation lives one level out where the server compares
    // it, and the payload above is the whole of what the event carries.
    assertEquals(true, json.get("parentId").isNull(), "a build nothing caused is a chain root");
  }

  @Test
  void theIdentityTravelsInTheEnvelopeAndNeverInThePayload() {
    BuildSuccessful event = anEvent();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("occurredAt"), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void aPipelineThatPublishedNoImageOmitsTheDigestRatherThanNullingIt() {
    BuildSuccessful noImage =
        new BuildSuccessful(
            "run-2", "qits-ci", "qits", "qits-ci", "main", "0123456789abcdef", null, null, null,
            null, FINISHED);

    String payload = CanonicalJson.payload(noImage);

    assertFalse(payload.contains("imageDigest"), payload);
    assertFalse(payload.contains("null"), payload);
  }

  @Test
  void theNameRidesTheWireWhenTheAnnouncingPushCarriedIt() {
    String payload = CanonicalJson.payload(anEvent());

    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"imageDigest\":\"sha256:deadbeef\","
            + "\"projectId\":\"qits\",\"repoId\":\"repo-uuid\",\"repoName\":\"qits-ci\","
            + "\"runId\":\"run-1\"}",
        payload);
  }

  @Test
  void anIdAddressedPushOmitsTheNamePairRatherThanNullingIt() {
    // No (project, name) pair — an id-addressed push announces neither, and null fields are dropped
    // from the canonical form, so the payload is byte-identical to what shipped before the pair
    // existed.
    BuildSuccessful idOnly =
        new BuildSuccessful(
            "run-3", "qits-ci", null, null, "main", "0123456789abcdef", null, null, null, null,
            FINISHED);

    String payload = CanonicalJson.payload(idOnly);

    assertFalse(payload.contains("projectId"), payload);
    assertFalse(payload.contains("repoName"), payload);
    assertFalse(payload.contains("null"), payload);
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"repoId\":\"qits-ci\",\"runId\":\"run-3\"}",
        payload);
  }

  @Test
  void aGatingRunOmitsTheFlagAndOnlyANonGatingOneWritesIt() {
    // Null means gating: every gating build's payload stays byte-identical to what shipped before
    // the field existed, and a subscriber reads absent as gating.
    assertFalse(CanonicalJson.payload(anEvent()).contains("gating"));

    BuildSuccessful nonGating =
        new BuildSuccessful(
            "run-4", "qits-ci", null, null, "main", "0123456789abcdef", null, false, null, null,
            FINISHED);
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"gating\":false,"
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-4\"}",
        CanonicalJson.payload(nonGating));
  }

  @Test
  void aRunThatIsNoPartOfAReleaseOmitsThePairAndAReleaseRunWritesBoth() {
    // Null is the ordinary value — most runs are no part of a release — so an ordinary green build's
    // payload is byte-identical to what it was before either component existed. A run that IS a
    // phase of a release writes the CiRunPhase word as a plain string, which is what a reader on the
    // other side of the bus tells P1 from P2 by, and the release request id beside it, which is what
    // that reader correlates the two halves of one release by. It does not make this a verdict about
    // the release: the event still says "this commit built green", and what a release is worth is
    // the release request's own business in qits-projects.
    assertFalse(CanonicalJson.payload(anEvent()).contains("phase"));
    assertFalse(CanonicalJson.payload(anEvent()).contains("releaseRequestId"));

    BuildSuccessful qa =
        new BuildSuccessful(
            "run-5", "qits-ci", null, null, "release/rr-1", "0123456789abcdef", null, null,
            "RELEASE_REQUEST", "rr-1", FINISHED);
    assertEquals(
        "{\"branch\":\"release/rr-1\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"phase\":\"RELEASE_REQUEST\","
            + "\"releaseRequestId\":\"rr-1\","
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-5\"}",
        CanonicalJson.payload(qa));
  }

  @Test
  void thePhaseAndTheReleaseRequestAreNullTogetherOrSetTogether() {
    // The invariant a consumer is told it may rely on, and the reason it can key a pipeline read
    // model on the pair without a second lookup: qits-ci derives the phase from the release request
    // id it has already read, returning null whenever that is null, so no run it announces holds one
    // without the other. Stated here on the wire because that is where a consumer meets it.
    String ordinary = CanonicalJson.payload(anEvent());
    assertFalse(ordinary.contains("phase"), ordinary);
    assertFalse(ordinary.contains("releaseRequestId"), ordinary);

    String release =
        CanonicalJson.payload(
            new BuildSuccessful(
                "run-6", "qits-ci", null, null, "2026.916.101112", "0123456789abcdef", null, null,
                "RELEASE", "rr-1", FINISHED));
    assertTrue(release.contains("\"phase\":\"RELEASE\""), release);
    assertTrue(release.contains("\"releaseRequestId\":\"rr-1\""), release);
    // And the publish half is why the id is a field rather than something derived: there is no
    // release/<id> in a P2 run's branch to parse — the branch is the version.
    assertFalse(release.contains("release/"), release);
  }

  @Test
  void aRunThatIsNoPartOfAReleaseIsByteIdenticalToWhatShippedBeforeEitherComponentExisted() {
    // The compatibility claim, spelled as bytes rather than as an absence: an ordinary id-addressed
    // green build's payload is exactly the five keys it carried before phase and releaseRequestId
    // were components at all, because CanonicalJson omits a null rather than writing one.
    BuildSuccessful ordinary =
        new BuildSuccessful(
            "run-7", "qits-ci", null, null, "main", "0123456789abcdef", null, null, null, null,
            FINISHED);

    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"repoId\":\"qits-ci\","
            + "\"runId\":\"run-7\"}",
        CanonicalJson.payload(ordinary));
  }

  @Test
  void aSubscriberReadsThePayloadBackIntoTheEvent() {
    BuildSuccessful published = anEvent();

    BuildSuccessful received =
        CanonicalJson.payloadTo(CanonicalJson.payload(published), BuildSuccessful.class);

    assertEquals(published.runId(), received.runId());
    assertEquals(published.repoId(), received.repoId());
    assertEquals(published.projectId(), received.projectId());
    assertEquals(published.repoName(), received.repoName());
    assertEquals(published.branch(), received.branch());
    assertEquals(published.commitSha(), received.commitSha());
    assertEquals(published.imageDigest(), received.imageDigest());
    assertEquals(published.phase(), received.phase());
    assertEquals(published.releaseRequestId(), received.releaseRequestId());
    assertEquals(published.finishedAt(), received.finishedAt());
  }
}
