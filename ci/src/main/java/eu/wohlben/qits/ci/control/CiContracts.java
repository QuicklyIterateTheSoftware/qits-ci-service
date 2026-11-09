package eu.wohlben.qits.ci.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code contracts:} section of {@code .config/qits/release.yml} (qits-620): a provider's golden
 * masters and a consumer's pacts, declared as facts — where the tree is and which package
 * ecosystems to produce — and packed and published by the platform.
 *
 * <pre>{@code
 * contracts:
 *   application: qits-landing
 *   golden-masters: { from: golden-masters/, packages: [maven, npm] }
 *   entities: docs/entities.json
 *   routes: { from: src/testing/browser/freezes }
 *   pacts:
 *     qits-projects-service: { packages: [maven] }
 * }</pre>
 *
 * <h2>{@code entities:} rides the golden-masters bundle</h2>
 *
 * <p>{@code entities:} (qits-1945) is the optional repository-relative path to the entity exposure
 * map a service's {@code EntityExposureTest} writes (qits-arch-rules), by convention {@code
 * docs/entities.json}. It is handed to {@code contract-docs} as {@code --entities <path>} alongside
 * {@code --from}, because the map rides the {@code @contracts} bundle that command builds — which
 * exists only when {@code golden-masters:} is declared — so the key is refused without one. It names
 * no package of its own: there is no {@code entities} ecosystem and no coordinate, only a file
 * folded into the bundle {@code golden-masters:} already produces.
 *
 * <h2>{@code routes:} is a docs site, and lives here rather than under {@code artifacts:}</h2>
 *
 * <p>{@code routes:} (qits-1948) names the repository-relative directory of a frontend's recorded
 * route freezes — the HTTP exchanges its browser tests replay — and the release slot's postlude
 * publishes it as the docs site {@code @routes/<application>}, through {@code qits artifacts
 * publish docs submit --dir <from> --if-changed}. It takes {@code from:} and nothing else: there is
 * no ecosystem and no coordinate, the site's name is derived from {@code application:} exactly as
 * the {@code @contracts} bundle's is, and so the key is refused without one like every other key
 * here.
 *
 * <p>It lives under {@code contracts:} and not under {@code artifacts:} on purpose, for the reason
 * {@code @contracts} does. An {@code artifacts:} entry is announced — it is a row the release join
 * owes, and an artifact that published nothing is a missing row — while a routes site is
 * <b>published only when its content changed</b>, a decision the CLI's {@code --if-changed} makes
 * from the tree's content hash at publish time. An unchanged release publishes nothing, which an
 * announcement row cannot express, so the site has no row at all: like the contract docs, it is
 * documentation the release carries, not a deliverable it promises.
 *
 * <h2>Pacts are keyed by the provider's repository name, and picked by file name</h2>
 *
 * <p>A consumer keeps every pact flat in {@value #PACTS_DIRECTORY}, one file per provider, named
 * {@code <consumer repository>_<provider repository>.json}. An entry under {@code pacts:} names the
 * provider's repository, and its package holds exactly the files {@code pacts/*_<key>.json} — the
 * CLI picks them by name — so a change to one provider's pact never publishes, and so never bumps,
 * another provider's package. A repository name, never an application name, because one
 * component can have a frontend and a service and the application name does not say which.
 *
 * <h2>The coordinate rule lives here and nowhere else</h2>
 *
 * <p>Every package's coordinate is derived ONCE, by {@link #coordinate}, and handed to the CLI as
 * {@code --name}; the CLI never re-derives it. It is the grammar qits-546 published by hand:
 *
 * <pre>
 * golden-masters  maven  eu.wohlben.qits:&lt;app&gt;-golden-masters
 * golden-masters  npm    &#64;qits/&lt;short(app)&gt;-golden-masters
 * pacts(provider) maven  eu.wohlben.qits:&lt;consumer repo&gt;-pacts-&lt;provider repo&gt;
 * pacts(provider) npm    &#64;qits/&lt;short(consumer repo)&gt;-pacts-&lt;provider repo&gt;
 * </pre>
 *
 * <p>Golden masters keep the provider's APPLICATION name ({@code application:}); a pact package is
 * named by the two REPOSITORY names, the consumer's being the repository the release runs for.
 * {@code short(x)} drops ONE leading {@code qits-}, and only on the consumer's side. {@code
 * CiContractsTest} pins the coordinates, so a change here that moves one of them is a red test
 * rather than a new artifact nobody consumes.
 *
 * <p><b>Every contract package is {@code if-changed} by definition</b>, which is why {@code
 * publish:} is refused anywhere inside the section rather than offered: there is no second policy
 * for it to choose.
 *
 * @param application the application whose contracts these are — the consumer for pacts, the
 *     provider for golden masters
 * @param goldenMasters the golden-masters tree, or null when none is declared
 * @param pacts the pact packages by provider repository, in declared order, empty when none
 * @param entities the repository-relative path to the entity exposure map, or null when none is
 *     declared; never present without {@code goldenMasters}
 * @param routesFrom the repository-relative directory of the recorded route freezes published as
 *     the {@code @routes/<application>} docs site, or null when {@code routes:} is not declared
 */
