package eu.wohlben.qits.ci.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The eight runner lifecycle events, on the wire. Plain JUnit for {@link BuildSuccessfulTest}'s
 * reason — an event class is data, and the serializer it is asserted against builds its own mapper
 * precisely so no container is needed to know what it emits.
 *
 * <p><b>The canonical payload of each event is pinned byte for byte</b>, once with every field set
 * and, where a field is nullable, once without it — because "absent rather than null" is the
 * convention every nullable field on this bus follows, and a subscriber reads the key's absence, not
 * a null. These strings are the contract a runner-lifecycle subscriber is written against, so a
 * change here that is not also a change there is a cross-repo break rather than a refactor.
 *
 * <p>The family names its timestamp {@code occurredAt}, {@link BuildStatusChanged}'s shape: {@code
 * CanonicalJson} excludes everything {@link QitsEvent} declares, so the instant rides the envelope
 * alone and appears in no payload below. That is asserted once for all eight rather than left to be
 * read off the absence.
 */
class RunnerLifecycleEventsTest {

  private static final Instant AT = Instant.parse("2026-09-28T10:49:52Z");

  private static final String ID = "3f2b8a4e-5c1d-4e7f-9a6b-0c8d7e6f5a4b";

  private static List<QitsEvent> everyEvent() {
    return List.of(
        new RunnerCreated(ID, "build-host-1", 2, "EDGE", "the attic box", AT),
        new RunnerRegistered(ID, "build-host-1", "ci-runner-7", true, "amd64", "linux", AT),
        new RunnerConnected(
            ID, "build-host-1", "2026.927.1", "2026.928.1", true, true, "amd64", "linux", AT),
        new RunnerDisconnected(ID, "build-host-1", "2026.928.1", RunnerDisconnected.LOST, 2, AT),
        new RunnerUpdateStarted(ID, "build-host-1", "2026.927.1", "2026.928.1", 0, AT),
        new RunnerUpdated(ID, "build-host-1", "2026.927.1", "2026.928.1", AT),
        new RunnerChanged(
            ID, "build-host-1", 0, "INTERNAL", null,
            List.of(RunnerChanged.SLOTS, RunnerChanged.PLANE), AT),
        new RunnerDeleted(ID, "build-host-1", AT));
  }

  @Test
  void theSignatureIsTheClassNameAndEveryOneIsARunnerNoun() {
    assertEquals(
        List.of(
            "RunnerCreated",
            "RunnerRegistered",
            "RunnerConnected",
            "RunnerDisconnected",
            "RunnerUpdateStarted",
            "RunnerUpdated",
            "RunnerChanged",
            "RunnerDeleted"),
        everyEvent().stream().map(QitsEvent::name).toList());
  }

  @Test
  void eachGetsItsOwnV4IdAndItsInstantRidesTheEnvelopeAlone() {
    for (QitsEvent event : everyEvent()) {
      assertEquals(4, event.eventId().version(), event.name());
      assertEquals(AT, event.occurredAt(), event.name());

      JsonNode envelope = CanonicalJson.parse(CanonicalJson.envelope(EventEnvelope.of(event)));
      assertEquals(
          List.of("description", "environment", "name", "occurredAt", "parentId", "payload"),
          envelope.properties().stream().map(Map.Entry::getKey).toList());
      assertEquals("2026-09-28T10:49:52Z", envelope.get("occurredAt").asText(), event.name());

      String payload = CanonicalJson.payload(event);
      assertFalse(payload.contains("eventId"), payload);
      assertFalse(payload.contains(event.eventId().toString()), payload);
      assertFalse(payload.contains("occurredAt"), payload);
      assertFalse(payload.contains("2026-09-28"), payload);
      assertFalse(payload.contains("null"), payload);
    }
    // Two announcements of the same facts are two occurrences and must not collide.
    assertNotEquals(everyEvent().get(0).eventId(), everyEvent().get(0).eventId());
  }

  @Test
  void runnerCreated() {
    assertEquals(
        "{\"description\":\"the attic box\",\"plane\":\"EDGE\",\"runnerId\":\"" + ID + "\","
            + "\"runnerName\":\"build-host-1\",\"slots\":2}",
        CanonicalJson.payload(new RunnerCreated(ID, "build-host-1", 2, "EDGE", "the attic box", AT)));
    // No description is no key.
    assertEquals(
        "{\"plane\":\"INTERNAL\",\"runnerId\":\"" + ID + "\",\"runnerName\":\"build-host-1\","
            + "\"slots\":1}",
        CanonicalJson.payload(new RunnerCreated(ID, "build-host-1", 1, "INTERNAL", null, AT)));
  }

  @Test
  void runnerRegistered() {
    assertEquals(
        "{\"arch\":\"amd64\",\"clientId\":\"ci-runner-7\",\"docker\":true,\"os\":\"linux\","
            + "\"runnerId\":\"" + ID + "\",\"runnerName\":\"build-host-1\"}",
        CanonicalJson.payload(
            new RunnerRegistered(ID, "build-host-1", "ci-runner-7", true, "amd64", "linux", AT)));
    // A runner that said nothing about its host: the three keys are absent, not null.
    assertEquals(
        "{\"clientId\":\"ci-runner-7\",\"runnerId\":\"" + ID + "\",\"runnerName\":\"build-host-1\"}",
        CanonicalJson.payload(
            new RunnerRegistered(ID, "build-host-1", "ci-runner-7", null, null, null, AT)));
  }

