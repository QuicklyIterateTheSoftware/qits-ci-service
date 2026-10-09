package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiStepImagePins;
import eu.wohlben.qits.ci.registry.HttpImagePins;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.enterprise.inject.Vetoed;

/**
 * {@link CiStepImagePins} answering one staged digest for every platform reference, or {@code
 * UNRESOLVED} when none is staged — the service suite's registry answers nothing, so a case about
 * what a pin turns into stages the pin itself. Installed per test, as every {@link QuarkusMock} is.
 * A subclass of {@link HttpImagePins} because a mock must be assignable to the bean's own class,
 * and {@code @Vetoed}, so it is not a second bean of the type it stands in for.
 */
@Vetoed
public class StagedImagePins extends HttpImagePins {

  private final String digest;

  private StagedImagePins(String digest) {
    this.digest = digest;
  }

  /** Every tag pins to {@code digest}; null stages none, so every platform tag is unresolved. */
  public static StagedImagePins install(String digest) {
    StagedImagePins stand = new StagedImagePins(digest);
    QuarkusMock.installMockForType(stand, CiStepImagePins.class);
    return stand;
  }

  @Override
  public Pin pin(String reference) {
    if (reference.indexOf('@') >= 0) {
      return Pin.alreadyPinned(reference);
    }
    if (digest == null) {
      return Pin.unresolved(reference, "staged by " + StagedImagePins.class.getSimpleName());
    }
    return Pin.pinned(reference.substring(0, reference.lastIndexOf(':')) + "@" + digest);
  }
}
