package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.dto.CiRunnerDto;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.RunnerCapabilities;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.CiException;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.error.UnavailableException;
import eu.wohlben.qits.ci.error.ForbiddenException;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.runnerhost.RegistrationTokenIdentityProvider;
import eu.wohlben.qits.ci.runnerhost.RunnerAddresses;
import eu.wohlben.qits.ci.runnerhost.RunnerInstallScript;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * The runners' surface: six verbs an operator uses to declare, read, tune, re-key and decommission a
 * runner, and the one door a runner itself knocks on to register.
 *
 * <p><b>Roles, per verb, as {@link CiRunController} spells them.</b> The two reads take {@code
 * qits:admin}, {@code qits:system} and {@code qits:agent}; every write is {@code qits:admin} alone,
 * the cancel button's role. {@code qits:ci-runner-registration} — what a registration token carries
 * — opens exactly two routes anywhere on the platform: the register door, only for the runner the
 * token was minted for (the bearer's {@code sub} must be that runner's registration token subject,
 * or it is 403), and {@code GET /runners/install.sh}, the generic install script, which carries no
 * secret and names no runner, so any live registration token reads it.
 *
 * <p><b>Every credential this class hands out is handed out exactly once and logged never.</b> The
 * registration token's value is in the install line that answers the create (and a rotation) and
 * nowhere else — not on the row, not on any read, not in a log line. The runner's client secret is in the answer
 * to the register door and nowhere else. A caller that lost either asks for a fresh one (a rotation;
 * a decommission and a new runner).
 *
 * <p><b>qits-idp is called from here and not from {@code ci/}</b>, which holds no HTTP; {@link
 * CiRunners} offers the check before each call and the write after it, and no transaction is held
 * across the network. Where a call to qits-idp lands and the write after it then loses a race, the
 * credential just minted is given back at once, so a refused request leaves nothing behind; where a
 * give-back itself cannot reach qits-idp, {@code CommissionReconciler} reaps it on its hourly pass.
 */
@Path("/runners")
@Produces(MediaType.APPLICATION_JSON)
public class CiRunnerController {

  private static final Logger LOG = Logger.getLogger(CiRunnerController.class);

  /** The role a registration token carries, and the only one the register door admits. */
  static final String REGISTRATION_ROLE = "qits:ci-runner-registration";

  @Inject CiRunners runners;

  @Inject IdpCommissioner idp;

  @Inject RunnerAddresses addresses;

  @Inject RunnerInstallScript installScript;

  @Inject MachineAuth machineAuth;

  @Inject SecurityIdentity identity;

  public record ListRunnersResponse(List<CiRunnerDto> runners) {}

  public record CreateRunnerRequest(
      @Schema(description = "[a-z][a-z0-9-]{0,63}, unique", required = true) String name,
      @Schema(description = "Free text, at most 1024 characters") String description,
      @Schema(description = "How many steps it may hold at once; 0 drains it. Default 1")
          Integer slots) {}

  public record PatchRunnerRequest(
      @Schema(description = "0 drains the runner; absent leaves it") Integer slots,
      @Schema(description = "Blank clears it; absent leaves it") String description) {}

  /**
   * A runner together with the secret-bearing answer only the request that minted it gets: the
   * ordinary read shape's fields, flat — the SPA reads it as {@code CiRunnerDto} plus one field —
   * and the install line, under the field name {@code installScript} the SPA reads, which carries
   * the registration token and is the only place its value is ever written. {@link #of} is the one way to build it, so the fields cannot drift from {@link
   * CiRunnerDto}'s; {@code CiRunnerControllerTest} checks the two lists agree.
   */
  public record CiRunnerCreated(
      UUID id,
      String name,
      String description,
      int slots,
      CiRunnerPlane plane,
      JsonNode capabilities,
      boolean registered,
      boolean connected,
      long heldRuns,
      Instant lastSeenAt,
      Instant createdAt,
      @Schema(
              description =
                  "The one line that installs this runner on a host: it fetches the generic"
                      + " install script with the registration token and pipes it into sudo sh with"
                      + " this runner's values. Returned once; never readable again")
          String installScript) {

    static CiRunnerCreated of(CiRunnerDto runner, String installScript) {
      return new CiRunnerCreated(
          runner.id(),
          runner.name(),
          runner.description(),
          runner.slots(),
          runner.plane(),
          runner.capabilities(),
          runner.registered(),
          runner.connected(),
          runner.heldRuns(),
          runner.lastSeenAt(),
          runner.createdAt(),
          installScript);
    }

    @Override
    public String toString() {
      return "CiRunnerCreated[id=" + id + ", name=" + name + "]";
    }
  }

