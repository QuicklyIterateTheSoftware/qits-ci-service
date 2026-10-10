package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Reports every row of {@link PendingContracts} as a skipped test whose reason names the provider
 * state it waits for (qits-1149), so the gap is visible in every test run rather than only in a
 * document.
 */
class PendingContractsTest {

  @TestFactory
  Stream<DynamicTest> everyUnpactedCallWaitsOnANamedProviderState() {
    assertFalse(PendingContracts.ROWS.isEmpty());
    return PendingContracts.ROWS.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.provider() + " " + row.method() + " " + row.path() + " — "
                        + row.trigger().value(),
                    () -> Assumptions.abort(row.reason())));
  }
}
