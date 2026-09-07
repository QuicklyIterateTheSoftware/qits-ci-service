package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiRunService;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What the step running <em>right now</em> has printed, per run. One bounded buffer per in-flight
 * run, fed by the chunks arriving on that run's container's control socket, read by the run read
 * surface, and dropped when the run closes.
 *
 * <p><b>It is the only thing that exists while a step runs.</b> Step rows are written once and
 * already terminal, so between two steps a run legitimately has fewer rows than its pipeline
 * declares; this is what makes that gap legible instead of looking like a run with missing steps.
 * The persisted tail on the step row is the record — this is the live convenience, it is memory, and
 * it dies with the process. Following along is a <b>poll</b> over {@code GET /ci/api/runs/{runId}}:
 * the daemon makes live output possible, it does not oblige a push transport, and there is no SSE
 * and no WebSocket on the read side.
 *
 * <p><b>Bounded by construction.</b> A step's output is repo-controlled and unbounded — a chatty
 * build, an accidental {@code cat} of a huge file — so the budget ({@code qits.ci.output-max-chars})
 * is applied as text arrives rather than to an assembled string, and the head is what goes. Which
 * makes this buffer the runner's accumulator too: the tail the step row is written with is read back
 * out of here, so the bound is applied once, in one place, rather than twice with a chance to drift.
 *
 * <p>Chunks arrive on the socket's virtual thread and the read arrives on an HTTP worker, so every
 * buffer method is synchronized on the buffer. Nothing here blocks for longer than an append.
 */
@ApplicationScoped
public class CiStepRelay {

  /** How much of a truncated tail is text, once the marker has taken its share. */
  private static final int MARKER_LENGTH = CiRunService.TRUNCATION_MARKER.length();

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  private final ConcurrentHashMap<String, Buffer> live = new ConcurrentHashMap<>();

  /**
   * Which step a run is on, when it really started, and what it has printed so far.
   *
   * <p>{@code startedAt} is <b>null until the step starts</b>, which is a state and not a gap: a
   * buffer opens the moment the runner takes the step on, and the container has still to be asked
   * for, started and dialled back before there is a step to have begun. It is the same instant, taken
   * at the same moment on the same side of the wire, that the step's row will carry — host-stamped,
   * never daemon-reported.
   */
  public record Snapshot(int stepIndex, Instant startedAt, String output) {}

  /** Start relaying a step. Replaces whatever the run's previous step left behind. */
  public void begin(String runId, int stepIndex) {
    live.put(runId, new Buffer(stepIndex, Math.max(MARKER_LENGTH + 1, outputMaxChars)));
  }

  /**
   * Stamp when the run's live step really began — the moment the host handed the script over.
   *
   * <p>One instant per buffer and nothing else: this class deliberately holds the minimum a live
   * read needs, and what it is being told here is the same fact the step row records at its end
   * rather than a second timeline. A stamp for a run with no live step is dropped, exactly as a
   * chunk for one is: neither resurrects a buffer.
   */
  public void started(String runId, Instant startedAt) {
    Buffer buffer = live.get(runId);
    if (buffer != null) {
      buffer.started(startedAt);
    }
  }

  /** Append one chunk. A chunk for a run with no live step is dropped, not resurrected. */
  public void append(String runId, String text) {
    Buffer buffer = live.get(runId);
    if (buffer != null && text != null) {
      buffer.append(text);
    }
  }

  /** The bounded tail of the run's current step, marked if its head was dropped. */
  public Optional<Snapshot> snapshot(String runId) {
    Buffer buffer = live.get(runId);
    return buffer == null
        ? Optional.empty()
        : Optional.of(new Snapshot(buffer.stepIndex, buffer.startedAt(), buffer.text()));
  }

  /** Forget a run's live output. Called when its run closes, however it closed. */
  public void drop(String runId) {
    live.remove(runId);
  }

  /** Observational: how many runs are relaying. Zero between runs. */
  public int size() {
    return live.size();
  }

  /**
   * A rolling tail. Trimming happens at twice the budget so a chatty step costs one array copy per
   * {@code maxChars} of output rather than one per chunk, and {@link #text()} trims exactly.
   */
  private static final class Buffer {

    private static final int TRIM_FACTOR = 2;

    private final int stepIndex;
    private final int maxChars;
    private final StringBuilder text = new StringBuilder();
    private boolean truncated;

    // Written on the run worker when the step is handed over and read on an HTTP worker — one
    // reference, so volatile is the whole of the synchronization it needs. Deliberately not under
    // the buffer's monitor: a read of it must never queue behind a chatty step's append.
    private volatile Instant startedAt;

    Buffer(int stepIndex, int maxChars) {
      this.stepIndex = stepIndex;
      this.maxChars = maxChars;
    }

    void started(Instant at) {
      startedAt = at;
    }

    Instant startedAt() {
      return startedAt;
    }

    synchronized void append(String chunk) {
      text.append(chunk);
      if (text.length() > (long) maxChars * TRIM_FACTOR) {
        text.delete(0, text.length() - maxChars);
        truncated = true;
      }
    }

    /**
     * The tail, never longer than the budget <em>including</em> the marker — so the caller can write
     * it straight onto a step row without a second truncation pass that would eat the marker it just
     * read.
     */
    synchronized String text() {
      if (text.length() > maxChars) {
        text.delete(0, text.length() - maxChars);
        truncated = true;
      }
      if (!truncated) {
        return text.toString();
      }
      int keep = Math.min(text.length(), maxChars - MARKER_LENGTH);
      return CiRunService.TRUNCATION_MARKER + text.substring(text.length() - keep);
    }
  }
}
