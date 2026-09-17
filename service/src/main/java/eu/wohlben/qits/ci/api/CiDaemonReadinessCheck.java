package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * <b>An informational readout, not a gate.</b> It is UP unconditionally and reports which daemon
 * binary this instance launches and where that came from, as health data.
 *
 * <p><b>Why it can no longer be DOWN, and why that is not a bug to fix.</b> It used to be DOWN on
 * one state: the daemon pin ladder having fallen all the way through — every adopted candidate
 * probed {@code REJECTED} and no configured pin behind them — which was a real condition with a
 * real cause, since the ladder's rungs came from released versions this service adopted on its own
 * and probed on its own. The version is the pinned protocol dependency's now
 * ({@link CiDaemonPins}), resolved at build time and refusing to resolve to blank, so there is no
 * state in which this instance has no daemon version to name. The arm was not removed because it
 * was inconvenient; it became unreachable because the thing it watched stopped existing.
 *
 * <p><b>A check that cannot be DOWN must not pretend to be a gate</b>, which is exactly what
 * leaving the old javadoc in place would have done: a reader of {@code /q/health/ready} seeing a
 * readiness entry reasonably assumes something is being judged. So this says plainly that nothing
 * is. It is kept rather than deleted for the one thing it still does well — putting the running
 * daemon version where an operator, {@code docker inspect} and a monitor already look, with no
 * second lookup and no credential — and it is cheap by construction, because the underlying read is
 * two constants and a trim rather than the query-and-maybe-probe it once was.
 *
 * <p><b>What the deleted DOWN arm cost qits-cd's {@code awaitHealthy}: nothing.</b> That gate is
 * the single most valuable thing a readiness check here reaches, and it is not lost, because
 * {@link CiRunWorkerReadinessCheck} is the real gate and was always the better one — a qits-ci with
 * no claim loop accepts runs and executes none, which is the failure that actually shipped
 * (2026-09-07, green-while-dead) and the one a restored previous container actually fixes. A bad
 * daemon version, by contrast, is now a bad <em>pom</em>, and a pom is gated by this repository's
 * own release request, which runs that binary at that version against this host before the merge —
 * a deployment cannot be the first thing to find out.
 *
 * <p><b>The name is {@code ci-daemon-version} and the rename is the honest half of the same
 * point.</b> It was {@code ci-daemon-pin}, which is ladder vocabulary: a "pin" there was a rung
 * that could be absent, and the entry's whole job was to say whether one had been found. Naming a
 * pure readout after the thing it no longer decides is how a reader concludes a gate is in place.
 * Nothing on the platform binds the literal — it is a key in {@code /q/health/ready}'s JSON, read
 * by people — so the rename costs a glance and buys the name being true.
 */
@Readiness
@ApplicationScoped
public class CiDaemonReadinessCheck implements HealthCheck {

  static final String NAME = "ci-daemon-version";

  @Inject CiDaemonPins pins;

  @Override
  public HealthCheckResponse call() {
    CiDaemonPins.Pin pin = pins.answer();
    return HealthCheckResponse.named(NAME)
        .up()
        .withData("daemonName", CiDaemonPins.DAEMON_NAME)
        .withData("daemonVersion", pin.version())
        .withData("source", pin.source())
        .build();
  }
}
