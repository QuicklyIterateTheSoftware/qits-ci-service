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
 * <h2>The one observed case: {@code announce: if-published}</h2>
 *
 * <p>A {@code maven} or {@code npm} entry may declare {@code announce: if-published}, and for that
 * entry the join does look before it announces: {@link ReleaseJoin} asks qits-artifacts whether the
 * artifact exists at the release version (the version's {@code .pom} for maven, {@code
 * versions[<version>]} in the packument for npm, through {@link CiArtifactPresence}) and announces
 * only when it does. It is for a pipeline whose publish is conditional — a reactor that deploys
 * only the modules that changed — where the declaration is a superset of what a given release
 * really pushed. It is still not an observation of what the step <em>did</em>: it is a question put
 * to the store afterwards, which is why it stays limited to the two stores that answer it cheaply
 * and authoritatively, and why {@code always} remains the default and today's behaviour.
 *
 * @param type the registry the artifact is published to
 * @param name the exact coordinate, as that registry names it
 * <h2>{@code publish:} (qits-620), and why it is here before it is accepted</h2>
 *
 * <p>{@code publish: if-changed} is the content-gated successor of {@code announce:}: the platform
 * itself uploads the entry, only when its content differs from the newest published version, and
 * the join records which way that went. It rides on this record for {@code announce}'s reason — it
 * has to survive the round trip through the composed trigger document the join reads. <b>This
 * qits-ci recognises the key and refuses it</b> ({@link #requirePublish}): release A of qits-640
 * ships the decision record and its read door first, and release B, which composes the publishing
 * postlude, is what accepts it. Every entry is therefore {@link Publish#ALWAYS} until then.
 *
 * @param type the registry the artifact is published to
 * @param name the exact coordinate, as that registry names it
 * @param announce when a green, released run announces this entry — {@link Announce#ALWAYS} unless
 *     the file says otherwise
 * @param publish when the platform publishes this entry — {@link Publish#ALWAYS}, the only value
 *     this qits-ci parses
 */
public record CiArtifact(Type type, String name, Announce announce, Publish publish) {

  /** The key a declaration spells the policy with, in a trigger file and in {@code release.yml}. */
  public static final String ANNOUNCE_KEY = "announce";

  /** The key a declaration spells its publish policy with (qits-620; refused until release B). */
  public static final String PUBLISH_KEY = "publish";

  /**
   * The sentence every key this qits-ci recognises but does not yet act on ends with. One spelling,
   * so a person reading any of the refusals can grep for all of them.
   */
  static final String ARRIVES_LATER = "arrives in a later qits-ci";

  public CiArtifact {
    announce = announce == null ? Announce.ALWAYS : announce;
    publish = publish == null ? Publish.ALWAYS : publish;
  }

  /** A declaration with the default policy — announced whenever the join closes. */
  public CiArtifact(Type type, String name) {
    this(type, name, Announce.ALWAYS, Publish.ALWAYS);
  }

  /** A declaration with an announce policy and the default publish policy. */
  public CiArtifact(Type type, String name, Announce announce) {
    this(type, name, announce, Publish.ALWAYS);
  }

  /** Whether the join must ask the store before announcing this entry. */
  public boolean announceIfPublished() {
    return announce == Announce.IF_PUBLISHED;
  }

  /** Whether the platform publishes this entry only when its content changed. */
  public boolean publishIfChanged() {
    return publish == Publish.IF_CHANGED;
  }

  /**
   * When the platform publishes a declared {@code maven} or {@code npm} entry. <b>The declared
   * spelling is also what the composed trigger document and the owed row carry</b>, {@link
   * Announce}'s arrangement.
   */
  public enum Publish {
    /** Published at every release version — the default, and what every entry is today. */
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
   * The {@code publish:} value of one artifact entry, for both parsers. Absent is {@link
   * Publish#ALWAYS}; <b>present is refused, whatever it says</b>, because this qits-ci composes no
   * publishing postlude yet and an accepted {@code if-changed} would be a gate nothing applies.
   * Release B of qits-640 replaces the refusal with the real rule (the value, the type, the {@code
   * sbom:} it needs, and the clash with {@code announce:}) — this method is the one place it lands.
   */
  static Publish requirePublish(
      Object value, Type type, String name, String configPath, int index) {
    if (value == null) {
      return Publish.ALWAYS;
    }
    throw new CiConfigException(
        entry(configPath, index, type, name)
            + " declares "
            + PUBLISH_KEY
            + ": "
            + value
            + " — "
            + PUBLISH_KEY
            + ": "
            + ARRIVES_LATER);
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
   * When a declared artifact is announced. <b>The declared spelling is also what the composed
   * trigger document and the owed row carry</b>, so the vocabulary exists once.
   */
  public enum Announce {
    /** Announced whenever the join closes — the declaration is believed. The default. */
    ALWAYS("always"),
    /**
     * Announced only when qits-artifacts holds the artifact at the release version. {@code maven}
     * and {@code npm} only — see {@link #allowedFor}.
     */
    IF_PUBLISHED("if-published");

    private final String declared;

    Announce(String declared) {
      this.declared = declared;
    }

    /** How a file spells it. */
    public String declared() {
      return declared;
    }

    /** The policy this keyword names, or null — the parsers turn null into a parse error. */
    public static Announce of(String keyword) {
      for (Announce announce : values()) {
        if (announce.declared.equals(keyword)) {
          return announce;
        }
      }
      return null;
    }

    /**
     * Whether a type may carry this policy. {@code if-published} needs a store that answers "does
     * this version exist" authoritatively and cheaply, which the hosted maven and npm repositories
     * do; a docker tag, a daemon binary and a docs site are not asked, so declaring it there would
     * be a check that silently never happens.
     */
    public boolean allowedFor(Type type) {
      return this == ALWAYS || type == Type.MAVEN || type == Type.NPM;
    }
  }

  /**
   * The {@code announce:} value of one artifact entry, for both parsers: absent is {@link
   * Announce#ALWAYS}; an unknown word, a non-string, or {@code if-published} on a type that cannot
   * carry it is a {@link CiConfigException} naming the file and the entry.
   */
  static Announce requireAnnounce(Object value, Type type, String name, String configPath, int index) {
    if (value == null) {
      return Announce.ALWAYS;
    }
    Announce announce = value instanceof String keyword ? Announce.of(keyword) : null;
    if (announce == null) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " declares "
              + ANNOUNCE_KEY
              + " '"
              + value
              + "' — it is '"
              + Announce.ALWAYS.declared()
              + "' (the default) or '"
              + Announce.IF_PUBLISHED.declared()
              + "'");
    }
    if (!announce.allowedFor(type)) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " ({ type: "
              + type.declared()
              + ", name: "
              + name
              + " }) declares "
              + ANNOUNCE_KEY
              + ": "
              + announce.declared()
              + " — only a maven or npm entry can be checked against qits-artifacts before it is"
              + " announced");
    }
    return announce;
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
