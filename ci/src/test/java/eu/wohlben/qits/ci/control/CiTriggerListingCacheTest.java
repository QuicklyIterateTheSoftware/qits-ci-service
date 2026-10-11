package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CiTriggerListingCache} on its own, hand-wired: no container, a counting {@link
 * CiConfigSource} and a clock the test moves. Each case is one rule of the class javadoc — what is
 * served from memory, what forgets it, and the two answers that must never be remembered (an
 * unreachable listing, and a listing that was in flight across an invalidation).
 *
 * <p>The engine's half — that a push event really reaches {@link CiTriggerListingCache#invalidate}
 * before the evaluation it causes — is {@code CiTriggerListingCacheWiringTest}.
 */
public class CiTriggerListingCacheTest {

  private static final String BRANCH = "main";
  private static final Duration TTL = Duration.ofMinutes(5);

  private static final CiRepoRef X = CiRepoRef.of("repo-x", "qits", "x");
  private static final CiRepoRef Y = CiRepoRef.of("repo-y", "qits", "y");

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private CountingSource source;
  private CiTriggerListingCache cache;

  /**
   * A git host that counts its listings, answers a configurable head per repository, and can run
   * one hook <em>during</em> a read — which is how "invalidated while in flight" is staged on one
   * thread, without a latch.
   */
  private static final class CountingSource implements CiConfigSource {
    final Map<String, EventTriggerLookup> answers = new HashMap<>();
    final List<String> reads = new ArrayList<>();
    Consumer<CiRepoRef> during = repo -> {};

    @Override
    public FileLookup readFile(CiRepoRef repo, String rev, String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CommitHeld commitHeld(CiRepoRef repo, String sha) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EventTriggerLookup readEventTriggers(
        CiRepoRef repo, String branch, CiTriggerScope scope) {
      reads.add(repo.repoId() + "#" + scope);
      // The head is resolved first and the hook runs after it — a push landing between the host
      // answering and the cache storing that answer, which is the window the guard is for.
      EventTriggerLookup answer = answers.get(repo.repoId());
      during.accept(repo);
      return answer == null ? EventTriggerLookup.found("0".repeat(40), List.of()) : answer;
    }

    long readsOf(CiRepoRef repo) {
      return reads.stream().filter(r -> r.startsWith(repo.repoId() + "#")).count();
    }
  }

  @BeforeEach
  void wire() {
    source = new CountingSource();
    cache = new CiTriggerListingCache(source, TTL, now::get);
  }

  private EventTriggerLookup read(CiRepoRef repo) {
    return cache.readEventTriggers(repo, BRANCH, CiTriggerScope.REPOSITORY);
  }

  private static EventTriggerLookup head(String sha) {
    return EventTriggerLookup.found(
        sha, List.of(new EventTriggerFile(".config/qits/ci-event-a.yml", "event: X\nsteps: []\n")));
  }

  @Test
  public void aSecondReadOfAnUnmovedRepositoryIsServedFromMemory() {
    source.answers.put(X.repoId(), head("a".repeat(40)));

    EventTriggerLookup first = read(X);
    EventTriggerLookup second = read(X);

    assertEquals(1, source.readsOf(X), "the host is asked once until the repository moves");
    assertSame(first, second);
    assertEquals("a".repeat(40), second.headSha());
  }

  @Test
  public void theScopeIsPartOfTheKey() {
    cache.readEventTriggers(X, BRANCH, CiTriggerScope.REPOSITORY);
    cache.readEventTriggers(X, BRANCH, CiTriggerScope.PLATFORM);
    cache.readEventTriggers(X, BRANCH, CiTriggerScope.PLATFORM);

    assertEquals(List.of("repo-x#REPOSITORY", "repo-x#PLATFORM"), source.reads);
  }

  @Test
  public void invalidatingOneRepositoryRereadsItAndNoOther() {
    source.answers.put(X.repoId(), head("a".repeat(40)));
    read(X);
    read(Y);
    cache.readEventTriggers(X, BRANCH, CiTriggerScope.PLATFORM);

    source.answers.put(X.repoId(), head("b".repeat(40)));
    cache.invalidate(X.repoId());

    assertEquals("b".repeat(40), read(X).headSha(), "the push's new head, not the remembered one");
    cache.readEventTriggers(X, BRANCH, CiTriggerScope.PLATFORM);
    read(Y);
    assertEquals(4, source.readsOf(X), "every scope of the moved repository is forgotten");
    assertEquals(1, source.readsOf(Y), "a repository nobody pushed to stays remembered");
  }

