package eu.wohlben.qits.ci.registry;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * <b>The registry's digest answer for the service suite</b> (epic qits-1481): every step of a run
 * runs in {@code qits/build-images/ci-slot}, a platform image a run pins by digest at accept, and no
 * registry answers in the suite. So a reference this platform publishes is answered with one fixed
 * digest, without HTTP; everything else — a foreign image, an author's own digest — takes the real
 * {@link HttpImagePins} arm unchanged. A test that wants another answer installs {@link
 * StagedImagePins} over this, as it always did.
 */
@Mock
@ApplicationScoped
public class SuiteImagePins extends HttpImagePins {

  /** The digest every platform image is pinned to in the suite. */
  public static final String DIGEST = "sha256:" + "5".repeat(64);

  @Override
  public Pin pin(String reference) {
    if (reference != null && reference.indexOf('@') < 0) {
      String registry = publicRegistryHost();
      Ref ref = Ref.parse(reference, artifactsImageRepository, registry);
      if (ref != null) {
        return Pin.pinned(ref.pinned(registry, DIGEST));
      }
    }
    return super.pin(reference);
  }
}
