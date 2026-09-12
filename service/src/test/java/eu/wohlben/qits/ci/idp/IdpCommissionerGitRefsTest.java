package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The commission body with and without a Git scope, and the fallback for an older qits-idp. */
public class IdpCommissionerGitRefsTest {

  private static final Duration PATIENCE = Duration.ofMillis(200);

  private StubIdp stub;

  @BeforeEach
  void start() {
    stub = new StubIdp();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  public void theBodyStatesTheScopeOnlyWhenOneIsGiven() {
    assertEquals(
        "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\"}",
        IdpCommissioner.commissionBody("ci-run", "run-1", null));
    assertEquals(
        "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\",\"gitRefs\":[]}",
        IdpCommissioner.commissionBody("ci-run", "run-1", List.of()));
    assertEquals(
        "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\",\"gitRefs\":"
            + "[\"refs/heads/maintenance/dependencies\",\"refs/heads/external/*\"]}",
        IdpCommissioner.commissionBody(
            "ci-run", "run-1", List.of("refs/heads/maintenance/dependencies", "refs/heads/external/*")));
  }

  @Test
  public void aScopedCommissionIsPostedAsIs() {
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    IdpCommissioner.Commission minted =
        idp.commission("ci-run", "run-1", List.of("refs/heads/maintenance/dependencies"));

    assertEquals("run-client-1", minted.clientId());
    assertEquals(
        List.of(
            "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\","
                + "\"gitRefs\":[\"refs/heads/maintenance/dependencies\"]}"),
        stub.posted);
    assertFalse(idp.warnedScopeRefused.get());
  }

  @Test
  public void anOlderIdpGetsTheUnscopedCommissionAndOneWarning() {
    stub.refuseGitRefs = true;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    IdpCommissioner.Commission first = idp.commission("ci-run", "run-1", List.of());

    assertEquals("run-client-1", first.clientId());
    assertEquals(
        List.of(
            "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\",\"gitRefs\":[]}",
            "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\"}"),
        stub.posted);
    assertTrue(idp.warnedScopeRefused.get());

    // The next commission states the scope again first: the idp may have been upgraded since.
    IdpCommissioner.Commission second = idp.commission("ci-run", "run-2", List.of());

    assertEquals("run-client-2", second.clientId());
    assertEquals(4, stub.posted.size());
    assertTrue(stub.posted.get(2).contains("\"gitRefs\":[]"), stub.posted.get(2));
    assertEquals("{\"contextKind\":\"ci-run\",\"contextId\":\"run-2\"}", stub.posted.get(3));
  }

  @Test
  public void aFourHundredOnTheUnscopedFormStillFailsAtOnce() {
    stub.mintStatus = 400;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    IdpCommissioner.CommissionFailedException failed =
        assertThrows(
            IdpCommissioner.CommissionFailedException.class,
            () -> idp.commission("ci-run", "run-1", List.of("refs/heads/maintenance/dependencies")));

    // Scoped, then unscoped, then stop: a 400 about the request is not held through.
    assertEquals(2, stub.posted.size());
    assertEquals("{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\"}", stub.posted.get(1));
    assertTrue(failed.getMessage().contains("400"), failed.getMessage());
  }

  @Test
  public void aCommissionThatStatesNoScopeIsNeverRetriedOnFourHundred() {
    stub.mintStatus = 400;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    assertThrows(
        IdpCommissioner.CommissionFailedException.class,
        () -> idp.commission("ci-run", "run-1", null));

    assertEquals(1, stub.posted.size());
    assertFalse(idp.warnedScopeRefused.get());
  }
}
