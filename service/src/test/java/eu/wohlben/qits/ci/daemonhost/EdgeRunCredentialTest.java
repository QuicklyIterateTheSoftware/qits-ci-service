package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.StubIdp;
import eu.wohlben.qits.ci.runnerhost.RunnerAddressesFixture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The run's credential by plane (qits-475): a {@code ci-run} TOKEN for a run on the edge plane,
 * the commissioned client for everything else, and the two step environments that follow from them
 * disjoint — an edge step is handed nothing that names the internal idp, an internal step nothing
 * of the token's. Plain JUnit against {@link StubIdp}, {@code RunCommissioningTest}'s arrangement.
 */
class EdgeRunCredentialTest {

  private static final String RUN = "0123456789abcdef-run";

  /** Every key that belongs to the client path and must never reach an edge step. */
  private static final Set<String> CLIENT_KEYS =
      Set.of(
          "QITS_COMMISSIONED_CLIENT_ID",
          "QITS_COMMISSIONED_CLIENT_SECRET",
          "QITS_GIT_AUTH_TOKEN_URL",
          "QITS_GIT_AUTH_HOST",
          "QITS_GIT_AUTH_AUDIENCE");

  private static final Set<String> TOKEN_KEYS = Set.of("QITS_TOKEN", "QITS_TOKEN_SUBJECT");

  private StubIdp idp;
  private RunCommissions commissions;

  @BeforeEach
  void startStub() {
    idp = new StubIdp();
    commissions = idp.runCommissions(Duration.ofMillis(200));
  }

  @AfterEach
  void stopStub() {
    idp.close();
  }

  private static Map<String, String> bump() {
    return StepEnvironmentCharacterizationTest.sampleStep(0, false, false).env();
  }

  @Test
  void anEdgeRunIsCommissionedATokenOnceAndEveryLaterStepReusesIt() {
    RunCommissions.Credential first = commissions.forRun(RUN, bump(), CiRunnerPlane.EDGE);
    RunCommissions.Credential second = commissions.forRun(RUN, bump(), CiRunnerPlane.EDGE);

    assertTrue(first.isToken());
    assertNull(first.client());
    assertSame(first.token(), second.token(), "one token per run, not per step");
    // The client's context and the client's scope, on the tokens door; the clients door untouched.
    assertEquals(
        List.of(
            "{\"contextKind\":\"ci-run\",\"contextId\":\""
                + RUN
                + "\",\"gitRefs\":[\"refs/heads/maintenance/dependencies\"]}"),
        idp.postedTokens);
    assertTrue(idp.posted.isEmpty(), "no client for an edge run");
    assertEquals("qits_tok_stub-1", first.token().token());
    assertEquals(first.token().subject(), commissions.tokenSubjectOf(RUN));
    assertTrue(commissions.holdsToken("token-1"));
  }

  @Test
  void anInternalRunAndALocalStepKeepTheClientExactlyAsBefore() {
    RunCommissions.Credential internal = commissions.forRun(RUN, bump(), CiRunnerPlane.INTERNAL);
    RunCommissions.Credential local = commissions.forRun(RUN, bump(), null);

    assertFalse(internal.isToken());
    assertEquals("run-client-1", internal.client().clientId());
    assertSame(internal.client(), local.client());
    assertTrue(idp.postedTokens.isEmpty(), "no token for an internal run");
    assertNull(commissions.tokenSubjectOf(RUN));
  }

  @Test
  void theRunsCloseDeletesItsToken() {
    commissions.forRun(RUN, bump(), CiRunnerPlane.EDGE);

    commissions.release(RUN);

    assertEquals(List.of("token-1"), idp.deletedTokens);
    assertFalse(commissions.holdsToken("token-1"));
    assertNull(commissions.tokenSubjectOf(RUN));
  }

  @Test
  void aDeploymentThatCommissionsNothingCommissionsNoTokenEither() {
    assertNull(StubIdp.disabledCommissions().forRun(RUN, bump(), CiRunnerPlane.EDGE));
  }

  @Test
  void theEdgeAndInternalEnvironmentsAreDisjointInTheirCredentials() {
    CiDaemonLauncher launcher =
        StepEnvironmentCharacterizationTest.shippedLauncher(idp.authServerUrl());
    StepAddressPlane edgePlane =
        StepAddressPlane.edge(
            RunnerAddressesFixture.withDomain("example.org").edgeOrigins().orElseThrow(),
            launcher.internalPlane());
    CiDaemonLauncher.LaunchSpec step = StepEnvironmentCharacterizationTest.sampleStep(1, true, false);

    Map<String, String> edge =
        StepWorkloadSpecs.compose(
                launcher.workloadSettings(),
                edgePlane,
                step,
                commissions.forRun(RUN, step.env(), CiRunnerPlane.EDGE))
            .env();
    Map<String, String> internal =
        StepWorkloadSpecs.compose(
                launcher.workloadSettings(),
                launcher.internalPlane(),
                step,
                commissions.forRun("another-run", step.env(), CiRunnerPlane.INTERNAL))
            .env();

    assertEquals("qits_tok_stub-1", edge.get("QITS_TOKEN"));
    assertEquals(commissions.tokenSubjectOf(RUN), edge.get("QITS_TOKEN_SUBJECT"));
    for (String key : CLIENT_KEYS) {
      assertFalse(edge.containsKey(key), key + " reached an edge step");
      assertTrue(internal.containsKey(key), key + " left an internal step");
    }
    for (String key : TOKEN_KEYS) {
      assertFalse(internal.containsKey(key), key + " reached an internal step");
    }
    // And nothing else that names the internal idp: not its address, not its client's secret.
    edge.forEach(
        (key, value) -> {
          assertFalse(value.contains(idp.authServerUrl()), key + " names the internal idp: " + value);
          assertFalse(value.contains("run-s3cr3t"), key + " carries a client secret");
        });
    // What both planes share, spelled the same: the gitconfig, the publish command.
    assertEquals(internal.get("GIT_CONFIG_GLOBAL"), edge.get("GIT_CONFIG_GLOBAL"));
    assertEquals(
        internal.get("QITS_PUBLISH_TOKEN_COMMAND"), edge.get("QITS_PUBLISH_TOKEN_COMMAND"));
  }
}
