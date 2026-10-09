package eu.wohlben.qits.ci.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link CiRunnerSignals} with no socket behind it: nobody is connected, so nobody is told. {@code
 * @DefaultBean}, so the runner registry's own implementation replaces it by merely existing — {@link
 * NoRunnerPresence}'s arrangement.
 */
@ApplicationScoped
@DefaultBean
public class NoRunnerSignals implements CiRunnerSignals {

  @Override
  public void quarantined(UUID runnerId, String reason, Instant since) {}

  @Override
  public void reinstated(UUID runnerId, String by) {}

  @Override
  public void slotsChanged(UUID runnerId) {}

  @Override
  public void deleted(UUID runnerId) {}

  @Override
  public void nodeHealthCheck(UUID runnerId) {}
}
