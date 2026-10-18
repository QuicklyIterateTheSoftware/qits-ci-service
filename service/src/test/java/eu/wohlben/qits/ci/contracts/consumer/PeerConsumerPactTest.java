package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of qits-ci's pacts against qits-projects, qits-githost, qits-artifacts and
 * qits-idp</b> (qits-1149): every row of {@link PeerContracts}, its real client against a pact
 * mock server answering that row's recorded interaction and nothing else, plus each committed pact
 * file and its references. Plain JUnit: every client is a hand-rolled {@code java.net.http} one.
 *
 * <p>A row whose provider state is not recorded is reported skipped, naming the state it needs. The
 * token mint is the one row run elsewhere ({@code IdpTokenMintPactTest}): its client is
 * quarkus-oidc-client's, which only exists inside the application.
 */
class PeerConsumerPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatItsClientAsksAndUnderstands() {
    assertFalse(PeerContracts.ROWS.isEmpty());
    return PeerContracts.ROWS.stream()
        .map(
            bound ->
                DynamicTest.dynamicTest(
                    bound.provider().repository()
                        + " "
                        + bound.row().description()
                        + " ["
                        + bound.row().state()
                        + "]",
                    () -> {
                      ConsumerPact pact = PeerContracts.pactOf(bound);
                      for (GoldenInteraction row : bound.rows()) {
                        if (!bound.provider().recorded(row.state(), row.operationId())) {
                          Assumptions.abort(pact.needs(row));
                        }
                      }
                      Assumptions.assumeTrue(
                          bound.call() != null, "run by IdpTokenMintPactTest, inside the application");
                      if (bound.with().isEmpty()) {
                        pact.run(bound.row(), (url, recorded) -> bound.call().run(url, recorded));
                      } else {
                        runTogether(pact, bound);
                      }
                    }));
  }

  /**
   * A call that is several requests: one mock server answering every row of the bound, and the
   * call handed the first row's recording. pact-jvm fails the run on a request no row answers and
   * on a row no request reached, as {@link ConsumerPact#run} does for one.
   */
  private static void runTogether(ConsumerPact pact, PeerContracts.Bound bound) {
    GoldenMasters.Operation recorded =
        bound.provider().operation(bound.row().state(), bound.row().operationId());
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact.pact(bound.rows()),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              bound.call().run(mockServer.getUrl(), recorded);
              return null;
            });
    if (result instanceof PactVerificationResult.Error error
        && error.getError() instanceof AssertionError assertion) {
      throw assertion;
    }
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(bound.row().description() + ": " + result);
    }
  }

  @TestFactory
  Stream<DynamicTest> theCommittedPactsAreWhatTheRowsWrite() {
    return PeerContracts.PACTS.stream()
        .map(pact -> DynamicTest.dynamicTest(pact.file(), pact::compareOrWritePactFile));
  }

  @TestFactory
  Stream<DynamicTest> everyInteractionCarriesBothReferences() {
    return PeerContracts.PACTS.stream()
        .filter(pact -> !pact.recorded().isEmpty())
        .map(
            pact ->
                DynamicTest.dynamicTest(
                    pact.file(), pact::assertEveryInteractionCarriesBothReferences));
  }
}
