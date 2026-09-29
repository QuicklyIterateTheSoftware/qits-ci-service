package eu.wohlben.qits.ci.idp;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Deletes the commissioned credentials no run owns any more — the durable half of {@link
 * RunCommissions}, which can only give back what this process is still holding.
 *
 * <p><b>What it is for.</b> A commission is released when the run closes, and a process that is
 * killed mid-run closes nothing: the credential stays live at qits-idp with no run behind it. So
 * does one whose {@code DELETE} failed, and one belonging to a run this instance never entered. The
 * list of what exists is qits-idp's, the list of what is owed is the run table, and the difference
 * between them is what this reaps.
 *
 * <p><b>The predicate is deliberately narrow.</b> Only rows of this owner's — the listing shows no
 * other client's — whose {@code contextKind} is {@code ci-run}, and whose {@code contextId} is not a
 * run that is {@code QUEUED} or {@code RUNNING} right now. A commission this process holds in memory
 * is spared as well, which covers the window between a run's row going terminal and its {@code
 * runClosed}.
 *
 * <p><b>A listing that could not be read reaps nothing.</b> {@link IdpCommissioner#live()} answers
 * an empty {@code Optional} rather than an empty list for that case, and reading the two as one
 * would delete every live run's credential the first time qits-idp was slow — the same "a read
 * failure must not shrink a set" rule the candidate listing and the run queue already state.
 *
 * <p><b>It reaps a runner's credentials too, by the runner table rather than the run table.</b> A
 * {@code ci-runner} client belongs to the runner its {@code contextId} names, and is reaped when that
 * runner's row is gone or names a different client — a decommission whose give-back did not reach
 * qits-idp, or a registration that lost a race. A {@code ci-runner-registration} token is reaped when
 * its runner's row is gone, when the runner has registered (the token is spent) or when the row names
 * a different token (it was rotated). A row with no client yet spares every client of its runner —
 * that is a registration in flight, between the commission and the write — and a token younger than
 * {@link #TOKEN_GRACE} is spared outright, since a create and a rotation commission the token before
 * the row names it. {@code GET /idp/api/tokens} is a second listing on the same pass, and it carries
 * the same rule as the first: one that could not be read reaps nothing.
 *
 * <p><b>And an EDGE run's {@code ci-run} token, by the run table like its client.</b> A run on the
 * edge plane holds a token rather than a client (qits-475); one whose run is no longer {@code
 * QUEUED} or {@code RUNNING}, that this process is not holding, and that is older than {@link
 * #TOKEN_GRACE} is deleted. Same listing, same rule: unread, nothing is reaped.
 *
 * <p><b>Boot, on its own thread.</b> The observer runs after the run sweep ({@code
 * CiRunService.BOOT_SWEEP_PRIORITY}) so the run table it reads is the one the sweep left, and it hands the work to a thread of its own rather than
 * blocking the startup thread on the network. That lesson was paid live by the daemon pin ladder's
 * own startup discovery — a startup observer that waits on a service loses the container
 * healthcheck's race and cd kills the deployment — and it outlived the discovery, which is deleted.
 */
@ApplicationScoped
public class CommissionReconciler {

  private static final Logger LOG = Logger.getLogger(CommissionReconciler.class);

  /**
   * Boot order: after the run sweep at 2100, because what is reaped here is decided by which runs
   * are still {@code QUEUED} or {@code RUNNING}, and the sweep is what settles that.
   */
  public static final int BOOT_RECONCILE_PRIORITY = 2200;

  @Inject IdpCommissioner idp;

  @Inject RunCommissions commissions;

  @Inject CiRunRepository runs;

  @Inject CiRunnerRepository runners;

  /**
   * How young a registration token has to be to be spared whatever the runner table says. A create
   * and a rotation commission the token first and write the row after, so for a moment a live token
   * is one no row names; ten minutes is that moment with a great deal of room, and still far inside
   * the hourly pass.
   */
  static final Duration TOKEN_GRACE = Duration.ofMinutes(10);

  /**
   * Skipped under {@code TEST}, like the run sweep it follows: the suites reach no idp by
   * intent. {@link #reconcile()} is what a test drives instead.
   */
  void onStart(@Observes @Priority(BOOT_RECONCILE_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST || !idp.enabled()) {
      return;
    }
    Thread sweep = new Thread(this::reconcile, "ci-commission-reconcile");
    sweep.setDaemon(true);
    sweep.start();
  }

  /**
   * The slow schedule underneath the boot pass. Hourly by default: what it collects is a leak of one
   * credential per process death, so a tighter interval would ask qits-idp for a listing far more
   * often than anything changes.
   */
  @Scheduled(
      every = "{qits.ci.commission.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    if (!idp.enabled()) {
      return;
    }
    reconcile();
  }

  /**
   * Read the live commissions, delete the ones no run owns. Package-private because both callers
   * above skip in a suite, so this is what a test calls directly.
   */
  void reconcile() {
    Optional<List<IdpCommissioner.LiveClient>> live = idp.live();
    if (live.isPresent()) {
      reap(live.get(), activeRunIds());
      // The runner table is read only when there is something of a runner's to judge against it.
      if (live.get().stream().anyMatch(c -> IdpCommissioner.RUNNER_KIND.equals(c.contextKind()))) {
        reapRunnerClients(live.get(), runnerCredentials());
      }
    }
    Optional<List<IdpCommissioner.LiveToken>> tokens = idp.liveTokens();
    if (tokens.isPresent()
        && tokens.get().stream()
            .anyMatch(t -> IdpCommissioner.RUNNER_REGISTRATION_KIND.equals(t.contextKind()))) {
      reapRunnerTokens(tokens.get(), runnerCredentials(), Instant.now());
    }
    // The run table is read again only when there is a run's token to judge against it.
    if (tokens.isPresent()
        && tokens.get().stream()
            .anyMatch(t -> IdpCommissioner.CONTEXT_KIND.equals(t.contextKind()))) {
      reapRunTokens(tokens.get(), activeRunIds(), Instant.now());
    }
  }

  /**
   * The edge plane's half of {@link #reap}: a {@code ci-run} TOKEN whose run is not {@code QUEUED}
   * or {@code RUNNING} and which this process is not holding. The same predicate as the clients',
   * plus {@link #TOKEN_GRACE}, since a token is commissioned at a run's first step and a listing can
   * catch it in the moment between the row's claim and the step's launch — the registration
   * token's reason, on a shorter path.
   */
  int reapRunTokens(
      List<IdpCommissioner.LiveToken> live, Set<String> activeRunIds, Instant now) {
    if (activeRunIds == null) {
      return 0;
    }
    int reaped = 0;
    for (IdpCommissioner.LiveToken each : live) {
      if (!IdpCommissioner.CONTEXT_KIND.equals(each.contextKind())) {
        continue;
      }
      if (each.createdAt() != null && each.createdAt().isAfter(now.minus(TOKEN_GRACE))) {
        continue;
      }
      if (activeRunIds.contains(each.contextId()) || commissions.holdsToken(each.tokenId())) {
        continue;
      }
      LOG.infof(
          "Reaping the ci-run token %s of run %s, which is no longer running",
          each.tokenId(), each.contextId());
      idp.deleteToken(each.tokenId());
      reaped++;
    }
    if (reaped > 0) {
      LOG.infof("Reaped %d ci-run token(s) no CI run owns any more", reaped);
    }
    return reaped;
  }

  /**
   * What each runner row says it holds at qits-idp, keyed by the runner id as qits-idp spells a
   * context id. {@code clientId} null is an unregistered runner.
   */
  record RunnerCredentials(String clientId, String registrationTokenId) {}

  /** Every runner's credentials, or null when the table could not be read — which reaps nothing. */
  private Map<String, RunnerCredentials> runnerCredentials() {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                Map<String, RunnerCredentials> rows = new HashMap<>();
                for (CiRunner runner : runners.listAll()) {
                  rows.put(
                      runner.id.toString(),
                      new RunnerCredentials(runner.clientId, runner.registrationTokenId));
                }
                return rows;
              });
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the runners, so no runner credential is reaped: %s", e.toString());
      return null;
    }
  }

  /** The runner half of the client sweep; see the class javadoc for the rule. */
  int reapRunnerClients(
      List<IdpCommissioner.LiveClient> live, Map<String, RunnerCredentials> runnerRows) {
    if (runnerRows == null) {
      return 0;
    }
    int reaped = 0;
    for (IdpCommissioner.LiveClient each : live) {
      if (!IdpCommissioner.RUNNER_KIND.equals(each.contextKind())) {
        continue;
      }
      RunnerCredentials row = runnerRows.get(each.contextId());
      if (row != null && (row.clientId() == null || row.clientId().equals(each.clientId()))) {
        continue;
      }
      LOG.infof(
          "Reaping runner client %s of runner %s, which %s",
          each.clientId(),
          each.contextId(),
          row == null ? "is decommissioned" : "is registered as " + row.clientId());
      idp.decommission(each.clientId());
      reaped++;
    }
    return reaped;
  }

  /** The registration-token sweep; see the class javadoc for the rule. */
  int reapRunnerTokens(
      List<IdpCommissioner.LiveToken> live,
      Map<String, RunnerCredentials> runnerRows,
      Instant now) {
    if (runnerRows == null) {
      return 0;
    }
    int reaped = 0;
    for (IdpCommissioner.LiveToken each : live) {
      if (!IdpCommissioner.RUNNER_REGISTRATION_KIND.equals(each.contextKind())) {
        continue;
      }
      if (each.createdAt() != null && each.createdAt().isAfter(now.minus(TOKEN_GRACE))) {
        continue;
      }
      RunnerCredentials row = runnerRows.get(each.contextId());
      String why;
      if (row == null) {
        why = "is decommissioned";
      } else if (row.clientId() != null) {
        why = "has registered";
      } else if (!each.tokenId().equals(row.registrationTokenId())) {
        why = "holds a newer one";
      } else {
        continue;
      }
      LOG.infof(
          "Reaping registration token %s of runner %s, which %s", each.tokenId(), each.contextId(), why);
      idp.deleteToken(each.tokenId());
      reaped++;
    }
    return reaped;
  }

  /** The run ids that still own a credential — see the class javadoc for why they are read here. */
  private Set<String> activeRunIds() {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                Set<String> ids = new HashSet<>();
                for (CiRun run : runs.listActiveNewestFirst()) {
                  ids.add(run.id);
                }
                return ids;
              });
    } catch (RuntimeException e) {
      // Nothing was learned about which runs are live, so nothing may be reaped: an empty set here
      // would read as "no run owns anything" and take every live credential with it.
      LOG.warnf("Could not read the active runs, so no commissioned credential is reaped: %s", e.toString());
      return null;
    }
  }

  /** The reaping itself, over an already-read listing — the seam a test drives with a set. */
  int reap(List<IdpCommissioner.LiveClient> live, Set<String> activeRunIds) {
    if (activeRunIds == null) {
      return 0;
    }
    int reaped = 0;
    for (IdpCommissioner.LiveClient each : live) {
      if (!IdpCommissioner.CONTEXT_KIND.equals(each.contextKind())) {
        continue;
      }
      if (activeRunIds.contains(each.contextId()) || commissions.holds(each.clientId())) {
        continue;
      }
      LOG.infof(
          "Reaping the commissioned credential %s of run %s, which is no longer running",
          each.clientId(), each.contextId());
      idp.decommission(each.clientId());
      reaped++;
    }
    if (reaped > 0) {
      LOG.infof("Reaped %d commissioned credential(s) no CI run owns any more", reaped);
    }
    return reaped;
  }
}
