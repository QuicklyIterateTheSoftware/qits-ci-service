package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Says out loud, once at boot, that a retired configuration key is set and is no longer read.
 *
 * <h2>What is retired, and why the entries are still there</h2>
 *
 * <p>Three keys stopped being read when the daemon pin ladder was retired.
 * {@code qits.ci.daemon-version} was the deployment's own pin — the standing platform holds it as
 * {@code env.QITS_CI_DAEMON_VERSION}, a sha256 digest written by qits-bootstrap — and the version
 * now comes from the pinned protocol dependency instead ({@link CiDaemonPins}).
 * {@code qits.ci.daemon-autoadopt-enabled} switched the bus listener that adopted daemon releases,
 * and {@code qits.ci.daemon-probe-image} named the image a candidate's probe container ran; the
 * listener, the probe and the table behind them are deleted.
 *
 * <p><b>But the entries already written are still in every deployment's environment.</b> Nothing on
 * this platform deletes a configuration entry — qits-configuration reports an orphan and never
 * cleans it up — and {@code DELETE …/entries/{key}} is {@code {qits:admin, qits:system}}, which the
 * credential this change was made under does not hold. What makes {@code qits.ci.daemon-version}
 * stop deciding is therefore not a deletion but a <em>rename</em>: the override is
 * {@code qits.ci.daemon-version-override}, a name nothing has ever written, so the residue is read
 * by nobody. The other two have no successor at all.
 *
 * <h2>Why a warning rather than nothing</h2>
 *
 * <p>Inert and invisible is the wrong pair. A digest sitting in the environment under a key that
 * <em>looks</em> like it decides which daemon binary every step container downloads, and does not,
 * is exactly the sort of thing that costs somebody an afternoon — and this process is the only
 * reader placed to notice it. So the line names the key, its stale value and the version really
 * used: the residue becomes a work item somebody with {@code qits:admin} closes whenever, instead
 * of a prerequisite for this change to be correct.
 *
 * <p><b>A WARN and never a refusal.</b> The entry is harmless by construction — nothing reads it.
 * Refusing to start over a stale key would turn a tidy-up into an outage, and this service starting
 * is worth more than this service being fastidious.
 *
 * <h2>Why it is its own bean, and why every property is {@code Optional}</h2>
 *
 * <p>Copied deliberately from qits-workspaces' {@code RetiredImageVersionKeys}, including the
 * mistake it records. Observing {@link StartupEvent} forces the <em>observing bean</em> to be
 * created at boot, so an observer parked on a bean that carries required config makes that config
 * mandatory for every test that instantiates the bean — on the sibling ticket a whole module's
 * {@code @QuarkusTest}s went red on configuration rather than on behaviour. A bean whose every
 * {@code @ConfigProperty} is {@code Optional} can be created anywhere, which is what this one is:
 * it must never be put on {@link CiDaemonPins}, {@code CiRunService} or anything else that has real
 * work to do.
 */
@ApplicationScoped
public class RetiredDaemonKeys {

  private static final Logger LOG = Logger.getLogger(RetiredDaemonKeys.class);

  static final String VERSION_KEY = "qits.ci.daemon-version";
  static final String AUTOADOPT_KEY = "qits.ci.daemon-autoadopt-enabled";
  static final String PROBE_IMAGE_KEY = "qits.ci.daemon-probe-image";

  /** The deployment's old pin. Superseded by a rename, so it has something to point at. */
  @ConfigProperty(name = VERSION_KEY)
  Optional<String> retiredVersionKey;

  /** The adoption switch. Nothing replaced it: there is no adoption. */
  @ConfigProperty(name = AUTOADOPT_KEY)
  Optional<String> retiredAutoadoptKey;

  /** The probe container's image. Nothing replaced it: there is no probe. */
  @ConfigProperty(name = PROBE_IMAGE_KEY)
  Optional<String> retiredProbeImageKey;

  void onStart(@Observes StartupEvent startup) {
    warnIfSet(VERSION_KEY, retiredVersionKey);
    warnIfSet(AUTOADOPT_KEY, retiredAutoadoptKey);
    warnIfSet(PROBE_IMAGE_KEY, retiredProbeImageKey);
  }

  private static void warnIfSet(String key, Optional<String> value) {
    warningFor(key, value).ifPresent(LOG::warn);
  }

  /**
   * The line, or empty when there is nothing to say. Split out from the logging so a test can
   * assert the <em>message</em> — that it names the key, the stale value and the version actually
   * used — rather than assert that some logger was called, which is a claim about a framework.
   *
   * <p>Blank counts as unset, for the reason every optional key here does: SmallRye maps an
   * environment variable onto the property, and a deployment rendering {@code KEY=} produces a
   * present, empty value rather than an absent one. Warning about that would be warning about a
   * template with nothing in it.
   */
  static Optional<String> warningFor(String key, Optional<String> value) {
    return value
        .map(String::trim)
        .filter(set -> !set.isEmpty())
        .map(
            set ->
                key
                    + " is set to '"
                    + set
                    + "' and is NO LONGER READ: this service takes the daemon version from the"
                    + " protocol dependency it pins ("
                    + CiDaemonBinary.VERSION
                    + "). Set "
                    + CiDaemonPins.OVERRIDE_KEY
                    + " to run a different binary deliberately; otherwise this entry can be"
                    + " deleted.");
  }
}
