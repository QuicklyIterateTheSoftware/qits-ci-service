package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunPurpose;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Panache DAO for {@link CiRun} (keyed by its String UUID row id).
 *
 * <p><b>A repository's listings are its BUILDS.</b> A retired CI runner's health check was a run
 * recorded against a real repository and commit, and its verdict says nothing about either, so every
 * read that answers "what has this repository's CI done" — its run listing, its newest run and newest
 * {@code main} run, the finished listing, and which repositories have runs at all — carries {@code
 * purpose = BUILD}. Nothing writes another purpose since CI runs in leases (epic qits-1481); the
 * predicate keeps the historical rows out.
 */
@ApplicationScoped
public class CiRunRepository implements PanacheRepositoryBase<CiRun, String> {

  private static final String NEWEST_FIRST =
      "repoId = ?1 and purpose = ?2 order by createdAt desc, id desc";

  private static final String NEWEST_FIRST_ON_BRANCH =
      "repoId = ?1 and branch = ?2 and purpose = ?3 order by createdAt desc, id desc";

  /** All builds recorded for a repository, newest-first. */
  public List<CiRun> listByRepoIdNewestFirst(String repoId) {
    return list(NEWEST_FIRST, repoId, CiRunPurpose.BUILD);
  }

  /**
   * The newest {@code limit} runs recorded for a repository.
   *
   * <p>The bound is applied in SQL rather than to a materialised list, because the point of asking
   * for the newest hundred is not fetching the other nine thousand. It is a total answer only
   * because the ordering is: {@code createdAt desc, id desc} is a strict total order over the rows,
   * so "the newest n" names the same n rows on every call. Deliberately <b>no offset</b> — a list
   * that grows at the head cannot be walked by skipping from the front without re-showing rows.
   */
  public List<CiRun> listByRepoIdNewestFirst(String repoId, int limit) {
    return find(NEWEST_FIRST, repoId, CiRunPurpose.BUILD).range(0, limit - 1).list();
  }

  /**
   * The newest run recorded for a repository on any branch, or empty when it has none — the {@code
   * lastRun} half of {@code GET /ci/api/repositories/summary}.
   */
  public Optional<CiRun> newestFor(String repoId) {
    return find(NEWEST_FIRST, repoId, CiRunPurpose.BUILD).firstResultOptional();
  }

  /**
   * The newest run recorded for a repository on one branch, or empty when it has none — the {@code
   * lastMainRun} half of the same summary.
   *
   * <p>A separate query rather than a filter over {@link #newestFor}'s answer, because the two
   * questions have different answers and the second one's answer can be arbitrarily far down the
   * list: a repository with a hundred feature-branch runs since its last push to {@code main} would
   * need the whole history read to find it.
   */
  public Optional<CiRun> newestForBranch(String repoId, String branch) {
    return find(NEWEST_FIRST_ON_BRANCH, repoId, branch, CiRunPurpose.BUILD).firstResultOptional();
  }

  /**
   * Every run that is accepted but not finished — {@code QUEUED} or {@code RUNNING} — across all
   * repositories, newest-first. The read behind {@code GET /ci/api/runs/active}.
   *
   * <p>Unscoped by repository on purpose, and it is the one read on this surface that is: the
   * question it answers is "what is CI doing right now", which has no repository to scope to. It is
   * bounded by accepted work and the configured worker pool rather than by a limit, so it does
   * not carry one.
   */
  public List<CiRun> listActiveNewestFirst() {
    return list(
        "status in (?1, ?2) order by createdAt desc, id desc",
        CiRunStatus.QUEUED,
        CiRunStatus.RUNNING);
  }

