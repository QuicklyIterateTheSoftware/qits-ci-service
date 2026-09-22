package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry's digest answer for the ci suite: whatever the test says, and no HTTP. The
 * production implementation is {@code service/…/registry/HttpImagePins} — this module has none,
 * which is the arrangement {@code FakeGitHostRepoListing} documents for {@link GitHostRepoListing}
 * and is also what makes {@link CiStepImagePins} resolvable here at all.
 *
 * <p><b>The default is {@code FOREIGN} for everything</b>, and it is the interesting default rather
 * than a neutral one: every test in this suite names {@code alpine:3}, which really is an image
 * this platform does not publish, so the whole suite runs the arm a real deployment runs for those
 * references and nothing has to be staged to get there.
 *
 * <p>It also records every reference it was asked about, in order and with repeats, which is how
 * the once-per-run discipline is asserted: a build that resolved one tag twice would show two
 * entries for it, and no assertion about the step's image could ever see the difference.
 */
@ApplicationScoped
public class FakeStepImagePins implements CiStepImagePins {

  // Read and written through methods: a field read on an injected CDI client proxy sees the proxy's.
  private final Map<String, Pin> answers = new LinkedHashMap<>();
  private final List<String> asked = new ArrayList<>();

  /** Forgets both the staged answers and the record of what was asked. */
  public void reset() {
    answers.clear();
    asked.clear();
  }

  /** {@code reference} resolves to {@code digest} — the ordinary platform-image arm. */
  public void pins(String reference, String digest) {
    answers.put(reference, Pin.pinned(reference.substring(0, reference.lastIndexOf(':')) + "@" + digest));
  }

  /** {@code reference} is ours and the registry said nothing — the arm that refuses a run. */
  public void unresolvable(String reference) {
    answers.put(reference, Pin.unresolved(reference, "staged by " + FakeStepImagePins.class.getSimpleName()));
  }

  /** Every reference this fake was asked about, in order, repeats included. */
  public List<String> asked() {
    return List.copyOf(asked);
  }

  /** How many times one reference was asked about — the once-per-run assertion. */
  public long timesAsked(String reference) {
    return asked.stream().filter(reference::equals).count();
  }

  @Override
  public Pin pin(String reference) {
    asked.add(reference);
    Pin staged = answers.get(reference);
    if (staged != null) {
      return staged;
    }
    return reference.indexOf('@') >= 0 ? Pin.alreadyPinned(reference) : Pin.foreign(reference);
  }
}
