package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * {@code POST /ci/api/runs/{runId}/cancel} takes {@code qits:agent} since qits-536, and like the
 * retry beside it the widening is scoped rather than blanket.
 *
 * <p><b>Why an agent presses it.</b> {@code AgentRetryAccessTest}'s case turned around: an agent
 * that knows a run is wasted — a build of a commit it has already superseded, a step hung on a
 * platform fault it has just reported — could re-fire a run but not stop one, and was left waiting
 * out the step deadline on capacity somebody else's queue stood behind.
 *
 * <p><b>What makes it safe is the scope check, and that is what this file is really about.</b> The
 * role admits the caller and {@link CiRunController#cancellationScope()} plus {@code
 * requireRepositoryInProject} decide the repository, against the RUN's own {@code repoId} — the
 * same two helpers the retry, the release-request cancellation and the phase rerun use. Both sides
 * are asserted: its own project is a 202, anybody else's is a 403, and a repository the catalogue
 * cannot place is a 403 too.
 *
 * <p><b>The order of the answers is asserted, not assumed.</b> The run is read before the scope is
 * asked, so a run id that names nothing is a 404 even for a caller that covers nothing, and a run
 * that has already finished is still the endpoint's own 409 once the scope passes.
 *
 * <p>The seeded run is {@code RUNNING} with no worker of this process owning it — what {@code
 * CiRunService.cancel} settles in one write, {@code CANCELLED}, without a container to ask — so the
 * 202 case is a real cancellation that finishes before the response does. The profile is {@link
 * MachineGuardTest.GateOn} for {@code AgentReadAccessTest}'s reason: the gate is what a deployment
 * runs with, and reusing that class's profile costs no second Quarkus start.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class AgentCancelAccessTest {

  private static final String AGENT = "qits:agent";

  /** This service's own audience, so the machine guard admits the token and only the scope decides. */
  private static final String OWN_AUDIENCE = "qits-platform";

  private static final String REPO = "agent-cancel-repo";

  private static final String PROJECT = "agent-cancel-project";

  @Inject CiRunRepository runs;

  /** The only catalogue that answers a repository's project, so it is what a scoped token is judged against. */
  @Inject FakeProjectsRepoListing projectsListing;

  private String finishedRun;

  private String runningRun;

  @BeforeEach
  void seed() {
    finishedRun = insert(CiRunStatus.SUCCESS);
    runningRun = insert(CiRunStatus.RUNNING);
  }

  /**
   * Every row this class wrote goes, for {@code AgentRetryAccessTest}'s reason: a recorded run makes
   * its repository a trigger candidate for the rest of this Quarkus instance, and a candidate in a
   * project no scoped caller holds turns {@code MachineGuardTest}'s manual-trigger cases from 503
   * into 403.
   */
  @AfterEach
  void clean() {
    projectsListing.unset();
    QuarkusTransaction.requiringNew().run(() -> runs.delete("repoId = ?1", REPO));
  }

  private CiRunStatus statusOf(String runId) {
    return QuarkusTransaction.requiringNew().call(() -> runs.findById(runId).status);
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
              run.commitSha = "c".repeat(40);
              run.status = status;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-agent-cancel.yml";
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

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = PROJECT)
      })
  void anAgentCancelsARunInItsOwnProject() {
    // THE case: the agent that knows the build is wasted stops it itself. The run no worker owns is
    // settled on the request thread, so the row is already CANCELLED when the 202 arrives — proof
    // the press reached the cancellation rather than only the door.
    projectsListing.set(new CiRepoRef(REPO, PROJECT, REPO));
    given().when().post("/ci/api/runs/" + runningRun + "/cancel").then().statusCode(202);
    assertEquals(CiRunStatus.CANCELLED, statusOf(runningRun));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-project")
      })
  void anAgentMayNotCancelARunInAnotherProject() {
    // Same catalogue, same repository, another project on the token — the cross-tenant case, and
    // the reason the role alone was never enough. 403, and the run is left running: stopping
    // somebody else's build is exactly what the scope check exists to refuse.
    projectsListing.set(new CiRepoRef(REPO, PROJECT, REPO));
    given().when().post("/ci/api/runs/" + runningRun + "/cancel").then().statusCode(403);
    assertEquals(CiRunStatus.RUNNING, statusOf(runningRun));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = PROJECT)
      })
  void anAgentIsRefusedWhenTheCatalogueCannotPlaceTheRepository() {
    // The fail-closed half, requireRepositoryInProject's rule rather than this endpoint's: a
    // repository the catalogue cannot place is in no project at all. Nothing is staged here, which
    // is exactly that state.
    given().when().post("/ci/api/runs/" + runningRun + "/cancel").then().statusCode(403);
    assertEquals(CiRunStatus.RUNNING, statusOf(runningRun));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-project")
      })
  void aRunIdThatNamesNothingIs404AndNot403() {
    // The run is read first, so a missing id is the endpoint's own 404 even for a token that
    // covers nothing — a 403 here would make the scope check an existence oracle.
    given().when().post("/ci/api/runs/no-such-run/cancel").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = PROJECT)
      })
  void aFinishedRunIsStill409() {
    // The scope passes here, so the 409 is the endpoint's own answer — a finished run has nothing
    // to stop — rather than a refusal wearing another code.
    projectsListing.set(new CiRepoRef(REPO, PROJECT, REPO));
    given().when().post("/ci/api/runs/" + finishedRun + "/cancel").then().statusCode(409);
  }

  @Test
  @TestSecurity(user = "alice", roles = {"qits:admin"})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void aSignedInAdminCancelsWithNoProjectClaimAtAll() {
    // The door's other real caller, unchanged by the widening: an operator's bearer holds
    // qits:admin and no project claim, the platform-wide arm of the scope check — so it cancels
    // with no catalogue staged at all. (It used to arrive on forward-auth's X-Qits-User /
    // X-Qits-Roles session, which leaves qits-auth-core with qits-1338, epic qits-543.)
    given()
        .when()
        .post("/ci/api/runs/" + runningRun + "/cancel")
        .then()
        .statusCode(202);
    assertEquals(CiRunStatus.CANCELLED, statusOf(runningRun));
  }
}
