package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.ci.control.CiEventTriggerParser;
import eu.wohlben.qits.ci.control.CiReleaseComposer;
import eu.wohlben.qits.ci.control.CiReleaseSlotParser;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>THE PIN TEST.</b> The {@code qits} CLI at exactly the version this reactor pins is downloaded
 * from the real artifacts store and made to publish an SBOM — driven by a <em>composed</em>
 * release-phase step script, the one {@link CiReleaseComposer} really emits, run under {@code bash}
 * with the environment {@link CiDaemonLauncher} really injects.
 *
 * <h2>What it is for</h2>
 *
 * <p>qits-ci's composed release prelude used to hand every step whatever was latest in
 * qits-artifacts' {@code daemons} store at the moment the step started. That is a shared,
 * unversioned, unreviewed input to every release on the platform at once: on 2026-09-13 one bad CLI
 * release broke all of them, with nothing changed in any consumer's tree and no line anybody could
 * revert. The version is a pinned dependency now ({@link PlatformAccessCliBinary#VERSION}, off
 * {@code eu.wohlben.qits:qits-platform-access-cli-binary}), and this is the test that makes the pin
 * mean something: a bump to a CLI that cannot publish an SBOM fails <em>this repository's</em>
 * release request, which is a red gate on a branch, rather than every composed release at once.
 *
 * <h2>Three things are under test and none of them is a copy of the others</h2>
 *
 * <ul>
 *   <li><b>The coordinate exists.</b> The pom pins a version, and that version really is in the
 *       real store. That is the assertion a red build gets you a day earlier than a release.
 *   <li><b>The composed script works.</b> The prelude is taken from the shipped composer rather than
 *       hand-written here, so the {@code curl}, the {@code chmod}, the {@code qits-publish} symlink
 *       and the {@code PATH} export are the text every release on the platform runs.
 *   <li><b>The binary at that coordinate does the job.</b> It is executed, and the SBOM it publishes
 *       is read off the far side — the stub's record of the PUT — rather than inferred from an exit
 *       code.
 * </ul>
 *
 * <h2>Not a {@code @QuarkusIntegrationTest}, deliberately</h2>
 *
 * <p>Nothing in the assertion needs the application. The subject is whether the pinned binary and
 * this reactor's composed text still agree, and both ends of that are on this classpath: {@link
 * CiReleaseComposer} is a pure function and the binary is bytes. A second launched artifact would
 * mean a second {@code @TestProfile} — a second whole qits-ci beside the story catalogue's, with its
 * own boot, its own databases and its own port — for no assertion a plain JUnit class cannot make.
 * qits-workspaces' {@code WorkspaceDaemonPinIT} makes the same judgement in the same words.
 *
 * <h2>It gates, and it skips only where it must</h2>
 *
 * <p>No {@code @Tag("extended")}: this one has to run. Where an artifacts origin IS configured, a
 * missing artifact or a failed publish is a <b>failure</b> and never a skip — in a CI step the
 * origin is always injected, so this cannot quietly pass by not running. Name it in {@code
 * .config/qits/ci-event-release-request.yml}'s {@code -Dit.test} list or it never runs there at all,
 * silently.
 *
 * <p><b>The one skip is defensive rather than a supported mode.</b> It covers an artifacts origin
 * configured to nothing, and it is deliberately not load-bearing: this repository's clone-alone rule
 * already reads "a clone builds against the platform Maven repository", and the CLI pin is one of
 * the dependencies resolved from it — so a checkout with no platform to ask fails at dependency
 * resolution long before any test runs. The branch exists so a deployment that blanks the address
 * gets a legible sentence instead of a malformed URL.
 *
 * <h2>Why the store is a stub and the binary is not</h2>
 *
 * <p>The download is from the REAL store, because "the pinned version exists" is the assertion. The
 * publish is against a stub in this process, because the alternative is writing an SBOM into the
 * platform's own sbom store at a coordinate nobody released — an immutable surface, so the litter
 * would be permanent and a re-run would then be asserting against its own first run.
 */
public class QitsCliPinIT {

  /**
   * Where qits-artifacts is, derived from the Maven repository address the build already carries —
   * the same {@code ${…%%/artifacts/*}} arithmetic every release pipeline does, because the daemon
   * store is a sibling path of the maven one inside one deployment. Derived rather than given its
   * own key, so there is no second address to configure wrongly.
   */
  private static final String ARTIFACTS_BASE = artifactsBase();

  /** A version that is a release's shape and is no release: nothing is published under it. */
  private static final String RELEASE_VERSION = "2026.101.10101";

