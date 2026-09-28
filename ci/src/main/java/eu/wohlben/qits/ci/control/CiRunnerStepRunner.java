package eu.wohlben.qits.ci.control;

/**
 * The step seam for a run a <b>runner</b> reserved rather than a local worker claimed: same shape,
 * same contract, a different transport. {@link CiRunService#stepRunnerFor} picks it for any run
 * whose row carries a {@code runner_id}; every other run keeps the {@link CiStepRunner} it always
 * had.
 *
 * <p><b>A type of its own rather than a qualifier</b>, and that is the whole of the CDI
 * arrangement: the implementation restricts its bean types to this interface (and itself), so it
 * never competes for an injection point of {@link CiStepRunner}, and the orchestrator reaches it
 * through an {@code Instance} that may be unsatisfied — the {@code ci} module ships no
 * implementation, exactly as it ships none of {@link CiStepRunner}.
 *
 * <p>What differs behind it is only who starts the container: a runner on its own host, asked over
 * the runner socket, where the local path asks qits-containers. The step's daemon still dials this
 * service, and the script still leaves as the reply to its {@code Initialized}. {@link #owns} is
 * true for as long as a driver of this process holds the run — from the {@code Take} to the {@code
 * Released} — which is longer than one step, so a cancellation between two steps is still a
 * cancellation of an owned run.
 */
public interface CiRunnerStepRunner extends CiStepRunner {}
