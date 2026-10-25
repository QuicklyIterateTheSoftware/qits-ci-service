package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.projects.FakeProjectsRepoListing;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1596: the delivery desk's token carries {@code qits:agent} + {@code qits:desk} and a {@code
 * project} claim, and the rule is "the desk is admitted everywhere the coordinator ({@code
 * qits:admin-agent}) is admitted" — bound to the desk's own project wherever a door already binds a
 * narrower caller to one, admitted outright everywhere else.
 *
 * <p><b>One case per representative shape, not one per door.</b> {@code CiRunController}'s reads
 * (class-level and per-method lists) are the "admitted, nothing to bind" shape {@code
 * CiRepositoryController} and {@code CiReportController} share; {@code cancelRun} is the "admitted
 * outright because the door never bound anyone else either" shape; {@code retryRun} is the
 * project-bound shape {@code cancelReleaseRequestRuns} and {@code rerunReleaseRequestPhase} share
 * via the identical {@code cancellationScope()}/{@code requireRepositoryInProject} pair, and {@code
 * CiEventController.trigger}'s {@code scopeOf()} is the same mechanism one class over. The fourth
 * shape main's qits-1596 also covered — "admitted outright, no project at all", the runner
 * administration doors under {@code /ci/api/runners} — has no door here any more: the CI runner is
 * deleted (qits-1544), and a run's capacity is a lease on a qits-system target, whose doors are
 * qits-system's.
 *
 * <p>The profile is {@link MachineGuardTest.GateOn}'s, for {@code AgentReadAccessTest}'s reason:
 * under {@code %test} the forward-auth {@code dev} identity would answer every request as an admin,
 * and reusing it costs no second Quarkus start.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class DeskAccessTest {

  private static final String DESK = "qits:desk";

  private static final String AGENT = "qits:agent";

  /** This service's own audience, so the machine guard admits the token and only roles decide. */
  private static final String OWN_AUDIENCE = "qits-platform";

  private static final String REPO = "desk-access-repo";

  private static final String PROJECT = "desk-access-project";

  @Inject CiRunRepository runs;

  /** The only catalogue that answers a repository's project, so a scoped token is judged against it. */
  @Inject FakeProjectsRepoListing projectsListing;

  private String finishedRun;

  private String runningRun;

  @BeforeEach
  void seed() {
    finishedRun = insert(CiRunStatus.SUCCESS);
    runningRun = insert(CiRunStatus.RUNNING);
  }

  @AfterEach
  void clean() {
    projectsListing.unset();
    long until = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < until && !onlyTheSeededRunsAreUnfinished()) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    QuarkusTransaction.requiringNew().run(() -> runs.delete("repoId = ?1", REPO));
  }

  private boolean onlyTheSeededRunsAreUnfinished() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                runs.find("repoId = ?1", REPO).list().stream()
                    .filter(run -> !run.id.equals(runningRun) && !run.id.equals(finishedRun))
                    .noneMatch(
                        run ->
                            run.status == CiRunStatus.QUEUED || run.status == CiRunStatus.RUNNING));
  }

  private String insert(CiRunStatus status) {
    String id = UUID.randomUUID().toString();
    Instant at = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = REPO;
              run.projectId = PROJECT;
              run.repoName = REPO;
              run.branch = "main";
              run.commitSha = "d".repeat(40);
              run.status = status;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-desk-access.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = "SoftwareRelease";
              run.triggerEventOccurredAt = at;
              run.triggerEventPayload = "{}";
              run.triggerConfig = "event: SoftwareRelease\nsteps: []\n";
              run.createdAt = at;
              if (status != CiRunStatus.QUEUED) {
                run.startedAt = at;
              }
              if (status == CiRunStatus.SUCCESS) {
                run.finishedAt = at;
              }
              runs.persist(run);
            });
    return id;
  }

  /**
   * Every read route this surface has, admitted on {@code qits:desk} with no project filter — the
   * "admitted, nothing to bind" shape {@code CiRunController}'s class-level and per-method lists
   * share with {@code CiRepositoryController} and {@code CiReportController}: a plain {@code
   * qits:agent} already reads every one of these unscoped, so the desk's project claim narrows
   * nothing here either.
   */
  @Test
  @TestSecurity(user = "delivery-desk", roles = {DESK, AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-project")
      })
  void aDeskReadsEveryRunAndRepositoryRoute() {
    given().when().get("/ci/api/runs?repositoryId=" + REPO).then().statusCode(200);
    given().when().get("/ci/api/runs/active").then().statusCode(200);
    given().when().get("/ci/api/runs/queue").then().statusCode(200);
    given().when().get("/ci/api/runs/finished?limit=1").then().statusCode(200);
    given().when().get("/ci/api/runs/" + finishedRun).then().statusCode(200);
    given().when().get("/ci/api/repositories").then().statusCode(200);
    given().when().get("/ci/api/repositories/summary").then().statusCode(200);
  }

  /**
   * {@code cancelRun} never bound anyone to a project — not {@code qits:admin}, not {@code
   * qits:admin-agent} — so the desk is admitted the same way: outright. A running run really stops.
   */
  @Test
  @TestSecurity(user = "delivery-desk", roles = {DESK, AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-project")
      })
  void aDeskCancelsARunningRunOutright() {
    given().when().post("/ci/api/runs/" + runningRun + "/cancel").then().statusCode(202);
  }

  /**
   * {@code retryRun} binds a scoped caller to the run's own repository through {@code
   * cancellationScope()}/{@code requireRepositoryInProject} — the same pair {@code
   * cancelReleaseRequestRuns} and {@code rerunReleaseRequestPhase} use, and the one {@code
   * CiEventController.trigger}'s {@code scopeOf()} mirrors one class over. The desk is bound to its
   * own project exactly as a plain {@code qits:agent} is: admitted in its own project, refused in
   * another's.
   */
  @Test
  @TestSecurity(user = "delivery-desk", roles = {DESK, AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = PROJECT)
      })
  void aDeskRetriesARunInItsOwnProject() {
    projectsListing.set(new CiRepoRef(REPO, PROJECT, REPO));
    String retried =
        given()
            .when()
            .post("/ci/api/runs/" + finishedRun + "/retry")
            .then()
            .statusCode(202)
            .extract()
            .jsonPath()
            .getString("runId");
    assertNotNull(retried);
    assertNotEquals(finishedRun, retried);
  }

  @Test
  @TestSecurity(user = "delivery-desk", roles = {DESK, AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-project")
      })
  void aDeskMayNotRetryARunInAnotherProject() {
    projectsListing.set(new CiRepoRef(REPO, PROJECT, REPO));
    given().when().post("/ci/api/runs/" + finishedRun + "/retry").then().statusCode(403);
  }
}
