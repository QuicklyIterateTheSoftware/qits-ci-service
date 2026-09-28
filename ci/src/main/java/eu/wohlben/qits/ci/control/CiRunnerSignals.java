package eu.wohlben.qits.ci.control;

import java.time.Instant;
import java.util.UUID;

/**
 * What a connected runner is told when its standing changes here — taken out of service, put back,
 * or granted a slot for its own health check. {@link CiRunnerPresence}'s kind of seam, pointed the
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
}
