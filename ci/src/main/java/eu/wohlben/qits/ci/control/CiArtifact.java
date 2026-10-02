package eu.wohlben.qits.ci.control;

import java.util.Arrays;
import java.util.List;

/**
 * One artifact a trigger file declares its pipeline publishes: {@code {type: npm, name:
 * "@qits/ui-components"}}.
 *
 * <p><b>Declared, not observed</b>, and that is the decision rather than a shortcut. qits-ci never
 * learns how to publish anything — every {@code npm publish}, {@code mvn deploy}, and {@code docker
 * push} on this platform lives in a step script inside the repository's own container — so what a
 * run published is not a thing this process can see. It could have been reported back, and it
 * deliberately is not:
 * the daemon's return channel carries only {@code StepChunk} and {@code StepFinished}, a stdout
 * sentinel is forbidden by design, and an emit-based scheme would have been a two-repo protocol
 * change. A declaration costs none of that and buys the thing an emission never could — it can be
 * <b>read statically</b>, so the cross-repo dependency graph the parked cycle-detection work needs
 * is derivable from the trigger files alone, without running a single pipeline.
 *
 * <p>The price is honest and worth naming: a declaration can lie. A pipeline that goes green without
 * publishing announces an artifact that is not there. By default nothing here checks.
 *
 * <h2>{@code publish: if-changed} (qits-620): the platform uploads, and the join records which way it went</h2>
 *
 * <p>A {@code maven} or {@code npm} entry is uploaded by the platform itself — one {@code qits
 * artifacts publish maven|npm} call the composed postlude makes per entry ({@link
 * CiReleaseComposer}) — and with {@code if-changed} only when its content differs from the newest
 * published version. That is the one declaration the join checks rather than believes: it asks the
 * store which way the publish went ({@link ReleaseJoin}, through {@link CiArtifactPresence}) —
 * present at the release version is {@code published}, absent with a newest version is {@code
 * unchanged since <v>} — and only {@code published} is announced. It rides on this record because
 * it has to survive the round trip through the composed trigger document the join reads.
 *
 * <p>History: {@code announce: if-published} (qits-561) was the first checked declaration, for a
 * repository whose own steps published conditionally. {@code publish: if-changed} replaced it, and
 * qits-648 deleted the key; it is an unknown key in both parsers now (see {@link
 * #retiredKeyHint}).
 *
 * @param type the registry the artifact is published to
 * @param name the exact coordinate, as that registry names it
 * <h2>{@code section} (qits-666): which part of {@code release.yml} declared it</h2>
 *
 * <p>The composer folds a {@code contracts:} package into the composed {@code artifacts:} block as an
 * ordinary {@code if-changed} entry, so without a marker the join could not tell it from an entry the
 * repository declared. {@link Section#CONTRACTS} is that marker: the composer writes {@code section:
 * contracts} on such an entry (and nothing on any other, so every other composed document is
 * byte-identical), the trigger parser reads it back, the owed row keeps it, and it leaves on {@code
 * SoftwareRelease.section}.
 *
 * @param publish when the platform publishes this entry — {@link Publish#ALWAYS} unless the file
 *     says otherwise
 * @param section which section declared it — {@link Section#ARTIFACTS} unless it is a contract
 *     package
 */
public record CiArtifact(Type type, String name, Publish publish, Section section) {

  /** The key a declaration spells its publish policy with (qits-620). */
  public static final String PUBLISH_KEY = "publish";

  /** The key a composed document marks a contract package with (qits-666). */
  public static final String SECTION_KEY = "section";

  /**
   * The key qits-648 deleted. Spelled here only so an unknown-key error can say what replaced it;
   * no parser accepts it.
   */
  static final String RETIRED_ANNOUNCE_KEY = "announce";

  public CiArtifact {
    publish = publish == null ? Publish.ALWAYS : publish;
    section = section == null ? Section.ARTIFACTS : section;
  }

  /** A declaration from {@code artifacts:} with the given policy. */
  public CiArtifact(Type type, String name, Publish publish) {
    this(type, name, publish, Section.ARTIFACTS);
  }

