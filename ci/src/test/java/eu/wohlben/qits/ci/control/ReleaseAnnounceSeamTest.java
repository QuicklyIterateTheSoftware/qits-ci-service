package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link ReleaseAnnouncer} seam's semantics: which green runs announce a published artifact,
 * with what, and — as often — which ones announce nothing.
 *
 * <p>Sibling of {@link RunAnnounceSeamTest}, and the pairing is the contract rather than a filing
 * convenience. A release pipeline's green run announces on <b>both</b> ports: {@code
 * BuildSuccessful} once, because it is a build that passed, and one {@code SoftwareRelease} per
 * declared artifact, because it is also a release. This class asserts that both happen and that
 * neither replaced the other.
 *
 * <p>What the production implementation does with the announcement (build a {@code SoftwareRelease},
 * hand it to the bus, land a PUT under the triggering event's id) is {@code
 * CiEventTriggerCausationTest}'s job in the service module.
 */
@QuarkusTest
public class ReleaseAnnounceSeamTest extends CiTestSupport {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-own-release.yml";

  private static final String HEAD = "b".repeat(40);

  /** The project the candidate listing answers with — what the announcement has to carry through. */
  private static final String PROJECT_ID = "p-1";

  /** A release pipeline: it selects its own repository's SCM release and declares what it ships. */
  private static final String RELEASE_TRIGGER =
      """
      event: SCMRelease
      artifacts:
        - { type: npm, name: "@qits/ui-components" }
        - { type: docker, name: qits/qits-stt }
      steps:
        - image: alpine:3
          script: ./publish-tag.sh
      """;

  /** A daemon release pipeline: it builds a platform binary and PUTs it to qits-artifacts. */
  private static final String DAEMON_TRIGGER =
      """
      event: SCMRelease
      artifacts:
        - { type: daemon, name: qits-ci-daemon }
      steps:
        - image: alpine:3
          docker: true
          script: ./publish-daemon.sh
      """;

  /**
   * The release pipeline again, declaring its checkout against the payload's own {@code branch}
   * rather than leaning on the default.
   *
   * <p>It exists for one case: a release event that states no {@code version}. The version is what a
   * release run's default checkout names its ref from, so such an event records no run at all — and
   * the case below is about a green run whose DECLARATION cannot be announced, not about a run that
   * never happened. Declaring the checkout is how the run is kept and the version made irrelevant to
   * it.
   */
  private static final String RELEASE_TRIGGER_AT_THE_PAYLOADS_BRANCH =
      """
      event: SCMRelease
      checkout:
        branch: branch
        sha: commitSha
      artifacts:
        - { type: npm, name: "@qits/ui-components" }
        - { type: docker, name: qits/qits-stt }
      steps:
        - image: alpine:3
          script: ./publish-tag.sh
      """;

  /** The same file without the declaration — an ordinary event pipeline, which publishes nothing. */
  private static final String PLAIN_TRIGGER =
      """
      event: SCMRelease
      steps:
        - image: alpine:3
          script: echo bump
      """;

  /** The commit the released tag points at, which is what a release run is now anchored at. */
  private static final String RELEASED_SHA = "c".repeat(40);

  /**
   * <b>Every release payload here states the revision it is about</b>, and that is the shape of the
   * event rather than a convenience of the fixture: a release event carries the commit its tag
   * points at, and a trigger file declaring no {@code checkout:} is recorded at that pair. It used
   * to be recorded at {@code main}'s head — a commit the release was not about — which is the
   * fallback this ticket removed.
   */
  private static final String RELEASED =
      "{\"branch\":\"main\",\"commitSha\":\""
          + RELEASED_SHA
          + "\",\"projectId\":\"p-1\",\"repository\":\"qits-spa-ui-components\","
          + "\"version\":\"1.4.0\"}";

  /**
   * A release event that states its revision and no version — which is what this class's one case
   * about a missing version is: the run happens, and the DECLARATION is what goes unannounced.
   *
   * <p>The version is also the ref a release run is recorded at, so a payload missing both would
   * record no run at all and the case would pass for a reason that is not the one it is about. The
   * trigger for it therefore declares its checkout explicitly, against the payload's own {@code
   * branch} — see {@link #RELEASE_TRIGGER_AT_THE_PAYLOADS_BRANCH}.
   */
  private static final String NO_VERSION =
      "{\"branch\":\"main\",\"commitSha\":\""
          + RELEASED_SHA
          + "\",\"projectId\":\"p-1\",\"repository\":\"qits-spa-ui-components\"}";

