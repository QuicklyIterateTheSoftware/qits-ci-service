package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Git scope a run's commission states, through the real CDI wiring.
 *
 * <p>{@link RunGitRefsTest} proves the pure mapping, and {@link StubIdp} wires the beans by hand.
 * Neither can see what production sees: {@link IdpCommissioner} is {@code @ApplicationScoped}, so
 * {@link RunCommissions} holds a client proxy of it, and a field read through that proxy is null.
 * On 2026-09-13 that cost every {@code MaintenanceBump} run its branch: {@code RunCommissions} read
 * {@code idp.objectMapper}, the payload could not be parsed, and the run stated {@code gitRefs []}.
 *
 * <p>So this test reaches {@code RunCommissions} by injection, installs a recording commissioner
 * behind the proxy, and feeds it the payload exactly as qits-platform-maintenance's {@code CiClient}
 * builds it and qits-ci's manual trigger stores it.
 */
@QuarkusTest
public class RunCommissionsWiringTest {

  @Inject RunCommissions commissions;

  @Inject ObjectMapper json;

  /** The {@code gitRefs} of every commission, in order. Null is "states nothing". */
  private final List<List<String>> stated = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void recordCommissions() {
    stated.clear();
    // Anonymous, so the test index does not find a second IdpCommissioner bean.
    IdpCommissioner recording =
        new IdpCommissioner() {
          @Override
          public boolean enabled() {
            return true;
          }

          @Override
          public Commission commission(String contextKind, String contextId, List<String> gitRefs) {
            stated.add(gitRefs);
            return new Commission("run-client-" + contextId, "run-s3cr3t");
          }

          @Override
          public void decommission(String commissionedClientId) {}
        };
    QuarkusMock.installMockForType(recording, IdpCommissioner.class);
  }

  /**
   * The payload of qits-platform-maintenance's {@code CiClient.trigger}: five top-level fields, and
   * {@code changes} as its {@code Change} record serializes. qits-ci's manual trigger stores it as
   * {@code objectMapper.writeValueAsString(payload)}, which is what reaches {@code
   * $QITS_EVENT_PAYLOAD}.
   */
  private String bumpPayload(String group, String branch) throws Exception {
    ObjectNode payload = json.createObjectNode();
    payload.put("repository", "qits-ci-service");
    payload.put("group", group);
    payload.put("branch", branch);
    payload.put("baseRef", "main");
    ObjectNode change = payload.putArray("changes").addObject();
    change.put("ecosystem", "maven");
    change.put("manifestPath", "service/pom.xml");
    change.put("name", "io.quarkus.platform:quarkus-bom");
    change.put("from", "3.30.1");
    change.put("to", "3.30.2");
    change.put("location", "property:quarkus.platform.version");
    return json.writeValueAsString(payload);
  }

  /** The run-scoped variables {@code CiRunService.eventEnv} gives every step of an event run. */
  private static Map<String, String> eventEnv(String eventName, String payload) {
    Map<String, String> env = new TreeMap<>();
    env.put("QITS_EVENT_ID", "0b5f3c1e-0000-4000-8000-00000000b0b1");
    env.put("QITS_EVENT_NAME", eventName);
    env.put("QITS_EVENT_OCCURRED_AT", "2026-09-13T00:03:00Z");
    env.put("QITS_EVENT_PAYLOAD", payload);
    env.put("QITS_VERSION", "");
    return env;
  }

  private List<String> scopeOf(String runId, Map<String, String> env) {
    try {
      commissions.forRun(runId, env);
    } finally {
      commissions.release(runId);
    }
    assertEquals(1, stated.size(), "one commission per run");
    return stated.get(0);
  }

  @Test
  public void aGroupBumpRunMayPushItsMaintenanceBranch() throws Exception {
    List<String> scope =
        scopeOf(
            "wiring-group-bump",
            eventEnv("MaintenanceBump", bumpPayload("dependencies", "maintenance/dependencies")));

    assertEquals(List.of("refs/heads/maintenance/dependencies"), scope);
  }

  @Test
  public void aTargetedBumpRunMayPushTheCallersBranch() throws Exception {
    List<String> scope =
        scopeOf(
            "wiring-targeted-bump",
            eventEnv("MaintenanceBump", bumpPayload("targeted", "ticket/t-4711")));

    assertEquals(List.of("refs/heads/ticket/t-4711"), scope);
  }

  @Test
  public void aReleaseRequestRunStillMayPushNothing() {
    String payload =
        "{\"backingBranch\":\"release/4711\",\"mergedSha\":\"0123456789abcdef0123456789abcdef01234567\","
            + "\"releaseRequestId\":\"4711\"}";

    assertEquals(
        List.of(), scopeOf("wiring-release-request", eventEnv("ReleaseRequestChanged", payload)));
  }

  @Test
  public void aRunOfAnUncheckedKindStillStatesNothing() {
    List<String> scope =
        scopeOf("wiring-unchecked", eventEnv("SCMPublishTag", "{\"tagName\":\"2026.913.1\"}"));

    assertEquals(null, scope);
  }
}