  /**
   * <b>The claim, as one conditional UPDATE</b> (epic qits-1481): a run whose lease was granted
   * becomes {@code RUNNING}, stamped and naming the target it runs on, only while the row is still
   * {@code QUEUED} and still names that lease. One row changed is this caller's run; zero is nobody's
   * — a cancellation, a settlement or a second observer of the same grant got there first. Postgres
   * re-evaluates the predicate against a concurrently committed version of the row, so two
   * transactions racing for one row cannot both see one row changed.
   */
  public int claimLeased(String runId, UUID leaseId, UUID targetId, Instant startedAt) {
    return update(
        "status = ?1, startedAt = ?2, targetId = ?3 where id = ?4 and status = ?5 and leaseId = ?6",
        CiRunStatus.RUNNING,
        startedAt,
        targetId,
        runId,
        CiRunStatus.QUEUED,
        leaseId);
  }

  /**
   * Records a queued run's lease and its launch, as one conditional UPDATE — only while the row is
   * still {@code QUEUED}, so a cancellation committing in between is never overwritten by a write
   * that read the row before it.
   */
  public int recordLease(
      String runId, UUID leaseId, String daemonId, String tokenSubject, String daemonVersion) {
    return update(
        "leaseId = ?1, targetId = null, daemonVersion = ?2, inflightStepIndex = null,"
            + " inflightDaemonId = ?3, inflightTokenSubject = ?4, inflightStartedAt = null"
            + " where id = ?5 and status = ?6",
        leaseId,
        daemonVersion,
        daemonId,
        tokenSubject,
        runId,
        CiRunStatus.QUEUED);
  }

  /** Forgets a queued run's lease, conditionally like {@link #recordLease}. */
  public int forgetLease(String runId, UUID leaseId) {
    return update(
        "leaseId = null, targetId = null, daemonVersion = null, inflightStepIndex = null,"
            + " inflightDaemonId = null, inflightTokenSubject = null, inflightStartedAt = null"
            + " where id = ?1 and status = ?2 and leaseId = ?3",
        runId,
        CiRunStatus.QUEUED,
        leaseId);
  }

  /** Every run that holds a lease and is {@code QUEUED} or {@code RUNNING} — the lease reconcile's walk. */
  public List<CiRun> listLeased() {
    return list(
        "leaseId is not null and status in (?1, ?2) order by createdAt, id",
        CiRunStatus.QUEUED,
        CiRunStatus.RUNNING);
  }

  /**
   * How many runs are {@code QUEUED} right now. A count rather than {@link #listQueuedOldestFirst}'s
   * size, for a caller that does not want the rows.
   */
  public long countQueued() {
    return count("status", CiRunStatus.QUEUED);
  }

  /**
   * The newest {@code limit} runs that are over — anything not {@code QUEUED} or {@code RUNNING} —
   * across all repositories, newest-first. The read behind {@code GET /ci/api/runs/finished}.
   *
   * <p><b>The predicate is the complement of {@link #listActiveNewestFirst}'s, and that is the
   * point.</b> Naming the terminal statuses instead ({@code in (SUCCESS, FAILED, CANCELLED,
   * CONFIG_ERROR, TIMED_OUT)})
   * would read the same today and rot silently: another status added to {@code ck_ci_run_status}
   * would be finished in fact and invisible here, so a run would leave the active list and never
   * arrive in this one. Written as a complement, the two lists partition the table by construction
   * and a new status is finished by default — which is also how {@code isTerminal} is defined on both
   * sides of the wire.
   *
   * <p>Unscoped by repository, like the active list and unlike everything else here, because "what
   * did CI last finish" has no repository to scope to. Unlike the active list it <b>needs</b> its
   * bound: what is active is bounded by what one worker has accepted, while what is finished grows
   * with the life of the instance. Ordering is by {@code finishedAt}, not acceptance time: a slow
   * older pipeline that finishes now belongs ahead of a newer pipeline that completed earlier.
   */
  public List<CiRun> listFinishedNewestFirst(int limit) {
    return find(
            "status not in (?1, ?2) and purpose = ?3 order by finishedAt desc, id desc",
            CiRunStatus.QUEUED,
            CiRunStatus.RUNNING,
            CiRunPurpose.BUILD)
        .range(0, limit - 1)
        .list();
  }

