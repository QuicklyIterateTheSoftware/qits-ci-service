package eu.wohlben.qits.ci.control;

/**
 * Whether qits-artifacts holds one artifact at one version — the question {@link ReleaseJoin} asks
 * before it announces an {@code announce: if-published} entry, and the only place qits-ci observes
 * rather than believes what a release pipeline published (see {@link CiArtifact}).
 *
 * <p>The port is here and the client is not, for the rule every port in {@code ci/control} follows:
 * {@code ci/} stays free of {@code java.net.http}. The production implementation is {@code
 * service/…/registry/HttpArtifactPresence}.
 *
 * <p><b>One attempt per call.</b> How often to ask again, and what to do when the answer never
 * becomes conclusive, is the join's decision rather than the client's, so it can be read in one
 * place beside the announcement it guards.
 */
public interface CiArtifactPresence {

  /**
   * Asks once.
   *
   * @param type {@link CiArtifact.Type#MAVEN} or {@link CiArtifact.Type#NPM} — the only two types
   *     that may declare {@code if-published}; any other is answered {@link Verdict#INCONCLUSIVE}
   * @param name the declared coordinate — {@code group:artifact} for maven, the package name for npm
   * @param version the release version
   */
  Probe probe(CiArtifact.Type type, String name, String version);

  /**
   * Asks once which version is the newest the store holds, by version order — the second question
   * the join puts to a {@code publish: if-changed} entry absent at the release version (qits-620).
   * {@link Verdict#PRESENT} carries that version in {@link Probe#version}; {@link Verdict#ABSENT}
   * means no version of the name exists at all.
   *
   * @param type {@link CiArtifact.Type#MAVEN} or {@link CiArtifact.Type#NPM}; any other is answered
   *     {@link Verdict#INCONCLUSIVE}
   * @param name the declared coordinate
   */
  Probe newest(CiArtifact.Type type, String name);

  /** What one question learned. */
  enum Verdict {
    /** The store answered that the artifact exists at that version. */
    PRESENT,
    /** The store answered that it does not (a 404, or an npm packument without that version). */
    ABSENT,
    /** Nothing was learned: a 5xx, a timeout, an unreachable store, an unreadable answer. */
    INCONCLUSIVE
  }

  /**
   * One answer, and what was asked — the url and the status — so a log line can name it.
   *
   * @param verdict what was learned
   * @param detail a human sentence naming the request and its answer
   * @param version the version a {@link #newest} answer names when {@code PRESENT}, else null
   */
  record Probe(Verdict verdict, String detail, String version) {

    public Probe(Verdict verdict, String detail) {
      this(verdict, detail, null);
    }

    public static Probe present(String detail) {
      return new Probe(Verdict.PRESENT, detail);
    }

    /** A {@link #newest} answer: the store's newest version of the name. */
    public static Probe newest(String version, String detail) {
      return new Probe(Verdict.PRESENT, detail, version);
    }

    public static Probe absent(String detail) {
      return new Probe(Verdict.ABSENT, detail);
    }

    public static Probe inconclusive(String detail) {
      return new Probe(Verdict.INCONCLUSIVE, detail);
    }
  }
}