  public record RegisterRunnerRequest(
      @Schema(description = "What the runner says about itself — a JSON object, at most 16 KiB")
          JsonNode capabilities) {}

  /**
   * What the register door answers, once: the runner's own client, where to mint a token with it and
   * for which audience, and where to dial.
   */
  public record RegisteredRunner(
      String clientId, String secret, String tokenUrl, String audience, String socketUrl) {
    @Override
    public String toString() {
      return "RegisteredRunner[clientId=" + clientId + ", socketUrl=" + socketUrl + "]";
    }
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("qits:admin")
  @Operation(summary = "Declare a runner; answers its install line, once")
  @APIResponse(
      responseCode = "201",
      description = "The runner and the install line carrying its registration token",
      content = @Content(schema = @Schema(implementation = CiRunnerCreated.class)))
  @APIResponse(responseCode = "400", description = "A malformed name, slots or description")
  @APIResponse(responseCode = "409", description = "The name is taken")
  @APIResponse(responseCode = "502", description = "qits-idp refused the registration token")
  @APIResponse(
      responseCode = "503",
      description = "This deployment commissions nothing, or cannot render an install script")
  public Response create(CreateRunnerRequest request) {
    if (request == null) {
      throw new BadRequestException("A runner needs a name");
    }
    runners.requireCreatable(request.name(), request.description(), request.slots());
    requireCommissioning();
    requireRenderable();
    UUID id = UUID.randomUUID();
    IdpCommissioner.CommissionedToken token = commissionRegistrationToken(id);
    CiRunner runner;
    try {
      runner =
          runners.create(
              id,
              request.name(),
              request.description(),
              request.slots(),
              token.tokenId(),
              token.subject());
    } catch (RuntimeException refused) {
      // The name went in between the check and the write, or the write failed outright: the token
      // was minted for a runner that does not exist, so it goes back now rather than at the next
      // reconciliation.
      idp.deleteToken(token.tokenId());
      throw refused;
    }
    LOG.infof(
        "Runner %s (%s) declared; its registration token is %s", runner.name, id, token.tokenId());
    return Response.status(Response.Status.CREATED)
        .entity(
            CiRunnerCreated.of(runners.view(runner), installScript.line(runner, token.token())))
        .build();
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "Every runner, with whether it is connected and how many runs it holds")
  @APIResponse(responseCode = "200", description = "Every runner, by name")
  public ListRunnersResponse list() {
    return new ListRunnersResponse(runners.views());
  }

  /**
   * The generic install script the install line pipes into {@code sh}: this deployment's artifacts
   * base and pinned runner version, and no secret and no runner — those four values reach it from
   * the line's {@code env}. Read with the registration token, which is what a host holds before it
   * holds anything else; behind the edge that token arrives as the edge's JWT, on qits-net as the
   * raw value {@code runnerhost/RegistrationTokenMechanism} introspects for this route and the
   * register door alone.
   */
  @GET
  @Path("/install.sh")
  @Produces(MediaType.TEXT_PLAIN)
  @RolesAllowed({REGISTRATION_ROLE, "qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "The generic runner install script, which the install line pipes into sh")
  @APIResponse(responseCode = "200", description = "A POSIX sh script, carrying no secret")
  @APIResponse(
      responseCode = "503",
      description = "This deployment cannot render an install script")
  public String installScript() {
    requireRenderable();
    return installScript.generic();
  }

  @GET
  @Path("/{id}")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @Operation(summary = "One runner")
  @APIResponse(responseCode = "200", description = "The runner")
  @APIResponse(responseCode = "404", description = "No such runner")
  public CiRunnerDto get(@PathParam("id") String id) {
    return runners.view(runners.get(runnerId(id)));
  }

  @PATCH
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("qits:admin")
  @Operation(summary = "Change a runner's slots or description")
  @APIResponse(responseCode = "200", description = "The runner as it now is")
  @APIResponse(responseCode = "400", description = "Negative slots or an overlong description")
  @APIResponse(responseCode = "404", description = "No such runner")
  public CiRunnerDto patch(@PathParam("id") String id, PatchRunnerRequest request) {
    PatchRunnerRequest change = request == null ? new PatchRunnerRequest(null, null) : request;
    return runners.view(runners.patch(runnerId(id), change.slots(), change.description()));
  }

  /**
   * A fresh registration token, for a runner whose first one was lost or leaked. The old one is
   * deleted at qits-idp once the new one is on the row, so between the two calls both open the door —
   * never neither. A registered runner is 409: it has spent its registration, and a token for it
   * would open a door that answers 409 anyway.
   */
  @POST
  @Path("/{id}/registration-token")
  @RolesAllowed("qits:admin")
  @Operation(summary = "Replace a runner's registration token; answers a new install line, once")
  @APIResponse(
      responseCode = "200",
      description = "The runner and the install line carrying its new registration token",
      content = @Content(schema = @Schema(implementation = CiRunnerCreated.class)))
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "The runner is already registered")
  @APIResponse(responseCode = "502", description = "qits-idp refused the registration token")
  @APIResponse(
      responseCode = "503",
      description = "This deployment commissions nothing, or cannot render an install script")
  public CiRunnerCreated rotateRegistrationToken(@PathParam("id") String id) {
    UUID runnerId = runnerId(id);
    runners.requireUnregistered(runnerId);
    requireCommissioning();
    requireRenderable();
    IdpCommissioner.CommissionedToken token = commissionRegistrationToken(runnerId);
    String previous;
    try {
      previous = runners.replaceRegistrationToken(runnerId, token.tokenId(), token.subject());
    } catch (RuntimeException refused) {
      idp.deleteToken(token.tokenId());
      throw refused;
    }
    if (previous != null && !idp.deleteToken(previous)) {
      LOG.warnf(
          "Runner %s's replaced registration token %s is still live at qits-idp; the commission"
              + " reconciliation reaps it",
          runnerId, previous);
    }
    CiRunner rotated = runners.get(runnerId);
    return CiRunnerCreated.of(runners.view(rotated), installScript.line(rotated, token.token()));
  }

  /**
   * Decommission a runner. The row goes first — 409 while it holds a {@code RUNNING} run — and its
   * client and registration token are given back at qits-idp after; see {@code CiRunners.delete}
   * for why that order. A give-back that cannot reach qits-idp is logged and left to the commission
   * reconciliation, which reaps a runner's credentials once the row is gone.
   */
  @DELETE
  @Path("/{id}")
  @RolesAllowed("qits:admin")
  @Operation(summary = "Decommission a runner and give its credentials back")
  @APIResponse(responseCode = "204", description = "Gone")
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "The runner holds a running run")
  public Response delete(@PathParam("id") String id) {
    CiRunner gone = runners.delete(runnerId(id));
    if (gone.clientId != null) {
      idp.decommission(gone.clientId);
    }
    if (gone.registrationTokenId != null) {
      idp.deleteToken(gone.registrationTokenId);
    }
    LOG.infof("Runner %s (%s) decommissioned", gone.name, gone.id);
    return Response.noContent().build();
  }

  /**
   * The register door. A runner presents its registration token — as the JWT the edge mints for it,
   * or, on qits-net where there is no edge, as the raw {@code qits_tok_} value, which {@code
   * runnerhost/RegistrationTokenMechanism} introspects at qits-idp for this route alone — with what
   * it says about itself, and is answered its own client, once. A raw token must be a {@code
   * ci-runner-registration} token whose context is this runner (403 otherwise); a value qits-idp
   * does not call live is a 401 before this method runs.
   *
   * <p><b>Four refusals, in this order</b>: a bearer that is no machine token, or is not addressed to
   * this platform, is {@code MachineAuth}'s; no such runner is 404; a {@code sub} that is not this
   * runner's registration token subject is 403; a runner that is already registered is 409 — which
   * is what a replay of the right token answers, since its edge JWT can outlive the token by a few
   * minutes.
   *
   * <p><b>The spent token is deleted at qits-idp, and a failure to is logged rather than fatal.</b>
   * Once the row carries a client the token opens nothing — the subject still matches and the door
   * answers 409 — so a token qits-idp still holds is litter rather than a hole, and the commission
   * reconciliation deletes a registered runner's registration token on its next pass. Failing the
   * registration over it would throw away a client the runner has not been told about.
   */
  @POST
  @Path("/{id}/register")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(REGISTRATION_ROLE)
  @Operation(summary = "Register a runner with its registration token; answers its client, once")
  @APIResponse(
      responseCode = "200",
      description = "The runner's own client and where to use it",
      content = @Content(schema = @Schema(implementation = RegisteredRunner.class)))
  @APIResponse(responseCode = "400", description = "Capabilities that are not a JSON object")
  @APIResponse(responseCode = "403", description = "The token is not this runner's")
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "The runner is already registered")
  @APIResponse(responseCode = "502", description = "qits-idp refused the runner's client")
  @APIResponse(responseCode = "503", description = "This deployment commissions nothing")
  public RegisteredRunner register(@PathParam("id") String id, RegisterRunnerRequest request) {
    IdpCommissioner.IntrospectedToken raw =
        identity.getAttribute(RegistrationTokenIdentityProvider.INTROSPECTED);
    if (raw == null) {
      // Behind the edge: a short JWT the edge minted for the token, checked like any machine bearer.
      machineAuth.require();
    }
    UUID runnerId = runnerId(id);
    String capabilities = capabilities(request);
    String subject;
    if (raw != null) {
      // The raw token, introspected by this service itself: qits-idp vouched that it is live, and
      // what it said must name this door and this runner before the subject is compared at all.
      if (!IdpCommissioner.RUNNER_REGISTRATION_KIND.equals(raw.contextKind())
          || !runnerId.toString().equals(raw.contextId())) {
        throw new ForbiddenException("This registration token is not this runner's");
      }
      subject = raw.subject();
    } else {
      subject = MachineIdentity.claim(identity, "sub").orElse(null);
    }
    CiRunner runner = runners.requireRegistrable(runnerId, subject);
    requireCommissioning();
    IdpCommissioner.Commission client;
    try {
      client = idp.commission(IdpCommissioner.RUNNER_KIND, runnerId.toString(), List.of());
    } catch (IdpCommissioner.CommissionFailedException failed) {
      throw new CiException(
          502, "qits-idp did not commission the runner's client: " + failed.getMessage());
    }
    try {
      runners.markRegistered(runnerId, client.clientId(), capabilities);
    } catch (RuntimeException refused) {
      // Two registrations raced and the other one won: this client was never recorded and nobody
      // will ever be told it, so it goes back now.
      idp.decommission(client.clientId());
      throw refused;
    }
    if (runner.registrationTokenId != null && !idp.deleteToken(runner.registrationTokenId)) {
      LOG.warnf(
          "Runner %s registered, and its spent registration token %s is still live at qits-idp;"
              + " it opens nothing now, and the commission reconciliation reaps it",
          runnerId, runner.registrationTokenId);
    }
    LOG.infof("Runner %s (%s) registered as %s", runner.name, runnerId, client.clientId());
    return new RegisteredRunner(
        client.clientId(),
        client.secret(),
        addresses.tokenUrl(),
        addresses.audience(),
        addresses.socketUrl());
  }

