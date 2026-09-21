package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.githost.StubGitHost;
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
 * The publish seam of a retry's lineage: {@link BuildAnnouncer} itself, driven at both terminal
 * methods, asserted against the bytes that reach the bus.
 *
 * <p><b>It exists because the lineage was pinned everywhere except where it is constructed.</b>
 * {@code retryOfRunId} travels {@code ci_run.retry_of_run_id} → {@code
 * CiRunService.announceRun}/{@code announceFailedRun} → {@link
 * eu.wohlben.qits.ci.control.RunAnnouncer} → this class → the published event, and the suites stood
 * at either end of that last hop: {@code BuildSuccessfulTest}/{@code BuildFailedTest} in {@code
 * ci-events} pin the RECORD (an event constructed with the field serialises it), and {@code
 * CiRunCancelAndRetryTest} in {@code ci} pins the SERVICE — but it announces through {@code
 * FakeRunAnnouncer}, so the real announcer is on neither side. Measured on 2026-09-20 by mutation:
 * passing {@code null} in place of {@code retryOfRunId} at either of the two constructions below
 * left the whole bus suite green, 34 tests for the {@code BuildFailed} arm and 7 for the {@code
 * BuildSuccessful} one. A refactor could therefore have dropped the lineage at the publish seam with
 * no red build anywhere, and qits-projects — which reads one verdict per runId, any-red-wins — would
 * have gone on holding a retried run's original red forever, which is the loop {@code qits ci retry}
 * exists to close.
 *
 * <p><b>What is asserted is the published payload and never the arguments handed in</b>, which is
 * the whole point of standing here rather than at the seam: a test that read back what it passed
 * would pass against an announcer that published nothing at all. So each case drives the announcer,
 * waits for its own PUT on {@link StubEventsServer} and reads {@code retryOfRunId} out of the
 * canonical payload.
 *
 * <p><b>The negative case is half the pin, not politeness.</b> {@code CanonicalJson}'s NON_NULL
 * inclusion leaves an absent component's key out entirely, so an ordinary run's payload must have no
 * {@code retryOfRunId} key at all — and without that case an assertion that the key is present could
 * be satisfied by a constant, or pass vacuously against a field nothing varies.
 *
 * <p>It goes through the announcer directly rather than through a run, because the seam under test is
 * one hop wide and a driven run would prove it only for whichever path drove it. {@code
 * BuildSuccessfulPublishTest} is where a real green run's whole output on the bus is asserted; this
 * class shares that class's profile and both of its test resources deliberately, so it costs no
 * second Quarkus start.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
@TestProfile(BuildSuccessfulPublishTest.EventstreamOn.class)
@WithTestResource(StubEventsServer.class)
public class BuildAnnouncerRetryLineageTest {

  /** The real announcer, by its own type: the class under test is the one that builds the event. */
  @Inject BuildAnnouncer announcer;

  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void forgetWhatArrived() {
    StubEventsServer.reset();
  }

  @Test
  public void aReFiredRunsPublishedBuildSuccessfulNamesTheRunItReFires() throws Exception {
    String runId = UUID.randomUUID().toString();
    String reFired = UUID.randomUUID().toString();

    announceSucceeded(runId, reFired);

    JsonNode payload = awaitPayloadOf(runId, "BuildSuccessful");
    assertTrue(payload.has("retryOfRunId"), payload.toString());
    assertEquals(reFired, payload.get("retryOfRunId").asText(), payload.toString());
  }

  @Test
  public void aReFiredRunsPublishedBuildFailedNamesTheRunItReFires() throws Exception {
    String runId = UUID.randomUUID().toString();
    String reFired = UUID.randomUUID().toString();

    announceFailed(runId, reFired);

    JsonNode payload = awaitPayloadOf(runId, "BuildFailed");
    assertTrue(payload.has("retryOfRunId"), payload.toString());
    assertEquals(reFired, payload.get("retryOfRunId").asText(), payload.toString());
  }

  @Test
  public void anOrdinaryRunsPublishedVerdictsNameNoRetryAtAll() throws Exception {
    String green = UUID.randomUUID().toString();
    String red = UUID.randomUUID().toString();

    announceSucceeded(green, null);
    announceFailed(red, null);

    // Absent rather than an explicit null: NON_NULL leaves the key out, so an ordinary build's
    // bytes are what they were before the component existed.
    assertFalse(
        awaitPayloadOf(green, "BuildSuccessful").has("retryOfRunId"),
        "an ordinary green run announces no lineage");
    assertFalse(
        awaitPayloadOf(red, "BuildFailed").has("retryOfRunId"),
        "an ordinary red run announces no lineage");
  }

  // --- the two calls, with everything that is not the subject held constant ---

  private void announceSucceeded(String runId, String retryOfRunId) {
    announcer.onRunSucceeded(
        runId,
        retryOfRunId,
        "repo-" + runId,
        null,
        null,
        "main",
        "0123456789abcdef0123456789abcdef01234567",
        null,
        null,
        Instant.parse("2026-09-20T09:00:00Z"),
        null);
  }

  private void announceFailed(String runId, String retryOfRunId) {
    announcer.onRunFailed(
        runId,
        retryOfRunId,
        "repo-" + runId,
        null,
        null,
        "main",
        "0123456789abcdef0123456789abcdef01234567",
        null,
        null,
        "FAILED",
        Instant.parse("2026-09-20T09:00:00Z"),
        null);
  }

  // --- waiting ---

  /**
   * The canonical payload of the one PUT carrying this name and this run's id.
   *
   * <p>Keyed on the run rather than on the name alone because the suite shares one application: a
   * run another class left in flight publishes onto the same stub, and a test that took "the first
   * {@code BuildSuccessful}" would assert against somebody else's build. Exactly one match is
   * demanded for the same reason.
   */
  private JsonNode awaitPayloadOf(String runId, String name) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    List<JsonNode> matching = new ArrayList<>();
    while (System.currentTimeMillis() < deadline) {
      matching = payloadsOf(runId, name);
      if (!matching.isEmpty()) {
        break;
      }
      Thread.sleep(50);
    }
    if (matching.isEmpty()) {
      return fail("no " + name + " reached the bus for run " + runId + " within the deadline");
    }
    assertEquals(1, matching.size(), "one announcement is one event");
    return matching.get(0);
  }

  private List<JsonNode> payloadsOf(String runId, String name) throws Exception {
    List<JsonNode> matching = new ArrayList<>();
    for (StubEventsServer.Put put : StubEventsServer.puts()) {
      JsonNode envelope = json.readTree(put.body());
      if (!name.equals(envelope.get("name").asText())) {
        continue;
      }
      JsonNode payload = json.readTree(envelope.get("payload").asText());
      if (payload.has("runId") && runId.equals(payload.get("runId").asText())) {
        matching.add(payload);
      }
    }
    return matching;
  }
}
