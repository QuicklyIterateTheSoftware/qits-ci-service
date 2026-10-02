package eu.wohlben.qits.ci.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiArtifact;
import eu.wohlben.qits.ci.control.CiArtifactPresence;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The sole production {@link CiArtifactPresence}: one {@code GET} against qits-artifacts' hosted
 * maven or npm repository, asking whether a declared artifact exists at a release version. It is
 * what {@code ReleaseJoin} asks before announcing a {@code publish: if-changed} entry.
 *
 * <ul>
 *   <li><b>maven</b> — {@code GET <maven root>/<group path>/<artifactId>/<version>/<artifactId>-<version>.pom}:
 *       200 is present, 404 absent. The pom is the file every maven deploy of a version writes, so
 *       it is the cheapest authoritative "this version exists".
 *   <li><b>npm</b> — {@code GET <npm root>/<name>}, the packument (a scoped name with its slash
 *       spelled {@code %2f}, {@code @qits%2fui-components}, which qits-artifacts' route grammar
 *       matches): present when
 *       {@code versions[<version>]} exists, absent on a 404 or a packument without that version.
 * </ul>
 *
 * <p>Anything else — a 5xx, any other status, a timeout, a refused connection, a packument that is
 * not JSON — is {@link Verdict#INCONCLUSIVE}. One attempt per call; the retry is the join's.
 *
 * <p><b>{@link #newest}</b> (qits-620) asks qits-artifacts' content-hash door, {@code GET
 * <origin>/artifacts/content-hashes/<maven|npm>/<name>/-/newest}, which answers the newest version
 * by version order (never a dist-tag) as {@code {"version": …}}, and 404 when no version of the name
 * exists. The name goes into the path <b>as declared</b>, its {@code :} and scope {@code /} literal,
 * because that is how the store's route grammar matches a coordinate and how the qits CLI sends it;
 * it is held to the declaration charset first, so nothing else can reach the path.
 *
 * <p><b>The address is the one qits-ci already reaches the store at, never a new key</b> — {@link
 * HttpImagePins}' rule and {@link ArtifactsOrigin}'s derivation: {@code qits.artifacts.url} when a
 * deployment sets it, otherwise the origin of {@code qits.artifacts.maven.registry-url}. The maven
 * root is {@code qits.artifacts.maven.registry-url} itself when no explicit origin is set (it IS the
 * hosted maven root), and {@code <origin>/artifacts/maven/maven} when one is; the npm root is {@code
 * <origin>/artifacts/npm/npm} — the same two paths {@code StepAddressPlane} tells a step.
 *
 * <p><b>No credential, deliberately</b>, for {@link HttpImagePins}' reason: qits-artifacts' {@code
 * PublishGuard} guards only the publish verbs ({@code PUT} on {@code /artifacts/maven/…} and {@code
 * /artifacts/npm/…}) and lets every read through, so presenting one would be a credential offered
 * where the store asks for none.
 *
 * <p>An instance {@code HttpClient}, not a static one — a static client is created at image-build
 * time and native-image refuses the heap it lands in. The packument is walked with {@code readTree},
 * which binds nothing, so no reflection registration is owed.
 */
@ApplicationScoped
public class HttpArtifactPresence implements CiArtifactPresence {

  /** Bound on opening the socket. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Bound on the whole exchange — a packument of a long-lived package is the largest answer. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  /** qits-artifacts' hosted maven repository under its root. */
  static final String MAVEN_PATH = "/artifacts/maven/maven";

  /** qits-artifacts' hosted npm repository under its root, the one {@code @qits/*} publishes to. */
  static final String NPM_PATH = "/artifacts/npm/npm";

  /** qits-artifacts' content-hash door under its root (qits-620). */
  static final String CONTENT_HASHES_PATH = "/artifacts/content-hashes";

  /** What a declared coordinate may be — the slot parser's {@code SCRIPT_SAFE}, no {@code ..}. */
  private static final String COORDINATE = "[A-Za-z0-9._:/@+-]+";

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  private final ObjectMapper json = new ObjectMapper();

  @ConfigProperty(name = "qits.artifacts.url")
  Optional<String> artifactsUrl;

  @ConfigProperty(name = "qits.artifacts.maven.registry-url")
  String artifactsMavenRegistryUrl;

  @Override
  public Probe probe(CiArtifact.Type type, String name, String version) {
    if (type == null || name == null || name.isBlank() || version == null || version.isBlank()) {
      return Probe.inconclusive("nothing to ask: type " + type + ", name " + name + ", version " + version);
    }
    return switch (type) {
      case MAVEN -> maven(name, version);
      case NPM -> npm(name, version);
      default -> Probe.inconclusive("qits-artifacts is not asked about a " + type.declared() + " artifact");
    };
  }

  @Override
  public Probe newest(CiArtifact.Type type, String name) {
    if (type != CiArtifact.Type.MAVEN && type != CiArtifact.Type.NPM) {
      return Probe.inconclusive(
          "qits-artifacts keeps no newest version for a "
              + (type == null ? "null" : type.declared())
              + " artifact");
    }
    if (name == null || !name.matches(COORDINATE) || name.contains("..")) {
      return Probe.inconclusive("'" + name + "' is not a coordinate the store can be asked about");
    }
    String origin = origin();
    if (origin.isBlank()) {
      return unconfigured();
    }
    String url = origin + CONTENT_HASHES_PATH + "/" + type.declared() + "/" + name + "/-/newest";
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(REQUEST_TIMEOUT)
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        return Probe.absent("GET " + url + " answered 404");
      }
      if (response.statusCode() != 200) {
        return Probe.inconclusive("GET " + url + " answered HTTP " + response.statusCode());
      }
      JsonNode version = json.readTree(response.body()).path("version");
      if (!version.isTextual() || version.asText().isBlank()) {
        return Probe.inconclusive("GET " + url + " answered 200 with no 'version'");
      }
      return Probe.newest(
          version.asText(), "GET " + url + " answered version " + version.asText());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Probe.inconclusive("GET " + url + " was not asked: interrupted");
    } catch (Exception e) {
      return Probe.inconclusive("GET " + url + " could not be asked: " + e);
    }
  }

  private Probe maven(String coordinate, String version) {
    String root = mavenRoot();
    if (root.isBlank()) {
      return unconfigured();
    }
    int colon = coordinate.indexOf(':');
    if (colon <= 0 || colon == coordinate.length() - 1 || coordinate.indexOf(':', colon + 1) >= 0) {
      return Probe.inconclusive(
          "'" + coordinate + "' is not a groupId:artifactId coordinate, so no pom path can be built");
    }
    String groupPath = coordinate.substring(0, colon).replace('.', '/');
    String artifactId = coordinate.substring(colon + 1);
    String url =
        root
            + "/"
            + segments(groupPath)
            + "/"
            + segment(artifactId)
            + "/"
            + segment(version)
            + "/"
            + segment(artifactId + "-" + version + ".pom");
    try {
      HttpResponse<Void> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      return switch (response.statusCode()) {
        case 200 -> Probe.present("GET " + url + " answered 200");
        case 404 -> Probe.absent("GET " + url + " answered 404");
        default -> Probe.inconclusive("GET " + url + " answered HTTP " + response.statusCode());
      };
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Probe.inconclusive("GET " + url + " was not asked: interrupted");
    } catch (Exception e) {
      return Probe.inconclusive("GET " + url + " could not be asked: " + e);
    }
  }

  private Probe npm(String name, String version) {
    String origin = origin();
    if (origin.isBlank()) {
      return unconfigured();
    }
    String url = origin + NPM_PATH + "/" + npmPackageSegment(name);
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(REQUEST_TIMEOUT)
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        return Probe.absent("GET " + url + " answered 404");
      }
      if (response.statusCode() != 200) {
        return Probe.inconclusive("GET " + url + " answered HTTP " + response.statusCode());
      }
      JsonNode versions = json.readTree(response.body()).path("versions");
      if (!versions.isObject()) {
        return Probe.inconclusive("GET " + url + " answered 200 with no 'versions' object");
      }
      return versions.has(version)
          ? Probe.present("GET " + url + " lists versions[" + version + "]")
          : Probe.absent("GET " + url + " does not list versions[" + version + "]");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Probe.inconclusive("GET " + url + " was not asked: interrupted");
    } catch (Exception e) {
      return Probe.inconclusive("GET " + url + " could not be asked: " + e);
    }
  }

  /** The hosted maven root, {@code ""} when the deployment states no artifacts address at all. */
  String mavenRoot() {
    String explicit = ArtifactsOrigin.explicit(artifactsUrl);
    if (explicit != null) {
      return explicit + MAVEN_PATH;
    }
    String configured = artifactsMavenRegistryUrl == null ? "" : artifactsMavenRegistryUrl.trim();
    return configured.replaceAll("/+$", "");
  }

  /** The store's origin, {@code ""} when none is stated. */
  String origin() {
    return ArtifactsOrigin.of(artifactsUrl, artifactsMavenRegistryUrl);
  }

  private static Probe unconfigured() {
    return Probe.inconclusive(
        "this deployment states no qits-artifacts origin (qits.artifacts.url,"
            + " qits.artifacts.maven.registry-url), so the store cannot be asked");
  }

  /**
   * A package name as ONE path segment, the way the npm CLI sends it: a scoped name keeps its
   * literal {@code @} and has its slash spelled {@code %2f} ({@code @qits%2fui-components}). That is
   * the exact shape qits-artifacts' route grammar matches ({@code NpmPaths.PACKAGE} in
   * qits-registries-javalib) — a {@code %40} would not match it, so this is not a plain encode.
   */
  static String npmPackageSegment(String name) {
    int slash = name.indexOf('/');
    if (name.startsWith("@") && slash > 1) {
      return "@" + segment(name.substring(1, slash)) + "%2f" + segment(name.substring(slash + 1));
    }
    return segment(name);
  }

  /** Each {@code /}-separated part of a path encoded on its own, the slashes kept. */
  private static String segments(String path) {
    StringBuilder out = new StringBuilder();
    for (String part : path.split("/", -1)) {
      if (!out.isEmpty()) {
        out.append('/');
      }
      out.append(segment(part));
    }
    return out.toString();
  }

  /** One path segment, percent-encoded — a {@code /} inside it included. */
  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
