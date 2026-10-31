package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.targethost.CiSlotHolds;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Ends a CI slot held for a release whose release request will not release (epic qits-732): consumes
 * qits-projects' {@code ReleaseRequestStateChanged} and, for a state in {@link
 * CiSlotHolds#GATES_UNMET}, releases the hold of that {@code (repoId, releaseRequestId)} — {@code
 * gates-unmet:<STATE>}. A request that is going to release says nothing here: its {@code SCMRelease}
 * is what the slot waits for, and the hold's window bounds the wait either way.
 *
 * <p><b>{@link #selects} narrows on the state</b>, unlike every other listener here: the predicate is
 * pure and cheap — one read of the payload's {@code state}, no I/O — and every other state of every
 * request on the platform is traffic this consumer has no use for, so it leaves no claim row. A
 * payload whose state cannot be read is not selected; there is nothing it could end.
 *
 * <p><b>Its own work is idempotent and never throws</b>: {@link CiSlotHolds#release} deletes the row
 * in its own transaction on ci's datasource (the claim is on the eventstream one, {@code
 * RepositoryRenamedListener}'s measured reason) and ends nothing twice. A payload that will not bind
 * is poison — a WARN and settled, since the same bytes fail the same way every time. Not replayed
 * from the epoch: a hold lives for a minute, so an old state change can end nothing.
 */
@ApplicationScoped
public class ReleaseRequestStateChangedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ReleaseRequestStateChangedListener.class);

  /** The storage key of this consumption — new, and never any other listener's. */
  static final String CONSUMER_ID = "ci-slot-hold-release-request";

  /** The one signature, spelled off the transcribed record. */
  static final String EVENT_NAME = ReleaseRequestStateChanged.class.getSimpleName();

  /** No reflection: {@code readTree} binds nothing. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    // Set.of(...).contains(null) throws, and an unreadable state is simply not selected.
    String state = field(frame.payload(), "state");
    return EVENT_NAME.equals(frame.name()) && state != null && CiSlotHolds.GATES_UNMET.contains(state);
  }

  @Override
  public void onFrame(EventFrame frame) {
    ReleaseRequestStateChanged changed;
    try {
      changed = CanonicalJson.payloadTo(frame.payload(), ReleaseRequestStateChanged.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried a payload that will not bind, so no slot hold is ended by it: %s",
          EVENT_NAME, frame.id(), unreadable.toString());
      return;
    }
    holds.onReleaseRequestStateChanged(
        changed.repoId(), changed.releaseRequestId(), changed.state());
  }

  /** One top-level string of a payload, or null when it is absent or the payload unreadable. */
  static String field(String payload, String name) {
    if (payload == null) {
      return null;
    }
    try {
      JsonNode value = MAPPER.readTree(payload).path(name);
      return value.isTextual() ? value.asText() : null;
    } catch (Exception unreadable) {
      return null;
    }
  }
}
