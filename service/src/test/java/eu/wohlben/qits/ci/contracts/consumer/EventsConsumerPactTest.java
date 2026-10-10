package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-events contract</b> (qits-1149): the qits-eventstream library's
 * real {@code EventsQuery}, making a real HTTP call to a pact-jvm mock server that answers what
 * {@link EventsContract}'s row promises, and the row's own assertions on what the library made of
 * it. Plain JUnit: the client is the JDK's HttpClient, so nothing needs Quarkus.
 *
 * <p>One mock server per row (pact-jvm's programmatic runner), because several rows send the same
 * request and differ only in their trigger.
 */
class EventsConsumerPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  @Test
  void everyRowIsWhatTheLibraryAsksAndUnderstands() {
    assertFalse(EventsContract.CASES.isEmpty());
    List<String> failures = new ArrayList<>();
    for (EventsContract.Case row : EventsContract.CASES) {
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              EventsContract.pact(List.of(row)),
              MockProviderConfig.createDefault(PactSpecVersion.V4),
              (mockServer, context) -> {
                row.call().run(mockServer.getUrl());
                return null;
              });
      if (!(result instanceof PactVerificationResult.Ok)) {
        failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " contract row(s) failed:\n  " + String.join("\n  ", failures));
    }
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
