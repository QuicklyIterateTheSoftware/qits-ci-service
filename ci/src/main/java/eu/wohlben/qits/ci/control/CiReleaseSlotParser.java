package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiReleaseSlots.SlotArtifact;
import eu.wohlben.qits.ci.control.CiReleaseSlots.Userflows;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code .config/qits/release.yml} — the repository's release SLOTS — and the archetype
 * recipes — a repository's own, or the ones packaged into qits-ci — which are the same document minus
 * {@code archetype:}.
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
 * <p>{@code archetype}, {@code release-request}, {@code release}, {@code artifacts}, {@code
 * userflows} and {@code contracts} are all of it; anything else is a {@link CiConfigException}
 * naming the file. So is a slot that is not a list, a slot that is an <em>empty</em> list, an {@code
 * artifacts:} entry that is not {@code {type, name, sbom[, publish][, path][, link][,
 * include]}} — {@code sbom:} is required on every type but {@code docs}, which is exactly {@code
 * {type, name}} — (with {@code publish:} on a {@code maven} or {@code npm} entry only, {@code path:}
 * likewise or on an {@code @apidocs} docs entry, {@code link:} on {@code maven} only, and {@code
 * include:} only beside {@code publish: if-changed}; {@code announce:}, which qits-648 deleted, is
 * an unknown key like any other), a {@code contracts:} that
 * {@link CiContracts} refuses, and a {@code userflows:} that is neither a boolean nor a name. The reason is sharper here than in a trigger file: this document is compiled into two
 * pipelines that publish, so a key that silently parsed to nothing is a release whose SBOM was
 * never submitted, or a QA gate that ran nothing and went green. Never a silent default.
 *
 * <p><b>The step lists are {@link CiConfigSchema#steps} verbatim</b>, per-step leniency included —
 * that leniency is the forward-compatibility lever the whole fleet relies on, and the one key it
 * subtracts ({@code branches:}) is refused here for the same reason it is refused there.
 *
 * <h2>The interpolation charset, and why it is a parse error rather than an escape</h2>
 *
 * <p>The declarations in this file reach a composed <em>shell script</em>: an artifact's {@code type}
 * (an enum, so it cannot be anything else), its {@code name}, {@code sbom}, {@code path}, {@code
 * link} and {@code include}, and a contract tree's application, provider and {@code from}.
 * Everything else a step needs arrives as environment, which is the design and not an accident. So
 * every free-text one is held to {@link #SCRIPT_SAFE} (a glob to {@link #INCLUDE_SAFE}) — an allow-list, not a deny-list, because a
 * deny-list is a claim about every shell that will ever read the composed text. It admits every
 * coordinate the estate publishes ({@code @qits/ui-components}, {@code qits/qits-stt}, {@code
 * eu.wohlben.qits:qits-eventstream}, {@code out/sbom.json}) and refuses quotes, whitespace, {@code
 * $} and backticks by construction. The composer single-quotes them anyway; belt and braces, since
 * one of the two is what would have to be got right forever.
 *
 * <h2>The qits-620 vocabulary: what the platform publishes, declared rather than scripted</h2>
 *
 * <p>{@code publish:}, {@code path:}, {@code link:}, {@code include:} and the top-level {@code
 * contracts:} are facts the composed publishing postlude spends ({@link CiReleaseComposer}): one
 * {@code qits artifacts publish maven|npm} per maven or npm entry, one {@code contract} call per
 * contract package, and one {@code docs submit --openapi} per {@code @apidocs} entry naming its
 * file. Only {@code publish:} reaches the composed {@code artifacts:} block; the rest is spent on
 * the postlude's command lines.
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

  /**
   * Where a repository keeps recipes of its own, one file per archetype — and where qits-ci-service
   * keeps the ones it packages, which is why a packaged recipe is recorded under this path too.
   */
  public static final String ARCHETYPE_DIR =
      CiEventTriggerParser.CONFIG_DIR + "release-archetypes/";

  static final String ARCHETYPE_KEY = "archetype";

  static final String RELEASE_REQUEST_KEY = "release-request";

  static final String RELEASE_KEY = "release";

  static final String USERFLOWS_KEY = "userflows";

  static final String SBOM_KEY = "sbom";

  /** The module or package directory a maven or npm entry is uploaded from (qits-620). */
  static final String PATH_KEY = "path";

  /** The sibling maven entries an entry keeps as pom dependencies rather than bundling (qits-620). */
  static final String LINK_KEY = "link";

  /** Globs narrowing an {@code if-changed} hash (qits-620). */
  static final String INCLUDE_KEY = "include";

  /** The contracts declaration (qits-620) — see {@link CiContracts}. */
  static final String CONTRACTS_KEY = CiContracts.KEY;

  /** The whole top-level vocabulary of a repository's slot file. */
  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          ARCHETYPE_KEY,
          RELEASE_REQUEST_KEY,
          RELEASE_KEY,
          CiConfigSchema.ARTIFACTS_KEY,
          USERFLOWS_KEY,
          CONTRACTS_KEY);

  /**
   * The whole of one {@code artifacts:} entry here — the trigger file's keys plus the path. {@code
   * publish}, {@code path}, {@code link} and {@code include} are accepted on the entries the class
   * javadoc names.
   */
  private static final Set<String> ARTIFACT_KEYS =
      Set.of(
          "type",
          "name",
          SBOM_KEY,
          CiArtifact.PUBLISH_KEY,
          PATH_KEY,
          LINK_KEY,
          INCLUDE_KEY);

  /**
   * What an archetype name may be. It becomes a path segment under {@link #ARCHETYPE_DIR} in a URL
   * against the git host and a segment of a classpath resource name, and it arrives from a
   * repository's own committed file — so what
   * it may contain is decided here rather than trusted, exactly as a trigger file's own {@code *} is.
   */
  private static final String ARCHETYPE_NAME = "[a-z0-9][a-z0-9-]{0,63}";

  /**
   * What a value destined for a composed shell script may contain. See the class javadoc: an
   * allow-list wide enough for every coordinate the estate publishes and narrow enough that quoting
   * is a second line of defence rather than the only one.
   */
  static final String SCRIPT_SAFE = "[A-Za-z0-9._:/@+-]+";

  /**
   * What an {@code include:} glob may contain: {@link #SCRIPT_SAFE} plus {@code *} and {@code ?},
   * which a glob needs. No braces, no commas, no whitespace — and the composer single-quotes it, so
   * the shell never expands it.
   */
  static final String INCLUDE_SAFE = "[A-Za-z0-9._:/@+*?-]+";

  /** The file endings an {@code @apidocs} entry's OpenAPI document may have. */
  private static final List<String> OPENAPI_SUFFIXES = List.of(".yml", ".yaml", ".json");

  /** The whole top-level vocabulary as a message fragment. */
  private static final String TOP_LEVEL_VOCABULARY =
      "'archetype', 'release-request', 'release', 'artifacts', 'userflows' and 'contracts'";

  /** Parses a repository's own {@code .config/qits/release.yml}. */
  public CiReleaseSlots parse(String configPath, String content) {
    return parse(configPath, content, true);
  }

  /**
   * Parses one archetype recipe, local or packaged. Same document, and {@code archetype:}
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
              + " is empty — a release slot file declares at least one of "
              + TOP_LEVEL_VOCABULARY.replace(" and ", " or "));
    }
    rejectUnknownTopLevelKeys(root, configPath, archetypeAllowed);
    return new CiReleaseSlots(
        configPath,
        parseArchetypeName(root.get(ARCHETYPE_KEY), configPath, archetypeAllowed),
        slot(root, RELEASE_REQUEST_KEY, configPath),
        slot(root, RELEASE_KEY, configPath),
        parseArtifacts(root.get(CiConfigSchema.ARTIFACTS_KEY), configPath),
        parseUserflows(root.get(USERFLOWS_KEY), configPath),
        root.containsKey(CONTRACTS_KEY)
            ? CiContracts.parse(root.get(CONTRACTS_KEY), configPath)
            : null);
  }

  private static void rejectUnknownTopLevelKeys(
      Map<?, ?> root, String configPath, boolean archetypeAllowed) {
    for (Object key : root.keySet()) {
      if (!(key instanceof String name) || !TOP_LEVEL_KEYS.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": unknown top-level key '"
                + key
                + "' — a release slot file declares only "
                + TOP_LEVEL_VOCABULARY);
      }
      if (CONTRACTS_KEY.equals(name) && !archetypeAllowed) {
        throw new CiConfigException(
            configPath
                + ": an archetype recipe may not declare '"
                + CONTRACTS_KEY
                + "' — contracts are a repository's own facts, declared in its release.yml, and a"
                + " recipe default would publish packages for an application it has never heard of");
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
   * The recipe this repository asks for. Absent is {@code ""} — the base composition,
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
      return CiConfigSchema.steps(
          Map.of(CiConfigSchema.STEPS_KEY, raw),
          configPath,
          // A slot file is committed bytes a person can fix, so it is held to the strict schema:
          // `gating:` is refused here exactly as it is in a trigger file.
          CiConfigSchema.Origin.COMMITTED_FILE);
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
    // Last, because a link names ANOTHER entry: whether it resolves is a fact about the whole list.
    requireLinksResolve(artifacts, configPath);
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
                + "' — an artifact is exactly { type, name, sbom[, publish][, path][, link]"
                + "[, include] } ({ type, name } for a docs entry, which needs no sbom)"
                + CiArtifact.retiredKeyHint(key));
      }
    }
    CiArtifact.Type type = requireArtifactType(map.get("type"), configPath, index);
    String name = requireScriptSafe(map.get("name"), configPath, "artifact " + index + " 'name'");
    CiArtifact artifact =
        new CiArtifact(
            type,
            name,
            CiArtifact.requirePublish(
                map.get(CiArtifact.PUBLISH_KEY), type, name, configPath, index));
    String sbomPath = parseSbomPath(map.get(SBOM_KEY), configPath, index);
    if (artifact.publishIfChanged() && sbomPath.isEmpty()) {
      throw new CiConfigException(
          CiArtifact.entry(configPath, index, type, name)
              + " declares "
              + CiArtifact.PUBLISH_KEY
              + ": "
              + CiArtifact.Publish.IF_CHANGED.declared()
              + " and no "
              + SBOM_KEY
              + " — the change decision hashes the SBOM with the content, so an if-changed entry"
              + " must name the CycloneDX document its build writes");
    }
    if (type != CiArtifact.Type.DOCS && sbomPath.isEmpty()) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " ("
              + type.declared()
              + " "
              + name
              + ") declares no "
              + SBOM_KEY
              + ": — every software artifact needs one; contracts go under "
              + CONTRACTS_KEY
              + ":");
    }
    return new SlotArtifact(
        artifact,
        sbomPath,
        parsePath(map.get(PATH_KEY), type, name, configPath, index),
        parseLink(map.get(LINK_KEY), type, name, configPath, index),
        parseInclude(map.get(INCLUDE_KEY), artifact, configPath, index));
  }

  /**
   * {@code include:} — a non-empty list of globs narrowing an {@code if-changed} entry's hash, never
   * what is uploaded. On any other type, or beside {@code publish: always}, it would narrow a hash
   * nothing computes, so it is refused rather than ignored.
   */
  private static List<String> parseInclude(
      Object raw, CiArtifact artifact, String configPath, int index) {
    if (raw == null) {
      return List.of();
    }
    String entry = CiArtifact.entry(configPath, index, artifact.type(), artifact.name());
    if (artifact.type() != CiArtifact.Type.MAVEN && artifact.type() != CiArtifact.Type.NPM) {
      throw new CiConfigException(
          entry + " declares " + INCLUDE_KEY + " — only a maven or npm entry is hashed");
    }
    if (!artifact.publishIfChanged()) {
      throw new CiConfigException(
          entry
              + " declares "
              + INCLUDE_KEY
              + ", which only narrows the "
              + CiArtifact.Publish.IF_CHANGED.declared()
              + " hash; it means nothing on a "
              + CiArtifact.PUBLISH_KEY
              + ": "
              + CiArtifact.Publish.ALWAYS.declared()
              + " entry");
    }
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new CiConfigException(
          entry
              + " declares "
              + INCLUDE_KEY
              + " as "
              + CiConfigSchema.typeOf(raw)
              + " — it is a non-empty list of globs, e.g. "
              + INCLUDE_KEY
              + ": [\"eu/wohlben/**\"]");
    }
    Set<String> globs = new LinkedHashSet<>();
    for (Object element : list) {
      if (!(element instanceof String glob) || !glob.matches(INCLUDE_SAFE)) {
        throw new CiConfigException(
            entry
                + " declares "
                + INCLUDE_KEY
                + " glob '"
                + element
                + "', which is not composable — a glob is held to "
                + INCLUDE_SAFE
                + " (no braces, no commas, no whitespace, no quotes)");
      }
      globs.add(glob);
    }
    return List.copyOf(globs);
  }

  /**
   * {@code path:} — the directory a {@code maven} module or an {@code npm} package is uploaded from,
   * {@code "."} when absent. Relative and downward, {@code sbom:}'s rule: it is a {@code --path}
   * argument in the release step's own checkout.
   *
   * <p>A {@code docs} entry in the {@code @apidocs/} scope names its OpenAPI file here ({@code .yml},
   * {@code .yaml} or {@code .json}), and the platform publishes that file as the site — one {@code
   * docs submit --openapi} in the postlude. Any other docs entry, and every {@code docker} and {@code
   * daemon} one, has nothing the platform uploads, so the key is refused there.
   */
  private static String parsePath(
      Object raw, CiArtifact.Type type, String name, String configPath, int index) {
    if (raw == null) {
      return SlotArtifact.defaultPath(type);
    }
    if (type == CiArtifact.Type.DOCS && name.startsWith(SlotArtifact.APIDOCS_SCOPE)) {
      String path = requireDownwardPath(raw, configPath, index, PATH_KEY);
      if (OPENAPI_SUFFIXES.stream().noneMatch(path::endsWith)) {
        throw new CiConfigException(
            CiArtifact.entry(configPath, index, type, name)
                + " @apidocs "
                + PATH_KEY
                + " '"
                + path
                + "' is not an OpenAPI document (.yml, .yaml or .json)");
      }
      return path;
    }
    if (type != CiArtifact.Type.MAVEN && type != CiArtifact.Type.NPM) {
      throw new CiConfigException(
          CiArtifact.entry(configPath, index, type, name)
              + " declares "
              + PATH_KEY
              + " — only a maven or npm entry, or an @apidocs docs entry naming its OpenAPI file,"
              + " has something the platform uploads");
    }
    return requireDownwardPath(raw, configPath, index, PATH_KEY);
  }

  /**
   * {@code link:} — a non-empty list of artifactIds, {@code maven} entries only. Each name's shape is
   * checked here; whether it names a sibling, and whether the links form a cycle, is {@link
   * #requireLinksResolve}'s, once every entry has parsed.
   */
  private static List<String> parseLink(
      Object raw, CiArtifact.Type type, String name, String configPath, int index) {
    if (raw == null) {
      return List.of();
    }
    if (type != CiArtifact.Type.MAVEN) {
      throw new CiConfigException(
          CiArtifact.entry(configPath, index, type, name)
              + " declares "
              + LINK_KEY
              + " — only a maven entry bundles its reactor siblings");
    }
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new CiConfigException(
          CiArtifact.entry(configPath, index, type, name)
              + " declares "
              + LINK_KEY
              + " as "
              + CiConfigSchema.typeOf(raw)
              + " — it is a non-empty list of the artifactIds of sibling maven entries, e.g. "
              + LINK_KEY
              + ": [qits-blobstore]");
    }
    Set<String> links = new LinkedHashSet<>();
    for (Object element : list) {
      String link =
          requireScriptSafe(element, configPath, "artifact " + index + " '" + LINK_KEY + "' entry");
      if (!links.add(link)) {
        throw new CiConfigException(
            CiArtifact.entry(configPath, index, type, name) + " links '" + link + "' twice");
      }
    }
    return List.copyOf(links);
  }

  /**
   * Every {@code link:} names <b>another maven entry of this file</b> by its artifactId, with the
   * same {@code publish:}, and the links form no cycle. The postlude decides a linked sibling before the entries linking it, so a
   * target that is not published here would be a pom dependency on nothing, and a cycle would be an
   * order that does not exist.
   */
  private static void requireLinksResolve(List<SlotArtifact> artifacts, String configPath) {
    Map<String, Integer> byArtifactId = new HashMap<>();
    Set<String> ambiguous = new HashSet<>();
    for (int i = 0; i < artifacts.size(); i++) {
      CiArtifact artifact = artifacts.get(i).artifact();
      if (artifact.type() == CiArtifact.Type.MAVEN
          && byArtifactId.putIfAbsent(artifactId(artifact.name()), i) != null) {
        ambiguous.add(artifactId(artifact.name()));
      }
    }
    for (int i = 0; i < artifacts.size(); i++) {
      CiArtifact artifact = artifacts.get(i).artifact();
      for (String link : artifacts.get(i).link()) {
        String entry = CiArtifact.entry(configPath, i, artifact.type(), artifact.name());
        if (link.equals(artifactId(artifact.name()))) {
          throw new CiConfigException(entry + " links itself");
        }
        if (!byArtifactId.containsKey(link)) {
          throw new CiConfigException(
              entry
                  + " links '"
                  + link
                  + "', which is not a maven entry of this release.yml — a linked sibling must"
                  + " itself be published here");
        }
        if (ambiguous.contains(link)) {
          throw new CiConfigException(
              entry
                  + " links '"
                  + link
                  + "', which is the artifactId of more than one maven entry of this release.yml —"
                  + " a link has to name exactly one sibling");
        }
        // A link group publishes together (the composer's publishBlock), so it has one policy.
        if (artifacts.get(byArtifactId.get(link)).artifact().publish() != artifact.publish()) {
          throw new CiConfigException(
              entry
                  + " links '"
                  + link
                  + "' with another publish: — linked entries publish together, so give them the"
                  + " same publish:");
        }
      }
    }
    int[] state = new int[artifacts.size()];
    for (int i = 0; i < artifacts.size(); i++) {
      List<Integer> cycle = findCycle(i, artifacts, byArtifactId, state, new ArrayList<>());
      if (cycle != null) {
        int start = cycle.getFirst();
        CiArtifact first = artifacts.get(start).artifact();
        List<String> names =
            cycle.stream().map(at -> artifactId(artifacts.get(at).artifact().name())).toList();
        throw new CiConfigException(
            CiArtifact.entry(configPath, start, first.type(), first.name())
                + " "
                + LINK_KEY
                + ": forms a cycle ("
                + String.join(" → ", names)
                + ")");
      }
    }
  }

  /**
   * Depth-first over the link graph: {@code state} is 0 unvisited, 1 on the current path, 2 done.
   * Answers the cycle as entry indices, first entry repeated at the end, or null.
   */
  private static List<Integer> findCycle(
      int at,
      List<SlotArtifact> artifacts,
      Map<String, Integer> byArtifactId,
      int[] state,
      List<Integer> path) {
    if (state[at] == 2) {
      return null;
    }
    if (state[at] == 1) {
      List<Integer> cycle = new ArrayList<>(path.subList(path.indexOf(at), path.size()));
      cycle.add(at);
      return cycle;
    }
    state[at] = 1;
    path.add(at);
    for (String link : artifacts.get(at).link()) {
      List<Integer> cycle = findCycle(byArtifactId.get(link), artifacts, byArtifactId, state, path);
      if (cycle != null) {
        return cycle;
      }
    }
    path.removeLast();
    state[at] = 2;
    return null;
  }

  /** The artifactId of a maven coordinate {@code group:artifactId} — what a {@code link:} names. */
  static String artifactId(String mavenName) {
    return mavenName.substring(mavenName.lastIndexOf(':') + 1);
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
    return requireDownwardPath(raw, configPath, index, SBOM_KEY);
  }

  /** A composable path inside the release's own checkout: relative, no {@code ..} segment. */
  private static String requireDownwardPath(Object raw, String configPath, int index, String key) {
    return requireDownward(raw, configPath, "artifact " + index + " '" + key + "'");
  }

  /**
   * The same rule for any value that becomes a path argument in the release step — an artifact's
   * {@code sbom:} or {@code path:}, a contract tree's {@code from:}. {@code what} names it in the
   * message.
   */
  static String requireDownward(Object raw, String configPath, String what) {
    String path = requireScriptSafe(raw, configPath, what);
    if (path.startsWith("/") || path.equals("..") || path.startsWith("../") || path.contains("/../")
        || path.endsWith("/..")) {
      throw new CiConfigException(
          configPath
              + ": "
              + what
              + " is '"
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
