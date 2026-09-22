package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * Where a repository's committed pipeline files come from, and whether it still holds a commit. The
 * production implementation is {@code githost/HttpGitConfigSource} in the {@code service} module —
 * it reads both off the git host's content endpoints — and it is a port here for the reason {@link
 * GitHostRepoListing} is: {@code ci} stays free of {@code java.net.http}. Tests replace it with an
 * in-memory fake ({@code @io.quarkus.test.Mock}).
 *
 * <p><b>There used to be a third answer here and it retired with per-push CI (2026-09-05).</b>
 * {@code ConfigLookup read(repo, branch, sha)} fetched {@code .config/qits/ci-post-receive.yml} at a
 * pushed commit and answered FOUND/ABSENT/GONE/UNREACHABLE/INVALID — five outcomes that existed to
 * decide what a push run should record. No push becomes a run any more, so four of them have no
 * reader; what survived is the one question the <em>shared</em> run path still asks, and {@link
 * #commitHeld} is that question under its own name.
 */
public interface CiConfigSource {

  /**
   * Whether a repository still holds a commit. Three answers, and the third is not a weaker second:
   * a host that could not be asked has said nothing about the commit, and a caller that read it as
   * {@link #GONE} would discard a run over a network blip.
   */
  enum CommitHeld {
    /** The repository resolves the commit. */
    HELD,
    /**
     * The repository does not hold the commit at all — it was amended or force-pushed away and
     * garbage-collected.
     *
     * <p>It is <b>held</b> rather than <b>reachable</b>, and the difference is deliberate: a commit
     * the branch has moved past is still held, and a run that built it still says something true.
     */
    GONE,
    /** The git host could not be reached, or answered something that is not an answer. */
    UNKNOWN
  }

  /**
   * Every trigger file of one {@link CiTriggerScope} at a branch's current head, with the head it
   * read them at. Empty {@code files} on a {@link Status#FOUND} is the ordinary case — most repositories
   * declare no event trigger — and is not an error.
   *
   * <p>The head sha travels with the files because a run records the commit it built, and the two
   * must be the same read: resolving the head twice would let a push land in between and record a
   * run against a commit whose trigger file said something else.
   */
  record EventTriggerLookup(Status status, String headSha, List<EventTriggerFile> files) {

    public enum Status {
      /** The branch was resolved. {@code files} is what it carries, possibly nothing. */
      FOUND,
      /**
       * The repository or the branch could not be read — the git host is down, the repository was
       * deleted, or it has no such branch. Indistinguishable here on purpose: all three mean "no
       * trigger can be evaluated for this repository right now", and none of them is a run.
       */
      UNREACHABLE
    }

    public static EventTriggerLookup found(String headSha, List<EventTriggerFile> files) {
      return new EventTriggerLookup(Status.FOUND, headSha, List.copyOf(files));
    }

    public static EventTriggerLookup unreachable() {
      return new EventTriggerLookup(Status.UNREACHABLE, null, List.of());
    }
  }

  /** One trigger file: its path in the tree, which is identity, and its content. */
  record EventTriggerFile(String path, String content) {}

  /**
   * One tag a repository holds, and the commit it resolves to.
   *
   * <p>The commit is the <b>peeled</b> one — what the tag really names in the tree — because the
   * only thing qits-ci does with a tag is read a file at it, and reading at an annotated tag's own
   * object would be reading at something that is not a commit at all.
   *
   * @param name the tag's short name, {@code refs/tags/} stripped: {@code 2026.922.161358}
   * @param commitSha the commit that name resolves to
   */
  record RepoTag(String name, String commitSha) {}

  /**
   * Every tag a repository holds, or the statement that it could not be asked.
   *
   * <p><b>Two answers, and the second is not an empty first.</b> {@code FOUND} with no tags is a
   * repository that has never been tagged, which is a real and final fact about it; {@code
   * UNREACHABLE} is a git host that said nothing, which is a fact about the moment. {@link
   * CommitHeld#UNKNOWN}'s rule one method up, and the caller that collapsed them would read a blip
   * as "this repository has never released" — see {@code CiReleasedVersions}.
   */
  record TagLookup(Status status, List<RepoTag> tags) {

    public enum Status {
      /** The repository was read. {@code tags} is what it carries, possibly nothing. */
      FOUND,
      /** The git host could not be asked, or answered something that is not an answer. */
      UNREACHABLE
    }

    public static TagLookup found(List<RepoTag> tags) {
      return new TagLookup(Status.FOUND, List.copyOf(tags));
    }

    public static TagLookup unreachable() {
      return new TagLookup(Status.UNREACHABLE, List.of());
    }
  }

  /**
   * One file read by path, and the three answers a caller has to keep apart.
   *
   * <p>{@link Status#ABSENT} is "this repository declares no such file", which for {@code
   * .config/qits/release.yml} is a repository that declares no release cycle at all — honest, final,
   * and the ordinary answer for anything the platform does not release. {@link Status#UNREACHABLE}
   * is "nothing was learned", and collapsing the two would turn a git-host blip into a repository
   * that suddenly declares nothing — the same rule {@link CommitHeld#UNKNOWN} states one method up,
   * and since every repository keeps its whole release cycle in that one file, the cost of getting
   * it wrong is a release request hung on a QA verdict nobody will record. {@code
   * CiEventTriggerService} answers the third case by leaving the event OWED.
   */
  record FileLookup(Status status, String content) {

    public enum Status {
      /** The file is there. {@code content} is its text. */
      FOUND,
      /** The repository holds the rev and does not hold that path. */
      ABSENT,
      /** The git host could not be asked, or answered something that is not an answer. */
      UNREACHABLE
    }

    public static FileLookup found(String content) {
      return new FileLookup(Status.FOUND, content);
    }

    public static FileLookup absent() {
      return new FileLookup(Status.ABSENT, null);
    }

    public static FileLookup unreachable() {
      return new FileLookup(Status.UNREACHABLE, null);
    }
  }

  /**
   * Reads one file at a rev — the blob route the trigger listing already uses, addressed directly
   * rather than through a directory listing.
   *
   * <p>Two callers, and both read a path this engine spells rather than one a listing handed back:
   * {@code CiEventTriggerService} reads {@code .config/qits/release.yml} at the head the trigger
   * listing just resolved (one read, one commit — the listing's own discipline), and {@link
   * CiReleaseArchetypes} reads a recipe out of the platform-pipelines repository at its {@code main}.
   * The one repository-controlled part of either path is an archetype's name, which {@code
   * CiReleaseSlotParser} bounds to a plain slug before it can reach a URL.
   */
  FileLookup readFile(CiRepoRef repo, String rev, String path);

  /**
   * Every tag {@code repo} holds — the one question this port asks about a repository as a whole
   * rather than about one revision of it.
   *
   * <p><b>One caller, and it is the wrapper's half of a composed release pipeline.</b> An archetype
   * recipe is read out of the platform-pipelines repository at the newest version that repository
   * has <em>released</em> — a tag a person gated and approved — rather than at whatever {@code main}
   * happens to hold, so something has to enumerate the tags and {@code CiReleasedVersions} has to
   * pick among them. Nothing else here enumerates anything: a trigger listing lists one directory
   * of one revision, and both content reads are addressed at a rev the caller already has.
   *
   * <p><b>It asks the git host for refs, not for releases.</b> Which tag names are released versions
   * is qits-ci's own reading of a platform convention and lives in {@code CiReleasedVersions}, a
   * pure function over this answer; an implementation here reports what the host advertises and
   * judges none of it. Keeping the policy out of the adapter is what lets the shape of a version be
   * changed without touching a second module, and what keeps this method testable against a real
   * repository rather than against a convention.
   */
  TagLookup readTags(CiRepoRef repo);

  /**
   * Does {@code repo} still hold {@code sha}? Asked in exactly one place — {@code
   * CiRunService.runSteps}, when a step container reports it could not check the commit out — and
   * the two causes it tells apart mean opposite things to the record. The commit force-pushed away
   * since the run was accepted describes a push that no longer exists, so the run is discarded; a
   * commit the repository still holds means the clone failed for some other reason, which is a real
   * failure that stays red.
   *
   * <p>{@link CommitHeld#UNKNOWN} is neither, and a caller must not collapse it into {@code GONE}:
   * discarding a run because the git host was briefly unreachable would erase a verdict about a
   * commit nobody has any evidence against.
   *
   * <p>The repository arrives as a {@link CiRepoRef} rather than an id because the git host serves
   * the same content name-addressed, and after the identity cutover the id route is qits-projects'
   * alone. An implementation reads name-addressed when the reference {@link CiRepoRef#named() is
   * named} and id-addressed when it is not.
   */
  CommitHeld commitHeld(CiRepoRef repo, String sha);

  /**
   * Lists and reads the repository's event-trigger files at {@code branch}'s current head.
   *
   * <p>This is the whole of what qits-ci reads a repository's config <em>for</em>: an event names no
   * commit of its own, so the platform's one tracked branch supplies it (every submodule follows
   * {@code main}) and the head is resolved rather than given.
   */
  default EventTriggerLookup readEventTriggers(CiRepoRef repo, String branch) {
    return readEventTriggers(repo, branch, CiTriggerScope.REPOSITORY);
  }

  /**
   * The same read, for a chosen scope: the repository's own {@code ci-event-*.yml} or the platform's
   * {@code ci-platform-event-*.yml}.
   *
   * <p><b>One scope per call, never both.</b> A platform read is a read of a <em>different</em>
   * repository — the one {@code qits.ci.platform-pipelines-repository} names — and a repository read
   * of that same repository still answers only its own files. Merging them would make one lookup
   * mean two things and would hide which file a run came from.
   */
  EventTriggerLookup readEventTriggers(CiRepoRef repo, String branch, CiTriggerScope scope);
}
