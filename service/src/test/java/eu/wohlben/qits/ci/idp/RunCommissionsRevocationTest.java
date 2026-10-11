package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The old credentials of an inherited run (qits-1192): the {@code ci-run} tokens a previous process
 * commissioned for a run and died holding are revoked when the run stops needing them — whatever
 * this process holds for the run itself is spared — and a listing that cannot be read leaves the
 * run owed, revoked by the next reconciliation pass rather than never.
 */
public class RunCommissionsRevocationTest {

  private StubIdp idp;
  private RunCommissions commissions;

  @BeforeEach
  void wire() {
    idp = new StubIdp();
    commissions = idp.runCommissions(Duration.ofMillis(200));
  }

  @AfterEach
  void stop() {
    idp.close();
  }

  private static String token(String id, String subject, String kind, String contextId) {
    return "{\"tokenId\":\""
        + id
        + "\",\"subject\":\""
        + subject
        + "\",\"contextKind\":\""
        + kind
        + "\",\"contextId\":\""
        + contextId
        + "\",\"createdAt\":\"2026-10-11T12:00:00Z\"}";
  }

  @Test
  public void revokesEveryTokenOfTheRunItDoesNotHoldAndNothingElse() {
    // This process's own token for the run: commissioned here, so held, so spared.
    String held = commissions.forRun("run-a", Map.of()).tokenId();
    idp.tokenListingBody =
        "["
            + String.join(
                ",",
                token(held, "sub-held", "ci-run", "run-a"),
                token("tok-old", "sub-old", "ci-run", "run-a"),
                token("tok-other-run", "sub-b", "ci-run", "run-b"),
                token("tok-runner", "sub-r", "ci-runner-registration", "run-a"))
            + "]";

    assertEquals(1, commissions.revokeOrphaned("run-a"));
    assertEquals(List.of("tok-old"), idp.deletedTokens);
  }

  @Test
  public void anUnreadableListingLeavesTheRunOwedUntilAPassCanRead() {
    idp.tokenListingBody = "not a listing";
    assertEquals(0, commissions.revokeOrphaned("run-a"));
    assertTrue(idp.deletedTokens.isEmpty());

    // The reconciliation's next pass, with qits-idp readable again.
    idp.tokenListingBody = "[" + token("tok-old", "sub-old", "ci-run", "run-a") + "]";
    commissions.revokeOwed();
    assertEquals(List.of("tok-old"), idp.deletedTokens);

    // Owed once, revoked once.
    commissions.revokeOwed();
    assertEquals(List.of("tok-old"), idp.deletedTokens);
  }

  @Test
  public void liveSubjectsAreTheRunsOwnAndUnknownIsNeverNone() {
    idp.tokenListingBody =
        "["
            + String.join(
                ",",
                token("t1", "sub-a", "ci-run", "run-a"),
                token("t2", "sub-b", "ci-run", "run-b"),
                token("t3", "sub-x", "ci-runner-registration", "run-a"))
            + "]";
    assertEquals(Optional.of(Set.of("sub-a")), commissions.liveSubjectsOf("run-a"));

    idp.tokenListingBody = "not a listing";
    assertEquals(Optional.empty(), commissions.liveSubjectsOf("run-a"));
    assertEquals(Optional.empty(), StubIdp.disabledCommissions().liveSubjectsOf("run-a"));
  }
}
