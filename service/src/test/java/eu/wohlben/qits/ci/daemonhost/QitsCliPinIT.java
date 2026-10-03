package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.ci.HermeticEnvironment;
import eu.wohlben.qits.ci.QitsTokenAuth;
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
 * with the environment {@link eu.wohlben.qits.ci.runnerhost.StepContainerSettings} really injects.
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
 *   <li><b>The binary at that coordinate carries what the composed text calls.</b> It is executed,
 *       offline, and its own usage must name {@code artifacts publish sbom submit} with every option
 *       the postlude passes, and {@code artifacts publish exists}. The composed script itself runs
 *       against a recording stand-in for the CLI, so its calls are read off the far side rather
 *       than inferred from an exit code.
 * </ul>
 *
 * <p><b>It no longer makes the pinned binary publish (qits-731).</b> A CLI from qits-731 on derives
 * every address from {@code $QITS_DOMAIN} in code and reads no URL variable, so there is nothing a
 * test process can point at a stub: a live publish could only reach the platform's real, immutable
 * sbom store. What the pin can break between this reactor and the binary is the command surface,
 * and that is held for the current CLI and the next one alike.
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
 * <p>The download is from the REAL store, because "the pinned version exists" is the assertion, and
 * it is a read. Nothing is published anywhere: the composed text runs against a stub store serving
 * a recording stand-in, and the real binary is only asked for its usage, in an environment whose
 * one address input is a domain that cannot resolve. Publishing for real would write an SBOM into
 * the platform's own sbom store at a coordinate nobody released — an immutable surface, so the
 * litter would be permanent.
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

  /**
   * Where the step runs its composed postlude against, in place of the platform: a domain RFC 6761
   * reserves to never resolve. A CLI that derives its hosts from {@code QITS_DOMAIN} (qits-731) and
   * is somehow reached anyway fails to resolve rather than writing into a real store.
   */
  private static final String UNRESOLVABLE_DOMAIN = "qits-cli-pin-it.invalid";

  /**
   * The stand-in the stub store serves on the CLI's daemons route: a shell script that records every
   * call the composed text makes to {@code qits}, copies the document a submit names, and answers the
   * presence check from what was really submitted. Anything else is a loud refusal, so a postlude
   * that grows a call nobody asserted fails here rather than passing by silence.
   */
  private static final String RECORDING_CLI =
      """
      #!/bin/sh
      # qits-ci's QitsCliPinIT: a stand-in for the qits CLI that records what it is asked.
      set -eu
      record=${QITS_PIN_IT_RECORD:?}
      mkdir -p "$record"
      printf '%s\\n' "$*" >> "$record/calls"
      case "$*" in
        "artifacts publish sbom submit "*)
          file=
          while [ $# -gt 0 ]; do
            case "$1" in
              --file) file=$2; shift ;;
              --file=*) file=${1#--file=} ;;
            esac
            shift
          done
          cp "$file" "$record/submitted" ;;
        "artifacts publish exists sbom "*)
          [ -f "$record/submitted" ] ;;
        *)
          echo "the pin test's qits stand-in was asked something nobody asserted: $*" >&2
          exit 64 ;;
      esac
      """;

  private static String artifactsBase() {
    String maven =
        System.getProperty(
            "qits.maven.repository.url", System.getenv().getOrDefault("QITS_MAVEN_REPOSITORY_URL", ""));
    int marker = maven.indexOf("/artifacts/");
    return marker < 0 ? "" : maven.substring(0, marker) + "/artifacts";
  }

  @Test
  public void theCliThisReactorPinsCarriesWhatAComposedReleaseStepCalls(@TempDir Path work)
      throws Exception {
    assumeTrue(
        !ARTIFACTS_BASE.isBlank(),
        "no artifacts origin is configured (qits.maven.repository.url / QITS_MAVEN_REPOSITORY_URL)"
            + " — a clone with no platform to ask cannot run the pin test");

    // 1. THE PINNED BINARY, OUT OF THE REAL STORE. A non-200 here is the whole point of the test.
    // This GET is the test's only contact with the platform; nothing below writes anywhere real.
    byte[] binary = downloadPinnedBinary();

    // 2. THE BINARY CARRIES WHAT THE POSTLUDE CALLS, with the options the postlude passes. Asked of
    // the binary itself, offline — see pinnedBinaryCarriesThePostludesCommands for why it is not
    // made to publish any more.
    pinnedBinaryCarriesThePostludesCommands(binary, work.resolve("pinned"));

    // 3. The stand-in store: it serves a RECORDING STAND-IN for the CLI on the daemons route the
    // composed prelude fetches from, so the composed text runs end to end and every call it makes
    // to `qits` is read back off the far side.
    StubStore store = new StubStore(RECORDING_CLI.getBytes(StandardCharsets.UTF_8));
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

      // THE ONE EDIT, and it is to the text rather than the environment (qits-731). The prelude
      // downloads from `https://registry.qits.$QITS_DOMAIN/artifacts/daemons/`, a host that is code
      // and that no variable a step is handed can move — which is the property. So the stub store
      // is put in place of exactly that text, on both the curl and the wget arm, and nowhere else:
      // a seam that exists only in this test's copy of the script.
      String download = CiReleaseComposer.CLI_DOWNLOAD_BASE;
      assertEquals(
          2,
          script.split(java.util.regex.Pattern.quote(download), -1).length - 1,
          "the composed prelude downloads from the registry's public name on both arms:\n"
              + script);
      String stubbed = script.replace(download, store.base() + "/artifacts/daemons/");

      Path scriptFile = work.resolve("step.sh");
      Files.writeString(scriptFile, stubbed, StandardCharsets.UTF_8);

      Result result = runStep(scriptFile, checkout, work, origin);

      assertEquals(
          0,
          result.exit(),
          "the composed release step failed.\n--- script ---\n" + script + "\n--- output ---\n"
              + result.output());

      // 4. WHAT THE POSTLUDE ASKED THE CLI TO DO. An exit code says the script did not stop; the
      // recorded calls say it submitted the declared coordinate and then asked for it back, in
      // that order, with exactly the arguments the pinned binary's own usage names (step 2).
      Path record = work.resolve("cli-record");
      assertEquals(
          List.of(
              "artifacts publish sbom submit --type "
                  + SBOM_TYPE
                  + " --name "
                  + SBOM_NAME
                  + " --version "
                  + RELEASE_VERSION
                  + " --file "
                  + SBOM_PATH,
              "artifacts publish exists sbom " + SBOM_TYPE + "/" + SBOM_NAME + " " + RELEASE_VERSION),
          Files.readAllLines(record.resolve("calls")),
          "the composed postlude's calls to the CLI");
      // The trailing newline is the heredoc's, and it is asserted rather than trimmed away: what the
      // step wrote is what the CLI was handed, byte for byte.
      assertArrayEquals(
          (SBOM_BODY + "\n").getBytes(StandardCharsets.UTF_8),
          Files.readAllBytes(record.resolve("submitted")),
          "the document the step wrote is the document handed to the CLI");

      // 5. AND IT WAS THE PINNED COORDINATE THAT WAS FETCHED. The prelude asked for exactly the
      // coordinate the pom names, which is the one step 1 found in the real store.
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
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET();
      // On an EDGE runner this reaches the registry through the public edge, which refuses an
      // anonymous read; QITS_TOKEN is the step's own job token, and unset on the internal plane.
      QitsTokenAuth.addIfPresent(request);
      HttpResponse<byte[]> answer =
          client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
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

  /**
   * The pinned binary's own usage for the two commands the composed postlude calls, asserted to
   * name each command and every option the postlude passes it.
   *
   * <p><b>Why it is asked rather than made to publish (qits-731).</b> This test used to run the
   * pinned binary's real {@code sbom submit} against a stub store it found through
   * {@code $QITS_ARTIFACTS_URL}. A CLI from qits-731 on reads no URL variable at all: it derives
   * {@code https://registry.qits.$QITS_DOMAIN} in code, and its only seam is a field a step cannot
   * set — so a live publish could only ever reach the real, immutable sbom store. The command
   * surface is what the pin can break between this reactor's composed text and the binary, so that
   * is what is held, for the current CLI and the next one alike. {@code --help} is answered by the
   * argument parser before any command body runs, and the environment holds nothing but a domain
   * that cannot resolve.
   *
   * <p>A missing subcommand is NOT an exit code: the parser answers {@code --help} on an unknown
   * name with the nearest parent's usage and 0. So the usage's own first line is what is read.
   */
  private static void pinnedBinaryCarriesThePostludesCommands(byte[] binary, Path dir)
      throws Exception {
    Files.createDirectories(dir);
    Path cli = dir.resolve("qits");
    Files.write(cli, binary);
    assertTrue(cli.toFile().setExecutable(true), "the pinned binary could not be made executable");

    String submit = usage(cli, dir, "artifacts", "publish", "sbom", "submit", "--help");
    assertTrue(
        submit.startsWith("Usage: qits artifacts publish sbom submit"),
        "the pinned CLI carries `artifacts publish sbom submit`:\n" + submit);
    for (String option : List.of("--type", "--name", "--version", "--file")) {
      assertTrue(
          submit.contains(option + "="),
          "the pinned CLI's `sbom submit` takes " + option + ", which the postlude passes:\n"
              + submit);
    }

    String exists = usage(cli, dir, "artifacts", "publish", "exists", "--help");
    assertTrue(
        exists.startsWith("Usage: qits artifacts publish exists"),
        "the pinned CLI carries `artifacts publish exists`:\n" + exists);
    assertTrue(
        exists.contains("sbom"),
        "the pinned CLI's `exists` answers for an sbom coordinate:\n" + exists);
  }

  private static String usage(Path cli, Path dir, String... args) throws Exception {
    List<String> argv = new ArrayList<>();
    argv.add(cli.toAbsolutePath().toString());
    argv.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(argv).directory(dir.toFile());
    Map<String, String> env = HermeticEnvironment.of(builder);
    env.put("HOME", dir.toAbsolutePath().toString());
    env.put("QITS_DOMAIN", UNRESOLVABLE_DOMAIN);
    Path log = dir.resolve("usage.log");
    Process process =
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(log.toFile())).start();
    if (!process.waitFor(1, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      fail("`qits " + String.join(" ", args) + "` did not answer within a minute:\n" + read(log));
    }
    String output = read(log);
    assertEquals(0, process.exitValue(), "`qits " + String.join(" ", args) + "`:\n" + output);
    return output;
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
   * <p><b>Every ambient variable but PATH and HOME is removed first ({@link HermeticEnvironment}),
   * and that is the difference between a test and a coincidence.</b> {@code ProcessBuilder} seeds
   * the child from this process's environment, and this process runs somewhere that has opinions: a
   * workspace container carries a commissioned credential and a full set of platform addresses, a
   * CI step container carries another. A pin test whose result depends on where it runs proves nothing about the pin — so the
   * child is handed exactly the variables the composed text reads, plus an unresolvable
   * {@code QITS_DOMAIN} and the stand-in's record directory, and nothing arrives from the host.
   *
   * <p>{@code GIT_CONFIG_GLOBAL} points at a scratch file for the same reason: the running user's
   * own git configuration is not part of what a step container has.
   */
  private static Result runStep(Path script, Path checkout, Path work, Path origin)
      throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder("bash", script.toAbsolutePath().toString()).directory(checkout.toFile());
    Map<String, String> env = HermeticEnvironment.of(builder);
    env.put("QITS_ARTIFACTS_CLI_PACKAGE", PlatformAccessCliBinary.DAEMON_NAME);
    env.put("QITS_ARTIFACTS_CLI_VERSION", PlatformAccessCliBinary.VERSION);
    env.put("QITS_VERSION", RELEASE_VERSION);
    env.put("QITS_CI_REPOSITORY_URL", origin.toAbsolutePath().toString());
    env.put("GIT_CONFIG_GLOBAL", work.resolve("gitconfig").toAbsolutePath().toString());
    env.put("HOME", work.toAbsolutePath().toString());
    // The one address input a step is told, pointed at nothing: the CLI download was moved onto the
    // stub in the text, the lockfile check finds no lockfile in the scratch checkout, and nothing
    // else in the composed text may dial a host derived from it.
    env.put("QITS_DOMAIN", UNRESOLVABLE_DOMAIN);
    // Where the stand-in CLI records the calls it was asked to make.
    env.put("QITS_PIN_IT_RECORD", work.resolve("cli-record").toAbsolutePath().toString());

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
   * qits-artifacts, as much of it as the composed prelude touches: the daemons route it downloads
   * the CLI from. It serves the recording stand-in rather than the pinned bytes — the pinned bytes
   * are checked by {@link #pinnedBinaryCarriesThePostludesCommands} — and records which coordinate
   * was asked for. There is no sbom route any more: nothing in this test publishes.
   */
  private static final class StubStore implements AutoCloseable {

    private final HttpServer server;
    private final List<String> gets = new ArrayList<>();

    StubStore(byte[] served) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/artifacts/daemons/", exchange -> daemon(exchange, served));
      server.start();
    }

    private void daemon(HttpExchange exchange, byte[] served) throws IOException {
      synchronized (gets) {
        gets.add(exchange.getRequestURI().getPath());
      }
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, served.length);
      try (var body = exchange.getResponseBody()) {
        body.write(served);
      }
    }

    String base() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
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
  }
}
