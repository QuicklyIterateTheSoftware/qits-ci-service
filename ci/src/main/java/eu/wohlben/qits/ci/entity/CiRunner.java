package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One runner an operator declared: a machine that registers with qits-ci and pulls step work, rather
 * than a container qits-ci asks qits-containers to start. {@code V23__runners.sql} carries the
 * argument for every column; what follows is what reading a row means.
 *
 * <p><b>Its registration state is which of two pairs is set.</b> {@link #registrationTokenId} and
 * {@link #registrationTokenSubject} name the one-use token qits-idp commissioned for this runner at
 * create (or at the last rotation) — the id so it can be deleted, the subject because it is what the
 * register door compares the caller's {@code sub} to. The token's VALUE is on no column anywhere: it
 * leaves this service once, in the answer to the request that made it. {@link #clientId} and {@link
 * #registeredAt} are set once, by the register door, and a row with a client is registered.
 *
 * <p><b>{@link #slots} zero is a state</b>: a runner that may hold no work, which is how one is
 * drained without being deleted.
 *
 * <p><b>{@link #capabilities} is the runner's own word about itself</b>, stored as the JSON text it
 * registered with and read back through {@code RunnerCapabilities}. {@code jsonb} rather than {@code
 * text} because the scheduler the next epic brings will query into it; a {@code String} attribute
 * rather than a bound type because nothing here binds another party's payload.
 *
 * <p>A {@link CausedRow} like {@link CiRun}: a runner is created by a person's request, and the REST
 * filter's restored scope is standing when it is — so the stamp fills the column on its own.
 */
@Entity
@Table(name = "ci_runner")
@EntityListeners(CausationStamp.class)
public class CiRunner extends PanacheEntityBase implements CausedRow {

  @Id public UUID id;

  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  /** Unique, {@code [a-z][a-z0-9-]{0,63}} — see {@code CiRunners.NAME}. */
  @Column(nullable = false, length = 64)
  public String name;

  @Column(length = 1024)
  public String description;

  /** How many steps this runner may hold at once; zero is a drained runner. */
  @Column(nullable = false)
  public int slots;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CiRunnerPlane plane;

  /** What the runner said about itself at registration, as JSON text; null until then. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String capabilities;

  @Column(name = "registration_token_id", length = 255)
  public String registrationTokenId;

  @Column(name = "registration_token_subject", length = 255)
  public String registrationTokenSubject;

  /** The commissioned client the register door answered with, or null while unregistered. */
  @Column(name = "client_id", length = 255)
  public String clientId;

  @Column(name = "registered_at")
  public Instant registeredAt;

  /** Host-stamped each time the runner is heard from; null until it first is. */
  @Column(name = "last_seen_at")
  public Instant lastSeenAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /**
   * When this runner was taken out of service, or null while it is in it. Set and cleared together
   * with {@link #quarantineReason}; {@link #slots} is never touched by either, so a reinstated runner
   * gets back exactly what its operator configured. See {@code V24__runner_quarantine.sql}.
   */
  @Column(name = "quarantined_at")
  public Instant quarantinedAt;

  @Column(name = "quarantine_reason", columnDefinition = "text")
  public String quarantineReason;

  /** Runner-caused step failures in a row; a step that started resets it. */
  @Column(name = "infra_failures", nullable = false)
  public int infraFailures;

  /** The distinct runs that streak spans, as a JSON array of run ids; null is an empty streak. */
  @Column(name = "infra_failure_runs", columnDefinition = "text")
  public String infraFailureRuns;

  /** When this runner's newest health check settled; null until one has. */
  @Column(name = "last_healthcheck_at")
  public Instant lastHealthcheckAt;

  /** {@code PASSED} or {@code FAILED}, or null until a health check has settled. */
  @Column(name = "last_healthcheck_result", length = 16)
  public String lastHealthcheckResult;

  @Column(name = "last_healthcheck_run_id", length = 255)
  public String lastHealthcheckRunId;

  /** What the newest health check said: its outcome and the head of its output. */
  @Column(name = "last_healthcheck_detail", columnDefinition = "text")
  public String lastHealthcheckDetail;

  /** Whether the register door has answered this runner. */
  public boolean registered() {
    return clientId != null;
  }

  /** Whether this runner is out of service: it takes no work but its own health check. */
  public boolean quarantined() {
    return quarantinedAt != null;
  }
}
