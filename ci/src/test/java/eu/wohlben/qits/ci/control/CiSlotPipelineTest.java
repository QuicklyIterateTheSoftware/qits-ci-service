package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The two config rules a run in a slot brings</b> (epic qits-1481, "CI in a slot", qits-1544):
 * every step of a run runs in the run's one {@code qits/build-images/ci-slot} container, so a step's
 * {@code image:} is ignored — said once, in the run's own log — and a {@code docker: true} step is a
 * {@code CONFIG_ERROR}, because a slot has no docker socket and its image no docker CLI.
 */
@QuarkusTest
public class CiSlotPipelineTest extends CiTestSupport {

  @Inject FakeRunAnnouncer announcer;

  private static final String DIGEST = "sha256:" + "5".repeat(64);

  @BeforeEach
  void quiet() {
    announcer.reset();
  }

  @Test
  public void aDockerStepIsAConfigErrorWithTheExactSentenceAndNothingRuns() throws Exception {
    String repo = "docker-" + UUID.randomUUID();
    executePipeline(
        repo,
        "main",
        "d".repeat(40),
        """
        steps:
          - script: ./verify.sh
          - image: docker:28-dind
            docker: true
            script: docker build .
          - script: ./after.sh
        """);
    forgetLoadedEntities();

    CiRun run = only(repo);
    assertEquals(CiRunStatus.CONFIG_ERROR, run.status);
    assertNotNull(run.finishedAt);
    assertTrue(fakeRunner.executed().isEmpty(), "no step of it is sent anywhere");
    assertEquals(null, run.leaseId, "and no lease is asked for it");
    List<CiStep> recorded = runService.stepsFor(run.id);
    assertEquals(3, recorded.size());
    assertEquals(CiStepStatus.SKIPPED, recorded.get(0).status);
    assertEquals(CiStepStatus.FAILED, recorded.get(1).status, "the step that asks for docker");
    assertEquals(
        "docker steps are not supported: steps run in qits/build-images/ci-slot without Docker",
        recorded.get(1).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(2).status);
    // A verdict, so a release request's gate is not left waiting for one.
    assertEquals(
        List.of(run.id), announcer.failed().stream().map(FakeRunAnnouncer.AnnouncedFailure::runId).toList());
    assertEquals("CONFIG_ERROR", announcer.failed().get(0).outcome());
  }

  @Test
  public void aStepImageIsIgnoredSaidOnceAndEveryStepRunsInThePinnedSlotImage() throws Exception {
    String slotTag = runService.resolveStepImage(CiSlot.IMAGE_TAG);
    fakeImagePins.pins(slotTag, DIGEST);
    String repo = "images-" + UUID.randomUUID();
    executePipeline(
        repo,
        "main",
        "e".repeat(40),
        """
        steps:
          - image: qits/build-images/maven-base:latest
            script: ./verify.sh
          - image: qits/build-images/node-base:latest
            script: ./publish.sh
          - script: ./announce.sh
        """);
    forgetLoadedEntities();

    CiRun run = only(repo);
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(
        List.of(slotTag),
        fakeImagePins.asked().stream().distinct().toList(),
        "the one image a run pins is the ci-slot image");
    String pinned = slotTag.substring(0, slotTag.lastIndexOf(':')) + "@" + DIGEST;
    assertEquals(3, fakeRunner.executed().size());
    assertTrue(
        fakeRunner.executed().stream().allMatch(spec -> pinned.equals(spec.image())),
        "every step runs in the run's pinned ci-slot image, whatever it declared");
    List<CiStep> recorded = runService.stepsFor(run.id);
    assertTrue(
        recorded.get(0).output.startsWith(
            "step images are ignored: every step runs in qits/build-images/ci-slot\n"),
        recorded.get(0).output);
    assertFalse(recorded.get(1).output.contains("step images are ignored"), "said once");
    assertTrue(recorded.stream().allMatch(step -> slotTag.equals(step.image)));
  }

  @Test
  public void aPipelineDeclaringNoImageIsToldNothing() throws Exception {
    String repo = "plain-" + UUID.randomUUID();
    executePipeline(
        repo,
        "main",
        "f".repeat(40),
        """
        steps:
          - script: ./verify.sh
          - build: true
            script: buildctl build .
        """);
    forgetLoadedEntities();

    CiRun run = only(repo);
    assertEquals(CiRunStatus.SUCCESS, run.status, "build: true still runs: every lease has a builder");
    assertTrue(
        runService.stepsFor(run.id).stream()
            .noneMatch(step -> step.output != null && step.output.contains("step images are ignored")));
  }

  private CiRun only(String repoId) {
    List<CiRun> found =
        QuarkusTransaction.requiringNew().call(() -> runs.list("repoId", repoId));
    assertEquals(1, found.size(), found.toString());
    return found.get(0);
  }
}
