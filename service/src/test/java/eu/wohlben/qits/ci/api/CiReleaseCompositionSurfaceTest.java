package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.githost.FakeGitHostRepoListing;
import eu.wohlben.qits.ci.githost.StubGitHost;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /ci/api/repositories/{repoId}/release-composition} at the service boundary: a real
 * branch in a real bare on the stub git host, read over HTTP.
 *
 * <p>What is under test here is the <b>adapter</b> — the route, the body, the mandatory {@code rev},
 * which verdict becomes which status, and the two things the wire contract owes a reader: the
 * guidance prose, and the absence of any boolean saying the two sides agree. The verdicts themselves,
 * including the failures only a fake git host can stage, belong to the {@code ci} module's {@code
 * CiReleaseCompositionTest} — this instance has a live git host and other classes' repositories on
 * it, exactly as {@code CiReleasePhaseSurfaceTest} says of its own 503.
 *
 * <p>Every repository is minted per test method and made a candidate through the git host listing,
 * for that class's reason: the candidate set is shared for the life of a Quarkus instance.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
public class CiReleaseCompositionSurfaceTest {

  private static final String SLOT_PATH = ".config/qits/release.yml";

  private static final String LEGACY_QA_PATH = ".config/qits/ci-event-release-request.yml";

  private static final String LEGACY_RELEASE_PATH = ".config/qits/ci-event-release.yml";

  /** A branch tip, not a tag: this read is about a file somebody is still editing. */
  private static final String REV = "main";

  @Inject FakeGitHostRepoListing gitHostListing;

  @BeforeEach
  void resetFakes() {
    gitHostListing.set();
  }

  @Test
  public void aCandidateIsComposedAgainstTheHandWrittenPairTheRefCommits() throws Exception {
    String repoId =
        seedRepository(
            Map.of(LEGACY_QA_PATH, committedQa(), LEGACY_RELEASE_PATH, committedRelease()));

    String body =
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(candidate("""
                release-request:
                  - image: qits/build-images/maven-base:latest
                    script: ./mvnw -B -ntp verify
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build ...
                artifacts:
                  - { type: docker, name: qits/qits-target, sbom: out/sbom.json }
                """))
            .when()
            .post(url(repoId))
            .then()
            .statusCode(200)
            .body("repositoryId", equalTo(repoId))
            .body("rev", equalTo(REV))
            .body("slotFileSource", equalTo("CANDIDATE"))
            .body("slotFilePath", equalTo(SLOT_PATH))
            .body("releaseRequestPhase.phase", equalTo("release-request"))
            .body("releaseRequestPhase.event", equalTo("ReleaseRequestChanged"))
            .body("releasePhase.event", equalTo("SCMRelease"))
            // Both sides present, both summarised by the one parser — which is what makes them
            // comparable field by field rather than as two blobs of text.
            .body("releaseRequestPhase.composed.document", notNullValue())
            .body("releaseRequestPhase.committed.document", notNullValue())
            .body("releaseRequestPhase.composed.summary.checkout.branchPath", equalTo("backingBranch"))
            .body("releaseRequestPhase.committed.summary.checkout.shaPath", equalTo("mergedSha"))
            .body("releasePhase.composed.summary.steps[0].build", equalTo(true))
            .body("releasePhase.composed.summary.artifacts[0].sbomPath", equalTo("out/sbom.json"))
            // A trigger file's artifacts: grammar has no sbom: key and never did.
            .body("releasePhase.committed.summary.artifacts[0].sbomPath", equalTo(""))
            .extract()
            .asString();

    assertGuidance(body);
    assertNoMatchesBoolean(body);
  }

