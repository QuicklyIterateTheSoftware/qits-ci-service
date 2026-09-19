package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A {@code qits:agent} reads every read route {@code qits:admin} or {@code qits:system} reads, with no
 * filter, and writes nothing. One case per changed controller, and one for the writes.
 *
 * <p>The agent's token names a project, and the run it reads is in another one: agents lose no read
 * access, so the project claim narrows nothing here.
 *
 * <p><b>The profile is what makes the agent the caller.</b> Under {@code %test},
 * {@code ForwardAuthMechanism} gives every request without {@code X-Qits-User} the synthetic
 * {@code dev} identity, which holds {@code qits:admin}. {@code @TestSecurity} yields to any identity
 * that is not anonymous, so without the profile every case ran as {@code dev}: the reads passed for
 * the admin and the cancel reached its handler (409 on a finished run). {@link MachineGuardTest.GateOn}
 * blanks the dev user, and reusing it costs no second Quarkus start. The token carries this
 * service's audience and {@code project=*}, so {@code MachineAuth} would admit it: only the role can
 * refuse a write. The reads answering 200 and the writes answering 403 together prove the agent was
 * the caller.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class AgentReadAccessTest {

  private static final String AGENT = "qits:agent";

  /** This service's own audience, so the machine guard admits the token and only roles decide. */
  private static final String OWN_AUDIENCE = "qits-platform";

  private static final String REPO = "agent-read-repo";

  @Inject CiRunRepository runs;

  private String foreignRun;

  @BeforeEach
  void seed() {
    // A year ahead, so it is the newest finished run in the suite's shared database.
    Instant at = Instant.now().plus(Duration.ofDays(365));
    foreignRun = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = foreignRun;
              run.repoId = REPO;
              run.projectId = "some-other-project";
              run.repoName = REPO;
              run.branch = "main";
              run.commitSha = "a".repeat(40);
              run.status = CiRunStatus.SUCCESS;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-agent-read.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.createdAt = at;
              run.startedAt = at;
              run.finishedAt = at;
              runs.persist(run);
            });
  }

  @AfterEach
  void clean() {
    QuarkusTransaction.requiringNew().run(() -> runs.deleteById(foreignRun));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "agent-project")
      })
  void anAgentReadsEveryRunRoute() {
    List<String> byRepository =
        given()
            .when()
            .get("/ci/api/runs?repositoryId=" + REPO)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("runs.id", String.class);
    assertTrue(byRepository.contains(foreignRun), byRepository.toString());
    given().when().get("/ci/api/runs/active").then().statusCode(200);
    // The queue is a read like every other one here, so it names qits:agent too. A route added to
    // this resource without that role ships refusing every agent and nothing says so — which is the
    // whole reason this file has one case per read rather than one case for the class.
    given().when().get("/ci/api/runs/queue").then().statusCode(200);
    List<String> finished =
        given()
            .when()
            .get("/ci/api/runs/finished?limit=1")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("runs.id", String.class);
    assertTrue(finished.contains(foreignRun), finished.toString());
    given()
        .when()
        .get("/ci/api/runs/" + foreignRun)
        .then()
        .statusCode(200)
        .body("id", equalTo(foreignRun));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "agent-project")
      })
  void anAgentReadsTheRepositoryRoutes() {
    given().when().get("/ci/api/repositories").then().statusCode(200);
    given().when().get("/ci/api/repositories/summary").then().statusCode(200);
    // The release-phase read answers the ENDPOINT's own answer here rather than 200, and the
    // repository is deliberately one no suite seeds: it is in no catalogue, so the question cannot
    // be asked and the answer is 503 whatever else is running in this JVM. What the case rules out
    // is 401 and 403, which is the whole of what a role test can say — the verdicts themselves are
    // CiReleasePhaseSurfaceTest's and the ci module's.
    given()
        .when()
        .get("/ci/api/repositories/agent-read-no-such-repo/release-phase?rev=refs/tags/2026.9.1")
        .then()
        .statusCode(503);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "agent-project")
      })
  void anAgentReadsTheDaemonPin() {
    given().when().get("/ci/api/daemon").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void anAgentWritesNothing() {
    given().when().post("/ci/api/runs/" + foreignRun + "/cancel").then().statusCode(403);
    given().when().post("/ci/api/runs/" + foreignRun + "/retry").then().statusCode(403);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"repoId\":\"" + REPO + "\",\"releaseRequestId\":\"rr-agent\"}")
        .when()
        .post("/ci/api/runs/cancellations")
        .then()
        .statusCode(403);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"name\":\"SoftwareRelease\",\"payload\":{}}")
        .when()
        .post("/ci/api/events/trigger")
        .then()
        .statusCode(403);
  }
}
