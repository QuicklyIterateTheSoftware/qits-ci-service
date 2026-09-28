package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The leftovers sweep: what qits-idp still holds, minus what a run still owns.
 *
 * <p>Driven through {@link CommissionReconciler#reap} with the run ids handed in, which is the seam
 * the boot pass and the schedule both reach through — the read behind them is one Panache query and
 * belongs to the run table's own tests. What is qits-ci's own here is the <b>predicate</b>: which
 * rows are this feature's, which are still owed, and what a listing nobody could read reaps.
 */
public class CommissionReconcilerTest {

  private StubIdp idp;
  private CommissionReconciler reconciler;

  @BeforeEach
  void wire() {
    idp = new StubIdp();
    reconciler = new CommissionReconciler();
    reconciler.idp = idp.commissioner(Duration.ofMillis(200));
    reconciler.commissions = idp.runCommissions(Duration.ofMillis(200));
  }

  @AfterEach
  void stop() {
    idp.close();
  }

  private static IdpCommissioner.LiveClient row(String clientId, String kind, String contextId) {
    return new IdpCommissioner.LiveClient(clientId, kind, contextId);
  }

  @Test
  public void aCredentialWhoseRunIsOverIsReapedAndALiveRunsIsLeftAlone() {
    int reaped =
        reconciler.reap(
            List.of(row("client-dead", "ci-run", "run-dead"), row("client-live", "ci-run", "run-live")),
            Set.of("run-live"));

    // The whole feature in one assertion: what a killed process left behind goes, what a running
    // build is pushing with stays.
    assertEquals(1, reaped);
    assertEquals(List.of("client-dead"), idp.deleted);
  }

  @Test
  public void aCommissionThisProcessIsHoldingIsSparedEvenWithNoRunRowLeft() {
    // The window between a run's row going terminal and its runClosed. The row says the run is over
    // and the credential is still in use, so memory outranks the table here.
    IdpCommissioner.Commission held =
        reconciler.commissions.forRun("run-finishing", java.util.Map.of());

    int reaped = reconciler.reap(List.of(row(held.clientId(), "ci-run", "run-finishing")), Set.of());

    assertEquals(0, reaped);
    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aCommissionOfAnotherContextKindIsNoneOfThisSweepsBusiness() {
    // The listing is this owner's whole set, and qits-ci may one day commission for something that
    // is not a run. A sweep that reaped by owner alone would take those with it.
    int reaped =
        reconciler.reap(List.of(row("client-workspace", "workspace", "ws-1")), Set.of());

    assertEquals(0, reaped);
    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aListingNobodyCouldReadReapsNothing() {
    // "Nothing was learned" must never read as "no run owns anything" — that would delete every live
    // build's credential the first time qits-idp answered badly. An unreadable listing is an empty
    // Optional, and reconcile returns before it even asks which runs are live.
    idp.listingBody = "{\"not\":\"an array\"}";
    reconciler.reconcile();
    assertEquals(List.of(), idp.deleted);
    assertTrue(idp.listings.get() > 0, "it did ask");

    // And the same for a run table that could not be read: a null id set reaps nothing.
    assertEquals(0, reconciler.reap(List.of(row("client-dead", "ci-run", "run-dead")), null));
    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aRealListingIsReadOffTheWireAndThenReaped() {
    idp.listingBody =
        "[{\"clientId\":\"client-dead\",\"owner\":\"dev-qits-ci\",\"contextKind\":\"ci-run\","
            + "\"contextId\":\"run-dead\",\"createdAt\":\"2026-08-14T10:00:00Z\"}]";

    List<IdpCommissioner.LiveClient> live = reconciler.idp.live().orElseThrow();

    assertEquals(List.of(row("client-dead", "ci-run", "run-dead")), live);
    assertEquals(1, reconciler.reap(live, Set.of("run-other")));
    assertEquals(List.of("client-dead"), idp.deleted);
  }

  // --- runners ------------------------------------------------------------------------------------

  private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

  private static final Instant OLD = NOW.minus(Duration.ofHours(2));

  private static CommissionReconciler.RunnerCredentials unregistered(String tokenId) {
    return new CommissionReconciler.RunnerCredentials(null, tokenId);
  }

  private static CommissionReconciler.RunnerCredentials registered(String clientId, String tokenId) {
    return new CommissionReconciler.RunnerCredentials(clientId, tokenId);
  }

  private static IdpCommissioner.LiveToken token(String id, String runner, Instant createdAt) {
    return new IdpCommissioner.LiveToken(
        id, "tok-ci-runner-registration-" + runner, "ci-runner-registration", runner, createdAt);
  }

  @Test
  public void aRunnerClientIsReapedWhenItsRunnerIsGoneOrNamesAnotherClient() {
    Map<String, CommissionReconciler.RunnerCredentials> rows = new HashMap<>();
    rows.put("runner-live", registered("client-live", null));
    rows.put("runner-registering", unregistered("token-r"));
    rows.put("runner-reregistered", registered("client-winner", null));

    int reaped =
        reconciler.reapRunnerClients(
            List.of(
                row("client-live", "ci-runner", "runner-live"),
                // A registration between its commission and its write: the row has no client yet.
                row("client-in-flight", "ci-runner", "runner-registering"),
                row("client-loser", "ci-runner", "runner-reregistered"),
                row("client-orphan", "ci-runner", "runner-deleted"),
                // Another kind is the run sweep's business, not this one's.
                row("client-run", "ci-run", "runner-deleted")),
            rows);

    assertEquals(2, reaped);
    assertEquals(List.of("client-loser", "client-orphan"), idp.deleted);
  }

  @Test
  public void aRegistrationTokenIsReapedWhenGoneSpentOrReplacedAndSparedWhileYoung() {
    Map<String, CommissionReconciler.RunnerCredentials> rows = new HashMap<>();
    rows.put("runner-waiting", unregistered("token-current"));
    rows.put("runner-rotated", unregistered("token-new"));
    rows.put("runner-registered", registered("client-x", "token-spent"));

    int reaped =
        reconciler.reapRunnerTokens(
            List.of(
                token("token-current", "runner-waiting", OLD),
                token("token-old", "runner-rotated", OLD),
                token("token-spent", "runner-registered", OLD),
                token("token-orphan", "runner-deleted", OLD),
                // Commissioned a moment ago, before the create's row could name it.
                token("token-young", "runner-being-created", NOW.minusSeconds(30)),
                // A listing with no instant reads as old, never as young.
                token("token-undated", "runner-deleted", null)),
            rows,
            NOW);

    assertEquals(4, reaped);
    assertEquals(List.of("token-old", "token-spent", "token-orphan", "token-undated"), idp.deletedTokens);
  }

  @Test
  public void anUnreadableRunnerTableReapsNoRunnerCredential() {
    assertEquals(
        0, reconciler.reapRunnerClients(List.of(row("c", "ci-runner", "r")), null));
    assertEquals(0, reconciler.reapRunnerTokens(List.of(token("t", "r", OLD)), null, NOW));
    assertEquals(List.of(), idp.deleted);
    assertEquals(List.of(), idp.deletedTokens);
  }

  @Test
  public void aRealTokenListingIsReadOffTheWireAndAnUnreadableOneReapsNothing() {
    idp.tokenListingBody =
        "[{\"tokenId\":\"token-dead\",\"subject\":\"tok-ci-runner-registration-r-1\","
            + "\"owner\":\"dev-qits-ci\",\"contextKind\":\"ci-runner-registration\","
            + "\"contextId\":\"runner-gone\",\"claims\":{},\"gitRefs\":[],"
            + "\"createdAt\":\"2026-09-27T09:00:00Z\"}]";

    List<IdpCommissioner.LiveToken> live = reconciler.idp.liveTokens().orElseThrow();

    assertEquals(
        List.of(
            new IdpCommissioner.LiveToken(
                "token-dead",
                "tok-ci-runner-registration-r-1",
                "ci-runner-registration",
                "runner-gone",
                Instant.parse("2026-09-27T09:00:00Z"))),
        live);
    assertEquals(1, reconciler.reapRunnerTokens(live, Map.of(), NOW));
    assertEquals(List.of("token-dead"), idp.deletedTokens);

    idp.tokenListingBody = "{\"not\":\"an array\"}";
    assertTrue(reconciler.idp.liveTokens().isEmpty(), "nothing learned is not an empty list");
  }

  @Test
  public void aCommissionedTokenAndItsDeletionGoToTheTokenSurface() {
    IdpCommissioner.CommissionedToken minted =
        reconciler.idp.commissionToken("ci-runner-registration", "runner-9", List.of());

    assertEquals("token-1", minted.tokenId());
    assertEquals("qits_tok_stub-1", minted.token());
    assertEquals("tok-ci-runner-registration-runner-9-1", minted.subject());
    // A record's toString names every component; this one must not name the value.
    assertTrue(!minted.toString().contains("qits_tok_"), minted.toString());
    assertEquals(
        List.of("{\"contextKind\":\"ci-runner-registration\",\"contextId\":\"runner-9\",\"gitRefs\":[]}"),
        idp.postedTokens);
    assertEquals(List.of(), idp.posted, "a token is not a client");

    assertTrue(reconciler.idp.deleteToken("token-1"));
    assertEquals(List.of("token-1"), idp.deletedTokens);
    assertEquals(List.of(), idp.deleted);

    idp.tokenMintStatus = 403;
    assertThrows(
        IdpCommissioner.CommissionFailedException.class,
        () -> reconciler.idp.commissionToken("ci-runner-registration", "runner-9", List.of()));
  }
}
