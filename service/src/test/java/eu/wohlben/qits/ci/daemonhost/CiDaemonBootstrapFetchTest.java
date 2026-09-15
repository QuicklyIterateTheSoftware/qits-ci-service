package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The REAL {@link CiDaemonLauncher#BOOTSTRAP}, run through {@code /bin/sh} against a server that is
 * not listening yet — the one failure the retry exists for.
 *
 * <p><b>Why this is not a case in {@code CiDaemonHandshakeIT}.</b> That IT's never-registers case
 * points the bootstrap at a url that 404s forever, which proves the give-up arm and nothing about
 * recovery: no number of attempts would have fetched it. The incident of 2026-09-15 01:05 UTC was
 * the opposite shape — qits-artifacts deploys {@code update_order: stop-first}, a step container
 * landed 7 seconds into that refusal window, got {@code Connection refused}, and a gating run went
 * red with {@code NEVER_STARTED} over a blip that was over a minute later. So this test refuses the
 * first connection and then serves, and the thing it asserts is that the shell recovered.
 *
 * <p>Plain JUnit, no Quarkus and no docker: the subject is a string and a shell, and both are on any
 * Linux build host. It is guarded rather than assumed — a host with no {@code /bin/sh} or no
 * downloader on {@code PATH} skips, because neither says anything about the bootstrap.
 *
 * <p><b>It writes {@code /tmp/qits-ci-daemon}</b>, because that path is a literal in the text and
 * the text honours no {@code TMPDIR}. Nothing here asserts on that file beyond running it, and
 * nothing else in this repo reads it.
 */
public class CiDaemonBootstrapFetchTest {

  /** {@code attempt} caps at this, {@code sleep} is this long — both typed into BOOTSTRAP. */
  private static final int ATTEMPTS = 10;

  private static final int PAUSE_SECONDS = 12;

  /** What the bootstrap can spend retrying a connection that is REFUSED, which fails at once. */
  private static final long REFUSED_BUDGET_SECONDS = (long) (ATTEMPTS - 1) * PAUSE_SECONDS;

  @Test
  @EnabledOnOs(OS.LINUX)
  public void aFetchRefusedOnceIsRetriedAndTheDaemonStillStarts() throws Exception {
    assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "/bin/sh is required for this test");
    assumeTrue(
        onPath("wget") || onPath("curl"),
        "one of wget/curl must be on PATH — the image contract this text probes for");

    int port = aPortNobodyIsListeningOn();
    Path work = Files.createTempDirectory("ci-bootstrap-fetch");
    Path out = work.resolve("stdout");
    Path err = work.resolve("stderr");

    // Bound about a second in, so attempt 1 is refused and attempt 2 (at ~12s) succeeds. A thread
    // rather than a scheduler: there is exactly one thing to do and one place it can fail.
    HttpServer[] server = new HttpServer[1];
    Thread binder =
        new Thread(
            () -> {
              try {
                Thread.sleep(1000);
                HttpServer started = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                started.createContext(
                    "/qits-ci-daemon",
                    exchange -> {
                      byte[] body = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);
                      exchange.sendResponseHeaders(200, body.length);
                      exchange.getResponseBody().write(body);
                      exchange.close();
                    });
                started.start();
                server[0] = started;
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (IOException e) {
                throw new IllegalStateException("the fixture could not bind " + port, e);
              }
            },
            "ci-bootstrap-fetch-fixture");
    binder.setDaemon(true);
    binder.start();

    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", CiDaemonLauncher.BOOTSTRAP);
      builder.redirectOutput(out.toFile());
      builder.redirectError(err.toFile());
      Map<String, String> env = builder.environment();
      env.put("QITS_CI_DAEMON_BINARY_URL", "http://127.0.0.1:" + port + "/qits-ci-daemon");
      // Everything the two conditional blocks read, emptied so both are skipped: this test is about
      // the fetch, and the credential-to-file mechanism is CiDaemonLauncherTest's subject.
      for (String unset :
          new String[] {
            "QITS_CI_REGISTRY_AUTH_CONFIG",
            "DOCKER_CONFIG",
            "QITS_COMMISSIONED_CLIENT_ID",
            "QITS_COMMISSIONED_CLIENT_SECRET",
            "GIT_CONFIG_GLOBAL"
          }) {
        env.remove(unset);
      }
      process = builder.start();

      // ~13 seconds by construction (one refusal, one 12s pause, then success). The wait is
      // generous and finite: a bootstrap that hangs must fail this test, not hang the build.
      if (!process.waitFor(90, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError(
            "the bootstrap never exited\nstdout:\n" + read(out) + "\nstderr:\n" + read(err));
      }
    } finally {
      binder.interrupt();
      if (server[0] != null) {
        server[0].stop(0);
      }
    }

    String stdout = read(out);
    String stderr = read(err);
    String both = "stdout:\n" + stdout + "\nstderr:\n" + stderr;

    // Exit 0 is the stub binary running: the text fetched it, chmod +x'd it and exec'd it.
    assertEquals(0, process.exitValue(), both);
    // And it really did fail first — without these two the test would pass against the one-attempt
    // text it exists to retire, as long as the fixture happened to be up.
    assertTrue(stderr.contains("could not fetch"), both);
    assertTrue(stderr.contains("retrying"), both);
    assertFalse(stderr.contains("after 10 attempts"), "it gave up rather than recovered: " + both);
  }

  /**
   * The text assertion behind the process one: cheap, runs everywhere, and fails the moment somebody
   * rewrites the loop away.
   */
  @Test
  public void theBootstrapRetriesItsFetchAndSaysHowManyTimes() {
    String bootstrap = CiDaemonLauncher.BOOTSTRAP;
    assertTrue(bootstrap.contains("while :; do"), bootstrap);
    assertTrue(bootstrap.contains("attempt=$((attempt + 1))"), bootstrap);
    assertTrue(bootstrap.contains("sleep " + PAUSE_SECONDS), bootstrap);
    assertTrue(bootstrap.contains("[ \"$attempt\" -ge " + ATTEMPTS + " ]"), bootstrap);
    // The substring both ITs assert on survives the rewrite, on the give-up arm and the retry one.
    assertTrue(bootstrap.contains("could not fetch $QITS_CI_DAEMON_BINARY_URL after"), bootstrap);
    assertTrue(bootstrap.contains("could not fetch $QITS_CI_DAEMON_BINARY_URL (attempt"), bootstrap);
    // A per-attempt timeout on each arm, or a hung attempt makes the whole budget meaningless.
    assertTrue(bootstrap.contains("wget -q -T 20 -O"), bootstrap);
    assertTrue(bootstrap.contains("--connect-timeout 10 --max-time 120"), bootstrap);
  }

  /**
   * <b>The guard that stops the two numbers drifting apart.</b> The in-container retry budget and the
   * host's register deadline are one decision written in two files, and the deadline losing to the
   * budget makes the retry pointless: qits-ci would give up and report {@code NEVER_STARTED} while
   * the container was still trying. The shipped value is read rather than restated here, so a
   * deployment-facing edit to {@code microprofile-config.properties} is what this sees.
   */
  @Test
  public void theShippedRegisterDeadlineOutlastsTheBootstrapsRetryBudget() {
    long deadline =
        ConfigProvider.getConfig().getValue("qits.ci.daemon-register-timeout-seconds", Long.class);
    assertTrue(
        deadline > REFUSED_BUDGET_SECONDS,
        "qits.ci.daemon-register-timeout-seconds is "
            + deadline
            + "s, which does not outlast BOOTSTRAP's "
            + REFUSED_BUDGET_SECONDS
            + "s of retrying against a refused connection");
  }

  private static int aPortNobodyIsListeningOn() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
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

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file) : "";
  }
}
