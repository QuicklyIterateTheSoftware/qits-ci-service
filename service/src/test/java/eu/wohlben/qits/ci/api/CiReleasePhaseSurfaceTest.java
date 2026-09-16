package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.githost.FakeGitHostRepoListing;
import eu.wohlben.qits.ci.githost.StubGitHost;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /ci/api/repositories/{repoId}/release-phase} at the service boundary: a real tag in a
 * real bare on the stub git host, read over HTTP.
 *
 * <p>What is under test here is the <b>adapter</b> — the route, the mandatory {@code rev}, and which
 * verdict becomes which status — and nothing beneath it. The verdicts themselves, including the two
 * failures that can only be staged by a fake git host (an unreachable read, an unreadable
 * archetype), belong to the {@code ci} module's {@code CiReleasePhaseTest}: this instance has a live
 * git host and other classes' repositories on it, exactly as {@code CiManualTriggerTest} says of its
 * own 503.
 *
 * <p>Every repository is minted per test method and made a candidate through the git host listing,
 * for that class's reason: the candidate set is shared for the life of a Quarkus instance.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
public class CiReleasePhaseSurfaceTest {

  private static final String SLOT_PATH = ".config/qits/release.yml";

  /** What the caller really sends, and a slashy rev is what makes the encoding worth asserting. */
  private static final String VERSION = "2026.916.114057";

  private static final String REV = "refs/tags/" + VERSION;

  @Inject FakeGitHostRepoListing gitHostListing;

  @BeforeEach
  void resetFakes() {
    gitHostListing.set();
  }

  @Test
  public void aRepositoryDeclaringItsOwnReleaseSlotIsDeclared() throws Exception {
    String repoId =
        seedTaggedRepository(
            """
            release-request:
              - image: alpine:3
                script: ./mvnw verify
            release:
              - image: alpine:3
                script: ./publish.sh
            """);

    given()
        .when()
        .get(releasePhase(repoId))
        .then()
        .statusCode(200)
        .body("repositoryId", equalTo(repoId))
        .body("rev", equalTo(REV))
        .body("declared", equalTo(true));
  }

  @Test
  public void aRepositoryWithNoReleaseSlotIsNotDeclared() throws Exception {
    // The SPA's shape, spelled without an archetype so the case is about the composition rather than
    // about the wrapper: no release half, so no release run, so no PUBLISH gate to raise.
    String repoId =
        seedTaggedRepository(
            """
            release-request:
              - image: alpine:3
                script: npm ci && npm run build
            """);

    String detail =
        given()
            .when()
            .get(releasePhase(repoId))
            .then()
            .statusCode(200)
            .body("declared", equalTo(false))
            .extract()
            .path("detail");
    assertTrue(detail != null && !detail.isBlank(), "the reason travels with the answer");
  }

  @Test
  public void noSlotFileAtTheTagIsNotDeclared() throws Exception {
    String repoId = seedTaggedRepository(null);

    given()
        .when()
        .get(releasePhase(repoId))
        .then()
        .statusCode(200)
        .body("declared", equalTo(false));
  }

  @Test
  public void aRepositoryTheCatalogueDoesNotHoldIs503AndNeverFalse() {
    // The whole point of the third answer at the HTTP boundary: a question that could not be asked
    // must come back as "retry", because a false derived from a failure publishes a release whose
    // pipeline nothing gated.
    String message =
        given()
            .when()
            .get(releasePhase("nobody-" + UUID.randomUUID()))
            .then()
            .statusCode(503)
            .extract()
            .path("message");
    assertTrue(
        message != null && !message.isBlank(), "CiExceptionMapper's envelope, not a stack: " + message);
  }

  @Test
  public void aMissingOrBlankRevIs400WithTheMessageEnvelope() throws Exception {
    String repoId = seedTaggedRepository("release:\n  - image: alpine:3\n    script: ./publish.sh\n");

    assertBadRequest("/ci/api/repositories/" + repoId + "/release-phase");
    assertBadRequest("/ci/api/repositories/" + repoId + "/release-phase?rev=");
    assertBadRequest("/ci/api/repositories/" + repoId + "/release-phase?rev=%20");
  }

  private static void assertBadRequest(String url) {
    String message =
        given().when().get(url).then().statusCode(400).extract().path("message");
    assertTrue(message != null && !message.isBlank(), "CiExceptionMapper's envelope, not a stack");
  }

  private String releasePhase(String repoId) {
    return "/ci/api/repositories/" + repoId + "/release-phase?rev=" + REV;
  }

  // --- the fixture -----------------------------------------------------------------------------

  /**
   * A bare on the stub git host carrying one tag, with {@code release.yml} at it or without — and
   * listed, because a repository the catalogue does not hold is the 503 case rather than this one.
   */
  private String seedTaggedRepository(String slotFile) throws Exception {
    String repoId = "phase-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Path seed = Files.createTempDirectory("ci-release-phase-seed");
    git(seed, "init", "-q", "-b", "main");
    write(seed, "README.md", "seeded\n");
    if (slotFile != null) {
      write(seed, SLOT_PATH, slotFile);
    }
    git(seed, "add", ".");
    git(seed, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", "release slots");
    git(seed, "-c", "user.email=ci@test", "-c", "user.name=ci", "tag", VERSION);

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
