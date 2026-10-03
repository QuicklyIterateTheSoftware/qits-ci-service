package eu.wohlben.qits.ci.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * The keys a suite must answer the same wherever it runs, pinned above the environment.
 *
 * <p><b>Why a config source and not {@code %test.} lines.</b> This suite runs inside a qits-ci step
 * container, and SmallRye lets an unprofiled key from a HIGHER-ordinal source beat a {@code %test.}
 * key from a lower one: an environment variable (300) wins over {@code application.properties}
 * (250) whatever profile prefix the file uses — measured, a {@code QUARKUS_OIDC_AUTH_SERVER_URL}
 * beat {@code %test.quarkus.oidc.auth-server-url=}. So a pin that must hold against an ambient
 * variable has to sit above 300; this source is 500, beside {@link EmbeddedPgConfigSource}.
 * A {@code QuarkusTestProfile}'s overrides still outrank it, so a test that wants another value
 * says so in its profile.
 *
 * <p><b>The OIDC tenant verifies locally, against a key nothing signs with.</b> A case that turns
 * the machine gate on ({@code MachineGuardTest.GateOn}) and presents a bearer {@code @TestSecurity}
 * did not install — {@code CiRunnerControllerTest.aRawTokenOpensNoOtherRoute}'s raw {@code
 * qits_tok_} on a route that is not the register door — is handed to quarkus-oidc, which fetched
 * the JWKS from the shipped address, {@code http://dev-qits-idp:8080/idp}. That alias
 * resolves on qits-net, so the answer was a 401 in a workspace and in a local step and a 503 ("OIDC
 * server is not available") on an EDGE runner, which cannot resolve it: qits-ci's first edge QA run
 * failed on exactly that. With a public key and no address the tenant dials nothing, and every
 * bearer it did not sign is a 401 wherever the suite runs. The address is blanked, not merely
 * shadowed: quarkus-oidc 3.34 builds a public-key tenant only when {@code auth-server-url} is
 * ABSENT ({@code TenantContextFactory}), and with both set it keeps dialling. {@code
 * TokenValidationBootstrapIT}, the one place the real JWKS path is exercised, launches the packaged
 * artifact, which does not see this test-classpath source.
 *
 * <p><b>{@code qits.artifacts.url} is blanked because an ambient environment can name it by
 * accident.</b> The environment source maps a variable spelled {@code QITS_ARTIFACTS_URL} onto this
 * key, which the service ships unset and {@code HttpImagePins} reads. A step container used to be
 * handed exactly that variable; since qits-731 no step is told any URL variable, but a step launched
 * by an older qits-ci, or any other container that carries one, still would be — and the suite must
 * not read where it runs.
 *
 * <p><b>{@code qits.ci.domain} is the suite's own, {@link #DOMAIN}.</b> Every address a runner or a
 * step is told is composed from the platform's public domain and there is nothing to fall back to
 * (qits-515), so a suite with no domain could declare no runner and launch no step. The shipped key
 * is {@code ${QITS_DOMAIN:}}, which an ambient variable would decide; this pin is above it. A case
 * about a qits-ci that knows no domain installs a {@code RunnerAddresses} of its own over the bean.
 */
public class HermeticConfigSource implements ConfigSource {

  /** A 2048-bit RSA public key whose private half was thrown away: it verifies nothing. */
  private static final String NOBODYS_PUBLIC_KEY =
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvNmeDzbh0xZmfY0ZnfPIVOPwYIN7wPyGf1qb83v1oErtoFq1"
          + "p+5fElbn/tFw3ucgDCeeVNxI/5MjdYIpvz5kfAx963LroAL2cLgQh8SpK4dQoUlhT+xbQP77IH+XaMN6sK3Vuzd"
          + "AAFJv7CAu7mOEfwFTk0TvBYB0HUAV/bfKyKQFOoauRI9DyLYiRBAIgPMP8I2nshqnI/bJ6iuyZZtI48Nus0XG1Rj"
          + "YfLJVKyOY2TUwobGmiyNh+ZevqeTLQ2txavZjeh/vDcHIKfSWo7GfHlAXVeCHS6igp4pCc0vAJwoBk3gTf+kAav3+"
          + "irYhO16f80pQXjrMOxBySoy45KhciQIDAQAB";

  /** The public domain the suite's qits-ci states: its names are {@code <host>.qits.suite.example}. */
  public static final String DOMAIN = "suite.example";

  private final Map<String, String> values =
      Map.of(
          "qits.ci.domain", DOMAIN,
          "quarkus.oidc.auth-server-url", "",
          "quarkus.oidc.public-key", NOBODYS_PUBLIC_KEY,
          "qits.artifacts.url", "");

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "hermetic";
  }
}
