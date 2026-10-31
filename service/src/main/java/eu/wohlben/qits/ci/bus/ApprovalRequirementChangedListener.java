package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.targethost.CiSlotHolds;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Ends a CI slot held for a release that now waits on a person (epic qits-732): consumes
 * qits-projects' {@code ApprovalRequirementChanged} and, when a requirement on the held fold is
 * {@code OPEN}, releases the hold of that {@code (repoId, releaseRequestId)} — {@code
 * approval-pending}. An approval takes longer than any hold's window, and the slot is somebody
 * else's build meanwhile. A requirement on another fold (a re-fold's) ends nothing.
 *
 * <p>{@link #selects} narrows on {@code state == OPEN}, for {@link
 * ReleaseRequestStateChangedListener}'s reasons, and the failure policy is that class's too: the work
 * is idempotent and never throws, a payload that will not bind is a WARN and settled, and nothing is
 * replayed from the epoch.
 */
@ApplicationScoped
public class ApprovalRequirementChangedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ApprovalRequirementChangedListener.class);

  /** The storage key of this consumption — new, and never any other listener's. */
  static final String CONSUMER_ID = "ci-slot-hold-approval";

  /** The one signature, spelled off the transcribed record. */
  static final String EVENT_NAME = ApprovalRequirementChanged.class.getSimpleName();

  @Inject CiSlotHolds holds;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(EVENT_NAME);
  }

  @Override
  public boolean selects(EventFrame frame) {
    return EVENT_NAME.equals(frame.name())
        && CiSlotHolds.APPROVAL_OPEN.equals(
            ReleaseRequestStateChangedListener.field(frame.payload(), "state"));
  }

  @Override
  public void onFrame(EventFrame frame) {
    ApprovalRequirementChanged changed;
    try {
      changed = CanonicalJson.payloadTo(frame.payload(), ApprovalRequirementChanged.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried a payload that will not bind, so no slot hold is ended by it: %s",
          EVENT_NAME, frame.id(), unreadable.toString());
      return;
    }
    holds.onApprovalRequirementChanged(
        changed.repoId(), changed.releaseRequestId(), changed.mergedSha(), changed.state());
  }
}
