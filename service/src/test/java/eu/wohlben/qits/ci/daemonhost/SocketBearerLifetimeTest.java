package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  private static final Principal DAEMON = () -> "tok-ci-run-run-1-1";

  private static SecurityIdentity bearer() {
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(DAEMON)
        .addRole(CiDaemonSocket.RUN_ROLE)
        .addAttribute(SocketBearerLifetime.EXPIRE_TIME, 1_790_000_000L)
        .addAttribute("tenant-id", "Default")
        .build();
  }

  @Test
  void theControlSocketIsCoveredByItsOwnLiteral() {
    assertEquals(
        Set.of(CiDaemonSocket.class.getAnnotation(WebSocket.class).path()),
        SocketBearerLifetime.SOCKET_PATHS);
  }

  @Test
  void anUpgradeOfTheSocketHoldsItsIdentityWithoutAnExpiry() {
    for (String path : SocketBearerLifetime.SOCKET_PATHS) {
      SecurityIdentity held = SocketBearerLifetime.forPath(bearer(), path);
      assertNull(held.getAttribute(SocketBearerLifetime.EXPIRE_TIME), path);
      assertEquals("Default", held.getAttribute("tenant-id"), "every other attribute is kept");
      assertEquals(DAEMON, held.getPrincipal());
      assertTrue(held.hasRole(CiDaemonSocket.RUN_ROLE));
    }
  }

  @Test
  void everyOtherRequestKeepsItsIdentityUntouched() {
    SecurityIdentity identity = bearer();
    assertSame(identity, SocketBearerLifetime.forPath(identity, "/ci/api/runs/active"));
    assertSame(identity, SocketBearerLifetime.forPath(identity, null));
  }
}
