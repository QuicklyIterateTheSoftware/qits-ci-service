package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The trigger engine with {@link CiTriggerListingCache} armed (qits-168): an event that moves no ref
 * reads no listing a previous event already read, and a ref-move event for one repository makes the
 * next evaluation re-read that repository and no other.
 *
 * <p>The suite runs with the cache off ({@code qits.ci.trigger-listing-cache.ttl=0S}), because the
 * rest of it reseeds the fake git host between evaluations without announcing a push — which an armed
 * cache would rightly not notice. This class arms it per test and disarms it after, through the
 * package-private hook, which also empties it, so nothing remembered here leaks into another class.
 */
@QuarkusTest
public class CiTriggerListingCacheWiringTest extends CiTestSupport {

  private static final String HEAD = "a".repeat(40);
  private static final String MOVED = "b".repeat(40);

  @Inject CiEventTriggerService engine;
  @Inject CiTriggerListingCache listings;

  private String x;
  private String y;

  @BeforeEach
  void arm() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    x = "pushed-" + suffix;
    y = "untouched-" + suffix;
    fakeCandidates.set(x, y);
    listings.ttl(Duration.ofMinutes(5));
  }

  @AfterEach
  void disarm() {
    listings.ttl(Duration.ZERO);
  }

  private static CiEventTriggerService.Arrival arrival(String name, String payload) {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(), name, Instant.parse("2026-10-11T12:00:00Z"), payload);
  }

  /** What qits-githost announces for a push to {@code main}, in the fields this engine reads. */
  private static String pushPayload(String repoId, String sha) {
    return "{\"branch\":\"main\",\"projectId\":\"qits\",\"repoId\":\""
        + repoId
        + "\",\"repoName\":\"whatever\",\"sha\":\""
        + sha
        + "\"}";
  }

  private long readsOf(String repoId) {
    return fakeConfig.triggerReads().stream().filter(r -> r.startsWith(repoId + "@")).count();
  }

  @Test
  public void anEventThatMovesNoRefReadsNoListingAgain() {
    engine.evaluate(arrival("NobodyListens", "{}"));
    engine.evaluate(arrival("NobodyListens", "{}"));
    engine.evaluate(arrival("NobodyListensEither", "{}"));

    assertEquals(1, readsOf(x), "each candidate is listed once until its refs move");
    assertEquals(1, readsOf(y));
  }

  @Test
  public void aPushToXRereadsXAndNotY() throws Exception {
    fakeConfig.putTriggers(x, "main", HEAD);
    engine.evaluate(arrival("NobodyListens", "{}"));

    fakeConfig.putTriggers(
        x,
        "main",
        MOVED,
        new EventTriggerFile(
            TEST_TRIGGER_PATH, triggerFile("steps:\n  - image: alpine:3\n    script: echo hi\n")));
    // The push's own evaluation goes through the manual door here, which forgets on the caller's
    // thread exactly as the bus door forgets on the listener's — and it must already see the new
    // head, or a trigger declaring `event: SCMPublishCommit` would decide on the pre-push file.
    engine.evaluateNow(arrival("SCMPublishCommit", pushPayload(x, MOVED)));
    assertEquals(2, readsOf(x), "the push to X is re-read by its own evaluation");
    assertEquals(1, readsOf(y), "Y did not move and is not asked");

    engine.evaluate(arrival(TEST_EVENT_NAME, "{}"));
    suiteRunner.awaitIdle();
    assertEquals(2, readsOf(x), "and remembered again after that");
    assertEquals(1, readsOf(y));
    assertTrue(
        runService.runsFor(x).stream().allMatch(run -> MOVED.equals(run.commitSha)),
        "the run that followed the push is recorded at the pushed head");
    assertEquals(1, runService.runsFor(x).size());
  }

  @Test
  public void theBusDoorForgetsAtArrivalBeforeTheEvaluationItQueues() throws Exception {
    engine.evaluate(arrival("NobodyListens", "{}"));

    assertTrue(engine.onEvent(arrival("SCMDeleteBranch", pushPayload(y, MOVED))));
    engine.awaitIdle();

    assertEquals(1, readsOf(x));
    assertEquals(2, readsOf(y), "the queued evaluation of the ref move re-read the repository it named");
  }

  @Test
  public void aRefMoveNamingNoRepositoryForgetsEverything() {
    engine.evaluate(arrival("NobodyListens", "{}"));

    engine.evaluateNow(arrival("SCMPublishTag", "not json {"));

    assertEquals(2, readsOf(x));
    assertEquals(2, readsOf(y));
  }
}
