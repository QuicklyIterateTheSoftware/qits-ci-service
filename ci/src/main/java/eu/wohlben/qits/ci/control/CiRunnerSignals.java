package eu.wohlben.qits.ci.control;

import java.time.Instant;
import java.util.UUID;

/**
 * What a connected runner is told when its standing changes here — taken out of service, put back,
 * granted a slot for its own health check, or deleted. {@link CiRunnerPresence}'s kind of seam, pointed the
 * other way: {@code ci/} decides, the runner socket's registry in {@code service/} is what speaks,
 * because the socket is a web stack's and this module has none.
 *
 * <p><b>Every method is a hint and none may fail its caller.</b> The decisions are rows —
 * {@code ci_runner.quarantined_at}, a queued health check — and a runner that is not connected, or
 * whose frame did not leave, learns the same thing from its next {@code Hello}, which reads those
 * rows again. So an implementation sends best-effort, bounded, and swallows what it cannot send;
 * {@link NoRunnerSignals}, the answer with no socket behind it, sends nothing at all.
 */
public interface CiRunnerSignals {

  /** The runner was quarantined: tell it why and since when, and re-{@code Ack} its slots (0). */
  void quarantined(UUID runnerId, String reason, Instant since);

  /**
   * The quarantine was lifted — {@code by} is {@code admin} or {@code healthcheck} — and its slots
   * are re-{@code Ack}ed as its row grants them.
   */
  void reinstated(UUID runnerId, String by);

  /**
   * What the runner may hold moved without its standing changing — an operator changed its slots, a
   * health check was queued for it, or one it held settled — so its {@code Ack} is re-sent with the
   * slots it has now, and a {@code Backlog} after it so a runner with room asks for work at once.
   */
  void slotsChanged(UUID runnerId);

  /**
   * The runner's row was deleted. Every connection it still holds is told so ({@code Retire} of kind
   * {@code DELETED}, on which the runner removes its own container and state) and then closed.
   * Called after the delete committed and <b>before</b> the runner's credentials are revoked at
   * qits-idp — the frame goes over a socket that is already open, so it does not need them, but a
   * runner that is told first has not yet begun failing to mint. Like every signal a hint: a runner
   * that is not connected finds out at its next dial, which the socket refuses {@code
   * RUNNER_DELETED}.
   */
  void deleted(UUID runnerId);

  /**
   * Ask the connected runner for its NODE health report now (qits-896): a {@code healthCheck} frame,
   * whose answer — or the lack of one — lands on {@code ci_runner.node_health}. Sent beside a
   * pseudo-build the schedule queued, so the report an operator reads next to that check's verdict is
   * as fresh as it is. A diagnosis and never a decision: nothing the report says moves the runner's
   * standing. A runner that is not connected, or already has a request pending, is asked nothing
   * more.
   */
  void nodeHealthCheck(UUID runnerId);
}
