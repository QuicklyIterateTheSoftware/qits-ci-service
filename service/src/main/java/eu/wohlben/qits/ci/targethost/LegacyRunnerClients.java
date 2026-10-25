package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.idp.IdpCommissioner;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The CI runner's retirement one-shot (epic qits-1481, qits-1544): gives back every qits-idp client
 * and token of the retired kinds, {@code ci-runner} (a registered runner's own client) and {@code
 * ci-runner-registration} (its one-use registration token), and nothing else. Without its client
 * the runner stops dialling and a registration token can install nothing any more.
 *
 * <p><b>It only gives back credentials; it decommissions no runner and removes nothing on one</b>
 * (qits-1592). The fixed runner, qits-ci-runner-daemon 2026.1025.102402 or newer, reads its revoked
 * credential ({@code invalid_client}) as "stop": it exits and stays down under {@code restart=no},
 * and removes none of its containers or volumes. A runner older than that read the same revocation
 * as "deleted" and purged everything it held — which is what happened on 2026-10-24, when the
 * workspace cutover deleted the old runner's client — so this one-shot is safe only against the
 * fixed one. Nor does anything here speak to a runner: there is no runner socket any more, so no
 * {@code Retire} and no {@code RUNNER_DELETED} close can be sent from this service at all.
 *
 * <p><b>Idempotent, and at every boot</b>, on a thread of its own so a slow qits-idp never holds the
 * startup. It deletes only what qits-idp lists for this service under those two kinds, so a second
 * run finds nothing. A listing that could not be read answers empty ({@link IdpCommissioner#live},
 * {@link IdpCommissioner#liveTokens}), and a delete that failed is logged by the commissioner:
 * either way the next boot asks again. It never fails a startup. The kinds themselves go from
 * qits-idp in a later release, once this has run live — qits-idp refuses an unknown kind, so
 * removing them first would make these deletes fail.
 */
@ApplicationScoped
public class LegacyRunnerClients {

  private static final Logger LOG = Logger.getLogger(LegacyRunnerClients.class);

  /** The retired CI runner's own client. */
  static final String RUNNER_KIND = "ci-runner";

  /** Its one-use registration token. */
  static final String REGISTRATION_KIND = "ci-runner-registration";

  static final Set<String> RETIRED_KINDS = Set.of(RUNNER_KIND, REGISTRATION_KIND);

  @Inject IdpCommissioner idp;

  void onStart(@Observes StartupEvent start) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Thread pass = new Thread(this::giveBack, "ci-legacy-runner-clients");
    pass.setDaemon(true);
    pass.start();
  }

  /** One pass; answers how many credentials it gave back. Never throws. */
  int giveBack() {
    int given = 0;
    try {
      if (!idp.enabled()) {
        return 0;
      }
      Optional<List<IdpCommissioner.LiveClient>> clients = idp.live();
      for (IdpCommissioner.LiveClient client : clients.orElse(List.of())) {
        if (!RETIRED_KINDS.contains(client.contextKind()) || client.clientId() == null) {
          continue;
        }
        LOG.infof(
            "Giving back retired CI runner client %s (%s, context %s)",
            client.clientId(), client.contextKind(), client.contextId());
        idp.decommission(client.clientId());
        given++;
      }
      Optional<List<IdpCommissioner.LiveToken>> tokens = idp.liveTokens();
      for (IdpCommissioner.LiveToken token : tokens.orElse(List.of())) {
        if (!RETIRED_KINDS.contains(token.contextKind()) || token.tokenId() == null) {
          continue;
        }
        LOG.infof(
            "Deleting retired CI runner token %s (%s, context %s)",
            token.tokenId(), token.contextKind(), token.contextId());
        if (idp.deleteToken(token.tokenId())) {
          given++;
        }
      }
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not give back the retired CI runner credentials; the next boot will: %s",
          e.toString());
    }
    return given;
  }
}
