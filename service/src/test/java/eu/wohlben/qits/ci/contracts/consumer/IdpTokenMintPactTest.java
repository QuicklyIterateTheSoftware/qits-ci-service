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
 * grant_type=client_credentials&audience=qits-platform}, which qits-idp records under {@code a
 * service client calling its peers}.
 *
 * <p><b>The port is a system property, not a static field.</b> Quarkus loads the test profile in
 * its own classloader, so a static field initialised there and the one this test instance reads are
 * two fields with two free ports: the client dialled one while the mock listened on the other. The
 * first to ask picks the port and parks it in {@link #PORT_PROPERTY}; both classloaders read it back.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
class IdpTokenMintPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /** Where the mock server's port is parked, shared by both classloaders. */
  static final String PORT_PROPERTY = "qits.ci.contract.idp-mock-port";

  /** The `qits` client on, as `test-broad`, against the mock server. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + port() + "/idp",
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
            MockProviderConfig.httpConfig("127.0.0.1", port(), PactSpecVersion.V4),
            (mockServer, context) -> {
              assertTrue(bearer.token().isPresent(), "the idp's access_token is the bearer");
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(row.description() + ": " + result);
    }
  }

  /** The port the pact mock server listens on: chosen free once per JVM, whichever loader asks. */
  static synchronized int port() {
    String parked = System.getProperty(PORT_PROPERTY);
    if (parked != null) {
      return Integer.parseInt(parked);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
