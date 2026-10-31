package eu.wohlben.qits.ci.control;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Says out loud, once at boot, that a retired registry key is still set and is no longer read
 * (qits-757) — {@link RetiredDaemonKeys}' pattern, for its reasons.
 *
 * <p><b>What is retired.</b> {@value #REGISTRY_HOST_KEY} named the host a bare platform step image
 * was prefixed with, and a pinned reference was built with and recognised by. Its shipped default
 * named a host-loopback machine name, and that string was what every step tile showed. A step image
 * is recorded as spelled now, and the registry a lease pulls from is the derived public name
 * {@code registry.qits.<QITS_DOMAIN>}: no value of this key can be right, because none is read.
 * {@value #RESOLVE_SWITCH_KEY} turned that prefixing and the digest pin off together; it went with
 * the prefixing.
 *
 * <p><b>A WARN and never a refusal</b>, and every property {@code Optional} — see {@link
 * RetiredDaemonKeys} for both. The deployer removes an environment entry no default or settings row
 * states on its next update, so this line is expected to go quiet by itself.
 */
@ApplicationScoped
public class RetiredRegistryKeys {

  private static final Logger LOG = Logger.getLogger(RetiredRegistryKeys.class);

  public static final String REGISTRY_HOST_KEY = "qits.artifacts.registry-host";
  static final String RESOLVE_SWITCH_KEY = "qits.ci.resolve-platform-step-images";

  /** The registry host. Nothing replaced it: the public name is derived from the domain. */
  @ConfigProperty(name = REGISTRY_HOST_KEY)
  Optional<String> retiredRegistryHost;

  /** The resolution switch. Nothing replaced it: a platform image is always pinned. */
  @ConfigProperty(name = RESOLVE_SWITCH_KEY)
  Optional<String> retiredResolveSwitch;

  void onStart(@Observes StartupEvent startup) {
    warningFor(REGISTRY_HOST_KEY, retiredRegistryHost).ifPresent(LOG::warn);
    warningFor(RESOLVE_SWITCH_KEY, retiredResolveSwitch).ifPresent(LOG::warn);
  }

  /**
   * The line, or empty when there is nothing to say. Blank counts as unset, {@link
   * RetiredDaemonKeys#warningFor}'s rule for its reason.
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
                    + "' and is NO LONGER READ (qits-757): a step image is recorded as spelled,"
                    + " and the registry a run's image is pinned under and pulled from is the"
                    + " derived public name registry.qits.<QITS_DOMAIN>. This entry can be"
                    + " deleted.");
  }
}