  @Test
  public void aRepositoryThatAlreadyCommitsASlotFileIgnoresTheCandidate() throws Exception {
    String repoId =
        seedRepository(
            Map.of(
                SLOT_PATH,
                """
                release:
                  - image: committed-image:1
                    script: ./committed.sh
                """));

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            candidate(
                """
                release:
                  - image: candidate-image:1
                    script: ./candidate.sh
                """))
        .when()
        .post(url(repoId))
        .then()
        .statusCode(200)
        .body("slotFileSource", equalTo("COMMITTED"))
        .body("releasePhase.composed.summary.steps[0].image", equalTo("committed-image:1"))
        // Nothing hand-written at this ref, so the other side of the comparison is an absence with
        // its reason rather than an empty document.
        .body("releasePhase.committed.document", nullValue())
        .body("releasePhase.committed.detail", notNullValue());
  }

  @Test
  public void anUnparseableCandidateIsA200CarryingItsDetail() throws Exception {
    // The bytes are the caller's own draft and the parser's message is what they need to read. A 503
    // would hide it behind "retry", which is a statement about qits-ci rather than about the file.
    String repoId = seedRepository(Map.of(LEGACY_QA_PATH, committedQa()));

    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(candidate("archetpye: java-service\n"))
        .when()
        .post(url(repoId))
        .then()
        .statusCode(200)
        .body("slotFileSource", equalTo("CANDIDATE"))
        .body("releaseRequestPhase.composed.document", nullValue())
        .body("releaseRequestPhase.composed.summary", nullValue())
        .body(
            "releaseRequestPhase.composed.detail",
            org.hamcrest.Matchers.containsString("not a usable release slot file"))
        .body("releaseRequestPhase.committed.document", notNullValue());
  }

  @Test
  public void noSlotFileAndNoCandidateIs400() throws Exception {
    // An empty request, not an empty answer: two absences would read as "this repository composes
    // nothing", which is a statement about the repository rather than about the ask.
    String repoId = seedRepository(Map.of(LEGACY_QA_PATH, committedQa()));

    assertMessageEnvelope(
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"candidateSlotFile\":\"  \"}")
            .when()
            .post(url(repoId))
            .then()
            .statusCode(400)
            .extract()
            .path("message"));
  }

  @Test
  public void aRepositoryTheCatalogueDoesNotHoldIs503() {
    assertMessageEnvelope(
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(candidate("release:\n  - image: alpine:3\n    script: ./publish.sh\n"))
            .when()
            .post(url("nobody-" + UUID.randomUUID()))
            .then()
            .statusCode(503)
            .extract()
            .path("message"));
  }

  @Test
  public void aMissingOrBlankRevIs400WithTheMessageEnvelope() throws Exception {
    String repoId = seedRepository(Map.of(LEGACY_QA_PATH, committedQa()));
    String candidate = candidate("release:\n  - image: alpine:3\n    script: ./publish.sh\n");

    for (String url :
        new String[] {
          "/ci/api/repositories/" + repoId + "/release-composition",
          "/ci/api/repositories/" + repoId + "/release-composition?rev=",
          "/ci/api/repositories/" + repoId + "/release-composition?rev=%20"
        }) {
      assertMessageEnvelope(
          given()
              .contentType(MediaType.APPLICATION_JSON)
              .body(candidate)
              .when()
              .post(url)
              .then()
              .statusCode(400)
              .extract()
              .path("message"));
    }
  }

  // --- the two things the wire contract owes a reader --------------------------------------------

  private static void assertGuidance(String body) {
    assertTrue(
        body.contains("not a pass/fail gate"),
        "the answer has to say what it is worth, in the answer: " + body);
    assertTrue(
        body.contains("NEVER byte-equal"),
        "and why a text comparison would mean nothing: " + body);
    assertTrue(body.contains("decided at main"), "and the rule about when a recipe first runs");
  }

  /**
   * No boolean anywhere saying the two sides agree — the one field a reader would most like and the
   * one that cannot be answered, since a composed document is never byte-equal to a hand-written one.
   */
  private static void assertNoMatchesBoolean(String body) {
    assertFalse(body.contains("\"matches\""), body);
    assertFalse(body.contains("\"equal\""), body);
    assertFalse(body.contains("\"identical\""), body);
  }

  private static void assertMessageEnvelope(String message) {
    assertTrue(
        message != null && !message.isBlank(), "CiExceptionMapper's envelope, not a stack: " + message);
  }

  private static String url(String repoId) {
    return "/ci/api/repositories/" + repoId + "/release-composition?rev=" + REV;
  }

  /** The request body, with the candidate document as a JSON string. */
  private static String candidate(String slotFile) {
    String escaped =
        slotFile.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    return "{\"candidateSlotFile\":\"" + escaped + "\"}";
  }

  private static String committedQa() {
    return """
        event: ReleaseRequestChanged
        when:
          - repoName: { exact: qits-target }
        checkout:
          branch: backingBranch
          sha: mergedSha
        steps:
          - image: qits/build-images/maven-base:latest
            script: ./mvnw -B -ntp verify
        """;
  }

  private static String committedRelease() {
    return """
        event: SCMRelease
        when:
          - repository: { exact: qits-target }
        checkout:
          branch: version
          sha: commitSha
          optional: true
        artifacts:
          - { type: docker, name: qits/qits-target }
        steps:
          - image: qits/build-images/ci-base:latest
            docker: true
            script: docker build . && docker push qits/qits-target
        """;
  }

  // --- the fixture -----------------------------------------------------------------------------

  /**
   * A bare on the stub git host carrying whatever files a case wants at {@code main}, and listed —
   * because a repository the catalogue does not hold is the 503 case rather than any of these.
   */
  private String seedRepository(Map<String, String> files) throws Exception {
    String repoId = "compose-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Path seed = Files.createTempDirectory("ci-release-composition-seed");
    git(seed, "init", "-q", "-b", "main");
    write(seed, "README.md", "seeded\n");
    for (Map.Entry<String, String> file : files.entrySet()) {
      write(seed, file.getKey(), file.getValue());
    }
    git(seed, "add", ".");
    git(seed, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", "release config");

    Path origin = StubGitHost.ROOT.resolve("git").resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    gitHostListing.set(repoId);
    return repoId;
  }

  private static void write(Path root, String path, String content) throws Exception {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException("git " + String.join(" ", args) + " failed:\n" + out);
    }
    return out;
  }
}
