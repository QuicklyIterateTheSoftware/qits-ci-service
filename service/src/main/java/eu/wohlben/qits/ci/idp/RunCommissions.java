package eu.wohlben.qits.ci.idp;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * One commissioned credential per run, for as long as the run lasts.
 *
 * <p><b>Commissioned lazily, at the first step.</b> Every step first clones its repository from the
 * authenticated git host, so every step needs the pair. The Git credential helper exchanges it for
 * a short-lived githost bearer rather than ever putting the pair on the wire. Every later step of
 * the same run reuses it: the credential belongs to the run rather than to the step, and one
 * commission per step would be N clients to leak instead of one.
 *
 * <p><b>Given back at {@code runClosed}.</b> {@code RunnerStepRunner.runClosed} is called from the
 * {@code finally} of the run body, which is what makes the release unconditional. The paths that
 * never enter a run body — a supersede at accept, a {@code QUEUED} cancel, a row the boot sweep
 * failed — commissioned nothing in this process and have nothing to give back; what covers a
 * credential this process died holding is {@link CommissionReconciler}.
 *
 * <p><b>Memory, not a row.</b> A commission is worth exactly one run and a run does not survive this
 * process: a restart fails or re-enqueues every run it was holding, so a persisted pair would name a
 * credential no run will ever present. The reconciliation is the durable half, and it needs no
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
  private final Map<String, IdpCommissioner.Commission> byRun = new ConcurrentHashMap<>();

  /**
   * The same, for a run on the EDGE plane: its {@code ci-run} TOKEN — id, subject and the value,
   * which qits-idp returns once and never again, so this entry is the only copy there is.
   */
  private final Map<String, IdpCommissioner.CommissionedToken> tokenByRun = new ConcurrentHashMap<>();

  /**
   * A run's credential, whichever kind its plane carries: exactly one of the two is set.
   *
   * <p><b>A client on qits-net, a token through the edge</b>, and the difference is what each side
   * can present. An INTERNAL step mints its own short-lived bearers from the pair, at the idp's wire
   * alias. An EDGE step cannot reach that alias, and the edge introspects a {@code qits_tok_} bearer
   * itself — for HTTP, for git's Basic and for the docker realm alike — so one opaque token is the
   * whole of what such a step needs, and it lives exactly as long as the run.
   */
  public record Credential(
      IdpCommissioner.Commission client, IdpCommissioner.CommissionedToken token) {

    public static Credential client(IdpCommissioner.Commission client) {
      return client == null ? null : new Credential(client, null);
    }

    public static Credential token(IdpCommissioner.CommissionedToken token) {
      return token == null ? null : new Credential(null, token);
    }

    public boolean isToken() {
      return token != null;
    }

    @Override
    public String toString() {
      // Both components carry a secret; neither is named here.
      return isToken()
          ? "Credential[token " + token.tokenId() + ", subject " + token.subject() + "]"
          : "Credential[client " + client.clientId() + "]";
    }
  }

  /**
   * This run's credential, commissioned on the first ask, or {@code null} when there is nothing to
   * commission with — see {@link IdpCommissioner#enabled()}, the arm on which a step container's
   * environment stays byte-identical to what it was before per-run credentials existed.
   *
   * <p>The null tolerance on the collaborator is the launcher's own: the hand-wired launchers in the
   * ITs set the fields a case needs and leave the rest, so an unset one is a test's silence rather
   * than a wiring failure.
   *
   * <p>{@code runEnv} is the step's run-scoped environment. Its {@code QITS_EVENT_NAME} and {@code
   * QITS_EVENT_PAYLOAD} decide the Git refs the commission states ({@link RunGitRefs}). Every step
   * of a run carries the same pair, so the first step's is the run's.
   *
   * @throws IdpCommissioner.CommissionFailedException when qits-idp could not be asked, which fails
   *     the step rather than launching it credential-less
   */
  public IdpCommissioner.Commission forRun(String runId, Map<String, String> runEnv) {
    if (idp == null || !idp.enabled()) {
      return null;
    }
    IdpCommissioner.Commission held = byRun.get(runId);
    if (held != null) {
      return held;
    }
    Optional<List<String>> gitRefs = RunGitRefs.fromRunEnv(runEnv, objectMapper);
    // INFO, once per run: the scope decides which pushes the githost refuses, so it must be
    // readable next to a refusal. Ref names only, never the credential.
    LOG.infof(
        "Run %s states gitRefs %s", runId, gitRefs.map(String::valueOf).orElse("(nothing)"));
    IdpCommissioner.Commission fresh =
        idp.commission(IdpCommissioner.CONTEXT_KIND, runId, gitRefs.orElse(null));
    byRun.put(runId, fresh);
    return fresh;
  }

  /**
   * This run's credential on {@code plane}: the {@link #forRun(String, Map) client} for {@code
   * INTERNAL} (and for a null plane — every local step), exactly as it always was; for {@code EDGE}
   * a {@code ci-run} TOKEN, commissioned on the first ask with the same context and the same {@link
   * RunGitRefs} scope and reused by every later step, like the client. Null when there is nothing to
   * commission with.
   *
   * <p><b>The value is handed to the step as {@code $QITS_TOKEN}</b>, which is the one departure
   * from the client path, where the container mints its own bearers from the pair: nothing can mint
   * a {@code qits_tok_} inside a container, and the token is worth one run and deleted when it
   * closes, so what the environment carries is exactly as long-lived as the pair it replaces.
   *
   * @throws IdpCommissioner.CommissionFailedException when qits-idp could not be asked
   */
  public Credential forRun(String runId, Map<String, String> runEnv, CiRunnerPlane plane) {
    if (plane != CiRunnerPlane.EDGE) {
      return Credential.client(forRun(runId, runEnv));
    }
    if (idp == null || !idp.enabled()) {
      return null;
    }
    IdpCommissioner.CommissionedToken held = tokenByRun.get(runId);
    if (held != null) {
      return Credential.token(held);
    }
    Optional<List<String>> gitRefs = RunGitRefs.fromRunEnv(runEnv, objectMapper);
    LOG.infof(
        "Run %s states gitRefs %s (a ci-run token, for the edge)",
        runId, gitRefs.map(String::valueOf).orElse("(nothing)"));
    IdpCommissioner.CommissionedToken fresh =
        idp.commissionToken(IdpCommissioner.CONTEXT_KIND, runId, gitRefs.orElse(null));
    tokenByRun.put(runId, fresh);
    return Credential.token(fresh);
  }

  /**
   * The subject of this run's {@code ci-run} token — the {@code sub} the edge puts on the JWT it
   * forwards for it — or null when the run holds none, which is every INTERNAL run.
   */
  public String tokenSubjectOf(String runId) {
    IdpCommissioner.CommissionedToken held = tokenByRun.get(runId);
    return held == null ? null : held.subject();
  }

  /**
   * Give this run's credential back, if it had one. <b>Never throws</b>: the caller is a run that is
   * already over, and a failure here costs a reconciliation rather than a run.
   */
  public void release(String runId) {
    IdpCommissioner.CommissionedToken token = tokenByRun.remove(runId);
    if (token != null && idp != null && !idp.deleteToken(token.tokenId())) {
      // deleteToken never throws and has logged why; the reconciliation reaps a ci-run token whose
      // run is no longer running.
      LOG.debugf("Run %s's ci-run token %s is left to the reconciliation", runId, token.tokenId());
    }
    IdpCommissioner.Commission gone = byRun.remove(runId);
    if (gone == null || idp == null) {
      return;
    }
    try {
      idp.decommission(gone.clientId());
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not decommission run %s's credential %s: %s — leaving it to the next"
              + " reconciliation",
          runId, gone.clientId(), e.toString());
    }
  }

  /**
   * Whether this process is currently holding that commissioned client — what keeps {@link
   * CommissionReconciler} off a credential a run is using right now, in the window between a run's
   * row going terminal and its {@code runClosed}.
   */
  public boolean holds(String commissionedClientId) {
    for (IdpCommissioner.Commission each : byRun.values()) {
      if (each.clientId().equals(commissionedClientId)) {
        return true;
      }
    }
    return false;
  }

  /** {@link #holds}, for a {@code ci-run} token: whether a run of this process holds it right now. */
  public boolean holdsToken(String tokenId) {
    for (IdpCommissioner.CommissionedToken each : tokenByRun.values()) {
      if (each.tokenId().equals(tokenId)) {
        return true;
      }
    }
    return false;
  }
}
