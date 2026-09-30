package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The commission body with and without a Git scope, and what a refused scope costs: the run may
 * push nothing. It never gets a credential with no scope.
 */
public class IdpCommissionerGitRefsTest {

  private static final Duration PATIENCE = Duration.ofMillis(200);

  private static final List<String> BUMP = List.of("refs/heads/maintenance/dependencies");

  private static final String SCOPED =
      "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\","
          + "\"gitRefs\":[\"refs/heads/maintenance/dependencies\"]}";

  private static final String PUSH_NOTHING =
      "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\",\"gitRefs\":[]}";

  private static final String UNSCOPED = "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\"}";

  private StubIdp stub;

  /** Held in a field: JUL holds loggers weakly, and a collected one would drop the handler. */
  private Logger log;

  /** Every ERROR the commissioner logged, as its message and its parameters. */
  private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
            // A printf-style record keeps its parameters apart from the format, so read both.
            errors.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
          }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void start() {
    stub = new StubIdp();
    log = Logger.getLogger(IdpCommissioner.class.getName());
    log.addHandler(capture);
  }

  @AfterEach
  void stop() {
    log.removeHandler(capture);
    stub.close();
  }

  @Test
  public void theBodyStatesTheScopeOnlyWhenOneIsGiven() {
    assertEquals(UNSCOPED, IdpCommissioner.commissionBody("ci-run", "run-1", null));
    assertEquals(PUSH_NOTHING, IdpCommissioner.commissionBody("ci-run", "run-1", List.of()));
    assertEquals(
        "{\"contextKind\":\"ci-run\",\"contextId\":\"run-1\",\"gitRefs\":"
            + "[\"refs/heads/maintenance/dependencies\",\"refs/heads/external/*\"]}",
        IdpCommissioner.commissionBody(
            "ci-run", "run-1", List.of("refs/heads/maintenance/dependencies", "refs/heads/external/*")));
  }

  @Test
  public void aScopedCommissionIsPostedAsIs() {
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    IdpCommissioner.CommissionedToken minted = idp.commissionToken("ci-run", "run-1", BUMP);

    assertEquals("token-1", minted.tokenId());
    assertEquals(List.of(SCOPED), stub.postedTokens);
    assertEquals(List.of(), errors);
  }

  @Test
  public void aRefusedScopeIsCommissionedAsPushNothingAndLoggedAsAnError() {
    stub.refuseGitRefList = true;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    IdpCommissioner.CommissionedToken minted = idp.commissionToken("ci-run", "run-1", BUMP);

    // Fail closed: the second ask states [], never no scope at all.
    assertEquals("token-1", minted.tokenId());
    assertEquals(List.of(SCOPED, PUSH_NOTHING), stub.postedTokens);
    assertEquals(1, errors.size(), errors.toString());
    assertTrue(errors.get(0).contains("run-1"), "the error names the run: " + errors.get(0));
    assertTrue(
        errors.get(0).contains("stubbed 400 refusal"),
        "the error names qits-idp's reason: " + errors.get(0));
  }

  @Test
  public void aFourHundredOnThePushNothingFormFailsTheCommission() {
    stub.tokenMintStatus = 400;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    IdpCommissioner.CommissionFailedException failed =
        assertThrows(
            IdpCommissioner.CommissionFailedException.class,
            () -> idp.commissionToken("ci-run", "run-1", BUMP));

    // Scoped, then [], then stop. No unscoped ask, and a 400 is not held through.
    assertEquals(List.of(SCOPED, PUSH_NOTHING), stub.postedTokens);
    assertTrue(failed.getMessage().contains("400"), failed.getMessage());
    assertTrue(failed.getMessage().contains("gitRefs=[]"), failed.getMessage());
  }

  @Test
  public void aRefusedPushNothingScopeIsNotAskedAgain() {
    stub.tokenMintStatus = 400;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    assertThrows(
        IdpCommissioner.CommissionFailedException.class,
        () -> idp.commissionToken("ci-run", "run-1", List.of()));

    assertEquals(List.of(PUSH_NOTHING), stub.postedTokens);
    assertEquals(List.of(), errors);
  }

  @Test
  public void aCommissionThatStatesNoScopeStaysUnscoped() {
    stub.refuseGitRefList = true;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    IdpCommissioner.CommissionedToken minted = idp.commissionToken("ci-run", "run-1", null);

    assertEquals("token-1", minted.tokenId());
    assertEquals(List.of(UNSCOPED), stub.postedTokens);
    assertEquals(List.of(), errors);
  }

  @Test
  public void aCommissionThatStatesNoScopeIsNeverRetriedOnFourHundred() {
    stub.tokenMintStatus = 400;
    IdpCommissioner idp = stub.commissioner(PATIENCE);

    assertThrows(
        IdpCommissioner.CommissionFailedException.class,
        () -> idp.commissionToken("ci-run", "run-1", null));

    assertEquals(List.of(UNSCOPED), stub.postedTokens);
    assertEquals(List.of(), errors);
  }
}
