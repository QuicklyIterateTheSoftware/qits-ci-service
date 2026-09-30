package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The addresses a runner is told: the public edge names composed from the platform's domain, each
 * override, and the refusal where no public domain is known. Plain JUnit: the composition
 * is string work over a few config values, and the bean's use is asserted on the register door's
 * answer.
 */
class RunnerAddressesTest {

  private static RunnerAddresses addresses(String domain) {
    RunnerAddresses addresses = new RunnerAddresses();
    addresses.domain = Optional.ofNullable(domain);
    addresses.publicUrl = Optional.empty();
    addresses.tokenUrlOverride = Optional.empty();
    addresses.artifactsUrl = Optional.empty();
    return addresses;
  }

  @Test
  void theLiveDomainComposesTheEdgesNamesUnderThePlatformProjectWithNoEnvironmentLabel() {
    // QITS_DOMAIN=wohlben.eu is the live estate's: these are the three names measured answering there,
    // and ci.dev.qits.wohlben.eu is a 404 — the platform project is env-less.
    RunnerAddresses addresses = addresses("wohlben.eu");

    assertEquals("https://ci.qits.wohlben.eu", addresses.ciBase());
    assertEquals("wss://ci.qits.wohlben.eu/ci/runners/socket", addresses.socketUrl());
    assertEquals("https://idp.qits.wohlben.eu/idp/token", addresses.tokenUrl());
    assertEquals("registry.qits.wohlben.eu", addresses.registryHost());
    assertEquals(
        "registry.qits.wohlben.eu/qits/qits-ci-runner:2026.928.1", addresses.runnerImage("2026.928.1"));
    assertEquals("qits-platform", addresses.audience());
  }

  @Test
  void theDomainIsReadTrimmedAndLowerCased() {
    RunnerAddresses addresses = addresses("  Example.CO.uk. ");

    assertEquals("https://ci.qits.example.co.uk", addresses.ciBase());
  }

  @Test
  void eachOverrideReplacesItsDerivationAlone() {
    RunnerAddresses addresses = addresses("wohlben.eu");
    addresses.publicUrl = Optional.of(" https://ci.elsewhere.example.org/ ");
    addresses.tokenUrlOverride = Optional.of("https://login.example.org/idp/token/");
    addresses.artifactsUrl = Optional.of("https://artifacts.example.org/");

    assertEquals("https://ci.elsewhere.example.org", addresses.ciBase());
    assertEquals("wss://ci.elsewhere.example.org/ci/runners/socket", addresses.socketUrl());
    assertEquals("https://login.example.org/idp/token", addresses.tokenUrl());
    assertEquals("artifacts.example.org", addresses.registryHost(), "the override's authority");

    addresses.publicUrl = Optional.of("  ");
    addresses.tokenUrlOverride = Optional.of("");
    addresses.artifactsUrl = Optional.of(" ");
    assertEquals("https://ci.qits.wohlben.eu", addresses.ciBase(), "blank is unset");
    assertEquals("https://idp.qits.wohlben.eu/idp/token", addresses.tokenUrl());
    assertEquals("registry.qits.wohlben.eu", addresses.registryHost());
  }

  /**
   * qits-515: there is no internal fallback. With no public domain and no override every address is
   * refused, in a sentence naming the key to set — never a qits-net alias a runner cannot resolve.
   */
  @Test
  void withNoPublicDomainEveryAddressIsRefusedRatherThanAnInternalAlias() {
    for (String domain : new String[] {null, "", "  ", "localhost"}) {
      RunnerAddresses addresses = addresses(domain);
      String what = "domain " + domain;

      for (java.util.function.Supplier<String> address :
          java.util.List.<java.util.function.Supplier<String>>of(
              addresses::ciBase,
              addresses::socketUrl,
              addresses::tokenUrl,
              addresses::registryHost,
              () -> addresses.runnerImage("2026.928.1"))) {
        RunnerAddresses.UnconfiguredException refused =
            assertThrows(RunnerAddresses.UnconfiguredException.class, address::get, what);
        assertTrue(refused.getMessage().contains("QITS_DOMAIN"), refused.getMessage());
        assertFalse(refused.getMessage().contains("-qits-"), refused.getMessage());
      }
      assertFalse(addresses.edgeAvailable(), what);
      assertTrue(addresses.edgeOrigins().isEmpty(), what);
    }
  }

  @Test
  void anOverrideStillAnswersWithNoPublicDomain() {
    RunnerAddresses addresses = addresses(null);
    addresses.publicUrl = Optional.of("https://ci.elsewhere.example.org");
    addresses.tokenUrlOverride = Optional.of("https://login.example.org/idp/token");
    addresses.artifactsUrl = Optional.of("https://artifacts.example.org");

    assertEquals("https://ci.elsewhere.example.org", addresses.ciBase());
    assertEquals("https://login.example.org/idp/token", addresses.tokenUrl());
    assertEquals("artifacts.example.org", addresses.registryHost());
    // The overrides re-point what a RUNNER is told; a step's addresses come from the domain alone.
    assertTrue(addresses.edgeOrigins().isEmpty());
  }
}
