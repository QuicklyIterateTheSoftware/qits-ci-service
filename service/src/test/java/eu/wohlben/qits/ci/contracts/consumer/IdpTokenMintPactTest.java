package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.ci.githost.IdpGitHostBearer;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The client-credentials token mint, as quarkus-oidc-client makes it</b> (qits-1149): the {@code
 * qits} named client, switched on and pointed at a pact mock server, asked for a token through the
 * one bean that spends it ({@link IdpGitHostBearer}). The row is {@link PeerContracts}' {@code
 * issueToken}; it runs here because the OIDC client only exists inside the application, and on a
 * port this profile picks before the application starts, because the client reads its idp from
 * config.
 *
 * <p>What the client sends is Basic auth as {@code test-broad} and {@code
 * grant_type=client_credentials&audience=qits-platform}; the idp's recorded client-credentials grant
 * carries no audience, so the row waits on a state that does.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
@Disabled(
    "needs provider state 'a service client minting a platform-audience token' for issueToken in"
        + " qits-idp-service")
class IdpTokenMintPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /** The port the pact mock server listens on, chosen free once per JVM. */
  static final int PORT = freePort();

  /** The `qits` client on, as `test-broad`, against the mock server. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + PORT + "/idp",
          "quarkus.oidc-client.qits.client-id", PeerContracts.IDP_CLIENT_ID,
          "quarkus.oidc-client.qits.credentials.secret", PeerContracts.IDP_CLIENT_SECRET);
    }
  }

  @Inject IdpGitHostBearer bearer;

  @Test
  void theBearerIsMintedFromTheIdp() {
    PeerContracts.Bound mint =
        PeerContracts.ROWS.stream()
            .filter(b -> b.client() == IdpGitHostBearer.class)
            .findFirst()
            .orElseThrow();
    GoldenInteraction row = mint.row();
    ConsumerPact pact = PeerContracts.pactOf(mint);
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact.pact(List.of(row)),
            MockProviderConfig.httpConfig("127.0.0.1", PORT, PactSpecVersion.V4),
            (mockServer, context) -> {
              assertTrue(bearer.token().isPresent(), "the idp's access_token is the bearer");
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(row.description() + ": " + result);
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
