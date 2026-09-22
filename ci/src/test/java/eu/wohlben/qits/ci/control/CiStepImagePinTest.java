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
 * <p>Every recipe step on the estate names {@code qits/build-images/*:latest}. Carried through to
 * the launch as written, that tag is resolved independently by each step's own container start — so
 * a publish landing mid-build means a run that verified against one toolchain and published from
 * another, with nothing on the row to say which. The run now resolves each distinct reference to a
 * digest when it is accepted and spends that digest at every step.
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

  /** The platform's own image as a recipe spells it, and as it is addressed once resolved. */
  private static final String BARE = "qits/build-images/ci-base:latest";

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
    registryReference = "qits-platform-artifacts:8080/" + BARE;
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
    String pinned = "qits-platform-artifacts:8080/qits/build-images/ci-base@" + DIGEST;
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
        Map.of(registryReference, "qits-platform-artifacts:8080/qits/build-images/ci-base@" + DIGEST),
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
   * <b>An image this platform does not publish is launched as named, and the row says so by
   * carrying no pin.</b>
   *
   * <p>{@code alpine:3} and {@code docker:28-dind} come from a store qits-ci holds no credential
   * for and no address to. Recording nothing is the honest answer — "this reference floated" is
   * then readable rather than assumed — and refusing the run over it would stop the one repository
   * on the estate that legitimately cannot run on a platform image.
   */
  @Test
  public void aForeignImageLaunchesAsNamedAndPinsNothing() throws Exception {
    deliver(
        """
        event: SCMPublishCommit
        steps:
          - image: alpine:3
            script: "true"
        """);

    assertEquals("alpine:3", fakeRunner.executed().get(0).image());
    assertNull(
        runService.runsFor(repoId).get(0).stepImages,
        "no pin is a statement about a foreign registry, not a gap");
  }

  /**
   * <b>A reference that is already a digest is spent verbatim and never re-resolved.</b>
   *
   * <p>Re-resolving one would turn somebody's deliberate pin into whatever {@code :latest} is now,
   * which is this feature's own defect wearing the fix's clothes.
   */
  @Test
  public void anAuthorsOwnDigestIsSpentVerbatim() throws Exception {
    String pinnedByHand = "qits-platform-artifacts:8080/qits/build-images/ci-base@" + OTHER_DIGEST;
    deliver(
        """
        event: SCMPublishCommit
        steps:
          - image: %s
            script: "true"
        """
            .formatted(pinnedByHand));

    assertEquals(pinnedByHand, fakeRunner.executed().get(0).image());
    assertEquals(
        Map.of(pinnedByHand, pinnedByHand),
        StepImages.decode(runService.runsFor(repoId).get(0).stepImages),
        "recorded as its own pin: the run really did run at those bytes, and a reader should not"
            + " have to tell 'nobody pinned this' from 'the author did'");
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
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds(), "a run against an unknown tool is worse than none");
    assertEquals(List.of(), runService.runsFor(repoId));
    assertTrue(
        evaluation.repositoriesUnreadable().contains(repoId),
        "nothing was learned about which tool this build would use, so the event is owed and a"
            + " sweep asks a registry that has probably come back: " + evaluation);
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void deliver(String triggerFile) throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, triggerFile));
    engine.evaluate(push());
    runService.awaitIdle();
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
