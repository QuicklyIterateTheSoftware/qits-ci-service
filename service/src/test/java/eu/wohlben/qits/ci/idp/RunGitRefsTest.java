package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The Git scope a run's commission states, per trigger kind. Plain JUnit: the class is pure. */
public class RunGitRefsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Optional<List<String>> NOTHING_STATED = Optional.empty();

  private static final Optional<List<String>> MAY_PUSH_NOTHING = Optional.of(List.of());

  private static Optional<List<String>> of(String event, String payload) {
    return RunGitRefs.of(event, payload, JSON);
  }

  /** The payload shape qits-platform-maintenance's {@code CiClient} sends. */
  private static String bump(String group, String branch) {
    return "{\"repository\":\"qits-ci-service\",\"group\":\""
        + group
        + "\",\"branch\":\""
        + branch
        + "\",\"baseRef\":\"main\",\"changes\":[]}";
  }

  @Test
  public void aGroupBumpMayPushItsMaintenanceBranch() {
    assertEquals(
        Optional.of(List.of("refs/heads/maintenance/dependencies")),
        of("MaintenanceBump", bump("dependencies", "maintenance/dependencies")));
  }

  @Test
  public void aTargetedBumpMayPushTheOneSourceBranchItWasAskedToBump() {
    assertEquals(
        Optional.of(List.of("refs/heads/ticket/some-ticket")),
        of("MaintenanceBump", bump("targeted", "ticket/some-ticket")));
  }

  @Test
  public void aBumpWithNoUsableBranchMayPushNothing() {
    // The pipeline refuses each of these before it pushes, so "may push nothing" is exact.
    for (String payload :
        Arrays.asList(
            "{\"group\":\"dependencies\"}",
            "{\"branch\":42}",
            bump("dependencies", ""),
            bump("dependencies", "-f"),
            bump("dependencies", "maintenance/../main"),
            bump("dependencies", "main branch"),
            bump("dependencies", "external/*"),
            bump("dependencies", "a".repeat(245)),
            "not json",
            "",
            null)) {
      assertEquals(MAY_PUSH_NOTHING, of("MaintenanceBump", payload), String.valueOf(payload));
    }
  }

  @Test
  public void theLongestBranchTheIdpAcceptsIsStated() {
    String branch = "a".repeat(255 - "refs/heads/".length());

    assertEquals(
        Optional.of(List.of("refs/heads/" + branch)), of("MaintenanceBump", bump("targeted", branch)));
  }

  @Test
  public void releaseRunsMayPushNothing() {
    // A branch in the payload does not widen them: no recipe on these events pushes.
    String payload = "{\"branch\":\"release/4711\",\"backingBranch\":\"release/4711\"}";

    assertEquals(MAY_PUSH_NOTHING, of("ReleaseRequestChanged", payload));
    assertEquals(MAY_PUSH_NOTHING, of("SCMRelease", payload));
  }

  @Test
  public void aRunKindWhosePushesAreUnknownStatesNothing() {
    assertEquals(NOTHING_STATED, of("SCMPublishTag", "{\"branch\":\"main\"}"));
    assertEquals(NOTHING_STATED, of("BuildSuccessful", "{}"));
    assertEquals(NOTHING_STATED, of("maintenancebump", bump("dependencies", "maintenance/dependencies")));
    assertEquals(NOTHING_STATED, of("", "{}"));
    assertEquals(NOTHING_STATED, of(null, null));
  }

  @Test
  public void theScopeIsReadFromTheRunsEventVariables() {
    Map<String, String> env = new HashMap<>();
    env.put("QITS_EVENT_ID", "0b5f3c1e-0000-4000-8000-000000000001");
    env.put("QITS_EVENT_NAME", "MaintenanceBump");
    env.put("QITS_EVENT_PAYLOAD", bump("dependencies", "maintenance/dependencies"));

    assertEquals(
        Optional.of(List.of("refs/heads/maintenance/dependencies")),
        RunGitRefs.fromRunEnv(env, JSON));
    // A run nothing announced carries no event variables, and states nothing.
    assertEquals(NOTHING_STATED, RunGitRefs.fromRunEnv(Map.of(), JSON));
    assertEquals(NOTHING_STATED, RunGitRefs.fromRunEnv(null, JSON));
  }
}
