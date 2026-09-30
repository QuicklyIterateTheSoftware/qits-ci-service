package eu.wohlben.qits.ci.runnerhost;

import java.util.Optional;

/**
 * A {@link RunnerAddresses} as a deployment with a stated domain composes it — for the plain-JUnit
 * suites, and for a suite that installs one over the bean with {@code QuarkusMock} to stand on a
 * domain other than the suite's own (no domain at all among them): one Quarkus start per domain
 * would be a test profile for a string.
 */
public final class RunnerAddressesFixture {

  private RunnerAddressesFixture() {}

  /**
   * The addresses of a qits-ci whose {@code QITS_DOMAIN} is {@code domain} — null or blank for one
   * that states none — and no override.
   */
  public static RunnerAddresses withDomain(String domain) {
    RunnerAddresses addresses = new RunnerAddresses();
    addresses.domain = Optional.ofNullable(domain);
    addresses.publicUrl = Optional.empty();
    addresses.tokenUrlOverride = Optional.empty();
    addresses.artifactsUrl = Optional.empty();
    return addresses;
  }
}
