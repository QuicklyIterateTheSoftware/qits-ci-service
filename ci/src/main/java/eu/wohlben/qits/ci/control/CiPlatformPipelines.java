package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The platform pipelines: event pipelines that act on whichever repository the event's payload
 * names, this module's own classpath resources rather than a repository's committed config.
 *
 * <p><b>qits-ci owns them, like the release archetypes.</b> They are ordinary files of this module
 * at {@code ci/src/main/resources/platform-pipelines/*.yml}, carried onto the jar's classpath by
 * Maven's default, unfiltered {@code src/main/resources} — no copying out of another directory, no
 * {@code targetPath} — and {@link CiEventTriggerService} evaluates them for
 * every arriving event. Until 2026-10-02 they were {@code ci-platform-event-*.yml} files in the
 * wrapper, read at its {@code main} head per event: a pipeline fix then shipped only with a wrapper
 * release, which needs a person's approval, and no repository's release ever exercised it. Packaged,
 * a pipeline moves with a qits-ci release. No repository is read for one any more.
 *
 * <p><b>They do not live under {@code .config/qits/}, and that absence is deliberate (qits-1077).</b>
 * That directory is a repository's own configuration — a committed trigger file or archetype a
 * person owns — and a change there is held for a person's approval by qits-projects' {@code
 * ApprovalPolicy}. These files are platform code: qits-ci's own behaviour, not a declaration one of
 * its repositories makes about itself. So they sit beside the Java that reads them and are gated the
 * way any other source change to this module is, rather than by a policy built for someone else's
 * config.
 *
 * <p>Each file is recorded on its runs under its real path in qits-ci-service, {@code
 * ci/src/main/resources/platform-pipelines/<name>.yml} — the same path on disk and the one a run's
 * {@code configPath} states, with no second packaged-only name standing in for it.
 *
 * <p><b>A second source: the automation kind files.</b> Every {@code
 * ci/src/main/resources/platform-pipelines/automations/<kind>.yml} is passed through {@link
 * CiAutomationComposer}, which turns its {@code image}, {@code timeout-seconds}, {@code script} and
 * optional {@code qits-cli} into a {@code ReleaseRequestAutomation} trigger selecting {@code kind:
 * <kind>}. The run is recorded under the kind file's path. A kind file the composer refuses is a
 * boot error naming it, like a missing file.
 *
 * <p><b>A packaged pipeline may ask for the qits CLI the same way a kind file does</b> (qits-893):
 * a top-level {@code qits-cli: true} prepends {@link CiReleaseComposer#cliFetch}'s {@code
 * AUTOMATION} text — hard on every failure, the pinned binary onto {@code PATH} — to every step
 * script ({@link #withQitsCli}). The key is the file's and not the trigger schema's, so it is
 * stripped before {@link CiEventTriggerParser}, which stays strict about unknown top-level keys,
 * ever sees the text; one download text for every caller, never a copy pasted into the YAML.
 *
 * <p><b>The set is fixed per build</b> ({@link #PACKAGED} and {@link #AUTOMATIONS}), checked at
 * boot, and read once. In the native image the files have to be named to be bundled: {@code
 * quarkus.native.resources.includes} in {@code service}.
 */
@ApplicationScoped
public class CiPlatformPipelines {

  /**
   * Where the packaged pipelines sit on the classpath: the {@code platform-pipelines/} subdirectory
   * of this module's own {@code src/main/resources}.
   */
  static final String PACKAGED_DIR = "platform-pipelines/";

  /** The path a packaged pipeline is recorded under: its real path in qits-ci-service. */
  static final String CONFIG_DIR = "ci/src/main/resources/platform-pipelines/";

  /**
   * Every pipeline this build carries. {@code PackagedPlatformPipelinesTest} holds it equal to the
   * {@code *.yml} files in {@code ci/src/main/resources/platform-pipelines/}.
   */
  static final Set<String> PACKAGED = Set.of("maintenance-bump");

  /** Where the automation kind files sit, under {@link #PACKAGED_DIR} and {@link #CONFIG_DIR}. */
  static final String AUTOMATIONS_DIR = "automations/";

  /**
   * Every automation kind this build carries, each composed by {@link CiAutomationComposer}. {@code
   * PackagedPlatformPipelinesTest} holds it equal to the {@code *.yml} files in {@code
   * ci/src/main/resources/platform-pipelines/automations/}.
   */
  static final Set<String> AUTOMATIONS =
      Set.of("dependency-bump", "entity-diagram", "screenshot-baselines");

  private volatile List<EventTriggerFile> files;

  /** A test's own set, or null for the packaged one. */
  private volatile List<EventTriggerFile> override;

  void onStart(@Observes StartupEvent startup) {
    files();
  }

  /**
   * The pipelines: the packaged ones in name order, then the composed automations in kind order.
   * Throws when a packaged file is missing or a kind file is refused: a broken build.
   */
  public List<EventTriggerFile> files() {
    List<EventTriggerFile> armed = override;
    if (armed != null) {
      return armed;
    }
    List<EventTriggerFile> loaded = files;
    if (loaded == null) {
      loaded = load();
      files = loaded;
    }
    return loaded;
  }

  /**
   * Replaces the set for one test. A method rather than a field write: the bean is normal-scoped,
   * so a test holds a client proxy. {@code null} restores the packaged set.
   */
  void override(List<EventTriggerFile> files) {
    this.override = files == null ? null : List.copyOf(files);
  }

  private static List<EventTriggerFile> load() {
    List<EventTriggerFile> loaded = new ArrayList<>();
    for (String name : new TreeSet<>(PACKAGED)) {
      String path = CONFIG_DIR + name + ".yml";
      loaded.add(new EventTriggerFile(path, withQitsCli(path, read(PACKAGED_DIR + name + ".yml"))));
    }
    for (String kind : new TreeSet<>(AUTOMATIONS)) {
      String path = CONFIG_DIR + AUTOMATIONS_DIR + kind + ".yml";
      String content = read(PACKAGED_DIR + AUTOMATIONS_DIR + kind + ".yml");
      loaded.add(new EventTriggerFile(path, CiAutomationComposer.compose(kind, path, content)));
    }
    return List.copyOf(loaded);
  }

  /** A top-level {@code qits-cli: true} line, the one spelling {@link #withQitsCli} strips. */
  private static final Pattern QITS_CLI_LINE =
      Pattern.compile("(?m)^" + CiAutomationComposer.QITS_CLI_KEY + ":[ \\t]*true[ \\t]*\\n");

  /** A step's {@code script: |} line, and the indentation of the block's first line after it. */
  private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?m)^ +script: \\|\\n( +)");

  /** The comment the fetch is introduced with, as the automation composer's is. */
  static final String QITS_CLI_HEADER =
      "# --- the qits CLI, pinned, on PATH: this pipeline declares qits-cli: true ---\n";

  /**
   * A packaged pipeline as the trigger parser is handed it: unchanged without a top-level {@code
   * qits-cli}, and with {@code qits-cli: true} the line stripped and {@link #QITS_CLI_HEADER} plus
   * the {@code AUTOMATION} CLI fetch prepended to every step's {@code script: |} block, indented as
   * the block is.
   *
   * <p>A text edit rather than a parse and a re-emit, so the comments in the file — which say why
   * every line of a bump is the way it is — survive into the stored trigger document. The edit is
   * then checked through the real parser: every step's script must start with exactly the fetch
   * for its own image, or this throws and the boot fails, so a step spelled some other way ({@code
   * script: >}, an inline scalar) is a broken build rather than a step that runs without its CLI.
   * Any value of {@code qits-cli} but {@code true} is refused the same way, as a kind file's is.
   */
  static String withQitsCli(String path, String content) {
    Map<?, ?> root = CiConfigSchema.load(content, true);
    Object flag = root == null ? null : root.get(CiAutomationComposer.QITS_CLI_KEY);
    if (flag == null) {
      return content;
    }
    if (!Boolean.TRUE.equals(flag)) {
      throw new IllegalStateException(
          path + ": " + CiAutomationComposer.QITS_CLI_KEY + " is only ever true; got " + flag);
    }
    Matcher line = QITS_CLI_LINE.matcher(content);
    if (!line.find()) {
      throw new IllegalStateException(
          path + ": declare the CLI as a line of its own, '" + CiAutomationComposer.QITS_CLI_KEY
              + ": true'");
    }
    String stripped = content.substring(0, line.start()) + content.substring(line.end());
    List<?> steps = root.get("steps") instanceof List<?> list ? list : List.of();
    List<String> images = new ArrayList<>();
    for (Object step : steps) {
      Object image = step instanceof Map<?, ?> map ? map.get("image") : null;
      images.add(image == null ? "" : String.valueOf(image));
    }
    StringBuilder out = new StringBuilder();
    Matcher block = SCRIPT_BLOCK.matcher(stripped);
    int at = 0;
    int index = 0;
    while (block.find()) {
      if (index >= images.size()) {
        throw new IllegalStateException(
            path + ": more 'script: |' blocks than steps; qits-cli: true cannot place its fetch");
      }
      String indent = block.group(1);
      out.append(stripped, at, block.start(1));
      for (String fetchLine : fetch(images.get(index)).split("\n")) {
        out.append(indent).append(fetchLine).append('\n');
      }
      at = block.start(1);
      index++;
    }
    out.append(stripped.substring(at));
    String composed = out.toString();
    List<CiPipeline.CiStepDecl> parsed =
        new CiEventTriggerParser().parse(path, composed).pipeline().steps();
    for (int i = 0; i < parsed.size(); i++) {
      CiPipeline.CiStepDecl step = parsed.get(i);
      if (!step.script().startsWith(fetch(step.image()))) {
        throw new IllegalStateException(
            path + ": step " + i + " does not start with the qits CLI fetch qits-cli: true"
                + " promises — write its script as a 'script: |' block");
      }
    }
    return composed;
  }

  /** What {@link #withQitsCli} prepends to a step on {@code image}. */
  static String fetch(String image) {
    StringBuilder out = new StringBuilder(QITS_CLI_HEADER);
    CiReleaseComposer.cliFetch(out, image, CiReleaseComposer.CliFetch.AUTOMATION);
    return out.toString();
  }

  /** One packaged file's text. Throws when it is missing: a broken build. */
  static String read(String resource) {
    try (InputStream in = open(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "the platform pipeline " + resource + " is not packaged into this qits-ci");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read the platform pipeline " + resource, e);
    }
  }

  private static InputStream open(String resource) {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    InputStream in = context == null ? null : context.getResourceAsStream(resource);
    return in != null ? in : CiPlatformPipelines.class.getClassLoader().getResourceAsStream(resource);
  }
}
