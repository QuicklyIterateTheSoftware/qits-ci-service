package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One CI slot <b>held</b> after a green release-request QA run, for the release that follows it
 * (epic qits-732, {@code V42__slot_holds.sql}).
 *
 * <p>A run is one CI lease — one ci-slot container, one qits-ci-daemon. A QA run (phase 1) that
 * settles SUCCESS on a daemon that speaks capability 3, for a repository whose composition has a
 * release half, keeps its lease open instead of closing it: no {@code Close}, no lease ended, the
 * token kept. The release (phase 2, on {@code SCMRelease}) can then continue in the same container.
 * The hold ends the first time any reason to stop waiting arrives — the request's gates unmet, an
 * approval pending, the window run out, the container gone, a restart, a newer hold — and ending it
 * is the run's close, late.
 *
 * <p><b>Not a run status, and never a run.</b> qits-projects holds a release request while {@code
 * GET /ci/api/runs/active} lists a run of its {@code (repoId, commitSha)}; the QA run settles and is
 * announced exactly as before, and the hold lives here, where no run listing reads.
 *
 * <p>{@code @Uncaused} by decision: {@link #heldFromRunId} is the run the hold came out of and that
 * run carries the cause, the way {@link CiStep}'s run does.
 */
@Entity
@Table(name = "ci_slot_hold")
@Uncaused
public class CiSlotHold extends PanacheEntityBase {

  /** The held lease at qits-system. No foreign key: another service's row. */
  @Id
  @Column(name = "lease_id")
  public UUID leaseId;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The release request the slot is held for — one hold per request ({@code ci_slot_hold_rr}). */
  @Column(name = "release_request_id", nullable = false)
  public String releaseRequestId;

  /** Its readable id, {@code <repository>-rr-<n>}, or null when the QA run's event carried none. */
  @Column(name = "release_request_qualified_id")
  public String releaseRequestQualifiedId;

  /** The phase-1 run: still the lease's {@code ownerRef} at qits-system, and its token's owner. */
  @Column(name = "held_from_run_id", nullable = false)
  public String heldFromRunId;

  /** The launch the slot's daemon names in its {@code Hello}. */
  @Column(name = "daemon_id", nullable = false, length = 64)
  public String daemonId;

  @Column(name = "target_id")
  public UUID targetId;

  @Column(name = "daemon_version", length = 64)
  public String daemonVersion;

  /** The fold the QA run built — what an {@code ApprovalRequirementChanged} is matched on. */
  @Column(name = "merged_sha", nullable = false, length = 64)
  public String mergedSha;

  @Column(name = "held_at", nullable = false)
  public Instant heldAt;

  /** Past this the hold is released ({@code qits.ci.release-hold.timeout} after {@link #heldAt}). */
  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;
}
