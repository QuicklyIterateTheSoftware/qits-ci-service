package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CiRunnerPresence} for the {@code ci} module's suite, where no runner socket exists: a runner
 * is connected exactly when a test says so. A {@code @Mock}, so it outranks {@link NoRunnerPresence}
 * — whose answer, nobody connected, is what every test that says nothing still gets.
 */
@Mock
@ApplicationScoped
public class FakeRunnerPresence implements CiRunnerPresence {

  private final Set<UUID> connected = ConcurrentHashMap.newKeySet();

  public void connect(UUID runnerId) {
    connected.add(runnerId);
  }

  public void reset() {
    connected.clear();
  }

  @Override
  public boolean connected(UUID runnerId) {
    return connected.contains(runnerId);
  }
}
