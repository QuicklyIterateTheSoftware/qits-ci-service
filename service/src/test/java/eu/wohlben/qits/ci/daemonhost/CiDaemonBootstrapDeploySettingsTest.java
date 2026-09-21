package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.w3c.dom.Document;

/**
 * The REAL {@link CiDaemonLauncher#BOOTSTRAP}, run through {@code /bin/sh}: the maven deploy
 * credential a step gets ambiently, proved by writing the settings file and reading it back rather
 * than by asserting on the text that would.
 *
 * <p><b>What is under test.</b> The deployment repository id is {@code qits} — what {@code
 * -DaltDeploymentRepository="qits::default::…"} names — and no {@code .qits-maven-settings.xml} on
 * this estate declares a {@code <server>} with it. Maven does not authenticate preemptively, so the
 * credential has to be an {@code Authorization} header on a {@code <server id=qits>}; writing that
 * here, and appending {@code -gs} to {@code MAVEN_ARGS}, is what makes every {@code mvn deploy} in
 * every step present the run's bearer with no recipe changing at all.
 *
 * <p><b>Why a process and not only assertions on the string.</b> Every part of it is shell: the
 * {@code umask} subshell that makes the file 0600, the substitution of the minted token into the
 * header, the append that preserves a {@code MAVEN_ARGS} the step image already set, the export
 * across the {@code exec}, and the arm that writes nothing when the mint failed. A substring
 * assertion says the lines are present and nothing about whether they work.
 *
 * <p><b>The XML is parsed, not merely grepped.</b> A malformed settings file would fail every maven
 * run in every step on the estate — that is the blast radius, so being well-formed is asserted by a
 * parser and not by the eye.
 *
 * <p><b>The {@code /tmp} literals are rewritten and that is the only edit made to the shipped
 * text</b>, for {@link CiDaemonBootstrapFetchTest}'s measured reason: this suite runs inside a
 * qits-ci step container, where {@code /tmp/qits-ci-daemon} <em>is</em> the running daemon and the
 * other three paths are that step's own credentials. Occurrence counts are asserted before each
 * replace, so moving a path or adding a use fails here loudly.
 *
 * <p>Plain JUnit, no Quarkus and no containers: the subject is a string, a shell and a socket.
 */
public class CiDaemonBootstrapDeploySettingsTest {

  private static final String DAEMON_PATH = "/tmp/qits-ci-daemon";
  private static final int DAEMON_PATH_USES = 4;
  private static final String TOKEN_SCRIPT_PATH = "/tmp/qits-publish-token";
  private static final int TOKEN_SCRIPT_PATH_USES = 5;
  private static final String GIT_HELPER_PATH = "/tmp/qits-git-credential";
  private static final int GIT_HELPER_PATH_USES = 3;
  private static final String SETTINGS_PATH = CiDaemonLauncher.DEPLOY_SETTINGS_FILE;
  private static final int SETTINGS_PATH_USES = 2;

  private static final String CLIENT_ID = "run-client-1";
  private static final String CLIENT_SECRET = "run-s3cr3t-1";
  private static final String TOKEN = "tok-deploy-123";

  /** What a step image may already have put there, and which this must never clobber. */
  private static final String PRE_EXISTING_MAVEN_ARGS = "-Dstyle.color=never -Dfoo=bar";

  private HttpServer server;
  private Path work;

  /** Flipped by the failure case: the endpoint then answers 500 and mints nothing. */
  private volatile boolean refuse;

