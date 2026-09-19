package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.control.CiCandidateRepos;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiQueueForecast;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunOrdering;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.dto.CiLiveStepDto;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.dto.CiRunOrderingDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The read side of {@code /ci/api} (docs/epics/qits-ci/): the recorded green/red per branch,
 * visible without any session (reads are open — the gate is advisory in the MVP), plus the one write
 * a person performs, cancelling a run.
 *
 * <p>The run is the entity and the repository is a <b>filter</b>, so the listing takes {@code
 * ?repositoryId=} rather than sitting under {@code /repositories/{repoId}/}. ci does not own
 * repositories — {@code ci_run.repo_id} is a plain string in ci's own database with no relation to
 * anything — so addressing runs beneath another context's aggregate claimed a containment that does
 * not exist, and put three services under one gateway prefix. {@code {runId}} stays in the path:
 * there it is identity, not scope.
 *
 * <p><b>The reads take the pair {@code qits:admin, qits:system}; of the two run-scoped writes the
 * cancel takes {@code qits:admin} alone and the retry adds {@code qits:agent}</b> (see {@link
 * #retryRun} for why an agent presses that one and what scopes it). qits:system is the machine role and qits:admin the human one, and a
 * machine that has to poll a run it asked for — qits-platform-maintenance waits out every bump this
 * way — must not be granted a person's role to do it. What mutates is not widened: {@link #cancelRun}
 * and {@link #retryRun} carry their own method-level list, which replaces the class's rather than
 * adding to it.
 *
 * <p><b>There is one write here a machine really does perform, and it is the exception that proves
 * that rule.</b> {@link #cancelReleaseRequestRuns} is qits-projects withdrawing a release request's
 * CI, addressed by {@code (repoId, releaseRequestId)} because the runs it stops carry a sha nobody
 * pushed. It keeps the class's role pair — a peer service and an operator both legitimately call it —
 * and adds this resource's only {@code MachineAuth} check, on the machine arm. The rule that
 * matters is the one {@code MachineGuardTest} enforces: a new write that omits the guard ships
 * unguarded and nothing says so, so every write on this surface has a case in that file.
 *
 * <p><b>That check demanded {@code project=*} until 2026-09-05, and no real caller could satisfy
 * it.</b> The only sender of this route is qits-projects' {@code HttpQaRunCancellations}, fired
 * when a release request is superseded, and the bearer it presents is the {@code
 * <env>-qits-projects} client's — which qits-idp mints with {@code groups} of {@code qits:system}
 * and {@code clients/<id>}, and <b>no structured claims at all</b>.
 * So every supersession was answered 403, swallowed at debug on the sender's side (that hop is
 * best-effort by design — the gate is correlated by merged sha and stayed correct), and superseded
 * QA runs simply kept running. The door now asks {@link #cancellationScope()}, which is {@code
 * CiEventController.scopeOf}'s ruling applied to a request that names its target. Read that method
 * before changing either.
 *
 * <p><b>Nothing here is hidden from the OpenAPI document any more.</b> The two reads used to carry
 * {@code @Operation(hidden = true)} on the criterion "does a client consume it, does a person invoke
 * it" — machine surfaces stay out, the cancel button goes in. The criterion was right and its answer
 * changed: qits-spa-ci reads both of these on every page it draws, so they are the JSON API a
 * first-party client consumes. Leaving them hidden would mean {@code docs/openapi.yml} — a file this
 * repo commits precisely so that a surface change shows up as a diff — omitted the entire contract
 * that client depends on, and a breaking change to {@link CiRunDto} would have landed with an empty
 * diff. The intake in {@code CiEventController} stays hidden: it really is machine-only,
 * token-guarded, and has a cross-repo wire contract with qits-artifacts.
 *
 * <p><b>Three of the four reads are literal segments under {@link #getRun}'s template</b> — {@code
 * /active}, {@code /finished} and now {@code /queue} — and only JAX-RS' rule that a literal outranks
 * a template keeps them from resolving to a lookup for a run of that name. Each has a case in {@code
 * CiPipelineBoundaryTest}, because a ranking regression surfaces as a client 404 and nothing else.
 * None of them adds a Vert.x route, so {@code quarkus.quinoa.ignored-path-prefixes} is unchanged:
 * {@code /api} already covers the lot.
 */
@Path("/runs")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class CiRunController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

  @Inject CiStepRelay relay;

  @Inject ObjectMapper objectMapper;

  /** The machine half of {@link #cancelReleaseRequestRuns}' guard; see that method. */
  @Inject MachineAuth machineAuth;

  /** Read only to tell a machine caller from a forwarded session — never as a security state. */
  @Inject SecurityIdentity identity;

  /**
   * Where "which project is this repository in" is answered, and the <b>same</b> catalogue {@code
   * CiEventController}'s scoped evaluation is judged against — deliberately, so that "the token's
   * project covers it" means one thing on both machine doors rather than two.
   *
   * <p>It is read on one arm only: a machine caller that presents a {@code project} claim naming a
   * single project. {@code project=*}, an unscoped platform-wide caller and an operator's forwarded
   * session never reach it, so the route's real traffic costs no listing read.
   */
  @Inject CiCandidateRepos candidates;

  /**
   * The roles that admit a caller naming no project — {@code CiEventController}'s constants, spelled
   * again here for the reason that class spells them: a role is a string qits-idp issues and this
   * repository holds no vocabulary for it. {@code qits:system} is a service calling a service and,
   * under the open calling model (2026-09-13), may act for every project; the admin half is plain
   * {@code qits:admin}, since there is no platform-scoped administrator. Read that class's javadoc
   * for why the split was retired rather than finished.
   */
  private static final String SYSTEM_ROLE = "qits:system";

  private static final String ADMIN_ROLE = "qits:admin";

  public record ListRunsResponse(List<CiRunDto> runs) {}

  public record CancelRunRequest(String reason) {}

  /**
   * What qits-projects sends to withdraw a release request's CI: the repository, and the request
   * whose work is no longer wanted. Both are required — see {@link #cancelReleaseRequestRuns}.
   */
  public record CancelReleaseRequestRunsRequest(
      @Schema(description = "The repository whose runs to stop", required = true) String repoId,
      @Schema(description = "The release request whose work is withdrawn", required = true)
          String releaseRequestId) {}

  /** Which runs the cancellation actually reached — empty when there was nothing left in flight. */
  public record CancelReleaseRequestRunsResponse(List<String> runIds) {}

  /** The run a retry created; poll it like any other. */
  public record RetryRunResponse(String runId) {}

  /**
   * What qits-projects sends to re-ask one phase of one release request: the repository, the request,
   * and which phase of the release. All three are required — see {@link #rerunReleaseRequestPhase}
   * for why this triple and not a run id.
   */
  public record RerunReleaseRequestPhaseRequest(
      @Schema(description = "The repository whose run to re-fire", required = true) String repoId,
      @Schema(description = "The release request the run serves", required = true)
          String releaseRequestId,
      @Schema(
              description =
                  "Which phase of the release to re-fire: RELEASE_REQUEST (QA) or RELEASE (publish)",
              required = true,
              enumeration = {"RELEASE_REQUEST", "RELEASE"})
          String phase) {}

  /**
   * A repository's runs, newest-first — without step output (fetch a single run for that). The
   * filter is required and validated: an unscoped listing would return every run on the instance,
   * and a missing one must say so rather than answer with an empty list.
   *
   * <p>{@code ?limit=} is optional and bounds the answer to the newest {@code n}; absent, the
   * listing is unbounded, so nothing that predates the parameter changes. The ordering is what makes
   * that a total answer rather than an arbitrary sample. There is deliberately <b>no {@code
   * ?offset=} and no cursor</b>: an offset over a list that grows at the head re-shows rows under
   * concurrent inserts, and the two things anyone actually wants — the newest n, then one specific
   * run — are both already covered. A real history walk wants {@code before=<createdAt>}, and that
   * waits for a requirement.
   *
   * <p><b>This is the one route where the step widening is paid per historical run, and the figure
   * belongs here beside the unboundedness that causes it.</b> The rows carry {@code steps} now, and
   * this listing is unbounded when {@code ?limit=} is absent — and qits-ci-frontend deliberately
   * re-asks without a limit once a limited answer comes back full. So an absent limit costs one
   * indexed step read per run in the repository's whole history, plus a few dozen bytes per step on
   * the wire. It is still the right trade, because what is carried is a step's two instants and its
   * index and not its log, but anyone weighing a <em>further</em> widening of these rows should
   * weigh it against that multiplier rather than against the bounded listings'.
   *
   * <p>The parameter is taken as a {@code String} and parsed here rather than bound to an {@code
   * Integer}, because JAX-RS answers a query-parameter conversion failure with a <b>404</b>. A
   * mistyped limit is a bad request, and it must arrive as one through {@link CiExceptionMapper}'s
   * {@code {"message": …}} envelope like every other rejected input on this surface. A present but
   * empty value is read as absent: {@code ?limit=} is what an unfilled template produces, and
   * refusing it buys nothing.
   */
  @GET
  // Every read also takes qits:agent. It sits on each read rather than on the class, because the
  // class list also guards the cancellations write, and agents do not write here.
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "List a repository's CI runs, newest first")
  @APIResponse(
      responseCode = "200",
      description =
          "The repository's runs with their step boundaries, without step output")
  @APIResponse(responseCode = "400", description = "The repository id is missing or invalid, or the limit is not a positive integer")
  public ListRunsResponse listRuns(
      @Parameter(description = "The repository whose runs to list — required", required = true)
          @QueryParam("repositoryId")
          String repositoryId,
      // Declared as the integer it is, though it binds as a String: the document describes the
      // contract, and taking it as a String is how a bad value becomes a 400 instead of a 404.
      @Parameter(
              description = "Return only the newest n runs; omit for all of them",
              schema = @Schema(type = SchemaType.INTEGER, minimum = "1"))
          @QueryParam("limit")
          String limit) {
    CiIdentifiers.requireRepoId(repositoryId);
    return listing(runService.runsFor(repositoryId, parseLimit(limit)));
  }

  /**
   * <b>The one shape every run LISTING on this surface answers with</b>: each row with its step
   * boundaries and its live step, both without output, and — for whichever rows are not finished —
   * the queue's answers about them.
   *
   * <p><b>The three listings carry the same thing now, and that is the change rather than an
   * accident.</b> Their documented contract was always "without step <em>output</em>", and output
   * was the only reason the whole step object was dropped: it is unbounded, repository-controlled
   * and the only heavy part of a row. A step's two host-stamped instants and its index are a few
   * dozen bytes, and they are what makes a segmented progress bar <em>boundary-true</em> — one
   * bubble per planned step, each filling against its own expected duration, none of them beginning
   * to fill before its step really started. Carrying them on one listing and not the others was the
   * worst of the three options: a run tree drawing a finished run as an entirely empty bar is not a
   * conservative answer, it is a wrong-looking one, and a bar whose truthfulness depends on which
   * page you are looking at is worse than either answer applied consistently.
   *
   * <p><b>The forecast is attached to every non-terminal row of a response and to no terminal
   * one.</b> That is the whole rule, and it holds whichever route produced the response: a {@code
   * QUEUED} or {@code RUNNING} run in a repository's own listing gets the same position and the
   * same ETAs it would get from {@code /active} or {@code /queue}, because an ETA that depended on
   * which page asked for it would be the same inconsistency the paragraph above refuses. A finished
   * run gets none — it has left the queue, so there is no position it could hold — which is also
   * what keeps this cheap: the extra read happens only when the response really holds something in
   * flight, so a page of history pays for nothing.
   *
   * <p><b>One {@code Instant} per response, and it is the snapshot's.</b> Every millisecond on every
   * row below is relative to the same moment; two rows of one body relative to two instants would
   * be two durations that cannot be compared to each other.
   */
  private ListRunsResponse listing(List<CiRun> forRuns) {
    boolean anythingInFlight = forRuns.stream().anyMatch(CiRunController::unfinished);
    return listing(forRuns, anythingInFlight ? runService.queueSnapshot() : null);
  }

  /**
   * {@link #listing(List)} against a snapshot the caller already holds — which {@code /active} does,
   * because the snapshot is also where it got its rows and their order from. Re-reading the queue
   * for the same response would be a second read of the same table and, worse, a second instant.
   *
   * @param queue the queue this response's durations are relative to, or null when the response
   *     holds nothing unfinished and no forecast was worth computing
   */
  private ListRunsResponse listing(List<CiRun> forRuns, CiRunService.Snapshot queue) {
    Map<String, List<CiStep>> stepsByRun = runService.stepsForAll(forRuns);
    return new ListRunsResponse(
        forRuns.stream()
            .map(
                run -> {
                  List<CiStep> steps = stepsByRun.getOrDefault(run.id, List.of());
                  CiRunDto dto =
                      mapper.toDtoWithoutStepOutput(run, steps, liveStep(run, steps, false));
                  // withQueueFacts leaves a row the snapshot does not name untouched, so "no
                  // terminal row is forecast" holds by construction rather than by a second test
                  // of the status here: a finished run is in neither half of the queue.
                  return queue == null ? dto : withQueueFacts(run, dto, queue);
                })
            .toList());
  }

  /** Whether the run is still in the queue's world — {@code QUEUED} or {@code RUNNING}. */
  private static boolean unfinished(CiRun run) {
    return run.status == CiRunStatus.QUEUED || run.status == CiRunStatus.RUNNING;
  }

  /** {@code null} for absent or blank; a positive int; otherwise a 400. */
  private static Integer parseLimit(String limit) {
    if (limit == null || limit.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(limit.trim());
    } catch (NumberFormatException notANumber) {
      throw new BadRequestException("Invalid limit");
    }
  }

  /**
   * Everything CI has accepted and not finished — {@code QUEUED} or {@code RUNNING} — across every
   * repository, newest first. No parameters, and step output is not carried (fetch a single run for
   * that).
   *
   * <p><b>The one read here that is not scoped to a repository</b>, and the exception is the whole
   * point: "what is CI doing right now" has no repository to scope to, and a client that had to ask
   * per repository would have to know the repositories first and would still see a different instant
   * in each answer. It became answerable only when a queued run became a row — before that, half of
   * this list lived in an executor's queue where nothing could read it.
   *
   * <p>It carries no {@code ?limit=} because it needs none: what is active is bounded by accepted
   * work and the configured worker pool, not by how long the instance has been up.
   *
   * <p>{@code /active} is a literal segment and {@link #getRun}'s is a template, so JAX-RS matches
   * this one first — a run whose id is the string {@code active} is not addressable, and no run id
   * this service mints ever is.
   */
  @GET
  @Path("/active")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "Every queued or running CI run, all repositories, newest first")
  @APIResponse(
      responseCode = "200",
      description =
          "The active runs with their step boundaries and queue position, without step output")
  public ListRunsResponse listActiveRuns() {
    // The snapshot is both the rows and the forecast, so this route reads the table once. The
    // listing keeps its documented newest-first order and the CLAIM order rides the rows as
    // queuePosition — which is strictly better than re-sorting a listing whose order is a contract.
    CiRunService.Snapshot queue = runService.queueSnapshot();
    return listing(queue.activeNewestFirst(), queue);
  }

  /**
   * The run queue as qits-ci itself sees it: what is executing, and what is waiting <b>in claim
   * order</b>, each row carrying where it sits and when the queue is expected to reach it.
   *
   * <p><b>No other route answers this, and the gap was not cosmetic.</b> The order was computed and
   * thrown away — {@link eu.wohlben.qits.ci.control.CiRunOrdering}'s only production caller is the
   * claim loop — and {@code /active} deliberately answers newest-first, which is a different
   * question with a plausible-looking answer. So "which build is next" had no reader, and the only
   * way a client could have one was to re-implement four ordering criteria, a topological pass and a
   * private rank table against rows that do not carry half of what the decision reads. That second
   * implementation would not crash; it would disagree, quietly, with the order the claim loop acts
   * on. <b>Queue-wait prediction is this service's to compute.</b>
   *
   * <p><b>{@code generatedAt} is what makes the durations interpretable, and it is why it is on the
   * envelope rather than on each row.</b> Every {@code expectedStartInMillis} and {@code
   * expectedFinishInMillis} below is a number of milliseconds <em>from that instant</em> — never a
   * clock time, which would read as a promise, be rendered in a timezone this service knows nothing
   * about and be wrong by however long the page has been open. One stamp per response is what lets
   * two rows' durations be compared to each other; a per-row instant would be two answers to "now".
   *
   * <p><b>{@code running} is newest-first</b>, the same order {@code /active} documents, so a client
   * holding both sees one repository's run in the same place in each. <b>{@code queued} is in
   * suggested claim order</b> — position 0 is the run a free worker would take next — which is the
   * whole reason this route exists and is the one listing here whose order is not chronological.
   *
   * <p><b>The rows are {@link CiRunDto}, not a queue-specific shape.</b> A third copy of a run's
   * wire shape would drift from this one exactly as a second copy of the ordering math would, and
   * for the same reason: nobody notices until the two disagree. They carry no {@code steps} and no
   * {@code live} — this route is about <em>when</em>, and a client that wants a run's progress has
   * {@code /active} for the bar and {@code /runs/{runId}} for the transcript.
   *
   * <p><b>A prediction is an estimate and never a promise.</b> A run with no ETA says so in {@code
   * predictionUnavailable} rather than simply lacking one, and a run behind an unpredicted one is
   * unknown too — with {@code RUN_AHEAD_HAS_NO_PREDICTION}, which is a different sentence to the
   * person waiting than "your pipeline has never been measured". Nothing is silently skipped: both
   * lists are total, so their lengths really are the queue's.
   *
   * <p>{@code /queue} is a literal segment under {@link #getRun}'s template, exactly as {@code
   * /active} and {@code /finished} are, and only JAX-RS' ranking of a literal above a template keeps
   * them apart. {@code CiPipelineBoundaryTest} asserts that rather than assuming it, because the
   * regression would surface as a client 404 and nothing else. It adds no Vert.x route, so {@code
   * quarkus.quinoa.ignored-path-prefixes} is unchanged — {@code /api} already covers it.
   */
  @GET
  @Path("/queue")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "The run queue in claim order, with each run's expected start and finish")
  @APIResponse(
      responseCode = "200",
      description = "The queue at one instant",
      content = @Content(schema = @Schema(implementation = QueueResponse.class)))
  public QueueResponse queue() {
    CiRunService.Snapshot queue = runService.queueSnapshot();
    return new QueueResponse(
        queue.concurrentBuilds(),
        queue.generatedAt(),
        queue.running().stream().map(run -> withQueueFacts(run, mapper.toDto(run), queue)).toList(),
        queue.queuedInClaimOrder().stream()
            .map(ordered -> withQueueFacts(ordered.run(), mapper.toDto(ordered.run()), queue))
            .toList());
  }

  /**
   * The queue envelope: how many slots there are, the instant every duration in it is relative to,
   * and the two halves of the queue.
   *
   * @param concurrentBuilds how many runs this deployment executes at once — the number the
   *     forecast really modelled with, so a reader can see why the queue moves as slowly as it does
   * @param generatedAt the instant every {@code expectedStartInMillis} and {@code
   *     expectedFinishInMillis} in this body is measured from. <b>It is the only absolute instant
   *     here, and that is deliberate</b>: one stamp makes a relative duration interpretable without
   *     any row having to carry a predicted clock time
   * @param running the {@code RUNNING} runs, newest first
   * @param queued the {@code QUEUED} runs in suggested claim order, each carrying its {@code
   *     queuePosition}, its {@code ordering} and its ETAs
   */
  public record QueueResponse(
      int concurrentBuilds, Instant generatedAt, List<CiRunDto> running, List<CiRunDto> queued) {}

  /**
   * The run's row stamped with what the queue says about it, or unchanged when the queue says
   * nothing — which is what a run that finished between the snapshot and this call looks like.
   *
   * <p>A queued run gets its position, its ordering and both ETAs; a running one gets only an
   * expected finish, because it has started and there is nothing left to forecast about its start.
   */
  private CiRunDto withQueueFacts(CiRun run, CiRunDto dto, CiRunService.Snapshot queue) {
    CiRunOrdering.OrderedRun ordered = queue.orderingOf(run.id);
    if (ordered != null) {
      CiQueueForecast.QueuedForecast forecast = queue.queuedForecastOf(run.id);
      return dto.withQueueFacts(
          ordered.position(),
          forecast == null ? null : forecast.expectedStartInMillis(),
          forecast == null ? null : forecast.expectedFinishInMillis(),
          forecast == null
              ? null
              : unavailability(forecast.expectedStart(), forecast.expectedFinish()),
          CiRunOrderingDto.of(ordered));
    }
    CiQueueForecast.RunningForecast inFlight = queue.runningForecastOf(run.id);
    if (inFlight != null) {
      return dto.withQueueFacts(
          null,
          null,
          inFlight.expectedFinishInMillis(),
          unavailability(null, inFlight.expectedFinish()),
          null);
    }
    return dto;
  }

  /**
   * Why this run has no ETA, as the reason's own enum name, or null when it has one.
   *
   * <p><b>The finish's reason wins where both are absent</b>, and that is not arbitrary: the
   * forecast already resolves own-unknown in favour of the finish, so the finish carries the more
   * specific fact — "this pipeline has never been measured" rather than "the queue ahead of you is
   * unknowable" — and it is the one the run's owner can do something about. "When will this be
   * done" is also the question actually being asked.
   */
  private static String unavailability(CiQueueForecast.Eta start, CiQueueForecast.Eta finish) {
    if (finish != null && !finish.isKnown()) {
      return finish.reason().name();
    }
    if (start != null && !start.isKnown()) {
      return start.reason().name();
    }
    return null;
  }

  /**
   * The step a run is executing right now, or null — the in-memory relay, filtered so that no step
   * is ever handed over twice.
   *
   * <p><b>Factored rather than copied, because the filter is the subtle half.</b> A step's buffer
   * outlives it by the one transaction that writes its row, and the next step's buffer replaces it;
   * {@code live} means "the step with no row yet", so during that window it means nothing and a
   * client handed the same step once as a row and once as live would draw it twice. That reasoning
   * is easy to leave behind when a second listing starts reading the relay, which is exactly what
   * {@code /active} now does — so there is one implementation of it and both callers use it.
   *
   * @param withOutput whether to carry what the step has printed. The single-run read is a person
   *     following one build and says yes; a listing draws boundaries and says no, since the output
   *     is the only heavy part of the object and no listing has an affordance that renders it
   */
  private CiLiveStepDto liveStep(CiRun run, List<CiStep> steps, boolean withOutput) {
    if (run.status != CiRunStatus.RUNNING) {
      return null;
    }
    return relay
        .snapshot(run.id)
        .filter(snapshot -> steps.stream().noneMatch(s -> s.stepIndex == snapshot.stepIndex()))
        .map(
            snapshot ->
                new CiLiveStepDto(
                    snapshot.stepIndex(),
                    snapshot.startedAt(),
                    withOutput ? snapshot.output() : null))
        .orElse(null);
  }

  /**
   * The newest finished runs — anything that is neither {@code QUEUED} nor {@code RUNNING} — across
   * every repository, newest first. Step output is not carried, exactly as the two listings above.
   *
   * <p><b>The sibling of {@code /active}, and it exists because that one cannot answer this.</b> A
   * client drawing "what is CI doing, and what did it just finish" had no way to ask the second half:
   * the active list holds only the two non-terminal statuses, the repository listing demands a
   * repository, and a per-repository fan-out is the n+1 {@code
   * CiRepositoryController#listRepositorySummaries} exists to spare a client — and it would still be
   * wrong, since two of the newest five finished runs can belong to one repository. The two lists are
   * complements over the same table, so a run that leaves one arrives in the other.
   *
   * <p>It <b>does</b> carry {@code ?limit=} where {@code /active} does not, and the asymmetry is the
   * whole difference between them: what is active is bounded by accepted work and the configured
   * worker pool, while what is finished grows for as long as the instance has been up. Absent means
   * {@link CiRunService#DEFAULT_FINISHED_LIMIT} rather than unbounded — the opposite of the
   * repository listing's default, because there is no repository here to make "all of them" a bounded
   * question. It is parsed by the same {@link #parseLimit} for the same 400-not-404 reason, and an
   * ask above {@link CiRunService#MAX_FINISHED_LIMIT} is answered with that many rather than refused.
   *
   * <p>{@code /finished} is a literal segment, so JAX-RS matches it before {@link #getRun}'s
   * template — the same ranking {@code /active} relies on, asserted rather than assumed in {@code
   * CiPipelineBoundaryTest}. It adds no Vert.x route of its own, so {@code
   * quarkus.quinoa.ignored-path-prefixes} is unchanged: {@code /api} already covers it.
   */
  @GET
  @Path("/finished")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "The newest finished CI runs, all repositories, newest first")
  @APIResponse(
      responseCode = "200",
      description = "The finished runs with their step boundaries, without step output")
  @APIResponse(responseCode = "400", description = "The limit is not a positive integer")
  public ListRunsResponse listFinishedRuns(
      @Parameter(
              description = "Return the newest n finished runs; omit for 5, capped at 100",
              schema = @Schema(type = SchemaType.INTEGER, minimum = "1", maximum = "100"))
          @QueryParam("limit")
          String limit) {
    // Every row here is terminal by this listing's own predicate, so nothing in it is forecast and
    // the queue is never read — the rule stated on `listing`, arriving at its cheap case.
    return listing(runService.finishedRuns(parseLimit(limit)));
  }

  /**
   * One run with its steps, exit codes and captured output — plus, while it is running, the {@code
   * live} object holding the step in flight and what it has printed so far.
   *
   * <p>Step rows are written at each step's end, so a mid-run poll legitimately sees fewer steps
   * than the pipeline declared; {@code live} is what makes that legible instead of looking like a
   * run with holes in it. It comes from memory rather than the database and is dropped the moment
   * the run closes — after that the persisted tails are the whole record. Following along is
   * polling this endpoint; there is no SSE and no WebSocket for it.
   *
   * <p><b>A run that has not finished also carries the queue's answers about it</b> — its {@code
   * queuePosition} and {@code ordering} while it is {@code QUEUED}, and its expected finish while it
   * is {@code QUEUED} or {@code RUNNING}, all relative to this response's own instant. That costs
   * one extra read of the active rows and is paid only where there is something to say: a finished
   * run has left the queue, so it is not forecast at all and every one of those fields is null.
   */
  @GET
  @Path("/{runId}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "One CI run with its steps, output and — while it runs — its live step")
  @APIResponse(responseCode = "200", description = "The run")
  @APIResponse(responseCode = "404", description = "No such run")
  public CiRunDto getRun(@PathParam("runId") String runId) {
    CiRun run = runService.requireRun(runId);
    List<CiStep> steps = runService.stepsFor(runId);
    CiRunDto dto = mapper.toDto(run, steps, liveStep(run, steps, true));
    // Only an unfinished run has a queue to be placed in, and only then is the extra read made.
    if (run.status != CiRunStatus.QUEUED && run.status != CiRunStatus.RUNNING) {
      return dto;
    }
    return withQueueFacts(run, dto, runService.queueSnapshot());
  }

  /**
   * Stop a running run: its in-flight step's container is asked to die, that step is recorded failed
   * with "cancelled" in its output, and the rest are skipped.
   *
   * <p>202 rather than 200, because the run is not finished when this returns — the container has
   * been asked, its daemon still has to answer with a terminal frame, and the worker still has rows
   * to write. Poll the run for the outcome. Cancelling a run that has already finished is a 409: it
   * has nothing to stop, and a cheerful 202 would be a claim the caller cannot check.
   *
   * <p>A run that is still {@code QUEUED} can be cancelled too, and it is the cheap case — there is
   * no container to ask, so the run is recorded {@code FAILED} with no steps and the worker never
   * picks it up. Still a 202: the shape of the answer does not change with how far along the run
   * was, and the caller polls either way.
   *
   * <p>Deliberately <b>not</b> {@code @Operation(hidden = true)}, unlike everything else in this
   * service. The intake and the run reads are machine surfaces; this one is a button a person
   * presses, so it belongs in the API document. It sits on the same deployment-policy-guarded
   * surface as the run reads and carries no token of its own — the single-user stance.
   */
  @POST
  @Path("/{runId}/cancel")
  // A method-level list REPLACES the class-level one rather than adding to it, which is exactly what
  // this needs: the reads above take the pair, and cancelling a build stays a person's.
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  @Consumes(MediaType.WILDCARD)
  @Operation(summary = "Cancel a queued or running CI run")
  @APIResponse(responseCode = "202", description = "The run has been stopped or asked to stop")
  @APIResponse(responseCode = "404", description = "No such run")
  @APIResponse(responseCode = "409", description = "The run has already finished, so there is nothing to stop")
  public Response cancelRun(
      @PathParam("runId") String runId,
      @org.eclipse.microprofile.openapi.annotations.parameters.RequestBody(
              required = false,
              description = "Optional human-readable cancellation reason",
              content =
                  @org.eclipse.microprofile.openapi.annotations.media.Content(
                      schema = @Schema(implementation = CancelRunRequest.class)))
          String payload) {
    runService.cancel(runId, cancellationReason(payload));
    return Response.accepted().build();
  }

  /**
   * Stop every unfinished run one repository has for one release request — the door qits-projects
   * knocks on when a request is withdrawn, closed or re-scoped.
   *
   * <p><b>Addressed by the pair, not by run id, and that is the contract rather than a convenience.</b>
   * The runs it cancels were triggered by a {@code ReleaseRequestChanged} and build the request's
   * backing branch, whose sha is a fold nobody pushed and is rewritten by the next re-fold. So the
   * caller holds no run id and no stable sha — the request id is the only handle that survives, which
   * is exactly why {@code ci_run.release_request_id} exists. Both halves are required: one request
   * folds many repositories and one repository carries many open requests, so either alone would
   * cancel a sibling's build.
   *
   * <p><b>202 and nothing else on the happy path.</b> A queued run is terminal before this returns; a
   * running one has only been <em>asked</em> to stop, and its container still has to answer. Nothing
   * left in flight is a 202 with an empty list rather than a 404: the caller asked for a state — this
   * request's work is not running — and that state holds. Repeating the call is therefore safe.
   *
   * <p><b>Nothing cancelled here publishes a verdict.</b> A {@code CANCELLED} run announces
   * neither {@code BuildSuccessful} nor {@code BuildFailed}, so the release gate on the other side
   * never sees a withdrawn request's stopped build as a failure. That is {@code CiRunService}'s
   * standing contract, and it is the property this endpoint depends on.
   *
   * <p><b>The guard is the machine one, and a person still reaches it.</b> A machine caller is judged
   * on its token exactly as {@code CiEventController}'s is — see {@link #cancellationScope()} for the
   * arms and {@link #requireRepositoryInProject} for what a project-scoped one buys. A caller
   * that presents no token is not a machine and never reaches the check: it has already been judged
   * by the class-level roles, which is the forwarded {@code X-Qits-User}/{@code X-Qits-Roles} session
   * an operator arrives on. Both callers are real for this route, which is why the check is on the
   * machine arm rather than over the whole method.
   */
  @POST
  @Path("/cancellations")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Cancel every unfinished run a repository has for one release request")
  @APIResponse(
      responseCode = "202",
      description = "The request's runs have been stopped or asked to stop",
      content = @Content(schema = @Schema(implementation = CancelReleaseRequestRunsResponse.class)))
  @APIResponse(responseCode = "400", description = "The repository id or the release request id is missing or invalid")
  @APIResponse(
      responseCode = "403",
      description =
          "The token covers this repository for nobody — no project claim and no platform-wide role,"
              + " or a project claim this instance cannot place the repository in")
  public Response cancelReleaseRequestRuns(CancelReleaseRequestRunsRequest request) {
    if (request == null) {
      throw new BadRequestException("A repository id and a release request id are required");
    }
    CiIdentifiers.requireRepoId(request.repoId());
    String releaseRequestId = requireReleaseRequestId(request.releaseRequestId());
    String projectScope = cancellationScope();
    if (projectScope != null) {
      requireRepositoryInProject(request.repoId(), projectScope);
    }
    return Response.accepted()
        .entity(
            new CancelReleaseRequestRunsResponse(
                runService.cancelReleaseRequestRuns(request.repoId(), releaseRequestId)))
        .build();
  }

  /**
   * A release request id is qits-projects' opaque string and this service validates it as one:
   * present, and short enough to be the id of a request this platform issued. It reaches nothing but
   * a bound query parameter, so there is no shape to check beyond that — inventing one here would be
   * this service holding an opinion about another context's identifiers.
   */
  private static String requireReleaseRequestId(String releaseRequestId) {
    if (releaseRequestId == null || releaseRequestId.isBlank()) {
      throw new BadRequestException("A release request id is required");
    }
    String trimmed = releaseRequestId.trim();
    if (trimmed.length() > MAX_RELEASE_REQUEST_ID_LENGTH) {
      throw new BadRequestException(
          "A release request id is at most " + MAX_RELEASE_REQUEST_ID_LENGTH + " characters");
    }
    return trimmed;
  }

  /** What {@code ci_run.release_request_id} holds, so a longer one names no run here. */
  private static final int MAX_RELEASE_REQUEST_ID_LENGTH = 255;

  /**
   * Which repositories this caller may withdraw a release request's CI in. {@code null} is every
   * one of them; a non-null value is a project, and {@link #requireRepositoryInProject} then decides
   * whether the named repository is in it.
   *
   * <p><b>This is {@code CiEventController.scopeOf}'s ruling, arm for arm</b>, and the sameness is
   * the point: two machine doors on one service that read a {@code project} claim differently is a
   * platform where "what does my token cover" has no answer. The order is the contract:
   *
   * <ol>
   *   <li><b>Not a machine</b> — the operator's forwarded session, already judged by the class's
   *       roles. Every repository, exactly as before.
   *   <li><b>{@link MachineAuth#require()}</b> — 401 with no token once the gate is on, 403 for one
   *       addressed to another service. Presence and audience only.
   *   <li><b>{@code project=<p>}</b> — that project, and the narrowing is what admits the caller.
   *       This is the arm that is <em>new</em> here, and it is new because the old comment's premise
   *       expired: "qits-ci owns no project entity and can resolve a repository to no project" was
   *       true when it was written and has not been since {@code CiRepoRef} started carrying {@code
   *       projectId} off qits-projects' catalogue. The lookup that was missing is in hand.
   *   <li><b>{@code project=*}</b> — every repository. Read on the token side only, so a caller
   *       cannot widen its own check by naming {@code "*"}: nothing here compares it to a target.
   *   <li><b>No {@code project} claim at all</b> — admitted on {@code qits:system} or {@code
   *       qits:admin}; refused without either.
   * </ol>
   *
   * <p><b>The last arm was the fix for the live 403, and it has since widened again.</b> Measured
   * 2026-09-05: qits-projects' bearer for this hop is the {@code <env>-qits-projects} client's, and
   * qits-idp mints that client {@code groups} of {@code qits:system} and {@code clients/<id>} — no
   * {@code project} claim, because the bootstrap grants one to exactly
   * two clients and this is neither. Demanding {@code project=*} therefore refused the route's only
   * sender on every call, and the loss was quiet twice over: the sender logs a non-2xx at debug
   * because the hop is best-effort (the release gate is correlated by merged sha and stays correct
   * without it), so what a supersession actually cost was a build agent, indefinitely.
   *
   * <p><b>The ordinary machine role used to not be enough, and under the open calling model
   * (2026-09-13) it is.</b> {@code qits:system} names a service calling a service, and such a
   * caller may act for every project — the cancellation is bounded regardless (it stops the runs of
   * one named release request in one named repository and publishes no verdict), so nothing is
   * widened beyond what the role already means. What stays refused is a commissioned credential: a
   * {@code ci-run} or {@code agent} context is minted {@code qits:ci-run}/{@code qits:agent}, never
   * {@code qits:system}, and neither role is in this resource's {@code @RolesAllowed} — such a token
   * never reaches this class's writes at all, so it stays bound to whatever the read routes that do
   * accept it narrow it to.
   */
  private String cancellationScope() {
    if (!MachineIdentity.isMachine(identity)) {
      return null;
    }
    machineAuth.require();
    String project = MachineIdentity.claim(identity, QitsClaims.PROJECT).orElse(null);
    if (project == null) {
      if (!identity.hasRole(ADMIN_ROLE) && !identity.hasRole(SYSTEM_ROLE)) {
        throw new ForbiddenException(
            "Token carries no "
                + QitsClaims.PROJECT
                + " claim and no platform-wide role, so it names no repository to cancel in");
      }
      return null;
    }
    return QitsClaims.ANY.equals(project) ? null : project;
  }

  /**
   * A project-scoped caller may cancel in the repositories the catalogue places in its project, and
   * in no others.
   *
   * <p><b>A check rather than a narrowing, because this request names its target.</b> The trigger
   * can refuse a foreign project by construction — it evaluates a set and the set is filtered — but
   * a cancellation is addressed at one {@code repoId}, so the question has to be asked out loud.
   * Same catalogue, same answer, one status code: 403.
   *
   * <p><b>A repository the catalogue cannot place is in no project at all</b>, which is the
   * fail-closed half and matches {@code CiEventTriggerService.inProject} exactly. Two kinds have no
   * {@code projectId} — one qits-ci knows only from its own run rows, and every one of them when the
   * qits-projects listing is unreachable — and in both cases the honest answer is "this instance
   * cannot prove that repository is yours". An unreadable catalogue therefore refuses a scoped
   * caller rather than quietly granting it the platform; the callers that must not be blocked by a
   * listing outage are the unscoped ones, and they never reach here.
   */
  private void requireRepositoryInProject(String repoId, String project) {
    for (CiRepoRef candidate : candidates.candidates()) {
      if (repoId.equals(candidate.repoId()) && project.equals(candidate.projectId())) {
        return;
      }
    }
    throw new ForbiddenException(
        "Token " + QitsClaims.PROJECT + " claim does not cover repository " + repoId);
  }

  /**
   * Run this run's pipeline again, unchanged — the same repository, the same trigger file, the same
   * commit, the same release request.
   *
   * <p>A person's button, for the case a red run is about the platform rather than about the code: a
   * flaked container, a registry that was down, a step that hit its deadline on a busy host. Nothing
   * about the work has changed, so nothing needs re-folding and no new event needs waiting for — the
   * new run builds the very sha the old one built, and its verdict lands on that commit exactly as
   * the first one's would have.
   *
   * <p>202 rather than 201: the answer is a run that has been <em>accepted</em>, is {@code QUEUED}
   * and has not started, so the caller polls {@code GET /ci/api/runs/{runId}} with the id in the body
   * like it does after a trigger. Retrying a run that has not finished is a 409 — the question is
   * still being answered, and two runs racing for one verdict is not what was asked for.
   *
   * <p><b>It is a person's write that an agent may also press, and the widening is scoped rather
   * than blanket.</b> The roles are {@code qits:admin} and {@code qits:agent}. A coding agent is the
   * caller standing in front of the case this endpoint exists for: a release-request gate that went
   * red for a reason that is the platform's and not the code's, which the agent can neither re-ask
   * nor explain away — its only other door is an empty commit on a source branch and a second full
   * gate. An agent holds {@code qits:agent} and nothing else, so without this it was answered 403
   * on the one button that fixes what it is looking at.
   *
   * <p><b>What makes that safe is the scope check, not the role.</b> A caller that is not
   * platform-wide is judged exactly as {@link #cancelReleaseRequestRuns} and {@link
   * #rerunReleaseRequestPhase} judge one — {@link #cancellationScope()} for the arms, then {@link
   * #requireRepositoryInProject} against the <em>run's own</em> repository — so an agent may
   * re-fire a build in its own project and is refused one in somebody else's, and a repository the
   * catalogue cannot place is in no project at all. qits-idp states a {@code project} claim on
   * every commissioned agent credential (qits-projects' {@code IdpAgentCredentials} and
   * qits-workspaces' {@code IdpCredentialCommissioner} both send it), so this is the claim such a
   * token really carries rather than one invented for the check; an agent arriving without one
   * holds no platform-wide role either and is refused, which is the fail-closed direction.
   *
   * <p><b>{@code qits:system} is deliberately absent</b>, and that half of the old rule stands: no
   * peer service presses this button. qits-projects re-asks a release request's CI through {@link
   * #rerunReleaseRequestPhase}, which is addressed by the triple a peer actually holds; a run id is
   * qits-ci's own. Adding the machine role here would grant every service on the platform a door
   * none of them has a caller for.
   *
   * <p>The order of the answers is part of the contract: the run is read first, so an id that names
   * no run is a 404 rather than a 403 that would tell an uncovered caller whether it exists.
   */
  @POST
  @Path("/{runId}/retry")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Consumes(MediaType.WILDCARD)
  @Operation(summary = "Run a finished CI run's pipeline again, at the same commit")
  @APIResponse(
      responseCode = "202",
      description = "A new run has been accepted and queued",
      content = @Content(schema = @Schema(implementation = RetryRunResponse.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "The token covers this run's repository for nobody — no project claim and no platform-wide"
              + " role, or a project claim this instance cannot place the repository in")
  @APIResponse(responseCode = "404", description = "No such run")
  @APIResponse(responseCode = "409", description = "The run has not finished, so there is nothing to retry yet")
  public Response retryRun(@PathParam("runId") String runId) {
    CiRun run = runService.requireRun(runId);
    String projectScope = cancellationScope();
    if (projectScope != null) {
      requireRepositoryInProject(run.repoId, projectScope);
    }
    return Response.accepted().entity(new RetryRunResponse(runService.retry(runId).id)).build();
  }

  /**
   * Run one PHASE of one release request again — the newest run that phase has for this repository
   * and request, re-fired through exactly the machinery {@link #retryRun} uses.
   *
   * <p><b>Addressed by {@code (repoId, releaseRequestId, phase)} because that triple is the only
   * identity the caller holds.</b> The release request in qits-projects IS the pipeline; a run id is
   * qits-ci's, and the run's commit is no handle either — a QA run builds a fold nobody pushed that
   * the next re-fold replaces. It is {@link #cancelReleaseRequestRuns}' pair with the phase added,
   * and both halves of that pair are required here for the identical reason: one request folds many
   * repositories and one repository carries many open requests, so either alone reaches a sibling's
   * work.
   *
   * <p><b>202 and a run id, exactly like {@link #retryRun}</b>: the answer is a run that has been
   * accepted, is {@code QUEUED} and has not started, so the caller polls {@code GET
   * /ci/api/runs/{runId}} with it.
   *
   * <p><b>The 409 for a phase that SUCCEEDED replaces a live failure mode rather than adding a
   * rule.</b> The same press used to reach {@code /ci/api/runs/{runId}/retry}, be accepted, and die
   * in a step container cloning {@code release/<id>} — the branch tag creation deletes — so the
   * operator's answer was a red run about a missing ref. A phase whose verdict was spent has nothing
   * to re-ask, and the message says so in words.
   *
   * <p><b>The guard is {@link #cancelReleaseRequestRuns}', arm for arm.</b> The roles are the
   * write's — {@code qits:admin}, as on {@code /ci/api/runs/{runId}/retry}, because starting
   * somebody's build is a person's act — plus {@code qits:system}, because qits-projects calls this
   * as a machine exactly as it calls the cancellation. The machine arm then runs the same {@link
   * #cancellationScope()}/{@link #requireRepositoryInProject} check, so "what does my token cover"
   * has one answer across every write on this resource.
   */
  @POST
  @Path("/rerun")
  @Consumes(MediaType.APPLICATION_JSON)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  @Operation(
      summary = "Run one phase of a release request's CI again",
      description =
          "A release is one pipeline of three phases and a PHASE is a unit of work with a state and"
              + " a rerun. Two of them are runs here — RELEASE_REQUEST is phase one, the QA run at"
              + " release/<id>@mergedSha, and RELEASE is phase two, the publish run at"
              + " <version>@commitSha. Phase three is the deploy and is qits-deployments' own"
              + " release request, so it is not a word this door takes. A step inside a run is not a"
              + " phase, so it cannot be re-fired separately. Which phase a run is was decided by"
              + " the event that triggered it and never by which config file produced it, so this"
              + " addresses a run the same way whatever the repository's release.yml composed for"
              + " it — and the same way for a run from a bespoke ci-event-*.yml, which is the escape"
              + " hatch the grammar still has. This decides nothing about the release: the pipeline"
              + " is the release"
              + " request in qits-projects, the new run announces its verdict on the bus exactly as"
              + " the first one did, and the gate between the two phases goes on holding the release"
              + " where it is until that verdict arrives — a gate delays, it does not fail.")
  @APIResponse(
      responseCode = "202",
      description = "A new run has been accepted and queued",
      content = @Content(schema = @Schema(implementation = RetryRunResponse.class)))
  @APIResponse(responseCode = "400", description = "The repository id, the release request id or the phase is missing or invalid")
  @APIResponse(
      responseCode = "403",
      description =
          "The token covers this repository for nobody — no project claim and no platform-wide role,"
              + " or a project claim this instance cannot place the repository in")
  @APIResponse(responseCode = "404", description = "No such repository")
  @APIResponse(
      responseCode = "409",
      description =
          "That phase has no run yet, its newest run is still going, or it succeeded — a succeeded"
              + " phase has nothing to ask again")
  public Response rerunReleaseRequestPhase(RerunReleaseRequestPhaseRequest request) {
    if (request == null) {
      throw new BadRequestException("A repository id, a release request id and a phase are required");
    }
    CiIdentifiers.requireRepoId(request.repoId());
    String releaseRequestId = requireReleaseRequestId(request.releaseRequestId());
    CiRunPhase phase = requirePhase(request.phase());
    String projectScope = cancellationScope();
    if (projectScope != null) {
      requireRepositoryInProject(request.repoId(), projectScope);
    }
    return Response.accepted()
        .entity(
            new RetryRunResponse(
                runService.retryReleaseRequestPhase(request.repoId(), releaseRequestId, phase).id))
        .build();
  }

  /**
   * The phase as the enum's own name, refused as a bad request when it is anything else.
   *
   * <p>Spelled out rather than bound to the enum by JAX-RS for {@code ?limit=}'s reason one door
   * over: a conversion failure on a body property is a framework-shaped answer, and every rejected
   * input on this surface arrives as a 400 through {@link CiExceptionMapper}'s {@code {"message":
   * …}} envelope. The message names both accepted words, because a caller that spelled one wrong
   * cannot be expected to know the vocabulary is closed.
   */
  private static CiRunPhase requirePhase(String phase) {
    if (phase == null || phase.isBlank()) {
      throw new BadRequestException(
          "A phase is required: one of RELEASE_REQUEST (QA) or RELEASE (publish)");
    }
    try {
      return CiRunPhase.valueOf(phase.trim());
    } catch (IllegalArgumentException unknown) {
      throw new BadRequestException(
          "Unknown phase '" + phase + "': expected RELEASE_REQUEST (QA) or RELEASE (publish)");
    }
  }

  private String cancellationReason(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      JsonNode request = objectMapper.readTree(payload);
      JsonNode reason = request == null ? null : request.get("reason");
      if (reason == null || reason.isNull()) {
        return null;
      }
      if (!reason.isTextual()) {
        throw new BadRequestException("Cancellation reason must be a string");
      }
      return reason.textValue();
    } catch (BadRequestException e) {
      throw e;
    } catch (Exception e) {
      throw new BadRequestException("Invalid cancellation request");
    }
  }
}
