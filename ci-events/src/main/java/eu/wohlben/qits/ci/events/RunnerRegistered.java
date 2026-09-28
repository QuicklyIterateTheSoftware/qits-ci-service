package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A runner registered: the register door accepted its registration token, qits-idp commissioned its
 * own client, and the row now carries that client — so from here on the runner can dial the socket.
 *
 * <p>{@code clientId} is the commissioned client's id, the {@code sub} every token the runner mints
 * will carry. It is an identifier and not a credential: the secret is answered to the runner once and
 * is on no event, no row and no log line.
 *
 * <p><b>The capabilities are flattened to {@code docker}, {@code arch} and {@code os}</b>, rather
 * than carried as the object the runner sent. No event on this platform carries free-form JSON — the
 * structured ones carry typed lists of records — and what a runner registers with is its own word,
 * up to 16 KiB of whatever it chose to say. The three fields are the ones the claim and an operator
 * actually read; each is nullable, and absent from the payload, when the runner did not say it (or
 * said it as something other than a boolean or a string). The labels stay on the row.
 *
 * <p><b>Published once the row committed, and never for a refused registration</b>: a token that is
 * not this runner's (403), no such runner (404), capabilities that are not an object (400) and a
 * runner that already registered (409) all leave the row as it was. {@code occurredAt} is the row's
 * {@code registeredAt}. Everything else is {@link RunnerCreated}'s, stated there.
 */
public record RunnerRegistered(
    UUID eventId,
    String runnerId,
    String runnerName,
    String clientId,
    Boolean docker,
    String arch,
    String os,
    Instant occurredAt)
    implements QitsEvent {

  public RunnerRegistered {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RunnerRegistered(
      String runnerId,
      String runnerName,
      String clientId,
      Boolean docker,
      String arch,
      String os,
      Instant occurredAt) {
    this(null, runnerId, runnerName, clientId, docker, arch, os, occurredAt);
  }
}
