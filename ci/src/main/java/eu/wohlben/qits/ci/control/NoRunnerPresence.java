package eu.wohlben.qits.ci.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * {@link CiRunnerPresence} with no socket behind it: nobody is connected. {@code @DefaultBean}, so
 * the runner socket's own implementation replaces it by merely existing — {@code KnownCiRepos}'
 * arrangement — and a suite that wants a connected runner replaces it with a {@code @Mock}.
 */
@ApplicationScoped
@DefaultBean
public class NoRunnerPresence implements CiRunnerPresence {

  @Override
  public boolean connected(UUID runnerId) {
    return false;
  }
}
