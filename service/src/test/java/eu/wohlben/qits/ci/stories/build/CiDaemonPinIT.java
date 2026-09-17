package eu.wohlben.qits.ci.stories.build;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.ci.stories.support.MockContainers;
import eu.wohlben.qits.ci.stories.support.StoryDaemon;
import eu.wohlben.qits.ci.stories.support.StoryIdentities;
import eu.wohlben.qits.ci.stories.support.StoryOrigin;
import eu.wohlben.qits.ci.stories.support.StoryTarget;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * <b>THE PIN TEST.</b> The {@code qits-ci-daemon} binary at exactly the version this reactor pins is
 * downloaded, started as a process against this launched qits-ci, and made to run a real build step
 * over the real control socket — before any step container on the platform is started from it.
 *
 * <h2>What it is for</h2>
 *
 * <p>qits-ci used to learn which daemon to put in a step container from {@code
 * qits.ci.daemon-version}, a qits-configuration entry a release listener rewrote the moment
 * qits-ci-daemon published, with an adoption ladder on top that took whatever had just been released
 * and kept it if a probe container dialled back. So a new daemon reached every step of every build on
 * the platform with this repository's suite never having run against it, and the only gate on a
 * broken wire was a probe the released service ran on itself in production. The version is a pinned
 * dependency now — {@link CiDaemonBinary#VERSION}, off {@code
 * eu.wohlben.qits:qits-ci-daemon-protocol}, whose release publishes both the jar this service
 * compiles against and the binary the bootstrap downloads — and this is the test that makes the pin
 * mean something: a bump that breaks the protocol fails <em>this repository's</em> release request,
 * which is a red gate on a branch, rather than a live run somebody is waiting on.
 *
 * <h2>Two assertions, and neither is worth anything without the other</h2>
 *
 * <p>The test fetches {@code …/artifacts/daemons/qits-ci-daemon/<CiDaemonBinary.VERSION>}, so a
 * <b>404 says the pin names a binary that does not exist</b> — released without its daemon, or
 * retention removed it. That is half. The other half is that the bytes at that path really are that
 * version, and the binary is the only thing that can say so: <b>the wire carries no version at
 * all</b> ({@code Hello} is {@code (daemonId, capabilityVersion)} and nothing more), and the run
 * row's {@code daemonVersion} is the host quoting its own pin back at itself, which is evidence about
 * this service's configuration and none at all about the process on the other end of the socket. What
 * is left is the daemon's own startup line — {@code ci-daemon <version> native …}, which Quarkus
 * prints out of the artifact's own coordinates — so that is what is asserted, out of the log this
 * test redirects the child to, and the failure quotes the line it found.
 *
 * <h2>A process, not a container</h2>
 *
 * <p>What this service talks to is the daemon inside a step container, not the container, so a
 * container buys nothing here and costs everything: a CI step container runs with no privilege and no
 * docker at all, rootless dind needs {@code --privileged}, and the suite has to stay green from a
 * clone with no docker. The binary is a GraalVM <b>musl-static</b> native image, which is exactly
 * what makes running it here possible — every step image on this platform is Alpine, and so is the
 * step container this gate runs in, so the artifact that ships is the artifact that executes. (The
 * sibling {@code WorkspaceDaemonPinIT} has to download a jar instead, because that daemon's native
 * image is compiled against glibc. Same idea, opposite constraint.) What is not covered is the step
 * image's own toolchain and the orchestrator's pull, which are qits-containers' and the image
 * pipeline's.
 *
 * <h2>Why it reuses the story harness rather than standing up a host of its own</h2>
 *
 * <p>The sibling pin test in qits-workspaces is a bare Vert.x server speaking the protocol, because
 * over there the subject is the codec. Here the subject is a <b>run</b>: a daemon that registers,
 * checks out and streams is only interesting if what comes back is a row somebody can read. So this
 * is {@link BuildExecutionIT}'s story with the fake daemon taken out and the real binary put in — the
 * same {@link StoryOrigin} repository, the same {@link MockContainers} orchestrator, the same trigger
 * door, the same transcript read over HTTP — and it shares that class's {@code @TestProfile}
 * deliberately. A second {@code @TestProfile} is a second launched qits-ci for the failsafe phase,
 * and a CI step here runs in a 4 g cgroup with no swap where each Quarkus profile retains ~125 MB of
 * metaspace.
 *
 * <p>It is <b>not</b> a userflow: it publishes no story and asserts no diagram. It sorts last among
 * the classes on that profile ({@code …stories.build.CiDaemonPinIT} after {@code BuildExecutionIT}
 * and {@code BuildTriggerIT}), so the traffic it makes through the framework's already-installed
 * RestAssured tap is recorded after every story has drained and lands in nobody's diagram.
 *
 * <h2>It gates, and it skips only where it must</h2>
 *
 * <p>No {@code @Tag("extended")}: this one has to run. The three daemon ITs that carry that tag need
 * real docker and a running qits-containers; this one needs neither, because the orchestrator is
 * {@link MockContainers} — qits-ci sends a workload spec, nothing starts a container, and the test
 * starts the daemon itself with the credentials it reads <b>out of that recorded spec</b>. Nothing
 * here reads the host's launch table, which is what makes an admitted dial a measurement of the whole
 * path rather than of a fixture.
 *
 * <p>Where an artifacts origin is configured, a missing artifact or a failed round trip is a
 * <b>failure</b> and never a skip. Name the class in {@code
 * .config/qits/ci-event-release-request.yml}'s {@code -Dit.test} comma list or it never runs there at
 * all — silently — which is why the two move together.
 *
 * <p><b>The one skip is defensive rather than a supported mode.</b> It covers an artifacts origin
 * configured to nothing, and it is deliberately not load-bearing: this repository's clone-alone rule
 * already reads "a clone builds against the platform Maven repository", and the protocol jar carrying
 * {@link CiDaemonBinary} is resolved from it — so a checkout with no platform to ask fails at
 * dependency resolution long before any test runs. The branch exists so that a deployment which
 * blanks the address gets a legible sentence instead of a malformed URL.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@EnabledIf("eu.wohlben.qits.ci.stories.support.StoryOrigin#gitPresent")