  @BeforeEach
  void start() throws IOException {
    work = Files.createTempDirectory("ci-bootstrap-deploy-settings");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              (refuse
                      ? "{\"error\":\"server_error\"}"
                      : "{\"token_type\":\"Bearer\",\"expires_in\":3600,"
                          + "\"access_token\":\""
                          + TOKEN
                          + "\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(refuse ? 500 : 200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    // The daemon the bootstrap execs: a stub that records MAVEN_ARGS as PID 1 holds it. That is
    // what a step script inherits — StepProcess never mutates the environment.
    server.createContext(
        "/qits-ci-daemon",
        exchange -> {
          byte[] body =
              ("#!/bin/sh\nprintenv MAVEN_ARGS > " + work.resolve("maven-args") + " || true\nexit 0\n")
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
  public void aCommissionedStepDeploysWithTheRunsBearerAndNoRecipeChange() throws Exception {
    assumeShell();

    Result result = runBootstrap();

    assertEquals(0, result.exitCode, result.diagnosis());

    // 0600: the deploy credential is the run's, not the image's other users'.
    assertEquals("rw-------", posixMode(settingsPath()), result.diagnosis());

    String settings = Files.readString(settingsPath());
    // The server maven will match against -DaltDeploymentRepository="qits::default::…", carrying
    // the bearer as a header because maven would never send a password to a repository that has
    // not asked for one.
    assertTrue(settings.contains("<id>qits</id>"), settings);
    assertTrue(settings.contains("<name>Authorization</name>"), settings);
    assertTrue(settings.contains("<value>Bearer " + TOKEN + "</value>"), settings);
    // And the blocker, re-declared: -gs REPLACES maven's conf/settings.xml, whose only live
    // element this is. Losing it would unblock plain-http external repositories estate-wide.
    assertTrue(settings.contains("<id>maven-default-http-blocker</id>"), settings);
    assertTrue(settings.contains("<mirrorOf>external:http:*</mirrorOf>"), settings);
    assertTrue(settings.contains("<url>http://0.0.0.0/</url>"), settings);
    assertTrue(settings.contains("<blocked>true</blocked>"), settings);

    // MAVEN_ARGS across the exec — APPENDED, so a step image that already set it still builds.
    assertEquals(
        PRE_EXISTING_MAVEN_ARGS + " -gs " + SETTINGS_PATH,
        inheritedMavenArgs(),
        result.diagnosis());

    // Never in the log: a run's transcript is read by people and stored by machines.
    assertFalse(result.stdout.contains(TOKEN), result.diagnosis());
    assertFalse(result.stderr.contains(TOKEN), result.diagnosis());
    assertFalse(result.stdout.contains(CLIENT_SECRET), result.diagnosis());
    assertFalse(result.stderr.contains(CLIENT_SECRET), result.diagnosis());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  public void theGeneratedSettingsFileIsWellFormedXml() throws Exception {
    assumeShell();

    Result result = runBootstrap();
    assertEquals(0, result.exitCode, result.diagnosis());

    // The blast radius: a malformed global settings file fails EVERY maven run in EVERY step, not
    // only the deploy. So it is parsed rather than grepped.
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document =
        builder.parse(
            new ByteArrayInputStream(Files.readAllBytes(settingsPath())));

    assertEquals("settings", document.getDocumentElement().getLocalName());
    assertEquals(1, document.getElementsByTagName("servers").getLength());
    assertEquals(1, document.getElementsByTagName("mirrors").getLength());
    assertEquals(1, document.getElementsByTagName("server").getLength());
    assertEquals(1, document.getElementsByTagName("mirror").getLength());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  public void aMintThatFailsWritesNoFileAndLeavesMavenArgsAlone() throws Exception {
    assumeShell();
    refuse = true;

    Result result = runBootstrap();

    // The daemon still started, so the step can report properly.
    assertEquals(0, result.exitCode, result.diagnosis());
    // No file at all, rather than one carrying an empty `Bearer `: a deploy then runs exactly as
    // it does today instead of being broken by this injection.
    assertFalse(Files.exists(settingsPath()), "a failed mint must write no settings file");
    assertEquals(PRE_EXISTING_MAVEN_ARGS, inheritedMavenArgs(), result.diagnosis());
    assertTrue(result.stderr.contains("no publish token at container start"), result.diagnosis());
  }

  private String inheritedMavenArgs() throws IOException {
    return read(work.resolve("maven-args")).strip().replace(settingsPath().toString(), SETTINGS_PATH);
  }

  private Path settingsPath() {
    return work.resolve("qits-deploy-settings.xml");
  }

  /** The shipped text with its {@code /tmp} literals moved under this test's own directory. */
  private String bootstrapUnderTemp() {
    String shipped = CiDaemonLauncher.BOOTSTRAP;
    assertEquals(DAEMON_PATH_USES, occurrences(shipped, DAEMON_PATH), DAEMON_PATH + " moved");
    assertEquals(
        TOKEN_SCRIPT_PATH_USES,
        occurrences(shipped, TOKEN_SCRIPT_PATH),
        TOKEN_SCRIPT_PATH + " moved");
    assertEquals(
        GIT_HELPER_PATH_USES, occurrences(shipped, GIT_HELPER_PATH), GIT_HELPER_PATH + " moved");
    assertEquals(SETTINGS_PATH_USES, occurrences(shipped, SETTINGS_PATH), SETTINGS_PATH + " moved");
    return shipped
        .replace(SETTINGS_PATH, settingsPath().toString())
        .replace(TOKEN_SCRIPT_PATH, work.resolve("qits-publish-token").toString())
        .replace(GIT_HELPER_PATH, work.resolve("qits-git-credential").toString())
        .replace(DAEMON_PATH, work.resolve("qits-ci-daemon").toString());
  }

  private Result runBootstrap() throws Exception {
    Path out = work.resolve("stdout");
    Path err = work.resolve("stderr");
    ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", bootstrapUnderTemp());
    builder.redirectOutput(out.toFile());
    builder.redirectError(err.toFile());
    Map<String, String> env = builder.environment();
    // Every ambient QITS_ name gone first. This suite runs inside a qits-ci step container, whose
    // environment carries a real commissioned pair and a real token endpoint, so a child that
    // inherited it would mint against the LIVE idp and pass against a text that does nothing.
    env.keySet().removeIf(name -> name.startsWith("QITS_"));
    env.remove("QITS_CI_REGISTRY_AUTH_CONFIG");
    env.remove("DOCKER_CONFIG");
    env.put(
        "QITS_CI_DAEMON_BINARY_URL",
        "http://127.0.0.1:" + server.getAddress().getPort() + "/qits-ci-daemon");
    env.put("QITS_COMMISSIONED_CLIENT_ID", CLIENT_ID);
    env.put("QITS_COMMISSIONED_CLIENT_SECRET", CLIENT_SECRET);
    env.put(
        "QITS_GIT_AUTH_TOKEN_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    env.put("QITS_GIT_AUTH_AUDIENCE", CiDaemonLauncher.CONTAINER_GIT_AUDIENCE);
    env.put("QITS_GIT_AUTH_HOST", "qits-githost:8080");
    env.put("GIT_CONFIG_GLOBAL", work.resolve("gitconfig").toString());
    env.put("MAVEN_ARGS", PRE_EXISTING_MAVEN_ARGS);

    Process process = builder.start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("the bootstrap never exited");
    }
    return new Result(process.exitValue(), read(out), read(err));
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
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
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
