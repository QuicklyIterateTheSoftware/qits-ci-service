package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigSource.FileLookup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Resolves one archetype recipe: the repository's own copy first, and otherwise the one packaged
 * into this qits-ci.
 *
 * <p><b>Two steps, in this order, and no third.</b> {@code archetype: <name>} in a repository's
 * {@code .config/qits/release.yml} is answered by
 *
 * <ol>
 *   <li><b>the local recipe</b> — {@code .config/qits/release-archetypes/<name>.yml} in the
 *       repository the run is for, read through {@link CiConfigSource#readFile} at <b>the same
 *       revision its {@code release.yml} was read at</b> (the fold for a {@code
 *       ReleaseRequestChanged}, the released tag's commit for an {@code SCMRelease}). For the
 *       repository that keeps the packaged set (qits-ci-service in the platform project, {@code
 *       qits.ci.release-archetypes.source-*}) the local recipe is that set's own source file, {@link #SOURCE_DIR}{@code
 *       <name>.yml}, at the same revision;
 *   <li><b>the packaged recipe</b> — the classpath resource {@code release-archetypes/<name>.yml},
 *       this module's own {@code src/main/resources/release-archetypes/}.
 * </ol>
 *
 * <p><b>The packaged set is not under {@code .config/qits/}, and that is deliberate (qits-1155).</b>
 * That directory is a repository's own configuration, and qits-projects holds every change there
 * for a person's approval. The defaults are the platform's templates, not qits-ci-service's own
 * configuration, so they sit beside the Java that reads them, like {@link CiPlatformPipelines}.
 * qits-ci-service still exercises its branch's copy of a recipe before it ships — the source file is
 * its local recipe — and a shadow in any other repository is still a {@code .config/qits/} file, so
 * a repository's deviation from a default is still a change a person approves.
 *
 * <p><b>No other repository is read for a recipe.</b> Until qits-583 the recipe came from the
 * platform-pipelines repository (the wrapper) at the sha of its newest released tag. That made a
 * recipe fix live only after a wrapper release — which needs a person's approval and is also what
 * resolves a workspace, so it could ship only as the last step of a ticket — and it meant no CI run
 * ever executed a changed recipe before it shipped, since the wrapper's own {@code release.yml}
 * names no archetype. Packaged, a recipe moves with a qits-ci release, and qits-ci-service's own
 * release request reads {@code java-service}'s source file at its fold, so that one recipe is exercised
 * by the release that carries it. The other seven are held by {@code
 * PackagedReleaseArchetypesTest}.
 *
 * <p><b>Shadowing is the design, and it grants nothing a branch could not already do.</b> Any
 * repository may carry its own copy of a packaged archetype, or one of its own invention. What is
 * <em>platform process</em> in a composed pipeline — the prelude and the postlude — lives in {@link
 * CiReleaseComposer}, in Java, and no recipe can touch it; an archetype contributes only slot
 * steps, {@code artifacts:} and {@code userflows:}, every one of which a repository can already
 * replace wholesale in its own {@code release.yml}. So reading a recipe at the revision under test
 * hands that revision no power it did not have.
 *
 * <p><b>Three answers, and they must stay apart</b> ({@link Status}) — {@link
 * CiConfigSource.FileLookup}'s three, one seam up:
 *
 * <ul>
 *   <li>{@link Status#FOUND} — a recipe, and which one ({@link ArchetypeRef}).
 *   <li>{@link Status#UNKNOWN} — there is no usable recipe of that name, and asking again cannot
 *       change it: the name is in neither place, or the local file is there and will not parse.
 *       Committed bytes; final.
 *   <li>{@link Status#UNREADABLE} — the local read came back {@code UNREACHABLE}. Nothing was
 *       learned, so the caller leaves its event owed.
 * </ul>
 *
 * <p><b>Neither failure of the local read falls through to the packaged copy, and both refusals
 * are deliberate.</b> A local file that will not parse is a broken shadow: silently composing from
 * the platform's recipe instead would run a pipeline the repository explicitly replaced, green, with
 * nothing to say the replacement was ignored. And a local read that could not be made says nothing
 * about whether a shadow exists — answering with the packaged copy on a git-host blip would compose
 * a different pipeline for the same commit depending on the weather.
 *
 * <p><b>The packaged recipes are immutable for the life of the process</b>, so each is parsed at
 * most once, on first use, and kept. {@code ci/} gains no {@code java.net.http} for it: the read is
 * {@code getResourceAsStream}. In the native image the resources have to be named to be bundled —
 * {@code quarkus.native.resources.includes} in the {@code service} module — and a binary built
 * without that answers {@link Status#UNKNOWN} for every name nobody shadows.
 */
@ApplicationScoped
public class CiReleaseArchetypes {

  private static final Logger LOG = Logger.getLogger(CiReleaseArchetypes.class);

  /**
   * Where the packaged recipes sit on the classpath — {@code ci/pom.xml}'s {@code targetPath}. A
   * resource name, so always {@code /}-separated and never leading with one.
   */
  static final String PACKAGED_DIR = "release-archetypes/";

  /**
   * Where the packaged recipes sit in qits-ci-service: this module's own resources. It is the path a
   * packaged recipe is recorded under, and the path qits-ci-service's own release reads its
   * branch's copy at.
   */
  static final String SOURCE_DIR = "ci/src/main/resources/" + PACKAGED_DIR;

  /**
   * The recipes this build <b>must</b> carry — the eight the estate's {@code release.yml} files name.
   *
   * <p><b>It is what {@link #requirePackaged} checks at boot and nothing else</b>: never an
   * allow-list for {@link #read}, which resolves any name a repository carries itself and any
   * further recipe a later build packages. {@code PackagedReleaseArchetypesTest} holds it equal to
   * the {@code *.yml} files in {@link #SOURCE_DIR}, so a ninth file added
   * without naming it here, or a name left here after its file went, is a red build.
   */
  static final Set<String> REQUIRED_PACKAGED =
      Set.of(
          "app", "cli", "daemon", "java-service", "maven-library", "npm-library", "oci",
          "spa-frontend");

  @Inject CiReleaseSlotParser slotParser;

  @Inject CiConfigSource configSource;

  /**
   * This qits-ci's own version, which is what a packaged recipe is recorded as having come from.
   *
   * <p>{@code quarkus.application.version} is the deployable's pom version, and the pom carries
   * the released calver — so two run rows with differing {@code archetype_version} were composed by
   * two qits-ci releases. {@code Optional} rather than a bare string because a missing key must
   * cost a row one column and never the composition: a packaged recipe whose version cannot be
   * named is recorded with a null version, which is honest, where a failed injection would be no
   * release pipeline on the platform at all.
   */
  @ConfigProperty(name = "quarkus.application.version")
  Optional<String> applicationVersion;

  /**
   * The name of the repository that keeps the packaged set — qits-ci-service. Its local recipe is
   * the source file under {@link #SOURCE_DIR}, so its own release request runs its branch's copy.
   *
   * <p><b>The name alone never identifies it</b>: any project may have a repository of that name,
   * and reading <em>its</em> {@link #SOURCE_DIR} would let it run a recipe from outside {@code
   * .config/qits/}, where no approval gate looks. So a match needs this name <b>and</b> one of
   * {@link #sourceProjects} as the reference's project. A reference with no project is never the
   * source. {@code Optional} for the same reason as {@link #applicationVersion}: a missing key
   * costs qits-ci-service its branch copy (it composes from the packaged set), never the
   * composition.
   */
  @ConfigProperty(name = "qits.ci.release-archetypes.source-repository")
  Optional<String> sourceRepository;

  /**
   * The project the source repository must be in: its id and its slug, since a reference may carry
   * either. Missing or empty means no repository is the source.
   */
  @ConfigProperty(name = "qits.ci.release-archetypes.source-project")
  Optional<List<String>> sourceProjects;

  /** Packaged recipes already parsed, by name. Only successes are kept: see {@link #packaged}. */
  private final Map<String, CiReleaseSlots> packagedByName = new ConcurrentHashMap<>();

  /**
   * <b>Which</b> recipe a composed pipeline was built from, with no bytes attached.
   *
   * <p>Four strings that only mean anything together, and they travel a long way: out of this
   * class, through the composition, onto {@code ci_run}'s four columns and out to the API. What
   * they say depends on which of the two steps answered:
   *
   * <table>
   *   <caption>What the four components hold</caption>
   *   <tr><th></th><th>{@code name}</th><th>{@code configPath}</th><th>{@code rev}</th>
   *       <th>{@code version}</th></tr>
   *   <tr><td>local</td><td>the name asked for</td>
   *       <td>{@code .config/qits/release-archetypes/<name>.yml}, or {@link #SOURCE_DIR}{@code
   *       <name>.yml} for qits-ci-service</td>
   *       <td>the revision it was read at — the run's own commit</td><td>null</td></tr>
   *   <tr><td>packaged</td><td>the name asked for</td>
   *       <td>{@link #SOURCE_DIR}{@code <name>.yml}, the file's path in qits-ci-service</td>
   *       <td>null</td><td>this qits-ci's own version</td></tr>
   * </table>
   *
   * <p>So <b>{@code rev} non-null means "shadowed locally"</b>, and a null {@code rev} beside a
   * version means the platform's own recipe as that qits-ci release carried it. All four are null
   * together on a composed run whose slot file names no archetype, which is a legitimate shape
   * ({@link CiReleaseSlots#namesArchetype()}) and never "unknown".
   *
   * <p>The columns are older than this meaning: until qits-583 {@code rev} was a commit of the
   * wrapper repository and {@code version} the wrapper release that commit was. Until qits-1155 a
   * packaged recipe was recorded under {@code .config/qits/release-archetypes/<name>.yml}, where
   * qits-ci-service kept it then. The schema did not
   * move — an applied migration is never edited — so a row from before then reads the old way.
   */
  public record ArchetypeRef(String name, String configPath, String rev, String version) {}

  /** One recipe: which one it is (above), and what it declares. */
  public record Archetype(
      String name, String configPath, String rev, String version, CiReleaseSlots slots) {

    /** The identity half, for a caller that wants to record what was used rather than use it. */
    public ArchetypeRef ref() {
      return new ArchetypeRef(name, configPath, rev, version);
    }
  }

  /** The three answers. Collapsing {@link #UNREADABLE} into either of the others is the bug. */
  public enum Status {
    /** A recipe was resolved, locally or from the packaged set. */
    FOUND,
    /**
     * No usable recipe of that name exists, and that is final: the name is neither in the
     * repository nor packaged, or the repository's own copy does not parse, or the name is not one
     * this qits-ci will build a path from.
     */
    UNKNOWN,
    /** The repository could not be asked whether it carries one. Never an answer; always a retry. */
    UNREADABLE
  }

  /**
   * What one resolution came to.
   *
   * @param status which of the three
   * @param archetype the recipe, on {@link Status#FOUND} and null otherwise
   * @param detail the sentence behind a failure, for a caller that has to put one in front of a
   *     person — null on {@link Status#FOUND}
   */
  public record Resolution(Status status, Archetype archetype, String detail) {

    static Resolution found(Archetype archetype) {
      return new Resolution(Status.FOUND, archetype, null);
    }

    static Resolution unknown(String detail) {
      return new Resolution(Status.UNKNOWN, null, detail);
    }

    static Resolution unreadable(String detail) {
      return new Resolution(Status.UNREADABLE, null, detail);
    }
  }

  /**
   * <b>Refuses to boot a build that does not carry its recipes.</b>
   *
   * <p>The failure this exists for has no other symptom. {@code service/} compiles to a native
   * image, a resource opened by a computed name is bundled only if {@code
   * quarkus.native.resources.includes} names it, and no JVM suite can see that key missing — on a
   * JVM the classpath has the files regardless. A binary without them would deploy, serve, and
   * answer "no such archetype" to every {@code archetype:} nobody shadows: no release-request run
   * for some fifty repositories, each event settled, one WARN apiece. A boot failure instead fails
   * the deployment's health gate, which keeps the previous container.
   *
   * <p><b>A refusal, where {@link RetiredDaemonKeys} beside it only warns</b>, and the difference
   * is the one that class states: its stale key is harmless by construction, while this process
   * starting without its recipes is a platform-wide stop of release QA that looks healthy.
   *
   * <p><b>It runs in every launch mode, test included</b> — no {@code LaunchMode} check. The files
   * are on the classpath of every suite that boots this bean, so there is nothing to skip for, and
   * a guard that skipped test mode would be a guard no test ever ran. It can sit on this bean
   * because nothing this bean injects is mandatory configuration ({@link #applicationVersion} is
   * {@code Optional}), which is the trap {@link RetiredDaemonKeys} records.
   *
   * <p>Eight classpath reads and eight parses, no other IO, and not wasted: each success lands in
   * the cache {@link #packaged} would have filled on first use.
   */
  void onStart(@Observes StartupEvent startup) {
    requirePackaged();
  }

  /** The guard itself, apart from the event so a hand-wired test can call it. */
  void requirePackaged() {
    List<String> unusable = new TreeSet<>(REQUIRED_PACKAGED).stream()
        .filter(name -> packaged(name) == null)
        .toList();
    if (!unusable.isEmpty()) {
      throw new IllegalStateException(
          "This qits-ci does not carry the release archetypes it must package: "
              + unusable
              + " could not be read off the classpath as "
              + PACKAGED_DIR
              + "<name>.yml, or did not parse. Without them every repository naming one composes"
              + " no release pipeline. In a native image, check that"
              + " quarkus.native.resources.includes (service/src/main/resources/"
              + "application.properties) names release-archetypes/*.yml; otherwise check the"
              + " files in ci/src/main/resources/release-archetypes/ and ci/pom.xml's resources.");
    }
  }

  /**
   * Resolves the recipe {@code name} names for one repository at one revision.
   *
   * <p><b>The name guard comes first, before any read.</b> The name is a URL segment in the local
   * read and a classpath segment in the packaged one, and {@link CiReleaseSlotParser} refuses
   * anything but a plain slug already — so reaching the refusal here means a caller built a name
   * some other way. Belt and braces, and the belt is real.
   *
   * @param repo the repository the run is for — the one whose {@code release.yml} named the
   *     archetype, never any other
   * @param rev the revision that {@code release.yml} was read at, so that the declaration and the
   *     recipe it names come from one commit
   * @param name the archetype the slot file asked for
   */
  public Resolution read(CiRepoRef repo, String rev, String name) {
    if (!CiReleaseSlotParser.isArchetypeName(name)) {
      LOG.warnf("Release archetype '%s' is not a name this qits-ci will read — no release run", name);
      return Resolution.unknown("'" + name + "' is not a release archetype name");
    }
    String path = isSource(repo) ? sourcePath(name) : CiReleaseSlotParser.archetypePath(name);
    FileLookup local = configSource.readFile(repo, rev, path);
    switch (local.status()) {
      case FOUND -> {
        try {
          return Resolution.found(
              new Archetype(name, path, rev, null, slotParser.parseArchetype(path, local.content())));
        } catch (CiConfigException e) {
          // FINAL, and NOT the packaged copy. The repository replaced this recipe and the
          // replacement is broken; composing from the platform's instead would run a pipeline its
          // author explicitly did not ask for and report it green.
          LOG.warnf(
              "Release archetype '%s' (%s in %s at %s) is not a usable recipe: %s — no release run,"
                  + " and the packaged recipe of that name is deliberately not used in its place",
              name, path, repo.display(), rev, e.getMessage());
          return Resolution.unknown(
              path + " at " + rev + " is not a usable release archetype: " + e.getMessage());
        }
      }
      case UNREACHABLE -> {
        LOG.warnf(
            "Release archetype '%s' could not be looked for in %s at %s (%s) — whether that"
                + " repository carries its own recipe is not known, so nothing is composed",
            name, repo.display(), rev, path);
        return Resolution.unreadable(path + " could not be read at " + rev);
      }
      default -> {
        // ABSENT: the repository carries no recipe of its own, which is the ordinary case.
      }
    }
    CiReleaseSlots packaged = packaged(name);
    if (packaged == null) {
      LOG.warnf(
          "Release archetype '%s' is unknown: %s carries no %s at %s and this qits-ci packages no"
              + " recipe of that name — no release run",
          name, repo.display(), path, rev);
      return Resolution.unknown(
          "no release archetype '"
              + name
              + "' exists: the repository carries no "
              + path
              + " at "
              + rev
              + " and this qits-ci packages none of that name");
    }
    return Resolution.found(
        new Archetype(name, sourcePath(name), null, applicationVersion.orElse(null), packaged));
  }

  /** The path of one packaged recipe in qits-ci-service — where it is edited and recorded. */
  static String sourcePath(String name) {
    return SOURCE_DIR + name + CiEventTriggerParser.CONFIG_SUFFIX;
  }

  /**
   * Whether {@code repo} is the repository that keeps the packaged set: its project is one of
   * {@link #sourceProjects} <b>and</b> its name is {@link #sourceRepository}. Anything less —
   * the right name in another project, or a reference with no project — is an ordinary repository.
   */
  private boolean isSource(CiRepoRef repo) {
    String source = sourceRepository == null ? null : sourceRepository.orElse(null);
    List<String> projects =
        sourceProjects == null ? List.of() : sourceProjects.orElse(List.of());
    if (source == null || source.isBlank() || !repo.named()) {
      return false;
    }
    return source.equals(repo.name()) && projects.contains(repo.projectId());
  }

  /**
   * The packaged recipe of one name, parsed, or null when this qits-ci packages none.
   *
   * <p>Parsed on first use and kept: the bytes are in the jar and cannot change under a running
   * process. <b>Only a success is kept</b> — the names asked for are repository-controlled, so a
   * memo of misses would be a map anybody can grow, and a miss costs one failed resource lookup.
   *
   * <p>A packaged recipe that does not parse is a defect of this build rather than of any
   * repository, and it is reported at ERROR and answered as "none": {@code
   * PackagedReleaseArchetypesTest} is what keeps that from shipping.
   */
  private CiReleaseSlots packaged(String name) {
    CiReleaseSlots known = packagedByName.get(name);
    if (known != null) {
      return known;
    }
    String resource = PACKAGED_DIR + name + CiEventTriggerParser.CONFIG_SUFFIX;
    String content = packagedContent(resource);
    if (content == null) {
      return null;
    }
    try {
      CiReleaseSlots parsed =
          slotParser.parseArchetype(CiReleaseSlotParser.archetypePath(name), content);
      packagedByName.put(name, parsed);
      return parsed;
    } catch (CiConfigException e) {
      LOG.errorf(
          "The release archetype '%s' packaged into this qits-ci (%s) is not a usable recipe: %s",
          name, resource, e.getMessage());
      return null;
    }
  }

  /**
   * The text of one classpath resource, or null when there is none.
   *
   * <p>Package-private and non-final so a hand-wired test can stand in a packaged set of its own;
   * production has exactly this one implementation. The context class loader is asked first
   * because that is the one a Quarkus application's resources are on in dev and test mode, and this
   * class's own loader second, which is the answer everywhere else.
   */
  String packagedContent(String resource) {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    try (InputStream in = open(context, resource)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOG.errorf(e, "The packaged release archetype %s could not be read", resource);
      return null;
    }
  }

  private static InputStream open(ClassLoader context, String resource) {
    InputStream in = context == null ? null : context.getResourceAsStream(resource);
    return in != null ? in : CiReleaseArchetypes.class.getClassLoader().getResourceAsStream(resource);
  }
}