public class CiDaemonPinIT {

  /**
   * Where qits-artifacts is, derived from the Maven repository address this build already carries —
   * the same {@code ${…%%/artifacts/*}} arithmetic every release pipeline does, because the daemon
   * store is a sibling path of the maven one inside one deployment. Derived rather than given a key
   * of its own, so there is no second address to configure wrongly.
   */
  private static final String ARTIFACTS_BASE = artifactsBase();

  /**
   * The repository this run is about. Fixed and readable for {@link StoryOrigin}'s reason, and
   * distinct from both build stories' so that nothing else's event can fire this pipeline.
   */
  private static final String REPO_ID = "story-daemon-pin";

  private static final String TRIGGER_FILE = "ci-event-pin-the-daemon.yml";

  private static final String TRIGGER_PATH = StoryOrigin.CONFIG_DIR + "/" + TRIGGER_FILE;

  private static final String EVENT = "SoftwareRelease";

  /** What this repository declared an interest in — unique to it, so no other story's event fires it. */
  private static final String DEPENDENCY = "qits-ci-daemon-pin-probe";

  /** The step's image. Nothing pulls it: no container is ever created, only asked for. */
  private static final String STEP_IMAGE = "alpine:3";

  /**
   * What the step prints, and the evidence that the step really ran in the real daemon. Unique per
   * run, so a transcript carrying it cannot be a leftover row from an earlier build of this
   * repository — the stub git host's root survives a run, and so does the database between two
   * invocations of the same suite.
   */
  private static final String MARKER = "ci-daemon-pin-ok-" + UUID.randomUUID();

  private static final int STEP_TIMEOUT_SECONDS = 300;

  /** How long a launch may take to arrive: the run is queued behind whatever the worker is doing. */
  private static final Duration LAUNCH_PATIENCE = Duration.ofSeconds(90);

