package eu.wohlben.qits.ci.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.ci.HermeticEnvironment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The REAL {@link StepContainerSettings#BOOTSTRAP}, run through {@code /bin/sh} with the environment
 * {@link StepWorkloadSpecs} composes for a build step: what a step makes of its {@code ci-run}
 * token — its only credential (qits-515) — proved by reading back the five files it writes and the
 * download it makes rather than by grepping the text that would.
 *
 * <p><b>The environment is the composition's, not a hand-written copy.</b> The step is composed
 * for {@code example.org} with the run's token, and only the addresses this suite
 * has to serve itself (the daemon binary) and the paths it has to keep out of {@code /tmp} are
 * replaced — so a key the composition stops sending, or a value it spells differently, fails here.
 *
 * <p>The {@code /tmp} literals are moved under this test's own directory for {@link
 * CiDaemonBootstrapFetchTest}'s measured reason (this suite runs inside a qits-ci step container,
 * whose {@code /tmp/qits-ci-daemon} is the running daemon), and every ambient {@code QITS_}
 * variable is stripped first, so nothing here depends on where it runs.
 */
public class CiDaemonBootstrapTokenTest {

  private static final String TOKEN = StepFixtures.TOKEN;

  private static final String DAEMON_PATH = "/tmp/qits-ci-daemon";
  private static final String TOKEN_SCRIPT_PATH = StepContainerSettings.PUBLISH_TOKEN_COMMAND;
  private static final String GIT_HELPER_PATH = "/tmp/qits-git-credential";
  private static final String SETTINGS_PATH = StepContainerSettings.DEPLOY_SETTINGS_FILE;

  private HttpServer server;
  private Path work;

  /** The Authorization header each download of the daemon binary carried. */
  private final List<String> downloadAuthorizations = new ArrayList<>();

