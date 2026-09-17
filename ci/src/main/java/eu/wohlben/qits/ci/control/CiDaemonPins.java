package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Which {@code qits-ci-daemon} binary a run started right now downloads, and where that answer came
 * from. Two rungs and no table: the deployment's {@code qits.ci.daemon-version-override} when it is
 * set, otherwise {@link CiDaemonBinary#VERSION} — the version of the protocol jar this reactor
 * pins, which is by construction the version of the binary the same release published.
 *
 * <p><b>The pin is the pom now, and that is the whole of this class.</b> The protocol contract both
 * sides speak and the binary one side downloads are one artifact
 * ({@code eu.wohlben.qits:qits-ci-daemon-protocol}), so bumping one is bumping the other and the
 * pair cannot be mismatched by a deployment act. What that replaced is worth having in front of
 * you, because every deleted class here was part of it: a durable ladder of candidate versions
 * (the {@code ci_daemon_pin} table, retired in {@code V18}), adopted off {@code SoftwareRelease}
 * frames by a bus listener, each probed by launching a throwaway container running whatever had
 * just been published and keeping it if the container dialled back. So a daemon reached a real
 * step without the pair ever having been built together, and the only gate on a protocol break was
 * a probe the released service ran on itself, in production, against its own host. Now the gate is
 * qits-ci's own release request, which runs this binary at this version before the merge.
 *
 * <p><b>Why the override is spelled {@code …-version-override} rather than keeping the old name.</b>
 * The key it replaces is {@code qits.ci.daemon-version}, and the standing platform still holds an
 * entry for it — {@code env.QITS_CI_DAEMON_VERSION}, a sha256 digest written by qits-bootstrap.
 * <b>Nothing on this platform deletes a configuration entry.</b> qits-configuration reports an
 * orphan and never cleans it up, and {@code DELETE …/entries/{key}} is {@code {qits:admin,
 * qits:system}}, which the {@code qits:agent} credential this work is done under cannot call. So
 * "retire the key" could not mean "remove the value"; it had to mean <em>stop reading that name</em>,
 * and a rename is the only move that does it with no deletion required and no window in which the
 * residue still decides. It buys a second thing on its own merits: the emergency hatch now carries
 * a name <b>no automation has ever written</b>, so a value found in it was put there by a person on
 * purpose, which is exactly what an override should be able to say about itself.
 * {@code RetiredDaemonKeys} is what makes the residue audible rather than silent.
 *
 * <p><b>There is no database read left</b>, so this is a pure read of two values and a trim. That
 * is why {@code currentAnswer()} is gone rather than kept as a name: it existed only as
 * {@link #answer}'s non-probing, {@code DbRetry}-wrapped twin, for callers (the health check on the
 * container healthcheck's cadence, the unguarded {@code GET /ci/api/daemon}) that must not launch a
 * probe container and must not fail on a severed connection. With no probe and no query there is
 * one answer for every caller and nothing to be patient about, and two methods that cannot differ
 * are two chances to pick the wrong one.
 */
@ApplicationScoped
public class CiDaemonPins {

  /**
   * The daemon's artifact name in qits-artifacts' {@code daemons} store. Taken from the protocol
   * jar rather than spelled again here: the release pipeline PUTs the bytes under
   * {@code …/artifacts/daemons/<name>/<version>} and a step container's bootstrap downloads from
   * exactly that path, so a second literal on this side is a second thing a rename can leave behind.
   */
  public static final String DAEMON_NAME = CiDaemonBinary.DAEMON_NAME;

  /** The version came from the pinned dependency — the ordinary state of every deployment. */
  public static final String SOURCE_PINNED = "pinned";

  /** The version came from {@code qits.ci.daemon-version-override} — a person overrode the pin. */
  public static final String SOURCE_OVERRIDE = "override";

  /**
   * The override key, spelled once. {@link RetiredDaemonKeys} names it in the line it prints about
   * the retired key, and the injection point below is bound to it; a second literal would be a
   * second thing a rename can leave behind, which is the whole failure mode the rename below is
   * about.
   */
  public static final String OVERRIDE_KEY = "qits.ci.daemon-version-override";

  /**
   * The emergency hatch, and it is expected to be unset. Setting it runs a daemon binary that no
   * release request of this repository has ever run against this host, which is the honest reading
   * of an override and the reason it is not called a pin.
   *
   * <p>{@code Optional} because an absent key and a key rendered as {@code KEY=} must both be
   * "unset" — SmallRye maps an environment variable onto the property and an empty deployment
   * template produces a present, empty value — and because a bare {@code String} injection point
   * with no default fails the whole deployment on the one value that means "off".
   *
   * <p>Public, unlike a normal injected field, because {@code CiDaemonPinTest} (in {@code service},
   * a different package) stages it through {@link io.quarkus.arc.ClientProxy} rather than paying
   * for a second Quarkus start to supply one config value — see that test's own javadoc.
   */
  @ConfigProperty(name = OVERRIDE_KEY)
  public Optional<String> versionOverride;

  /**
   * What a run started right now would download, and where it came from — the shape
   * {@code GET /ci/api/daemon} answers verbatim.
   *
   * <p><b>{@code previousVersion} is permanently blank, and that is an honest answer rather than a
   * field nobody got round to filling.</b> It used to name the rung below the current one: the next
   * proven adopted candidate, the version this instance would actually try if the current one
   * stopped registering. There is no ladder and therefore no rung below — a pinned version has no
   * fallback, because the fallback for a bad pin is a commit that changes the pin. The field stays
   * on the wire because the response shape is published in {@code docs/openapi.yml} and read
   * fail-closed by qits-artifacts' daemon-binary GC; dropping a key from a document another
   * repository's sweep binds is a change to that sweep, and "always blank" costs it nothing.
   */
  public record Pin(String version, String previousVersion, String source) {}

  /**
   * The override if a person set one, the pinned version otherwise. Never blank in a working
   * deployment: {@link CiDaemonBinary#VERSION} refuses to exist rather than resolve to {@code ""},
   * because a blank version composes a download path that 404s inside a throwaway container nobody
   * is watching.
   */
  public Pin answer() {
    String override = versionOverride.map(String::trim).orElse("");
    if (!override.isEmpty()) {
      return new Pin(override, "", SOURCE_OVERRIDE);
    }
    return new Pin(CiDaemonBinary.VERSION, "", SOURCE_PINNED);
  }
}
