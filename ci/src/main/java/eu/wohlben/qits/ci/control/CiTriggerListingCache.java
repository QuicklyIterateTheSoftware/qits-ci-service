package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The trigger engine's memory of what each candidate repository's {@code .config/qits/} said at
 * {@code main}, kept until that repository's ref moves (qits-168).
 *
 * <h2>Why it exists: one event used to cost one by-name lookup per candidate</h2>
 *
 * <p>{@link CiEventTriggerService} evaluates <b>every</b> event on the bus (its listener subscribes
 * to {@code "*"}), and each evaluation listed {@code .config/qits/} at {@code main} in <b>every</b>
 * candidate repository — about sixty of them. Each listing is a name-addressed read at the git host,
 * and qits-githost resolves every name-addressed read by asking qits-projects {@code GET
 * /projects/api/projects/{projectId}/repositories/by-name/{repoName}}. So an otherwise idle
 * platform — heartbeats, release-request churn, run announcements, nothing pushed — produced about
 * ten of those lookups a second, for answers that had not changed since the last push. The listing
 * is a function of the branch head, and the head only moves on a push; reading it per event was
 * paying per <em>event</em> for something that changes per <em>push</em>.
 *
 * <h2>What invalidates an entry, and why that is complete</h2>
 *
 * <p>qits-githost announces every ref move — a push, and each of its API doors that move a ref — as
 * {@code SCMPublishCommit}, {@code SCMDeleteBranch}, {@code SCMPublishTag} or {@code
 * SCMDeleteTag}, and every one of those carries the repository's <b>storage id</b> as {@code
 * repoId}. {@link CiEventTriggerService} calls {@link #invalidate} with that id when such an event
 * <b>arrives</b> — on the listener's thread, before the event is queued — so the evaluation of the
 * push event itself, and of every event queued behind it on the single worker, reads the new head.
 * The tag events move no branch; they invalidate anyway because the cost is one re-read and the
 * rule "any ref move of X forgets X" has no exceptions to remember.
 *
 * <p>A payload that will not parse, or that names no {@code repoId}, forgets <b>everything</b>
 * ({@link #invalidateAll}) rather than nothing: a push this cache cannot attribute is still a push,
 * and the cost of being wrong in the generous direction is one fan-out.
 *
 * <p>{@link #ttl} is the backstop for the one thing the arrival rule cannot see: an announcement
 * that never reached this process (lost on qits-githost's side, or a gap the catch-up sweep cannot
 * fill). An entry older than that is read through again whatever happened on the bus, so a lost
 * announcement costs at most one TTL of a stale head rather than a stale head forever. Zero turns
 * the cache off entirely — every call reads through, exactly the pre-qits-168 behaviour.
 *
 * <p><b>The window it does open, stated rather than hidden.</b> Between qits-githost moving a ref
 * and its announcement reaching this process, an event evaluated in that gap reads the cached,
 * previous head where it used to read the new one. That gap is the bus's own latency, and an event
 * that depends on the push has to arrive after the push's announcement to be causally after it
 * anyway; the push event's own evaluation, which is what a {@code event: SCMPublishCommit} trigger
 * builds, is never affected because it invalidates before it is queued.
 *
 * <h2>The key, and the race guard</h2>
 *
 * <p>The key is {@code (repoId, branch, scope)}: {@link CiRepoRef#repoId()} because that is what
 * the push announces and so what invalidation can name — never the name pair, which an id-addressed
 * push does not carry. {@code branch} because the port takes one (the engine only ever passes
 * {@code main}), and {@link CiTriggerScope} because the two scopes are different files.
 *
 * <p><b>A candidate whose {@code repoId} is null or blank is never cached</b>, and that is
 * deliberate rather than a fallback key: no announcement could ever invalidate an entry keyed on
 * anything else, so caching it would make the TTL its only expiry, which is precisely the stale
 * head this class exists not to serve. In practice none exists — {@code ListedAndKnownCiRepos} keys
 * its candidates by {@code repoId} in a {@code TreeMap}, which refuses a null — so this is a guard
 * rather than a path.
 *
 * <p><b>A read that was in flight when an invalidation happened must not be served afterwards.</b>
 * The worker can be listing X while the listener thread forgets X for a push that landed a moment
 * after the listing resolved its head; if that listing were stored, the push's own evaluation would
 * hit it and read the pre-push head. So each repository has a <b>generation</b>, bumped by {@link
 * #invalidate}, and a global <b>epoch</b>, bumped by {@link #invalidateAll}. A read samples both
 * before it asks the host and its entry carries them; an entry whose stamps are not the current
 * ones is never served, whichever order the store and the invalidation happened to land in. That is
 * why the check is on the read side and not only before the store: a "check, then put" can always
 * lose the race to an invalidation landing between the two, and a stamp compared at read time
 * cannot.
 *
 * <p><b>Only a {@link EventTriggerLookup.Status#FOUND} is cached.</b> An {@code UNREACHABLE} says
 * nothing about the repository — the host was down, the repository is gone, it has no {@code main}
 * — and remembering it would turn a git-host blip into a repository that answers nothing for a
 * whole TTL. Such a candidate is asked again on the next event, exactly as before.
 *
 * <p><b>Not routed through here: {@code CiRunnerHealth}'s head read.</b> The health probe exists to
 * ask the git host whether it answers; a cached answer would make it a probe of this map.
 *
 * <p>Memory is bounded by what the engine asks for — one entry per candidate per scope — and a
 * store sweeps expired entries, so a repository that stopped being a candidate does not keep its
 * listing past one TTL.
 */
@ApplicationScoped
public class CiTriggerListingCache {

  private static final Logger LOG = Logger.getLogger(CiTriggerListingCache.class);

  /**
   * The events qits-githost announces a ref move with — the whole of what invalidates an entry.
   * Strings, because {@code ci} names no other context's types; {@code
   * bus/ScmRefMoveContractTest} resolves them against the real records in the module that has the
   * jar, so a rename upstream is a red suite rather than a cache that silently stops forgetting.
   */
  public static final Set<String> REF_MOVE_EVENTS =
      Set.of("SCMPublishCommit", "SCMDeleteBranch", "SCMPublishTag", "SCMDeleteTag");

  /** The payload field every one of {@link #REF_MOVE_EVENTS} carries the storage id in. */
  public static final String REPO_ID_FIELD = "repoId";

  @Inject CiConfigSource configSource;

  /**
   * How long a listing is served without asking the host again, when no announcement said it moved.
   * The backstop for a lost announcement, not the expiry anyone relies on — see the class javadoc.
   * Zero (or negative) disables the cache.
   */
  @ConfigProperty(name = "qits.ci.trigger-listing-cache.ttl")
  Duration configuredTtl;

  /** The config's value, and whatever a test armed after that. */
  private volatile Duration ttl;

  /**
   * The monotonic clock entries are aged by. {@link System#nanoTime} in production; a test swaps it
   * through {@link #clock} so expiry is a statement rather than a sleep.
   */
  private volatile LongSupplier nanoClock = System::nanoTime;

  private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

  /** Per-repository invalidation counter. Grows by one key per repository ever invalidated. */
  private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();

  /** Bumped by {@link #invalidateAll}: one counter instead of bumping every repository's. */
  private final AtomicLong epoch = new AtomicLong();

  /** For CDI. */
  CiTriggerListingCache() {}

  /** A hand-wired instance for a plain unit test: no container, a chosen TTL and clock. */
  CiTriggerListingCache(CiConfigSource configSource, Duration ttl, LongSupplier nanoClock) {
    this.configSource = configSource;
    this.ttl = ttl;
    this.nanoClock = nanoClock;
  }

  @PostConstruct
  void readTtl() {
    ttl = configuredTtl;
  }

  /**
   * Sets the TTL for one test, dropping everything cached. A method rather than a field write for
   * the reason {@code CiEventTriggerService#platformPipelines} gives: a test holds a client proxy.
   */
  void ttl(Duration ttl) {
    this.ttl = ttl;
    invalidateAll();
  }

  /** Swaps the clock for one test. */
  void clock(LongSupplier nanoClock) {
    this.nanoClock = nanoClock;
  }

  /**
   * {@link CiConfigSource#readEventTriggers(CiRepoRef, String, CiTriggerScope)}, answered from
   * memory when this repository's ref has not moved since it was last read and the entry is younger
   * than the TTL.
   */
  public EventTriggerLookup readEventTriggers(
      CiRepoRef repo, String branch, CiTriggerScope scope) {
    Duration live = ttl;
    String repoId = repo.repoId();
    if (live == null || live.isZero() || live.isNegative() || repoId == null || repoId.isBlank()) {
      return configSource.readEventTriggers(repo, branch, scope);
    }
    Key key = new Key(repoId, branch, scope);
    long now = nanoClock.getAsLong();
    long ttlNanos = live.toNanos();
    Entry cached = entries.get(key);
    if (cached != null) {
      if (current(cached, repoId) && now - cached.readAtNanos() < ttlNanos) {
        return cached.lookup();
      }
      entries.remove(key, cached);
    }
    // Stamped BEFORE the read: an invalidation landing while the host is being asked makes the
    // answer stale, and the stamp is what says so — see the class javadoc.
    long generation = generation(repoId);
    long epochAtRead = epoch.get();
    EventTriggerLookup lookup = configSource.readEventTriggers(repo, branch, scope);
    if (lookup.status() == EventTriggerLookup.Status.FOUND) {
      Entry fresh = new Entry(lookup, now, generation, epochAtRead);
      if (current(fresh, repoId)) {
        entries.put(key, fresh);
        evictExpired(now, ttlNanos);
      }
    }
    return lookup;
  }

  /**
   * Forgets every listing of one repository — any branch, any scope — because its refs moved. Safe
   * against a read in flight: that read's answer carries the old generation and is never served.
   */
  public void invalidate(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      invalidateAll();
      return;
    }
    generations.computeIfAbsent(repoId, id -> new AtomicLong()).incrementAndGet();
    entries.keySet().removeIf(key -> key.repoId().equals(repoId));
    LOG.debugf("Trigger listings of %s forgotten: its refs moved", repoId);
  }

  /** Forgets everything — the answer to a ref move this cache cannot attribute to a repository. */
  public void invalidateAll() {
    epoch.incrementAndGet();
    entries.clear();
    LOG.debug("All trigger listings forgotten");
  }

  /** How many listings are held — for a test's assertion, not for a decision. */
  int size() {
    return entries.size();
  }

  private long generation(String repoId) {
    AtomicLong counter = generations.get(repoId);
    return counter == null ? 0L : counter.get();
  }

  private boolean current(Entry entry, String repoId) {
    return entry.generation() == generation(repoId) && entry.epoch() == epoch.get();
  }

  private void evictExpired(long now, long ttlNanos) {
    entries.values().removeIf(entry -> now - entry.readAtNanos() >= ttlNanos);
  }

  private record Key(String repoId, String branch, CiTriggerScope scope) {}

  /**
   * One cached listing, aged from when its read STARTED — so an entry never outlives the TTL
   * measured from the moment the host was asked — and stamped with the generation and epoch that
   * were current then.
   */
  private record Entry(EventTriggerLookup lookup, long readAtNanos, long generation, long epoch) {}
}
