package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiReportBaselines.Baseline;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The baseline lookup (qits-983, epic qits-754 Design §4): newest release by {@link VersionSort},
 * the request that release row names (or, for a historical row, that version's RELEASE run carrying
 * one), that request's newest green QA run. Every gap is
 * "no baseline", never an exception, and a run is never its own baseline.
 */
@QuarkusTest
public class CiReportBaselinesTest extends CiTestSupport {

  private static final String V_OLD = "2026.810.98";

  /** Newer than {@link #V_OLD} by version sort, older by plain string order. */
  private static final String V_NEW = "2026.810.184518";

  @Inject CiReportBaselines baselines;

  private String repoId;

  private String repoName;

  /** The QA run asking for its baseline: a later release request of the same repository. */
  private CiRun asking;

  @BeforeEach
  void freshRepository() {
    repoId = UUID.randomUUID().toString();
    repoName = "qits-baseline-" + repoId.substring(0, 8);
    asking = run(CiRunPhase.RELEASE_REQUEST, "release/rr-asking", "rr-asking", CiRunStatus.RUNNING, 100);
  }

  @Test
  public void aRepositoryThatNeverReleasedHasNoBaseline() {
    assertEquals(Optional.empty(), baselines.forRun(asking));
  }

  @Test
  public void aReleaseWithNoReleaseRunHasNoBaseline() {
    release(V_OLD);
    assertEquals(Optional.empty(), baselines.forRun(asking));
  }

  @Test
  public void aReleaseRunWithNoRequestIdHasNoBaseline() {
    release(V_OLD);
    run(CiRunPhase.RELEASE, V_OLD, null, CiRunStatus.SUCCESS, 10);
    assertEquals(Optional.empty(), baselines.forRun(asking));
  }

  @Test
  public void theGatingRunOfTheNewestReleaseIsTheBaseline() {
    release(V_OLD);
    // An older red attempt and the green one: the newest SUCCESS is the gate.
    run(CiRunPhase.RELEASE_REQUEST, "release/rr-1", "rr-1", CiRunStatus.FAILED, 1);
    CiRun gate = run(CiRunPhase.RELEASE_REQUEST, "release/rr-1", "rr-1", CiRunStatus.SUCCESS, 2);
    CiRun releaseRun = run(CiRunPhase.RELEASE, V_OLD, "rr-1", CiRunStatus.SUCCESS, 3);

    assertEquals(
        Optional.of(new Baseline(V_OLD, gate.id, "rr-1", releaseRun.commitSha)),
        baselines.forRun(asking));
  }

  @Test
  public void versionSortAndNotStringOrderPicksTheNewestRelease() {
    release(V_OLD);
    release(V_NEW);
    run(CiRunPhase.RELEASE_REQUEST, "release/rr-old", "rr-old", CiRunStatus.SUCCESS, 1);
    run(CiRunPhase.RELEASE, V_OLD, "rr-old", CiRunStatus.SUCCESS, 2);
    CiRun gate = run(CiRunPhase.RELEASE_REQUEST, "release/rr-new", "rr-new", CiRunStatus.SUCCESS, 3);
    CiRun releaseRun = run(CiRunPhase.RELEASE, V_NEW, "rr-new", CiRunStatus.SUCCESS, 4);

    assertTrue(V_OLD.compareTo(V_NEW) > 0, "the fixture is the case string order gets wrong");
    assertEquals(
        Optional.of(new Baseline(V_NEW, gate.id, "rr-new", releaseRun.commitSha)),
        baselines.forRun(asking));
  }

  @Test
  public void theRunsOwnRequestIsNeverItsBaseline() {
    // The asking request has released V_NEW itself; its baseline is the version before.
    release(V_OLD);
    release(V_NEW);
    CiRun olderGate =
        run(CiRunPhase.RELEASE_REQUEST, "release/rr-old", "rr-old", CiRunStatus.SUCCESS, 1);
    CiRun olderRelease = run(CiRunPhase.RELEASE, V_OLD, "rr-old", CiRunStatus.SUCCESS, 2);
    CiRun ownGate =
        run(CiRunPhase.RELEASE_REQUEST, "release/rr-own", "rr-own", CiRunStatus.SUCCESS, 3);
    run(CiRunPhase.RELEASE, V_NEW, "rr-own", CiRunStatus.SUCCESS, 4);

    assertEquals(
        Optional.of(new Baseline(V_OLD, olderGate.id, "rr-old", olderRelease.commitSha)),
        baselines.forRun(ownGate));

    // And with nothing older to fall back on, there is none at all.
    QuarkusTransaction.requiringNew()
        .run(() -> scmReleases.delete("repoId = ?1 and version = ?2", repoName, V_OLD));
    assertEquals(Optional.empty(), baselines.forRun(ownGate));
  }

  // --- the release row names its request (SCMRelease's releaseRequestId, V34)

  private static final String TAG_SHA = "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af";

  /**
   * The live defect: a repository with no deployment (an spa-frontend, a library) has no RELEASE
   * run at all. The release row names its request and its tag's commit, and that is enough.
   */
  @Test
  public void aReleaseNamingItsRequestNeedsNoReleaseRun() {
    release(V_OLD, "rr-spa", TAG_SHA);
    CiRun gate = run(CiRunPhase.RELEASE_REQUEST, "release/rr-spa", "rr-spa", CiRunStatus.SUCCESS, 1);

    assertEquals(
        Optional.of(new Baseline(V_OLD, gate.id, "rr-spa", TAG_SHA)), baselines.forRun(asking));
  }

