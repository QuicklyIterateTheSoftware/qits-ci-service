package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link CiConfigSource} for the ci suite: an in-memory catalogue of trigger files per (repoId,
 * branch, scope), plus per-commit answers to the commit-held probe.
 *
 * <p><b>It used to carry a third thing and the push arm took it with it.</b> {@code read(repo,
 * branch, sha)} answered a whole {@code ConfigLookup} — FOUND with content, ABSENT, GONE,
 * UNREACHABLE, INVALID — because the push path read a pipeline out of a commit on the run worker.
 * Nothing reads a pipeline out of a commit any more: a trigger file is read at a branch head by
 * {@code CiEventTriggerService}, and the only per-commit question left is whether the repository
 * still holds the sha a step container failed to check out.
 *
 * <p>So a commit is {@link CommitHeld#HELD} unless a test says otherwise, which is the interesting
 * default rather than a neutral one: it is what a healthy git host answers, and staging a discard
 * therefore has to be deliberate.
 */
@Mock
@ApplicationScoped
public class FakeCiConfigSource implements CiConfigSource {

  /**
   * Queued answers per commit, so a test can model a repository that changed between two probes.
   * The last value stands for every further read.
   */
  private final Map<String, Deque<CommitHeld>> byCommit = new HashMap<>();

  /**
   * The event half, per (repoId, branch, scope). Unseeded repositories answer {@link
   * EventTriggerLookup#found} with no files — "this repository declares no trigger", which is the
   * ordinary case and not an error.
   *
   * <p>The scope is part of the key because a platform read and a repository read of the same
   * repository answer different files, which is the whole of what the scope means.
   */
  private final Map<String, EventTriggerLookup> triggersByBranch = new HashMap<>();

  /**
   * The by-path half, per (repoId, rev, path). An unseeded path is {@link FileLookup#absent()} —
   * "this repository declares no such file", which for {@code .config/qits/release.yml} is every
   * repository that has not migrated and is what keeps the legacy behaviour the default in the suite.
   */
  private final Map<String, FileLookup> filesByPath = new HashMap<>();

  /** Every {@code readFile} this fake was asked, in order. */
  private final List<String> fileReads = Collections.synchronizedList(new ArrayList<>());

  /** Every commit-held probe this fake was asked, in order. */
  private final List<String> commitProbes = Collections.synchronizedList(new ArrayList<>());

  /** Every {@code readEventTriggers} this fake was asked, in order — the listing's own assertion. */
  private final List<String> triggerReads = Collections.synchronizedList(new ArrayList<>());

  /**
   * The tags per repository. An unseeded repository answers {@link TagLookup#found} with none —
   * "this repository has never been tagged", which is the honest default and the interesting one:
   * it is exactly the state a fresh estate's wrapper is in, so the never-released case needs no
   * staging and a test that wants an archetype read has to say which version it was released at.
   */
  private final Map<String, TagLookup> tagsByRepo = new HashMap<>();

  /** Every {@code readTags} this fake was asked, in order — one per evaluation is the assertion. */
  private final List<String> tagReads = Collections.synchronizedList(new ArrayList<>());

  /**
   * Every reference this fake was addressed with, in order — how a test says whether a read went out
   * name-addressed or id-addressed without standing up an HTTP server for it. The url shapes
   * themselves are {@code HttpGitConfigSourceTest}'s.
   */
  private final List<CiRepoRef> addressed = Collections.synchronizedList(new ArrayList<>());

  /**
   * Appends an answer to the commit-held probe: the first {@code put} answers the first probe, the
   * second the next, … An unseeded commit is {@link CommitHeld#HELD}.
   */
  public void putCommit(String repoId, String sha, CommitHeld held) {
    byCommit.computeIfAbsent(repoId + "@" + sha, k -> new ArrayDeque<>()).add(held);
  }

  /** Seeds the trigger files a repository's branch head carries. */
  public void putTriggers(String repoId, String branch, String headSha, EventTriggerFile... files) {
    putTriggers(repoId, branch, CiTriggerScope.REPOSITORY, headSha, files);
  }

  /** The same, for one scope — how a test seeds the platform-pipelines repository's own files. */
  public void putTriggers(
      String repoId,
      String branch,
      CiTriggerScope scope,
      String headSha,
      EventTriggerFile... files) {
    triggersByBranch.put(key(repoId, branch, scope), EventTriggerLookup.found(headSha, List.of(files)));
  }

  /** Seeds a repository whose branch cannot be read at all — deleted, or the git host is down. */
  public void putTriggersUnreachable(String repoId, String branch) {
    putTriggersUnreachable(repoId, branch, CiTriggerScope.REPOSITORY);
  }

  /** The same, for one scope. */
  public void putTriggersUnreachable(String repoId, String branch, CiTriggerScope scope) {
    triggersByBranch.put(key(repoId, branch, scope), EventTriggerLookup.unreachable());
  }

  private static String key(String repoId, String branch, CiTriggerScope scope) {
    return repoId + "@" + branch + "#" + scope;
  }

  /** Seeds the tags a repository holds, exactly as a git host would advertise them. */
  public void putTags(String repoId, RepoTag... tags) {
    tagsByRepo.put(repoId, TagLookup.found(List.of(tags)));
  }

  /**
   * Seeds a repository at one released version: the tag a release cut, pointing at {@code sha}.
   * The shorthand every test that wants an archetype read uses, since what it needs is one released
   * version and not a tag namespace.
   */
  public void putReleasedVersion(String repoId, String version, String sha) {
    putTags(repoId, new RepoTag(version, sha));
  }

  /** Seeds a repository whose tags the git host could not answer for — not an empty listing. */
  public void putTagsUnreachable(String repoId) {
    tagsByRepo.put(repoId, TagLookup.unreachable());
  }

  /** Seeds one file readable by path at a rev. */
  public void putFile(String repoId, String rev, String path, String content) {
    filesByPath.put(fileKey(repoId, rev, path), FileLookup.found(content));
  }

  /** Seeds a path the git host could not answer for at all — the third answer, not the second. */
  public void putFileUnreachable(String repoId, String rev, String path) {
    filesByPath.put(fileKey(repoId, rev, path), FileLookup.unreachable());
  }

  private static String fileKey(String repoId, String rev, String path) {
    return repoId + "@" + rev + "/" + path;
  }

  public List<String> fileReads() {
    return List.copyOf(fileReads);
  }

  public List<String> commitProbes() {
    return List.copyOf(commitProbes);
  }

  public List<String> triggerReads() {
    return List.copyOf(triggerReads);
  }

  public List<String> tagReads() {
    return List.copyOf(tagReads);
  }

  public List<CiRepoRef> addressed() {
    return List.copyOf(addressed);
  }

  public void reset() {
    byCommit.clear();
    triggersByBranch.clear();
    filesByPath.clear();
    commitProbes.clear();
    triggerReads.clear();
    fileReads.clear();
    tagsByRepo.clear();
    tagReads.clear();
    addressed.clear();
  }

  @Override
  public FileLookup readFile(CiRepoRef repo, String rev, String path) {
    String repoId = repo.repoId();
    addressed.add(repo);
    fileReads.add(fileKey(repoId, rev, path));
    FileLookup seeded = filesByPath.get(fileKey(repoId, rev, path));
    return seeded == null ? FileLookup.absent() : seeded;
  }

  @Override
  public CommitHeld commitHeld(CiRepoRef repo, String sha) {
    String repoId = repo.repoId();
    addressed.add(repo);
    commitProbes.add(repoId + "@" + sha);
    Deque<CommitHeld> queued = byCommit.get(repoId + "@" + sha);
    if (queued == null || queued.isEmpty()) {
      return CommitHeld.HELD;
    }
    // Keep the last value standing so repeated probes stay answerable.
    return queued.size() == 1 ? queued.peek() : queued.poll();
  }

  @Override
  public EventTriggerLookup readEventTriggers(
      CiRepoRef repo, String branch, CiTriggerScope scope) {
    String repoId = repo.repoId();
    addressed.add(repo);
    triggerReads.add(key(repoId, branch, scope));
    EventTriggerLookup seeded = triggersByBranch.get(key(repoId, branch, scope));
    return seeded == null ? EventTriggerLookup.found("0".repeat(40), List.of()) : seeded;
  }

  @Override
  public TagLookup readTags(CiRepoRef repo) {
    String repoId = repo.repoId();
    addressed.add(repo);
    tagReads.add(repoId);
    TagLookup seeded = tagsByRepo.get(repoId);
    return seeded == null ? TagLookup.found(List.of()) : seeded;
  }
}
