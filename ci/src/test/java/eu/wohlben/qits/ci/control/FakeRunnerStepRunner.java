package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The runner step seam in the {@code ci} module's own application, which ships no implementation —
 * {@link FakeCiStepRunner}'s twin for a run a runner reserved. Scripted like that one: it performs
 * no step, answers every step green, and records what it was asked, so a test can say which seam a
 * run's steps, its close and its cancellation reached.
 *
 * <p>Typed to {@link CiRunnerStepRunner} and itself, exactly as the service's real one is, so it
 * never competes with {@link FakeCiStepRunner} for an injection point of {@link CiStepRunner}.
 */
@ApplicationScoped
@Typed({FakeRunnerStepRunner.class, CiRunnerStepRunner.class})
public class FakeRunnerStepRunner implements CiRunnerStepRunner {

  private final List<StepSpec> executed = Collections.synchronizedList(new ArrayList<>());
  private final List<String> cancelled = Collections.synchronizedList(new ArrayList<>());
  private final List<String> closed = Collections.synchronizedList(new ArrayList<>());
  private final Set<String> held = ConcurrentHashMap.newKeySet();
  private volatile Consumer<StepSpec> during;
  private volatile Function<StepSpec, StepResult> answer;

  public List<StepSpec> executed() {
    return List.copyOf(executed);
  }

  public List<String> cancelled() {
    return List.copyOf(cancelled);
  }

  public List<String> closed() {
    return List.copyOf(closed);
  }

  /** What the runner socket does at a Take: the run is this process's from now until it closes. */
  public void hold(String runId) {
    held.add(runId);
  }

  /** Runs on the driver's thread inside every step, before it answers. */
  public void during(Consumer<StepSpec> action) {
    this.during = action;
  }

  /**
   * What every step answers from now on, instead of green — how a test stages a runner that cannot
   * start a container, or a health check that goes red. Null is green again.
   */
  public void answer(Function<StepSpec, StepResult> answer) {
    this.answer = answer;
  }

  public void reset() {
    executed.clear();
    cancelled.clear();
    closed.clear();
    held.clear();
    during = null;
    answer = null;
  }

  @Override
  public DaemonPin pinDaemon() {
    return new DaemonPin("runner-test", "http://daemon.invalid/runner-test");
  }

  @Override
  public StepResult run(StepSpec spec, StepListener listener) {
    executed.add(spec);
    listener.onStarted();
    Consumer<StepSpec> action = during;
    if (action != null) {
      action.accept(spec);
    }
    listener.onFinished();
    Function<StepSpec, StepResult> scripted = answer;
    return scripted != null
        ? scripted.apply(spec)
        : new StepResult(0, false, StepOutcome.OK, "ran on the runner\n");
  }

  @Override
  public void cancel(String runId) {
    cancelled.add(runId);
  }

  @Override
  public boolean owns(String runId) {
    return held.contains(runId);
  }

  @Override
  public void runClosed(String runId) {
    held.remove(runId);
    closed.add(runId);
  }
}
