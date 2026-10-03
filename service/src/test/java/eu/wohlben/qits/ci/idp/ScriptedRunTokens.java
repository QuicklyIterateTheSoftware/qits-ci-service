package eu.wohlben.qits.ci.idp;

import jakarta.enterprise.inject.Vetoed;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link RunCommissions} with no qits-idp behind it, for a suite that launches steps: every run is
 * handed a {@code ci-run} token whose subject is the one given here.
 *
 * <p><b>Why a suite needs one.</b> The %test posture commissions nothing ({@code
 * quarkus.oidc-client.qits.client-enabled} is off), and since qits-515 a step with no token is not
 * launched at all — the token is its only credential, and the subject it carries is what the
 * step's daemon is admitted to its launch by. So a suite that drives a step through {@code
 * RunnerStepRunner} installs this over the bean with {@code QuarkusMock}.
 *
 * <p><b>The subject is the caller's to choose, and it has to be the one its daemon arrives as.</b>
 * A suite whose {@code @TestSecurity} identity is applied to every upgrade in the method passes that
 * identity's {@code sub}; a suite whose fake daemon asserts its own identity passes nothing and
 * reads the subject back out of the launch it is sent ({@code QITS_TOKEN_SUBJECT}).
 *
 * <p>{@code @Vetoed}, so it is never discovered: a subclass inherits {@code @ApplicationScoped},
 * and a second {@code RunCommissions} bean on the test index would make every injection of one
 * ambiguous. It is only ever installed.
 */
@Vetoed
public final class ScriptedRunTokens extends RunCommissions {

  private final String subject;

  private final Map<String, IdpCommissioner.CommissionedToken> held = new ConcurrentHashMap<>();

  /** Every run id given back, in order — what "the run's close released its token" is read from. */
  public final List<String> released = new CopyOnWriteArrayList<>();

  /** Each run gets a subject of its own, {@code tok-ci-run-<runId>}. */
  public ScriptedRunTokens() {
    this(null);
  }

  /** Every run gets {@code subject}. */
  public ScriptedRunTokens(String subject) {
    this.subject = subject;
  }

  @Override
  public IdpCommissioner.CommissionedToken forRun(String runId, Map<String, String> runEnv) {
    return held.computeIfAbsent(
        runId,
        id ->
            new IdpCommissioner.CommissionedToken(
                "token-" + id,
                "qits_tok_scripted-" + id,
                subject == null ? "tok-ci-run-" + id : subject));
  }

  @Override
  public String tokenSubjectOf(String runId) {
    IdpCommissioner.CommissionedToken token = held.get(runId);
    return token == null ? null : token.subject();
  }

  @Override
  public void release(String runId) {
    if (held.remove(runId) != null) {
      released.add(runId);
    }
  }

  @Override
  public boolean holdsToken(String tokenId) {
    return held.values().stream().anyMatch(token -> token.tokenId().equals(tokenId));
  }
}
