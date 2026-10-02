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
 * names, packaged into this qits-ci.
 *
 * <p><b>qits-ci owns them, like the release archetypes.</b> {@code ci/pom.xml} builds
 * qits-ci-service's own {@code .config/qits/platform-pipelines/*.yml} into this jar as {@code
 * platform-pipelines/<name>.yml}, and {@link CiEventTriggerService} evaluates them for every
 * arriving event. Until 2026-10-02 they were {@code ci-platform-event-*.yml} files in the wrapper,
 * read at its {@code main} head per event: a pipeline fix then shipped only with a wrapper release,
 * which needs a person's approval, and no repository's release ever exercised it. Packaged, a
 * pipeline moves with a qits-ci release. No repository is read for one any more.
 *
 * <p>Each file is recorded on its runs under its path in qits-ci-service, {@code
 * .config/qits/platform-pipelines/<name>.yml}, the way a packaged archetype is.
 *
 * <p><b>The set is fixed per build</b> ({@link #PACKAGED}), checked at boot, and read once. In the
 * native image the files have to be named to be bundled: {@code quarkus.native.resources.includes}
 * in {@code service}.
 */
@ApplicationScoped
public class CiPlatformPipelines {

  /** Where the packaged pipelines sit on the classpath: {@code ci/pom.xml}'s {@code targetPath}. */
  static final String PACKAGED_DIR = "platform-pipelines/";

  /** The path a packaged pipeline is recorded under: its file in qits-ci-service. */
  static final String CONFIG_DIR = ".config/qits/platform-pipelines/";

  /**
   * Every pipeline this build carries. {@code PackagedPlatformPipelinesTest} holds it equal to the
   * {@code *.yml} files in {@code .config/qits/platform-pipelines/}.
   */
  static final Set<String> PACKAGED = Set.of("maintenance-bump", "screenshot-baselines");

  private volatile List<EventTriggerFile> files;

  /** A test's own set, or null for the packaged one. */
  private volatile List<EventTriggerFile> override;

  void onStart(@Observes StartupEvent startup) {
    files();
  }

  /** The pipelines, in name order. Throws when a packaged file is missing: a broken build. */
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
      String resource = PACKAGED_DIR + name + ".yml";
      try (InputStream in = open(resource)) {
        if (in == null) {
          throw new IllegalStateException(
              "the platform pipeline " + resource + " is not packaged into this qits-ci");
        }
        loaded.add(
            new EventTriggerFile(
                CONFIG_DIR + name + ".yml", new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      } catch (IOException e) {
        throw new IllegalStateException("could not read the platform pipeline " + resource, e);
      }
    }
    return List.copyOf(loaded);
  }

  private static InputStream open(String resource) {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    InputStream in = context == null ? null : context.getResourceAsStream(resource);
    return in != null ? in : CiPlatformPipelines.class.getClassLoader().getResourceAsStream(resource);
  }
}
