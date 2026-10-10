package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiReportBaselines;
import eu.wohlben.qits.ci.control.CiReportStore;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.dto.CiBaselineAnswer;
import eu.wohlben.qits.ci.dto.CiReportBaselineDto;
import eu.wohlben.qits.ci.dto.CiReportDto;
import eu.wohlben.qits.ci.dto.CiReportSubmission;
import eu.wohlben.qits.ci.dto.CiRunReportsDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.CiException;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.ForbiddenException;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.idp.CallerSubject;
import eu.wohlben.qits.ci.idp.RunCommissions;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * Release reports (epic qits-754, Design §5): the run-bound door a QA step submits its reports
 * through, the four generic reads the release-request page and the run view draw them from, and the
 * gate's reports a publish run's changelog reads (qits-893).
 *
 * <p><b>Generic over kinds.</b> A report is a kind name, a kind version, opaque highlights and an
 * opaque payload; nothing here knows what {@code test-results} or {@code coverage} mean, so a new
 * kind is no change to this class.
 *
 * <p><b>The submit door is bound to its run.</b> Its caller is a step's own {@code ci-run} token,
 * whose role is {@code qits:ci-run} and whose {@code sub} the edge forwards. Holding the role is not
 * enough — every run's token holds it — so the caller's {@code sub} must equal the subject of the
 * token this process commissioned for <em>this</em> run ({@link RunCommissions#tokenSubjectOf}),
 * the same binding the ci-daemon control socket admits by. The checks are ordered so a caller learns
 * no more than it is entitled to: a bad kind name is a 400 before anything is read, an unknown run
 * a 404, another run's token a 403 before the run's status is told, and only then 409, 413 and the
 * highlight count.
 *
 * <p><b>Same {@code @Path("/runs")} as {@code CiRunController}</b>, with every route under a {@code
 * /{runId}/…} template that one does not use; {@code CiPipelineBoundaryTest} asserts that {@code
 * /runs/active}, {@code /runs/finished}, {@code /runs/queue} and {@code /runs/{runId}} still resolve
 * to that class with this one present.
 */
@Path("/runs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent", "qits:ci-run"})
public class CiReportController {

  private static final Logger LOG = Logger.getLogger(CiReportController.class);

  /** A kind's wire name, as the CLI's {@code ReportKind.id()} promises it. */
  static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** The largest submit body accepted: 1 MiB. */
  static final int MAX_BODY_BYTES = 1024 * 1024;

  /** The most highlights one report may carry. */
  static final int MAX_HIGHLIGHTS = 10;

  @Inject CiRunService runService;

  @Inject CiReportStore store;

  @Inject CiReportBaselines baselines;

  @Inject RunCommissions commissions;

  @Inject SecurityIdentity identity;

  @Inject ObjectMapper objectMapper;

  /**
   * A step submits one report of one kind about itself; a re-submit replaces it.
   *
   * <p>The body is read as bytes rather than bound, so that the run's binding and status are judged
   * before a byte of it is parsed and an oversized body is refused by count rather than by an
   * out-of-memory parse.
   */
  @PUT
  @Path("/{runId}/steps/{stepIndex}/reports/{kind}")
  @RolesAllowed("qits:ci-run")
  @Consumes(MediaType.WILDCARD)
  @Operation(summary = "Submit a step's release report of one kind (a run's own ci-run token only)")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = CiReportSubmission.class)))
  @APIResponse(responseCode = "204", description = "Stored, replacing any earlier report of the kind")
  @APIResponse(responseCode = "400", description = "A bad kind name, body, or more than 10 highlights")
  @APIResponse(responseCode = "403", description = "The token is not this run's")
  @APIResponse(responseCode = "404", description = "No such run")
  @APIResponse(responseCode = "409", description = "The run is not RUNNING")
  @APIResponse(responseCode = "413", description = "The body is larger than 1 MiB")
  public Response submit(
      @PathParam("runId") String runId,
      @PathParam("stepIndex") int stepIndex,
      @PathParam("kind") String kind,
      InputStream body) {
    requireKind(kind);
    if (stepIndex < 0) {
      throw new BadRequestException("stepIndex must not be negative");
    }
    CiRun run = runService.requireRun(runId);
    String caller = CallerSubject.of(identity);
    String bound = commissions.tokenSubjectOf(runId);
    if (bound == null || !bound.equals(caller)) {
      LOG.warnf(
          "Refused a %s report for run %s step %d: the caller is subject '%s', and the run's ci-run"
              + " token is '%s'",
          kind, runId, stepIndex, caller, bound == null ? "(none held)" : bound);
      throw new ForbiddenException("This token is not run " + runId + "'s");
    }
    if (run.status != CiRunStatus.RUNNING) {
      throw new ConflictException(
          "Run " + runId + " is " + run.status + "; reports are accepted only while it runs");
    }
    byte[] bytes = readBounded(body);
    CiReportSubmission submission = parse(bytes);
    if (submission.highlights() != null && submission.highlights().size() > MAX_HIGHLIGHTS) {
      throw new BadRequestException(
          "A report carries at most "
              + MAX_HIGHLIGHTS
              + " highlights; this one has "
              + submission.highlights().size());
    }
    store.submit(runId, stepIndex, kind, submission);
    return Response.noContent().build();
  }

  @GET
  @Path("/{runId}/reports")
  @Operation(
      operationId = "listRunReports",
      summary = "A run's release reports, without payloads, and its baseline")
  @APIResponse(responseCode = "200", description = "The run's reports; an empty list when it has none")
  @APIResponse(responseCode = "404", description = "No such run")
  public CiRunReportsDto reports(@PathParam("runId") String runId) {
    CiRun run = runService.requireRun(runId);
    return new CiRunReportsDto(
        run.id,
        run.commitSha,
        run.releaseRequestId,
        baselineOf(run).orElse(null),
        store.forRun(runId).stream().map(store::summary).toList());
  }

  /**
   * The reports of the QA run that gated this run's release request (qits-893): what a publish run's
   * changelog reads, asked with the publish run's own id because that is the one id its step holds
   * ({@code $QITS_CI_RUN_ID}). The answer is shaped like {@link #reports} for the GATE run — its id,
   * its commit, its request — with no baseline, since a changelog compares nothing.
   *
   * <p>No gate is a 200, not a 404: a run naming no request, or one whose request no green QA run
   * gated, answers its own request id, a null run and commit, and {@code []}. Only an unknown run is
   * a 404.
   */
  @GET
  @Path("/{runId}/gate/reports")
  @Operation(
      summary =
          "The release reports of the green QA run that gated this run's release request, without"
              + " payloads")
  @APIResponse(
      responseCode = "200",
      description = "The gate run's reports; runId null and [] when no green QA run gated the request")
  @APIResponse(responseCode = "404", description = "No such run")
  public CiRunReportsDto gateReports(@PathParam("runId") String runId) {
    CiRun run = runService.requireRun(runId);
    return baselines
        .gateOf(run)
        .map(
            gate ->
                new CiRunReportsDto(
                    gate.id,
                    gate.commitSha,
                    gate.releaseRequestId,
                    null,
                    store.forRun(gate.id).stream().map(store::summary).toList()))
        .orElseGet(() -> new CiRunReportsDto(null, null, run.releaseRequestId, null, List.of()));
  }

  @GET
  @Path("/{runId}/reports/{reportId}")
  @Operation(
      operationId = "getRunReport",
      summary = "One release report of a run, with its payload")
  @APIResponse(responseCode = "200", description = "The report")
  @APIResponse(responseCode = "404", description = "No such run, or no such report on it")
  public CiReportDto report(
      @PathParam("runId") String runId, @PathParam("reportId") String reportId) {
    runService.requireRun(runId);
    return store
        .byId(runId, reportId)
        .map(store::full)
        .orElseThrow(() -> new NotFoundException("No report " + reportId + " on run " + runId));
  }

  @GET
  @Path("/{runId}/baseline")
  @Operation(
      summary =
          "A run's baseline: the gating run of its repository's newest released version, or null")
  @APIResponse(responseCode = "200", description = "The baseline, or {\"baseline\":null}")
  @APIResponse(responseCode = "404", description = "No such run")
  public CiBaselineAnswer baseline(@PathParam("runId") String runId) {
    return new CiBaselineAnswer(baselineOf(runService.requireRun(runId)).orElse(null));
  }

  @GET
  @Path("/{runId}/baseline/reports/{kind}")
  @Operation(summary = "The baseline run's release reports of one kind, with payloads")
  @APIResponse(responseCode = "200", description = "The reports; [] when there is no baseline or none")
  @APIResponse(responseCode = "400", description = "A bad kind name")
  @APIResponse(responseCode = "404", description = "No such run")
  public List<CiReportDto> baselineReports(
      @PathParam("runId") String runId, @PathParam("kind") String kind) {
    requireKind(kind);
    CiRun run = runService.requireRun(runId);
    return baselineOf(run)
        .map(baseline -> store.forRun(baseline.runId(), kind).stream().map(store::full).toList())
        .orElse(List.of());
  }

  private Optional<CiReportBaselineDto> baselineOf(CiRun run) {
    return baselines
        .forRun(run)
        .map(b -> new CiReportBaselineDto(b.version(), b.runId(), b.releaseRequestId(), b.tagSha()));
  }

  private static void requireKind(String kind) {
    if (kind == null || !KIND.matcher(kind).matches()) {
      throw new BadRequestException("A report kind is [a-z][a-z0-9-]{0,63}; got '" + kind + "'");
    }
  }

  private static byte[] readBounded(InputStream body) {
    if (body == null) {
      return new byte[0];
    }
    try (body) {
      byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw new CiException(413, "A report body is at most " + MAX_BODY_BYTES + " bytes");
      }
      return bytes;
    } catch (IOException e) {
      throw new BadRequestException("The report body could not be read: " + e.getMessage());
    }
  }

  private CiReportSubmission parse(byte[] bytes) {
    CiReportSubmission submission;
    try {
      submission = objectMapper.readValue(bytes, CiReportSubmission.class);
    } catch (IOException e) {
      throw new BadRequestException("The report body is not a report submission");
    }
    if (submission == null) {
      throw new BadRequestException("The report body is empty");
    }
    if (submission.kindVersion() == null) {
      throw new BadRequestException("kindVersion is required");
    }
    if (submission.payload() == null || submission.payload().isNull()) {
      throw new BadRequestException("payload is required");
    }
    return submission;
  }
}