  @Test
  void runnerConnected() {
    assertEquals(
        "{\"arch\":\"amd64\",\"docker\":true,\"os\":\"linux\",\"runnerId\":\"" + ID + "\","
            + "\"runnerName\":\"build-host-1\",\"runnerVersion\":\"2026.927.1\","
            + "\"targetVersion\":\"2026.928.1\",\"upgradeRequired\":true}",
        CanonicalJson.payload(
            new RunnerConnected(
                ID, "build-host-1", "2026.927.1", "2026.928.1", true, true, "amd64", "linux",
                AT)));
    // A runner of another capability version: its capabilities are not read, and not carried. The
    // boolean is a primitive, so a current runner says false rather than nothing.
    assertEquals(
        "{\"runnerId\":\"" + ID + "\",\"runnerName\":\"build-host-1\","
            + "\"runnerVersion\":\"2026.928.1\",\"targetVersion\":\"2026.928.1\","
            + "\"upgradeRequired\":false}",
        CanonicalJson.payload(
            new RunnerConnected(
                ID, "build-host-1", "2026.928.1", "2026.928.1", false, null, null, null, AT)));
  }

  @Test
  void runnerDisconnected() {
    assertEquals(
        "{\"heldRuns\":2,\"reason\":\"LOST\",\"runnerId\":\"" + ID + "\","
            + "\"runnerName\":\"build-host-1\",\"runnerVersion\":\"2026.928.1\"}",
        CanonicalJson.payload(
            new RunnerDisconnected(
                ID, "build-host-1", "2026.928.1", RunnerDisconnected.LOST, 2, AT)));
    // The vocabulary is five plain words, spelled once here.
    assertEquals(
        List.of("RETIRED", "REPLACED", "LOST", "REFUSED", "SHUTDOWN"),
        List.of(
            RunnerDisconnected.RETIRED,
            RunnerDisconnected.REPLACED,
            RunnerDisconnected.LOST,
            RunnerDisconnected.REFUSED,
            RunnerDisconnected.SHUTDOWN));
  }

  @Test
  void runnerUpdateStarted() {
    assertEquals(
        "{\"fromVersion\":\"2026.927.1\",\"heldRuns\":0,\"runnerId\":\"" + ID + "\","
            + "\"runnerName\":\"build-host-1\",\"toVersion\":\"2026.928.1\"}",
        CanonicalJson.payload(
            new RunnerUpdateStarted(ID, "build-host-1", "2026.927.1", "2026.928.1", 0, AT)));
  }

  @Test
  void runnerUpdated() {
    assertEquals(
        "{\"fromVersion\":\"2026.927.1\",\"runnerId\":\"" + ID + "\","
            + "\"runnerName\":\"build-host-1\",\"toVersion\":\"2026.928.1\"}",
        CanonicalJson.payload(
            new RunnerUpdated(ID, "build-host-1", "2026.927.1", "2026.928.1", AT)));
  }

  @Test
  void runnerChanged() {
    // The whole state now, and which of it moved — a cleared description is named in `changed`
    // and absent as a value.
    assertEquals(
        "{\"changed\":[\"slots\",\"description\"],\"plane\":\"INTERNAL\",\"runnerId\":\"" + ID
            + "\",\"runnerName\":\"build-host-1\",\"slots\":0}",
        CanonicalJson.payload(
            new RunnerChanged(
                ID, "build-host-1", 0, "INTERNAL", null,
                List.of(RunnerChanged.SLOTS, RunnerChanged.DESCRIPTION), AT)));
    assertEquals(
        "{\"changed\":[\"description\"],\"description\":\"moved to the rack\",\"plane\":\"EDGE\","
            + "\"runnerId\":\"" + ID + "\",\"runnerName\":\"build-host-1\",\"slots\":3}",
        CanonicalJson.payload(
            new RunnerChanged(
                ID, "build-host-1", 3, "EDGE", "moved to the rack",
                List.of(RunnerChanged.DESCRIPTION), AT)));
  }

  @Test
  void runnerDeleted() {
    assertEquals(
        "{\"runnerId\":\"" + ID + "\",\"runnerName\":\"build-host-1\"}",
        CanonicalJson.payload(new RunnerDeleted(ID, "build-host-1", AT)));
  }

  @Test
  void aSubscriberReadsEveryPayloadBackIntoItsEvent() {
    for (QitsEvent published : everyEvent()) {
      QitsEvent received =
          CanonicalJson.payloadTo(CanonicalJson.payload(published), published.getClass());
      // The payload carries neither identity nor instant, so what comes back is the facts: the
      // same canonical payload, byte for byte.
      assertEquals(CanonicalJson.payload(published), CanonicalJson.payload(received));
      assertTrue(received.getClass() == published.getClass());
    }
  }
}
