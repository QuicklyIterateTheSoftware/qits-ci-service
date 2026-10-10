package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.runnerhost.CiRunnerRegistry;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The socket registry's answer to "is this runner connected", plus {@link SuiteRunner}'s own row
 * while it is enabled — the suite's runner has no socket, and a runner that is not connected
 * contributes no slot to the queue's forecast. A provider state may declare a runner connected too
 * ({@link #declareConnected}). Every other question is the registry's.
 */
@Mock
@ApplicationScoped
@Typed({CiRunnerPresence.class, SuiteRunnerPresence.class})
public class SuiteRunnerPresence implements CiRunnerPresence {

  @Inject CiRunnerRegistry registry;

  @Inject SuiteRunner suiteRunner;

  /** Runners a provider state declared connected (contracts/ProviderStates); none otherwise. */
  private final Set<UUID> declared = ConcurrentHashMap.newKeySet();

  /** Counts {@code runnerId} as connected until {@link #forget}, as if its socket were open. */
  public void declareConnected(UUID runnerId) {
    declared.add(runnerId);
  }

  public void forget(UUID runnerId) {
    declared.remove(runnerId);
  }

  @Override
  public boolean connected(UUID runnerId) {
    return runnerId != null && (runnerId.equals(suiteRunner.id()) || declared.contains(runnerId))
        || registry.connected(runnerId);
  }

  @Override
  public Versions versions(UUID runnerId) {
    return registry.versions(runnerId);
  }
}
