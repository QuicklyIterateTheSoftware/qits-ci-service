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
 * One release report a step submitted about itself — test results, coverage, or any later kind —
 * keyed by {@code (runId, stepIndex, kind)} (epic qits-754, Design §4).
 *
 * <p><b>Kind-agnostic by construction.</b> {@link #payload} and {@link #highlights} are JSON this
 * service stores and serves back verbatim and never reads a field of: what a kind means lives in the
 * CLI that submits it and the UI component that draws it, so a new kind is no change here.
 *
 * <p>{@code @Uncaused} by decision, for {@link CiStep}'s reason: the run this report belongs to
 * carries the cause one join away, and the row is written by a step's own upload, a request whose
 * caller is a run credential rather than an event — a stamp here would be the run's cause under a
 * second name.
 */
@Entity
@Table(name = "ci_report")
@Uncaused
public class CiReport extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "run_id", nullable = false)
  public String runId;

  @Column(name = "step_index", nullable = false)
  public int stepIndex;

  /** The kind's wire name, {@code [a-z][a-z0-9-]{0,63}}: {@code test-results}, {@code coverage}. */
  @Column(nullable = false, length = 64)
  public String kind;

  /** The kind's payload schema version, bumped by the kind only on an incompatible change. */
  @Column(name = "kind_version", nullable = false)
  public int kindVersion;

  /** The kind's JSON document, opaque. {@code text} rather than {@code @Lob}: see {@link CiStep#output}. */
  @Column(nullable = false, columnDefinition = "text")
  public String payload;

  /** A JSON array of at most ten highlights, opaque here. */
  @Column(nullable = false, columnDefinition = "text")
  public String highlights;

  /** The run the submitter compared against, or null when it had no baseline. */
  @Column(name = "baseline_run_id")
  public String baselineRunId;

  /** That baseline run's released version, or null when it had no baseline. */
  @Column(name = "baseline_version")
  public String baselineVersion;

  /** {@link #payload}'s UTF-8 length. */
  @Column(name = "payload_bytes", nullable = false)
  public int payloadBytes;

  @Column(name = "submitted_at", nullable = false)
  public Instant submittedAt;
}
