package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Records what a connected runner would have been told, in order, as {@code "<signal> <runnerId>"}
 * plus what it carried — the {@code ci} module has no socket to send it on. A {@code @Mock}, so it
 * replaces {@link NoRunnerSignals}. State is read through methods, the proxy rule every fake here
 * follows.
 */
@Mock
@ApplicationScoped
public class RecordingRunnerSignals implements CiRunnerSignals {

  private final List<String> signals = Collections.synchronizedList(new ArrayList<>());

  public void reset() {
    signals.clear();
  }

  /** Every signal about one runner, in order: {@code quarantined:<reason>}, {@code reinstated:<by>}, {@code slotsChanged}. */
  public List<String> of(UUID runnerId) {
    synchronized (signals) {
      String prefix = runnerId + " ";
      return signals.stream()
          .filter(s -> s.startsWith(prefix))
          .map(s -> s.substring(prefix.length()))
          .toList();
    }
  }

  @Override
  public void quarantined(UUID runnerId, String reason, Instant since) {
    signals.add(runnerId + " quarantined:" + reason);
  }

  @Override
  public void reinstated(UUID runnerId, String by) {
    signals.add(runnerId + " reinstated:" + by);
  }

  @Override
  public void slotsChanged(UUID runnerId) {
    signals.add(runnerId + " slotsChanged");
  }
}