  /**
   * Every {@code QUEUED} run, oldest-first — what the startup sweep asks leases for again, and what the
   * queue's explanation orders.
   *
   * <p>It was "what the startup sweep re-enqueues, in the order the runs were accepted so a restart
   * does not reorder a backlog", and both halves of that moved on. A reservation reads this list on
   * every pass and hands it to {@code CiRunOrdering}, which decides the real order; the ordering's
   * <em>last</em> tie-break is {@code (createdAt, id)}, which is exactly this order — so a queue
   * with no priorities, no downstream lists and no release runs in it is claimed in precisely the
   * order this query returns, and a restart re-derives that from the same rows.
   *
   * <p>Unbounded on purpose: it is bounded by the accepted backlog, which is the same thing {@code
   * listActiveNewestFirst} says about itself, and a bound here would be a bound on how far the
   * ordering can see.
   */
  public List<CiRun> listQueuedOldestFirst() {
    return list("status = ?1 order by createdAt, id", CiRunStatus.QUEUED);
  }

  /**
   * The other queued runs one trigger file has for one event name, the versionsort supersede's
   * candidates — see {@code CiRunService.supersedeByVersion}.
   *
   * <p>Scoped by {@code configPath} rather than by branch: a trigger file is one pipeline per
   * file, so that is the unit a burst collapses onto. The event NAME is in the predicate because two trigger files can
   * be one file at two names only by coincidence — what supersedes what is decided by reading both
   * payloads, and two payloads of different events have no field in common to compare.
   */
  public List<CiRun> listQueuedEventRuns(
      String repoId, String configPath, String eventName, String exceptRunId) {
    return list(
        "repoId = ?1 and configPath = ?2 and status = ?3 and triggerType = ?4"
            + " and triggerEventName = ?5 and id <> ?6",
        repoId,
        configPath,
        CiRunStatus.QUEUED,
        eu.wohlben.qits.ci.entity.CiTriggerType.EVENT,
        eventName,
        exceptRunId);
  }

  /**
   * {@link #listQueuedEventRuns} narrowed to one branch — the checkout collapse's question, which
   * only makes sense for runs whose branch came out of the payload (the caller gates on that): a
   * burst of events naming one branch is one pipeline per file per branch.
   */
  public List<CiRun> listQueuedEventRunsOnBranch(
      String repoId, String configPath, String eventName, String branch, String exceptRunId) {
    return list(
        "repoId = ?1 and configPath = ?2 and status = ?3 and triggerType = ?4"
            + " and triggerEventName = ?5 and branch = ?6 and id <> ?7",
        repoId,
        configPath,
        CiRunStatus.QUEUED,
        eu.wohlben.qits.ci.entity.CiTriggerType.EVENT,
        eventName,
        branch,
        exceptRunId);
  }

  /**
   * The newest run one repository has for one release request in one phase, or empty when that
   * phase has never run there.
   *
   * <p><b>The triple is the identity, and it is the only one the caller holds.</b> qits-projects
   * knows a repository and a release request and which phase of the release it is asking about; it
   * does not know a run id, and the sha cannot stand in for one — a QA run's commit is a fold nobody
   * pushed and the next re-fold replaces it. So the rerun door addresses work exactly as {@link
   * #listUnfinishedForReleaseRequest} does, with the phase as the third term, and both halves of
   * that pair are load-bearing there for the reasons stated there.
   *
   * <p>Newest first and one row, because a phase can have run several times — a re-fold, a rerun of
   * its own — and what a rerun re-asks is the latest question, never an older one whose answer has
   * already been superseded. {@code createdAt desc, id desc} is the strict total order every other
   * newest-first read here uses, so "the newest" names one row rather than an arbitrary one of
   * several.
   */
  public Optional<CiRun> findNewestForReleaseRequestPhase(
      String repoId, String releaseRequestId, CiRunPhase phase) {
    return find(
            "repoId = ?1 and releaseRequestId = ?2 and phase = ?3 order by createdAt desc, id desc",
            repoId,
            releaseRequestId,
            phase)
        .range(0, 0)
        .firstResultOptional();
  }