  /**
   * The same release, cut the way the release-request flow cuts one: {@code branch} names the
   * request's backing branch rather than a branch anybody pushed, and that branch is <b>deleted</b>
   * at tag creation, so it no longer exists by the time this event is evaluated.
   */
  private static final String RELEASED_FROM_A_BACKING_BRANCH =
      "{\"branch\":\"release/9f2c1a7e-4b31-4c8e-9a11-6d0f5c2e8b44\",\"commitSha\":\""
          + RELEASED_SHA
          + "\",\"projectId\":\"p-1\","
          + "\"repository\":\"qits-spa-ui-components\",\"repositoryName\":\"qits-spa-ui-components\","
          + "\"version\":\"1.4.0\"}";

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer runAnnouncer;
  @Inject FakeReleaseAnnouncer releaseAnnouncer;

  private String repoId;

  @BeforeEach
  void resetAnnouncers() {
    repoId = "releaser-" + UUID.randomUUID().toString().substring(0, 8);
    // The full public coordinate, because what this class asserts is what an announcement CARRIES:
    // projectId and repoId are on the event so a deploy consumer can address the repository without
    // a lookup, and only a named candidate has a project to carry. The id-addressed arm — where the
    // announcement correctly names none — is ReleaseJoinTest's.
    fakeCandidates.setRefs(CiRepoRef.of(repoId, PROJECT_ID, "qits-spa-ui-components"));
    runAnnouncer.reset();
    releaseAnnouncer.reset();
  }

