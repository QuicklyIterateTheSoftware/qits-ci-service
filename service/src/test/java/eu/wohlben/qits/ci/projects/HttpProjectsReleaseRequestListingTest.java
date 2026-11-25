package eu.wohlben.qits.ci.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiReleaseRequestListing.Listing;
import eu.wohlben.qits.ci.control.CiReleaseRequestListing.Request;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpProjectsReleaseRequestListing} on its own (qits-2604) — plain JUnit against a real
 * server on a real socket, {@link HttpProjectsRepoListingTest}'s shape one door over.
 *
 * <p>The contract under test is the one the parity check leans on: an answer is the requests, every
 * one of them as the door spelled it, and <b>every way the read can fail is {@link
 * Listing#unreachable}, never an empty list</b> — an empty list would say the counterpart has no
 * open request and refuse a fold one of them would have let through. Which states count is not this
 * client's decision, so nothing here filters by state.
 */
public class HttpProjectsReleaseRequestListingTest {

  private static final String REPO_ID = "00000000-0000-4000-8000-000000000002";

  private Vertx vertx;
  private HttpServer server;
  private int port;

  private final AtomicInteger reads = new AtomicInteger();
  private final List<String> uris = Collections.synchronizedList(new ArrayList<>());
  private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

  private volatile int status = 200;
  private volatile String body = "{\"requests\":[]}";

  @BeforeEach
  void startStub() throws Exception {
    vertx = Vertx.vertx();
    server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          reads.incrementAndGet();
          uris.add(req.uri());
          authorizations.add(req.getHeader("Authorization"));
          req.response()
              .setStatusCode(status)
              .putHeader("Content-Type", "application/json")
              .end(body);
        });
    port =
        server
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .actualPort();
  }

  @AfterEach
  void stopStub() {
    server.close();
    vertx.close();
  }

  @Test
  public void theRequestsAreReadWithNoStateAndCarryTheQitsOidcClientsBearer() {
    body =
        "{\"requests\":["
            + "{\"id\":\"r1\",\"qualifiedId\":\"x-rr-2\",\"repoId\":\"" + REPO_ID + "\","
            + "\"state\":\"PENDING\",\"mergedSha\":\"" + "a".repeat(40) + "\",\"sources\":[]},"
            + "{\"id\":\"r0\",\"qualifiedId\":\"x-rr-1\",\"state\":\"FINALIZED\","
            + "\"mergedSha\":null}]}";

    Listing listing = listing().releaseRequests(REPO_ID);

    assertNull(listing.unreachable());
    assertEquals(
        List.of(
            new Request("r1", "x-rr-2", "PENDING", "a".repeat(40)),
            new Request("r0", "x-rr-1", "FINALIZED", null)),
        listing.requests(),
        "every request as answered; which states count is the parity inputs' decision");
    assertEquals(List.of("/projects/api/repositories/" + REPO_ID + "/release-requests"), uris);
    assertEquals(List.of("Bearer machine-token"), authorizations);
  }

  @Test
  public void anEntryWithoutAnIdIsSkippedAndAMissingQualifiedIdLabelsByTheId() {
    body =
        "{\"requests\":[{\"qualifiedId\":\"x-rr-3\",\"state\":\"READY\"},"
            + "{\"id\":\"r4\",\"state\":\"READY\",\"mergedSha\":\"" + "b".repeat(40) + "\"}]}";

    List<Request> requests = listing().releaseRequests(REPO_ID).requests();

    assertEquals(1, requests.size(), requests.toString());
    assertEquals("r4", requests.get(0).label());
  }

  @Test
  public void aTrailingSlashOnTheConfiguredBaseDoesNotDoubleTheSegment() {
    listing("http://127.0.0.1:" + port + "/", "machine-token").releaseRequests(REPO_ID);
    assertEquals(List.of("/projects/api/repositories/" + REPO_ID + "/release-requests"), uris);
  }

  @Test
  public void withNoBearerTheCallFailsClosedOpensNoSocketAndIsUnreachable() {
    Listing listing = listing("http://127.0.0.1:" + port, null).releaseRequests(REPO_ID);
    assertNotNull(listing.unreachable());
    assertEquals(0, reads.get());
  }

  @Test
  public void anUnsetUrlIsNotConfiguredAndOpensNoSocket() {
    HttpProjectsReleaseRequestListing unset = ProjectsClients.releaseRequestListing(null);
    assertFalse(unset.configured(), "unset is the release-only arm, not a broken deployment");
    assertNotNull(unset.releaseRequests(REPO_ID).unreachable());
    assertEquals(0, reads.get());
  }

  @Test
  public void aRepositoryIdThatCouldEscapeTheUrlIsNeverSent() {
    assertNotNull(listing().releaseRequests("../etc").unreachable());
    assertEquals(0, reads.get());
  }

  @Test
  public void aNon2xxIsUnreachable() {
    status = 503;
    Listing listing = listing().releaseRequests(REPO_ID);
    assertTrue(listing.unreachable().contains("503"), listing.unreachable());
    assertTrue(listing.requests().isEmpty());

    status = 404;
    assertNotNull(listing().releaseRequests(REPO_ID).unreachable());
  }

  @Test
  public void anUnreachableProjectsIsUnreachableRatherThanThrowing() throws Exception {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    }
    assertNotNull(
        listing("http://127.0.0.1:" + deadPort, "machine-token")
            .releaseRequests(REPO_ID)
            .unreachable());
  }

  @Test
  public void aBodyThatIsNotJsonOrHasNoRequestsArrayIsUnreachable() {
    body = "not json at all";
    assertNotNull(listing().releaseRequests(REPO_ID).unreachable());

    body = "{\"releaseRequests\":[]}";
    assertNotNull(listing().releaseRequests(REPO_ID).unreachable());
  }

  @Test
  public void anEmptyAnswerIsAnAnswer() {
    Listing listing = listing().releaseRequests(REPO_ID);
    assertNull(listing.unreachable());
    assertTrue(listing.requests().isEmpty());
  }

  @Test
  public void aProjectsUrlThatIsNotHttpIsNotCalledAtAll() {
    assertNotNull(
        listing("file:///tmp/qits-projects", "machine-token").releaseRequests(REPO_ID).unreachable());
    assertEquals(0, reads.get());
  }

  private HttpProjectsReleaseRequestListing listing() {
    return listing("http://127.0.0.1:" + port, "machine-token");
  }

  private static HttpProjectsReleaseRequestListing listing(String projectsUrl, String bearer) {
    return ProjectsClients.releaseRequestListing(projectsUrl, bearer);
  }
}
