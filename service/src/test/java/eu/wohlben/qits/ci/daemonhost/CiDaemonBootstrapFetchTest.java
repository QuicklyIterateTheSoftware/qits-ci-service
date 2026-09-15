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
 * <p><b>It must NOT write {@code /tmp/qits-ci-daemon}, and that is the one edit made to the shipped
 * text.</b> The path is a literal in {@code BOOTSTRAP} and the text honours no {@code TMPDIR}, so
 * the only way to run the real thing safely is to rewrite the literal. Rewriting it is not
 * convenience: this suite runs inside a qits-ci step container, where {@code /tmp/qits-ci-daemon}
 * <em>is the running ci daemon</em> — the maven build executing this test is a child of the process
 * exec'd from that file. Writing there is one errno away from overwriting the daemon that is
 * hosting the build, and Linux answers the attempt with {@code ETXTBSY} ("Text file busy", errno
 * 26) because the file is a mapped ELF image. That is exactly what happened on 2026-09-15: every
 * fetch failed, the loop slept its whole budget, and the test reported "the bootstrap never exited"
 * — a message that hid its own cause. (A {@code #!/bin/sh} script at that path is NOT protected
 * this way, which is why a developer sandbox with nothing running from there passed.)
 *
 * <p>The substitution is a single guarded {@code replace} of that one literal with a path under
 * this test's own temp directory, and it is the <b>only</b> edit made to the shipped text:
 * the downloader probe, the retry loop, the per-attempt timeouts, the sleep, the give-up arm, the
 * {@code chmod +x} and the {@code exec} are all run verbatim. What keeps that honest is the
 * occurrence count asserted before the replace — if somebody changes the path or adds another use
 * of it, this test fails loudly instead of quietly exercising a text that no longer ships. The
 * substituted copy is local to this method; every other test in this class asserts on the
 * unmodified constant.
 */
public class CiDaemonBootstrapFetchTest {

  /** {@code attempt} caps at this, {@code sleep} is this long — both typed into BOOTSTRAP. */
  private static final int ATTEMPTS = 10;

  private static final int PAUSE_SECONDS = 12;

  /** What the bootstrap can spend retrying a connection that is REFUSED, which fails at once. */
  private static final long REFUSED_BUDGET_SECONDS = (long) (ATTEMPTS - 1) * PAUSE_SECONDS;

  /** The output path typed into BOOTSTRAP, and how many times the real text spells it. */
  private static final String SHIPPED_OUTPUT_PATH = "/tmp/qits-ci-daemon";

  private static final int SHIPPED_OUTPUT_PATH_USES = 4;

  /**
   * Room per attempt on top of {@link #REFUSED_BUDGET_SECONDS} for the attempts themselves — the
   * connect, and on a host that answers slowly the {@code -T 20} / {@code --max-time 120} deadlines.
   */
  private static final long PER_ATTEMPT_ALLOWANCE_SECONDS = 8;

  /**
   * <b>Derived, not picked.</b> A wait shorter than the bootstrap's own give-up budget turns every
   * real failure into "the bootstrap never exited" and hides the stderr that explains it — which is
   * precisely what 90s did here. So it is {@link #REFUSED_BUDGET_SECONDS} plus a generous per-attempt
   * margin, and it moves when {@link #ATTEMPTS} or {@link #PAUSE_SECONDS} do.
   */
  private static final long PROCESS_WAIT_SECONDS =
      REFUSED_BUDGET_SECONDS + ATTEMPTS * PER_ATTEMPT_ALLOWANCE_SECONDS;

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

    // The one edit to the shipped text — see this class's javadoc. The count is asserted first so
    // that a change to the path, or a fifth use of it, fails here rather than silently leaving an
    // occurrence pointing at the running daemon.
    String shipped = CiDaemonLauncher.BOOTSTRAP;
    assertEquals(
        SHIPPED_OUTPUT_PATH_USES,
        occurrences(shipped, SHIPPED_OUTPUT_PATH),
        "BOOTSTRAP no longer spells " + SHIPPED_OUTPUT_PATH + " the expected number of times");
    Path daemonPath = work.resolve("qits-ci-daemon");
    String bootstrap = shipped.replace(SHIPPED_OUTPUT_PATH, daemonPath.toString());

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
      ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", bootstrap);
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
      // generous and finite: a bootstrap that hangs must fail this test, not hang the build — and
      // it OUTLASTS the give-up budget by construction (see PROCESS_WAIT_SECONDS), so a bootstrap
      // that really gave up is reported as its own stderr rather than as "never exited".
      if (!process.waitFor(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)) {
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
