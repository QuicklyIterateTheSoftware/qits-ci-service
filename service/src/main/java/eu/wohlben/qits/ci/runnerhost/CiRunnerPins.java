package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Which {@code qits-ci-runner} version a runner is meant to be — the image an install script
 * rendered right now starts, and the version every {@code Hello} is compared with (a runner of any
 * other is sent {@code Upgrade}, qits-465): the
 * deployment's {@code qits.ci.runner-version-override} when it is set, otherwise {@link
 * CiRunnerBinary#VERSION} — the version of the protocol jar this reactor pins ({@code
 * qits.ci-runner-protocol.version} in the root pom), which is by construction the version of the
 * binary the same release published. {@code CiDaemonPins}' twin, for the same reason: the wire
 * contract this host speaks and the binary it hands out are one artifact, so a bump of one is a bump
 * of the other and a deployment act cannot pair them wrongly.
 *
 * <p>It lives in {@code runnerhost} rather than beside {@code CiDaemonPins} in {@code ci/control}
 * because the runner protocol is a dependency of this module alone, and the install script and
 * the runner socket are its only readers.
 */
@ApplicationScoped
public class CiRunnerPins {

  /** The runner's name — its image's last path segment — from the protocol jar. */
  public static final String RUNNER_NAME = CiRunnerBinary.RUNNER_NAME;

  /** The override key, spelled once. */
  public static final String OVERRIDE_KEY = "qits.ci.runner-version-override";

  /**
   * The emergency hatch, expected unset. {@code Optional} because an absent key and one rendered as
   * {@code KEY=} must both read as unset — {@code CiDaemonPins.versionOverride}'s reasoning.
   */
  @ConfigProperty(name = OVERRIDE_KEY)
  Optional<String> versionOverride;

  /** The override if a person set one, the pinned version otherwise; never blank. */
  public String version() {
    String override = versionOverride == null ? "" : versionOverride.map(String::trim).orElse("");
    return override.isEmpty() ? CiRunnerBinary.VERSION : override;
  }
}
