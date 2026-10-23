package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.eventstream.QitsEventListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * How this service learns what became of its leases as it happens (epic qits-1481): qits-system's
 * {@link TargetLeaseChanged}, handed to {@link CiLeases#observe}. The reconcile in {@link CiLeases}
 * lists the leases every {@code qits.ci.target-leases.reconcile-interval} (PT30S), so a lost event
 * only delays a run.
 *
 * <p><b>A live listener, not a durable one</b>, unlike every other consumer in this service, and on
 * purpose: what an event says, the next listing says too, so a missed one costs at most an interval
 * and needs no watermark — qits-workspaces, the first owner to lease, consumes it the same way. Only
 * leases whose owner is {@code qits-ci} and whose slot type is {@code CI} are this service's; every
 * other is another owner's and is ignored.
 */
@ApplicationScoped
public class TargetLeaseEvents implements QitsEventListener<TargetLeaseChanged> {

  private static final Logger LOG = Logger.getLogger(TargetLeaseEvents.class);

  @Inject CiLeases leases;

  @Override
  public Class<TargetLeaseChanged> eventType() {
    return TargetLeaseChanged.class;
  }

  @Override
  public void onEvent(TargetLeaseChanged event) {
    if (event == null
        || !SystemLeases.OWNER.equals(event.ownerApp())
        || !SystemLeases.SLOT_TYPE.equals(event.slotType())) {
      return;
    }
    UUID leaseId = uuid(event.leaseId());
    if (leaseId == null) {
      LOG.debugf("TargetLeaseChanged %s names no lease id; ignored", event.eventId());
      return;
    }
    try {
      leases.observe(
          new SystemLeases.LeaseView(
              leaseId,
              event.ownerRef(),
              event.state(),
              uuid(event.targetId()),
              event.exitCode(),
              event.detail(),
              null));
    } catch (RuntimeException e) {
      // The reconcile tells the run the same thing within an interval.
      LOG.warnf(e, "Could not apply lease %s's %s; the reconcile will", leaseId, event.state());
    }
  }

  static UUID uuid(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(text.trim());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
