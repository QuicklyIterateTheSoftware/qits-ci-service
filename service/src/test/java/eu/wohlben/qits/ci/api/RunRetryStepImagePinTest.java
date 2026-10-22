package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.runnerhost.StagedImagePins;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /ci/api/runs/{runId}/retry} answers 503 rather than 500 when the retry's re-pin of a
 * platform step image cannot be resolved — the HTTP side of {@code
 * CiRunCancelAndRetryTest.aRetryWhoseStepImageCannotBePinnedIsRefusedAndRecordsNoRun} in the {@code
 * ci} module, which proves the domain throws {@code UnavailableException}; this proves
 * {@code CiExceptionMapper} turns that into the response a caller actually sees.
 *
 * <p>{@link StagedImagePins#install(String) install(null)} stages the registry's silence: every
 * platform reference (one with no {@code @digest} already on it) answers {@code UNRESOLVED}, which
 * is exactly {@link eu.wohlben.qits.ci.runnerhost.RunnerQuarantineSocketTest}'s arrangement for the
 * runner health check's own pin, applied here to a retry's.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class RunRetryStepImagePinTest {

  private static final String REPO = "retry-pin-repo";

  @Inject CiRunRepository runs;

  @AfterEach
  void clean() {
    QuarkusTransaction.requiringNew().run(() -> runs.delete("repoId = ?1", REPO));
  }

  /**
   * A terminal run whose stored pipeline declares a platform step image — unlike {@code
   * AgentRetryAccessTest}'s zero-step fixture, which never asks the registry at all. {@code
   * configPath} names an ordinary committed trigger file, never {@code CiReleaseSlotParser.CONFIG_PATH},
   * so the retry replays these bytes verbatim rather than recomposing anything.
   */
  private String insertFinishedRun() {
    String id = UUID.randomUUID().toString();
    Instant at = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = REPO;
              run.branch = "main";
              run.commitSha = "b".repeat(40);
              run.status = CiRunStatus.SUCCESS;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-retry-pin.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = "SoftwareRelease";
              run.triggerEventOccurredAt = at;
              run.triggerEventPayload = "{}";
              run.triggerConfig =
                  """
                  event: SoftwareRelease
                  steps:
                    - image: qits/build-images/ci-base:latest
                      script: "true"
                  """;
              run.createdAt = at;
              run.startedAt = at;
              run.finishedAt = at;
              runs.persist(run);
            });
    return id;
  }

  @Test
  void aRetryWhoseStepImageCannotBePinnedIs503NamingTheImage() {
    // The door's other real caller (AgentRetryAccessTest's own last case): an operator arrives on
    // the edge's forwarded session, carries no bearer, and is never subject to the project scope
    // check — this case is about the registry refusal, not about who may press the button.
    StagedImagePins.install(null);
    String finished = insertFinishedRun();

    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .post("/ci/api/runs/" + finished + "/retry")
        .then()
        .statusCode(503)
        .body("message", containsString("qits/build-images/ci-base"));
  }
}
