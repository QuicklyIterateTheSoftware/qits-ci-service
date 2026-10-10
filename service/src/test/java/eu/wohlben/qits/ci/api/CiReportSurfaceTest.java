package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiReportStore;
import eu.wohlben.qits.ci.dto.CiReportHighlightDto;
import eu.wohlben.qits.ci.dto.CiReportSubmission;
import eu.wohlben.qits.ci.entity.CiReport;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunPhase;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.ScriptedRunTokens;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release-report doors' shapes (qits-983, epic qits-754 Design §5): the four reads — a run's
 * reports with its baseline, one report with its payload, the baseline, the baseline's reports of a
 * kind — the gate's reports a publish run's changelog reads (qits-893), and the submit door's own
 * refusals past the binding (413, the highlight count, a bad kind) and its replace. Who may knock is {@code MachineGuardTest}'s; this file runs on its profile rather
 * than a new one, so it costs no extra Quarkus start.
 *
 * <p>The fixture is a repository with one released version whose gating QA run holds a coverage and
 * a test-results report, a later QA run of the same repository (the asker), and a run of a
 * repository that never released (the lonely one).
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class CiReportSurfaceTest {

  private static final String OWN_AUDIENCE = "qits-platform";

  private static final String AGENT = "qits:agent";

  private static final String RUN_SUBJECT = "tok-ci-run-report-surface";

  private static final String VERSION = "2026.1003.52637";

  @Inject CiRunRepository runs;

  @Inject CiScmReleaseRepository scmReleases;

  @Inject CiReportStore store;

  @Inject ObjectMapper objectMapper;

  private final List<String> seededRuns = new ArrayList<>();

  private String repo;

  private CiRun gate;

  private CiRun releaseRun;

  private CiRun asking;

  private CiRun lonely;

  private CiReport gateCoverage;

  private CiReport askingTests;

  @BeforeEach
  void seed() throws Exception {
    repo = "report-surface-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiScmRelease release = new CiScmRelease();
              release.id = UUID.randomUUID().toString();
              release.repoId = repo;
              release.version = VERSION;
              release.eventId = UUID.randomUUID().toString();
              release.occurredAt = Instant.now();
              release.seenAt = Instant.now();
              scmReleases.persist(release);
            });
    gate = run(repo, CiRunPhase.RELEASE_REQUEST, "release/rr-base", "rr-base", CiRunStatus.SUCCESS, 1);
    releaseRun = run(repo, CiRunPhase.RELEASE, VERSION, "rr-base", CiRunStatus.SUCCESS, 2);
    asking = run(repo, CiRunPhase.RELEASE_REQUEST, "release/rr-new", "rr-new", CiRunStatus.RUNNING, 3);
    lonely =
        run("report-lonely-" + UUID.randomUUID(), CiRunPhase.RELEASE_REQUEST, "release/rr-x", "rr-x",
            CiRunStatus.RUNNING, 4);

    gateCoverage =
        store.submit(gate.id, 1, "coverage", submission("{\"total\":{\"percent\":81.4}}", null));
    store.submit(gate.id, 1, "test-results", submission("{\"totals\":{\"tests\":9}}", null));
    askingTests =
        store.submit(
            asking.id,
            1,
            "test-results",
            submission(
                "{\"totals\":{\"tests\":10,\"failed\":1}}",
                new CiReportSubmission.Baseline(gate.id, VERSION)));

    ScriptedRunTokens tokens = new ScriptedRunTokens(RUN_SUBJECT);
    tokens.forRun(asking.id, Map.of());
    tokens.forRun(lonely.id, Map.of());
    QuarkusMock.installMockForType(tokens, RunCommissions.class);
  }

  @AfterEach
  void clean() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String runId : seededRuns) {
                store.deleteForRun(runId);
                runs.deleteById(runId);
              }
              scmReleases.delete("repoId", repo);
            });
    seededRuns.clear();
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void theRunsReportsCarryItsCoordinatesItsBaselineAndSummariesWithoutPayload() {
    given()
        .when()
        .get("/ci/api/runs/" + asking.id + "/reports")
        .then()
        .statusCode(200)
        .body("runId", equalTo(asking.id))
        .body("commitSha", equalTo(asking.commitSha))
        .body("releaseRequestId", equalTo("rr-new"))
        .body("baseline.version", equalTo(VERSION))
        .body("baseline.runId", equalTo(gate.id))
        .body("baseline.releaseRequestId", equalTo("rr-base"))
        .body("baseline.tagSha", equalTo(releaseRun.commitSha))
        .body("reports.size()", equalTo(1))
        .body("reports[0].id", equalTo(askingTests.id.toString()))
        .body("reports[0].kind", equalTo("test-results"))
        .body("reports[0].kindVersion", equalTo(1))
        .body("reports[0].stepIndex", equalTo(1))
        .body("reports[0].highlights[0].severity", equalTo("bad"))
        .body("reports[0].highlights[0].text", equalTo("1 test failed"))
        .body("reports[0].highlights[0].metric", equalTo("failed"))
        .body("reports[0].highlights[0].value", equalTo(1.0f))
        .body("reports[0].highlights[0].delta", equalTo(1.0f))
        .body("reports[0].baselineRunId", equalTo(gate.id))
        .body("reports[0].baselineVersion", equalTo(VERSION))
        .body("reports[0].payloadBytes", equalTo(askingTests.payloadBytes))
        .body("reports[0].submittedAt", not(nullValue()))
        .body("reports[0]", not(hasKey("payload")));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void aRunWithNoReportsAndNoBaselineIsAnEmptyListAndANullBaseline() {
    given()
        .when()
        .get("/ci/api/runs/" + lonely.id + "/reports")
        .then()
        .statusCode(200)
        .body("runId", equalTo(lonely.id))
        .body("$", hasKey("baseline"))
        .body("baseline", nullValue())
        .body("reports.size()", equalTo(0));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void oneReportCarriesItsPayloadAndIsAddressedUnderItsRun() {
    given()
        .when()
        .get("/ci/api/runs/" + asking.id + "/reports/" + askingTests.id)
        .then()
        .statusCode(200)
        .body("id", equalTo(askingTests.id.toString()))
        .body("kind", equalTo("test-results"))
        .body("highlights[0].text", equalTo("1 test failed"))
        .body("payload.totals.tests", equalTo(10))
        .body("payload.totals.failed", equalTo(1));
    // Another run's report under this run, a malformed id and an unknown run: all "no such thing".
    given().when().get("/ci/api/runs/" + asking.id + "/reports/" + gateCoverage.id).then().statusCode(404);
    given().when().get("/ci/api/runs/" + asking.id + "/reports/not-a-uuid").then().statusCode(404);
    given().when().get("/ci/api/runs/no-such-run/reports/" + askingTests.id).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void theBaselineIsAnEnvelopeAndNoBaselineIsNullNotAnError() {
    given()
        .when()
        .get("/ci/api/runs/" + asking.id + "/baseline")
        .then()
        .statusCode(200)
        .body("baseline.version", equalTo(VERSION))
        .body("baseline.runId", equalTo(gate.id))
        .body("baseline.releaseRequestId", equalTo("rr-base"))
        .body("baseline.tagSha", equalTo(releaseRun.commitSha));
    String none =
        given()
            .when()
            .get("/ci/api/runs/" + lonely.id + "/baseline")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertEquals("{\"baseline\":null}", none);
    given().when().get("/ci/api/runs/no-such-run/baseline").then().statusCode(404);
    given().when().get("/ci/api/runs/no-such-run/reports").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void theBaselinesReportsOfAKindCarryTheirPayloadsAndNoneIsAnEmptyArray() {
    given()
        .when()
        .get("/ci/api/runs/" + asking.id + "/baseline/reports/coverage")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].id", equalTo(gateCoverage.id.toString()))
        .body("[0].kind", equalTo("coverage"))
        .body("[0].payload.total.percent", equalTo(81.4f));
    String none =
        given()
            .when()
            .get("/ci/api/runs/" + lonely.id + "/baseline/reports/coverage")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertEquals("[]", none);
    given()
        .when()
        .get("/ci/api/runs/" + asking.id + "/baseline/reports/entity-diagram")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
    given().when().get("/ci/api/runs/" + asking.id + "/baseline/reports/Not_A_Kind").then().statusCode(400);
    given().when().get("/ci/api/runs/no-such-run/baseline/reports/coverage").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void theGateReportsAreTheGreenQaRunsOfTheRunsOwnRequest() {
    // The publish run asks with its own id and is answered about the QA run that gated rr-base:
    // the gate's coordinates, both its reports, no baseline.
    given()
        .when()
        .get("/ci/api/runs/" + releaseRun.id + "/gate/reports")
        .then()
        .statusCode(200)
        .body("runId", equalTo(gate.id))
        .body("commitSha", equalTo(gate.commitSha))
        .body("releaseRequestId", equalTo("rr-base"))
        .body("$", hasKey("baseline"))
        .body("baseline", nullValue())
        .body("reports.size()", equalTo(2))
        .body("reports.kind", containsInAnyOrder("coverage", "test-results"))
        .body("reports[0]", not(hasKey("payload")));
    given().when().get("/ci/api/runs/no-such-run/gate/reports").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void noGreenQaRunForTheRequestIsANullGateAndNoReports() {
    // rr-x was gated by nothing green: its only QA run is the lonely one, still RUNNING.
    given()
        .when()
        .get("/ci/api/runs/" + lonely.id + "/gate/reports")
        .then()
        .statusCode(200)
        .body("$", hasKey("runId"))
        .body("runId", nullValue())
        .body("commitSha", nullValue())
        .body("releaseRequestId", equalTo("rr-x"))
        .body("baseline", nullValue())
        .body("reports.size()", equalTo(0));
  }

  @Test
  @TestSecurity(user = "ticket-agent", roles = {AGENT})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void aRedQaRunIsNeverTheGate() throws Exception {
    // A request whose only QA run failed has no gate, however many reports that run submitted.
    CiRun red =
        run(repo, CiRunPhase.RELEASE_REQUEST, "release/rr-red", "rr-red", CiRunStatus.FAILED, 5);
    store.submit(red.id, 0, "test-results", submission("{\"totals\":{\"tests\":3}}", null));
    CiRun publish =
        run(repo, CiRunPhase.RELEASE, "2026.1003.60000", "rr-red", CiRunStatus.RUNNING, 6);
    given()
        .when()
        .get("/ci/api/runs/" + publish.id + "/gate/reports")
        .then()
        .statusCode(200)
        .body("runId", nullValue())
        .body("releaseRequestId", equalTo("rr-red"))
        .body("reports.size()", equalTo(0));

    // And a newer red QA run of a request that WAS gated green does not displace the green one.
    run(repo, CiRunPhase.RELEASE_REQUEST, "release/rr-base", "rr-base", CiRunStatus.FAILED, 7);
    given()
        .when()
        .get("/ci/api/runs/" + releaseRun.id + "/gate/reports")
        .then()
        .statusCode(200)
        .body("runId", equalTo(gate.id))
        .body("reports.size()", equalTo(2));
  }

  @Test
  @TestSecurity(user = RUN_SUBJECT, roles = {"qits:ci-run"})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = RUN_SUBJECT)})
  void theRunsOwnTokenReadsItsReportsBackAndAResubmitReplaces() {
    // The CLI reads the baseline and the baseline's same-kind report before it submits, on the same
    // credential it submits with.
    given().when().get("/ci/api/runs/" + asking.id + "/baseline").then().statusCode(200);
    given()
        .when()
        .get("/ci/api/runs/" + asking.id + "/baseline/reports/test-results")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1));

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            "{\"kindVersion\":2,\"highlights\":[],\"baseline\":null,"
                + "\"payload\":{\"totals\":{\"tests\":11}}}")
        .when()
        .put("/ci/api/runs/" + asking.id + "/steps/1/reports/test-results")
        .then()
        .statusCode(204);
    List<CiReport> stored = store.forRun(asking.id);
    assertEquals(1, stored.size(), "replaced, not added");
    assertNotEquals(askingTests.id, stored.get(0).id);
    given()
        .when()
        .get("/ci/api/runs/" + asking.id + "/reports/" + stored.get(0).id)
        .then()
        .statusCode(200)
        .body("kindVersion", equalTo(2))
        .body("baselineRunId", nullValue())
        .body("payload.totals.tests", equalTo(11));
  }

  @Test
  @TestSecurity(user = RUN_SUBJECT, roles = {"qits:ci-run"})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = RUN_SUBJECT)})
  void aBodyOverOneMebibyteIs413() {
    String filler = "x".repeat(CiReportController.MAX_BODY_BYTES);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"kindVersion\":1,\"payload\":{\"filler\":\"" + filler + "\"}}")
        .when()
        .put("/ci/api/runs/" + lonely.id + "/steps/0/reports/coverage")
        .then()
        .statusCode(413);
    assertEquals(List.of(), store.forRun(lonely.id));
  }

  @Test
  @TestSecurity(user = RUN_SUBJECT, roles = {"qits:ci-run"})
  @OidcSecurity(
      claims = {@Claim(key = "aud", value = OWN_AUDIENCE), @Claim(key = "sub", value = RUN_SUBJECT)})
  void elevenHighlightsABadKindAndAMissingPayloadAre400() throws Exception {
    List<CiReportHighlightDto> eleven = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      eleven.add(new CiReportHighlightDto("info", "highlight " + i, null, null, null));
    }
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            objectMapper.writeValueAsString(
                new CiReportSubmission(1, eleven, null, objectMapper.createObjectNode())))
        .when()
        .put("/ci/api/runs/" + lonely.id + "/steps/0/reports/coverage")
        .then()
        .statusCode(400);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"kindVersion\":1,\"payload\":{}}")
        .when()
        .put("/ci/api/runs/" + lonely.id + "/steps/0/reports/Coverage")
        .then()
        .statusCode(400);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"kindVersion\":1}")
        .when()
        .put("/ci/api/runs/" + lonely.id + "/steps/0/reports/coverage")
        .then()
        .statusCode(400);
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("not json")
        .when()
        .put("/ci/api/runs/" + lonely.id + "/steps/0/reports/coverage")
        .then()
        .statusCode(400);
    // An unknown run is 404, before any binding is asked.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"kindVersion\":1,\"payload\":{}}")
        .when()
        .put("/ci/api/runs/no-such-run/steps/0/reports/coverage")
        .then()
        .statusCode(404);
    assertEquals(List.of(), store.forRun(lonely.id));
  }

  private CiReportSubmission submission(String payload, CiReportSubmission.Baseline baseline)
      throws Exception {
    return new CiReportSubmission(
        1,
        List.of(new CiReportHighlightDto("bad", "1 test failed", "failed", 1.0, 1.0)),
        baseline,
        objectMapper.readTree(payload));
  }

  private CiRun run(
      String repoId,
      CiRunPhase phase,
      String branch,
      String requestId,
      CiRunStatus status,
      int minute) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repoId;
    run.branch = branch;
    run.commitSha = String.format("%040x", minute);
    run.releaseRequestId = requestId;
    run.phase = phase;
    run.status = status;
    run.triggerType = CiTriggerType.EVENT;
    run.configPath = ".config/qits/release.yml";
    run.triggerEventId = UUID.randomUUID().toString();
    run.createdAt = Instant.parse("2026-10-01T00:00:00Z").plusSeconds(60L * minute);
    run.startedAt = run.createdAt;
    QuarkusTransaction.requiringNew().run(() -> runs.persist(run));
    seededRuns.add(run.id);
    return run;
  }
}
