package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
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
                      if (!bound.provider().recorded(bound.row().state(), bound.row().operationId())) {
                        Assumptions.abort(pact.needs(bound.row()));
                      }
                      Assumptions.assumeTrue(
                          bound.call() != null, "run by IdpTokenMintPactTest, inside the application");
                      pact.run(bound.row(), (url, recorded) -> bound.call().run(url, recorded));
                    }));
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
