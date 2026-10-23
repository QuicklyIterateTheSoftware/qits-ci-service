package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.StepImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A run is fixed to one toolchain, and the fixing happens once.</b>
 *
 * <p>Every step of a run runs in the run's one {@code qits/build-images/ci-slot} container (epic
 * qits-1481), whatever image the step declares. The run resolves that one image's {@code :latest} to
 * a digest when it is accepted, and its lease is asked for at that digest — so a publish of the image
 * landing mid-build cannot make a run verify against one toolchain and publish from another.
 *
 * <p>The cases here are the four answers the seam has, and each of them is a different sentence
 * about the run: pinned, already pinned by the author, not this platform's to pin, and not
 * resolvable at all. The last one is the only one that costs the run, which is the decision worth
 * reading twice — see {@link #aPlatformImageWithNoResolvableDigestRecordsNoRunAndStaysOwed}.
 */
@QuarkusTest
public class CiStepImagePinTest extends CiTestSupport {

  private static final String HEAD = "a".repeat(40);
  private static final String PUSHED = "b".repeat(40);
  private static final String TRIGGER_PATH = ".config/qits/ci-event-build.yml";

  /** The one image a run runs in, as the engine spells it before it is resolved. */
  private static final String BARE = CiSlot.IMAGE_TAG;

  /**
   * The registry the engine prefixes a platform reference with — the RESOLVED value of
   * {@code qits.artifacts.registry-host}, which the shipped default derives as
   * {@code ${QITS_ENVIRONMENT:dev}-qits-artifacts:8080}. Surefire gains no environment variable, so
   * what the engine really prefixes here is the {@code dev} fallback. Spelled once: these cases are
   * about the pin and the digest, and five loose copies of an address would make a change to that
   * default read as five unrelated failures.
   */
  private static final String REGISTRY = "dev-qits-artifacts:8080";

  private static final String DIGEST = "sha256:" + "1".repeat(64);
  private static final String OTHER_DIGEST = "sha256:" + "2".repeat(64);

  @Inject CiEventTriggerService engine;

  private String repoId;
  private String registryReference;

  @BeforeEach
  void seedRepository() {
    repoId = "pinned-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    // The reference the engine really pins and launches: CiStepImage prefixes the platform's own
    // namespace with the registry, and the pin is asked about what will be pulled.
    registryReference = REGISTRY + "/" + BARE;
  }

  /**
   * <b>Two steps of one build, one tag, one digest — and one question asked of the registry.</b>
   *
   * <p>This is the whole feature in one case. The count is asserted as well as the value, because
   * the two failures are different: resolving twice and getting one answer is luck, and no
   * assertion about the launched image could tell it from the guarantee.
   */
  @Test
  public void twoStepsNamingOneTagBootTheSameDigestAndResolveItOnce() throws Exception {
    fakeImagePins.pins(registryReference, DIGEST);
    deliver(
        """
        event: SCMPublishCommit
        steps:
          - image: qits/build-images/ci-base:latest
            script: "true"
          - image: qits/build-images/ci-base:latest
            script: "true"
        """);

    List<CiStepRunner.StepSpec> launched = fakeRunner.executed();
    assertEquals(2, launched.size());
    String pinned = REGISTRY + "/qits/build-images/ci-slot@" + DIGEST;
    assertEquals(pinned, launched.get(0).image());
    assertEquals(
        pinned,
        launched.get(1).image(),
        "a build must never straddle two versions of one tool — the second step spends the first"
            + " step's digest rather than asking again");
    assertEquals(
        1,
        fakeImagePins.timesAsked(registryReference),
        "one resolution per (run, reference), not per step: asking twice is the defect, and two"
            + " equal answers would only mean nothing was published in between");
  }

  /** The run row is the readable half: which reference meant which bytes, for this run. */
  @Test
  public void theRunRowRecordsWhatWasResolved() throws Exception {
    fakeImagePins.pins(registryReference, DIGEST);
    deliver(
        """
        event: SCMPublishCommit
        steps:
          - image: qits/build-images/ci-base:latest
            script: "true"
        """);

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(
        Map.of(registryReference, REGISTRY + "/qits/build-images/ci-slot@" + DIGEST),
        StepImages.decode(run.stepImages),
        "archetype_rev says which recipe ran; this says which image it ran inside, and neither is"
            + " reconstructable afterwards by asking a registry what a moving tag used to be");
    // The step row keeps the reference the recipe named, deliberately: it is what the duration
    // prediction samples history by, and a digest there would reset every pipeline's history on
    // every build-image publish.
    assertEquals(
        registryReference,
        steps.listByRunIdOrdered(run.id).get(0).image,
        "the step row is the reference, the run row is the bytes — together they say what ran");
  }

  /**
   * <b>A step's own image is ignored</b>, a foreign one and an author's own digest alike: what runs
   * is the run's ci-slot image, and a deployment whose registry pins nothing launches it at its tag.
   */
  @Test
  public void aStepsOwnImageIsIgnoredWhateverItNames() throws Exception {
    deliver(
        """
        event: SCMPublishCommit
        steps:
          - image: alpine:3
            script: "true"
          - image: %s
            script: "true"
        """
            .formatted(REGISTRY + "/qits/build-images/ci-base@" + OTHER_DIGEST));

    assertEquals(registryReference, fakeRunner.executed().get(0).image());
    assertEquals(registryReference, fakeRunner.executed().get(1).image());
    assertEquals(
        List.of(registryReference),
        fakeImagePins.asked().stream().distinct().toList(),
        "the one image asked about is ci-slot");
    assertNull(
        runService.runsFor(repoId).get(0).stepImages,
        "the registry answered nothing to pin, and nothing is recorded as pinned");
  }

  /**
   * <b>A platform image whose digest could not be had is NO RUN, and the event stays OWED.</b>
   *
   * <p>The three answers beside it record something true. This one records nothing, because nothing
   * was learned: the registry did not answer, or answered that it holds no such tag. Accepting the
   * run anyway is precisely the floating build this feature closes — the containers would each pull
   * whatever the tag points at when they start — so the accept refuses before a row exists.
   *
   * <p><b>Owed rather than settled</b>, which is the opposite decision from a payload that carries
   * no revision and for the opposite reason: a registry that did not answer is a question that
   * still stands, so asking again minutes later is a different question with a possibly different
   * answer. Settling it would lose the build outright — the release request behind it would wait on
   * a verdict nothing would ever record again — which is exactly what an {@code UNREACHABLE} {@code
   * release.yml} used to cost and is why that read is owed too.
   */
  @Test
  public void aPlatformImageWithNoResolvableDigestRecordsNoRunAndStaysOwed() throws Exception {
    fakeImagePins.unresolvable(registryReference);
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            TRIGGER_PATH,
            """
            event: SCMPublishCommit
            steps:
              - image: qits/build-images/ci-base:latest
                script: "true"
            """));

    CiEventTriggerService.Evaluation evaluation = engine.evaluate(push());
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds(), "a run against an unknown tool is worse than none");
    assertEquals(List.of(), runService.runsFor(repoId));
    assertTrue(
        evaluation.repositoriesUnreadable().contains(repoId),
        "nothing was learned about which tool this build would use, so the event is owed and a"
            + " sweep asks a registry that has probably come back: " + evaluation);
    assertEquals(
        3,
        fakeImagePins.timesAsked(registryReference),
        "asked once and then once per configured retry delay before the run is refused");
  }

  /**
   * <b>A registry that misses a lookup and answers the next one costs nothing</b> (qits-1183). On
   * 2026-10-11 one missed lookup dropped a dependency bump's run, while the same image resolved for
   * other repositories seconds before and after. The accept now asks again after a short wait.
   */
  @Test
  public void aBriefRegistryBlipIsAskedAgainAndTheRunStarts() throws Exception {
    fakeImagePins.unresolvableThenPins(registryReference, 2, DIGEST);
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            TRIGGER_PATH,
            """
            event: SCMPublishCommit
            steps:
              - image: qits/build-images/ci-base:latest
                script: "true"
            """));

    CiEventTriggerService.Evaluation evaluation = engine.evaluate(push());
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, evaluation.runIds().size(), "the third lookup answered: " + evaluation);
    assertEquals(List.of(), evaluation.repositoriesUnreadable(), "nothing is owed: " + evaluation);
    assertEquals(3, fakeImagePins.timesAsked(registryReference));
    assertEquals(
        REGISTRY + "/qits/build-images/ci-slot@" + DIGEST, fakeRunner.executed().get(0).image());
  }

  /**
   * <b>The same event id again answers with the run it already started</b> (qits-1183). A caller
   * that sends an event a second time — qits-maintenance after a lost answer, a redelivery, a sweep
   * — must find that run, not read "no run recorded", and must not get a second one. The look comes
   * before the pin, so a registry that is down by then cannot refuse it either.
   */
  @Test
  public void aRepeatedEventIdAnswersWithTheRunItAlreadyStarted() throws Exception {
    fakeImagePins.pins(registryReference, DIGEST);
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            TRIGGER_PATH,
            """
            event: SCMPublishCommit
            steps:
              - image: qits/build-images/ci-base:latest
                script: "true"
            """));
    CiEventTriggerService.Arrival arrival = push();

    CiEventTriggerService.Evaluation first = engine.evaluate(arrival);
    suiteRunner.awaitIdle();
    assertEquals(1, first.runIds().size(), "the first send starts the run: " + first);

    fakeImagePins.unresolvable(registryReference);
    CiEventTriggerService.Evaluation again = engine.evaluate(arrival);
    suiteRunner.awaitIdle();
    forgetLoadedEntities();

    assertEquals(first.runIds(), again.runIds(), "the repeat names the run that exists");
    assertEquals(List.of(), again.repositoriesUnreadable(), "nothing is owed: " + again);
    assertEquals(1, runService.runsFor(repoId).size(), "a repeated id records no second run");
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void deliver(String triggerFile) throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, triggerFile));
    engine.evaluate(push());
    suiteRunner.awaitIdle();
    forgetLoadedEntities();
  }

  private CiEventTriggerService.Arrival push() {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(),
        "SCMPublishCommit",
        Instant.parse("2026-09-22T12:00:00Z"),
        "{\"branch\":\"main\",\"sha\":\"" + PUSHED + "\"}");
  }
}
