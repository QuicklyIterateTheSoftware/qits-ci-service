package eu.wohlben.qits.ci.idp;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link IdpCommissioner} wired by hand against one idp, for tests outside this package — the
 * consumer pacts (qits-1149) point it at a pact mock server, as {@link StubIdp} does at its stub.
 */
public final class IdpClients {

  private IdpClients() {}

  /**
   * A commissioner asking {@code authServerUrl} (the idp's base, {@code …/idp}) as {@code
   * clientId}/{@code secret}, giving up after the first answer.
   */
  public static IdpCommissioner commissioner(String authServerUrl, String clientId, String secret) {
    IdpCommissioner idp = new IdpCommissioner();
    idp.clientEnabled = true;
    idp.authServerUrl = authServerUrl;
    idp.clientId = clientId;
    idp.clientSecret = Optional.of(secret);
    idp.patience = Duration.ZERO;
    idp.objectMapper = new ObjectMapper();
    idp.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    idp.buildClient();
    return idp;
  }
}
