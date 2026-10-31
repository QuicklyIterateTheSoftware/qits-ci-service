package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * qits-projects' announcement that a release request moved to a new state, transcribed here as the
 * record {@link ReleaseRequestStateChangedListener} binds its payload to (epic qits-732).
 *
 * <p>A local copy for {@link RepositoryRenamed}'s reason: qits-projects publishes no vocabulary jar.
 * The component list is the publisher's, in the publisher's order — {@code
 * components/qits-projects/qits-projects-service/service/src/main/java/eu/wohlben/qits/projects/bus/ReleaseRequestStateChanged.java}
 * — and {@code ReleaseRequestStateChangedContractTest} keeps it honest through the real {@code
 * CanonicalJson}. {@code changedAt} is both a component and the event's {@code occurredAt}, as over
 * there.
 *
 * <p><b>Unknown keys are ignored when binding</b>, unlike {@link RepositoryRenamed}: this payload has
 * grown before and will again, and a component added over there must not turn every arrival into a
 * payload that will not bind — which would leave a held slot to its timeout instead of its gate. The
 * contract test is what notices the drift; the listener keeps working through it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseRequestStateChanged(
    UUID eventId,
    String releaseRequestId,
    String releaseRequestQualifiedId,
    String projectId,
    String repoId,
    String repoName,
    String backingBranch,
    String state,
    String previousState,
    Instant changedAt,
    String mergedSha,
    String version,
    String detail,
    List<String> branches)
    implements QitsEvent {

  public ReleaseRequestStateChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    branches = branches == null ? null : List.copyOf(branches);
  }

  /** The publisher's convenience constructor, minting the identity — transcribed with the rest. */
  public ReleaseRequestStateChanged(
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String projectId,
      String repoId,
      String repoName,
      String backingBranch,
      String state,
      String previousState,
      Instant changedAt,
      String mergedSha,
      String version,
      String detail,
      List<String> branches) {
    this(
        null,
        releaseRequestId,
        releaseRequestQualifiedId,
        projectId,
        repoId,
        repoName,
        backingBranch,
        state,
        previousState,
        changedAt,
        mergedSha,
        version,
        detail,
        branches);
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
