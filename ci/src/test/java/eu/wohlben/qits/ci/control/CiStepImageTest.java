package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * <b>A step's image is recorded as spelled, and qits-ci adds no registry host to it</b> (qits-757).
 *
 * <p>The regression this pins: a bare {@code qits/build-images/…} reference used to be prefixed with
 * a configured registry host, whose shipped default was {@code registry.dev.localhost:8080} — and
 * that string was the label on every step tile of every run. The image a step row records, a pin is
 * keyed by and a client falls back to is now the ci-slot tag exactly as {@link CiSlot} spells it,
 * whatever the step declared.
 */
class CiStepImageTest {

  @Test
  void aPlatformStepImageGainsNoRegistryHost() {
    String image =
        CiRunService.stepImage(
            new CiPipeline.CiStepDecl(
                "qits/build-images/maven-base:latest", "true", null, false, false, ""));
    assertEquals("qits/build-images/ci-slot:latest", image);
    assertEquals(CiSlot.IMAGE_TAG, image);
    assertFalse(image.contains("localhost"), image);
    assertFalse(image.substring(0, image.indexOf('/')).contains("."), "no host segment: " + image);
  }

  @Test
  void everyStepRecordsTheCiSlotTagWhateverItDeclares() {
    // A declared image is ignored (epic qits-1481): one container runs every step.
    assertEquals(
        CiSlot.IMAGE_TAG,
        CiRunService.stepImage(new CiPipeline.CiStepDecl(null, "true", null, false, false, "")));
    assertEquals(
        CiSlot.IMAGE_TAG,
        CiRunService.stepImage(
            new CiPipeline.CiStepDecl("alpine:3", "true", null, false, false, "")));
    assertEquals(
        CiSlot.IMAGE_TAG,
        CiRunService.stepImage(
            new CiPipeline.CiStepDecl(
                "registry.qits.example.org/qits/build-images/ci-base:latest",
                "true",
                null,
                false,
                false,
                "")));
  }
}
