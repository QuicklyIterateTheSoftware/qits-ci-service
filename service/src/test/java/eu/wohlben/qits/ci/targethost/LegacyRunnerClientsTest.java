package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.StubIdp;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1592 AC3, the service side: the CI cutover only GIVES BACK credentials.
 *
 * <p>On 2026-10-24 the workspace cutover deleted the old runner's qits-idp client, and the old runner
 * read the revoked credential as "deleted" and purged every container and volume it held. The fixed
 * runner (qits-ci-runner-daemon 2026.1025.102402 or newer) stops under {@code restart=no} and removes
 * nothing; what this service must hold up its end of is that {@link LegacyRunnerClients} deletes
 * qits-idp clients and tokens of the two retired kinds, {@code ci-runner} and {@code
 * ci-runner-registration}, and nothing else — no other kind's credential, nothing minted, and no
 * word to any runner: no {@code Retire{DELETED}}, no 1008 {@code RUNNER_DELETED} close, no
 * decommission. The last is proved structurally, because the socket those would travel on no longer
 * exists in this service.
 */
class LegacyRunnerClientsTest {

  private StubIdp idp;

  private LegacyRunnerClients legacy;

  @BeforeEach
  void wire() {
    idp = new StubIdp();
    legacy = new LegacyRunnerClients();
    legacy.idp = idp.commissioner(Duration.ofMillis(200));
  }

  @AfterEach
  void stop() {
    idp.close();
  }

  private static String client(String clientId, String kind, String contextId) {
    return "{\"clientId\":\""
        + clientId
        + "\",\"owner\":\"dev-qits-ci\",\"contextKind\":\""
        + kind
        + "\",\"contextId\":\""
        + contextId
        + "\",\"createdAt\":\"2026-10-20T10:00:00Z\"}";
  }

  private static String token(String tokenId, String kind, String contextId) {
    return "{\"tokenId\":\""
        + tokenId
        + "\",\"subject\":\"tok-"
        + kind
        + "-"
        + contextId
        + "\",\"owner\":\"dev-qits-ci\",\"contextKind\":\""
        + kind
        + "\",\"contextId\":\""
        + contextId
        + "\",\"claims\":{},\"gitRefs\":[],\"createdAt\":\"2026-10-20T10:00:00Z\"}";
  }

  @Test
  void itDeletesOnlyTheRetiredKindsCredentialsAndMintsNothing() {
    idp.listingBody =
        "["
            + String.join(
                ",",
                client("client-runner-edge-1", "ci-runner", "runner-edge-1"),
                client("client-runner-registration", "ci-runner-registration", "runner-edge-2"),
                client("client-run", "ci-run", "run-1"),
                client("client-workspace", "workspace-agent", "workspace-1"))
            + "]";
    idp.tokenListingBody =
        "["
            + String.join(
                ",",
                token("token-registration", "ci-runner-registration", "runner-edge-1"),
                token("token-runner", "ci-runner", "runner-edge-2"),
                token("token-run", "ci-run", "run-1"),
                token("token-lease", "ci-slot", "lease-1"))
            + "]";

    int given = legacy.giveBack();

    assertEquals(4, given);
    assertEquals(
        List.of("client-runner-edge-1", "client-runner-registration"), List.copyOf(idp.deleted));
    assertEquals(List.of("token-registration", "token-runner"), List.copyOf(idp.deletedTokens));
    // Gives back, never commissions: nothing is minted in the same breath.
    assertEquals(List.of(), List.copyOf(idp.posted));
    assertEquals(List.of(), List.copyOf(idp.postedTokens));
  }

  @Test
  void itGivesBackNothingWhenNoRetiredCredentialIsLeft() {
    idp.listingBody = "[" + client("client-run", "ci-run", "run-1") + "]";
    idp.tokenListingBody = "[" + token("token-run", "ci-run", "run-1") + "]";

    assertEquals(0, legacy.giveBack());
    assertEquals(List.of(), List.copyOf(idp.deleted));
    assertEquals(List.of(), List.copyOf(idp.deletedTokens));
  }

  @Test
  void anUnreadableListingDeletesNothing() {
    idp.listingBody = "{\"not\":\"an array\"}";
    idp.tokenListingBody = "{\"not\":\"an array\"}";

    assertEquals(0, legacy.giveBack());
    assertEquals(List.of(), List.copyOf(idp.deleted));
    assertEquals(List.of(), List.copyOf(idp.deletedTokens));
    assertTrue(idp.listings.get() > 0, "it did ask");
  }

  /**
   * Its one collaborator is qits-idp: the one-shot holds no handle on a runner, a socket or a
   * registry through which a decommission could go.
   */
  @Test
  void itsOnlyCollaboratorIsTheIdpCommissioner() {
    List<Class<?>> collaborators =
        Arrays.stream(LegacyRunnerClients.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .<Class<?>>map(Field::getType)
            .toList();

    assertEquals(List.of(IdpCommissioner.class), collaborators);
  }

  /**
   * And there is no runner to speak to: the runner control socket, its registry and the wire
   * contract that carried {@code Retire} and the {@code RUNNER_DELETED} close are gone from this
   * service's classpath, so neither can be sent from here at all.
   */
  @Test
  void noRunnerSocketOrRunnerProtocolIsLeftToSendARetireOrA1008Close() {
    for (String gone :
        List.of(
            "eu.wohlben.qits.ci.runnerhost.CiRunnerSocket",
            "eu.wohlben.qits.ci.runnerhost.CiRunnerRegistry",
            "eu.wohlben.qits.ci.api.CiRunnerController",
            "eu.wohlben.qits.cirunner.protocol.Retire",
            "eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol")) {
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName(gone, false, LegacyRunnerClientsTest.class.getClassLoader()),
          gone + " must not be on the classpath");
    }
  }
}