  private String deliver(String trigger, String payload) throws Exception {
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, trigger));
    String eventId = UUID.randomUUID().toString();
    engine.evaluate(
        new CiEventTriggerService.Arrival(
            eventId, "SCMRelease", Instant.parse("2026-08-01T09:00:00Z"), payload));
    runService.awaitIdle();
    forgetLoadedEntities();
    return eventId;
  }

  @Test
  public void aGreenReleasePipelineAnnouncesOncePerDeclaredArtifact() throws Exception {
    String eventId = deliver(RELEASE_TRIGGER, RELEASED);

    CiRun run = runService.runsFor(repoId).get(0);
    List<FakeReleaseAnnouncer.Published> published = releaseAnnouncer.published();
    assertEquals(2, published.size(), "two declarations are two announcements");

    FakeReleaseAnnouncer.Published npm = published.get(0);
    assertEquals(run.id, npm.runId());
    assertEquals(repoId, npm.repoId(), "the repository that PUBLISHED it, not the one that released");
    assertEquals(
        PROJECT_ID,
        npm.projectId(),
        "the project comes off the run's own repository reference, never off the event's payload —"
            + " the event names what released, this names what published");
    assertEquals(
        "qits-spa-ui-components",
        npm.repoName(),
        "and the public name beside it — a deploy consumer reads the released repository's"
            + " deployments.yml at /git/<projectId>/<repoName>/blob/…, and the id-addressed"
            + " fallback is refused to everyone but qits-projects");
    assertEquals("1.4.0", npm.version(), "the version comes out of the triggering event's payload");
    assertEquals("npm", npm.packageType());
    assertEquals("@qits/ui-components", npm.packageName());
    assertEquals(eventId, npm.triggerEventId(), "the parent of the event this becomes");
    assertNotNull(npm.finishedAt(), "an event with no occurredAt is a 400 on the wire");
    assertTrue(
        Duration.between(run.finishedAt, npm.finishedAt()).abs().toNanos() < 1_000,
        "expected the row's own finishedAt (" + run.finishedAt + "), got " + npm.finishedAt());

    FakeReleaseAnnouncer.Published docker = published.get(1);
    assertEquals("docker", docker.packageType());
    assertEquals("qits/qits-stt", docker.packageName());
    assertEquals("1.4.0", docker.version());
    assertEquals(eventId, docker.triggerEventId(), "N siblings under one parent");

    // And the other port is untouched: this is still a build that passed, announced exactly once.
    assertEquals(1, runAnnouncer.announced().size(), "SoftwareRelease is additional, not a swap");
    assertEquals(run.id, runAnnouncer.announced().get(0).runId());
  }

  @Test
  public void aDaemonBinaryIsAnnouncedLikeAnyOtherArtifact() throws Exception {
    String eventId = deliver(DAEMON_TRIGGER, RELEASED);

    CiRun run = runService.runsFor(repoId).get(0);
    List<FakeReleaseAnnouncer.Published> published = releaseAnnouncer.published();
    assertEquals(1, published.size(), "one declaration is one announcement");

    FakeReleaseAnnouncer.Published daemon = published.get(0);
    // Nothing about the daemon type is special anywhere on this path, and that is the whole claim:
    // the keyword parses, travels as the wire value, and reaches the seam beside npm and docker with
    // no per-type branch to get wrong. qits-ci publishes nothing here either — the PUT to
    // qits-artifacts is a step inside the daemon repository's own pipeline.
    assertEquals("daemon", daemon.packageType());
    assertEquals("qits-ci-daemon", daemon.packageName());
    assertEquals("1.4.0", daemon.version());
    assertEquals(run.id, daemon.runId());
    assertEquals(repoId, daemon.repoId());
    assertEquals(eventId, daemon.triggerEventId());
    assertNotNull(daemon.finishedAt());

    // And it is still a build that passed, announced exactly once on the other port.
    assertEquals(1, runAnnouncer.announced().size());
  }

  @Test
  public void aRedReleasePipelineAnnouncesNothing() throws Exception {
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    deliver(RELEASE_TRIGGER, RELEASED);

    // A declaration is a claim about a pipeline that finished, so a failed publish step announces
    // nothing — which is the whole reason the announcement waits for the terminal transition.
    assertEquals(List.of(), releaseAnnouncer.published());
    assertEquals(List.of(), runAnnouncer.announced());
  }

  @Test
  public void aGreenRunThatDeclaredNothingAnnouncesNothing() throws Exception {
    deliver(PLAIN_TRIGGER, RELEASED);

    assertEquals(1, runAnnouncer.announced().size(), "it is still an ordinary green build");
    assertEquals(
        List.of(),
        releaseAnnouncer.published(),
        "no declaration, no release — the train stops here by construction");
  }

  @Test
  public void aTriggerThatDeclaredNoArtifactsAnnouncesNothingOnThisPortEver() {
    // The `artifacts:` key is what makes a file a release pipeline, so a file without one is silent
    // on this port however green it goes — which is every ordinary event pipeline. (It used to be
    // stated about pushes, whose file could not carry a declaration at all and which carried no
    // version to publish one under; that intake retired on 2026-09-05 and this is the same claim
    // about the trigger type that is left.)
    String sha = UUID.randomUUID().toString().replace("-", "");
    executePipeline(repoId, "main", sha, "steps:\n  - image: alpine:3\n    script: echo ok\n");

    assertEquals(1, runAnnouncer.announced().size());
    assertEquals(List.of(), releaseAnnouncer.published());
  }

  /**
   * The publisher of {@code SCMRelease} moves from qits-workspaces to qits-projects and its {@code
   * branch} becomes a release request's backing branch, {@code release/<id>} — which is deleted the
   * moment the tag is created, so it is gone before this event is ever evaluated. <b>Nothing about
   * the release pipeline may depend on it.</b>
   *
   * <p>This is the unit-level pin of that. The run is recorded at the <b>tag</b> and the commit the
   * release states — {@code version}@{@code commitSha} — because a trigger file with no {@code
   * checkout:} on a release event is recorded at the revision the event is about. The payload's
   * {@code branch} reaches neither the row nor the clone and is never validated as an identifier,
   * which is the claim this case is really about: {@code release/<id>} is deleted at tag creation
   * and nothing here may depend on it. What drives the announcement is the <b>version</b>, exactly
   * as it did before, so the coordinates of the published artifacts are byte for byte what a {@code
   * branch: main} payload produces.
   *
   * <p><b>It used to be recorded at {@code main}'s head</b>, which is the fallback this ticket
   * removed: a release run said {@code main@<head>} about a commit the release was not about, and
   * the released tree was named only inside a step script.
   *
   * <p>The step's own checkout is the other half and lives in the recipe rather than here: {@code
   * .config/qits/ci-event-release.yml} fetches {@code refs/tags/$version} and checks it out detached,
   * so the tree it builds is the tag's whatever the clone landed on. A pipeline that cloned the
   * event's branch instead would have died on the first release of the new flow.
   */
  @Test
  public void aReleaseCutFromADeletedBackingBranchAnnouncesExactlyTheSame() throws Exception {
    String eventId = deliver(RELEASE_TRIGGER, RELEASED_FROM_A_BACKING_BRANCH);

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals("1.4.0", run.branch, "the run builds the tag, never the backing branch");
    assertEquals(RELEASED_SHA, run.commitSha, "and the commit that tag points at");
    assertNotEquals(HEAD, run.commitSha, "never main's head: that is a commit this release is not about");

    List<FakeReleaseAnnouncer.Published> published = releaseAnnouncer.published();
    assertEquals(2, published.size(), "two declarations are two announcements, as ever");
    assertEquals("1.4.0", published.get(0).version(), "the version drives the coordinates");
    assertEquals("@qits/ui-components", published.get(0).packageName());
    assertEquals("qits/qits-stt", published.get(1).packageName());
    assertEquals(eventId, published.get(0).triggerEventId());
    assertEquals(1, runAnnouncer.announced().size(), "and it is still a build that passed");
  }

  @Test
  public void aDeclarationWhoseTriggerCarriesNoVersionAnnouncesNothing() throws Exception {
    deliver(RELEASE_TRIGGER_AT_THE_PAYLOADS_BRANCH, NO_VERSION);

    // The version is not qits-ci's to invent: announcing a blank one would publish a package
    // reference nothing can resolve. The run is green and says so on the other port; the declaration
    // is what was written against the wrong trigger, and it costs a WARN rather than an event.
    assertEquals(1, runAnnouncer.announced().size());
    assertEquals(List.of(), releaseAnnouncer.published());
  }
}