  @Test
  public void invalidateAllForgetsEveryRepository() {
    read(X);
    read(Y);

    cache.invalidateAll();
    read(X);
    read(Y);

    assertEquals(2, source.readsOf(X));
    assertEquals(2, source.readsOf(Y));
  }

  @Test
  public void aBlankRepoIdInvalidatesEverything() {
    read(X);
    cache.invalidate(" ");
    read(X);
    assertEquals(2, source.readsOf(X), "an unattributable ref move forgets rather than ignores");
  }

  @Test
  public void aReadInFlightAcrossAnInvalidationIsNeverServed() {
    // The race: the worker's listing resolved the OLD head, and while it was still being answered
    // the listener thread forgot X for a push. Storing that answer would hand the push's own
    // evaluation the pre-push head.
    source.answers.put(X.repoId(), head("a".repeat(40)));
    source.during =
        repo -> {
          source.during = r -> {};
          cache.invalidate(repo.repoId());
          source.answers.put(X.repoId(), head("b".repeat(40)));
        };

    assertEquals("a".repeat(40), read(X).headSha(), "the in-flight caller still gets its answer");
    assertEquals("b".repeat(40), read(X).headSha(), "but nobody after it does");
    assertEquals(2, source.readsOf(X));
    assertEquals("b".repeat(40), read(X).headSha());
    assertEquals(2, source.readsOf(X), "the read after the invalidation is the one remembered");
  }

  @Test
  public void aReadInFlightAcrossAnInvalidateAllIsNeverServed() {
    source.during =
        repo -> {
          source.during = r -> {};
          cache.invalidateAll();
        };

    read(X);
    read(X);

    assertEquals(2, source.readsOf(X));
  }

  @Test
  public void anUnreachableListingIsNeverRemembered() {
    source.answers.put(X.repoId(), EventTriggerLookup.unreachable());
    read(X);
    read(X);
    assertEquals(2, source.readsOf(X), "a git-host blip is asked again, not remembered for a TTL");
    assertEquals(0, cache.size());

    source.answers.put(X.repoId(), head("a".repeat(40)));
    assertEquals(EventTriggerLookup.Status.FOUND, read(X).status());
    read(X);
    assertEquals(3, source.readsOf(X), "the first answer after recovery is the one remembered");
  }

  @Test
  public void anEntryOlderThanTheTtlIsReadAgain() {
    read(X);
    now.addAndGet(TTL.toNanos() - 1);
    read(X);
    assertEquals(1, source.readsOf(X), "just inside the TTL is still a hit");

    now.addAndGet(1);
    read(X);
    assertEquals(2, source.readsOf(X), "a lost announcement costs at most one TTL");
  }

  @Test
  public void aStoreSweepsExpiredEntriesOfOtherRepositories() {
    read(X);
    now.addAndGet(TTL.toNanos());
    read(Y);
    assertEquals(1, cache.size(), "X stopped being asked for and does not outlive its TTL");
  }

  @Test
  public void aZeroTtlReadsThroughEveryTime() {
    cache = new CiTriggerListingCache(source, Duration.ZERO, now::get);
    read(X);
    read(X);
    assertEquals(2, source.readsOf(X));
    assertEquals(0, cache.size());
  }

  @Test
  public void aRepositoryWithNoStorageIdIsNeverCached() {
    // Nothing could ever invalidate it — a push announces the storage id — so it reads through.
    CiRepoRef unkeyed = new CiRepoRef(null, "qits", "nameless");
    cache.readEventTriggers(unkeyed, BRANCH, CiTriggerScope.REPOSITORY);
    cache.readEventTriggers(unkeyed, BRANCH, CiTriggerScope.REPOSITORY);
    assertEquals(2, source.reads.size());
    assertEquals(0, cache.size());
  }
}
