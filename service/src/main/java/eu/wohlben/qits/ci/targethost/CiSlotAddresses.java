package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.daemonhost.CiDaemonSocket;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>Every address a CI lease is told</b>, each the PUBLIC edge name of a service, {@code
 * https://<host>.qits.<QITS_DOMAIN>} — the composition qits-idp's {@code PlatformDomain} makes for
 * its own origin. A lease runs on a target that may be any node, reached through the platform edge
 * like everything else, so there is no internal alias to fall back to and no override: a qits-ci
 * with no public domain hands out no address ({@link #plane()} is empty) and asks for no lease.
 *
 * <p>This is what was left of {@code runnerhost/RunnerAddresses} and {@code StepAddressPlane}:
 * the runner's own addresses (its socket, its token url, its image) went with the runner, and the
 * per-step image spellings went with per-step images (epic qits-1481). What a lease needs is the
 * daemon's socket and binary, the git host it fetches its mirror from, the two maven urls every
 * repository's settings read, the workspaces door, the registry and mirror hosts a build logs in to,
 * and the one image every lease runs.
 */
@ApplicationScoped
public class CiSlotAddresses {

  /** The project segment of every public name: {@code <host>.qits.<domain>}. */
  static final String PLATFORM_PROJECT = "qits";

  static final String CI_HOST = "ci";
  static final String ARTIFACTS_HOST = "registry";
  static final String MIRROR_HOST = "mirror";
  static final String GITHOST_HOST = "githost";
  static final String WORKSPACES_HOST = "workspaces";

  /** Where the daemons store serves a pinned binary, under the registry's public name. */
  static final String DAEMON_BINARY_PATH = "/artifacts/daemons/";

  static final String MAVEN_REPOSITORY_PATH = "/artifacts/maven/maven";
  static final String MAVEN_CENTRAL_PATH = "/mirror/maven/central";

  @ConfigProperty(name = "qits.ci.domain")
  Optional<String> domain;

  /** A suite's way in: an instance composed for a given domain, with no CDI around it. */
  static CiSlotAddresses forDomain(String domain) {
    CiSlotAddresses addresses = new CiSlotAddresses();
    addresses.domain = Optional.ofNullable(domain);
    return addresses;
  }

  /** The addresses, or empty when this qits-ci knows no public domain. */
  public Optional<Plane> plane() {
    return publicDomain().map(Plane::of);
  }

  Optional<String> publicDomain() {
    return (domain == null ? Optional.<String>empty() : domain)
        .map(String::trim)
        .map(value -> value.toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", ""))
        .filter(value -> value.indexOf('.') > 0);
  }

  /** {@code /artifacts/daemons/<name>/<version>}: the run-pinned binary, as a path with no origin. */
  static String daemonBinaryPath(String daemonName, String version) {
    return DAEMON_BINARY_PATH + daemonName + "/" + (version == null ? "" : version);
  }

  /** The addresses of one public domain. */
  public record Plane(String domain) {

    static Plane of(String domain) {
      return new Plane(domain);
    }

    String origin(String host) {
      return "https://" + host + "." + PLATFORM_PROJECT + "." + domain;
    }

    /** The registry's public host, which serves the hosted npm, maven and OCI stores. */
    public String registryHost() {
      return ARTIFACTS_HOST + "." + PLATFORM_PROJECT + "." + domain;
    }

    /** The mirror's public host, which serves the npmjs and Maven Central caches. */
    public String mirrorHost() {
      return MIRROR_HOST + "." + PLATFORM_PROJECT + "." + domain;
    }

    /** {@code wss://ci.qits.<d>/ci/daemon}: the control socket the lease's daemon dials verbatim. */
    public String daemonUrl() {
      return "wss://" + CI_HOST + "." + PLATFORM_PROJECT + "." + domain + CiDaemonSocket.PATH;
    }

    /** The pinned binary's path under the registry's public name. */
    public String daemonBinaryUrl(String pinnedPath) {
      if (pinnedPath == null || pinnedPath.isBlank()) {
        return "";
      }
      return origin(ARTIFACTS_HOST) + pinnedPath.trim();
    }

    /** The git host's public origin; a repository is {@code <origin>/git/...} under it. */
    public String gitBaseUrl() {
      return origin(GITHOST_HOST);
    }

    public String workspacesUrl() {
      return origin(WORKSPACES_HOST);
    }

    public String mavenRepositoryUrl() {
      return origin(ARTIFACTS_HOST) + MAVEN_REPOSITORY_PATH;
    }

    public String mavenCentralUrl() {
      return origin(MIRROR_HOST) + MAVEN_CENTRAL_PATH;
    }

    /**
     * The hosts a lease's registry login names: the registry and the mirror. The docker client and
     * buildctl pick a login by registry hostname, and a build pulls from the mirror and pushes to the
     * registry.
     */
    public List<String> authHosts() {
      List<String> hosts = new ArrayList<>();
      hosts.add(registryHost());
      if (!mirrorHost().equals(registryHost())) {
        hosts.add(mirrorHost());
      }
      return List.copyOf(hosts);
    }

    /**
     * The ci-slot reference a lease is asked for, under the registry's public name: the run's pin
     * ({@code <any registry host>/qits/build-images/ci-slot@sha256:…}) or, when this deployment pins
     * nothing, the tag — its registry host replaced, its path, tag and digest kept. qits-system
     * refuses any image that does not start with {@code registry.qits.<d>/qits/build-images/ci-slot}.
     */
    public String slotImage(String reference) {
      String path = reference;
      int slash = reference.indexOf('/');
      if (slash > 0) {
        String first = reference.substring(0, slash);
        if (first.indexOf('.') >= 0 || first.indexOf(':') >= 0 || "localhost".equals(first)) {
          path = reference.substring(slash + 1);
        }
      }
      return registryHost() + "/" + path;
    }
  }
}
