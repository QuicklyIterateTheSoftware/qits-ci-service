package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.runnerhost.RunnerStepRunner;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The step-runner seam for the service suite — a <b>scripted-event</b> fake, the same shape the ci
 * module's copy has. Duplicated on purpose: the two modules do not share a test classpath.
 *
 * <p>It used to be a deliberately <em>honest</em> fake that performed the real step semantics as
 * host processes — clone the repo at the pushed sha, then {@code bash -c <script>}. That died with
 * the approach it modelled. Running a repository's script as a host process is precisely the thing
 * qits-ci does not do, and a fixture that kept doing it would have kept the retired approach alive
 * in the test sources after it was deleted from the main ones — the residue the eradication decision
 * exists to forbid. **No fake in this repository executes a step.**
 *
 * <p>What a test scripts here is therefore only what the seam promises: some chunks, then a result.
 *
 * <p><b>It does feed the live relay, and that is not it performing a step.</b> The relay is the
 * transport's own bookkeeping — which step a run is on, when the host handed it over, what has come
 * back so far — and this class stands in for the transport, so a suite whose fake left it empty
 * could not see {@code GET /ci/api/runs/&#123;runId&#125;}'s {@code live} object at all and every
 * assertion about it would have to be made against a hand-wired relay instead of against the read
 * surface. What is still scripted rather than performed is everything a step does; the four calls
 * below are the ones {@code RunnerStepRunner} makes around a step, in its order.
 *
 * <p><b>It is the runner seam, and it scripts only the {@link SuiteRunner}'s runs.</b> Since qits-506
 * every run is a runner's and {@link CiRunnerStepRunner} is the only seam {@code CiRunService} asks,
 * so this {@code @Mock} alternative stands in for it across the test application — but a run a real
 * runner holds over the real socket ({@code CiRunnerSocketTest}, {@code RunnerStepRunnerTest}) is
 * handed straight to the real {@link RunnerStepRunner}, untouched. Which is which is decided by
 * {@link #hold}: the suite's runner holds its runs here from its reservation until they close, the
 * way the real registry holds a socket runner's.
 */
@Mock
@ApplicationScoped
@Typed({FakeCiStepRunner.class, CiRunnerStepRunner.class})
public class FakeCiStepRunner implements CiRunnerStepRunner {

  /** What a scripted step emits before it answers. */
  public record Script(List<String> chunks, StepResult result) {

    public static Script of(StepResult result, String... chunks) {
      return new Script(List.of(chunks), result);
    }
  }

  // Accessed via the getters — a direct field read through the CDI client proxy would see the
  // proxy's own (empty) field, not the contextual instance's.
  private final List<StepSpec> executed = java.util.Collections.synchronizedList(new ArrayList<>());
  private final Map<Integer, Script> scripted = new HashMap<>();
  private final Map<Integer, Consumer<StepSpec>> during = new HashMap<>();
  private final List<String> cancelled = java.util.Collections.synchronizedList(new ArrayList<>());

  // Written on the worker thread and read on the request thread — the same crossing the real
  // runner's in-flight map makes, and the reason this one is concurrent.
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /** The live surface, fed here exactly where the real runner feeds it — see the class javadoc. */
  @Inject CiStepRelay relay;

  /** The real seam, for every run the suite's runner does not hold. */
  @Inject RunnerStepRunner real;

  /** The suite's runner's runs, from reservation to close — see {@link #hold}. */
  private final Set<String> held = ConcurrentHashMap.newKeySet();

  /** What {@link SuiteRunner} does at its reservation: this run is scripted here until it closes. */
  public void hold(String runId) {
    held.add(runId);
  }

  private boolean scripted(String runId) {
    return held.contains(runId);
  }

  public List<StepSpec> executed() {
    return executed;
  }

  public List<String> cancelled() {
    return cancelled;
  }

  public void script(int stepIndex, StepResult result, String... chunks) {
    scripted.put(stepIndex, Script.of(result, chunks));
  }

  /**
   * Run something on the run worker <b>while</b> this step is executing — the same hook the ci
   * module's copy carries, and it is here for the same reason: it is how a test stages the states
   * that only exist while a run is in flight without a sleep and without a race.
   *
   * <p>At this level that is what makes {@code RUNNING} and {@code QUEUED} observable over HTTP. The
   * worker is single-threaded, so a run parked inside its first step really does hold the next one
   * in the queue, and the read surface really is being asked about that instant.
   */
  public void during(int stepIndex, Consumer<StepSpec> action) {
    during.put(stepIndex, action);
  }

  public void reset() {
    executed.clear();
    scripted.clear();
    during.clear();
    cancelled.clear();
    inFlight.clear();
    held.clear();
  }

  /**
   * The pin is asked before the run's first step and carries no run id, so it cannot tell whose run
   * it is — and it needs no telling: the real pin is a constant off the classpath, so every run in
   * this suite pins exactly what a deployed one would.
   */
  @Override
  public DaemonPin pinDaemon() {
    return real.pinDaemon();
  }

  @Override
  public StepResult run(StepSpec spec, StepListener listener) {
    if (!scripted(spec.runId())) {
      return real.run(spec, listener);
    }
    inFlight.add(spec.runId());
    relay.begin(spec.runId(), spec.stepIndex());
    try {
      return runStep(spec, listener);
    } finally {
      inFlight.remove(spec.runId());
    }
  }

  private StepResult runStep(StepSpec spec, StepListener listener) {
    executed.add(spec);
    Script script = scripted.getOrDefault(spec.stepIndex(), green(spec.stepIndex()));
    listener.onStarted();
    relay.started(spec.runId(), Instant.now());
    // The hook runs AFTER the stamp, deliberately: what it stages is the middle of a step, and a
    // step the host has not handed over yet is the setup window rather than the state under test.
    Consumer<StepSpec> midStep = during.get(spec.stepIndex());
    if (midStep != null) {
      midStep.accept(spec);
    }
    for (String chunk : script.chunks()) {
      relay.append(spec.runId(), chunk);
      listener.onChunk(chunk);
    }
    listener.onFinished();
    return script.result();
  }

  @Override
  public void cancel(String runId) {
    if (!scripted(runId)) {
      real.cancel(runId);
      return;
    }
    cancelled.add(runId);
  }

  /** Held by the suite's runner, or by a real runner through the real seam. */
  @Override
  public boolean owns(String runId) {
    return scripted(runId) || inFlight.contains(runId) || real.owns(runId);
  }

  @Override
  public void runClosed(String runId) {
    if (!held.remove(runId)) {
      real.runClosed(runId);
      return;
    }
    // The relay is the one thing that IS held between steps, and dropping it is what makes `live`
    // null on a finished run — the real runner's own last act.
    relay.drop(runId);
  }

  private static Script green(int stepIndex) {
    String text = "step " + stepIndex + " ran";
    return Script.of(new StepResult(0, false, StepOutcome.OK, text), text);
  }
}