public record CiContracts(
    String application,
    Source goldenMasters,
    Map<String, Source> pacts,
    String entities,
    String routesFrom) {

  /** The group every contract package's maven coordinate is in. */
  static final String GROUP = "eu.wohlben.qits";

  /** The npm scope every contract package's npm name is in. */
  static final String NPM_SCOPE = "@qits/";

  static final String KEY = "contracts";

  private static final String APPLICATION_KEY = "application";
  private static final String GOLDEN_MASTERS_KEY = "golden-masters";
  private static final String PACTS_KEY = "pacts";
  private static final String ENTITIES_KEY = "entities";
  private static final String ROUTES_KEY = "routes";
  private static final String FROM_KEY = "from";
  private static final String PACKAGES_KEY = "packages";

  /** What an application name, the section's own, may be. */
  static final String APPLICATION = "[a-z][a-z0-9-]*";

  /**
   * What a pact's provider key may be: a repository name, which ends in its role (the wrapper's
   * AGENTS.md lists the roles).
   */
  static final String PROVIDER_REPOSITORY =
      "[a-z][a-z0-9-]*-(service|frontend|app|daemon|oci|cli|javalib|jslib)";

  /** The one directory every pact sits in, flat. */
  static final String PACTS_DIRECTORY = "pacts/";

  public CiContracts {
    pacts = Collections.unmodifiableMap(new LinkedHashMap<>(pacts));
  }

  /**
   * One declared tree.
   *
   * @param from the repository-relative directory the tree is packed from; for pacts always
   *     {@value #PACTS_DIRECTORY}, from which the CLI picks the provider's files by name
   * @param packages the ecosystems to produce, in declared order, never empty
   */
  public record Source(String from, List<Ecosystem> packages) {

    public Source {
      packages = List.copyOf(packages);
    }
  }

  /** The package ecosystems this qits-ci produces. A closed list; more arrive with languages. */
  public enum Ecosystem {
    MAVEN("maven", CiArtifact.Type.MAVEN),
    NPM("npm", CiArtifact.Type.NPM);

    private final String declared;
    private final CiArtifact.Type type;

    Ecosystem(String declared, CiArtifact.Type type) {
      this.declared = declared;
      this.type = type;
    }

    /** How a file spells it, and how the CLI's {@code --ecosystem} does. */
    public String declared() {
      return declared;
    }

    /** The artifact type the package is announced as. */
    public CiArtifact.Type type() {
      return type;
    }

    static Ecosystem of(String keyword) {
      for (Ecosystem ecosystem : values()) {
        if (ecosystem.declared.equals(keyword)) {
          return ecosystem;
        }
      }
      return null;
    }
  }

  /** What a tree is. The declared spelling is the CLI's {@code --kind}. */
  public enum Kind {
    GOLDEN_MASTERS(GOLDEN_MASTERS_KEY),
    PACTS(PACTS_KEY);

    private final String declared;

    Kind(String declared) {
      this.declared = declared;
    }

    public String declared() {
      return declared;
    }
  }

  /**
   * One package the platform produces: one (kind, provider, ecosystem).
   *
   * @param kind golden masters or pacts
   * @param provider the provider repository a pact is with, {@code ""} for golden masters
   * @param ecosystem the package's ecosystem
   * @param name the derived coordinate
   * @param from the tree it is packed from
   */
  public record Package(Kind kind, String provider, Ecosystem ecosystem, String name, String from) {

    /** The package as an ordinary artifact declaration — always {@code if-changed}. */
    public CiArtifact artifact() {
      return new CiArtifact(
          ecosystem.type(), name, CiArtifact.Publish.IF_CHANGED, CiArtifact.Section.CONTRACTS);
    }
  }

  /**
   * Every package this declaration produces: golden masters first, then each provider's pacts in
   * declared order, each in its declared ecosystem order. That is the order the postlude publishes
   * them in and the order they are appended to the composed {@code artifacts:} block.
   *
   * @param repository the consumer's repository name — the repository this release is for — which
   *     names its pact packages
   */
  public List<Package> packages(String repository) {
    List<Package> packages = new ArrayList<>(goldenMasterPackages());
    for (Map.Entry<String, Source> pact : pacts.entrySet()) {
      for (Ecosystem ecosystem : pact.getValue().packages()) {
        packages.add(
            new Package(
                Kind.PACTS,
                pact.getKey(),
                ecosystem,
                coordinate(Kind.PACTS, repository, pact.getKey(), ecosystem),
                pact.getValue().from()));
      }
    }
    return List.copyOf(packages);
  }

  /** The golden-masters packages alone — what {@code contract-docs} lists. */
  public List<Package> goldenMasterPackages() {
    List<Package> packages = new ArrayList<>();
    if (goldenMasters != null) {
      for (Ecosystem ecosystem : goldenMasters.packages()) {
        packages.add(
            new Package(
                Kind.GOLDEN_MASTERS,
                "",
                ecosystem,
                coordinate(Kind.GOLDEN_MASTERS, application, "", ecosystem),
                goldenMasters.from()));
      }
    }
    return List.copyOf(packages);
  }

  /**
   * THE coordinate rule. See the class javadoc; {@code CiContractsTest} pins its live outputs.
   *
   * @param owner the application for golden masters, the consumer repository for pacts
   * @param provider the pact's provider repository, ignored for golden masters
   */
  static String coordinate(Kind kind, String owner, String provider, Ecosystem ecosystem) {
    String suffix = kind == Kind.GOLDEN_MASTERS ? "-golden-masters" : "-pacts-" + provider;
    return switch (ecosystem) {
      case MAVEN -> GROUP + ":" + owner + suffix;
      case NPM -> NPM_SCOPE + shortName(owner) + suffix;
    };
  }

  /** The application with ONE leading {@code qits-} removed, if it has one. */
  static String shortName(String application) {
    return application.startsWith("qits-") ? application.substring("qits-".length()) : application;
  }

  // --- parsing -----------------------------------------------------------------------------------

  /**
   * Parses the section, strictly: an unknown key at any level, a missing or malformed {@code
   * application}, a section with neither tree, an empty or duplicate or unknown {@code packages}
   * entry, an {@code entities:} that is not a relative, downward {@code .json} path or that is
   * declared without {@code golden-masters:}, a {@code routes:} that is not exactly {@code { from:
   * <relative, downward directory> }}, and {@code publish:} anywhere inside are all {@link
   * CiConfigException}s naming the file.
   */
  static CiContracts parse(Object raw, String configPath) {
    String where = configPath + ": '" + KEY + "'";
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          where
              + " must be a mapping of { application, golden-masters, entities, routes, pacts }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    refusePublish(map, configPath, KEY);
    requireKeys(
        map,
        Set.of(APPLICATION_KEY, GOLDEN_MASTERS_KEY, ENTITIES_KEY, ROUTES_KEY, PACTS_KEY),
        configPath,
        KEY,
        "'application', 'golden-masters', 'entities', 'routes' and 'pacts'");
    Object app = map.get(APPLICATION_KEY);
    if (!(app instanceof String application) || !application.matches(APPLICATION)) {
      throw new CiConfigException(
          configPath
              + ": "
              + KEY
              + "."
              + APPLICATION_KEY
              + " is "
              + (app == null ? "missing" : "'" + app + "'")
              + " — it is the application whose contracts these are, "
              + APPLICATION);
    }
    if (!map.containsKey(GOLDEN_MASTERS_KEY)
        && !map.containsKey(PACTS_KEY)
        && !map.containsKey(ROUTES_KEY)) {
      throw new CiConfigException(
          where
              + " declares none of '"
              + GOLDEN_MASTERS_KEY
              + "', '"
              + PACTS_KEY
              + "' or '"
              + ROUTES_KEY
              + "' — omit the section, or name the trees this repository publishes");
    }
    Source goldenMasters =
        map.containsKey(GOLDEN_MASTERS_KEY)
            ? source(map.get(GOLDEN_MASTERS_KEY), configPath, KEY + "." + GOLDEN_MASTERS_KEY)
            : null;
    String entities =
        map.containsKey(ENTITIES_KEY) ? entities(map, goldenMasters, configPath) : null;
    String routesFrom =
        map.containsKey(ROUTES_KEY) ? routes(map.get(ROUTES_KEY), configPath) : null;
    Map<String, Source> pacts = new LinkedHashMap<>();
    if (map.containsKey(PACTS_KEY)) {
      Object rawPacts = map.get(PACTS_KEY);
      String pactsWhere = KEY + "." + PACTS_KEY;
      if (!(rawPacts instanceof Map<?, ?> byProvider) || byProvider.isEmpty()) {
        throw new CiConfigException(
            configPath
                + ": "
                + pactsWhere
                + " must be a non-empty mapping keyed by provider repository, e.g."
                + " qits-projects-service: { packages: [maven] }, got: "
                + CiConfigSchema.typeOf(rawPacts));
      }
      refusePublish(byProvider, configPath, pactsWhere);
      for (Map.Entry<?, ?> pact : byProvider.entrySet()) {
        if (!(pact.getKey() instanceof String provider) || !provider.matches(PROVIDER_REPOSITORY)) {
          throw new CiConfigException(
              configPath
                  + ": "
                  + pactsWhere
                  + " names provider '"
                  + pact.getKey()
                  + "' — a provider is the provider's REPOSITORY name, ending in its role (e.g."
                  + " qits-projects-service), and its pacts are the files "
                  + PACTS_DIRECTORY
                  + "*_<provider>.json; an application name is no longer accepted");
        }
        pacts.put(provider, pactSource(pact.getValue(), configPath, pactsWhere + "." + provider));
      }
    }
    return new CiContracts(application, goldenMasters, pacts, entities, routesFrom);
  }

  /**
   * The {@code routes:} mapping: {@code { from }} and nothing else, {@code from} a non-blank,
   * relative, downward directory — the same rule as {@code golden-masters.from}, since both become
   * a path argument of the release step. {@code publish:} is refused like everywhere in the section:
   * the site is {@code --if-changed} by definition.
   */
  private static String routes(Object raw, String configPath) {
    String where = KEY + "." + ROUTES_KEY;
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          configPath
              + ": "
              + where
              + " must be a mapping of { from }, e.g. { from: src/testing/browser/freezes }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    refusePublish(map, configPath, where);
    requireKeys(map, Set.of(FROM_KEY), configPath, where, "'from'");
    return CiReleaseSlotParser.requireDownward(
        map.get(FROM_KEY), configPath, where + "." + FROM_KEY);
  }

  /**
   * The {@code entities:} path: a non-blank, relative, downward {@code .json} path, and refused
   * without {@code golden-masters:} — the entity exposure map rides the {@code @contracts} bundle
   * {@code contract-docs} builds from {@code golden-masters.from}, so there is nothing to ride
   * without one.
   */
  private static String entities(Map<?, ?> map, Source goldenMasters, String configPath) {
    String what = KEY + "." + ENTITIES_KEY;
    if (goldenMasters == null) {
      throw new CiConfigException(
          configPath
              + ": "
              + what
              + " is declared without '"
              + GOLDEN_MASTERS_KEY
              + "' — the entity exposure map is packed into the golden-masters docs bundle, so it"
              + " needs that tree declared too");
    }
    String path =
        CiReleaseSlotParser.requireDownward(map.get(ENTITIES_KEY), configPath, "'" + what + "'");
    if (!path.endsWith(".json")) {
      throw new CiConfigException(
          configPath
              + ": "
              + what
              + " is '"
              + path
              + "' — it is the path to the entity exposure map EntityExposureTest writes, and must"
              + " end in .json");
    }
    return path;
  }

  /**
   * One pact entry: {@code { packages }} and nothing else. The tree is always {@value
   * #PACTS_DIRECTORY}; {@code from:} is refused with the migration it needs.
   */
  private static Source pactSource(Object raw, String configPath, String where) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          configPath
              + ": "
              + where
              + " must be a mapping of { packages }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    refusePublish(map, configPath, where);
    if (map.containsKey(FROM_KEY)) {
      throw new CiConfigException(
          configPath
              + ": "
              + where
              + " declares '"
              + FROM_KEY
              + "' — pacts are no longer packed from a directory of their own: keep every pact flat"
              + " in "
              + PACTS_DIRECTORY
              + " named <consumer repository>_<provider repository>.json, and drop '"
              + FROM_KEY
              + "'");
    }
    requireKeys(map, Set.of(PACKAGES_KEY), configPath, where, "'packages'");
    return new Source(PACTS_DIRECTORY, packages(map.get(PACKAGES_KEY), configPath, where));
  }

  private static Source source(Object raw, String configPath, String where) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          configPath
              + ": "
              + where
              + " must be a mapping of { from, packages }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    refusePublish(map, configPath, where);
    requireKeys(map, Set.of(FROM_KEY, PACKAGES_KEY), configPath, where, "'from' and 'packages'");
    String from =
        CiReleaseSlotParser.requireDownward(
            map.get(FROM_KEY), configPath, where + "." + FROM_KEY);
    return new Source(from, packages(map.get(PACKAGES_KEY), configPath, where));
  }

  private static List<Ecosystem> packages(Object rawPackages, String configPath, String where) {
    if (!(rawPackages instanceof List<?> list) || list.isEmpty()) {
      throw new CiConfigException(
          configPath
              + ": "
              + where
              + "."
              + PACKAGES_KEY
              + " must be a non-empty list of ecosystems, e.g. [maven, npm], got: "
              + CiConfigSchema.typeOf(rawPackages));
    }
    Set<Ecosystem> packages = new LinkedHashSet<>();
    for (Object element : list) {
      Ecosystem ecosystem = element instanceof String keyword ? Ecosystem.of(keyword) : null;
      if (ecosystem == null) {
        throw new CiConfigException(
            configPath
                + ": "
                + where
                + "."
                + PACKAGES_KEY
                + " names '"
                + element
                + "' — this qits-ci packages maven and npm");
      }
      if (!packages.add(ecosystem)) {
        throw new CiConfigException(
            configPath + ": " + where + "." + PACKAGES_KEY + " names '" + element + "' twice");
      }
    }
    return List.copyOf(packages);
  }

  private static void refusePublish(Map<?, ?> map, String configPath, String where) {
    if (map.containsKey(CiArtifact.PUBLISH_KEY)) {
      throw new CiConfigException(
          configPath
              + ": "
              + where
              + " declares "
              + CiArtifact.PUBLISH_KEY
              + " — every contract package is "
              + CiArtifact.Publish.IF_CHANGED.declared()
              + " by definition, so the key is not offered");
    }
  }

  private static void requireKeys(
      Map<?, ?> map, Set<String> allowed, String configPath, String where, String vocabulary) {
    for (Object key : map.keySet()) {
      if (!(key instanceof String name) || !allowed.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": "
                + where
                + " declares an unknown key '"
                + key
                + "' — it declares only "
                + vocabulary);
      }
    }
  }
}
