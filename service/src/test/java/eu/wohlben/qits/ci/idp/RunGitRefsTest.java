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
  public void aNonTargetedGroupBumpMayPushNothing() {
    // The GROUP arm is retired (qits-1133): the pipeline refuses any group but "targeted" before
    // it ever reads a branch, so such a run's scope is empty rather than a ref it will never push.
    assertEquals(
        MAY_PUSH_NOTHING, of("MaintenanceBump", bump("dependencies", "maintenance/dependencies")));
  }

  @Test
  public void aRetiredScreenshotBaselinesEventStatesNothing() {
    // ScreenshotBaselines is retired (qits-1007): the platform pipeline that sent it is gone, so
    // it is an unknown event now, answered like any other.
    String payload =
        "{\"repository\":\"qits-landing-app\",\"branch\":\"maintenance/baselines/abc\","
            + "\"baseRef\":\"release/abc\"}";
    assertEquals(NOTHING_STATED, of("ScreenshotBaselines", payload));
  }

  /** The payload shape qits-maintenance sends to run one automation kind. */
  private static String automation(String kind, String branch) {
    return "{\"kind\":\""
        + kind
        + "\",\"repository\":\"qits-landing-app\",\"requestId\":\"abc\","
        + "\"foldSha\":\""
        + "a".repeat(40)
        + "\",\"baseRef\":\"release/abc\",\"branch\":\""
        + branch
        + "\",\"commitPaths\":[\":(glob)**/__screenshots__/**\"]}";
  }

  @Test
  public void anAutomationMayPushOnlyABranchUnderItsOwnKind() {
    assertEquals(
        Optional.of(List.of("refs/heads/maintenance/automations/screenshot-baselines/abc")),
        of(
            "ReleaseRequestAutomation",
            automation("screenshot-baselines", "maintenance/automations/screenshot-baselines/abc")));
    assertEquals(
        Optional.of(List.of("refs/heads/maintenance/automations/entity-diagram/abc")),
        of(
            "ReleaseRequestAutomation",
            automation("entity-diagram", "maintenance/automations/entity-diagram/abc")));
  }

  @Test
  public void anAutomationBranchOutsideItsKindMayPushNothing() {
    for (String payload :
        Arrays.asList(
            // another kind's prefix
            automation("screenshot-baselines", "maintenance/automations/entity-diagram/abc"),
            // the retiring baselines prefix is not an automation's
            automation("screenshot-baselines", "maintenance/baselines/x"),
            automation("screenshot-baselines", "main"),
            automation("screenshot-baselines", "maintenance/automations/screenshot-baselines/../main"),
            automation("screenshot-baselines", "maintenance/automations/screenshot-baselines/"),
            automation("screenshot-baselines", "maintenance/automations/screenshot-baselines"),
            // a kind that is no kind cannot name a prefix
            automation("", "maintenance/automations//abc"),
            automation("Screenshot", "maintenance/automations/Screenshot/abc"),
            automation("screenshot-baselines/abc", "maintenance/automations/screenshot-baselines/abc/x"),
            "{\"branch\":\"maintenance/automations/screenshot-baselines/abc\"}",
            "{\"kind\":42,\"branch\":\"maintenance/automations/42/abc\"}",
            "not json",
            "",
            null)) {
      assertEquals(
          MAY_PUSH_NOTHING, of("ReleaseRequestAutomation", payload), String.valueOf(payload));
    }
  }

  @Test
  public void aTargetedBumpMayPushTheOneSourceBranchItWasAskedToBump() {
    assertEquals(
        Optional.of(List.of("refs/heads/ticket/some-ticket")),
        of("MaintenanceBump", bump("targeted", "ticket/some-ticket")));
  }

  @Test
  public void aBumpWithNoUsableBranchMayPushNothing() {
    // Targeted, so the branch is what is under test here and not the group gate above.
    for (String payload :
        Arrays.asList(
            "{\"group\":\"targeted\"}",
            "{\"branch\":42}",
            bump("targeted", ""),
            bump("targeted", "-f"),
            bump("targeted", "maintenance/../main"),
            bump("targeted", "main branch"),
            bump("targeted", "external/*"),
            bump("targeted", "a".repeat(245)),
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

  /** The payload shape {@code SoftwareReleaseAnnouncer} publishes for a ui-components release. */
  private static final String UI_COMPONENTS_RELEASE =
      "{\"repository\":\"0b5f3c1e-0000-4000-8000-000000000002\",\"projectId\":\"qits\","
          + "\"repoId\":\"0b5f3c1e-0000-4000-8000-000000000002\","
          + "\"repoName\":\"qits-ui-components-jslib\",\"version\":\"2026.912.1\","
          + "\"packageType\":\"npm\",\"packageName\":\"@qits/ui-components\"}";

  @Test
  public void aSoftwareReleaseRunMayPushNothing() {
    // No recipe selects SoftwareRelease since the hop files were deleted, so the repository the
    // payload names does not become a ref.
    assertEquals(MAY_PUSH_NOTHING, of("SoftwareRelease", UI_COMPONENTS_RELEASE));
    // A runner's health check: qits-ci wrote its only recipe, and it echoes.
    assertEquals(MAY_PUSH_NOTHING, of("RunnerHealthCheck", "{\"runnerId\":\"r-1\"}"));
  }

  @Test
  public void aMalformedSoftwareReleaseMayPushNothing() {
    for (String payload :
        Arrays.asList(
            "{\"repository\":\"../main\"}", "{\"repository\":42}", "not json", "", null)) {
      assertEquals(MAY_PUSH_NOTHING, of("SoftwareRelease", payload), String.valueOf(payload));
    }
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
    env.put("QITS_EVENT_PAYLOAD", bump("targeted", "ticket/some-ticket"));

    assertEquals(
        Optional.of(List.of("refs/heads/ticket/some-ticket")), RunGitRefs.fromRunEnv(env, JSON));
    // A run nothing announced carries no event variables, and states nothing.
    assertEquals(NOTHING_STATED, RunGitRefs.fromRunEnv(Map.of(), JSON));
    assertEquals(NOTHING_STATED, RunGitRefs.fromRunEnv(null, JSON));
  }
}
