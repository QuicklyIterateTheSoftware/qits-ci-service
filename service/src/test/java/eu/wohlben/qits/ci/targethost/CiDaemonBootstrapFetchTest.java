package eu.wohlben.qits.ci.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.ci.HermeticEnvironment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The REAL {@link CiSlotSettings#BOOTSTRAP}, run through {@code /bin/sh} against a server that is
 * not listening yet — the one failure the retry exists for.
 *
 * <p><b>Why this was never a case in the (since deleted, qits-506) {@code CiDaemonHandshakeIT}.</b>
 * That IT's never-registers case pointed the bootstrap at a url that 404s forever, which proved the
 * give-up arm and nothing about recovery: no number of attempts would have fetched it. The incident of 2026-09-15 01:05 UTC was
 * the opposite shape — qits-artifacts deploys {@code update_order: stop-first}, a step container
 * landed 7 seconds into that refusal window, got {@code Connection refused}, and a release-request run went
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
 * <p>The substitution is a guarded {@code replace} of that literal with a path under this test's
 * own temp directory, and of the target's daemon cache volume {@code /caches/ci-daemon}
 * (qits-1809) likewise — a step container mounts the real one, shared with every lease on its
 * target. Those two are the <b>only</b> edits made to the shipped text: the cache arms, the
 * downloader probe, the retry loop, the per-attempt timeouts, the sleep, the give-up arm, the
 * {@code chmod +x} and the {@code exec} are all run verbatim. What keeps that honest is the
 * occurrence counts asserted before the replace — if somebody changes a path or adds another use
 * of one, this test fails loudly instead of quietly exercising a text that no longer ships. The one
 * deliberate exception is the demonstration in {@link #aSlowButMovingDownloadIsNeverCutOff}, which
 * puts the retired {@code --max-time} back to show what it did.
 */
public class CiDaemonBootstrapFetchTest {

  /** {@code attempt} caps at this, {@code sleep} is this long — both typed into BOOTSTRAP. */
  private static final int ATTEMPTS = 10;

  private static final int PAUSE_SECONDS = 12;

  /** What the bootstrap can spend retrying a connection that is REFUSED, which fails at once. */
  private static final long REFUSED_BUDGET_SECONDS = (long) (ATTEMPTS - 1) * PAUSE_SECONDS;

  /** The output path typed into BOOTSTRAP, and how many times the real text spells it. */
  private static final String SHIPPED_OUTPUT_PATH = "/tmp/qits-ci-daemon";

  private static final int SHIPPED_OUTPUT_PATH_USES = 6;

  /**
   * The target's daemon cache volume (qits-1809), typed into BOOTSTRAP, and how many times the real
   * text spells it. Moved under the test's own directory for the same reason as {@link
   * #SHIPPED_OUTPUT_PATH}: a qits-ci step container mounts the real one, shared with every other
   * lease on its target.
   */
  private static final String SHIPPED_CACHE_DIR = "/caches/ci-daemon";

  private static final int SHIPPED_CACHE_DIR_USES = 5;

  /** The version the cache tests' leases are told, {@code $QITS_CI_DAEMON_VERSION}. */
  private static final String VERSION = "2026.1102.1";

  /**
   * Room per attempt on top of {@link #REFUSED_BUDGET_SECONDS} for the attempts themselves — the
   * connect, and on a host that answers slowly the {@code -T 20} / {@code --connect-timeout 10}
   * deadlines.
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

    // The one edit to the shipped text — see this class's javadoc.
    String bootstrap = underTemp(work);

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
      // Nothing inherited but PATH and HOME, so every conditional block — the registry login and
      // QITS_TOKEN's credential setup — is skipped: this test is about the fetch, and what the
      // token becomes is CiDaemonBootstrapTokenTest's subject. A remove-list here missed QITS_TOKEN
      // on qits-ci's first run through the edge; see HermeticEnvironment.
      Map<String, String> env = HermeticEnvironment.of(builder);
      env.put("QITS_CI_DAEMON_BINARY_URL", "http://127.0.0.1:" + port + "/qits-ci-daemon");
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
    String bootstrap = CiSlotSettings.BOOTSTRAP;
    assertTrue(bootstrap.contains("while [ -n \"$daemon_out\" ]; do"), bootstrap);
    assertTrue(bootstrap.contains("attempt=$((attempt + 1))"), bootstrap);
    assertTrue(bootstrap.contains("sleep " + PAUSE_SECONDS), bootstrap);
    assertTrue(bootstrap.contains("[ \"$attempt\" -ge " + ATTEMPTS + " ]"), bootstrap);
    // The substring both ITs assert on survives the rewrite, on the give-up arm and the retry one.
    assertTrue(bootstrap.contains("could not fetch $QITS_CI_DAEMON_BINARY_URL after"), bootstrap);
    assertTrue(bootstrap.contains("could not fetch $QITS_CI_DAEMON_BINARY_URL (attempt"), bootstrap);
    // A per-attempt timeout on each arm, or a hung attempt makes the whole budget meaningless.
    // "$@" is the edge plane's bearer (qits-441) and expands to nothing on an internal step.
    assertTrue(bootstrap.contains("wget -q -T 20 \"$@\" -O"), bootstrap);
    // qits-1809: curl aborts a STALLED transfer only, never a moving one; no total cap.
    assertTrue(
        bootstrap.contains("--connect-timeout 10 --speed-limit 10240 --speed-time 30"), bootstrap);
    assertFalse(bootstrap.contains("--max-time"), bootstrap);
  }

  // --- the daemon cache (qits-1809) --------------------------------------------------------------

  /**
   * A miss: the lease downloads the daemon once, renames it into the cache under its version, runs
   * the copy in {@code /tmp}, and prunes what has not been used for 14 days — a stale version and a
   * part file a dead lease left.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void aCacheMissDownloadsOnceAndCachesTheVersion() throws Exception {
    assumeShell();
    Path work = Files.createTempDirectory("ci-bootstrap-cache-miss");
    Path cache = Files.createDirectories(work.resolve("caches-ci-daemon"));
    Path stale = Files.writeString(cache.resolve("qits-ci-daemon-2026.101.1"), "old");
    Path deadPart = Files.writeString(cache.resolve("qits-ci-daemon-2026.101.1.part.abc123"), "x");
    Path recent = Files.writeString(cache.resolve("qits-ci-daemon-2026.1101.1"), "recent");
    FileTime monthAgo = FileTime.from(Instant.now().minus(Duration.ofDays(30)));
    Files.setLastModifiedTime(stale, monthAgo);
    Files.setLastModifiedTime(deadPart, monthAgo);

    try (Daemons daemons = new Daemons(work)) {
      Result result = run(work, daemons.url(), VERSION);

      assertEquals(0, result.exitCode, result.diagnosis());
      assertEquals(1, daemons.requests.size(), result.diagnosis());
      assertEquals("ran", Files.readString(work.resolve("marker")).strip(), "the daemon was exec'd");
      Path cached = cache.resolve("qits-ci-daemon-" + VERSION);
      assertEquals(daemons.script(), Files.readString(cached), "the version is cached whole");
      assertTrue(
          result.stderr.contains("qits-ci: fetched ci daemon " + VERSION + " and cached it"),
          result.diagnosis());
      assertFalse(Files.exists(stale), "a version unused for 14 days is pruned");
      assertFalse(Files.exists(deadPart), "a dead lease's part file is pruned");
      assertTrue(Files.exists(recent), "a recently used version is kept");
      try (var listing = Files.list(cache)) {
        assertTrue(
            listing.noneMatch(file -> file.getFileName().toString().contains(".part.")),
            "no part file is left behind");
      }
    }
  }

  /** A hit: the cached version is copied and run, with no request to qits-artifacts at all. */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void aCacheHitRunsTheCachedDaemonWithoutARequest() throws Exception {
    assumeShell();
    Path work = Files.createTempDirectory("ci-bootstrap-cache-hit");
    Path cache = Files.createDirectories(work.resolve("caches-ci-daemon"));
    Path cached =
        Files.writeString(
            cache.resolve("qits-ci-daemon-" + VERSION),
            "#!/bin/sh\necho cached > " + work.resolve("marker") + "\nexit 0\n");
    FileTime weekAgo = FileTime.from(Instant.now().minus(Duration.ofDays(7)));
    Files.setLastModifiedTime(cached, weekAgo);

    try (Daemons daemons = new Daemons(work)) {
      Result result = run(work, daemons.url(), VERSION);

      assertEquals(0, result.exitCode, result.diagnosis());
      assertEquals(List.of(), daemons.requests, "a hit makes no request");
      assertEquals("cached", Files.readString(work.resolve("marker")).strip());
      assertTrue(
          result.stderr.contains("qits-ci: using cached ci daemon " + VERSION), result.diagnosis());
      assertTrue(
          Files.getLastModifiedTime(cached).compareTo(weekAgo) > 0,
          "a hit is touched, so the prune keeps what is in use");
    }
  }

  /** No cache volume: the daemon is fetched straight to {@code /tmp}, as before qits-1809. */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void withoutTheCacheVolumeTheDaemonIsFetchedAsBefore() throws Exception {
    assumeShell();
    Path work = Files.createTempDirectory("ci-bootstrap-no-cache");

    try (Daemons daemons = new Daemons(work)) {
      Result result = run(work, daemons.url(), VERSION);

      assertEquals(0, result.exitCode, result.diagnosis());
      assertEquals(1, daemons.requests.size(), result.diagnosis());
      assertEquals("ran", Files.readString(work.resolve("marker")).strip());
      assertFalse(result.stderr.contains("cache"), result.diagnosis());
      assertFalse(Files.exists(work.resolve("caches-ci-daemon")), "nothing made the volume");
    }
  }

  /** A lease told no version cannot key the cache, and fetches as before even with the volume. */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void aLeaseWithNoVersionLeavesTheCacheAlone() throws Exception {
    assumeShell();
    Path work = Files.createTempDirectory("ci-bootstrap-no-version");
    Path cache = Files.createDirectories(work.resolve("caches-ci-daemon"));

    try (Daemons daemons = new Daemons(work)) {
      Result result = run(work, daemons.url(), null);

      assertEquals(0, result.exitCode, result.diagnosis());
      assertEquals(1, daemons.requests.size(), result.diagnosis());
      assertEquals("ran", Files.readString(work.resolve("marker")).strip());
      try (var listing = Files.list(cache)) {
        assertEquals(0, listing.count(), "nothing is cached without a version");
      }
    }
  }

  /** A cache directory the build user cannot write is left alone, as if absent. */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void anUnwritableCacheVolumeIsIgnored() throws Exception {
    assumeShell();
    assumeTrue(!"root".equals(System.getProperty("user.name")), "root writes a 0555 directory");
    Path work = Files.createTempDirectory("ci-bootstrap-unwritable-cache");
    Path cache = Files.createDirectories(work.resolve("caches-ci-daemon"));
    Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("r-xr-xr-x"));

    try (Daemons daemons = new Daemons(work)) {
      Result result = run(work, daemons.url(), VERSION);

      assertEquals(0, result.exitCode, result.diagnosis());
      assertEquals(1, daemons.requests.size(), result.diagnosis());
      assertEquals("ran", Files.readString(work.resolve("marker")).strip());
      assertFalse(result.stderr.contains("cache"), result.diagnosis());
    } finally {
      Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  // --- a slow but moving download (qits-1809) -----------------------------------------------------

  /**
   * A body streamed steadily over more than three seconds is fetched and the daemon starts: curl
   * aborts only a stalled transfer. The same text with the old total cap put back — {@code
   * --max-time 2} for the speed limit, and one attempt rather than ten so the demonstration does
   * not sleep through the retry budget — fails on the same stream, which is the regression on
   * 2026-11-02 at a scale a test can wait for (a 39 MB binary at ~300 KB/s against 120 s).
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  public void aSlowButMovingDownloadIsNeverCutOff() throws Exception {
    assumeShell();
    Path work = Files.createTempDirectory("ci-bootstrap-slow");

    try (Daemons daemons = new Daemons(work, SLOW_BODY_BYTES, SLOW_CHUNKS, SLOW_PAUSE)) {
      long began = System.nanoTime();
      Result result = run(work, daemons.url(), null);
      long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

      assertEquals(0, result.exitCode, result.diagnosis());
      assertEquals("ran", Files.readString(work.resolve("marker")).strip());
      assertTrue(tookMillis > 3_000, "the stream really took over 3s: " + tookMillis);
      assertFalse(result.stderr.contains("could not fetch"), result.diagnosis());

      if (onPath("wget")) {
        return; // the demonstration is curl's: an image with wget never reaches the curl arm
      }
      String speedLimit = "--speed-limit 10240 --speed-time 30";
      String giveUp = "[ \"$attempt\" -ge " + ATTEMPTS + " ]";
      String shipped = underTemp(work);
      assertEquals(1, occurrences(shipped, speedLimit), shipped);
      assertEquals(1, occurrences(shipped, giveUp), shipped);
      String capped =
          shipped.replace(speedLimit, "--max-time 2").replace(giveUp, "[ \"$attempt\" -ge 1 ]");
      Result old = run(capped, work, daemons.url(), null);
      assertEquals(1, old.exitCode, "a total cap cuts the moving download off: " + old.diagnosis());
      assertTrue(old.stderr.contains("could not fetch"), old.diagnosis());
    }
  }

  /** The slow stream: 2 MB in 40 chunks 90 ms apart, so ~3.6s at ~570 KB/s — never stalled. */
  private static final int SLOW_BODY_BYTES = 2 * 1024 * 1024;

  private static final int SLOW_CHUNKS = 40;

  private static final long SLOW_PAUSE = 90;

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

  /**
   * The shipped text with its two path literals moved under {@code work}: {@code /tmp/qits-ci-daemon}
   * to {@code work/qits-ci-daemon} and the cache volume to {@code work/caches-ci-daemon}. The counts
   * are asserted first, so a change to either path, or another use of one, fails here rather than
   * silently leaving an occurrence pointing at the running daemon or the target's real cache.
   */
  private static String underTemp(Path work) {
    String shipped = CiSlotSettings.BOOTSTRAP;
    assertEquals(
        SHIPPED_OUTPUT_PATH_USES,
        occurrences(shipped, SHIPPED_OUTPUT_PATH),
        "BOOTSTRAP no longer spells " + SHIPPED_OUTPUT_PATH + " the expected number of times");
    assertEquals(
        SHIPPED_CACHE_DIR_USES,
        occurrences(shipped, SHIPPED_CACHE_DIR),
        "BOOTSTRAP no longer spells " + SHIPPED_CACHE_DIR + " the expected number of times");
    return shipped
        .replace(SHIPPED_OUTPUT_PATH, work.resolve("qits-ci-daemon").toString())
        .replace(SHIPPED_CACHE_DIR, work.resolve("caches-ci-daemon").toString());
  }

  private static Result run(Path work, String url, String version) throws Exception {
    return run(underTemp(work), work, url, version);
  }

  /** {@code bootstrap} under {@code /bin/sh}, hermetic but for the url and, if any, the version. */
  private static Result run(String bootstrap, Path work, String url, String version)
      throws Exception {
    Path out = work.resolve("stdout");
    Path err = work.resolve("stderr");
    ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", bootstrap);
    builder.redirectOutput(out.toFile());
    builder.redirectError(err.toFile());
    Map<String, String> env = HermeticEnvironment.of(builder);
    env.put("QITS_CI_DAEMON_BINARY_URL", url);
    if (version != null) {
      env.put("QITS_CI_DAEMON_VERSION", version);
    }
    Process process = builder.start();
    if (!process.waitFor(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError(
          "the bootstrap never exited\nstdout:\n" + read(out) + "\nstderr:\n" + read(err));
    }
    return new Result(process.exitValue(), read(out), read(err));
  }

  private record Result(int exitCode, String stdout, String stderr) {
    String diagnosis() {
      return "exit " + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
    }
  }

  /**
   * qits-artifacts' daemons store, serving one stand-in daemon — a script that writes {@code ran}
   * to {@code work/marker}, padded with comment lines to the body's size — and recording each
   * request. Streamed in chunks with a pause between them when asked to be slow.
   */
  private static final class Daemons implements AutoCloseable {
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final HttpServer server;
    private final String script;

    Daemons(Path work) throws IOException {
      this(work, 0, 1, 0);
    }

    Daemons(Path work, int bodyBytes, int chunks, long pauseMillis) throws IOException {
      StringBuilder body =
          new StringBuilder("#!/bin/sh\necho ran > " + work.resolve("marker") + "\nexit 0\n");
      while (body.length() < bodyBytes) {
        body.append('#').append("x".repeat(Math.min(1023, bodyBytes - body.length()) - 1));
        body.append('\n');
      }
      script = body.toString();
      byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/artifacts/daemons/qits-ci-daemon/",
          exchange -> {
            requests.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(200, bytes.length);
            int size = (bytes.length + chunks - 1) / chunks;
            try (var response = exchange.getResponseBody()) {
              for (int from = 0; from < bytes.length; from += size) {
                response.write(bytes, from, Math.min(size, bytes.length - from));
                response.flush();
                if (pauseMillis > 0) {
                  Thread.sleep(pauseMillis);
                }
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (IOException e) {
              // the client hung up — the capped demonstration does exactly that
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/artifacts/daemons/qits-ci-daemon/"
          + VERSION;
    }

    String script() {
      return script;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static void assumeShell() {
    assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "/bin/sh is required for this test");
    assumeTrue(
        onPath("wget") || onPath("curl"),
        "one of wget/curl must be on PATH — the image contract this text probes for");
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
