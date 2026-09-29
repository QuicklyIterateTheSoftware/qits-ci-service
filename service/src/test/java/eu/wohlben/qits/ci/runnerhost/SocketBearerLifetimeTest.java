package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonSocket;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.websockets.next.WebSocket;
import java.security.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which identities lose their expiry. What that buys — websockets-next leaving the connection open
 * past the bearer's {@code exp}, where it otherwise closes it 1008 "Authentication expired" — is
 * Quarkus' behaviour and was measured against quarkus-oidc 3.34.6 with a real signed token (qits-545):
 * the same token opened two sockets, the one this augmentor covers outlived it and still answered a
 * frame, the other was closed at {@code exp}.
 */
class SocketBearerLifetimeTest {

  private static final Principal RUNNER = () -> "ci-runner-client";

  private static SecurityIdentity bearer() {
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(RUNNER)
        .addRole(CiRunnerSocket.RUNNER_ROLE)
        .addAttribute(SocketBearerLifetime.EXPIRE_TIME, 1_790_000_000L)
        .addAttribute("tenant-id", "Default")
        .build();
  }

  @Test
  void bothControlSocketsAreCoveredByTheirOwnLiterals() {
    assertEquals(
        Set.of(
            CiRunnerSocket.class.getAnnotation(WebSocket.class).path(),
            CiDaemonSocket.class.getAnnotation(WebSocket.class).path()),
        SocketBearerLifetime.SOCKET_PATHS);
  }

  @Test
  void anUpgradeOfEitherSocketHoldsItsIdentityWithoutAnExpiry() {
    for (String path : SocketBearerLifetime.SOCKET_PATHS) {
      SecurityIdentity held = SocketBearerLifetime.forPath(bearer(), path);
      assertNull(held.getAttribute(SocketBearerLifetime.EXPIRE_TIME), path);
      assertEquals("Default", held.getAttribute("tenant-id"), "every other attribute is kept");
      assertEquals(RUNNER, held.getPrincipal());
      assertTrue(held.hasRole(CiRunnerSocket.RUNNER_ROLE));
    }
  }

  @Test
  void everyOtherRequestKeepsItsIdentityUntouched() {
    SecurityIdentity identity = bearer();
    assertSame(identity, SocketBearerLifetime.forPath(identity, "/ci/api/runs/active"));
    assertSame(identity, SocketBearerLifetime.forPath(identity, null));
  }
}
