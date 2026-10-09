package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiRunnerHealth;
import eu.wohlben.qits.ci.control.CiRunners;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunner;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.enterprise.inject.Vetoed;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * {@link CiRunnerHealth} with its pseudo-build queued by nobody: {@link #requestHealthCheck} answers
 * a run id without resolving the health-check repository, its head or its image, which the service
 * suite's catalogue and registry cannot answer (every real request is a 503 here). What the door does
 * AROUND that call — the node health frame it sends, the 202 it answers — is what a case installing
 * this is about; the pseudo-build's own rules are {@code CiRunnerHealthTest}'s, in the {@code ci}
 * module. Every other method a caller can reach goes to the real bean, so a runner's {@code Ack}
 * still carries its real slots.
 *
 * <p>{@code @Vetoed}, because the scope it inherits would otherwise make it a second bean of the type
 * it stands in for. Per test, as every {@link QuarkusMock} is.
 */
@Vetoed
public class QueuedHealthChecks extends CiRunnerHealth {

  private final CiRunnerHealth real;

  private final List<String> queued = Collections.synchronizedList(new ArrayList<>());

  private QueuedHealthChecks(CiRunnerHealth real) {
    this.real = real;
  }

  /** Installs one over the bean for the current test, and answers it. */
  public static QueuedHealthChecks install() {
    CiRunnerHealth real = ClientProxy.unwrap(Arc.container().instance(CiRunnerHealth.class).get());
    QueuedHealthChecks stand = new QueuedHealthChecks(real);
    QuarkusMock.installMockForType(stand, CiRunnerHealth.class);
    return stand;
  }

  /** The run ids this answered, in order. */
  public List<String> queued() {
    synchronized (queued) {
      return List.copyOf(queued);
    }
  }

  /** A run id for any runner that exists — 404 for one that does not, as the real door answers. */
  @Override
  public CiRun requestHealthCheck(UUID runnerId) {
    CiRunner runner = Arc.container().instance(CiRunners.class).get().get(runnerId);
    CiRun run = new CiRun();
    run.id = "queued-health-check-" + UUID.randomUUID();
    run.targetRunnerId = runner.id;
    queued.add(run.id);
    return run;
  }

  @Override
  public int effectiveSlots(UUID runnerId) {
    return real.effectiveSlots(runnerId);
  }

  @Override
  public CiRunner greenlight(UUID runnerId) {
    return real.greenlight(runnerId);
  }

  @Override
  public void onRegistered(UUID runnerId) {
    real.onRegistered(runnerId);
  }
}
