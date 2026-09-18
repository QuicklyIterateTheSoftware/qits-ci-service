package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiEventTriggerService;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.UnavailableException;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
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
 * <p><b>One read here is scoped to a repository after all</b>, and it is the exception that says
 * what the resource is: {@link #releasePhase} asks whether a given rev of a given repository
 * composes a release pipeline. It sits here rather than beside the runs because it is not about a
 * run — it is about the repository at a rev, which is the only subject this resource has.
 *
 * <p>It had a companion, {@code POST /ci/api/repositories/{repoId}/release-composition}, which
 * reported what a candidate {@code release.yml} would compose beside the two hand-written trigger
 * files a rev committed. Both halves of that question are gone: every repository in the estate is on
 * {@code release.yml}, so there is no committed pair to hold a candidate against and nobody is about
 * to write a migration commit. It retired with the split pipeline on 2026-09-18.
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
}