  /**
   * How long the daemon may take to say what it is. It has only to start — the download already
   * happened — so this is a ceiling on a native binary's boot, not a budget.
   */
  private static final Duration BANNER_PATIENCE = Duration.ofSeconds(60);

  /** How long the whole run may take: a clone, a checkout and an {@code echo}. */
  private static final Duration RUN_PATIENCE = Duration.ofSeconds(180);

  /**
   * The line Quarkus prints out of the artifact's own coordinates, measured against the pinned
   * binary: {@code ci-daemon 2026.915.44557 native (powered by Quarkus 3.34.6) started in 0.015s}. It
   * is the binary self-reporting the version it was built at, which is the only version claim in this
   * whole exchange that the binary itself makes — see the class javadoc on why neither the wire nor
   * the run row can stand in for it.
   *
   * <p><b>The name here is {@code ci-daemon} and not {@link CiDaemonBinary#DAEMON_NAME}, and the two
   * are different strings on purpose.</b> What Quarkus prints is the daemon module's maven
   * artifactId; {@code qits-ci-daemon} is the coordinate the release PUTs the bytes under in
   * qits-artifacts' {@code daemons} store. Spelling the constant here would match nothing and the
   * test would fail on its own regex rather than on the pin. Written as a substring so that a daemon
   * repo which later renames its module to the full coordinate keeps matching.
   */
  private static final Pattern STARTUP_LINE =
      Pattern.compile("ci-daemon (\\S+) (?:native|on JVM)\\b");

  private static String artifactsBase() {
    String maven =
        System.getProperty(
            "qits.maven.repository.url",
            System.getenv().getOrDefault("QITS_MAVEN_REPOSITORY_URL", ""));
    int marker = maven.indexOf("/artifacts/");
    return marker < 0 ? "" : maven.substring(0, marker) + "/artifacts";
  }

  private static String triggerFile() {
    return """
        event: %s
        when:
          - name: { exact: %s }
        steps:
          - image: %s
            timeout-seconds: %d
            script: |
              echo %s
        """
        .formatted(EVENT, DEPENDENCY, STEP_IMAGE, STEP_TIMEOUT_SECONDS, MARKER);
  }

