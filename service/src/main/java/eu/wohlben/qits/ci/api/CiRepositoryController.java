package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiEventTriggerService;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.UnavailableException;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The repository ids qits-ci has recorded runs for — the one read on this surface that is not scoped
 * to a repository, because it is the question "which repositories are there to scope to".
 *
 * <p><b>The name is the contract.</b> The response is {@code {"repositoryIds": […]}} and not {@code
 * {"repositories": […]}}: ci does not own repositories, {@code ci_run.repo_id} is a plain string in
 * ci's own database with no relation to anything, and there is no object here to return. These are
 * ids this instance <em>observed</em>, sorted ascending so the answer is stable.
 *
 * <p><b>Why it exists at all.</b> The run listing takes a mandatory {@code ?repositoryId=} filter,
 * which is right — an unscoped listing of every run on the instance is not a page anyone wants — but
 * it also makes CI activity that nothing else knows about <em>invisible</em>. The explorer at {@code
 * /ci/} walks qits-projects' projects down to their repositories, and every repository a project
 * does not claim would simply not be drawn. On the platform as it stands that is the whole run
 * history: qits-local-up.sh seeds the platform's own bare repositories directly onto the git host
 * with no qits-projects {@code Repository} row, so their runs belong to no project. This endpoint is
 * what lets a client compute that set and show it, rather than quietly omitting it.
 *
 * <p>Deliberately narrower than {@code CiCandidateRepos}, which the trigger engine asks: that one
 * also counts every repository the git host lists, because a repository ci has never built is still
 * a candidate to be triggered. A repository with no run has no history to explore, so it does not
 * belong in an answer a UI draws nodes from.
 *
 * <p>A separate resource rather than a second method on {@code CiRunController}, because {@code
 * @Path("/runs")} is about runs. It is an ordinary JAX-RS resource under {@code quarkus.rest.path},
 * so it adds <b>no literal route</b> and {@code quarkus.quinoa.ignored-path-prefixes} is unchanged —
 * {@code /api} already covers it. And it is a read, so it calls no machine guard — exactly like the
 * run reads.
 *
 * <p><b>Two reads here are scoped to a repository after all</b>, and they are the exception that
 * says what the resource is: {@link #releasePhase} asks whether a given rev of a given repository
 * composes a release pipeline, and {@link #releaseComposition} asks what a candidate {@code
 * release.yml} would compose at a rev beside what that rev commits. Both sit here rather than beside
 * the runs because neither is about a run — each is about the repository at a rev, which is the only
 * subject this resource has.
 *
 * <p><b>The second of those is a POST and this class still has no write.</b> It is a POST because a
 * candidate slot file is a whole YAML document that is not committed anywhere yet, and a document is
 * not a thing a query string carries; nothing is recorded, enqueued or mutated by it. So the
 * sentence below still holds, and no machine guard is called anywhere in this file.
 *
 * <p><b>Read by both kinds of caller</b>, so it takes {@code qits:admin} and {@code qits:system}
 * together — the same pair {@code CiRunController}'s reads take, and for the reason stated there.
 * {@code qits:agent} reads it too: agents keep every read and write nothing, and this class has no
 * write.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
public class CiRepositoryController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

  @Inject CiEventTriggerService triggers;

  public record ListRepositoryIdsResponse(List<String> repositoryIds) {}

  @GET
  @Operation(summary = "The repository ids qits-ci has recorded runs for")
  @APIResponse(responseCode = "200", description = "Distinct repository ids, ascending")
  public ListRepositoryIdsResponse listRepositoryIds() {
    return new ListRepositoryIdsResponse(runService.repositoryIds());
  }

  /**
   * One repository, its newest run on any branch, and its newest run on {@code main}. {@code
   * lastMainRun} is null when the repository has never run on {@code main}, and is frequently the
   * same run as {@code lastRun}.
   *
   * <p><b>The union is still by storage id, and the name rides along when it is known.</b> {@code
   * repositoryId} is what the runs are grouped by — it is stable across a rename and it is what
   * every existing client binds — while {@code projectId} and {@code repoName} come off the
   * repository's newest run, which is the only place qits-ci learns them. Both are null for a
   * repository whose pushes were id-addressed, so a client labels by name when there is one.
   *
   * <p>Both slots carry the <b>full</b> {@link CiRunDto} rather than a trimmed shape, for the reason
   * the run listing does: it is small, it is the type every client already binds, and a second
   * "run summary" type would drift from it. What it does not carry is {@code steps} and {@code live},
   * because the mapper's list shape omits them — exactly as {@code GET /ci/api/runs} does.
   */
  public record RepositorySummaryDto(
      String repositoryId,
      String projectId,
      String repoName,
      CiRunDto lastRun,
      CiRunDto lastMainRun) {}

  public record ListRepositorySummariesResponse(List<RepositorySummaryDto> repositories) {}

  /**
   * The same repositories {@link #listRepositoryIds} answers with, each carrying the two runs a
   * client would otherwise fetch a listing per repository to find.
   *
   * <p><b>It exists because the alternative is a request per repository on every page load.</b> A
   * client drawing "which repositories are there, and how is each doing" had to call {@code GET
   * /ci/api/repositories} and then {@code GET /ci/api/runs?repositoryId=…&limit=1} per id — n+1
   * requests over a gateway for a page that is one question. The n+1 is still there, but it is two
   * indexed top-1 reads inside one process rather than n round trips over HTTP.
   *
   * <p><b>The name is {@code repositories} where the older endpoint says {@code repositoryIds}, and
   * both are right.</b> That one returns bare strings and must not suggest ci owns an object; this
   * one returns objects, and they are objects about <em>runs</em> — a repository id with what ci has
   * recorded against it. ci still owns no repository, and this response says nothing about one that
   * is not a run.
   *
   * <p>Sorted by {@code repositoryId} ascending, the same ordering and for the same reason: a client
   * diffing this against another service's list must not see the order change because the query
   * planner did.
   */
  @GET
  @Path("/summary")
  @Operation(summary = "Each repository with its newest run and its newest run on main")
  @APIResponse(responseCode = "200", description = "One entry per repository, ascending by id")
  public ListRepositorySummariesResponse listRepositorySummaries() {
    return new ListRepositorySummariesResponse(
        runService.repositorySummaries().stream()
            .map(
                summary ->
                    new RepositorySummaryDto(
                        summary.repositoryId(),
                        summary.projectId(),
                        summary.repoName(),
                        mapper.toDto(summary.lastRun()),
                        summary.lastMainRun() == null ? null : mapper.toDto(summary.lastMainRun())))
            .toList());
  }

  /**
   * Whether a rev's composed release cycle has a release phase.
   *
   * <p>{@code declared} is the whole answer; {@code detail} is the sentence behind it, and it is part
   * of the contract rather than a log line — a caller showing a person why a release request is, or
   * is not, waiting for a publish has nothing else to show. {@code repositoryId} and {@code rev} come
   * back verbatim as the caller sent them, so an answer correlates without the caller keeping state.
   */
  public record ReleasePhaseResponse(
      String repositoryId, String rev, boolean declared, String detail) {}

  /**
   * Whether qits-ci would run a <b>release pipeline</b> for this repository at this rev — the one
   * question the composer can answer and its caller cannot.
   *
   * <h2>What it is for</h2>
   *
   * <p>qits-projects gates a released tag on a PUBLISH phase, and it decided whether to raise that
   * gate by reading the tag's {@code .config/qits/release.yml} and asking only whether it named an
   * {@code archetype:}. That is answerable from the file and it is the wrong question: {@code
   * spa-frontend} and {@code cli} deliberately declare no {@code release:} slot, so a migrated SPA
   * got a PUBLISH gate whose run nobody would ever record and its release request sat RELEASED
   * forever. What decides is the <b>composition</b> — {@code CiReleaseComposer}'s whole-slot override
   * lets a repository declare its own {@code release:} on top of a publish-free archetype — and
   * qits-ci is the composer. So the composer answers, and this is the read.
   *
   * <h2>Three answers, because a boolean has to give a failure a side</h2>
   *
   * <p>A 200 means the question was asked and answered. <b>503 means it was not asked at all</b> —
   * the repository is in no catalogue here, the slot file's read was {@code UNREACHABLE}, or the
   * archetype could not be read from the wrapper repository — and the caller retries. It is never a
   * {@code false}: a {@code false} derived from a failure is the very bug this endpoint fixes, in
   * the direction that publishes a release nothing gated.
   *
   * <p><b>A slot file that will not parse, or a pair that will not compile, answers {@code declared:
   * true}.</b> Both are facts about bytes the repository committed, and the two outcomes are not
   * symmetrical: waiting on a pipeline somebody has to fix is recoverable — the fix is a commit and
   * the gate answers afterwards — while waving a release through whose pipeline was never composed
   * is not, because nothing downstream asks again. {@code detail} names which case it was, so a
   * stuck gate reads as "your release.yml is broken" rather than as silence.
   *
   * <h2>What the answer is about in time</h2>
   *
   * <p>The repository's half is read at {@code rev} — immutable bytes at a tag — and <b>the archetype
   * is read at the wrapper's {@code main} at ask time</b>, which is where every composition on this
   * service reads it. So the answer describes the pipeline <em>as it composes now</em>, not as it
   * composed when the tag was cut: a wrapper commit that gives an archetype a {@code release:} slot
   * changes what this read says about a tag whose own bytes never moved. That is the direction that
   * is wanted, because the run that would satisfy the gate would be composed now too.
   *
   * <p>The role set is the class's, and {@code qits:system} is load-bearing rather than inherited:
   * the caller is qits-projects, presenting either a machine bearer or the edge's forwarded {@code
   * X-Qits-User: qits-projects} / {@code X-Qits-Roles: qits:system} pair. It is a read, so it calls
   * no machine guard — exactly like every other read here.
   *
   * @param repoId the repository, by public name or by storage id
   * @param rev mandatory; a git rev the host can resolve, in practice {@code refs/tags/<version>}.
   *     Blank is a 400 rather than a default, because the one thing this read must never do is
   *     answer about a ref the caller did not name.
   */
  @GET
  @Path("/{repoId}/release-phase")
  @Operation(summary = "Whether a rev's composed release cycle declares a release phase")
  @APIResponse(
      responseCode = "200",
      description = "Answered — declared true or false, with the reason",
      content = @Content(schema = @Schema(implementation = ReleasePhaseResponse.class)))
  @APIResponse(responseCode = "400", description = "Missing or blank rev")
  @APIResponse(
      responseCode = "503",
      description =
          "Not answered — the repository is in no catalogue here, or the slot file or its archetype"
              + " could not be read. Retry.")
  public ReleasePhaseResponse releasePhase(
      @PathParam("repoId") String repoId,
      @Parameter(
              required = true,
              description = "A git rev the host can resolve, in practice refs/tags/<version>")
          @QueryParam("rev")
          String rev) {
    if (rev == null || rev.isBlank()) {
      throw new BadRequestException("A rev is required");
    }
    CiEventTriggerService.ReleasePhase phase = triggers.releasePhaseAt(repoId, rev.trim());
    if (phase.verdict() == CiEventTriggerService.Verdict.UNKNOWN) {
      throw new UnavailableException(phase.detail());
    }
    return new ReleasePhaseResponse(
        repoId,
        rev,
        phase.verdict() == CiEventTriggerService.Verdict.DECLARED,
        phase.detail());
  }

  /**
   * The one thing this door takes: the candidate {@code release.yml} to compose.
   *
   * <p>One field, and no second one is coming. A rev is a query parameter because it addresses the
   * thing being read; this is a <em>body</em> because it is a whole YAML document that has not been
   * committed anywhere yet, and a file's text is not a thing a query string carries.
   *
   * @param candidateSlotFile the slot-file text to compose. Null or blank means "use the
   *     repository's own {@code .config/qits/release.yml} at that ref" — the honest spelling of "I
   *     have no draft, show me what is already there" — and it is ignored outright when the ref
   *     commits one, because the ref's own bytes are the better answer to the question asked.
   */
  public record ReleaseCompositionRequest(String candidateSlotFile) {}

  /** The two payload dot-paths a run of a document is anchored at, and the compatibility arm. */
  public record CheckoutResponse(String branchPath, String shaPath, boolean optional) {}

  /**
   * One step as this read reports it.
   *
   * <p><b>{@code scriptSha256} and {@code scriptLines} are the whole of what is said about a
   * script's content</b>, and that bound is the decision rather than a first cut. Two digests that
   * differ prove the two texts differ, which is what a reader needs in order to know that a step was
   * not carried over verbatim; how they differ is what opening the two documents is for. A diff
   * algorithm here would be a second thing to maintain, on a door whose whole answer is "read these
   * and decide".
   *
   * @param user the container user, {@code ""} when the document declares none — the image's default
   * @param timeoutSeconds null when the document declares none, which is the deployment-wide default
   */
  public record StepResponse(
      int index,
      String image,
      boolean gating,
      boolean build,
      boolean docker,
      String user,
      Integer timeoutSeconds,
      String scriptSha256,
      int scriptLines) {}

  /**
   * One declared artifact — which is what answers "what does this pipeline publish", because nothing
   * else can.
   *
   * <p>qits-ci never learns how to publish anything and cannot see what a step pushed (README, "What
   * it declares is not what it observed"), so the {@code artifacts:} block is the statically
   * readable claim and the script is not consulted. Reading a script for an {@code npm publish}
   * would be a grep inside a shell script — the mechanism {@code userflows:} was invented to retire —
   * and it stops working the moment the script is composed.
   *
   * @param sbomPath the {@code sbom:} path the slot file carried for this artifact, {@code ""} when
   *     none — and always {@code ""} on the committed side, since a trigger file's {@code
   *     artifacts:} grammar has no such key and never did
   */
  public record ArtifactResponse(String type, String name, String sbomPath) {}

  /**
   * Everything about one trigger document that decides what a run of it does.
   *
   * @param selection the {@code when:} written out as one line, because an absent {@code when:}
   *     means <b>unconditional</b> and a candidate that lost its selection would fire for every
   *     release of every repository on the platform
   * @param checkout null when the document declares none, which means the run builds {@code main}'s
   *     head
   * @param gating the FILE-level flag: whether a red run stands in the way of releasing its commit
   */
  public record SummaryResponse(
      String event,
      String selection,
      CheckoutResponse checkout,
      boolean gating,
      List<StepResponse> steps,
      List<ArtifactResponse> artifacts) {}

  /**
   * One side of one phase.
   *
   * @param document the document's text, or null when this side declares nothing — {@code detail}
   *     then says why, which is an explicit absence marker rather than an empty string a reader
   *     could take for an empty file
   * @param summary null when there is nothing to summarise: the side is absent, or its document is
   *     present and will not parse — in which case {@code detail} carries the parser's own message,
   *     which is the most useful sentence this read has
   */
  public record DocumentResponse(
      String path, String document, String detail, SummaryResponse summary) {}

  /**
   * One phase of the release cycle, from both sides — what the slot file composes, and what the ref
   * commits.
   *
   * @param event the domain event a run of this phase is triggered by, which is what makes the two
   *     sides comparable at all: they are two declarations about one event
   */
  public record PhaseResponse(
      String phase, String event, DocumentResponse composed, DocumentResponse committed) {}

  /**
   * Both phases, and the prose that says what this answer is worth.
   *
   * @param slotFileSource {@code COMMITTED} when the ref's own slot file was composed, {@code
   *     CANDIDATE} when the supplied one was. Reported rather than left to be inferred: a caller
   *     that sent a draft and got the committed file back has to be told so.
   * @param guidance the standing sentence about what to do with this, carried in every answer rather
   *     than left to a document nobody reads beside the response — see {@link #GUIDANCE}
   */
  public record ReleaseCompositionResponse(
      String repositoryId,
      String rev,
      String slotFileSource,
      String slotFilePath,
      String detail,
      String guidance,
      PhaseResponse releaseRequestPhase,
      PhaseResponse releasePhase) {}

  /**
   * What the answer is for, said in the answer itself.
   *
   * <p>It travels on every response rather than living in a README because the failure it prevents
   * is a caller treating this door as a gate: there is no boolean here, and the one a reader would
   * most like — "do these two match" — is unanswerable in principle. A composed document carries a
   * platform prelude and a postlude no hand-written file ever had, so the two texts are never
   * byte-equal; a comparison over them could only ever say "different", and a signal that is always
   * the same is worse than no signal because somebody comes to trust it.
   */
  static final String GUIDANCE =
      "This is for judgement, not a pass/fail gate. A composed document carries a platform prelude"
          + " and postlude the hand-written pair never had, so the two sides are NEVER byte-equal and"
          + " no comparison of their texts would mean anything; qits-ci therefore reports both sides"
          + " and compares neither. Read the summaries — event, selection, checkout, file-level and"
          + " per-step gating, each step's image and flags, the script digests, and the declared"
          + " artifacts — and decide whether the candidate says what the committed pair says. A"
          + " step's script is reported as a digest and a line count only: what a pipeline publishes"
          + " is answered by its declared artifacts, never by reading its script. And note that a"
          + " recipe is decided at main: a repository's slot file cannot gate the fold that"
          + " introduces it, so the first run that composes from it is the one after the file is on"
          + " main.";

  /**
   * What a candidate {@code .config/qits/release.yml} <b>would</b> compose at a ref, beside the two
   * hand-written trigger files that ref really commits.
   *
   * <h2>What it is for, and why it exists before the migration rather than after</h2>
   *
   * <p>46 repositories are about to delete their {@code ci-event-release-request.yml}/{@code
   * ci-event-release.yml} pair and commit a {@code release.yml} in its place. The pair is what gates
   * and publishes them today; the slot file is a promise about what would happen instead, and the
   * promise is only redeemable by <em>composing</em> it — {@link
   * eu.wohlben.qits.ci.control.CiReleaseComposer}'s whole-slot override means the file alone does
   * not say what the pipeline is. So a person sends the draft here, before committing it, and reads
   * the two sides side by side.
   *
   * <h2>It is a POST only because it takes a body. It is a READ.</h2>
   *
   * <p>Nothing is written: no run is recorded, no row is touched, nothing is enqueued and no
   * container is asked for. The candidate slot file is a whole YAML document that is not committed
   * anywhere yet, which is not a thing a query string carries — so the method is POST and the
   * subject is still a repository at a rev, exactly as {@link #releasePhase}'s is.
   *
   * <p><b>Because it writes nothing it calls no machine guard, and that omission is deliberate
   * rather than an oversight.</b> {@code MachineAuth} is called by the handlers that mutate — the
   * house rule is a call in the handler rather than a filter over a path — and a read that mutates
   * nothing has nothing to authorise beyond the roles that already shut in front of it. There is no
   * {@code MachineGuardTest} case for this route for the same reason: that test is the guard over
   * the <em>writes</em>, and adding a read to it would say a guard is here that is not.
   *
   * <p><b>No method-level role list, so it inherits the class's</b> — {@code qits:admin}, {@code
   * qits:system} and {@code qits:agent}. A method-level list replaces the class's rather than adding
   * to it, so naming one here would be the way to lose a role by accident. {@code qits:agent} is the
   * one worth saying out loud: the house rule is that <b>a new read route names {@code qits:agent}
   * too</b> — agents keep every read and write nothing — and {@code AgentReadAccessTest} is what
   * holds it, this route included.
   *
   * <h2>A recipe is decided at {@code main}</h2>
   *
   * <p>A repository's slot file <b>cannot gate the fold that introduces it</b>. Discovery, parsing
   * and selection all read the tracked branch's head, so the first run composed from a new {@code
   * release.yml} is the one after that file is on {@code main} — the release request that lands it
   * is still gated by whatever {@code main} said beforehand. That is precisely why this door is
   * useful before the commit and largely uninteresting after it: the migration commit's own CI
   * proves nothing about the migration.
   *
   * <h2>Three statuses, and which failures are answers</h2>
   *
   * <p><b>200</b> is "both sides were read". Every failure of <em>bytes</em> is inside it: a
   * candidate that will not parse, and a pair that will not compose, come back as an absent composed
   * side carrying the parser's own message — which is the single most useful thing this read hands
   * back to somebody about to commit that file.
   *
   * <p><b>503</b> is "the question was not asked at all", {@link #releasePhase}'s rule exactly: the
   * repository is in no catalogue here, the slot file's read was {@code UNREACHABLE}, either legacy
   * file's read was, or the archetype could not be read from the wrapper repository. None of those
   * says anything about the repository's bytes, and reporting one as an absence would have this door
   * say "this repository has already stopped committing that file" on the strength of a blip.
   *
   * <p><b>400</b> is the empty ask, and there are two of them. A blank {@code rev} is one, because
   * the one thing a read must never do is answer about a ref the caller did not name. The other is
   * <em>no {@code release.yml} at the ref and no candidate supplied</em>: the subject of this read is
   * a candidate measured against a committed pair, so with neither there is nothing composed to
   * report, and a 200 carrying two absences would read as "this repository composes nothing" — a
   * statement about the repository rather than about an empty request — when the missing input is
   * the caller's own to send.
   *
   * @param repoId the repository, by public name or by storage id
   * @param rev mandatory; the git rev to read the committed pair and the committed slot file at. In
   *     practice {@code main} or a branch tip while a migration is being written, rather than the
   *     released tag {@link #releasePhase} is asked about — this read is about files somebody is
   *     still editing. Blank is a 400 rather than a default.
   * @param request the candidate slot file, or null/blank for "use the repository's own". A body-less
   *     POST is accepted and means exactly that.
   */
  @POST
  @Path("/{repoId}/release-composition")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      summary =
          "What a candidate release.yml would compose at a rev, beside what that rev commits (a"
              + " read; POST only because it takes a body)",
      description =
          "Reported per PHASE, because the slot file's two keys are the release pipeline's first two"
              + " phases: release-request: is phase one, the QA a release request is gated on, and"
              + " release: is phase two, the publish at the released tag. Phase three is the deploy"
              + " and belongs to qits-deployments, so no slot file declares it and this read has"
              + " nothing to say about it. A phase is a unit of work with a state and a rerun; the"
              + " composed text names none of them, since which phase a run is, is decided by the"
              + " event that triggered it and never by the slot it was composed from. And this is"
              + " not a GATE — a gate is the condition between two phases and it delays rather than"
              + " fails, whereas this door refuses nothing and returns no verdict: it reports both"
              + " sides and compares neither, for a person's judgement before the file is"
              + " committed.")
  @APIResponse(
      responseCode = "200",
      description =
          "Both sides, per phase, with the guidance that says this is for judgement rather than a"
              + " gate. A candidate that will not parse or compose is reported here, not as a 503.",
      content = @Content(schema = @Schema(implementation = ReleaseCompositionResponse.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Missing or blank rev, or no release.yml at the ref and no candidate supplied — an empty"
              + " request rather than an empty answer")
  @APIResponse(
      responseCode = "503",
      description =
          "Not answered — the repository is in no catalogue here, or the slot file, a legacy trigger"
              + " file or the archetype could not be read. Retry.")
  public ReleaseCompositionResponse releaseComposition(
      @PathParam("repoId") String repoId,
      @Parameter(
              required = true,
              description =
                  "A git rev the host can resolve — in practice main or the branch a migration is"
                      + " being written on")
          @QueryParam("rev")
          String rev,
      @RequestBody(
              required = false,
              description =
                  "The candidate release.yml to compose. Omit it — or send a blank one — to compose"
                      + " the repository's own file at that ref, which is what a caller with no"
                      + " draft asks for.")
          ReleaseCompositionRequest request) {
    if (rev == null || rev.isBlank()) {
      throw new BadRequestException("A rev is required");
    }
    CiEventTriggerService.ReleaseComposition composition =
        triggers.releaseCompositionAt(
            repoId, rev.trim(), request == null ? null : request.candidateSlotFile());
    switch (composition.verdict()) {
      case UNAVAILABLE -> throw new UnavailableException(composition.detail());
      case NOTHING_TO_COMPARE -> throw new BadRequestException(composition.detail());
      default -> {
        // Answered. Fall through to the mapping below.
      }
    }
    return new ReleaseCompositionResponse(
        repoId,
        rev,
        composition.slotFileSource().name(),
        composition.slotFilePath(),
        composition.detail(),
        GUIDANCE,
        phase(composition.releaseRequestPhase()),
        phase(composition.releasePhase()));
  }

  private static PhaseResponse phase(CiEventTriggerService.PhaseComparison phase) {
    return new PhaseResponse(
        phase.phase(), phase.event(), document(phase.composed()), document(phase.committed()));
  }

  private static DocumentResponse document(CiEventTriggerService.PhaseDocument document) {
    return new DocumentResponse(
        document.path(), document.document(), document.detail(), summary(document.summary()));
  }

  private static SummaryResponse summary(CiEventTriggerService.DocumentSummary summary) {
    if (summary == null) {
      return null;
    }
    return new SummaryResponse(
        summary.event(),
        summary.selection(),
        summary.checkout() == null
            ? null
            : new CheckoutResponse(
                summary.checkout().branchPath(),
                summary.checkout().shaPath(),
                summary.checkout().optional()),
        summary.gating(),
        summary.steps().stream()
            .map(
                step ->
                    new StepResponse(
                        step.index(),
                        step.image(),
                        step.gating(),
                        step.build(),
                        step.docker(),
                        step.user(),
                        step.timeoutSeconds(),
                        step.scriptSha256(),
                        step.scriptLines()))
            .toList(),
        summary.artifacts().stream()
            .map(
                artifact ->
                    new ArtifactResponse(artifact.type(), artifact.name(), artifact.sbomPath()))
            .toList());
  }
}
