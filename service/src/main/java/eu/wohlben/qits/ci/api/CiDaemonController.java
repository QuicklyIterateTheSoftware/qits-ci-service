package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The daemon binary this qits-ci launches, and where that answer came from:
 * {@code {"daemonName", "daemonVersion", "previousDaemonVersion", "source"}}.
 *
 * <p><b>The pin, never a run row.</b> {@code ci_run.daemon_version} is history — what some run once
 * launched — and the run listing clamps at 100, so the rows cannot even enumerate what this
 * instance has launched over its life. This answers the different and much smaller question: what
 * would a run started right now download. There is exactly one place that knows it,
 * {@link CiDaemonPins}, which is why this reads that rather than a config key of its own.
 *
 * <p><b>{@code daemonVersion} is never blank, and that is what changed.</b> It used to be: the
 * shipped state of a deployment that had adopted and pinned no daemon, spelled {@code source:
 * "none"}. The version is now the pinned protocol jar's own, resolved at build time, so a
 * deployment that could produce a blank here could not have been built. The two {@code source}
 * values left are {@code "pinned"} (the dependency's version — the ordinary answer) and
 * {@code "override"} (a person set {@code qits.ci.daemon-version-override}); {@code "adopted"},
 * {@code "configured"} and {@code "none"} went with the ladder.
 *
 * <p><b>{@code previousDaemonVersion} is permanently blank</b> and is kept for the shape's sake
 * alone — see {@link CiDaemonPins.Pin}, which argues why an always-blank key is better than a key
 * removed from a document another repository binds.
 *
 * <p><b>Who asks.</b> qits-artifacts' daemon-binary GC reads it when it plans a sweep: the blobs a
 * live pin names are the ones it must keep, and an unreachable qits-ci aborts the plan with nothing
 * deleted. That is the same fail-closed shape the docker strategy already has against qits-cd's
 * deployments, and it is why the pin needed a queryable surface at all — the alternative was a
 * hand-maintained allowlist in qits-artifacts that a deployment bumping the pin would forget,
 * arming the GC against its own CI. {@code daemonName} is reported so that lookup needs no inference
 * — {@code (repository, name, version)} is exactly how qits-artifacts' own {@code daemon_binary} rows
 * are keyed. The client at {@code /ci/} may read the same value to show which daemon the platform
 * runs.
 *
 * <p>Read-only and unguarded, exactly like the run and repository reads. There is no secret here —
 * the version is already in every step container's environment and on every run row — and this
 * service authenticates nothing anyway (the gateway does).
 *
 * <p>In the OpenAPI document rather than hidden, on this repo's standing criterion: does a
 * first-party client consume it, is its contract written down anywhere else. Its contract lives
 * here, its consumer is another first-party service reading it fail-closed, and a client draws from
 * it — so a change to the shape belongs in a reviewable diff. Nothing is hidden any more: the one
 * operation that was, {@code POST /ci/api/events/post-receive}, is gone — first to the bus, then
 * with per-push CI itself.
 *
 * <p>An ordinary JAX-RS resource under {@code quarkus.rest.path}, so it adds <b>no literal route</b>
 * and {@code quarkus.quinoa.ignored-path-prefixes} is unchanged — {@code /api} already covers it.
 * Note the address is {@code /ci/api/daemon} and the control socket is {@code /ci/daemon}: adjacent
 * spellings, unrelated surfaces, and only this one is a resource.
 *
 * <p>This class used to be careful about <em>which</em> read of {@link CiDaemonPins} it made: there
 * were two, and the probing one would have let anyone who can reach this endpoint launch a
 * container on demand. There is one method now and it touches neither a database nor a container,
 * so the care has nowhere left to go wrong.
 */
@Path("/daemon")
@Produces(MediaType.APPLICATION_JSON)
// qits:agent reads it too: agents keep every read and write nothing, and this class has no write.
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
public class CiDaemonController {

  @Inject CiDaemonPins pins;

  /**
   * A field per answer rather than a bare string so the shape stays extensible — a caller binding
   * these four keeps working the day this grows a sibling.
   */
  public record DaemonPinDto(
      String daemonName, String daemonVersion, String previousDaemonVersion, String source) {}

  @GET
  @Operation(summary = "The daemon binary this instance is configured to launch")
  @APIResponse(
      responseCode = "200",
      description =
          "The pinned daemon binary. daemonVersion is the version of the protocol dependency this"
              + " service is built against, with source \"pinned\"; source is \"override\" when a"
              + " deployment has set qits.ci.daemon-version-override. previousDaemonVersion is"
              + " always blank: a pin has no fallback rung.")
  public DaemonPinDto daemonPin() {
    CiDaemonPins.Pin pin = pins.answer();
    return new DaemonPinDto(
        CiDaemonPins.DAEMON_NAME, pin.version(), pin.previousVersion(), pin.source());
  }
}
