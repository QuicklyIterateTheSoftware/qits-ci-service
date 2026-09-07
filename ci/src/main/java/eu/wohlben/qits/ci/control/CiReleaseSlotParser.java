package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import eu.wohlben.qits.ci.control.CiReleaseSlots.Userflows;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code .config/qits/release.yml} — the repository's release SLOTS — and the archetype
 * recipes in the wrapper repository, which are the same document minus {@code archetype:}.
 *
 * <pre>{@code
 * archetype: java-service
 * release-request:
 *   - image: qits/build-images/maven-base:latest
 *     script: ./mvnw verify -Dit.test=TelemetryBootstrapIT
 * release:
 *   - image: qits/build-images/ci-base:latest
 *     build: true
 *     script: |
 *       buildctl build ...
 * artifacts:
 *   - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
 * userflows: true
 * }</pre>
 *
 * <h2>Strict about the whole vocabulary, and for the trigger file's own reason</h2>
 *
 * <p>{@code archetype}, {@code release-request}, {@code release}, {@code artifacts} and {@code
 * userflows} are all of it; anything else is a {@link CiConfigException} naming the file. So is a
 * slot that is not a list, a slot that is an <em>empty</em> list, an {@code artifacts:} entry that
 * is not {@code {type, name[, sbom]}}, and a {@code userflows:} that is neither a boolean nor a
 * name. The reason is sharper here than in a trigger file: this document is compiled into two
 * pipelines that publish, so a key that silently parsed to nothing is a release whose SBOM was
 * never submitted, or a QA gate that ran nothing and went green. Never a silent default.
 *
 * <p><b>The step lists are {@link CiConfigSchema#steps} verbatim</b>, per-step leniency included —
 * that leniency is the forward-compatibility lever the whole fleet relies on, and the one key it
 * subtracts ({@code branches:}) is refused here for the same reason it is refused there.
 *
 * <h2>The interpolation charset, and why it is a parse error rather than an escape</h2>
 *
 * <p>Three values from this file reach a composed <em>shell script</em>: an artifact's {@code type}
 * (an enum, so it cannot be anything else), its {@code name}, and its {@code sbom} path. Everything
 * else a step needs arrives as environment, which is the design and not an accident. So the two
 * free-text ones are held to {@link #SCRIPT_SAFE} — an allow-list, not a deny-list, because a
 * deny-list is a claim about every shell that will ever read the composed text. It admits every
 * coordinate the estate publishes ({@code @qits/ui-components}, {@code qits/qits-stt}, {@code
 * eu.wohlben.qits:qits-eventstream}, {@code out/sbom.json}) and refuses quotes, whitespace, {@code
 * $} and backticks by construction. The composer single-quotes them anyway; belt and braces, since
 * one of the two is what would have to be got right forever.
 *
 * <h2>An archetype recipe is this document minus one key</h2>
 *
 * <p>{@link #parseArchetype} refuses {@code archetype:} rather than ignoring it. A recipe naming
 * another recipe is a chain, and a chain is a thing with a depth limit, a cycle check and an
 * override order — none of which anybody asked for. One level, said out loud.
 */
@ApplicationScoped
public class CiReleaseSlotParser {

  /** The repository-scope slot file. One fixed name — it is not a prefix and never a set. */
  public static final String CONFIG_PATH = CiEventTriggerParser.CONFIG_DIR + "release.yml";

  /** Where the wrapper repository keeps its recipes, one file per archetype. */
  public static final String ARCHETYPE_DIR =
      CiEventTriggerParser.CONFIG_DIR + "release-archetypes/";

  static final String ARCHETYPE_KEY = "archetype";

  static final String RELEASE_REQUEST_KEY = "release-request";

  static final String RELEASE_KEY = "release";

  static final String USERFLOWS_KEY = "userflows";

  static final String SBOM_KEY = "sbom";

  /** The whole top-level vocabulary of a repository's slot file. */
  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          ARCHETYPE_KEY,
          RELEASE_REQUEST_KEY,
          RELEASE_KEY,
          CiConfigSchema.ARTIFACTS_KEY,
          USERFLOWS_KEY);

  /** The whole of one {@code artifacts:} entry here — the trigger file's two keys plus the path. */
  private static final Set<String> ARTIFACT_KEYS = Set.of("type", "name", SBOM_KEY);

  /**
   * What an archetype name may be. It becomes a path segment under {@link #ARCHETYPE_DIR} in a URL
   * against the wrapper repository, and it arrives from a repository's own committed file — so what
   * it may contain is decided here rather than trusted, exactly as a trigger file's own {@code *} is.
   */
  private static final String ARCHETYPE_NAME = "[a-z0-9][a-z0-9-]{0,63}";

  /**
   * What a value destined for a composed shell script may contain. See the class javadoc: an
   * allow-list wide enough for every coordinate the estate publishes and narrow enough that quoting
   * is a second line of defence rather than the only one.
   */
  static final String SCRIPT_SAFE = "[A-Za-z0-9._:/@+-]+";

  /** Parses a repository's own {@code .config/qits/release.yml}. */
  public CiReleaseSlots parse(String configPath, String content) {
    return parse(configPath, content, true);
  }

  /**
   * Parses one archetype recipe out of the wrapper repository. Same document, and {@code archetype:}
   * is a parse error rather than a second level of indirection.
   */
  public CiReleaseSlots parseArchetype(String configPath, String content) {
    return parse(configPath, content, false);
  }

  /** The path one archetype's recipe lives at. The name must have passed {@link #isArchetypeName}. */
  public static String archetypePath(String name) {
    return ARCHETYPE_DIR + name + CiEventTriggerParser.CONFIG_SUFFIX;
  }

  /** Whether a name is one this parser will build a path out of — see {@link #ARCHETYPE_NAME}. */
  public static boolean isArchetypeName(String name) {
    return name != null && name.matches(ARCHETYPE_NAME);
  }

  private CiReleaseSlots parse(String configPath, String content, boolean archetypeAllowed) {
    // Strict about duplicate keys, exactly as a trigger file is: a silently dropped slot here is a
    // release that publishes nothing, which is the same class of failure as a widened selection.
    Map<?, ?> root = CiConfigSchema.load(content, true);
    if (root == null) {
      throw new CiConfigException(
          configPath
              + " is empty — a release slot file declares at least one of 'archetype',"
              + " 'release-request', 'release', 'artifacts' or 'userflows'");
    }
    rejectUnknownTopLevelKeys(root, configPath, archetypeAllowed);
    return new CiReleaseSlots(
        configPath,
        parseArchetypeName(root.get(ARCHETYPE_KEY), configPath, archetypeAllowed),
        slot(root, RELEASE_REQUEST_KEY, configPath),
        slot(root, RELEASE_KEY, configPath),
        parseArtifacts(root.get(CiConfigSchema.ARTIFACTS_KEY), configPath),
        parseUserflows(root.get(USERFLOWS_KEY), configPath));
  }

  private static void rejectUnknownTopLevelKeys(
      Map<?, ?> root, String configPath, boolean archetypeAllowed) {
    for (Object key : root.keySet()) {
      if (!(key instanceof String name) || !TOP_LEVEL_KEYS.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": unknown top-level key '"
                + key
                + "' — a release slot file declares only 'archetype', 'release-request', 'release',"
                + " 'artifacts' and 'userflows'");
      }
      if (ARCHETYPE_KEY.equals(name) && !archetypeAllowed) {
        throw new CiConfigException(
            configPath
                + ": an archetype recipe may not declare '"
                + ARCHETYPE_KEY
                + "' — recipes do not chain, and a chain would need a depth limit, a cycle check and"
                + " an override order that nothing here has");
      }
    }
  }

  /**
   * The recipe this repository asks the wrapper for. Absent is {@code ""} — the base composition,
   * platform prelude and postlude only, which is what a genuinely bespoke repository declares.
   */
  private static String parseArchetypeName(
      Object raw, String configPath, boolean archetypeAllowed) {
    if (raw == null || !archetypeAllowed) {
      return "";
    }
    if (!(raw instanceof String name) || name.isBlank()) {
      throw new CiConfigException(
          configPath
              + ": '"
              + ARCHETYPE_KEY
              + "' names a recipe in the platform-pipelines repository, got: "
              + CiConfigSchema.typeOf(raw));
    }
    if (!isArchetypeName(name)) {
      throw new CiConfigException(
          configPath
              + ": '"
              + ARCHETYPE_KEY
              + "' must be a plain lowercase slug ("
              + ARCHETYPE_NAME
              + "), got: '"
              + name
              + "'");
    }
    return name;
  }

  /**
   * One step slot. Absent is null — "this document declares no pipeline for that phase", which is
   * what lets an archetype supply one. An <b>empty list</b> is refused rather than read as "none",
   * on {@code artifacts:}' own argument: omitting the key already spells that unambiguously, and a
   * declared-but-empty slot reads like an override that erased the archetype's steps on purpose,
   * which is not what it would do.
   */
  private static CiPipeline slot(Map<?, ?> root, String key, String configPath) {
    Object raw = root.get(key);
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof List<?> list)) {
      throw new CiConfigException(
          configPath + ": '" + key + "' must be a list of steps, got: " + CiConfigSchema.typeOf(raw));
    }
    if (list.isEmpty()) {
      throw new CiConfigException(
          configPath
              + ": '"
              + key
              + "' is empty — omit the key to inherit the archetype's steps, or name the steps this"
              + " repository runs");
    }
    try {
      // The step schema, verbatim and by construction: the map handed over is exactly the shape
      // CiConfigSchema.steps reads, so a step means the same thing here as in a trigger file.
      return CiConfigSchema.steps(Map.of(CiConfigSchema.STEPS_KEY, raw), configPath);
    } catch (CiConfigException e) {
      // Re-thrown naming the file and the slot: the shared schema's messages are per step, and a
      // document with two slots would otherwise say "Step 0" about one of two lists.
      throw new CiConfigException(configPath + ": '" + key + "': " + e.getMessage(), e);
    }
  }

  /**
   * The {@code artifacts:} block. Deliberately a <b>second</b> implementation of the trigger file's
   * own, rather than a shared one: the two grammars differ (this one takes {@code sbom:}, and holds
   * {@code name} to a charset the other has no reason to), and the trigger file's grammar is pinned
   * by {@code CiEventTriggerParserTest} as the thing that must not move while the fleet migrates.
   * The duplication is the cheaper of the two couplings, and it is the same call this repository
   * makes about its two {@code FakeCiStepRunner}s.
   */
  private static List<SlotArtifact> parseArtifacts(Object raw, String configPath) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new CiConfigException(
          configPath
              + ": 'artifacts' must be a list of { type: …, name: … } mappings, got: "
              + CiConfigSchema.typeOf(raw));
    }
    if (list.isEmpty()) {
      throw new CiConfigException(
          configPath
              + ": 'artifacts' is empty — omit the key to publish nothing, or name what this"
              + " release publishes");
    }
    List<SlotArtifact> artifacts = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      artifacts.add(parseArtifact(list.get(i), configPath, i));
    }
    return List.copyOf(artifacts);
  }

  private static SlotArtifact parseArtifact(Object raw, String configPath, int index) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " must be a mapping of { type: …, name: … }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    for (Object key : map.keySet()) {
      if (!(key instanceof String name) || !ARTIFACT_KEYS.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": artifact "
                + index
                + " declares an unknown key '"
                + key
                + "' — an artifact is exactly { type, name, sbom }");
      }
    }
    return new SlotArtifact(
        new CiArtifact(
            requireArtifactType(map.get("type"), configPath, index),
            requireScriptSafe(map.get("name"), configPath, "artifact " + index + " 'name'")),
        parseSbomPath(map.get(SBOM_KEY), configPath, index));
  }

  private static CiArtifact.Type requireArtifactType(Object value, String configPath, int index) {
    CiArtifact.Type type = value instanceof String keyword ? CiArtifact.Type.of(keyword) : null;
    if (type == null) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " declares type '"
              + value
              + "' — this qits-ci publishes "
              + CiArtifact.Type.vocabulary());
    }
    return type;
  }

  /**
   * The repository-relative path to the CycloneDX document the release step wrote. Absent is {@code
   * ""} — no submission is composed for that artifact, which is the ordinary case for a type that
   * has no document to offer.
   *
   * <p>Relative and downward-only: it is a {@code --file} argument in a step's own checkout, so an
   * absolute path or a {@code ..} segment names something outside the tree the release built.
   */
  private static String parseSbomPath(Object raw, String configPath, int index) {
    if (raw == null) {
      return "";
    }
    String path = requireScriptSafe(raw, configPath, "artifact " + index + " '" + SBOM_KEY + "'");
    if (path.startsWith("/") || path.equals("..") || path.startsWith("../") || path.contains("/../")
        || path.endsWith("/..")) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " declares sbom '"
              + path
              + "' — it is a path inside the release's own checkout, so it is relative and points"
              + " downwards");
    }
    return path;
  }

  /**
   * {@code userflows: true}, or {@code userflows: <site>}. {@code false} is refused rather than read
   * as absence: omitting the key already says "no userflows", and a {@code false} that parsed to the
   * same thing would be a second spelling of one fact — which is how two spellings drift.
   */
  private static Userflows parseUserflows(Object raw, String configPath) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Boolean declared) {
      if (!declared) {
        throw new CiConfigException(
            configPath
                + ": '"
                + USERFLOWS_KEY
                + ": false' is not a declaration — omit the key, which is what says this repository"
                + " publishes no userflow bundle");
      }
      return new Userflows("");
    }
    if (raw instanceof String site && !site.isBlank()) {
      return new Userflows(requireScriptSafe(site, configPath, "'" + USERFLOWS_KEY + "'"));
    }
    throw new CiConfigException(
        configPath
            + ": '"
            + USERFLOWS_KEY
            + "' is true or the site name the bundle publishes under, got: "
            + CiConfigSchema.typeOf(raw));
  }

  /** A string that will reach a composed shell script — see {@link #SCRIPT_SAFE}. */
  private static String requireScriptSafe(Object value, String configPath, String what) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new CiConfigException(
          configPath
              + ": "
              + what
              + " is missing — it is a plain coordinate, and a scoped npm name needs quoting ('@' is"
              + " a reserved YAML indicator)");
    }
    if (!text.matches(SCRIPT_SAFE)) {
      throw new CiConfigException(
          configPath
              + ": "
              + what
              + " is '"
              + text
              + "', which is not composable — this value is interpolated into a generated shell"
              + " script, so it is held to "
              + SCRIPT_SAFE
              + " (no quotes, no whitespace, no '$', no backticks)");
    }
    return text;
  }
}
