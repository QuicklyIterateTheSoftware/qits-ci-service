package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The addresses a runner is told: the public edge names composed from the platform's domain, each
 * override, and the internal fallback where no public domain is known. Plain JUnit: the composition
 * is string work over a few config values, and the bean's use is asserted on the register door's
 * answer.
 */
class RunnerAddressesTest {

  private static RunnerAddresses addresses(String domain) {
    RunnerAddresses addresses = new RunnerAddresses();
    addresses.domain = Optional.ofNullable(domain);
    addresses.internalUrl = "http://dev-qits-ci:8080/";
    addresses.publicUrl = Optional.empty();
    addresses.idpUrl = "http://dev-qits-platform-idp:8080/idp/";
    addresses.tokenUrlOverride = Optional.empty();
    addresses.artifactsInternalUrl = "http://dev-qits-artifacts:8080/";
    addresses.artifactsUrl = Optional.empty();
    addresses.registryInternalHost = "registry.dev.localhost:8080";
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
    assertEquals("https://registry.qits.wohlben.eu", addresses.artifactsBase());
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
    assertEquals("https://artifacts.example.org", addresses.artifactsBase());
    assertEquals("artifacts.example.org", addresses.registryHost(), "the same store's authority");

    addresses.publicUrl = Optional.of("  ");
    addresses.tokenUrlOverride = Optional.of("");
    addresses.artifactsUrl = Optional.of(" ");
    assertEquals("https://ci.qits.wohlben.eu", addresses.ciBase(), "blank is unset");
    assertEquals("https://idp.qits.wohlben.eu/idp/token", addresses.tokenUrl());
    assertEquals("https://registry.qits.wohlben.eu", addresses.artifactsBase());
  }

  @Test
  void withNoPublicDomainTheInternalAliasesAreTheFallback() {
    for (String domain : new String[] {null, "", "  ", "localhost"}) {
      RunnerAddresses addresses = addresses(domain);

      assertEquals("http://dev-qits-ci:8080", addresses.ciBase(), "domain " + domain);
      assertEquals("ws://dev-qits-ci:8080/ci/runners/socket", addresses.socketUrl());
      assertEquals("http://dev-qits-platform-idp:8080/idp/token", addresses.tokenUrl());
      assertEquals("http://dev-qits-artifacts:8080", addresses.artifactsBase());
      // The image is pulled by a docker, which resolves the registry-host key and no qits-net alias.
      assertEquals("registry.dev.localhost:8080", addresses.registryHost());
    }
  }
}
