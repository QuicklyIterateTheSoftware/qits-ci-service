package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The addresses a runner is told: the public edge names composed from the platform's domain, each
 * override, the internal fallback where no public domain is known, and the qits-net addresses an
 * INTERNAL runner is told whatever the domain. Plain JUnit: the composition
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

  @Test
  void anEdgeRunnerIsToldExactlyWhatThePlaneLessCompositionAnswers() {
    // With a domain, with every override, and with neither: the EDGE answer is the plane-less one.
    RunnerAddresses derived = addresses("wohlben.eu");
    RunnerAddresses overridden = addresses("wohlben.eu");
    overridden.publicUrl = Optional.of(" https://ci.elsewhere.example.org/ ");
    overridden.tokenUrlOverride = Optional.of("https://login.example.org/idp/token/");
    overridden.artifactsUrl = Optional.of("https://artifacts.example.org/");
    for (RunnerAddresses addresses : new RunnerAddresses[] {derived, overridden, addresses(null)}) {
      assertEquals(addresses.ciBase(), addresses.ciBase(CiRunnerPlane.EDGE));
      assertEquals(addresses.socketUrl(), addresses.socketUrl(CiRunnerPlane.EDGE));
      assertEquals(addresses.tokenUrl(), addresses.tokenUrl(CiRunnerPlane.EDGE));
      assertEquals(addresses.registryHost(), addresses.registryHost(CiRunnerPlane.EDGE));
      assertEquals(
          addresses.runnerImage("2026.928.1"),
          addresses.runnerImage(CiRunnerPlane.EDGE, "2026.928.1"));
    }

    assertEquals("https://ci.qits.wohlben.eu", derived.ciBase(CiRunnerPlane.EDGE));
    assertEquals(
        "wss://ci.qits.wohlben.eu/ci/runners/socket", derived.socketUrl(CiRunnerPlane.EDGE));
    assertEquals("https://idp.qits.wohlben.eu/idp/token", derived.tokenUrl(CiRunnerPlane.EDGE));
    assertEquals("registry.qits.wohlben.eu", derived.registryHost(CiRunnerPlane.EDGE));
    assertEquals(
        "registry.qits.wohlben.eu/qits/qits-ci-runner:2026.928.1",
        derived.runnerImage(CiRunnerPlane.EDGE, "2026.928.1"));
    assertEquals("https://ci.elsewhere.example.org", overridden.ciBase(CiRunnerPlane.EDGE));
    assertEquals("https://login.example.org/idp/token", overridden.tokenUrl(CiRunnerPlane.EDGE));
    assertEquals("artifacts.example.org", overridden.registryHost(CiRunnerPlane.EDGE));
  }

  @Test
  void anInternalRunnerIsToldTheQitsNetAddressesWhateverTheDomainAndTheOverrides() {
    // A platform being bootstrapped states its domain before any edge answers under it.
    for (String domain : new String[] {"wohlben.eu", null, "localhost"}) {
      RunnerAddresses addresses = addresses(domain);
      addresses.publicUrl = Optional.of("https://ci.elsewhere.example.org/");
      addresses.tokenUrlOverride = Optional.of("https://login.example.org/idp/token/");
      addresses.artifactsUrl = Optional.of("https://artifacts.example.org/");

      assertEquals(
          "http://dev-qits-ci:8080", addresses.ciBase(CiRunnerPlane.INTERNAL), "domain " + domain);
      assertEquals(
          "ws://dev-qits-ci:8080/ci/runners/socket", addresses.socketUrl(CiRunnerPlane.INTERNAL));
      assertEquals(
          "http://dev-qits-platform-idp:8080/idp/token", addresses.tokenUrl(CiRunnerPlane.INTERNAL));
      // The platform host's own docker pulls it, under the name it pulls every step image by.
      assertEquals("registry.dev.localhost:8080", addresses.registryHost(CiRunnerPlane.INTERNAL));
      assertEquals(
          "registry.dev.localhost:8080/qits/qits-ci-runner:2026.928.1",
          addresses.runnerImage(CiRunnerPlane.INTERNAL, "2026.928.1"));
    }
  }
}
