package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The two spellings of the qits CLI's daemon-store name are one string, and this is what keeps them
 * that way.
 *
 * <p>qits-ci ships {@code qits.ci.artifacts-cli-package=qits-platform-access-cli} as the package a
 * composed release-phase step downloads the CLI from; the CLI's own repository declares the same
 * name as {@link PlatformAccessCliBinary#DAEMON_NAME}, which is the string its release recipe PUTs
 * to. Two literals, two repositories, one coordinate — and a rename that moved only one side is a
 * fetch that 404s on every composed release at once, with nothing on either side saying why.
 *
 * <p><b>It reads the SHIPPED file, not the effective configuration.</b> A {@code @ConfigProperty}
 * injection or a {@code ConfigProvider} lookup would answer whatever this suite, a system property
 * or an environment variable happened to say, so it could pass against a default that had already
 * drifted. The resource on the classpath is the thing that deploys.
 *
 * <p>Plain JUnit and in {@code service} rather than in {@code ci}: this module sees both the {@code
 * ci} jar's resources and the pinned {@code qits-platform-access-cli-binary} constant, and {@code
 * ci} does not depend on the latter — the launcher is what needs a version, and the launcher is
 * here.
 */
public class ArtifactsCliPackageDefaultTest {

  private static final String RESOURCE = "META-INF/microprofile-config.properties";
  private static final String KEY = "qits.ci.artifacts-cli-package";

  @Test
  public void theShippedPackageDefaultIsTheCliRepositorysOwnDaemonName() throws Exception {
    Properties shipped = new Properties();
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE)) {
      assertNotNull(in, RESOURCE + " is not on the classpath — the ci jar ships it");
      shipped.load(in);
    }

    assertEquals(
        PlatformAccessCliBinary.DAEMON_NAME,
        shipped.getProperty(KEY),
        KEY
            + " and PlatformAccessCliBinary.DAEMON_NAME name the same artifact in the daemons store."
            + " One of them was renamed without the other, and a composed release step would"
            + " download a coordinate nothing published.");
  }
}