  @BeforeEach
  void start() throws IOException {
    work = Files.createTempDirectory("ci-bootstrap-edge-token");
    Files.createDirectories(work.resolve("home"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // The daemon: served only to the run's bearer, as the edge serves the registry vhost, and a
    // stub that records what PID 1 inherited.
    server.createContext(
        "/artifacts/daemons/qits-ci-daemon/",
        exchange -> {
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          downloadAuthorizations.add(String.valueOf(authorization));
          if (!("Bearer " + TOKEN).equals(authorization)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          byte[] body =
              ("#!/bin/sh\nprintenv QITS_PUBLISH_TOKEN > "
                      + work.resolve("inherited-token")
                      + " || true\nprintenv MAVEN_ARGS > "
                      + work.resolve("inherited-maven-args")
                      + " || true\nexit 0\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  public void aStepTurnsItsTokenIntoEveryCredentialItNeeds() throws Exception {
    assumeShell();

    Map<String, String> composed = composedEnv();
    Result result = runBootstrap(composed);

    assertEquals(0, result.exitCode, result.diagnosis());
    // The download carried the run's bearer — the registry vhost refuses an anonymous one.
    assertEquals(List.of("Bearer " + TOKEN), downloadAuthorizations, result.diagnosis());
    assertEquals(TOKEN + "\n", Files.readString(work.resolve("inherited-token")));

    // 1. The publish command prints the token itself, raw, one line; 0700.
    Process print = stripped(new ProcessBuilder(path(TOKEN_SCRIPT_PATH).toString()), composed).start();
    print.getOutputStream().close();
    String printed = new String(print.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(print.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, print.exitValue());
    assertEquals(TOKEN + "\n", printed);
    assertEquals("rwx------", posixMode(path(TOKEN_SCRIPT_PATH)));

    // 2. The git helper answers the clone host with oauth2/token, and nobody else; the global git
    // config names it.
    assertEquals(
        "[credential]\n\thelper = " + path(GIT_HELPER_PATH) + "\n",
        Files.readString(work.resolve("gitconfig")));
    assertEquals(
        "username=oauth2\npassword=" + TOKEN + "\n\n",
        gitCredential(composed, "https", "githost.qits.example.org"));
    assertEquals("", gitCredential(composed, "https", "evil.example.com"));
    assertEquals("", gitCredential(composed, "ssh", "githost.qits.example.org"));

    // 3. Maven: the bearer on every server id the estate's settings use, parsed, 0600, and -gs
    // appended to MAVEN_ARGS across the exec.
    assertEquals("rw-------", posixMode(path(SETTINGS_PATH)));
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    Document settings =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(Files.readAllBytes(path(SETTINGS_PATH))));
    NodeList servers = settings.getElementsByTagName("server");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < servers.getLength(); i++) {
      Element server = (Element) servers.item(i);
      ids.add(server.getElementsByTagName("id").item(0).getTextContent());
      assertEquals("Authorization", server.getElementsByTagName("name").item(0).getTextContent());
      assertEquals(
          "Bearer " + TOKEN, server.getElementsByTagName("value").item(0).getTextContent());
    }
    assertEquals(List.of("qits", "qits-maven-network", "qits-central-proxy"), ids);
    assertEquals(1, settings.getElementsByTagName("mirror").getLength(), "the blocker, re-declared");
    assertEquals(
        "-gs " + path(SETTINGS_PATH) + "\n",
        Files.readString(work.resolve("inherited-maven-args")));

    // 4. npm: one _authToken per npm host derived from $QITS_DOMAIN, appended to ~/.npmrc.
    assertEquals(
        "//registry.qits.example.org/:_authToken="
            + TOKEN
            + "\n//mirror.qits.example.org/:_authToken="
            + TOKEN
            + "\n",
        Files.readString(work.resolve("home").resolve(".npmrc")));

    // 5. docker: a login per public registry host, token:<value>, written from the composed doc.
    String auth =
        Base64.getEncoder().encodeToString(("token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "{\"auths\":{\"registry.qits.example.org\":{\"auth\":\""
            + auth
            + "\"},\"mirror.qits.example.org\":{\"auth\":\""
            + auth
            + "\"}}}",
        Files.readString(work.resolve("docker").resolve("config.json")));

    // Never in the log.
    assertFalse(result.stdout.contains(TOKEN), result.diagnosis());
    assertFalse(result.stderr.contains(TOKEN), result.diagnosis());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  public void anImageWhoseNpmrcExistsKeepsItAndGainsTheTokens() throws Exception {
    assumeShell();
    Files.writeString(work.resolve("home").resolve(".npmrc"), "fund=false\n");

    Result result = runBootstrap(composedEnv());

    assertEquals(0, result.exitCode, result.diagnosis());
    assertTrue(
        Files.readString(work.resolve("home").resolve(".npmrc")).startsWith("fund=false\n//registry"),
        "appended, never clobbered");
  }

  /**
   * MAVEN_ARGS is appended to, never assigned: a step image may already set it, and clobbering it
   * would break that image's builds.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void anImagesOwnMavenArgsSurviveAndGainTheSettingsFile() throws Exception {
    assumeShell();
    Map<String, String> composed = new java.util.LinkedHashMap<>(composedEnv());
    composed.put("MAVEN_ARGS", "-Dstyle.color=never -Dfoo=bar");

    Result result = runBootstrap(composed);

    assertEquals(0, result.exitCode, result.diagnosis());
    assertEquals(
        "-Dstyle.color=never -Dfoo=bar -gs " + path(SETTINGS_PATH) + "\n",
        Files.readString(work.resolve("inherited-maven-args")));
  }

  /**
   * qits-515: the text holds no second credential path. It names neither the commissioned pair nor
   * the git-auth variables, and a pair left in the environment changes nothing it writes.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void aCommissionedPairInTheEnvironmentIsReadByNothing() throws Exception {
    assumeShell();
    assertFalse(StepContainerSettings.BOOTSTRAP.contains("QITS_COMMISSIONED_CLIENT"));
    assertFalse(StepContainerSettings.BOOTSTRAP.contains("QITS_GIT_AUTH_"));
    assertFalse(StepContainerSettings.BOOTSTRAP.contains("grant_type"));
    Map<String, String> composed = new java.util.LinkedHashMap<>(composedEnv());
    composed.put("QITS_COMMISSIONED_CLIENT_ID", "a-client");
    composed.put("QITS_COMMISSIONED_CLIENT_SECRET", "a-secret");
    composed.put("QITS_GIT_AUTH_TOKEN_URL", "http://127.0.0.1:1/idp/token");
    composed.put("QITS_GIT_AUTH_HOST", "githost.qits.example.org");

    Result result = runBootstrap(composed);

    assertEquals(0, result.exitCode, result.diagnosis());
    assertEquals(TOKEN + "\n", Files.readString(work.resolve("inherited-token")));
    assertEquals(
        "username=oauth2\npassword=" + TOKEN + "\n\n",
        gitCredential(composed, "https", "githost.qits.example.org"));
  }

  /** The env the composition sends a {@code build: true} step holding its run's token. */
  private static Map<String, String> composedEnv() {
    StepContainerSettings launcher = StepFixtures.shippedLauncher();
    return StepWorkloadSpecs.compose(
            launcher.workloadSettings(),
            StepFixtures.plane(launcher),
            StepFixtures.sampleStep(1, false, true),
            StepFixtures.token(),
            null)
        .env();
  }

  private Result runBootstrap(Map<String, String> composed) throws Exception {
    Path out = work.resolve("stdout");
    Path err = work.resolve("stderr");
    ProcessBuilder builder =
        stripped(new ProcessBuilder("/bin/sh", "-c", bootstrapUnderTemp()), composed);
    builder.redirectOutput(out.toFile());
    builder.redirectError(err.toFile());
    Process process = builder.start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("the bootstrap never exited");
    }
    return new Result(process.exitValue(), read(out), read(err));
  }

  /**
   * Nothing ambient but PATH and HOME ({@link HermeticEnvironment}), then the composed env with
   * what this suite serves or keeps out of {@code /tmp} replaced: the daemon's origin (its path
   * kept), the docker, git-config, publish-command and home locations.
   */
  private ProcessBuilder stripped(ProcessBuilder builder, Map<String, String> composed) {
    Map<String, String> env = HermeticEnvironment.of(builder);
    env.putAll(composed);
    env.put(
        "QITS_CI_DAEMON_BINARY_URL",
        StepAddressPlane.rebase(
            composed.get("QITS_CI_DAEMON_BINARY_URL"),
            "http://127.0.0.1:" + server.getAddress().getPort()));
    env.put("DOCKER_CONFIG", work.resolve("docker").toString());
    env.put("GIT_CONFIG_GLOBAL", work.resolve("gitconfig").toString());
    env.put("QITS_PUBLISH_TOKEN_COMMAND", path(TOKEN_SCRIPT_PATH).toString());
    env.put("HOME", work.resolve("home").toString());
    return builder;
  }

  private String gitCredential(Map<String, String> composed, String protocol, String host)
      throws Exception {
    Process helper =
        stripped(new ProcessBuilder(path(GIT_HELPER_PATH).toString(), "get"), composed).start();
    helper
        .getOutputStream()
        .write(("protocol=" + protocol + "\nhost=" + host + "\n\n").getBytes(StandardCharsets.UTF_8));
    helper.getOutputStream().close();
    String answer = new String(helper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(helper.waitFor(30, TimeUnit.SECONDS), "the git helper never exited");
    assertEquals(0, helper.exitValue(), "a git helper never fails the fetch it was asked about");
    return answer;
  }

  /** The shipped text with its four {@code /tmp} literals moved under this test's directory. */
  private String bootstrapUnderTemp() {
    String shipped = StepContainerSettings.BOOTSTRAP;
    for (String literal : List.of(DAEMON_PATH, TOKEN_SCRIPT_PATH, GIT_HELPER_PATH, SETTINGS_PATH)) {
      assertTrue(shipped.contains(literal), literal + " moved");
    }
    return shipped
        .replace(SETTINGS_PATH, path(SETTINGS_PATH).toString())
        .replace(TOKEN_SCRIPT_PATH, path(TOKEN_SCRIPT_PATH).toString())
        .replace(GIT_HELPER_PATH, path(GIT_HELPER_PATH).toString())
        .replace(DAEMON_PATH, path(DAEMON_PATH).toString());
  }

  private Path path(String tmpLiteral) {
    return work.resolve(tmpLiteral.substring("/tmp/".length()));
  }

  private record Result(int exitCode, String stdout, String stderr) {
    String diagnosis() {
      return "exit " + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
    }
  }

  private static void assumeShell() {
    assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "/bin/sh is required for this test");
    assumeTrue(
        onPath("wget") || onPath("curl"),
        "one of wget/curl must be on PATH — the image contract this text probes for");
  }

  private static boolean onPath(String program) {
    String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    for (String each : path.split(":")) {
      if (!each.isEmpty() && Files.isExecutable(Path.of(each, program))) {
        return true;
      }
    }
    return false;
  }

  private static String posixMode(Path file) throws IOException {
    return java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file) : "";
  }
}
