package eu.wohlben.qits.ci.control;

/**
 * Told how many runs are {@code QUEUED} each time that number may have moved — a run accepted, a
 * run finished, a queued run settled or cancelled. The listener that matters is the runner
 * registry in {@code service/}, which pushes the number to every connected runner as a {@code
 * Backlog} frame so a runner learns of work without polling.
 *
 * <p>A seam in {@code ci/} for {@link CiRunnerPresence}'s reason: the push is a socket, and this
 * module has no web stack. {@link CiRunService} counts and calls; it never learns who listens.
 *
 * <p><b>Called on whatever thread moved the queue</b> — the trigger worker, a run worker, a request
 * thread — and after the write that moved it has committed, so the count is one a reader could see.
 * An implementation must therefore not block for long and must not throw for anything it cares
 * about: a listener that failed costs its own push, never the run's transition.
 */
public interface CiBacklogListener {

  void backlogChanged(int queued);
}
