package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.entity.CiRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The install script a person pastes on a runner host: {@code runner-install.sh.tmpl} with the
 * runner's id and slots, its registration token, where to dial qits-ci and where to download the
 * binary, and which version of it. The answer to a create and to a rotation, and the only place the
 * token's value leaves this service.
 *
 * <p><b>The template's shape is a contract with another repository.</b> qits-ci-runner-daemon's
 * {@code scripts/test-install-contract.sh} runs one rendering of it (its {@code
 * scripts/fixtures/runner-install.sh}) against stubs, and its {@code packaging/qits-ci-runner.service}
 * is embedded in it byte for byte. Change the template and that fixture moves with it.
 *
 * <p><b>Every value lands inside a single-quoted sh assignment and on a {@code KEY=value} line of a
 * systemd {@code EnvironmentFile}</b>, so each is held to a charset that is literal in both — no
 * quote, backslash, whitespace, {@code $} or brace — and a value outside it is refused here rather
 * than rendered into a script that parses as something else on a root shell. The config-side values
 * are checked by {@link #requireRenderable()} before anything is minted; the token by {@link
 * #requireCarriable(String)} straight after, while it can still be given back.
 */
@ApplicationScoped
public class RunnerInstallScript {

  /** The template, on the classpath root (and in {@code quarkus.native.resources.includes}). */
  static final String TEMPLATE = "runner-install.sh.tmpl";

  private static final Pattern URL =
      Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?");

  /** {@code qits_tok_} plus base64 or base64url, and nothing a quote or a line could trip on. */
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{1,1024}");

  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

  private static final String TEXT = load();

  @Inject RunnerAddresses addresses;

  @Inject CiRunnerPins pins;

  /** The fixed values of one rendering; the token is kept out of {@link #toString()}. */
  public record Values(
      String ciUrl,
      UUID runnerId,
      String registrationToken,
      int slots,
      String artifactsUrl,
      String runnerVersion) {
    @Override
    public String toString() {
      return "Values[runnerId=" + runnerId + ", ciUrl=" + ciUrl + "]";
    }
  }

  /**
   * Refuses — before a token is minted — when this deployment's addresses or pinned version could
   * not be rendered, naming the key to fix.
   */
  public void requireRenderable() {
    require(URL, addresses.ciBase(), "qits.ci.runner.public-url / qits.ci.runner.internal-url");
    require(
        URL,
        addresses.artifactsBase(),
        "qits.ci.runner.artifacts-url / qits.ci.runner.artifacts-internal-url");
    require(VERSION, pins.version(), CiRunnerPins.OVERRIDE_KEY + " / the pinned protocol version");
  }

  /** Refuses a token qits-idp minted that the script could not carry verbatim. */
  public static void requireCarriable(String token) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new IllegalArgumentException(
          "qits-idp answered a registration token the install script cannot carry verbatim");
    }
  }

  /** The script for this runner and this token, with this deployment's addresses and version. */
  public String render(CiRunner runner, String registrationToken) {
    return render(
        new Values(
            addresses.ciBase(),
            runner.id,
            registrationToken,
            runner.slots,
            addresses.artifactsBase(),
            pins.version()));
  }

  /**
   * The template with every placeholder replaced. Slots are at least 1: a drained row (0) is still
   * a runner that should connect, the runner refuses {@code QITS_CI_RUNNER_SLOTS=0} outright, and
   * the row's own number is what holds it once it says hello whatever its env says.
   */
  public static String render(Values values) {
    require(URL, values.ciUrl(), "the CI base");
    require(URL, values.artifactsUrl(), "the artifacts base");
    require(VERSION, values.runnerVersion(), "the runner version");
    requireCarriable(values.registrationToken());
    if (values.runnerId() == null) {
      throw new IllegalArgumentException("A runner install script needs the runner's id");
    }
    Map<String, String> placeholders = new LinkedHashMap<>();
    placeholders.put("CI_URL", values.ciUrl());
    placeholders.put("RUNNER_ID", values.runnerId().toString());
    placeholders.put("REGISTRATION_TOKEN", values.registrationToken());
    placeholders.put("SLOTS", Integer.toString(Math.max(1, values.slots())));
    placeholders.put("ARTIFACTS_URL", values.artifactsUrl());
    placeholders.put("RUNNER_VERSION", values.runnerVersion());
    String script = TEXT;
    for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
      String marker = "{{" + placeholder.getKey() + "}}";
      if (!script.contains(marker)) {
        throw new IllegalStateException(TEMPLATE + " has no " + marker);
      }
      script = script.replace(marker, placeholder.getValue());
    }
    if (script.contains("{{")) {
      throw new IllegalStateException(TEMPLATE + " has a placeholder nothing fills");
    }
    return script;
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
