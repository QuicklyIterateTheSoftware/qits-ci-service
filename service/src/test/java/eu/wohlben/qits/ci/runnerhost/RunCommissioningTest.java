package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.StubIdp;
import eu.wohlben.qits.ci.runnerhost.StepContainerSettings.LaunchSpec;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-run credential, end to end on this side of the wire: a real {@link StubIdp} on a real
 * socket, the real {@link StepWorkloadSpecs} assembling a real spec from what it minted, and {@link
 * RunCommissions#release} giving it back.
 *
 * <p><b>The credential is one {@code ci-run} TOKEN</b> (qits-475), and since qits-515 it is the only
 * kind: a run never commissions a client, so every case here reads the stub's tokens door and
 * asserts the clients door was left alone.
 *
 * <p><b>Why it is its own class.</b> {@code StepContainerSettingsTest} and {@code
 * StepEnvironmentTest} are spec assembly with no collaborator that sends anything, and they stay
 * that way; what is under test here is the <em>lifecycle</em> — one commission per run rather than
 * per step, the Git scope it states, a second run's own, and a close that deletes.
 *
 * <p><b>Plain JUnit, no Quarkus.</b> The settings take their config in fields, the commissioner
 * takes its own the same way, and {@code StubIdp} hands out a wired pair because those fields are
 * package-private in another package. Nothing here needs an application.
 */
public class RunCommissioningTest {

  private static final String RUN = "0123456789abcdef-run";

  /** Short, so a refusal case costs milliseconds rather than the shipped thirty seconds. */
  private static final Duration PATIENCE = Duration.ofMillis(200);

  private StubIdp idp;

  @BeforeEach
  void startStub() {
    idp = new StubIdp();
  }

  @AfterEach
  void stopStub() {
    idp.close();
  }

  /** Commissions the run's token as {@code RunnerStepRunner} does, then composes the step with it. */
  private WorkloadSpec compose(RunCommissions commissions, LaunchSpec step) {
    StepContainerSettings launcher = StepFixtures.shippedLauncher();
    return StepWorkloadSpecs.compose(
        launcher.workloadSettings(),
        StepFixtures.plane(launcher),
        step,
        commissions.forRun(step.runId(), step.env()),
        null);
  }

  /** One step of a run, publishing or not. */
  private static LaunchSpec step(String runId, int index, boolean docker) {
    return step(runId, index, docker, Map.of());
  }

  /** The four QITS_EVENT_* variables an event-triggered run carries. */
  private static Map<String, String> event(String name, String payload) {
    return Map.of(
        "QITS_EVENT_ID", "0b5f3c1e-0000-4000-8000-000000000001",
        "QITS_EVENT_NAME", name,
        "QITS_EVENT_OCCURRED_AT", "2026-09-12T10:00:00Z",
        "QITS_EVENT_PAYLOAD", payload);
  }

  private static Map<String, String> bump(String group, String branch) {
    return event(
        "MaintenanceBump",
        "{\"repository\":\"repo-1\",\"group\":\""
            + group
            + "\",\"branch\":\""
            + branch
            + "\",\"baseRef\":\"main\",\"changes\":[]}");
  }

  /** One step of a run with its run-scoped environment. */
  private static LaunchSpec step(String runId, int index, boolean docker, Map<String, String> env) {
    return new LaunchSpec(
        runId,
        index,
        CiRepoRef.of("repo-1"),
        "main",
        "cafebabe",
        "maven:3.9",
        "daemon-7",
        "/artifacts/daemons/qits-ci-daemon/deadbeef",
        0,
        docker,
        false,
        "",
        env);
  }

  @Test
  public void aNonTargetedGroupBumpRunMayPushNothing() {
    // The GROUP arm is retired (qits-1133): the pipeline refuses any group but "targeted" before
    // it ever reads a branch, so such a run's scope is empty rather than a ref it will never push.
    compose(idp.runCommissions(PATIENCE), step(RUN, 0, false, bump("dependencies", "maintenance/dependencies")));

    assertEquals(
        List.of("{\"contextKind\":\"ci-run\",\"contextId\":\"" + RUN + "\",\"gitRefs\":[]}"),
        idp.postedTokens);
  }

  @Test
  public void aTargetedBumpRunMayPushTheSourceBranchItWasAskedToBump() {
    compose(idp.runCommissions(PATIENCE), step(RUN, 0, false, bump("targeted", "task/pin-the-frontend")));

    assertEquals(
        List.of(
            "{\"contextKind\":\"ci-run\",\"contextId\":\""
                + RUN
                + "\",\"gitRefs\":[\"refs/heads/task/pin-the-frontend\"]}"),
        idp.postedTokens);
  }

  @Test
  public void aReleaseRequestRunMayPushNothing() {
    compose(
        idp.runCommissions(PATIENCE),
        step(
            RUN,
            0,
            true,
            event(
                "ReleaseRequestChanged",
                "{\"backingBranch\":\"release/4711\",\"mergedSha\":\"cafebabe\"}")));

    assertEquals(
        List.of("{\"contextKind\":\"ci-run\",\"contextId\":\"" + RUN + "\",\"gitRefs\":[]}"),
        idp.postedTokens);
  }

  @Test
  public void aRunKindWhosePushesAreUnknownStatesNoScope() {
    compose(idp.runCommissions(PATIENCE), step(RUN, 0, false, event("SCMPublishTag", "{\"tagName\":\"1.0\"}")));

    assertEquals(
        List.of("{\"contextKind\":\"ci-run\",\"contextId\":\"" + RUN + "\"}"), idp.postedTokens);
  }

  @Test
  public void oneRunCommissionsOnceAndEveryLaterStepReusesIt() {
    RunCommissions commissions = idp.runCommissions(PATIENCE);

    IdpCommissioner.CommissionedToken held = commissions.forRun(RUN, Map.of());
    Map<String, String> first = compose(commissions, step(RUN, 1, false)).env();
    Map<String, String> second = compose(commissions, step(RUN, 2, true)).env();

    // The credential belongs to the RUN, not to the step: one commission, and every step is handed
    // the same token. One per step would be N tokens to leak instead of one.
    assertEquals(1, idp.postedTokens.size(), "one commission for the whole run");
    assertSame(held, commissions.forRun(RUN, Map.of()));
    assertEquals("qits_tok_stub-1", first.get("QITS_TOKEN"));
    assertEquals(first.get("QITS_TOKEN"), second.get("QITS_TOKEN"));
    assertEquals(held.subject(), first.get("QITS_TOKEN_SUBJECT"));
    assertEquals(held.subject(), commissions.tokenSubjectOf(RUN));
    assertTrue(commissions.holdsToken("token-1"));
    assertEquals("/tmp/qits-gitconfig", first.get("GIT_CONFIG_GLOBAL"));
    assertEquals("/tmp/qits-publish-token", first.get("QITS_PUBLISH_TOKEN_COMMAND"));
    assertEquals("/tmp/qits-publish-token", second.get("QITS_PUBLISH_TOKEN_COMMAND"));
  }

  /** qits-515: the CLIENT kind of run commission is deleted; the clients door is never asked. */
  @Test
  public void aRunNeverCommissionsAClient() {
    RunCommissions commissions = idp.runCommissions(PATIENCE);
    compose(commissions, step(RUN, 1, true));
    commissions.release(RUN);

    assertEquals(List.of(), idp.posted, "no client was commissioned for the run");
    assertEquals(List.of(), idp.deleted, "and none was given back");
  }

  @Test
  public void theCommissioningCallSaysWhatItIsForAndWhoIsAsking() {
    compose(idp.runCommissions(PATIENCE), step(RUN, 1, true));

    // The context is what makes the reconciliation possible at all: a token qits-idp holds says
    // which run owns it, so one whose run is over is reapable without any bookkeeping of ours.
    assertEquals(
        List.of("{\"contextKind\":\"ci-run\",\"contextId\":\"" + RUN + "\"}"), idp.postedTokens);
    // And the caller is the service's OWN oidc client.
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (StubIdp.SERVICE_CLIENT_ID + ":" + StubIdp.SERVICE_SECRET)
                        .getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of(expected), idp.authorizations);
  }

  @Test
  public void aSecondRunGetsACredentialOfItsOwn() {
    RunCommissions commissions = idp.runCommissions(PATIENCE);

    Map<String, String> first = compose(commissions, step("run-a", 1, true)).env();
    Map<String, String> second = compose(commissions, step("run-b", 1, true)).env();

    assertEquals(2, idp.postedTokens.size());
    assertNotEquals(first.get("QITS_TOKEN"), second.get("QITS_TOKEN"));
    assertNotEquals(first.get("QITS_TOKEN_SUBJECT"), second.get("QITS_TOKEN_SUBJECT"));
    assertTrue(idp.postedTokens.get(0).contains("run-a"));
    assertTrue(idp.postedTokens.get(1).contains("run-b"));
  }

  @Test
  public void aCommissionThatCannotBeMadeIsAnExceptionNamingTheCall() {
    idp.tokenMintStatus = 403;

    IdpCommissioner.CommissionFailedException failed =
        assertThrows(
            IdpCommissioner.CommissionFailedException.class,
            () -> idp.runCommissions(PATIENCE).forRun(RUN, Map.of()));

    // What RunnerStepRunner records as the step's LAUNCH_FAILED output.
    assertTrue(failed.getMessage().contains("/api/tokens"), failed.getMessage());
    assertTrue(failed.getMessage().contains(RUN), failed.getMessage());
    assertEquals(1, idp.postedTokens.size(), "a 403 is about the request: one attempt");
  }

  @Test
  public void closingTheRunGivesTheCredentialBack() {
    RunCommissions commissions = idp.runCommissions(PATIENCE);
    compose(commissions, step(RUN, 1, true));

    // The runner-side teardown (RunnerStepRunner#runClosed) is what calls this in production; the
    // credential lifecycle itself is RunCommissions' own and is what this class pins.
    commissions.release(RUN);

    assertEquals(List.of("token-1"), idp.deletedTokens);
    // And it is gone from memory too, so a run id that came round again would commission afresh
    // rather than hand out a credential qits-idp no longer knows.
    assertFalse(commissions.holdsToken("token-1"));
    assertNull(commissions.tokenSubjectOf(RUN));
    // Releasing a run that holds nothing asks qits-idp nothing.
    commissions.release(RUN);
    assertEquals(List.of("token-1"), idp.deletedTokens);
  }

  @Test
  public void aDeploymentWithNoOidcClientCommissionsNothing() {
    // quarkus.oidc-client.qits.client-enabled is false under %test, so there is nothing to
    // commission with. The answer is null and RunnerStepRunner launches no step on it.
    assertNull(StubIdp.disabledCommissions().forRun(RUN, Map.of()));
    assertEquals(List.of(), idp.postedTokens);
  }

  @Test
  public void theTokenReachesTheContainerInExactlyTheFormsItIsSpentIn() {
    WorkloadSpec request = compose(idp.runCommissions(PATIENCE), step(RUN, 1, true));
    String token = "qits_tok_stub-1";

    // Raw under two names — $QITS_TOKEN, and the password half of the pair a repository's own
    // maven settings read — and base64 inside the docker document. Anywhere else — an argv, a
    // label, the container's name, the bootstrap — is a leak into something that gets logged or
    // baked into an image.
    List<String> carrying =
        request.env().entrySet().stream()
            .filter(entry -> entry.getValue().contains(token))
            .map(Map.Entry::getKey)
            .toList();
    assertEquals(List.of("QITS_TOKEN", "QITS_MAVEN_AUTH_PSW"), carrying);
    String encoded =
        Base64.getEncoder().encodeToString(("token:" + token).getBytes(StandardCharsets.UTF_8));
    assertTrue(request.env().get("QITS_CI_REGISTRY_AUTH_CONFIG").contains(encoded));
    assertFalse(String.valueOf(request.args()).contains(token));
    assertFalse(String.valueOf(request.entrypoint()).contains(token));
    assertFalse(String.valueOf(request.labels()).contains(token));
    assertFalse(String.valueOf(request.name()).contains(token));
    assertFalse(StepContainerSettings.BOOTSTRAP.contains(token));
    // And nothing sent names the idp it was minted at.
    request
        .env()
        .forEach(
            (key, value) ->
                assertFalse(value.contains(idp.authServerUrl()), key + " names the idp: " + value));
  }
}