  /**
   * The gating run one fold already has, or empty — the newest run one repository has for one
   * release request in {@link CiRunPhase#RELEASE_REQUEST} at one sha and one trigger file whose
   * status is anything but {@link CiRunStatus#CANCELLED} (qits-1250).
   *
   * <p>What a repeated {@code ReleaseRequestChanged} is answered with when it arrives under a new
   * event id, so the unique constraint on the event cannot tell it is the same question. Finished
   * runs count, green or red: the fold has its verdict, and a second build of it could only add a
   * lineage-less one beside it. A cancelled run is the one status that answers nothing, so it does
   * not stand in the way. Served by {@code idx_ci_run_release_request_id}.
   */
  public Optional<CiRun> findGatingRunForFold(
      String repoId, String releaseRequestId, String commitSha, String configPath) {
    return find(
            "repoId = ?1 and releaseRequestId = ?2 and phase = ?3 and commitSha = ?4"
                + " and configPath = ?5 and status <> ?6 order by createdAt desc, id desc",
            repoId,
            releaseRequestId,
            CiRunPhase.RELEASE_REQUEST,
            commitSha,
            configPath,
            CiRunStatus.CANCELLED)
        .range(0, 0)
        .firstResultOptional();
  }

  /**
   * Whether this instance has ever recorded a run for a repository — what tells a repository the
   * rerun door has never heard of from one whose phase simply has not run.
   *
   * <p>Run history is deliberately the test rather than the candidate catalogue. The catalogue is a
   * live listing behind a five-second cache and an unreachable qits-projects empties it, so asking
   * it here would turn a listing outage into a 404 about a repository that exists; a row, once
   * written, is this service's own durable knowledge of the repository and cannot go away under a
   * blip. It is also exactly the set the door can answer for: a repository with no run has no phase
   * to re-ask.
   */
  public boolean hasAnyRun(String repoId) {
    return count("repoId = ?1", repoId) > 0;
  }

  /**
   * Every unfinished run one repository has for one release request — {@code QUEUED} or {@code
   * RUNNING} — oldest first. What {@code POST /ci/api/runs/cancellations} cancels.
   *
   * <p><b>The pair is the scope, and both halves are load-bearing.</b> A release request is
   * qits-projects' aggregate and one request folds N repositories, so the id alone would reach
   * another repository's run; a repository alone would reach the runs of every OTHER request open
   * against it, which is exactly the sibling a withdrawn request must not take down with it. The
   * predicate is the complement of the terminal statuses for {@link #listFinishedNewestFirst}'s
   * reason: a status added later is finished by default rather than silently cancellable.
   *
   * <p>Oldest first rather than newest, unlike every other listing here, because this one is not
   * read — it is <em>acted on</em>, run by run, and settling the oldest first is what makes a
   * partial pass (a caller that died halfway) leave the newest work standing rather than the
   * staleest.
   *
   * <p><b>{@code phase} narrows it to one phase of the release, and {@code null} does not
   * narrow.</b> The two phases share the request id — the publish run a tag push creates carries it
   * exactly as the QA runs do — so the pair alone reaches both. That is right for a withdrawal or a
   * supersession, where the whole release is no longer wanted, and wrong for RELEASED: there the
   * request's QA runs are done with but its publish run is the release happening, and when the tag
   * push queued it before the status change arrived, the unnarrowed pair cancelled it (qits-1264).
   * So the caller names {@link CiRunPhase#RELEASE_REQUEST} there, and a sender that names nothing
   * keeps the old answer.
   */
  public List<CiRun> listUnfinishedForReleaseRequest(
      String repoId, String releaseRequestId, CiRunPhase phase) {
    if (phase == null) {
      return list(
          "repoId = ?1 and releaseRequestId = ?2 and status in (?3, ?4) order by createdAt, id",
          repoId,
          releaseRequestId,
          CiRunStatus.QUEUED,
          CiRunStatus.RUNNING);
    }
    return list(
        "repoId = ?1 and releaseRequestId = ?2 and phase = ?3 and status in (?4, ?5)"
            + " order by createdAt, id",
        repoId,
        releaseRequestId,
        phase,
        CiRunStatus.QUEUED,
        CiRunStatus.RUNNING);
  }

