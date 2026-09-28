package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Hands one runner lifecycle announcement to every {@link RunnerAnnouncer}, and makes sure none of
 * them can cost the caller anything by throwing.
 *
 * <p>The loop {@link CiRunService}'s three announce methods each spell out for {@link RunAnnouncer},
 * written once here because it has two callers in two modules — {@link CiRunners} for the row, and
 * the runner socket's registry in {@code service/} for the connection — and both need exactly the
 * same guarantee: the create, the registration or the {@code Hello} stands whatever an announcement
 * does. A failure is a WARN naming what was being announced, and nothing else.
 */
@ApplicationScoped
public class RunnerAnnouncements {

  private static final Logger LOG = Logger.getLogger(RunnerAnnouncements.class);

  /** The runner event port; zero implementations is fine. */
  @Inject Instance<RunnerAnnouncer> announcers;

  /** Call {@code announcement} on each announcer; {@code what} names it in a failure's log line. */
  public void announce(String what, Consumer<RunnerAnnouncer> announcement) {
    for (RunnerAnnouncer announcer : announcers) {
      try {
        announcement.accept(announcer);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing %s failed", what);
      }
    }
  }
}