  /** A declaration with the default policy — published and announced at every release. */
  public CiArtifact(Type type, String name) {
    this(type, name, Publish.ALWAYS);
  }

  /** Whether the platform publishes this entry only when its content changed. */
  public boolean publishIfChanged() {
    return publish == Publish.IF_CHANGED;
  }

  /**
   * When the platform publishes a declared {@code maven} or {@code npm} entry. <b>The declared
   * spelling is also what the composed trigger document and the owed row carry</b>, so the
   * vocabulary exists once.
   */
  public enum Publish {
    /** Published at every release version — the default. */
    ALWAYS("always"),
    /**
     * Published only when the content differs from the newest published version; otherwise the join
     * records {@code unchanged since <v>} and announces nothing.
     */
    IF_CHANGED("if-changed");

    private final String declared;

    Publish(String declared) {
      this.declared = declared;
    }

    /** How a file spells it. */
    public String declared() {
      return declared;
    }

    /** The policy this keyword names, or null — the parsers turn null into a parse error. */
    public static Publish of(String keyword) {
      for (Publish publish : values()) {
        if (publish.declared.equals(keyword)) {
          return publish;
        }
      }
      return null;
    }
  }

  /**
   * Which section of {@code release.yml} an entry came from. <b>The declared spelling is the wire
   * value</b> of {@code SoftwareRelease.section} and what the owed row carries.
   */
  public enum Section {
    /** An {@code artifacts:} entry — the default, and never written into a composed document. */
    ARTIFACTS("artifacts"),
    /** A contract package the platform packs and publishes from {@code contracts:}. */
    CONTRACTS("contracts");

    private final String declared;

    Section(String declared) {
      this.declared = declared;
    }

    /** How a file and the wire spell it. */
    public String declared() {
      return declared;
    }

    /** The section this keyword names, or null — the trigger parser turns null into an error. */
    public static Section of(String keyword) {
      for (Section section : values()) {
        if (section.declared.equals(keyword)) {
          return section;
        }
      }
      return null;
    }
  }

  /**
   * The {@code section:} value of one composed-document entry. Absent is {@link Section#ARTIFACTS};
   * an unknown value is a parse error naming the entry.
   */
  static Section requireSection(Object value, Type type, String name, String configPath, int index) {
    if (value == null) {
      return Section.ARTIFACTS;
    }
    Section section = value instanceof String keyword ? Section.of(keyword) : null;
    if (section == null) {
      throw new CiConfigException(
          entry(configPath, index, type, name)
              + " declares "
              + SECTION_KEY
              + " '"
              + value
              + "' — it is '"
              + Section.ARTIFACTS.declared()
              + "' (the default) or '"
              + Section.CONTRACTS.declared()
              + "'");
    }
    return section;
  }

  /**
   * The {@code publish:} value of one artifact entry, for both parsers. Absent is {@link
   * Publish#ALWAYS}. <b>The key itself is refused on a {@code docker}, {@code daemon} or {@code docs}
   * entry</b>, {@code always} included: the platform uploads only maven and npm, so on any other type
   * the word would describe a publish nothing here performs. An unknown value is a parse error too.
   *
   * <p>What {@code if-changed} additionally needs from a {@code release.yml} entry — an {@code
   * sbom:} — is the slot parser's, because a composed trigger document carries no SBOM path: a
   * contract package is {@code if-changed} with no SBOM at all.
   */
  static Publish requirePublish(
      Object value, Type type, String name, String configPath, int index) {
    if (value == null) {
      return Publish.ALWAYS;
    }
    if (type != Type.MAVEN && type != Type.NPM) {
      throw new CiConfigException(
          entry(configPath, index, type, name)
              + " declares "
              + PUBLISH_KEY
              + ": "
              + value
              + " — only a maven or npm entry is published by the platform; a docker, daemon or docs"
              + " entry is published by its own step");
    }
    Publish publish = value instanceof String keyword ? Publish.of(keyword) : null;
    if (publish == null) {
      throw new CiConfigException(
          entry(configPath, index, type, name)
              + " declares "
              + PUBLISH_KEY
              + " '"
              + value
              + "' — it is '"
              + Publish.ALWAYS.declared()
              + "' (the default) or '"
              + Publish.IF_CHANGED.declared()
              + "'");
    }
    return publish;
  }

