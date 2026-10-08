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
import java.util.Set;
import java.util.TreeSet;

/**
 * The platform pipelines: event pipelines that act on whichever repository the event's payload
 * names, this module's own classpath resources rather than a repository's committed config.
 *
 * <p><b>qits-ci owns them, like the release archetypes.</b> They are ordinary files of this module
 * at {@code ci/src/main/resources/platform-pipelines/*.yml}, carried onto the jar's classpath by
 * {@code src/main/resources}'s own unfiltered declaration in {@code ci/pom.xml} — no copying out of
 * another directory, no {@code targetPath} — and {@link CiEventTriggerService} evaluates them for
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
  static final Set<String> AUTOMATIONS = Set.of("entity-diagram", "screenshot-baselines");

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
      loaded.add(new EventTriggerFile(CONFIG_DIR + name + ".yml", read(PACKAGED_DIR + name + ".yml")));
    }
    for (String kind : new TreeSet<>(AUTOMATIONS)) {
      String path = CONFIG_DIR + AUTOMATIONS_DIR + kind + ".yml";
      String content = read(PACKAGED_DIR + AUTOMATIONS_DIR + kind + ".yml");
      loaded.add(new EventTriggerFile(path, CiAutomationComposer.compose(kind, path, content)));
    }
    return List.copyOf(loaded);
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
