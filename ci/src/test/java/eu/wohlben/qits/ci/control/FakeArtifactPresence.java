package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * qits-artifacts' presence answer for the ci suite: whatever the test scripts, and no HTTP. The
 * production implementation is {@code service/…/registry/HttpArtifactPresence} — this module has
 * none, which is the arrangement {@link FakeStepImagePins} documents and is also what makes {@link
 * CiArtifactPresence} resolvable here at all.
 *
 * <p>Answers are a queue, one per question, so "a 5xx and then a 200" is two entries; an empty
 * queue answers {@link #fallback}, {@code PRESENT} unless a test says otherwise. Every question is
 * recorded, which is how "asked three times" and "never asked" are asserted.
 */
@ApplicationScoped
public class FakeArtifactPresence implements CiArtifactPresence {

  /** One question this fake was asked. */
  public record Asked(CiArtifact.Type type, String name, String version) {}

  private final Deque<Probe> answers = new ArrayDeque<>();
  private final List<Asked> asked = new ArrayList<>();
  private Probe fallback = Probe.present("staged by " + FakeArtifactPresence.class.getSimpleName());

  public synchronized void reset() {
    answers.clear();
    asked.clear();
    fallback = Probe.present("staged by " + FakeArtifactPresence.class.getSimpleName());
  }

  /** The next answers, in order. */
  public synchronized void answer(Probe... probes) {
    answers.addAll(List.of(probes));
  }

  /** What every question answers once the scripted queue is spent. */
  public synchronized void otherwise(Probe probe) {
    fallback = probe;
  }

  public synchronized List<Asked> asked() {
    return List.copyOf(asked);
  }

  @Override
  public synchronized Probe probe(CiArtifact.Type type, String name, String version) {
    asked.add(new Asked(type, name, version));
    Probe next = answers.pollFirst();
    return next == null ? fallback : next;
  }
}
