package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.persistence.CiReleaseAnnouncementRepository;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Keeps this service's durable rows addressing a repository by the name it answers to now: consumes
 * qits-projects' {@code RepositoryRenamed} and rewrites {@code (project_id, repo_name)} on every
 * {@code ci_run} and {@code ci_release_announcement} recorded for that repository.
 *
 * <h2>Why a repair is owed at all</h2>
 *
 * <p>Both columns are written <b>once</b>, at accept time, off whatever the triggering event
 * announced, and nothing re-derives them afterwards. The candidate catalogue is fine — it is a live
 * listing behind a five-second cache, so a renamed repository is a renamed candidate on the next
 * read — and so is every future run, which is accepted with the new name by itself. What is stale is
 * the history, and it is stale in two places with very different prices.
 *
 * <p>The cheap one is display: {@code GET /ci/api/repositories/summary} reads the last run's name, so
 * a renamed repository shows its old one until it next builds — forever, for a repository that does
 * not.
 *
 * <p><b>The expensive one is an owed announcement, and it holds a deployment.</b>
 * {@code ci_release_announcement} carries the pair because the announcement is often made later than
 * the run that owes it (V10, V11 — see that entity), so an announcement owed across a rename
 * publishes {@code SoftwareRelease} naming the repository the platform no longer knows. qits-deployments
 * reads the released repository's spec name-addressed at {@code /git/<projectId>/<repoName>} when the
 * event carries the pair, and that read 404s; the id-addressed fallback is refused by qits-githost's
 * storage-client guard for everyone but qits-projects. So the release publishes, the deploy never
 * happens, and nothing in either service names the rename as the cause. Two live renames on
 * 2026-09-07 — {@code qits-configuration-service} and {@code qits-configuration-frontend}, each
 * gaining its {@code platform} tier modifier — are what made that concrete.
 *
 * <h2>The storage id is what makes the repair possible</h2>
 *
 * <p>A rename moves no bare: the git host keys by {@code repositoryId}, which is unchanged, and so is
 * {@code ci_run.repo_id}. So the rows to fix are found by the one column the rename did not touch,
 * and the effect is an idempotent write of the event's own facts — the shape the library's javadoc
 * says needs no tip check of its own.
 *
 * <p><b>One limit is accepted rather than overlooked.</b> Rows whose {@code repo_id} IS the old name
 * — what an id-addressed push recorded on a pre-cutover platform, where the id and the name were the
 * same string — are not reached, because this matches the storage id and nothing else. Widening it to
 * "or {@code repo_id = oldName}" would rewrite rows on the strength of a string collision between one
 * repository's storage id and another's former name, which is a worse failure than a stale name: such
 * rows carry no {@code project_id} anyway, so they are already read id-addressed and are not the
 * announcement hazard above.
 *
 * <h2>Its own transaction, not the claim's</h2>
 *
 * <p>The claim lives on the eventstream datasource and these rows on ci's, and one JTA transaction
 * does not take both — measured as {@code Enlisted connection used without active transaction}.
 * {@code ScmReleaseListener}/{@code ReleaseJoin.onScmRelease} is the exact precedent and
 * {@code CiEventTriggerService}'s owed ledger is the second. So the two do not commit together, and a
 * claim that rolled back after this returned leaves the rename applied and the event offered again —
 * which is harmless, because applying it twice writes the same two values.
 *
 * <h2>Ordering, which is this handler's to answer</h2>
 *
 * <p>Catch-up delivers late and out of stream order, so two renames of ONE repository could in a
 * narrow window be applied newest-first and leave the row holding the older name. <b>Accepted, and
 * stated rather than guarded.</b> A tip check here would have to ask what the repository is called
 * now, which is a call to qits-projects on the dispatch thread for a fact the next event or the next
 * run supplies for free — every subsequent rename and every subsequent run of that repository writes
 * the current name, so the window closes by itself. Renames are rare, and two of one repository
 * inside one catch-up sweep rarer still.
 *
 * <h2>Replaying from the epoch, which this listener asks for</h2>
 *
 * <p>{@link #replayFromEpoch()} is {@code true}, and it is the case the library's javadoc reserves it
 * for: "a projection being built". A brand-new consumer initializes at the <em>head</em> of the log by
 * default, so the two renames that already happened would never be applied and the rows they made
 * stale would stay stale — which is the entire reason this listener exists. Replaying is bounded by
 * construction rather than by hope: {@link #signatures()} names one event, catch-up queries the log
 * with exactly that name filter, and the whole history of it is two frames today. It applies at
 * initialization and never again; an intentional re-repair later is
 * {@code CatchupSweeper.rebuildFromEpoch("ci-repository-rename")} and not a flag flip.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p><b>Retryable, and left to throw:</b> anything the database raises. A store that is down is a
 * condition rather than a verdict, so the claim rolls back and the event stays owed for the next
 * sweep — and an unapplied rename is exactly the stale row this listener exists to prevent.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not bind, and one whose
 * repository id, project id or new name is missing or is not a value this service would put in a URL
 * path segment ({@code CiIdentifiers}, which is where every attacker-shaped identifier is checked —
 * a payload establishes delivery, never content). None of those can succeed on a later offer, and a
 * throw would hold this consumer's watermark behind one bad event forever. {@code oldName} is read
 * for the log line and nothing else, so a missing one is not poison.
 *
 * <p>{@link #selects} is left at its default: this listener wants every event under its one
 * signature, so there is nothing to narrow and no predicate that could be undecidable. That does mean
 * a claim row per rename on the platform rather than per rename of a repository this instance has run
 * — a rounding error against a table already keyed per event, and the alternative would be a
 * database read in front of the claim.
 */
@ApplicationScoped
public class RepositoryRenamedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(RepositoryRenamedListener.class);

  /**
   * The storage key of this consumption: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} this repair is caught up by. New, and deliberately not any of the four live
   * ids nor the abandoned {@code ci-push-runs} — a listener inheriting a watermark would believe it
   * had already applied renames it has never been offered.
   */
  static final String CONSUMER_ID = "ci-repository-rename";

  /** The one signature, spelled off the transcribed record so the two cannot drift apart. */
  static final String EVENT_NAME = RepositoryRenamed.class.getSimpleName();

  @Inject CiRunRepository runs;

  @Inject CiReleaseAnnouncementRepository announcements;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(EVENT_NAME);
  }

  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  @Override
  public void onFrame(EventFrame frame) {
    RepositoryRenamed renamed;
    try {
      renamed = CanonicalJson.payloadTo(frame.payload(), RepositoryRenamed.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried a payload that will not bind, so no row is renamed: %s",
          EVENT_NAME, frame.id(), unreadable.toString());
      return;
    }

    String repositoryId;
    String projectId;
    String newName;
    try {
      repositoryId = CiIdentifiers.requireRepoId(renamed.repositoryId());
      projectId = CiIdentifiers.requireProjectId(renamed.projectId());
      newName = CiIdentifiers.requireRepoName(renamed.newName());
    } catch (RuntimeException refused) {
      // Poison: these three ARE the rewrite. A row addressed by a value this service would not put
      // in a clone URL is worse than a row addressed by a stale name.
      LOG.warnf(
          "%s %s names no repository this service could address (%s/%s -> %s), so no row is"
              + " renamed: %s",
          EVENT_NAME,
          frame.id(),
          renamed.projectId(),
          renamed.repositoryId(),
          renamed.newName(),
          refused.toString());
      return;
    }

    int[] rewritten =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    new int[] {
                      runs.renameRepository(repositoryId, projectId, newName),
                      announcements.renameRepository(repositoryId, projectId, newName)
                    });

    if (rewritten[0] > 0 || rewritten[1] > 0) {
      LOG.infof(
          "%s %s: %s is now %s/%s — %d run(s) and %d release announcement(s) re-addressed",
          EVENT_NAME,
          frame.id(),
          renamed.oldName() == null ? repositoryId : renamed.oldName(),
          projectId,
          newName,
          rewritten[0],
          rewritten[1]);
    } else {
      LOG.debugf(
          "%s %s: %s is a repository this instance has recorded nothing for",
          EVENT_NAME, frame.id(), repositoryId);
    }
  }
}
