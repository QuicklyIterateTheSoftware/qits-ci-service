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
 * REST-assured calls run. Every {@code @State} method delegates to {@link ProviderStates}; {@link
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
  Map<String, String> aRepositoryWithTheRunsOfARequest() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_THE_RUNS_OF_A_RELEASE_REQUEST);
  }
}