  /** What the composed postlude submits, and the coordinate the stub must be asked for. */
  private static final String SBOM_TYPE = "docker";

  private static final String SBOM_NAME = "qits/qits-ci-pin-it";

  private static final String SBOM_PATH = "out/sbom.json";

  private static final String SBOM_BODY =
      "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"version\":1,\"components\":[]}";

  private static String artifactsBase() {
    String maven =
        System.getProperty(
            "qits.maven.repository.url", System.getenv().getOrDefault("QITS_MAVEN_REPOSITORY_URL", ""));
    int marker = maven.indexOf("/artifacts/");
    return marker < 0 ? "" : maven.substring(0, marker) + "/artifacts";
  }

  @Test
  public void theCliThisReactorPinsRunsAComposedReleaseStepAndPublishesTheSbom(@TempDir Path work)
      throws Exception {
    assumeTrue(
        !ARTIFACTS_BASE.isBlank(),
        "no artifacts origin is configured (qits.maven.repository.url / QITS_MAVEN_REPOSITORY_URL)"
            + " — a clone with no platform to ask cannot run the pin test");

    // 1. THE PINNED BINARY, OUT OF THE REAL STORE. A non-200 here is the whole point of the test.
    byte[] binary = downloadPinnedBinary();

    // 2. The stand-in store: it serves those same bytes back on the daemons route the composed
    // prelude fetches from, and records what the CLI PUTs.
    StubStore store = new StubStore(binary);
    try {
      // 3. THE SCRIPT IS THE SHIPPED COMPOSER'S, never a copy. Composed, then parsed back through
      // the ordinary trigger parser — which is exactly the road a real run's document travels.
      String script = composedReleaseStepScript();
      assertTrue(
          script.contains("qits artifacts publish sbom submit"),
          "the composed step carries the SBOM postlude:\n" + script);
      assertTrue(
          script.contains("$QITS_ARTIFACTS_CLI_VERSION"),
          "the composed prelude spends the injected version rather than resolving one:\n" + script);

      Path origin = gitOriginTaggedWithTheRelease(work.resolve("origin"));
      Path checkout = Files.createDirectories(work.resolve("checkout"));
      run(checkout, "git", "init", "-q");

      Path scriptFile = work.resolve("step.sh");
      Files.writeString(scriptFile, script, StandardCharsets.UTF_8);

      Result result = runStep(scriptFile, checkout, work, origin, store.base());

      assertEquals(
          0,
          result.exit(),
          "the composed release step failed.\n--- script ---\n" + script + "\n--- output ---\n"
              + result.output());

      // 4. WHAT THE FAR SIDE SAW. An exit code says the script did not stop; the PUT is the only
      // evidence that the pinned binary really published, at the coordinate the postlude declared,
      // carrying the bytes the step wrote.
      List<StubStore.Recorded> puts = store.puts();
      assertEquals(1, puts.size(), "exactly one SBOM submission: " + puts);
      StubStore.Recorded put = puts.get(0);
      assertEquals(
          "/artifacts/sboms/" + SBOM_TYPE + "/" + SBOM_NAME + "/-/" + RELEASE_VERSION, put.path());
      assertEquals("application/vnd.cyclonedx+json", put.contentType());
      // The trailing newline is the heredoc's, and it is asserted rather than trimmed away: what the
      // step wrote is what must arrive, byte for byte, and a publisher that normalised its input
      // would be publishing something nobody wrote.
      assertArrayEquals(
          (SBOM_BODY + "\n").getBytes(StandardCharsets.UTF_8),
          put.body(),
          "the document the step wrote is the document that was published");

      // 5. AND IT WAS THE PINNED BINARY THAT DID IT. The prelude asked for exactly the coordinate
      // the pom names, and what it ran is the byte-identical copy of what the real store served.
      assertEquals(
          List.of(
              "/artifacts/daemons/"
                  + PlatformAccessCliBinary.DAEMON_NAME
                  + "/"
                  + PlatformAccessCliBinary.VERSION),
          store.gets(),
          "the prelude downloaded the pinned coordinate and nothing else");
      assertTrue(
          result
              .output()
              .contains(
                  "qits-ci: fetched "
                      + PlatformAccessCliBinary.DAEMON_NAME
                      + " "
                      + PlatformAccessCliBinary.VERSION),
          "the prelude says which CLI it put on PATH:\n" + result.output());
    } finally {
      store.close();
    }
  }

  // --- the pinned binary ---------------------------------------------------------------------------

