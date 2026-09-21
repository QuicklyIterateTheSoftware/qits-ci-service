package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The REAL {@link CiDaemonLauncher#BOOTSTRAP}, run through {@code /bin/sh} against a real token
 * endpoint: the publish credential a step gets, proved by minting one rather than by reading the
 * text that would.
 *
 * <p><b>Why a process and not only assertions on the string.</b> What this ticket is about is that a
 * step can authenticate to the artifacts store at the moment it publishes, and every part of that is
 * shell: the exchange, the parse of {@code access_token} out of the answer, the export across the
 * {@code exec} into the daemon, and the non-zero exit when there is nothing to hand back. A
 * substring assertion can say the lines are present and nothing about whether they work.
 *
 * <p><b>Four {@code /tmp} literals are rewritten and that is the only edit made to the shipped
 * text</b>, for {@link CiDaemonBootstrapFetchTest}'s measured reason: this suite runs inside a
 * qits-ci step container, where {@code /tmp/qits-ci-daemon} <em>is</em> the running daemon and
 * {@code /tmp/qits-publish-token} is the credential the step's own release phase will use. Writing
 * either would be sabotaging the build that is running the test. The occurrence counts are asserted
 * before each replace, so moving a path or adding a use fails here loudly instead of quietly
 * exercising a text that no longer ships.
 *
 * <p>Plain JUnit, no Quarkus and no containers: the subject is a string, a shell and a socket.
 */
public class CiDaemonBootstrapPublishTokenTest {

  private static final String DAEMON_PATH = "/tmp/qits-ci-daemon";
  private static final int DAEMON_PATH_USES = 4;
  private static final String TOKEN_SCRIPT_PATH = "/tmp/qits-publish-token";
  private static final int TOKEN_SCRIPT_PATH_USES = 5;
  private static final String GIT_HELPER_PATH = "/tmp/qits-git-credential";
  private static final int GIT_HELPER_PATH_USES = 3;
  /** Not this test's subject, but written under the same guard — so it moves out of /tmp too. */
  private static final String SETTINGS_PATH = CiDaemonLauncher.DEPLOY_SETTINGS_FILE;

  private static final int SETTINGS_PATH_USES = 2;

  private static final String CLIENT_ID = "run-client-1";
  private static final String CLIENT_SECRET = "run-s3cr3t-1";

  private HttpServer server;
  private Path work;

  /** What the token endpoint received, so the exchange itself is asserted and not assumed. */
  private final List<String> bodies = new ArrayList<>();

  private final List<String> authorizations = new ArrayList<>();

  /** Flipped by the failure case: the endpoint then answers 500 and mints nothing. */
  private volatile boolean refuse;

  @BeforeEach
  void start() throws IOException {
    work = Files.createTempDirectory("ci-bootstrap-publish-token");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] body =
              (refuse
                      ? "{\"error\":\"server_error\"}"
                      : "{\"token_type\":\"Bearer\",\"expires_in\":3600,"
                          + "\"access_token\":\"tok-123\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(refuse ? 500 : 200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    // The daemon the bootstrap execs: a stub that records the environment it inherited. That is the
    // whole of what "exported into the environment the step script inherits" can be measured as on
    // this side — StepProcess never mutates it, so what PID 1 holds is what a step holds.
    server.createContext(
        "/qits-ci-daemon",
        exchange -> {
          byte[] body =
              ("#!/bin/sh\nprintenv QITS_PUBLISH_TOKEN > "
                      + work.resolve("inherited")
                      + " || true\nprintenv QITS_PUBLISH_TOKEN_COMMAND >> "
                      + work.resolve("inherited")
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
  public void aCommissionedStepInheritsAFreshlyMintedPublishToken() throws Exception {
    assumeShell();

    Result result = runBootstrap();

    assertEquals(0, result.exitCode, result.diagnosis());
    // The token, across the exec: the daemon — and therefore every step script under it — reads it
    // out of its own environment.
    assertEquals(
        "tok-123\n" + TOKEN_SCRIPT_PATH + "\n",
        Files.readString(work.resolve("inherited")).replace(scriptPath().toString(), TOKEN_SCRIPT_PATH),
        result.diagnosis());
    // And the exchange really was the commissioned client's, for the platform audience.
    assertEquals(
        List.of("grant_type=client_credentials&audience=qits-platform"), bodies, result.diagnosis());
    assertEquals(
        List.of(
            "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8))),
        authorizations);
    // Never in the log: the container's own output is a run's transcript, and a bearer in it is a
    // credential in a place people read and machines store.
    assertFalse(result.stdout.contains("tok-123"), result.diagnosis());
    assertFalse(result.stderr.contains("tok-123"), result.diagnosis());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  public void theCommandMintsAgainAndPrintsTheRawTokenAndNothingElse() throws Exception {
    assumeShell();

    runBootstrap();
    bodies.clear();
    authorizations.clear();

    // What a recipe runs when its step has been going for an hour: a second exchange, a second
    // token, and stdout that can be pasted into an Authorization header unedited — no `Bearer `
    // prefix, no whitespace but the single trailing newline.
    Process process = tokenCommand().start();
    process.getOutputStream().close();
    String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the token command never exited");

    assertEquals(0, process.exitValue());
    assertEquals("tok-123\n", printed);
    assertEquals(1, bodies.size(), "the command mints on every invocation");
    // 0700: the credential-minting script is the run's, not the image's other users'.
    assertEquals("rwx------", posixMode(scriptPath()));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  public void aMintThatFailsIsLoudInTheCommandAndSurvivableAtBootstrap() throws Exception {
    assumeShell();
    refuse = true;

    Result result = runBootstrap();

    // The daemon still started, so the step can report properly — the whole reason the bootstrap
    // does not treat this as fatal — and the variable is simply absent.
    assertEquals(0, result.exitCode, result.diagnosis());
    assertEquals(
        TOKEN_SCRIPT_PATH + "\n",
        Files.readString(work.resolve("inherited")).replace(scriptPath().toString(), TOKEN_SCRIPT_PATH),
        result.diagnosis());
    assertTrue(result.stderr.contains("no publish token at container start"), result.diagnosis());

    // And the command itself refuses rather than printing nothing successfully: a publish that
    // silently loses its credential is exactly what this must not do.
    Process process = tokenCommand().start();
    process.getOutputStream().close();
    String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String complaint = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the token command never exited");

    assertNotEquals(0, process.exitValue(), "a failed mint must be a failed command");
    assertEquals("", printed);
    assertFalse(complaint.isBlank(), "it must say why on stderr");
  }

  /** The shipped text with its four {@code /tmp} literals moved under this test's own directory. */
  private String bootstrapUnderTemp() {
    String shipped = CiDaemonLauncher.BOOTSTRAP;
    assertEquals(DAEMON_PATH_USES, occurrences(shipped, DAEMON_PATH), DAEMON_PATH + " moved");
    assertEquals(
        TOKEN_SCRIPT_PATH_USES, occurrences(shipped, TOKEN_SCRIPT_PATH), TOKEN_SCRIPT_PATH + " moved");
    assertEquals(
        GIT_HELPER_PATH_USES, occurrences(shipped, GIT_HELPER_PATH), GIT_HELPER_PATH + " moved");
    assertEquals(SETTINGS_PATH_USES, occurrences(shipped, SETTINGS_PATH), SETTINGS_PATH + " moved");
    return shipped
        .replace(SETTINGS_PATH, work.resolve("qits-deploy-settings.xml").toString())
        .replace(TOKEN_SCRIPT_PATH, scriptPath().toString())
        .replace(GIT_HELPER_PATH, work.resolve("qits-git-credential").toString())
        .replace(DAEMON_PATH, work.resolve("qits-ci-daemon").toString());
  }

  private Path scriptPath() {
    return work.resolve("qits-publish-token");
  }

  /**
   * The written script, invoked the way a step's recipe invokes it — and with <b>every ambient
   * {@code QITS_} variable stripped first</b>. This suite runs inside a qits-ci step container,
   * whose environment already carries a commissioned pair and a real token endpoint, so a child
   * process inheriting it would mint against the live idp and pass against a script that does
   * nothing. {@code WorkspaceDaemonPinIT}'s rule: a test whose result depends on where it runs
   * proves nothing.
   */
  private ProcessBuilder tokenCommand() {
    ProcessBuilder builder = new ProcessBuilder(scriptPath().toString());
    return stripped(builder);
  }

  private Result runBootstrap() throws Exception {
    Path out = work.resolve("stdout");
    Path err = work.resolve("stderr");
    ProcessBuilder builder = stripped(new ProcessBuilder("/bin/sh", "-c", bootstrapUnderTemp()));
    builder.redirectOutput(out.toFile());
    builder.redirectError(err.toFile());
    Map<String, String> env = builder.environment();
    env.put(
        "QITS_CI_DAEMON_BINARY_URL",
        "http://127.0.0.1:" + server.getAddress().getPort() + "/qits-ci-daemon");
    env.put("QITS_COMMISSIONED_CLIENT_ID", CLIENT_ID);
    env.put("QITS_COMMISSIONED_CLIENT_SECRET", CLIENT_SECRET);
    env.put("QITS_GIT_AUTH_TOKEN_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    env.put("QITS_GIT_AUTH_AUDIENCE", CiDaemonLauncher.CONTAINER_GIT_AUDIENCE);
    env.put("QITS_GIT_AUTH_HOST", "qits-githost:8080");
    env.put("QITS_PUBLISH_TOKEN_COMMAND", TOKEN_SCRIPT_PATH);
    env.put("GIT_CONFIG_GLOBAL", work.resolve("gitconfig").toString());
    // The registry block is another test's subject; emptied, it does nothing.
    env.remove("QITS_CI_REGISTRY_AUTH_CONFIG");
    env.remove("DOCKER_CONFIG");

    Process process = builder.start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("the bootstrap never exited");
    }
    return new Result(process.exitValue(), read(out), read(err));
  }

  /** Every ambient {@code QITS_} name gone, and the four the exchange reads set to this fixture's. */
  private ProcessBuilder stripped(ProcessBuilder builder) {
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(name -> name.startsWith("QITS_"));
    env.remove("DOCKER_CONFIG");
    env.remove("GIT_CONFIG_GLOBAL");
    env.put("QITS_COMMISSIONED_CLIENT_ID", CLIENT_ID);
    env.put("QITS_COMMISSIONED_CLIENT_SECRET", CLIENT_SECRET);
    env.put("QITS_GIT_AUTH_TOKEN_URL", tokenUrl());
    env.put("QITS_GIT_AUTH_AUDIENCE", CiDaemonLauncher.CONTAINER_GIT_AUDIENCE);
    return builder;
  }

  private String tokenUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
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

  private static String posixMode(Path file) throws IOException {
    return java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
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

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
      count++;
    }
    return count;
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file) : "";
  }
}
