package eu.wohlben.qits.ci.control;

/**
 * The step seam for a run a <b>runner</b> reserved — and since qits-506 every run is one: the
 * in-process executor that claimed runs itself (and asked qits-containers for their containers) is
 * deleted, so {@link CiRunService#stepRunnerFor} answers this seam for every run. {@link
 * CiStepRunner} stays as the contract it extends.
 *
 * <p><b>A type of its own rather than a qualifier</b>: the implementation restricts its bean types
 * to this interface (and itself), and the orchestrator reaches it through an {@code Instance} that
 * may be unsatisfied — the {@code ci} module ships no implementation; the service's runner socket
 * does, and a suite supplies a fake.
 *
 * <p>Behind it, a runner on its own host starts each step's container, asked over the runner
 * socket. The step's daemon dials this service, and the script leaves as the reply to its {@code
 * Initialized}. {@link #owns} is
 * true for as long as a driver of this process holds the run — from the {@code Take} to the {@code
 * Released} — which is longer than one step, so a cancellation between two steps is still a
 * cancellation of an owned run.
 */
public interface CiRunnerStepRunner extends CiStepRunner {}