  /**
   * Every unfinished release-request gating build one repository has for some request OTHER than
   * {@code releaseRequestId} — what a newer gating build of that repository supersedes (qits-552),
   * oldest first.
   *
   * <p>{@link CiRunPhase#RELEASE_REQUEST} only, so a publish run and a run that is no part of a
   * release never appear, and the request id must be set and differ — the same request's own runs
   * are the per-branch collapse's and the cancellation door's, never this one's.
   */
  public List<CiRun> listUnfinishedReleaseBuildsOfOtherRequests(
      String repoId, String releaseRequestId) {
    return list(
        "repoId = ?1 and phase = ?2 and releaseRequestId is not null and releaseRequestId <> ?3"
            + " and status in (?4, ?5) order by createdAt, id",
        repoId,
        CiRunPhase.RELEASE_REQUEST,
        releaseRequestId,
        CiRunStatus.QUEUED,
        CiRunStatus.RUNNING);
  }

  /**
   * Every repository this instance has ever recorded a run for — half of what {@code KnownCiRepos}
   * offers the trigger engine as candidates, and the whole of what {@code GET /ci/api/repositories}
   * answers.
   *
   * <p>Unsorted here on purpose: the trigger engine drops the result into a {@code TreeSet} and the
   * read surface sorts for its own reasons, so a database-side {@code order by} would be a third
   * opinion about an ordering neither caller takes from here.
   */
  public List<String> distinctRepoIds() {
    return getEntityManager()
        .createQuery("select distinct r.repoId from CiRun r where r.purpose = ?1", String.class)
        .setParameter(1, CiRunPurpose.BUILD)
        .getResultList();
  }

  /**
   * Whether this (event, repository, trigger file) has already produced a run.
   *
   * <p>A <b>cheap pre-check, not the guarantee</b>. The guarantee is the unique constraint on
   * {@code (trigger_event_id, repo_id, config_path)}, which is what survives a race and a restart;
   * this query only keeps a redelivery from reaching the insert and turning an expected outcome into
   * a caught exception in the log.
   */
  public boolean alreadyTriggered(String triggerEventId, String repoId, String configPath) {
    return count(
            "triggerEventId = ?1 and repoId = ?2 and configPath = ?3",
            triggerEventId,
            repoId,
            configPath)
        > 0;
  }

  /**
   * The id of the run this (event, repository, trigger file) already produced, or null. What a
   * repeated event id is answered with, so the caller finds the run it started (qits-1183).
   */
  public String triggeredRunId(String triggerEventId, String repoId, String configPath) {
    return find(
            "triggerEventId = ?1 and repoId = ?2 and configPath = ?3",
            triggerEventId,
            repoId,
            configPath)
        .firstResultOptional()
        .map(run -> run.id)
        .orElse(null);
  }

  /**
   * Rewrites the public coordinate of every run recorded for one repository — what {@code
   * RepositoryRenamed} makes of the rows a rename left stale.
   *
   * <p>{@code project_id} and {@code repo_name} are written once, at accept time, off whatever the
   * triggering event announced; nothing re-derives them afterwards, so a repository renamed in
   * qits-projects displays its old name in {@code GET /ci/api/repositories/summary} forever. The
   * storage id is what makes the repair possible at all: the bare on the git host does not move on a
   * rename, and neither does {@link eu.wohlben.qits.ci.entity.CiRun#repoId}, so every stale row is
   * found by the one column the rename did not touch.
   *
   * <p>A bulk update rather than a read-modify-write: the set of rows is unbounded (a repository's
   * whole history) and none of them is otherwise being mutated, so loading them into a persistence
   * context would buy nothing but the memory. Idempotent by construction — it writes the same two
   * values whatever the row held — which is what a durable consumer needs from an effect it may be
   * offered twice.
   *
   * @return how many rows were rewritten, which is zero for a repository this instance never ran
   */
  public int renameRepository(String repoId, String projectId, String repoName) {
    return update("set projectId = ?1, repoName = ?2 where repoId = ?3", projectId, repoName, repoId);
  }
}
