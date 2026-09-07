package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One {@code SCMRelease} this instance has seen: the durable half of the release join that says a
 * {@code (repository, version)} really was released. Only qits-workspaces publishes that event, so a
 * row here is the novelty a bootstrap replay does not have — see {@code ReleaseJoin}.
 *
 * <p>Kept forever rather than pruned. A green release run may arrive arbitrarily later than the
 * release it belongs to, and a fact that expired would turn a slow build into a silent replay.
 *
 * <p>{@link #repoName} is the same repository under its registered name, which {@code SCMRelease}
 * carries beside the id and may leave null. The lookup matches either spelling: a run's {@code
 * repoId} is the git host's id, and on this platform the two agree — but the event's own javadoc does
 * not promise it, and a join that silently missed would be a release nobody announces.
 *
 * <p>{@code @Uncaused} by decision, the {@link CiDaemonPin} argument verbatim: {@link #eventId} IS
 * the causing event, already on the row, so a generic causation column would be that column under a
 * second name.
 */
@Entity
@Table(name = "ci_scm_release")
@Uncaused
public class CiScmRelease extends PanacheEntityBase {

  @Id public String id;

  /** The repository that released, by the id the event carries. */
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The same repository by its registered name, or null when the event carried none. */
  @Column(name = "repo_name")
  public String repoName;

  @Column(nullable = false)
  public String version;

  /** The announcing {@code SCMRelease}'s own id. */
  @Column(name = "event_id", nullable = false)
  public String eventId;

  /** When the release happened, as the event reports it. */
  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /** When this instance recorded it. */
  @Column(name = "seen_at", nullable = false)
  public Instant seenAt;

  /**
   * The priority the release itself stated, verbatim — {@code LOWEST} … {@code BLOCKING} as
   * qits-projects folds it out of a release request's participating branches.
   *
   * <p><b>A String and not an enum, because qits-ci never acts on it.</b> It is carried from {@code
   * SCMRelease} onto {@code SoftwareRelease} and read by nobody in between: no queue is ordered by
   * it, no comparison is made against it, and the run row does not have it at all. The vocabulary
   * belongs to qits-projects, so a local enum would be a second list to keep in step and a value it
   * had not heard of would turn an inert field into a refused release.
   *
   * <p>It is on <b>this</b> row rather than on the owed announcement for the same reason the join has
   * two rows at all: the announcement may be made by whoever closes the join later — a later {@code
   * SCMRelease}, or a boot sweep in another process — and this is the row that knows what the release
   * said. Nullable, no backfill: a release that named none, one published before the field existed,
   * and every historical row carry nothing, and absence reaches the wire as a missing key.
   */
  @Column(length = 32)
  public String priority;
}
