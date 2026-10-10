package eu.wohlben.qits.ci.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running qits-ci</b> (epic qits-112), the way
 * qits-projects-service and qits-edge-service do.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-ci-service.json} (repository names on both sides), this repo pins
 * that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases a
 * changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>No consumer pins a pact yet</b>, so {@code @IgnoreNoPactsToVerify} lets an empty classpath
 * pass and the loader logs that nothing was verified. The first is qits-landing-app's ({@code
 * eu.wohlben.qits:qits-landing-app-pacts-qits-ci-service}). When its jar is pinned in {@code
 * service/pom.xml}, drop the annotation and set {@link ClasspathPactLoader#REQUIRED} to true.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP,
 * unauthenticated — the {@code %test} dev user, exactly as {@link GoldenMasterRecordingTest}'s
 * REST-assured calls run. A door that judges the caller's token (register a runner, submit a step's
 * report, the machine gate) reads the {@code Authorization} header the pact sends: the state's
 * {@code authorization} param, which {@link ContractBearers} turns into that token's identity. Every {@code @State} method delegates to {@link ProviderStates}; {@link
 * #target} fails an unknown state, and an interaction without {@code comments.references.qits-call}
 * or {@code qits-trigger}.
 *
 * <p><b>Ordering.</b> pact-jvm runs the {@code @State} methods after every before-each, so the state
 * seeds after {@link #target}; {@link #consumerPactHolds} removes the rows again.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  /**
   * The provider as a consumer pact names it: the repository name. The golden-master index keeps
   * the application name ({@link GoldenMasterRecordingTest#PROVIDER}).
   */
  static final String PROVIDER = "qits-ci-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // no pact to verify: @IgnoreNoPactsToVerify's single empty run
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-ci does not answer for — it answers for "
                + states.names());
      }
    }
    // THE REFERENCES. pact-jvm's verification ignores comments.references; this does not. Every
    // interaction must say which provider operation it calls (qits-call) and what on the consumer's
    // side triggers it (qits-trigger), so a consumer that drops them is caught at the provider too.
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context == null) {
      return;
    }
    try {
      context.verifyInteraction();
    } finally {
      states.cleanUp();
    }
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST)
  Map<String, String> aRepositoryWithTheRunsOfAReleaseRequest() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST);
  }

  @State(ProviderStates.A_RUN_WITH_REPORTS_FAILING_TESTS_AND_COVERAGE)
  Map<String, String> aRunWithReportsFailingTestsAndCoverage() {
    return states.params(ProviderStates.A_RUN_WITH_REPORTS_FAILING_TESTS_AND_COVERAGE);
  }

  @State(ProviderStates.A_GREEN_RELEASE_RUN)
  Map<String, String> aGreenReleaseRun() {
    return states.params(ProviderStates.A_GREEN_RELEASE_RUN);
  }

  @State(ProviderStates.A_FAILED_RUN_THAT_RETRIES_ANOTHER)
  Map<String, String> aFailedRunThatRetriesAnother() {
    return states.params(ProviderStates.A_FAILED_RUN_THAT_RETRIES_ANOTHER);
  }

  @State(ProviderStates.A_RUNNING_RUN)
  Map<String, String> aRunningRun() {
    return states.params(ProviderStates.A_RUNNING_RUN);
  }

  @State(ProviderStates.A_REPOSITORY_WHOSE_RELEASE_RECIPE_SELECTS_SCM_RELEASE)
  Map<String, String> aRepositoryWhoseReleaseRecipeSelectsScmRelease() {
    return states.params(ProviderStates.A_REPOSITORY_WHOSE_RELEASE_RECIPE_SELECTS_SCM_RELEASE);
  }

  @State(ProviderStates.THE_MACHINE_GATE_IS_ON)
  Map<String, String> theMachineGateIsOn() {
    return states.params(ProviderStates.THE_MACHINE_GATE_IS_ON);
  }

  @State(ProviderStates.NO_RUNNERS)
  Map<String, String> noRunners() {
    return states.params(ProviderStates.NO_RUNNERS);
  }

  @State(ProviderStates.AN_UNREGISTERED_RUNNER)
  Map<String, String> anUnregisteredRunner() {
    return states.params(ProviderStates.AN_UNREGISTERED_RUNNER);
  }

  @State(ProviderStates.A_REGISTERED_RUNNER)
  Map<String, String> aRegisteredRunner() {
    return states.params(ProviderStates.A_REGISTERED_RUNNER);
  }

  @State(ProviderStates.A_CONNECTED_RUNNER)
  Map<String, String> aConnectedRunner() {
    return states.params(ProviderStates.A_CONNECTED_RUNNER);
  }

  @State(ProviderStates.A_QUARANTINED_RUNNER)
  Map<String, String> aQuarantinedRunner() {
    return states.params(ProviderStates.A_QUARANTINED_RUNNER);
  }

  @State(ProviderStates.RUNNERS_WITH_FREE_SLOTS)
  Map<String, String> runnersWithFreeSlots() {
    return states.params(ProviderStates.RUNNERS_WITH_FREE_SLOTS);
  }

  @State(ProviderStates.A_PINNED_DAEMON)
  Map<String, String> aPinnedDaemon() {
    return states.params(ProviderStates.A_PINNED_DAEMON);
  }

  @State(ProviderStates.A_RELEASED_VERSION_WITH_ARTIFACT_DECISIONS)
  Map<String, String> aReleasedVersionWithArtifactDecisions() {
    return states.params(ProviderStates.A_RELEASED_VERSION_WITH_ARTIFACT_DECISIONS);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_RUNS_IN_FLIGHT)
  Map<String, String> aReleaseRequestWithRunsInFlight() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_RUNS_IN_FLIGHT);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WHOSE_QA_RUN_FAILED)
  Map<String, String> aReleaseRequestWhoseQaRunFailed() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WHOSE_QA_RUN_FAILED);
  }

  @State(ProviderStates.A_REPOSITORY_THAT_DECLARES_A_RELEASE_PHASE)
  Map<String, String> aRepositoryThatDeclaresAReleasePhase() {
    return states.params(ProviderStates.A_REPOSITORY_THAT_DECLARES_A_RELEASE_PHASE);
  }

  @State(ProviderStates.A_COMMIT_WITH_A_RUN_IN_FLIGHT)
  Map<String, String> aCommitWithARunInFlight() {
    return states.params(ProviderStates.A_COMMIT_WITH_A_RUN_IN_FLIGHT);
  }

  @State(ProviderStates.A_RELEASE_RUN_WHOSE_GATE_RUN_HAS_REPORTS)
  Map<String, String> aReleaseRunWhoseGateRunHasReports() {
    return states.params(ProviderStates.A_RELEASE_RUN_WHOSE_GATE_RUN_HAS_REPORTS);
  }
}
