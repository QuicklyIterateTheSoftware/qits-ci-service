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
 * {@code POST /ci/api/runs/{runId}/retry} is the ONE write a {@code qits:agent} may press, and it is
 * scoped rather than blanket.
 *
 * <p><b>Why the exception exists at all.</b> An agent standing in front of a release-request gate
 * that went red for a reason that is the platform's — a flaked container, a registry 500 — can
 * neither re-ask the gate nor explain it away; its only other door was an empty commit on a source
 * branch and a second full gate. Owner ruling, 2026-09-19. Everything else on this surface is
 * unchanged: {@code AgentReadAccessTest} still holds that an agent reads everything and writes
 * nothing else.
 *
 * <p><b>What makes it safe is the scope check, and that is what this file is really about.</b> The
 * role admits the caller and {@link CiRunController#cancellationScope()} plus {@code
 * requireRepositoryInProject} decide the repository, against the RUN's own {@code repoId} — the
 * same two helpers the cancellation and the phase rerun use, so "what does my token cover" has one
 * answer across every write here. Both sides are asserted: its own project is a 202, anybody else's
 * is a 403, and a repository the catalogue cannot place is a 403 too.
 *
 * <p><b>The claim these cases put on the token is the one a real agent carries.</b> qits-idp states
 * {@code project} on every commissioned agent credential — qits-projects' {@code
 * IdpAgentCredentials} sends {@code claims: {project: <projectId>}} for an {@code agent-container},
 * qits-workspaces' {@code IdpCredentialCommissioner} does the same for a workspace — so the check
 * reads something that is really there. An agent arriving without one holds no platform-wide role
 * either, so {@code cancellationScope()} refuses it, which is the fail-closed direction.
 *
 * <p><b>The order of the answers is asserted, not assumed.</b> The widening must not reorder the
 * endpoint's existing outcomes: a run id that names nothing is a 404 even for a caller that covers
 * nothing (a 403 there would tell an uncovered caller whether a run exists), and a run that has not
 * finished is still a 409.
 *
 * <p>The profile is {@link MachineGuardTest.GateOn} for {@code AgentReadAccessTest}'s reason: under
 * {@code %test} the forward-auth {@code dev} identity would answer every request as an admin, and
 * reusing that class's profile costs no second Quarkus start.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class AgentRetryAccessTest {

  private static final String AGENT = "qits:agent";

  /** This service's own audience, so the machine guard admits the token and only the scope decides. */
  private static final String OWN_AUDIENCE = "qits-platform";

  private static final String REPO = "agent-retry-repo";

  private static final String PROJECT = "agent-retry-project";

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
   * Every row this class wrote goes, retries included, and that is not tidiness.
   *
   * <p>A recorded run makes its repository a trigger CANDIDATE for the rest of this Quarkus
   * instance ({@code KnownCiRepos}), and a candidate whose project is not a scoped caller's turns
   * {@code MachineGuardTest}'s manual-trigger cases from "nothing could be read" (503) into "your
   * project holds nothing here" (403). Measured, not feared: leaving the retried rows behind failed
   * {@code aProjectScopedTokenIsAdmittedAndEvaluatedAgainstItsOwnProject} exactly that way.
   *
   * <p>The wait is what makes the delete safe rather than a race: an accepted retry is on the run
   * worker, and its zero-step pipeline finishes in milliseconds — deleting the row underneath it
   * would leave the worker updating something that is gone.
   */
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

  /**
   * A run a retry can really re-fire: terminal, with the snapshot that makes it re-runnable.
   *
   * <p>The pipeline declares no steps on purpose — a zero-step run reaches SUCCESS with no
   * container and no daemon, so the 202 case below is a real accept rather than a row the worker
   * then chokes on. {@code createdAt} is now rather than a year ahead, so this row never displaces
   * whatever another class's {@code ?limit=1} expects to be newest.
   */
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
              run.commitSha = "b".repeat(40);
              run.status = status;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-agent-retry.yml";
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
  void anAgentRetriesARunInItsOwnProject() {
    // THE case, and the whole point of the widening: the agent that is looking at the red gate
    // presses the button itself. 202 and a NEW run id — a retry is a second row, never a rewritten
    // one, which is what makes the answer pollable like any other run.
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
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-project")
      })
  void anAgentMayNotRetryARunInAnotherProject() {
    // Same catalogue, same repository, another project on the token — the cross-tenant case at the
    // door, and the reason the role alone was never enough. 403 rather than an accepted run:
    // starting somebody else's build is exactly what the scope check exists to refuse.
    projectsListing.set(new CiRepoRef(REPO, PROJECT, REPO));
    given().when().post("/ci/api/runs/" + finishedRun + "/retry").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = PROJECT)
      })
  void anAgentIsRefusedWhenTheCatalogueCannotPlaceTheRepository() {
    // The fail-closed half, and it is requireRepositoryInProject's documented rule rather than this
    // endpoint's own: a repository the catalogue cannot place is in no project at all, so an
    // unreadable or unconfigured listing refuses a scoped caller rather than quietly granting it
    // the platform. Nothing is staged here, which is exactly that state.
    given().when().post("/ci/api/runs/" + finishedRun + "/retry").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-project")
      })
  void aRunIdThatNamesNothingIs404AndNot403() {
    // The ordering the widening must not disturb. The run is read first, so a missing id is the
    // endpoint's own 404 even for a token that covers nothing — a 403 here would make the scope
    // check an existence oracle, and it would also send an agent hunting for a permission problem
    // it does not have.
    given().when().post("/ci/api/runs/no-such-run/retry").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = PROJECT)
      })
  void anUnfinishedRunIsStill409() {
    // The other outcome the widening must not reorder: the question is still being answered, and
    // two runs racing for one verdict is not what was asked for. The scope passes here, so the 409
    // is the endpoint's own answer rather than a refusal wearing another code.
    projectsListing.set(new CiRepoRef(REPO, PROJECT, REPO));
    given().when().post("/ci/api/runs/" + runningRun + "/retry").then().statusCode(409);
  }

  @Test
  void aForwardedAdminSessionRetriesWithNoProjectClaimAtAll() {
    // The door's other real caller, unchanged by the widening: an operator arrives on the edge's
    // forwarded X-Qits-User/X-Qits-Roles session, carries no bearer, is therefore not a machine,
    // and never reaches the scope check. A guard tightened to "always a project" would answer this
    // 403 and take the button away from the person it was written for.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .post("/ci/api/runs/" + finishedRun + "/retry")
        .then()
        .statusCode(202);
  }
}
