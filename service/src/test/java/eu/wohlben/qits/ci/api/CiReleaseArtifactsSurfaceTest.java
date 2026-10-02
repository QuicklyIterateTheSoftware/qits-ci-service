package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.ci.entity.CiReleaseAnnouncement;
import eu.wohlben.qits.ci.persistence.CiReleaseAnnouncementRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /ci/api/repositories/{repoId}/releases/{version}/artifacts} at the service boundary
 * (qits-640): the release join's decision record, read over HTTP.
 *
 * <p>The rows are seeded straight into {@code ci_release_announcement} rather than earned through a
 * run, because what is under test is the read — the route, both spellings of the repository, the
 * wire words, and above all a row settled <b>before V30</b>, which no run of this qits-ci can write
 * any more. How the join decides is {@code ReleaseJoinTest}'s, in the {@code ci} module.
 */
@QuarkusTest
public class CiReleaseArtifactsSurfaceTest {

  private static final String VERSION = "2026.1002.1";

  @Inject CiReleaseAnnouncementRepository announcements;

  private String repoId;
  private String repoName;

  @BeforeEach
  void mintRepository() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    repoId = "storage-" + suffix;
    repoName = "qits-released-" + suffix;
  }

  @Test
  public void everyDecisionReadsAsItsWireWordIncludingRowsSettledBeforeV30() {
    Instant run = Instant.parse("2026-10-02T10:00:00Z");
    // One run's rows. The first three were settled before V30 existed: announced_at and V29's
    // skip_reason, no decision — they must read from the skip reason.
    seed("run-1", run, 0, "docker", "qits/qits-thing", null, null, null, true, null);
    seed("run-1", run, 1, "maven", "eu.wohlben.qits:gone", null, null, "ABSENT", true, null);
    seed("run-1", run, 2, "npm", "@qits/blip", null, null, "UNVERIFIED", true, null);
    // ...and two written by a decision-recording join: one decided, one still owed.
    seed("run-1", run, 3, "docs", "@apidocs/qits-thing", null, "PUBLISHED", null, true, null);
    seed("run-1", run, 4, "maven", "eu.wohlben.qits:gm", "if-changed", "UNCHANGED", null, true,
        "2026.1001.162201");
    seed("run-1", run, 5, "npm", "@qits/owed", null, null, null, false, null);

    given()
        .when()
        .get(artifacts(repoName, VERSION))
        .then()
        .statusCode(200)
        .body("repository", equalTo(repoName))
        .body("version", equalTo(VERSION))
        .body("artifacts", hasSize(6))
        .body("artifacts[0].type", equalTo("docker"))
        .body("artifacts[0].name", equalTo("qits/qits-thing"))
        .body("artifacts[0].publish", equalTo("always"))
        .body("artifacts[0].decision", equalTo("published"))
        .body("artifacts[0].unchangedSince", nullValue())
        .body("artifacts[0].runId", equalTo("run-1-" + repoId))
        .body("artifacts[0].decidedAt", equalTo("2026-10-02T10:05:00Z"))
        .body("artifacts[1].decision", equalTo("absent"))
        .body("artifacts[2].decision", equalTo("unverified"))
        .body("artifacts[3].decision", equalTo("published"))
        .body("artifacts[4].publish", equalTo("if-changed"))
        .body("artifacts[4].decision", equalTo("unchanged"))
        .body("artifacts[4].unchangedSince", equalTo("2026.1001.162201"))
        .body("artifacts[5].decision", equalTo("pending"))
        .body("artifacts[5].decidedAt", nullValue());
  }

  @Test
  public void theStorageIdAnswersTooAndTheNewestRunSpeaksPerArtifact() {
    Instant older = Instant.parse("2026-10-02T10:00:00Z");
    seed("run-old", older, 0, "docker", "qits/qits-thing", null, null, null, true, null);
    seed("run-old", older, 1, "maven", "eu.wohlben.qits:lib", null, null, "ABSENT", true, null);
    seed("run-new", older.plusSeconds(600), 0, "maven", "eu.wohlben.qits:lib", null, null, null,
        false, null);

    given()
        .when()
        .get(artifacts(repoId, VERSION))
        .then()
        .statusCode(200)
        .body("repository", equalTo(repoId))
        .body("artifacts", hasSize(2))
        .body("artifacts[0].name", equalTo("eu.wohlben.qits:lib"))
        .body("artifacts[0].runId", equalTo("run-new-" + repoId))
        .body("artifacts[0].decision", equalTo("pending"))
        .body("artifacts[1].name", equalTo("qits/qits-thing"))
        .body("artifacts[1].runId", equalTo("run-old-" + repoId));
  }

  @Test
  public void aVersionNothingOwedIsAnEmptyAnswerNeverA404() {
    given()
        .when()
        .get(artifacts(repoName, "2026.1.1"))
        .then()
        .statusCode(200)
        .body("artifacts", empty());
  }

  @Test
  public void aBlankVersionIs400() {
    // Sent pre-encoded, so the server decodes the segment to a single space.
    given()
        .urlEncodingEnabled(false)
        .when()
        .get(artifacts(repoName, "%20"))
        .then()
        .statusCode(400);
  }

  private static String artifacts(String repo, String version) {
    return "/ci/api/repositories/" + repo + "/releases/" + version + "/artifacts";
  }

  /**
   * One row. {@code settled} stamps {@code announced_at} five minutes after the run, otherwise the
   * row is owed. The run id is suffixed with the minted repository, because {@code (run_id,
   * package_type, package_name)} is unique across the suite's shared database.
   */
  private void seed(
      String runId,
      Instant createdAt,
      int index,
      String type,
      String name,
      String publish,
      String decision,
      String skipReason,
      boolean settled,
      String unchangedSince) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiReleaseAnnouncement row = new CiReleaseAnnouncement();
              row.id = UUID.randomUUID().toString();
              row.runId = runId + "-" + repoId;
              row.repoId = repoId;
              row.repoName = repoName;
              row.version = VERSION;
              row.packageType = type;
              row.packageName = name;
              row.artifactIndex = index;
              row.finishedAt = createdAt;
              row.createdAt = createdAt;
              row.publish = publish;
              row.decision = decision;
              row.skipReason = skipReason;
              row.unchangedSince = unchangedSince;
              row.announcedAt = settled ? createdAt.plusSeconds(300) : null;
              announcements.persist(row);
            });
  }
}
