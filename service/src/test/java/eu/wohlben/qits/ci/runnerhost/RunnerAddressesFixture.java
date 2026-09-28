package eu.wohlben.qits.ci.runnerhost;

import java.util.Optional;

/**
 * A {@link RunnerAddresses} as a deployment with a stated domain composes it, for a suite that
 * installs it over the bean with {@code QuarkusMock} — the suite itself pins the domain empty, and
 * one Quarkus start per domain would be a test profile for a string.
 */
public final class RunnerAddressesFixture {

  private RunnerAddressesFixture() {}

  /** The addresses of a qits-ci whose {@code QITS_DOMAIN} is {@code domain}, and no override. */
  public static RunnerAddresses withDomain(String domain) {
    RunnerAddresses addresses = new RunnerAddresses();
    addresses.domain = Optional.of(domain);
    addresses.internalUrl = "http://dev-qits-ci:8080";
    addresses.publicUrl = Optional.empty();
    addresses.idpUrl = "http://dev-qits-platform-idp:8080/idp";
    addresses.tokenUrlOverride = Optional.empty();
    addresses.artifactsInternalUrl = "http://dev-qits-artifacts:8080";
    addresses.artifactsUrl = Optional.empty();
    return addresses;
  }
}
