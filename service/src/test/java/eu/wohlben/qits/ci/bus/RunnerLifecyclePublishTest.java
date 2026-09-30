package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.githost.StubGitHost;
import eu.wohlben.qits.eventstream.CausationScope;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RunnerLifecycleAnnouncer} against a real HTTP qits-events stand-in: each port call is one
 * PUT of its own event name, in the order the calls were made, with the caller's instant on the
 * envelope and the caller's cause as its parent — although the PUT itself is made on another
 * thread.
 *
 * <p>That last clause is the reason for this class. The announcer hands every event to one
 * publishing thread so no socket handler waits on qits-events, which puts two things at risk that a
 * port-level fake cannot see: the order a self-update is told in, and the causation edge, which is a
 * thread-local and does not follow work across threads by itself. Both are asserted here, on the
 * wire. What triggers each call is {@code CiRunnerEventsTest}'s and {@code
 * CiRunnerSocketEventsTest}'s; the payload bytes are {@code RunnerLifecycleEventsTest}'s.
 *
 * <p>Shares {@link BuildSuccessfulPublishTest}'s profile and both of its test resources
 * deliberately, so it costs no second Quarkus start.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
@TestProfile(BuildSuccessfulPublishTest.EventstreamOn.class)
@WithTestResource(StubEventsServer.class)
public class RunnerLifecyclePublishTest {

  private static final Instant AT = Instant.parse("2026-09-28T10:49:52Z");

  @Inject RunnerLifecycleAnnouncer announcer;

  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void reset() {
    StubEventsServer.reset();
  }

  @Test
  public void everyCallIsOnePutOfItsOwnNameInCallOrder() throws Exception {
    String id = UUID.randomUUID().toString();
    announcer.onRunnerCreated(id, "wire-host", 2, "EDGE", null, null, AT);
    announcer.onRunnerRegistered(id, "wire-host", "ci-runner-9", true, "amd64", "linux", AT);
    announcer.onRunnerConnected(id, "wire-host", "0.0.1", "0.0.2", true, true, "amd64", "linux", AT);
    announcer.onRunnerUpdateStarted(id, "wire-host", "0.0.1", "0.0.2", 0, AT);
    announcer.onRunnerUpdated(id, "wire-host", "0.0.1", "0.0.2", AT);
    announcer.onRunnerDisconnected(id, "wire-host", "0.0.1", "RETIRED", 0, AT);
    announcer.onRunnerChanged(id, "wire-host", 0, "EDGE", null, null, List.of("slots"), AT);
    announcer.onRunnerDeleted(id, "wire-host", AT);

    List<JsonNode> envelopes = awaitEnvelopes(id, 8);

    assertEquals(
        List.of(
            "RunnerCreated",
            "RunnerRegistered",
            "RunnerConnected",
            "RunnerUpdateStarted",
            "RunnerUpdated",
            "RunnerDisconnected",
            "RunnerChanged",
            "RunnerDeleted"),
        envelopes.stream().map(e -> e.get("name").asText()).toList(),
        "one publishing thread: the order the calls were made is the order on the wire");
    for (JsonNode envelope : envelopes) {
      // The caller's instant, not the publishing thread's clock.
      assertEquals("2026-09-28T10:49:52Z", envelope.get("occurredAt").asText());
      assertTrue(envelope.get("parentId").isNull(), "nothing caused these: chain roots");
    }
    assertEquals(
        "{\"changed\":[\"slots\"],\"plane\":\"EDGE\",\"runnerId\":\"" + id + "\","
            + "\"runnerName\":\"wire-host\",\"slots\":0}",
        envelopes.get(6).get("payload").asText());
  }

  @Test
  public void theCallersCauseIsTheParentThoughThePutIsMadeElsewhere() throws Exception {
    String id = UUID.randomUUID().toString();
    UUID cause = UUID.randomUUID();

    CausationScope.with(
        cause, () -> announcer.onRunnerCreated(id, "caused-host", 1, "EDGE", null, null, AT));

    JsonNode envelope = awaitEnvelopes(id, 1).get(0);
    assertEquals(cause.toString(), envelope.get("parentId").asText());
  }

  /** The envelopes PUT for one runner, once {@code expected} of them have arrived. */
  private List<JsonNode> awaitEnvelopes(String runnerId, int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    List<JsonNode> mine = new ArrayList<>();
    while (System.currentTimeMillis() < deadline) {
      mine.clear();
      for (StubEventsServer.Put put : StubEventsServer.puts()) {
        JsonNode envelope = json.readTree(put.body());
        if (envelope.get("payload").asText().contains(runnerId)) {
          mine.add(envelope);
        }
      }
      if (mine.size() >= expected) {
        break;
      }
      Thread.sleep(50);
    }
    assertEquals(expected, mine.size(), "PUTs for runner " + runnerId);
    return mine;
  }
}
