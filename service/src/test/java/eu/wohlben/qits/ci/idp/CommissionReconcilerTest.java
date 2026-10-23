package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
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

  private static IdpCommissioner.LiveToken token(
      String tokenId, String kind, String contextId, Instant createdAt) {
    return new IdpCommissioner.LiveToken(tokenId, "tok-" + tokenId, kind, contextId, createdAt);
  }

  @Test
  public void aRunsTokenIsReapedOnceItsRunIsOverAndNotBefore() {
    Instant now = Instant.parse("2026-09-28T12:00:00Z");
    Instant old = now.minus(Duration.ofMinutes(30));

    int reaped =
        reconciler.reapRunTokens(
            List.of(
                token("tok-dead", "ci-run", "run-dead", old),
                token("tok-live", "ci-run", "run-live", old),
                // Younger than the grace: its run may simply not have launched a step yet.
                token("tok-young", "ci-run", "run-young", now.minus(Duration.ofMinutes(2))),
                // Another kind of token is not a run's, and this sweep never touches it.
                token("tok-reg", "ci-runner-registration", "runner-1", old)),
            Set.of("run-live"),
            now);

    // The whole feature in one assertion: what a killed process left behind goes, what a running
    // build is pushing with stays.
    assertEquals(1, reaped);
    assertEquals(List.of("tok-dead"), idp.deletedTokens);
  }

  @Test
  public void aTokenThisProcessIsHoldingIsSparedAndAnUnreadRunTableReapsNothing() {
    // The window between a run's row going terminal and its runClosed. The row says the run is over
    // and the credential is still in use, so memory outranks the table here.
    IdpCommissioner.CommissionedToken held =
        reconciler.commissions.forRun("run-finishing", Map.of());
    Instant now = Instant.now().plus(Duration.ofHours(1));

    assertEquals(
        0,
        reconciler.reapRunTokens(
            List.of(token(held.tokenId(), "ci-run", "run-finishing", Instant.EPOCH)),
            Set.of(),
            now));
    assertEquals(
        0,
        reconciler.reapRunTokens(
            List.of(token("tok-dead", "ci-run", "run-dead", Instant.EPOCH)), null, now));
    assertEquals(List.of(), idp.deletedTokens);
  }

  /**
   * qits-515: the reconciler stops looking for {@code ci-run} CLIENTS. A run commissions a token and
   * never a client, so a row of that kind in the listing — whatever a qits-ci before this one left —
   * is none of this sweep's business. Since epic qits-1481 a pass reaps no client at all: the
   * retired runner's clients are {@code LegacyRunnerClients}' one-shot.
   */
  @Test
  public void aCiRunClientInTheListingIsNoLongerReaped() {
    idp.listingBody =
        "[{\"clientId\":\"client-dead\",\"owner\":\"dev-qits-ci\",\"contextKind\":\"ci-run\","
            + "\"contextId\":\"run-dead\",\"createdAt\":\"2026-08-14T10:00:00Z\"}]";

    // Read off the wire as it always was...
    assertEquals(
        List.of(row("client-dead", "ci-run", "run-dead")), reconciler.idp.live().orElseThrow());
    // ...and left alone by the whole pass, which does not even read the run table for it.
    reconciler.reconcile();
    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aListingNobodyCouldReadReapsNothing() {
    // "Nothing was learned" must never read as "no run owns anything" — that would delete every live
    // build's credential the first time qits-idp answered badly. An unreadable listing is an empty
    // Optional, and reconcile returns before it even asks which runs are live.
    idp.listingBody = "{\"not\":\"an array\"}";
    idp.tokenListingBody = "{\"not\":\"an array\"}";
    reconciler.reconcile();
    assertEquals(List.of(), idp.deleted);
    assertEquals(List.of(), idp.deletedTokens);
    assertTrue(idp.listings.get() > 0, "it did ask");
  }

  private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

  @Test
  public void aRealTokenListingIsReadOffTheWireAndAnUnreadableOneReapsNothing() {
    idp.tokenListingBody =
        "[{\"tokenId\":\"token-dead\",\"subject\":\"tok-ci-run-run-gone-1\","
            + "\"owner\":\"dev-qits-ci\",\"contextKind\":\"ci-run\","
            + "\"contextId\":\"run-gone\",\"claims\":{},\"gitRefs\":[],"
            + "\"createdAt\":\"2026-09-27T09:00:00Z\"}]";

    List<IdpCommissioner.LiveToken> live = reconciler.idp.liveTokens().orElseThrow();

    assertEquals(
        List.of(
            new IdpCommissioner.LiveToken(
                "token-dead",
                "tok-ci-run-run-gone-1",
                "ci-run",
                "run-gone",
                Instant.parse("2026-09-27T09:00:00Z"))),
        live);
    assertEquals(1, reconciler.reapRunTokens(live, Set.of(), NOW));
    assertEquals(List.of("token-dead"), idp.deletedTokens);

    idp.tokenListingBody = "{\"not\":\"an array\"}";
    assertTrue(reconciler.idp.liveTokens().isEmpty(), "nothing learned is not an empty list");
  }

  @Test
  public void aCommissionedTokenAndItsDeletionGoToTheTokenSurface() {
    IdpCommissioner.CommissionedToken minted =
        reconciler.idp.commissionToken("ci-run", "run-9", List.of());

    assertEquals("token-1", minted.tokenId());
    assertEquals("qits_tok_stub-1", minted.token());
    assertEquals("tok-ci-run-run-9-1", minted.subject());
    // A record's toString names every component; this one must not name the value.
    assertTrue(!minted.toString().contains("qits_tok_"), minted.toString());
    assertEquals(
        List.of("{\"contextKind\":\"ci-run\",\"contextId\":\"run-9\",\"gitRefs\":[]}"),
        idp.postedTokens);
    assertEquals(List.of(), idp.posted, "a token is not a client");

    assertTrue(reconciler.idp.deleteToken("token-1"));
    assertEquals(List.of("token-1"), idp.deletedTokens);
    assertEquals(List.of(), idp.deleted);

    idp.tokenMintStatus = 403;
    assertThrows(
        IdpCommissioner.CommissionFailedException.class,
        () -> reconciler.idp.commissionToken("ci-run", "run-9", List.of()));
  }
}