  /**
   * Fetches the pinned CLI out of qits-artifacts' {@code daemons} store.
   *
   * <p>A missing artifact is a <b>failure with a sentence</b>, never a skip. It means the pom pins a
   * version whose binary was never published — or that retention removed it — and either is the
   * exact class of defect this test exists to surface, one release earlier than every composed
   * release step on the platform failing at its {@code curl}.
   */
  private static byte[] downloadPinnedBinary() throws Exception {
    String url =
        ARTIFACTS_BASE
            + "/daemons/"
            + PlatformAccessCliBinary.DAEMON_NAME
            + "/"
            + PlatformAccessCliBinary.VERSION;
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
      HttpResponse<byte[]> answer =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (answer.statusCode() != 200) {
        fail(
            "the pinned qits CLI "
                + PlatformAccessCliBinary.DAEMON_NAME
                + " "
                + PlatformAccessCliBinary.VERSION
                + " is not in qits-artifacts ("
                + answer.statusCode()
                + " from "
                + url
                + "). The pom pins a version whose binary was never published or no longer exists.");
      }
      return answer.body();
    }
  }

  // --- the composed step ---------------------------------------------------------------------------

  /**
   * One real release-phase step script, composed by the shipped composer and read back out of the
   * composed document through the ordinary trigger parser.
   *
   * <p>The step declares neither {@code build:} nor {@code docker:} on purpose: those arms demand
   * {@code $BUILDKIT_HOST} and write credential files, neither of which is anything to do with the
   * CLI pin. Its own script writes the document the postlude then submits, which is exactly the
   * arrangement a real release pipeline has — the build produces the SBOM, the platform submits it.
   */
  private static String composedReleaseStepScript() {
    String slots =
        "release:\n"
            + "  - image: qits/build-images/ci-base:latest\n"
            + "    script: |\n"
            + "      mkdir -p out\n"
            + "      cat > "
            + SBOM_PATH
            + " <<'SBOM'\n"
            + "      "
            + SBOM_BODY
            + "\n"
            + "      SBOM\n"
            + "artifacts:\n"
            + "  - { type: "
            + SBOM_TYPE
            + ", name: "
            + SBOM_NAME
            + ", sbom: "
            + SBOM_PATH
            + " }\n";

    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("7f1d6c2a-0000-4000-8000-000000000001", "qits", "qits-ci-service"),
            new CiReleaseSlotParser().parse(CiReleaseSlotParser.CONFIG_PATH, slots),
            null);

    return new CiEventTriggerParser()
        .parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseDocument())
        .pipeline()
        .steps()
        .get(0)
        .script();
  }

  // --- running it the way a step container would ----------------------------------------------------

  private record Result(int exit, String output) {}

  /**
   * Runs the composed script under {@code bash}, in a scratch checkout, with the environment a
   * release-phase step gets and nothing else.
   *
   * <p><b>Every ambient {@code QITS_} variable is removed first, and that is the difference between
   * a test and a coincidence.</b> {@code ProcessBuilder} seeds the child from this process's
   * environment, and this process runs somewhere that has opinions: a workspace container carries a
   * commissioned credential and a full set of platform addresses, a CI step container carries
   * another. A pin test whose result depends on where it runs proves nothing about the pin — so the
   * child is handed exactly the five variables the composed text reads, and a sixth arriving from
   * the host would be a failure this test could not see.
   *
   * <p>{@code GIT_CONFIG_GLOBAL} points at a scratch file for the same reason: the running user's
   * own git configuration is not part of what a step container has.
   */
  private static Result runStep(Path script, Path checkout, Path work, Path origin, String storeBase)
      throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder("bash", script.toAbsolutePath().toString()).directory(checkout.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(key -> key.startsWith("QITS_"));
    env.put("QITS_ARTIFACTS_URL", storeBase);
    env.put("QITS_ARTIFACTS_CLI_PACKAGE", PlatformAccessCliBinary.DAEMON_NAME);
    env.put("QITS_ARTIFACTS_CLI_VERSION", PlatformAccessCliBinary.VERSION);
    env.put("QITS_VERSION", RELEASE_VERSION);
    env.put("QITS_CI_REPOSITORY_URL", origin.toAbsolutePath().toString());
    env.put("GIT_CONFIG_GLOBAL", work.resolve("gitconfig").toAbsolutePath().toString());
    env.put("HOME", work.toAbsolutePath().toString());

    Path log = work.resolve("step.log");
    Process step =
        builder
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
            .start();
    if (!step.waitFor(5, TimeUnit.MINUTES)) {
      step.destroyForcibly();
      fail("the composed release step did not finish within 5 minutes:\n" + read(log));
    }
    return new Result(step.exitValue(), read(log));
  }

  /**
   * A bare-ish origin holding one commit tagged with the release version, which is what the
   * prelude's {@code git fetch refs/tags/$QITS_VERSION} and {@code git checkout --detach} need to
   * find. An ordinary working repository rather than a bare one: a local path fetch reads either,
   * and this one is one command shorter.
   */
  private static Path gitOriginTaggedWithTheRelease(Path origin) throws Exception {
    Files.createDirectories(origin);
    run(origin, "git", "init", "-q");
    run(origin, "git", "config", "user.email", "pin-it@qits.invalid");
    run(origin, "git", "config", "user.name", "qits-ci pin test");
    Files.writeString(origin.resolve("README.md"), "the tag the release step checks out\n");
    run(origin, "git", "add", "README.md");
    run(origin, "git", "commit", "-q", "-m", "release(" + RELEASE_VERSION + "): the fixture");
    run(origin, "git", "tag", RELEASE_VERSION);
    return origin;
  }

  private static void run(Path cwd, String... argv) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile());
    builder.environment().put("GIT_CONFIG_GLOBAL", cwd.resolve(".gitconfig-scratch").toString());
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process process = builder.redirectErrorStream(true).start();
    String output;
    try (InputStream out = process.getInputStream()) {
      output = new String(out.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (!process.waitFor(2, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      fail("`" + String.join(" ", argv) + "` hung in " + cwd);
    }
    assertEquals(0, process.exitValue(), String.join(" ", argv) + " in " + cwd + ":\n" + output);
  }

  private static String read(Path log) {
    try {
      return Files.exists(log) ? Files.readString(log) : "(nothing was written)";
    } catch (IOException e) {
      return "(the step's log could not be read: " + e + ")";
    }
  }

  // --- the stand-in store ---------------------------------------------------------------------------

  /**
   * qits-artifacts, as much of it as a release-phase step touches: the daemons route the prelude
   * downloads from, and the sboms route the postlude PUTs to.
   *
   * <p>It serves the <b>real</b> bytes on the daemons route — the ones the pinned coordinate really
   * answered with — so what runs here is the released binary and not a fixture. What it fakes is
   * only where the SBOM lands, because the platform's sbom store is immutable and a test must not
   * write into it at a coordinate nobody released.
   *
   * <p>The whole surface is a PUT and a GET, which is what {@code Publisher.sbomSubmit} really
   * asks: one PUT of the document, 201 with {@code sizeBytes} and {@code digest}, and no probe
   * before it. A 200 arm would put the CLI on its digest-comparison path — that is the store's
   * already-published rule and this test is about neither.
   */
  private static final class StubStore implements AutoCloseable {

    private final HttpServer server;
    private final List<Recorded> puts = new ArrayList<>();
    private final List<String> gets = new ArrayList<>();

    record Recorded(String path, String contentType, byte[] body) {
      @Override
      public String toString() {
        return path + " (" + contentType + ", " + body.length + " bytes)";
      }
    }

    StubStore(byte[] binary) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/artifacts/daemons/",
          exchange -> {
            synchronized (gets) {
              gets.add(exchange.getRequestURI().getPath());
            }
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, binary.length);
            try (var body = exchange.getResponseBody()) {
              body.write(binary);
            }
          });
      server.createContext("/artifacts/sboms/", this::sbom);
      server.start();
    }

    private void sbom(HttpExchange exchange) throws IOException {
      byte[] body = exchange.getRequestBody().readAllBytes();
      if (!"PUT".equals(exchange.getRequestMethod())) {
        // Deliberately a 405 rather than a 404: the CLI makes no probe today, and a stub that
        // answered a hypothetical one with "absent" would be inventing a contract.
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
        return;
      }
      synchronized (puts) {
        puts.add(
            new Recorded(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body));
      }
      byte[] receipt =
          ("{\"sizeBytes\":" + body.length + ",\"digest\":\"sha256:" + sha256(body) + "\"}")
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(201, receipt.length);
      try (var out = exchange.getResponseBody()) {
        out.write(receipt);
      }
    }

    String base() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Recorded> puts() {
      synchronized (puts) {
        return List.copyOf(puts);
      }
    }

    List<String> gets() {
      synchronized (gets) {
        return List.copyOf(gets);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private static String sha256(byte[] bytes) {
      try {
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
          hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

}