  /** The row's own commit wins over a release run's when both exist. */
  @Test
  public void theRowsCommitIsTheTagShaEvenBesideAReleaseRun() {
    release(V_OLD, "rr-1", TAG_SHA);
    CiRun gate = run(CiRunPhase.RELEASE_REQUEST, "release/rr-1", "rr-1", CiRunStatus.SUCCESS, 1);
    run(CiRunPhase.RELEASE, V_OLD, "rr-1", CiRunStatus.SUCCESS, 2);

    assertEquals(
        Optional.of(new Baseline(V_OLD, gate.id, "rr-1", TAG_SHA)), baselines.forRun(asking));
  }

  /** A row naming its request but no commit borrows the release run's, or has none. */
  @Test
  public void aRowWithoutACommitFallsBackToTheReleaseRunsThenToNone() {
    release(V_OLD, "rr-1", null);
    CiRun gate = run(CiRunPhase.RELEASE_REQUEST, "release/rr-1", "rr-1", CiRunStatus.SUCCESS, 1);

    assertEquals(
        Optional.of(new Baseline(V_OLD, gate.id, "rr-1", null)), baselines.forRun(asking));

    CiRun releaseRun = run(CiRunPhase.RELEASE, V_OLD, "rr-1", CiRunStatus.SUCCESS, 2);
    assertEquals(
        Optional.of(new Baseline(V_OLD, gate.id, "rr-1", releaseRun.commitSha)),
        baselines.forRun(asking));
  }

  /** A named request with no green QA run is still no baseline. */
  @Test
  public void aNamedRequestWithNoGreenGateHasNoBaseline() {
    release(V_OLD, "rr-1", TAG_SHA);
    run(CiRunPhase.RELEASE_REQUEST, "release/rr-1", "rr-1", CiRunStatus.FAILED, 1);

    assertEquals(Optional.empty(), baselines.forRun(asking));
  }

  @Test
  public void theRunsOwnRequestIsNeverItsBaselineWhenTheRowNamesIt() {
    release(V_OLD, "rr-old", TAG_SHA);
    release(V_NEW, "rr-own", "0".repeat(40));
    CiRun olderGate =
        run(CiRunPhase.RELEASE_REQUEST, "release/rr-old", "rr-old", CiRunStatus.SUCCESS, 1);
    CiRun ownGate =
        run(CiRunPhase.RELEASE_REQUEST, "release/rr-own", "rr-own", CiRunStatus.SUCCESS, 3);

    assertEquals(
        Optional.of(new Baseline(V_OLD, olderGate.id, "rr-old", TAG_SHA)),
        baselines.forRun(ownGate));

    QuarkusTransaction.requiringNew()
        .run(() -> scmReleases.delete("repoId = ?1 and version = ?2", repoName, V_OLD));
    assertEquals(Optional.empty(), baselines.forRun(ownGate));
  }

  /** A named newest release over a historical older one: the own-request skip reaches the old path. */
  @Test
  public void theOwnRequestSkipFallsThroughToAHistoricalRow() {
    release(V_OLD);
    release(V_NEW, "rr-own", TAG_SHA);
    CiRun olderGate =
        run(CiRunPhase.RELEASE_REQUEST, "release/rr-old", "rr-old", CiRunStatus.SUCCESS, 1);
    CiRun olderRelease = run(CiRunPhase.RELEASE, V_OLD, "rr-old", CiRunStatus.SUCCESS, 2);
    CiRun ownGate =
        run(CiRunPhase.RELEASE_REQUEST, "release/rr-own", "rr-own", CiRunStatus.SUCCESS, 3);

    assertEquals(
        Optional.of(new Baseline(V_OLD, olderGate.id, "rr-old", olderRelease.commitSha)),
        baselines.forRun(ownGate));
  }

  /** The release fact, announced under the repository's public name as qits-workspaces does. */
  private void release(String version) {
    release(version, null, null);
  }

  /** The same, naming the request it came out of and its tag's commit, as SCMRelease now does. */
  private void release(String version, String releaseRequestId, String commitSha) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiScmRelease release = new CiScmRelease();
              release.id = UUID.randomUUID().toString();
              release.repoId = repoName;
              release.version = version;
              release.releaseRequestId = releaseRequestId;
              release.commitSha = commitSha;
              release.eventId = UUID.randomUUID().toString();
              release.occurredAt = Instant.now();
              release.seenAt = Instant.now();
              scmReleases.persist(release);
            });
  }

  private CiRun run(
      CiRunPhase phase, String branch, String requestId, CiRunStatus status, int minute) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repoId;
    run.repoName = repoName;
    run.branch = branch;
    run.commitSha = String.format("%040x", minute + 1);
    run.releaseRequestId = requestId;
    run.phase = phase;
    run.status = status;
    run.triggerType = CiTriggerType.EVENT;
    run.configPath = ".config/qits/release.yml";
    run.triggerEventId = UUID.randomUUID().toString();
    run.createdAt = Instant.parse("2026-10-01T00:00:00Z").plusSeconds(60L * minute);
    QuarkusTransaction.requiringNew().run(() -> runs.persist(run));
    return run;
  }
}
