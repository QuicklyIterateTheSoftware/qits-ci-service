package eu.wohlben.qits.ci.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
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
 * <p><b>Twenty consumers pin a pact</b>. Seven since qits-1149: qits-landing-app ({@code
 * eu.wohlben.qits:qits-landing-app-pacts-qits-ci-service}), qits-artifacts-service ({@code
 * eu.wohlben.qits:qits-artifacts-service-pacts-qits-ci-service}), qits-bootstrap-cli,
 * qits-maintenance-service, qits-orchestrator-service, qits-platform-access-cli and
 * qits-projects-service (each {@code eu.wohlben.qits:<consumer>-pacts-qits-ci-service}), all
 * test-scoped in {@code service/pom.xml}. Thirteen since qits-1284, one per frontend whose
 * {@code @qits/ui-components} calls qits-ci on its behalf: qits-ci-frontend, qits-projects-frontend,
 * qits-docs-frontend, qits-events-frontend, qits-githost-frontend, qits-maintenance-frontend,
 * qits-artifacts-frontend, qits-configuration-frontend, qits-deployments-frontend,
 * qits-mirror-frontend, qits-observability-frontend, qits-orchestrator-frontend and
 * qits-system-frontend (each {@code eu.wohlben.qits:<frontend>-pacts-qits-ci-service}, holding
 * {@code pacts/<frontend>_qits-ui-components-jslib_qits-ci-service.json} — the library sits in the
 * middle of the name, and the loader matches only the {@code _qits-ci-service.json} suffix). All
 * thirteen ask {@code listActiveRuns} under {@code a commit with a run in flight}; qits-ci-frontend
 * and qits-projects-frontend also open a run's reports ({@code listRunReports}, {@code
 * getRunReport}, {@code listRunBaselineReports}) under {@code a run with reports: failing tests and
 * coverage}. Every state {@link ProviderStates} answers has its {@code @State} method below,
 * so a consumer pinned next needs no edit here unless it asks for a state nobody recorded. {@link ClasspathPactLoader#REQUIRED} is true, so a classpath that lost them
 * fails rather than verifying nothing. A new consumer's jar is pinned beside them, and any state its
 * pact names that {@link ProviderStates} does not answer is added there with its golden master.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP,
 * unauthenticated — the {@code %test} dev user, exactly as {@link GoldenMasterRecordingTest}'s
 * REST-assured calls run. A door that judges the caller's token (submit a step's
 * report, the machine gate) reads the {@code Authorization} header the pact sends: the state's
 * {@code authorization} param, which {@link ContractBearers} turns into that token's identity. Every {@code @State} method delegates to {@link ProviderStates}; {@link
 * #target} fails an unknown state, and an interaction without {@code comments.references.qits-call}
 * or {@code qits-trigger}.
 *
 * <p><b>A pinned pact that is wrong is named in {@link #KNOWN_WRONG}, by its jar's version</b>, and
 * its interaction is reported as skipped with the reason — never answered by bending the provider
 * to it (the pattern of qits-maintenance-service). The entry is for that version alone: the bump
 * that carries the consumer's fix verifies the interaction for real, and a bump that does not fix
 * it goes red here, which is the point.
 *
 * <p><b>Ordering.</b> pact-jvm runs the {@code @State} methods after every before-each, so the state
 * seeds after {@link #target}; {@link #consumerPactHolds} removes the rows again.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
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

  /** A pinned pact interaction this provider cannot honour, and why: the consumer must fix it. */
  record KnownWrong(String consumer, String version, String description, String reason) {

    boolean names(Pact pact, Interaction interaction) {
      return consumer.equals(pact.getConsumer().getName())
          && description.equals(interaction.getDescription())
          && String.valueOf(pact.getSource()).contains("/" + version + "/");
    }
  }

  /**
   * None at present: qits-platform-access-cli 2026.1019.65035's report row without the run's
   * Authorization header was fixed in 2026.1019.92700, which is the version pinned.
   */
  static final List<KnownWrong> KNOWN_WRONG = List.of();

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
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
    for (KnownWrong wrong : KNOWN_WRONG) {
      if (wrong.names(pact, interaction)) {
        Assumptions.abort(
            consumer + " " + wrong.version() + " '" + interaction.getDescription() + "': "
                + wrong.reason());
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
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

  @State(ProviderStates.A_RUN_QUEUE_WITH_A_RUN_WAITING)
  Map<String, String> aRunQueueWithARunWaiting() {
    return states.params(ProviderStates.A_RUN_QUEUE_WITH_A_RUN_WAITING);
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

  @State(ProviderStates.NO_RUN_WITH_THE_GIVEN_ID)
  Map<String, String> noRunWithTheGivenId() {
    return states.params(ProviderStates.NO_RUN_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.A_FAILED_RUN_RETRIED_AUTOMATICALLY)
  Map<String, String> aFailedRunRetriedAutomatically() {
    return states.params(ProviderStates.A_FAILED_RUN_RETRIED_AUTOMATICALLY);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_QA_RUNS_IN_FLIGHT)
  Map<String, String> aReleaseRequestWithQaRunsInFlight() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_QA_RUNS_IN_FLIGHT);
  }

  @State(ProviderStates.NO_CANDIDATE_REPOSITORY_CAN_BE_READ)
  Map<String, String> noCandidateRepositoryCanBeRead() {
    return states.params(ProviderStates.NO_CANDIDATE_REPOSITORY_CAN_BE_READ);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_NO_PIPELINE_FOR_THE_EVENT)
  Map<String, String> aRepositoryWithNoPipelineForTheEvent() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_NO_PIPELINE_FOR_THE_EVENT);
  }

  @State(ProviderStates.A_REPOSITORY_A_MAINTENANCE_BUMP_TARGETS)
  Map<String, String> aRepositoryAMaintenanceBumpTargets() {
    return states.params(ProviderStates.A_REPOSITORY_A_MAINTENANCE_BUMP_TARGETS);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_RELEASE_REQUEST_AUTOMATION_PLATFORM_PIPELINE)
  Map<String, String> aRepositoryWithAReleaseRequestAutomationPlatformPipeline() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_RELEASE_REQUEST_AUTOMATION_PLATFORM_PIPELINE);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_RUNS_TO_FIND_BY_THE_START_OF_AN_ID)
  Map<String, String> aRepositoryWithRunsToFindByTheStartOfAnId() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_RUNS_TO_FIND_BY_THE_START_OF_AN_ID);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_ITS_30_MOST_RECENT_RUNS)
  Map<String, String> aRepositoryWithIts30MostRecentRuns() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_ITS_30_MOST_RECENT_RUNS);
  }
}
