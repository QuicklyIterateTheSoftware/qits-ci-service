package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The three addresses a runner is told, from the two ways a deployment can state its CI base. Plain
 * JUnit: the composition is string work over three config values, and the shipped values themselves
 * are asserted where they are used, on the register door's answer.
 */
class RunnerAddressesTest {

  private static RunnerAddresses addresses(String internal, String publicUrl) {
    RunnerAddresses addresses = new RunnerAddresses();
    addresses.internalUrl = internal;
    addresses.publicUrl = Optional.ofNullable(publicUrl);
    addresses.idpUrl = "http://dev-qits-platform-idp:8080/idp/";
    return addresses;
  }

  @Test
  void theInternalAliasIsTheBaseAndItsSocketIsPlainWs() {
    RunnerAddresses addresses = addresses("http://dev-qits-ci:8080/", null);

    assertEquals("http://dev-qits-ci:8080", addresses.ciBase());
    assertEquals("ws://dev-qits-ci:8080/ci/runners/socket", addresses.socketUrl());
    assertEquals("http://dev-qits-platform-idp:8080/idp/token", addresses.tokenUrl());
    assertEquals("qits-platform", addresses.audience());
  }

  @Test
  void aPublicUrlReplacesTheAliasAndAnHttpsBaseDialsWss() {
    RunnerAddresses addresses = addresses("http://dev-qits-ci:8080", " https://ci.dev.example.org ");

    assertEquals("https://ci.dev.example.org", addresses.ciBase());
    assertEquals("wss://ci.dev.example.org/ci/runners/socket", addresses.socketUrl());
  }

  @Test
  void aBlankPublicUrlIsUnset() {
    assertEquals("http://dev-qits-ci:8080", addresses("http://dev-qits-ci:8080", "  ").ciBase());
  }
}
