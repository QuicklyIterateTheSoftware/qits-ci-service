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
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final String AGENT = "qits:agent";

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
  @OidcSecurity(claims = {@Claim(key = QitsClaims.PROJECT, value = "agent-project")})
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
  @OidcSecurity(claims = {@Claim(key = QitsClaims.PROJECT, value = "agent-project")})
  void anAgentReadsTheRepositoryRoutes() {
    given().when().get("/ci/api/repositories").then().statusCode(200);
    given().when().get("/ci/api/repositories/summary").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = QitsClaims.PROJECT, value = "agent-project")})
  void anAgentReadsTheDaemonPin() {
    given().when().get("/ci/api/daemon").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = QitsClaims.PROJECT, value = "*")})
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