  private static String capabilities(RegisterRunnerRequest request) {
    try {
      return RunnerCapabilities.encode(request == null ? null : request.capabilities());
    } catch (IllegalArgumentException malformed) {
      throw new BadRequestException(malformed.getMessage());
    }
  }

  private IdpCommissioner.CommissionedToken commissionRegistrationToken(UUID runnerId) {
    IdpCommissioner.CommissionedToken token;
    try {
      // gitRefs [] — a registration token may push nothing, and states so.
      token =
          idp.commissionToken(
              IdpCommissioner.RUNNER_REGISTRATION_KIND, runnerId.toString(), List.of());
    } catch (IdpCommissioner.CommissionFailedException failed) {
      throw new CiException(
          502, "qits-idp did not commission the registration token: " + failed.getMessage());
    }
    try {
      RunnerInstallScript.requireCarriable(token.token());
    } catch (IllegalArgumentException uncarriable) {
      // Checked before the row is written, so the token has nowhere to be but qits-idp: give it back.
      idp.deleteToken(token.tokenId());
      throw new CiException(502, uncarriable.getMessage());
    }
    return token;
  }

  /**
   * 503 when this deployment's runner addresses or pinned binary version could not be rendered into
   * an install script — checked before anything is minted, so a misconfiguration costs no token.
   */
  private void requireRenderable() {
    try {
      installScript.requireRenderable();
    } catch (IllegalStateException unrenderable) {
      throw new UnavailableException(unrenderable.getMessage());
    }
  }

  /**
   * 503 when this deployment cannot commission at all — the qits oidc client is off, which is the
   * shipped posture. A runner is nothing without the credentials qits-idp mints for it, so declaring
   * one here would be a row nobody can ever register.
   */
  private void requireCommissioning() {
    if (!idp.enabled()) {
      throw new UnavailableException(
          "This qits-ci commissions no credentials (quarkus.oidc-client.qits.client-enabled is off),"
              + " so it cannot mint a runner's");
    }
  }

  /** A path id that is not a uuid names no runner: 404, the answer for any unknown id. */
  private static UUID runnerId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw new NotFoundException("No runner " + id);
    }
  }
}
