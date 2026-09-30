package eu.wohlben.qits.ci.idp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * One commissioned {@code ci-run} token per run, for as long as the run lasts.
 *
 * <p><b>A token, and only a token.</b> A step reaches every service through the platform edge, which
 * introspects a {@code qits_tok_} bearer itself — for HTTP, for git's Basic and for the docker realm
 * alike — so one opaque token is the whole of what a step presents, and it lives exactly as long as
 * the run. The commissioned CLIENT a run on qits-net used to hold, which its container exchanged for
 * short-lived bearers at the idp's wire alias, was deleted with that plane (qits-515).
 *
 * <p><b>Commissioned lazily, at the first step.</b> Every step first clones its repository from the
 * authenticated git host, so every step needs the token. Every later step of the same run reuses it:
 * the credential belongs to the run rather than to the step, and one commission per step would be N
 * tokens to leak instead of one.
 *
 * <p><b>Given back at {@code runClosed}.</b> {@code RunnerStepRunner.runClosed} is called from the
 * {@code finally} of the run body, which is what makes the release unconditional. The paths that
 * never enter a run body — a supersede at accept, a {@code QUEUED} cancel, a row the boot sweep
 * failed — commissioned nothing in this process and have nothing to give back; what covers a
 * token this process died holding is {@link CommissionReconciler}.
 *
 * <p><b>Memory, not a row.</b> A commission is worth exactly one run and a run does not survive this
 * process: a restart fails or re-enqueues every run it was holding, so a persisted token would name
 * a credential no run will ever present — and qits-idp returns the value once and never again, so
 * the entry here is the only copy there is. The reconciliation is the durable half, and it needs no
 * table of ours because qits-idp already holds the list.
 */
@ApplicationScoped
public class RunCommissions {

  private static final Logger LOG = Logger.getLogger(RunCommissions.class);

  @Inject IdpCommissioner idp;

  /**
   * Our own mapper, for the run's event payload. Never {@code idp.objectMapper}: {@code idp} is a
   * client proxy, and a field read through a proxy is null. That read made every {@code
   * MaintenanceBump} run state {@code gitRefs []} on 2026-09-13 (see {@code
   * RunCommissionsWiringTest}).
   */
  @Inject ObjectMapper objectMapper;

  /** One entry per run that has reached its first step, removed when the run closes. */
  private final Map<String, IdpCommissioner.CommissionedToken> tokenByRun =
      new ConcurrentHashMap<>();

  /**
   * This run's {@code ci-run} token, commissioned on the first ask, or {@code null} when there is
   * nothing to commission with — see {@link IdpCommissioner#enabled()}. The caller does not launch
   * a step on a null: a step with no token can reach nothing.
   *
   * <p>{@code runEnv} is the step's run-scoped environment. Its {@code QITS_EVENT_NAME} and {@code
   * QITS_EVENT_PAYLOAD} decide the Git refs the commission states ({@link RunGitRefs}). Every step
   * of a run carries the same pair, so the first step's is the run's.
   *
   * <p><b>The value is handed to the step as {@code $QITS_TOKEN}</b>: nothing can mint a {@code
   * qits_tok_} inside a container, and the token is worth one run and deleted when it closes.
   *
   * @throws IdpCommissioner.CommissionFailedException when qits-idp could not be asked, which fails
   *     the step rather than launching it credential-less
   */
  public IdpCommissioner.CommissionedToken forRun(String runId, Map<String, String> runEnv) {
    if (idp == null || !idp.enabled()) {
      return null;
    }
    IdpCommissioner.CommissionedToken held = tokenByRun.get(runId);
    if (held != null) {
      return held;
    }
    Optional<List<String>> gitRefs = RunGitRefs.fromRunEnv(runEnv, objectMapper);
    // INFO, once per run: the scope decides which pushes the githost refuses, so it must be
    // readable next to a refusal. Ref names only, never the credential.
    LOG.infof(
        "Run %s states gitRefs %s", runId, gitRefs.map(String::valueOf).orElse("(nothing)"));
    IdpCommissioner.CommissionedToken fresh =
        idp.commissionToken(IdpCommissioner.CONTEXT_KIND, runId, gitRefs.orElse(null));
    tokenByRun.put(runId, fresh);
    return fresh;
  }

  /**
   * The subject of this run's {@code ci-run} token — the {@code sub} the edge puts on the JWT it
   * forwards for it — or null when the run holds none.
   */
  public String tokenSubjectOf(String runId) {
    IdpCommissioner.CommissionedToken held = tokenByRun.get(runId);
    return held == null ? null : held.subject();
  }

  /**
   * Give this run's token back, if it had one. <b>Never throws</b>: the caller is a run that is
   * already over, and a failure here costs a reconciliation rather than a run.
   */
  public void release(String runId) {
    IdpCommissioner.CommissionedToken token = tokenByRun.remove(runId);
    if (token != null && idp != null && !idp.deleteToken(token.tokenId())) {
      // deleteToken never throws and has logged why; the reconciliation reaps a ci-run token whose
      // run is no longer running.
      LOG.debugf("Run %s's ci-run token %s is left to the reconciliation", runId, token.tokenId());
    }
  }

  /**
   * Whether a run of this process holds that {@code ci-run} token right now — what keeps {@link
   * CommissionReconciler} off a credential a run is using, in the window between a run's row going
   * terminal and its {@code runClosed}.
   */
  public boolean holdsToken(String tokenId) {
    for (IdpCommissioner.CommissionedToken each : tokenByRun.values()) {
      if (each.tokenId().equals(tokenId)) {
        return true;
      }
    }
    return false;
  }
}
