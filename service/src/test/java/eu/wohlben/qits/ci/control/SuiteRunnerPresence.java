package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.runnerhost.CiRunnerRegistry;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * The socket registry's answer to "is this runner connected", plus {@link SuiteRunner}'s own row
 * while it is enabled — the suite's runner has no socket, and a runner that is not connected
 * contributes no slot to the queue's forecast. Every other question is the registry's.
 */
@Mock
@ApplicationScoped
@Typed(CiRunnerPresence.class)
public class SuiteRunnerPresence implements CiRunnerPresence {

  @Inject CiRunnerRegistry registry;

  @Inject SuiteRunner suiteRunner;

  @Override
  public boolean connected(UUID runnerId) {
    return runnerId != null && runnerId.equals(suiteRunner.id()) || registry.connected(runnerId);
  }

  @Override
  public Versions versions(UUID runnerId) {
    return registry.versions(runnerId);
  }
}
