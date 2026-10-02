package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiReleaseAnnouncement;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.persistence.CiReleaseAnnouncementRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import eu.wohlben.qits.ci.control.CiArtifactPresence.Probe;
import eu.wohlben.qits.ci.control.CiArtifactPresence.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The join that decides whether a green release pipeline may announce a {@code SoftwareRelease}:
 * <b>both</b> a green release-pipeline run for a {@code (repository, version)} and an {@code
 * SCMRelease} for that same pair, in either order, or no announcement at all.
 *
 * <h2>Why the second fact exists</h2>
 *
 * <p>A green release-recipe run used to announce one {@code SoftwareRelease} per {@code artifacts:}
 * entry off <em>any</em> trigger. A bootstrap replay pushes a release tag without releasing anything
 * — the tag is a durable fact being restored, not novelty being announced — so every rebootstrap
 * impersonated a release: the train woke, every consumer ran a bump, and each bump ended in a release
 * call against a qits-workspaces the boot had not deployed yet. bootstrap-replay-plan.md's WP2.
 *
 * <p>The vocabulary already draws the line. A <b>restore</b> re-establishes SCM state, so it produces
 * only {@code SCMPublishTag}. A <b>release</b> additionally announces novelty, and {@code SCMRelease}
 * is that announcement — <b>only qits-workspaces publishes it</b>. So a real release produces both
 * events and a replay produces one, and this class is that difference made into a rule.
 *
 * <h2>The key, and where each side derives it</h2>
 *
 * <p>{@code (repository, version)}, one key with two producers:
 *
 * <ul>
 *   <li><b>The run.</b> {@code repository} is {@code ci_run.repo_id}, the repository whose pipeline
 *       published. {@code version} is read out of the payload of the event that triggered the run —
 *       {@code version} on an {@code SCMRelease}, {@code tagName} on an {@code SCMPublishTag}, which
 *       IS the version string, since a release stamp is the name of the tag the release push
 *       created. {@code CiRunService.releaseVersionOf} is the one place that choice is made.
 *   <li><b>The release.</b> {@code repository} and {@code version} off the {@code SCMRelease}
 *       payload, with {@code repositoryName} recorded beside the id as a second spelling — see
 *       {@link CiScmRelease}.
 * </ul>
 *
 * <h2>Arrival order, and why neither half may be in memory</h2>
 *
 * <p>On a real release the two race, and all four orders have to work:
 *
 * <ul>
 *   <li>an {@code SCMRelease}-triggered run goes green — both facts are one row, and it announces at
 *       green exactly as it always did. That path takes no lookup at all: the event that caused the
 *       run IS the release announcement, so the join is closed by construction. It is also what keeps
 *       the manual trigger door working, since a hand-supplied {@code SCMRelease} rides no bus and
 *       leaves no fact row;
 *   <li>a tag-triggered run goes green with the release already recorded — announced at green;
 *   <li>a tag-triggered run goes green first — the announcement is <b>owed</b>, and the {@code
 *       SCMRelease} makes it when it arrives;
 *   <li>a tag-triggered run goes green and no {@code SCMRelease} ever comes — nothing is announced,
 *       ever. <b>No timeout and no fallback</b>: a replay has no novelty to announce, and a deadline
 *       that eventually announced anyway would be the defect with a delay in front of it.
 * </ul>
 *
 * <p>Both halves are therefore rows rather than state: {@link CiReleaseAnnouncement} for what a run
 * owes and {@link CiScmRelease} for what was really released. A restart between the two costs
 * nothing, which is this platform's standing rule that announcements must not be lost.
 *
 * <h2>At-least-once, deliberately</h2>
 *
 * <p>An announcement is <b>published first and marked after</b>, inside one transaction that holds
 * the owed rows locked. So two drivers of the same key cannot both announce (the loser re-reads and
 * finds nothing owed), and a crash between the publish and the commit leaves the row owed — the boot
 * sweep announces it again. Losing an announcement is the failure this class exists to prevent;
 * making one twice is a nuisance the other way round, and that is the trade taken.
 *
 * <h2>The priority rides through here, and this class is the only reason it can</h2>
 *
 * <p>A release request carries a priority — declared on its participating branches in qits-projects,
 * folded there into one effective value — and {@code SCMRelease} carries it down. qits-ci
 * <b>transcribes</b> it onto {@code SoftwareRelease} and acts on it nowhere: the run queue is FIFO
 * and stays FIFO, {@code ci_run} does not have the column, and no comparison anywhere in this service
 * reads the value. Queue ordering is the next feature; this is the inert data it will read.
 *
 * <p>It lands on {@link CiScmRelease} rather than on the owed row, and it is <b>resolved at announce
 * time</b> rather than carried in — which falls out of the arrival orders above. An owed row exists
 * from the moment a run goes green, possibly long before any release does and in a process that no
 * longer runs by the time one arrives; the fact row is the only half of the join that knows what the
 * release said. So the tag-first order reads the priority off a row written after the obligation was,
 * and the release-first order reads it off one written before — same lookup, same answer. A drive
 * with no fact row behind it (a run whose own trigger was the release, which is the manual door's
 * shape) resolves null, and null reaches the wire as an absent key.
 *
 * <h2>{@code publish: if-changed}: the one entry this class checks before announcing</h2>
 *
 * <p>An {@code if-changed} row (a declared maven or npm entry, or a contract package the composer
 * expanded) was uploaded by the release step only if its content changed, and nothing tells qits-ci
 * which way that went but the store. So it is announced only once qits-artifacts confirms the
 * artifact exists at the release version, asked through {@link CiArtifactPresence}. Present:
 * announced as any other row. Absent (404): followed by a second question, {@link
 * CiArtifactPresence#newest} — a newest version {@code v} makes the row {@code UNCHANGED} with
 * {@code unchanged_since = v} and skip reason {@code UNCHANGED}, logged at INFO and not announced,
 * and a 404 there is {@code ABSENT}, settled without an announcement and logged at INFO.
 * Inconclusive (a 5xx, a timeout, an unreachable store) to either question: asked again, up to
 * {@link #PRESENCE_ATTEMPTS} times in the same drive with a short backoff, and if it never becomes
 * conclusive, settled {@code UNVERIFIED} without an announcement and logged at ERROR. Either way
 * the row gets {@code announced_at} plus a {@code skip_reason}, so no later drive checks it again
 * and none announces it later. A gap in an artifact's versions therefore always means "unchanged",
 * and qits-maintenance is offered no bump for it.
 *
 * <p>History: {@code announce: if-published} (qits-561) was the first checked entry, and qits-648
 * deleted the key in favour of this one. A row owed before the deletion still carries {@code
 * announce = 'if-published'} (V29) and is decided exactly as an {@code if-changed} row
 * ({@link #isIfChanged}), so it keeps its store check.
 *
 * <p><b>Dropping is deliberate, and it is safe because it self-heals — do not "fix" it into an
 * announcement.</b> qits-maintenance's daily scan ({@code LatestResolver} via {@code
 * ScanService.recordLatest}) reads maven-metadata.xml and the npm packument itself and moves {@code
 * mt_latest} to the version the store really holds, so a missed announcement delays a consumer's
 * bump by at most a day, and the scan never offers a version that does not exist. Announcing on an
 * inconclusive answer would be the opposite failure: a {@code SoftwareRelease} for a version that
 * may not exist, which every consumer's bump then tries to resolve. The question is put
 * <b>outside</b> the locking transaction (see {@link #presenceVerdicts}), because holding row locks
 * across HTTP retries is how a slow store becomes a stuck join.
 *
 * <h2>The decision record (qits-640, V30)</h2>
 *
 * <p>Every settled row also says <b>what was decided</b> about its artifact at the release version,
 * in {@code decision}: {@code PUBLISHED} for a row that was announced — believed for an {@code
 * always} row, confirmed by the store for an {@code if-changed} one — and {@code UNCHANGED}, {@code
 * ABSENT} or {@code UNVERIFIED} beside the matching {@code skip_reason} for one that was not. {@code
 * SoftwareRelease} goes out for exactly the rows that are {@code PUBLISHED}. {@link
 * #releasedArtifacts} is the read, for {@code GET
 * /ci/api/repositories/{repoId}/releases/{version}/artifacts}.
 *
 * <h2>What this class is NOT, and the deploy that looks like it is</h2>
 *
 * <p><b>Nothing here can announce before the run that published.</b> An owed row is written by a
 * green run and by nothing else, and a run owes rows only for what its own trigger file's {@code
 * artifacts:} declared — {@code CiRunService.announceRelease} returns on a null declaration before
 * this class is reached at all. So a release request's QA pipeline, which declares nothing, cannot
 * satisfy a join for a version it never built, however green and however recent it is; and an {@code
 * SCMRelease} arriving at tag time announces exactly the rows that already exist, which for a repo
 * whose release pipeline is still QUEUED is none. {@code ReleaseJoinTest} pins both directions.
 *
 * <p>That is worth stating because the symptom points here and the cause is elsewhere. A deployment
 * of {@code qits/<app>:<version>} appearing minutes after the tag — failing {@code IMAGE_MISSING}
 * because the release pipeline has not pushed yet — is qits-deployments' <b>manual door</b> ({@code
 * POST /platform-deployments/api/events/software-released}), knocked by an operator or a script
 * before the image run finished. It is distinguishable in the deployment REQUEST row: the bus door
 * records the {@code packageName} the release announced, the manual door records none. And it is not
 * free — the manual door writes a request row, {@code ReleaseTips} takes the newest request row as
 * its floor, and the genuine {@code SoftwareRelease} arriving later is then refused as "not the
 * newest release of this application any more". So the early knock does not merely fail; it can cost
 * the deployment that would have worked.
 */
@ApplicationScoped
public class ReleaseJoin {

  private static final Logger LOG = Logger.getLogger(ReleaseJoin.class);

  /**
   * The event that means a release really happened, by the name it rides the bus under.
   *
   * <p><b>A string, like {@code CiRunService.TAG_EVENT_NAME}</b>, and for the same two reasons: this
   * module has no compile-time knowledge of another context, and the vocabulary jar that owns the
   * record ({@code qits-workspaces-events}) is not on this repository's classpath in either module —
   * so unlike the tag event there is no contract test to resolve it against. A rename in
   * qits-workspaces would therefore stop the join closing rather than fail a build here, which is
   * why it is spelled once, here, and read from this constant everywhere.
   */
  public static final String RELEASE_EVENT_NAME = "SCMRelease";

  /**
   * What {@code ci_scm_release.priority} can hold; a longer value is recorded as none.
   *
   * <p>Generous several times over for the vocabulary that feeds it ({@code LOWEST} … {@code
   * BLOCKING}), which is the point: the bound is a column's, not a validation of another context's
   * words.
   */
  static final int MAX_PRIORITY_LENGTH = 32;

  /**
   * How many times one drive asks qits-artifacts about an {@code if-changed} entry before it gives
   * up on a conclusive answer. Small on purpose: a drive runs on a run's driver thread or on the bus
   * listener's, and a store that stays unreachable for longer is answered by the daily scan anyway
   * (see the class javadoc).
   */
  static final int PRESENCE_ATTEMPTS = 3;

  /** The wait before the second attempt; it doubles before the third. */
  private static final Duration DEFAULT_PRESENCE_BACKOFF = Duration.ofSeconds(1);

  // Written and read through methods: a field read on an injected CDI client proxy sees the proxy's.
  private volatile Duration presenceBackoff = DEFAULT_PRESENCE_BACKOFF;

  /** Asked before an {@code if-changed} row is announced; see the class javadoc. */
  @Inject CiArtifactPresence artifactPresence;

  @Inject CiReleaseAnnouncementRepository announcements;
  @Inject CiScmReleaseRepository releases;

  /** The published-artifact port (see {@link ReleaseAnnouncer}); zero implementations is fine. */
  @Inject Instance<ReleaseAnnouncer> releaseAnnouncers;

  /**
   * One green release pipeline's whole claim: the run, the key it published under, and the artifacts
   * its trigger file declared.
   *
   * <p>{@code triggerEventName} is here rather than a boolean, because deciding what counts as a
   * release is this class's job and not its caller's.
   *
   * <p>{@code repoName} is the run's own public name, null on a run whose push was id-addressed. It
   * is the <b>preferred</b> half of the join key: after the identity cutover a run's {@code repoId}
   * is an opaque storage UUID while {@code SCMRelease} speaks the platform's public name, so a join
   * that only compared ids would silently never close. The id arm stays as the fallback, and it is
   * what a pre-cutover platform — where the two agree — closes on. It is <b>also</b> carried onto the
   * owed row, for the reason {@code projectId} is: the two together are the address a deploy consumer
   * reads the released repository's spec at.
   *
   * <p>{@code projectId} is carried for the announcement and for nothing else: it is no part of the
   * join key and no part of any lookup here. It travels because the announcement it ends up in may
   * be made long after this call — by a later {@code SCMRelease}, or by a boot sweep in another
   * process — and neither of those can read the run row back. Null on an id-addressed run.
   */
  public record Published(
      String runId,
      String repoId,
      String repoName,
      String projectId,
      String version,
      String triggerEventName,
      String triggerEventId,
      Instant finishedAt,
      List<CiArtifact> artifacts) {}

  /**
   * A green release pipeline finished: record what it owes, and announce it at once when the release
   * fact is already in.
   *
   * <p>Called on the run worker, after the terminal row is committed. The owed rows are written
   * whichever way the gate goes — they are the durable half of the join, not a consolation prize —
   * and a run that is held says so once, at INFO, naming the key it is waiting on.
   */
  public void onGreenReleaseRun(Published run) {
    QuarkusTransaction.requiringNew().run(() -> owe(run));
    if (releasedAlready(run)) {
      announceOwed(run.repoId(), run.repoName(), run.version());
      return;
    }
    LOG.infof(
        "Run %s published %d artifact(s) of %s %s, but no %s for it has arrived — holding the"
            + " announcement (a bootstrap replay never announces)",
        run.runId(),
        run.artifacts().size(),
        run.repoId(),
        run.version(),
        RELEASE_EVENT_NAME);
  }

  /**
   * Whether the release half of the join is in for this run.
   *
   * <p>The run's own trigger settles it without a read when that trigger IS the release — see the
   * class javadoc on why that path must stay lookup-free.
   */
  private boolean releasedAlready(Published run) {
    if (RELEASE_EVENT_NAME.equals(run.triggerEventName())) {
      return true;
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> releases.released(run.repoId(), run.repoName(), run.version()));
  }

  /**
   * Writes one owed row per declared artifact, skipping what this run already owes or has already
   * announced.
   *
   * <p>The pre-check keeps a re-entry — a restarted event run, a boot sweep racing a live drive —
   * from turning an expected outcome into a caught constraint violation in the log. The unique
   * constraint underneath is still the guarantee; this is only what stops it being reached.
   */
  private void owe(Published run) {
    Set<String> already = new HashSet<>();
    for (CiReleaseAnnouncement existing : announcements.listForRun(run.runId())) {
      already.add(artifactKey(existing.packageType, existing.packageName));
    }
    Instant now = Instant.now();
    List<CiArtifact> artifacts = run.artifacts();
    for (int index = 0; index < artifacts.size(); index++) {
      CiArtifact artifact = artifacts.get(index);
      String type = artifact.type().declared();
      if (!already.add(artifactKey(type, artifact.name()))) {
        continue;
      }
      CiReleaseAnnouncement owed = new CiReleaseAnnouncement();
      owed.id = UUID.randomUUID().toString();
      owed.runId = run.runId();
      owed.repoId = run.repoId();
      owed.projectId = run.projectId();
      owed.repoName = run.repoName();
      owed.version = run.version();
      owed.packageType = type;
      owed.packageName = artifact.name();
      owed.artifactIndex = index;
      owed.finishedAt = run.finishedAt();
      owed.triggerEventId = run.triggerEventId();
      owed.publish = artifact.publishIfChanged() ? artifact.publish().declared() : null;
      owed.section = artifact.section().declared();
      owed.createdAt = now;
      announcements.persist(owed);
    }
  }

  private static String artifactKey(String packageType, String packageName) {
    return packageType + " " + packageName;
  }

  /**
   * An {@code SCMRelease} arrived: record the fact, then make whatever announcements it unblocks.
   *
   * <p>Called from the bus listener while the claiming transaction stands, and it writes in a
   * transaction of its <b>own</b> — {@code CiRunService.acceptPostReceive}'s arrangement, and for the
   * same reason it has one: the claim lives on the eventstream datasource and this row on ci's, and
   * one JTA transaction does not take both. So the two do not commit together, and a claim that
   * rolled back after this returned leaves a recorded release the funnel offers again — which is
   * harmless, because the read below makes a second recording a no-op and an already-made
   * announcement is not made twice. The other direction, a lost fact behind a committed claim, cannot
   * happen: this row is written first.
   *
   * <p>Nothing here is wrapped in {@code DbRetry}, the same stance {@code acceptPostReceive} takes:
   * a retry that outlived the claim's own connection would leave a committed row behind an event the
   * funnel then re-offers.
   *
   * <p><b>Both spellings of the repository drive the announcement</b>, because an owed row is keyed
   * by the run's repository id while the event carries an id and, optionally, a registered name. The
   * lookup matches either; so must the drive.
   *
   * @param repoId the repository that released, by the id the event carries
   * @param repoName the same repository by its registered name, or null when the event carried none
   * @param version the release stamp — also the name of the tag the release push created
   * @param eventId the announcing event, kept so a row says which release made the claim
   * @param occurredAt when the release happened
   * @param priority what the release said its priority was, or null when it said nothing — see
   *     {@link #MAX_PRIORITY_LENGTH} for the one rule applied to it and {@link ReleaseAnnouncer} for
   *     why there is no second one
   */
  public void onScmRelease(
      String repoId,
      String repoName,
      String version,
      String eventId,
      Instant occurredAt,
      String priority) {
    QuarkusTransaction.requiringNew()
        .run(() -> recordRelease(repoId, repoName, version, eventId, occurredAt, priority));
    announceOwed(repoId, repoName, version);
    if (repoName != null && !repoName.isBlank() && !repoName.equals(repoId)) {
      announceOwed(repoName, repoId, version);
    }
  }

  /** The fact row, written once. The read is the guard; the unique constraint is the guarantee. */
  private void recordRelease(
      String repoId,
      String repoName,
      String version,
      String eventId,
      Instant occurredAt,
      String priority) {
    if (releases.findRelease(repoId, version).isPresent()) {
      return;
    }
    CiScmRelease release = new CiScmRelease();
    release.id = UUID.randomUUID().toString();
    release.repoId = repoId;
    release.repoName = repoName;
    release.version = version;
    release.eventId = eventId;
    release.occurredAt = occurredAt;
    release.seenAt = Instant.now();
    release.priority = priorityToRecord(repoId, version, priority);
    releases.persist(release);
  }

  /**
   * The release's stated priority as this row may hold it: the value verbatim, or none.
   *
   * <p><b>Nothing here judges the value</b>, which is the whole of qits-ci's relationship with it.
   * There is no enum to parse it into and no list of accepted words: qits-projects owns the
   * vocabulary, it will grow there, and a value this service had not heard of must ride through
   * untouched rather than cost a release its announcement.
   *
   * <p>The one rule is the column's own width, and it is {@code ci_run.release_request_id}'s rule
   * verbatim — <b>recorded as none, with a WARN, rather than truncated or thrown</b>. The release
   * fact is the point of this row; a payload that cannot name a priority within {@link
   * #MAX_PRIORITY_LENGTH} characters is not naming one this platform issued, and a truncated value
   * would be a word nobody wrote travelling on as if somebody had.
   */
  private static String priorityToRecord(String repoId, String version, String priority) {
    if (priority == null || priority.isBlank()) {
      return null;
    }
    if (priority.length() > MAX_PRIORITY_LENGTH) {
      LOG.warnf(
          "%s of %s %s names a priority of %d characters — too long to record, the release keeps"
              + " none",
          RELEASE_EVENT_NAME, repoId, version, priority.length());
      return null;
    }
    return priority;
  }

  /**
   * Announces everything one {@code (repository, version)} owes, in one transaction that holds those
   * rows locked — see {@link CiReleaseAnnouncementRepository#lockOwed} for what the lock is for and
   * the class javadoc for why the publish comes before the mark.
   *
   * <p>A failure of one announcer costs that announcement and not its siblings, the fan-out rule the
   * {@link ReleaseAnnouncer} port states: N declarations are N calls.
   *
   * <p>The one spelling the caller has, for the boot sweep — which reads its keys back out of the
   * owed rows and therefore knows the repository by the run's id alone.
   */
  private void announceOwed(String repoId, String version) {
    announceOwed(repoId, null, version);
  }

  /**
   * The same, with the second spelling of the repository stated.
   *
   * <p>{@code repoId} is the spelling the <b>owed rows</b> are keyed by and is what selects them;
   * {@code repoName} is the other one the caller happens to know, and it is used for one thing only —
   * finding the release fact this announcement's priority is read off, with the matcher {@code
   * released(…)} closes the join with. The two arguments are the run's pair in the green-run
   * direction and the event's pair in the arriving-release direction, and either way the lookup has
   * to be able to reach the row the gate already accepted.
   *
   * <p><b>The priority is resolved HERE, inside this transaction, and not carried in.</b> An owed row
   * is written when a run goes green, which may be long before the release exists and in another
   * process entirely — so the value cannot be on it, and reading it at the moment of announcing is
   * what makes a late escalation reach the wire. It is looked up once per drive rather than once per
   * row: N artifacts of one run are one question about one release, the shape {@link #sweepOwed}
   * already takes.
   */
  private void announceOwed(String repoId, String repoName, String version) {
    Map<String, Probe> newest = new HashMap<>();
    Map<String, Probe> verdicts = presenceVerdicts(repoId, version, newest);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              List<CiReleaseAnnouncement> owed = announcements.lockOwed(repoId, version);
              if (owed.isEmpty()) {
                return;
              }
              // Null when no release fact stands behind this drive — a run whose own trigger WAS the
              // release (the manual door leaves no row), or a release that stated no priority. Both
              // reach the wire as an absent key, which is the honest spelling of "none was stated".
              String priority = releases.priorityOf(repoId, repoName, version).orElse(null);
              Instant now = Instant.now();
              for (CiReleaseAnnouncement row : owed) {
                if (isIfChanged(row)) {
                  // A row owed after presenceVerdicts read the table (a second run racing this
                  // drive) has no verdict yet and is asked here. That is the rare path, and the only
                  // HTTP this transaction can ever make.
                  Probe probe =
                      verdicts.computeIfAbsent(
                          presenceKey(row),
                          key -> presenceOf(row.packageType, row.packageName, version));
                  if (probe.verdict() == Verdict.ABSENT) {
                    Probe since =
                        newest.computeIfAbsent(
                            presenceKey(row), key -> newestOf(row.packageType, row.packageName));
                    if (since.verdict() == Verdict.PRESENT) {
                      unchanged(row, since, now);
                    } else {
                      skip(row, since, now);
                    }
                    continue;
                  }
                  if (probe.verdict() != Verdict.PRESENT) {
                    skip(row, probe, now);
                    continue;
                  }
                }
                // Believed for an always-row, confirmed above for an if-changed one: either way
                // this row is announced, and that is what PUBLISHED records.
                row.decision = CiReleaseAnnouncement.DECISION_PUBLISHED;
                for (ReleaseAnnouncer announcer : releaseAnnouncers) {
                  try {
                    announcer.onArtifactPublished(
                        row.runId,
                        row.repoId,
                        row.projectId,
                        row.repoName,
                        row.version,
                        row.packageType,
                        row.packageName,
                        row.finishedAt,
                        row.triggerEventId,
                        priority,
                        row.section);
                  } catch (RuntimeException e) {
                    LOG.warnf(
                        e, "Announcing artifact %s of run %s failed", row.packageName, row.runId);
                  }
                }
                row.announcedAt = now;
              }
            });
  }

  /**
   * The presence answer for every distinct {@code if-changed} entry {@code (repository, version)}
   * owes right now, asked <b>before</b> the locking transaction opens — an unlocked read of the owed
   * rows, then {@link #presenceOf} per distinct artifact, and for one found ABSENT, {@link #newestOf}
   * into {@code newest}. Empty, and no HTTP at all, when nothing owed declares it, which is every
   * row of a repository that does not.
   */
  private Map<String, Probe> presenceVerdicts(
      String repoId, String version, Map<String, Probe> newest) {
    List<CiReleaseAnnouncement> owed =
        QuarkusTransaction.requiringNew().call(() -> announcements.listOwed(repoId, version));
    Map<String, Probe> verdicts = new HashMap<>();
    for (CiReleaseAnnouncement row : owed) {
      if (isIfChanged(row)) {
        Probe probe =
            verdicts.computeIfAbsent(
                presenceKey(row), key -> presenceOf(row.packageType, row.packageName, version));
        if (probe.verdict() == Verdict.ABSENT) {
          newest.computeIfAbsent(
              presenceKey(row), key -> newestOf(row.packageType, row.packageName));
        }
      }
    }
    return verdicts;
  }

  /**
   * Asks qits-artifacts whether one artifact exists at {@code version}: a conclusive answer is
   * returned at once, an inconclusive one is asked again, up to {@link #PRESENCE_ATTEMPTS} times
   * with a doubling backoff. What comes back after the last attempt is the last answer.
   */
  private Probe presenceOf(String packageType, String packageName, String version) {
    CiArtifact.Type type = CiArtifact.Type.of(packageType);
    return asked(
        packageType + " " + packageName + " at " + version,
        () -> artifactPresence.probe(type, packageName, version));
  }

  /** {@link #presenceOf}'s second question, the newest version, with the same retries. */
  private Probe newestOf(String packageType, String packageName) {
    CiArtifact.Type type = CiArtifact.Type.of(packageType);
    return asked(
        "the newest " + packageType + " " + packageName,
        () -> artifactPresence.newest(type, packageName));
  }

  /** One question put to the store up to {@link #PRESENCE_ATTEMPTS} times; see {@link #presenceOf}. */
  private Probe asked(String what, Supplier<Probe> question) {
    Duration backoff = presenceBackoff;
    Probe probe = Probe.inconclusive("not asked");
    for (int attempt = 1; attempt <= PRESENCE_ATTEMPTS; attempt++) {
      try {
        probe = question.get();
      } catch (RuntimeException e) {
        // The port's contract is to answer, not to throw; an implementation that throws has said
        // nothing, which is exactly INCONCLUSIVE.
        probe = Probe.inconclusive("the presence check threw: " + e);
      }
      if (probe.verdict() != Verdict.INCONCLUSIVE) {
        return probe;
      }
      LOG.debugf(
          "Presence of %s inconclusive on attempt %d of %d: %s",
          what, attempt, PRESENCE_ATTEMPTS, probe.detail());
      if (attempt < PRESENCE_ATTEMPTS) {
        try {
          Thread.sleep(backoff.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return probe;
        }
        backoff = backoff.multipliedBy(2);
      }
    }
    return probe;
  }

  /**
   * Settles an {@code if-changed} row WITHOUT announcing it, marked like an announced row so no
   * later drive of the join checks it again or announces it.
   *
   * <p>This drop is safe and must stay a drop: qits-maintenance's daily scan ({@code LatestResolver}
   * via {@code ScanService.recordLatest}) reads maven-metadata.xml / the npm packument and moves
   * {@code mt_latest} to the newest version that really exists, so a missed announcement delays a
   * bump by at most a day and never offers a version that does not exist. Announcing an
   * inconclusive one instead would offer exactly that.
   */
  private static void skip(CiReleaseAnnouncement row, Probe probe, Instant now) {
    if (probe.verdict() == Verdict.ABSENT) {
      LOG.infof(
          "Not announcing %s %s at version %s (run %s, %s): it declares %s and"
              + " qits-artifacts does not hold that version (%s)",
          row.packageType,
          row.packageName,
          row.version,
          row.runId,
          row.repoId,
          policyOf(row),
          probe.detail());
      row.skipReason = CiReleaseAnnouncement.SKIPPED_ABSENT;
      row.decision = CiReleaseAnnouncement.DECISION_ABSENT;
    } else {
      LOG.errorf(
          "Not announcing %s %s at version %s (run %s, %s): it declares %s and"
              + " qits-artifacts could not be asked conclusively in %d attempts (last: %s)."
              + " Dropped rather than announced unverified; qits-maintenance's daily scan picks the"
              + " version up if it exists",
          row.packageType,
          row.packageName,
          row.version,
          row.runId,
          row.repoId,
          policyOf(row),
          PRESENCE_ATTEMPTS,
          probe.detail());
      row.skipReason = CiReleaseAnnouncement.SKIPPED_UNVERIFIED;
      row.decision = CiReleaseAnnouncement.DECISION_UNVERIFIED;
    }
    row.announcedAt = now;
  }

  /**
   * Settles an {@code if-changed} row the release step did not upload because its content equals
   * the newest published version's: recorded, logged at INFO, and not announced — there is no new
   * version for any consumer to take.
   */
  private static void unchanged(CiReleaseAnnouncement row, Probe newest, Instant now) {
    LOG.infof(
        "Not announcing %s %s at version %s (run %s, %s): it declares %s and is unchanged since %s"
            + " — not announcing",
        row.packageType,
        row.packageName,
        row.version,
        row.runId,
        row.repoId,
        policyOf(row),
        newest.version());
    row.skipReason = CiReleaseAnnouncement.SKIPPED_UNCHANGED;
    row.decision = CiReleaseAnnouncement.DECISION_UNCHANGED;
    row.unchangedSince = newest.version();
    row.announcedAt = now;
  }

  /** The policy a probed row declares, as a file spells it, for a log line. */
  private static String policyOf(CiReleaseAnnouncement row) {
    return CiArtifact.PUBLISH_KEY + ": " + CiArtifact.Publish.IF_CHANGED.declared();
  }

  /**
   * Whether the row is decided by the store: a {@code publish: if-changed} one, or one owed before
   * qits-648 under the deleted {@code announce: if-published} (V29), which folds in here rather than
   * keeping a path of its own — see the class javadoc.
   */
  private static boolean isIfChanged(CiReleaseAnnouncement row) {
    return CiArtifact.Publish.IF_CHANGED.declared().equals(row.publish)
        || CiReleaseAnnouncement.LEGACY_IF_PUBLISHED.equals(row.announce);
  }

  private static String presenceKey(CiReleaseAnnouncement row) {
    return artifactKey(row.packageType, row.packageName);
  }

  /**
   * One artifact of one release, as the decision record states it — the body element of {@code GET
   * /ci/api/repositories/{repoId}/releases/{version}/artifacts}. Every string is the wire spelling.
   *
   * @param type the declared type, {@code maven}, {@code npm}, {@code docker}, {@code daemon} or
   *     {@code docs}
   * @param name the declared coordinate
   * @param publish {@code always} or {@code if-changed}
   * @param decision {@code pending} while owed, then {@code published}, {@code unchanged}, {@code
   *     absent} or {@code unverified}
   * @param unchangedSince the newest stored version when {@code unchanged}, else null
   * @param runId the run that owed the row
   * @param decidedAt when the row was settled, null while {@code pending}
   */
  public record ReleasedArtifact(
      String type,
      String name,
      String publish,
      String decision,
      String unchangedSince,
      String runId,
      Instant decidedAt) {}

  /** The wire word for a row still owed. */
  static final String PENDING = "pending";

  /**
   * What qits-ci recorded about each artifact of one release: the rows every green release run of
   * {@code (repo, version)} owed, <b>newest run first, one per (type, name)</b> — a re-run of the
   * release pipeline owes its rows again, and the newest run's word is the current one. {@code repo}
   * is the public name or the storage id. Empty when no green release run owed anything for that
   * version, which is an answer and not an error.
   */
  public List<ReleasedArtifact> releasedArtifacts(String repo, String version) {
    List<CiReleaseAnnouncement> rows =
        QuarkusTransaction.requiringNew().call(() -> announcements.listForRelease(repo, version));
    Set<String> seen = new HashSet<>();
    List<ReleasedArtifact> released = new ArrayList<>();
    for (CiReleaseAnnouncement row : rows) {
      if (!seen.add(artifactKey(row.packageType, row.packageName))) {
        continue;
      }
      released.add(
          new ReleasedArtifact(
              row.packageType,
              row.packageName,
              row.publish == null ? CiArtifact.Publish.ALWAYS.declared() : row.publish,
              decisionOf(row),
              row.unchangedSince,
              row.runId,
              row.announcedAt));
    }
    return List.copyOf(released);
  }

  /**
   * The wire word for a row's decision. A row settled <b>before V30</b> has {@code announced_at} and
   * no {@code decision}; its {@code skip_reason} then says it all — none was announced, so {@code
   * published}, and {@code ABSENT}/{@code UNVERIFIED} name themselves.
   */
  static String decisionOf(CiReleaseAnnouncement row) {
    if (row.announcedAt == null) {
      return PENDING;
    }
    String decision = row.decision;
    if (decision == null) {
      decision =
          row.skipReason == null ? CiReleaseAnnouncement.DECISION_PUBLISHED : row.skipReason;
    }
    return decision.toLowerCase(Locale.ROOT);
  }

  /** Test seam: the backoff between presence attempts. A method, because this bean is proxied. */
  void presenceBackoff(Duration backoff) {
    presenceBackoff = backoff == null ? DEFAULT_PRESENCE_BACKOFF : backoff;
  }

  /**
   * The crash window's recovery: every owed announcement whose release fact is already recorded is
   * made again at boot.
   *
   * <p>It exists for the gap between publishing an announcement and committing the mark — the price
   * of publishing first — and for the older gap between recording a release and driving what it
   * unblocks. Both are milliseconds wide and neither may cost an announcement.
   *
   * <p><b>It runs on its own thread, and that is a measured rule rather than tidiness.</b> A publish
   * is a bounded HTTP call, and a startup observer that blocks on the network is what killed a
   * qits-ci deployment once already: the container healthcheck's budget expires before the socket
   * ever binds, and cd kills a process that was only being polite to a service that was down. See
   * {@code DaemonReleaseListener.reconcileFromLog}.
   *
   * <p>No {@code @Priority}: this observer neither hands work to the run worker nor touches a
   * container, so it is ordered against neither of the boot pair.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Thread sweeper = new Thread(this::sweepOwed, "ci-release-join-sweep");
    sweeper.setDaemon(true);
    sweeper.start();
  }

  /**
   * The sweep itself — package-private because {@link #onStart} skips test mode, so this is what a
   * suite drives to make a claim about a restart.
   *
   * <p>One lookup per distinct key rather than per row: N artifacts of one run are one question about
   * one release. A key with no release recorded is left exactly as it is, which is the replay case
   * and is not a failure.
   */
  void sweepOwed() {
    List<Object[]> keys;
    try {
      keys = QuarkusTransaction.requiringNew().call(announcements::distinctOwedKeys);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the owed release announcements at startup");
      return;
    }
    int announced = 0;
    for (Object[] key : keys) {
      String repoId = (String) key[0];
      String version = (String) key[1];
      try {
        if (QuarkusTransaction.requiringNew().call(() -> releases.released(repoId, version))) {
          announceOwed(repoId, version);
          announced++;
        }
      } catch (RuntimeException e) {
        // One key's failure must not cost the others theirs — the same containment the announcement
        // fan-out has.
        LOG.warnf(e, "Could not announce what %s %s owes", repoId, version);
      }
    }
    if (!keys.isEmpty()) {
      LOG.infof(
          "Release join swept at startup: %d of %d owed release(s) had an %s and were announced",
          announced, keys.size(), RELEASE_EVENT_NAME);
    }
  }
}
