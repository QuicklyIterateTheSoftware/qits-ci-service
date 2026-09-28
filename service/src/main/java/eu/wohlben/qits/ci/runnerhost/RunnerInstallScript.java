package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.entity.CiRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * How a runner is installed, in two parts that never meet on this side: the <b>generic script</b>
 * and the <b>install line</b>.
 *
 * <p>The generic script is {@code runner-install.sh.tmpl} with the pinned runner image filled in —
 * {@link RunnerAddresses#runnerImage}, the same reference an {@code Upgrade} names — and nothing
 * else: no runner id, no token, no secret. {@code GET /ci/api/runners/install.sh} serves it. It logs
 * in to the image's registry with the registration token, pulls the image, removes every container
 * of the runner and starts one ({@code docker run -d}) with the four values; there is no systemd
 * unit, no binary on the host's disk and no env file any more (qits-484). The install line is what the create and
 * a rotation answer as {@code installScript}: one line to paste, which fetches that script with the
 * registration token and pipes it into {@code sudo env <the four values> sh}. Piped, the script runs
 * in a process of its own, so its {@code set -eu} refusing ends that process and never the shell
 * the line was pasted into — which a pasted multi-line script did. The token is in the line twice,
 * as the fetch's bearer and as the script's value, and in no other body anywhere.
 *
 * <p><b>The template's shape is a contract with another repository.</b> qits-ci-runner-daemon's
 * {@code scripts/test-install-contract.sh} runs one rendering of it (its {@code
 * scripts/fixtures/runner-install.sh}) against a stub docker with the four values in its
 * environment, as the line runs it, and checks the container it starts against the runner's
 * container contract. Change the template and that fixture moves with it.
 *
 * <p><b>Every value lands inside single quotes</b> — in the line, and in the script's own
 * assignment — and in an argument of {@code docker}, so each is held to a charset that is literal
 * in all of them: no quote, backslash, whitespace, {@code $} or brace.
 * A value outside it is refused here rather than rendered into something that parses differently on
 * a root shell. The config-side values are checked by {@link #requireRenderable()} before anything
 * is minted; the token by {@link #requireCarriable(String)} straight after, while it can still be
 * given back. The script checks the four values again on the host, since there they arrive from an
 * environment rather than from this class.
 */
@ApplicationScoped
public class RunnerInstallScript {

  /** The template, on the classpath root (and in {@code quarkus.native.resources.includes}). */
  static final String TEMPLATE = "runner-install.sh.tmpl";

  /** Where the generic script is served, under the CI base. */
  public static final String PATH = "/ci/api/runners/install.sh";

  private static final Pattern URL =
      Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?");

  /** {@code qits_tok_} plus base64 or base64url, and nothing a quote or a line could trip on. */
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{1,1024}");

  /** A docker tag: what the pinned version becomes in the image reference. */
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

  /** A registry authority, {@code host[:port]}, as docker reads the first segment of a reference. */
  private static final Pattern REGISTRY = Pattern.compile("[A-Za-z0-9.-]+(:[0-9]{1,5})?");

  private static final String TEXT = load();

  @Inject RunnerAddresses addresses;

  @Inject CiRunnerPins pins;

  /** The fixed values of one install line; the token is kept out of {@link #toString()}. */
  public record Line(String ciUrl, UUID runnerId, String registrationToken, int slots) {
    @Override
    public String toString() {
      return "Line[runnerId=" + runnerId + ", ciUrl=" + ciUrl + "]";
    }
  }

  /**
   * Refuses — before a token is minted — when this deployment's addresses or pinned version could
   * not be rendered, naming the key to fix.
   */
  public void requireRenderable() {
    require(
        URL,
        addresses.ciBase(),
        "the CI base (QITS_DOMAIN / qits.ci.runner.public-url / qits.ci.runner.internal-url)");
    require(
        REGISTRY,
        addresses.registryHost(),
        "the registry host (QITS_DOMAIN / qits.ci.runner.artifacts-url /"
            + " qits.artifacts.registry-host)");
    require(VERSION, pins.version(), CiRunnerPins.OVERRIDE_KEY + " / the pinned protocol version");
  }

  /** Refuses a token qits-idp minted that the line could not carry verbatim. */
  public static void requireCarriable(String token) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new IllegalArgumentException(
          "qits-idp answered a registration token the install line cannot carry verbatim");
    }
  }

  /** The generic script, naming the pinned runner image on this deployment's registry. */
  public String generic() {
    return generic(addresses.registryHost(), pins.version());
  }

  /** The line for this runner and this token, dialling this deployment's CI base. */
  public String line(CiRunner runner, String registrationToken) {
    return line(new Line(addresses.ciBase(), runner.id, registrationToken, runner.slots));
  }

  /**
   * The template with its one placeholder, the image, replaced: {@code
   * <registryHost>/qits/qits-ci-runner:<runnerVersion>}, composed exactly as {@link
   * RunnerAddresses#runnerImage} composes it for an {@code Upgrade}.
   */
  public static String generic(String registryHost, String runnerVersion) {
    require(REGISTRY, registryHost, "the registry host");
    require(VERSION, runnerVersion, "the runner version");
    String image = registryHost + "/" + RunnerAddresses.RUNNER_IMAGE_REPOSITORY + ":" + runnerVersion;
    String script = fill(TEXT, "IMAGE", image);
    if (script.contains("{{")) {
      throw new IllegalStateException(TEMPLATE + " has a placeholder nothing fills");
    }
    return script;
  }

  /**
   * The one line to paste. Slots are at least 1: a drained row (0) is still a runner that should
   * connect, the runner refuses {@code QITS_CI_RUNNER_SLOTS=0} outright, and the row's own number is
   * what holds it once it says hello whatever its env says.
   */
  public static String line(Line values) {
    require(URL, values.ciUrl(), "the CI base");
    requireCarriable(values.registrationToken());
    if (values.runnerId() == null) {
      throw new IllegalArgumentException("A runner install line needs the runner's id");
    }
    String base = values.ciUrl().replaceAll("/+$", "");
    String token = values.registrationToken();
    return "curl -fsSL -H 'Authorization: Bearer "
        + token
        + "' "
        + base
        + PATH
        + " | sudo env QITS_CI_RUNNER_URL='"
        + base
        + "' QITS_CI_RUNNER_ID='"
        + values.runnerId()
        + "' QITS_CI_RUNNER_REGISTRATION_TOKEN='"
        + token
        + "' QITS_CI_RUNNER_SLOTS='"
        + Math.max(1, values.slots())
        + "' sh";
  }

  private static String fill(String script, String name, String value) {
    String marker = "{{" + name + "}}";
    if (!script.contains(marker)) {
      throw new IllegalStateException(TEMPLATE + " has no " + marker);
    }
    return script.replace(marker, value);
  }

  private static void require(Pattern shape, String value, String what) {
    if (value == null || !shape.matcher(value).matches()) {
      throw new IllegalStateException(
          "A runner install script cannot carry " + what + " '" + value + "'");
    }
  }

  private static String load() {
    try (InputStream in =
        RunnerInstallScript.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
      if (in == null) {
        throw new IllegalStateException(TEMPLATE + " is not on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + TEMPLATE, e);
    }
  }
}