  @Test
  public void theDaemonThisReactorPinsRunsARealStepAgainstThisHost() throws Exception {
    assumeTrue(
        !ARTIFACTS_BASE.isBlank(),
        "no artifacts origin is configured (qits.maven.repository.url /"
            + " QITS_MAVEN_REPOSITORY_URL) — a clone with no platform to ask cannot run the pin"
            + " test");

    // NOT /tmp/qits-ci-daemon, AND THE NAME IS THE REASON. Inside a CI step container that exact
    // path IS the running ci daemon: CiDaemonLauncher.BOOTSTRAP downloads the binary there, chmods
    // it and execs it, so a write to it gets ETXTBSY in CI while passing on a developer sandbox —
    // a red gate whose cause is a coincidence of names. This test owns its own directory and puts
    // everything, the binary included, inside it.
    Path work = Files.createTempDirectory("qits-ci-daemon-pin-");
    Path binary = work.resolve("qits-ci-daemon");
    Path checkout = work.resolve("checkout");
    Path log = work.resolve("daemon.log");

    // Downloaded BEFORE the trigger, on purpose: from the launch onward the host is counting down
    // qits.ci.daemon-register-timeout-seconds, and a 38 MB fetch inside that window would be this
    // test spending the container's budget on itself.
    download(binary);

    MockContainers.installSource();
    String publishedSha = StoryOrigin.publish(REPO_ID, TRIGGER_FILE, triggerFile());
    // …and then wait for it to be a candidate: qits-ci caches the git host's repository listing, so
    // a repository published inside that window is one the engine has not heard of yet.
    StoryOrigin.awaitCandidateListing();

    Process daemon = null;
    try {
      String runId = trigger();

      // The credentials come out of the workload spec and by no other route — MockContainers records
      // request BODIES for exactly this, and reading the host's own launch table instead would make
      // the dial a fixture rather than a measurement of the path qits-ci says it uses.
      MockContainers.Launch launch = awaitOurLaunch();
      String daemonId = launch.environment().get(StoryDaemon.ID_VARIABLE);
      String secret = launch.environment().get(StoryDaemon.SECRET_VARIABLE);
      assertNotNull(daemonId, "the spec must carry the per-container daemon id");
      assertNotNull(secret, "…and the secret that is the whole of this socket's authentication");
      assertTrue(
          launch.environment().get(StoryDaemon.URL_VARIABLE).endsWith(StoryTarget.DAEMON_PATH),
          "the container is told to dial " + StoryTarget.DAEMON_PATH);

      daemon = start(binary, checkout, launch, publishedSha, log);
      assertPinnedVersion(log);

      Map<String, Object> run = awaitTerminalRun(runId, log);
      assertEquals(
          "SUCCESS",
          run.get("status"),
          "the exit code the pinned daemon reported is the verdict; its output was:\n"
              + logText(log));
      assertEquals(publishedSha, run.get("commitSha"));

      List<Map<String, Object>> steps = readSteps(run);
      assertEquals(1, steps.size(), "the pipeline declared one step and one step was recorded");
      assertEquals(0, steps.getFirst().get("exitCode"));
      // The whole round trip in one string: the daemon cloned the repository, checked the pushed sha
      // out, ran the repository's own script under the step image's shell, and streamed what it
      // printed back up the socket as StepChunk frames — which the host turned into this row.
      assertTrue(
          String.valueOf(steps.getFirst().get("output")).contains(MARKER),
          "the step's row must carry what the pinned daemon streamed; got: "
              + steps.getFirst().get("output"));

      // Decommission: this daemon has one step in it and every ending is an exit, so a clean run is
      // a process that has already gone by the time the host's DELETE lands. Waited for rather than
      // killed — a daemon that delivered its StepFinished and then hung would be a bump worth
      // failing on, and destroying it in the finally would hide exactly that.
      assertTrue(
          daemon.waitFor(30, TimeUnit.SECONDS),
          "the pinned daemon did not exit after its step finished; its output was:\n" + logText(log));
      assertEquals(
          0,
          daemon.exitValue(),
          "the pinned daemon exited nonzero after delivering its step; its output was:\n"
              + logText(log));

      MockContainers.awaitRemoved(launch.containerName(), Duration.ofSeconds(30));
    } finally {
      if (daemon != null) {
        daemon.destroy();
        if (!daemon.waitFor(15, TimeUnit.SECONDS)) {
          daemon.destroyForcibly();
        }
      }
      deleteTree(work);
    }
  }

  // --- the run -------------------------------------------------------------------------------------

