package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * qits-projects' announcement that an approval requirement on a release request's fold changed
 * state, transcribed here as the record {@link ApprovalRequirementChangedListener} binds its payload
 * to (epic qits-732).
 *
 * <p>A local copy of {@code
 * components/qits-projects/qits-projects-service/service/src/main/java/eu/wohlben/qits/projects/bus/ApprovalRequirementChanged.java},
 * component for component, for {@link ReleaseRequestStateChanged}'s reasons — no vocabulary jar, a
 * contract test ({@code ApprovalRequirementChangedContractTest}) keeping the list honest, and unknown
 * keys ignored so a component added over there costs nothing here. {@code changedAt} is the event's
 * {@code occurredAt}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalRequirementChanged(
    UUID eventId,
    String releaseRequestId,
    String releaseRequestQualifiedId,
    String projectId,
    String repoId,
    String repoName,
    String mergedSha,
    String requirementId,
    String reason,
    List<String> admits,
    boolean excludesAuthors,
    List<String> authors,
    String state,
    Instant changedAt)
    implements QitsEvent {

  public ApprovalRequirementChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    admits = admits == null ? List.of() : List.copyOf(admits);
    authors = authors == null ? List.of() : List.copyOf(authors);
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