  /**
   * What an unknown-key error appends for {@code key}: for the deleted {@code announce:} (qits-648),
   * that {@code publish:} replaced it; for any other key, nothing.
   */
  static String retiredKeyHint(Object key) {
    return RETIRED_ANNOUNCE_KEY.equals(key)
        ? " — "
            + RETIRED_ANNOUNCE_KEY
            + ": was deleted (qits-648); "
            + PUBLISH_KEY
            + ": "
            + Publish.IF_CHANGED.declared()
            + " replaced it"
        : "";
  }

  /**
   * How a parse error names one entry: the file, the index and the entry itself, {@code
   * <file>: artifact <i> ({ type: maven, name: a:b })}.
   */
  static String entry(String configPath, int index, Type type, String name) {
    return configPath
        + ": artifact "
        + index
        + " ({ type: "
        + type.declared()
        + ", name: "
        + name
        + " })";
  }

  /**
   * The registries a declaration may name. <b>The constant's declared spelling is also its wire
   * value</b> — {@code type: npm} in the file becomes {@code "packageType": "npm"} in the published
   * {@code SoftwareRelease} — so the vocabulary exists once and cannot drift between the parser and
   * the event.
   *
   * <p>Maven names a published GAV, for example {@code eu.wohlben.qits:qits-eventstream}. The
   * repository host is omitted for the same portability reason as docker's: the consumer supplies
   * the address from its own environment.
   *
   * <p>{@code daemon} names a platform daemon binary, for example {@code qits-ci-daemon} — an
   * executable qits-artifacts holds and the platform downloads and runs, rather than a package any
   * third-party tool installs. It is here so the release train can announce a daemon like anything
   * else it builds; before it existed, the one binary every CI run depends on was the only artifact
   * on the platform no {@code SoftwareRelease} could name. <b>qits-ci publishes none of these</b>,
   * exactly as it publishes no npm package — the PUT to qits-artifacts is a step in the daemon
   * repository's own release pipeline, and the declaration here is what turns that pipeline's green
   * run into an announcement.
   *
   * <p>{@code docs} names a published documentation site, for example {@code @qits/ui-components} —
   * a built static bundle qits-artifacts holds per version and qits-platform-docs serves. The name
   * is the <b>site</b> name, which for a library is conventionally its package name, so one release
   * declaring both {@code npm} and {@code docs} repeats it; that repetition is the point, since the
   * two entries announce two different things arriving at two different addresses. Its publish is a
   * step in the documented repository's own release pipeline like every other type here.
   */
  public enum Type {
    NPM("npm"),
    MAVEN("maven"),
    DOCKER("docker"),
    DAEMON("daemon"),
    DOCS("docs");

    private final String declared;

    Type(String declared) {
      this.declared = declared;
    }

    /** How a trigger file spells it, and how the wire spells it. */
    public String declared() {
      return declared;
    }

    /** The type this keyword names, or null — the parser turns null into the file's parse error. */
    static Type of(String keyword) {
      for (Type type : values()) {
        if (type.declared.equals(keyword)) {
          return type;
        }
      }
      return null;
    }

    /**
     * The vocabulary as a message fragment, so an error names what this qits-ci knows.
     *
     * <p>Derived from {@link #values()} rather than spelled out, because a hand-written list is a
     * second place a new type has to be added and the only symptom of forgetting is an error message
     * that refuses a keyword it does not admit to knowing.
     */
    static String vocabulary() {
      List<String> all = Arrays.stream(values()).map(Type::declared).toList();
      return String.join(", ", all.subList(0, all.size() - 1)) + " and " + all.getLast();
    }
  }
}