  /** Announce the release this repository declared an interest in, and answer the run it accepted. */
  private static String trigger() {
    List<String> runIds =
        given()
            .header("Authorization", "Bearer " + StoryIdentities.platformToken())
            .contentType(ContentType.JSON)
            .body(Map.of("name", EVENT, "payload", Map.of("name", DEPENDENCY, "version", "1.0.0")))
            .when()
            .post(StoryTarget.TRIGGER_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("runIds");
    assertEquals(1, runIds.size(), "the repository that declared the interest was accepted for a run");
    return runIds.getFirst();
  }

  /**
   * The step container qits-ci asked for <b>for this repository</b>.
   *
   * <p>{@link MockContainers#awaitLaunch} consumes launches in arrival order and the counter is
   * shared across every class on this profile, so "the next one" is only unambiguous while nobody
   * leaves one unconsumed. This class runs last, after two story classes, and a spec carries the
   * repository it is for — so the cheap check is made rather than assumed.
   */
  private static MockContainers.Launch awaitOurLaunch() {
    long deadline = System.nanoTime() + LAUNCH_PATIENCE.toNanos();
    while (true) {
      MockContainers.Launch launch = MockContainers.awaitLaunch(LAUNCH_PATIENCE);
      if (REPO_ID.equals(launch.environment().get("QITS_CI_REPO_ID"))) {
        return launch;
      }
      if (System.nanoTime() >= deadline) {
        return fail(
            "qits-ci never asked for a step container for "
                + REPO_ID
                + "; the last one was for "
                + launch.environment().get("QITS_CI_REPO_ID"));
      }
    }
  }

  /** Poll one run until it has left {@code QUEUED}/{@code RUNNING}, as the peer that asked for it. */
  private static Map<String, Object> awaitTerminalRun(String runId, Path log) {
    long deadline = System.nanoTime() + RUN_PATIENCE.toNanos();
    while (true) {
      Map<String, Object> run =
          given()
              .header("Authorization", "Bearer " + StoryIdentities.platformToken())
              .when()
              .get(StoryTarget.runPath(runId))
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getMap("$");
      Object status = run.get("status");
      if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
        return run;
      }
      if (System.nanoTime() >= deadline) {
        // The daemon's own output is the only thing that says WHICH of the many reasons this is —
        // a clone that could not reach the origin, a shell the image does not have, a frame it
        // could not decode. A bare timeout sends the reader to a log a deliberately non-inherited
        // stream means they cannot find.
        return fail(
            "run "
                + runId
                + " never reached a terminal status within "
                + RUN_PATIENCE
                + " — "
                + CiDaemonBinary.DAEMON_NAME
                + " "
                + CiDaemonBinary.VERSION
                + ", the version this reactor pins. Its output was:\n"
                + logText(log));
      }
      sleep(250);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> readSteps(Map<String, Object> run) {
    List<Map<String, Object>> steps = (List<Map<String, Object>>) run.get("steps");
    assertNotNull(steps, "a run read by id carries its steps");
    return steps;
  }

  // --- the binary ----------------------------------------------------------------------------------

  /**
   * Fetches the pinned daemon out of qits-artifacts' {@code daemons} store.
   *
   * <p>A missing artifact is a <b>failure with a sentence</b>, never a skip. It means the version
   * this reactor pins was released without its binary — or that retention removed it — and either is
   * the exact class of defect this test exists to surface, one release earlier than a step container
   * whose bootstrap 404s where nobody is watching.
   */
  private static void download(Path target) throws Exception {
    String url =
        ARTIFACTS_BASE + "/daemons/" + CiDaemonBinary.DAEMON_NAME + "/" + CiDaemonBinary.VERSION;
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
      HttpResponse<InputStream> answer =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (answer.statusCode() != 200) {
        fail(
            "the pinned daemon "
                + CiDaemonBinary.DAEMON_NAME
                + " "
                + CiDaemonBinary.VERSION
                + " is not in qits-artifacts ("
                + answer.statusCode()
                + " from "
                + url
                + "). The pom pins a version whose binary was never published or no longer exists.");
      }
      try (InputStream body = answer.body()) {
        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    target.toFile().setExecutable(true, true);
  }

  /**
   * Starts the pinned daemon as an ordinary process, told exactly what a step container is told.
   *
   * <p><b>Every ambient {@code QITS_} variable is removed first, and that is the difference between a
   * test and a coincidence.</b> {@link ProcessBuilder} seeds the child from this process's
   * environment, and this process runs somewhere with opinions: a workspace container carries a full
   * commissioned credential set, and a CI step container carries {@code QITS_COMMISSIONED_CLIENT_ID}
   * / {@code …_SECRET} and little else — including, on the gating run, {@code QITS_CI_DAEMON_URL},
   * {@code QITS_CI_BRANCH} and {@code QITS_CI_SHA} of the build this very test is part of, which name
   * a different host, a different branch and a different commit. A pin test whose result depends on
   * where it runs proves nothing about the pin, which is the same class of defect as the
   * configuration entry this whole change replaced. So the child gets the six variables {@code
   * DaemonEnv} requires and no others.
   *
   * <p>Three of those six are the spec's own — the id, the secret and the sha/branch qits-ci decided
   * this run is about. Two are not, and both substitutions are named here rather than left to be
   * discovered:
   *
   * <ul>
   *   <li><b>The url.</b> The spec carries {@code qits.ci.container-daemon-url}, whose default is
   *       {@code ws://qits-ci:8080/ci/daemon} — a name that resolves on {@code qits-net} and nowhere
   *       else. What is asserted about it at the call site is the part that is a cross-repo contract
   *       (the path the binary dials verbatim); the authority is this JVM's own, because the host
   *       under test is a process on loopback and not a service on a network.
   *   <li><b>The repository url.</b> {@code StubGitHost} serves qits-githost's two CONTENT routes
   *       ({@code /git/<repoId>/blob|tree/…}) and is not a git server: there is no {@code
   *       git-upload-pack} behind it, so the daemon's {@code git clone --depth 50} could not talk to
   *       it whatever address it were given. The clone is therefore pointed at the bare {@link
   *       StoryOrigin} published, over {@code file://} so that {@code --depth} is honoured rather
   *       than warned about — a real clone of a real repository, which is what this test needs the
   *       daemon to do. Which transport carried it is qits-githost's question and not this one's.
   * </ul>
   *
   * <p>The workspace directory is handed over as a system property rather than as a seventh
   * environment variable, so the env stays exactly the contract. It has to be handed over at all:
   * the shipped default is {@code /workspace}, which inside a step container is an empty volume and
   * on this machine is somebody's checkout.
   */
  private static Process start(
      Path binary, Path checkout, MockContainers.Launch launch, String sha, Path log)
      throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(binary.toString(), "-Dqits.ci.workspace-dir=" + checkout);
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(key -> key.startsWith("QITS_"));
    env.put(
        StoryDaemon.URL_VARIABLE,
        "ws://127.0.0.1:" + RestAssured.port + StoryTarget.DAEMON_PATH);
    env.put(StoryDaemon.ID_VARIABLE, launch.environment().get(StoryDaemon.ID_VARIABLE));
    env.put(StoryDaemon.SECRET_VARIABLE, launch.environment().get(StoryDaemon.SECRET_VARIABLE));
    env.put("QITS_CI_REPOSITORY_URL", StoryOrigin.bare(REPO_ID).toUri().toString());
    env.put("QITS_CI_BRANCH", launch.environment().get("QITS_CI_BRANCH"));
    env.put("QITS_CI_SHA", sha);
    // TO A FILE, AND NOT inheritIO(). A pipe nobody drains would fill and wedge the child, so the
    // output has to go somewhere — and `inheritIO` sends it to this forked JVM's native stdout,
    // which is failsafe's own control channel: surefire answers that with "Corrupted channel by
    // directly writing to native stream in forked JVM". The file is also where the version
    // assertion reads from, and every failure message here quotes it, so a daemon that dies at
    // startup still says why.
    return builder
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
        .start();
  }

  /**
   * <b>The version assertion.</b> What makes this "the pinned daemon" rather than "a daemon": the
   * process that just started says, out of its own build coordinates, which version it is, and it has
   * to be the one the pom names. If this disagrees, something at that artifacts path is not the build
   * the reactor compiled against and every assertion below it is about the wrong binary.
   */
  private static void assertPinnedVersion(Path log) {
    long deadline = System.nanoTime() + BANNER_PATIENCE.toNanos();
    while (true) {
      String text = logText(log);
      Matcher line = STARTUP_LINE.matcher(text);
      if (line.find()) {
        assertEquals(
            CiDaemonBinary.VERSION,
            line.group(1),
            "the daemon that started is not the version this reactor pins — pinned "
                + CiDaemonBinary.VERSION
                + ", binary says "
                + line.group(1)
                + ". The line it printed was: "
                + text.substring(Math.max(0, line.start()), line.end()));
        return;
      }
      if (System.nanoTime() >= deadline) {
        fail(
            "the pinned daemon never said what it is within "
                + BANNER_PATIENCE
                + " — no '"
                + CiDaemonBinary.DAEMON_NAME
                + " <version> native' line. Its output was:\n"
                + text);
      }
      sleep(200);
    }
  }

  private static String logText(Path log) {
    try {
      return Files.exists(log) ? Files.readString(log) : "(the daemon wrote nothing)";
    } catch (IOException unreadable) {
      return "(the daemon's log could not be read: " + unreadable + ")";
    }
  }

  private static void deleteTree(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException leftover) {
      // A leftover temp directory is not worth failing a green run over.
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
