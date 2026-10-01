package eu.wohlben.qits.ci.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiArtifact;
import eu.wohlben.qits.ci.control.CiArtifactPresence.Probe;
import eu.wohlben.qits.ci.control.CiArtifactPresence.Verdict;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpArtifactPresence} on its own — plain JUnit against a real store on a real socket, no
 * Quarkus, for {@link HttpImagePinsTest}'s reason: everything under test is one hand-rolled client's
 * url shape and its reading of each answer. The three-attempt retry is the join's and is asserted in
 * {@code ReleaseJoinTest}.
 */
public class HttpArtifactPresenceTest {

  private static final String VERSION = "2026.1001.120000";

  private Vertx vertx;
  private HttpServer server;
  private int port;

  /** Raw request uris, so an encoded scope is visible as sent. */
  private final List<String> uris = Collections.synchronizedList(new ArrayList<>());

  private volatile int status = 200;
  private volatile String body = "";

  @BeforeEach
  void startStub() throws Exception {
    vertx = Vertx.vertx();
    server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          uris.add(req.uri());
          req.response().setStatusCode(status).end(body);
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
  public void aMavenVersionIsAskedAtItsPomUnderTheConfiguredMavenRoot() {
    Probe probe = presence().probe(CiArtifact.Type.MAVEN, "eu.wohlben.qits:qits-thing", VERSION);

    assertEquals(Verdict.PRESENT, probe.verdict(), probe.detail());
    assertEquals(
        List.of(
            "/artifacts/maven/maven/eu/wohlben/qits/qits-thing/"
                + VERSION
                + "/qits-thing-"
                + VERSION
                + ".pom"),
        uris);
  }

  @Test
  public void aMavenFourOhFourIsAbsent() {
    status = 404;
    assertEquals(
        Verdict.ABSENT,
        presence().probe(CiArtifact.Type.MAVEN, "eu.wohlben.qits:qits-thing", VERSION).verdict());
  }

  @Test
  public void aServerErrorIsInconclusiveAndNamesTheStatus() {
    status = 503;
    Probe probe = presence().probe(CiArtifact.Type.MAVEN, "eu.wohlben.qits:qits-thing", VERSION);
    assertEquals(Verdict.INCONCLUSIVE, probe.verdict());
    assertTrue(probe.detail().contains("503"), probe.detail());
  }

  @Test
  public void anNpmPackumentListingTheVersionIsPresentAndAScopeIsOneEncodedSegment() {
    body = "{\"name\":\"@qits/ui\",\"versions\":{\"" + VERSION + "\":{},\"1.0.0\":{}}}";

    Probe probe = presence().probe(CiArtifact.Type.NPM, "@qits/ui", VERSION);

    assertEquals(Verdict.PRESENT, probe.verdict(), probe.detail());
    assertEquals(List.of("/artifacts/npm/npm/@qits%2fui"), uris);
  }

  @Test
  public void anNpmPackumentWithoutTheVersionIsAbsent() {
    body = "{\"name\":\"@qits/ui\",\"versions\":{\"1.0.0\":{}}}";
    assertEquals(Verdict.ABSENT, presence().probe(CiArtifact.Type.NPM, "@qits/ui", VERSION).verdict());
  }

  @Test
  public void anNpmFourOhFourIsAbsentAndAnUnreadablePackumentIsInconclusive() {
    status = 404;
    assertEquals(Verdict.ABSENT, presence().probe(CiArtifact.Type.NPM, "@qits/ui", VERSION).verdict());
    status = 200;
    body = "<html>not a packument</html>";
    assertEquals(
        Verdict.INCONCLUSIVE, presence().probe(CiArtifact.Type.NPM, "@qits/ui", VERSION).verdict());
  }

  @Test
  public void anUnreachableStoreIsInconclusive() throws Exception {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    }
    HttpArtifactPresence presence = new HttpArtifactPresence();
    presence.artifactsUrl = Optional.empty();
    presence.artifactsMavenRegistryUrl = "http://127.0.0.1:" + closed + "/artifacts/maven/maven";

    assertEquals(
        Verdict.INCONCLUSIVE,
        presence.probe(CiArtifact.Type.MAVEN, "eu.wohlben.qits:x", VERSION).verdict());
    assertEquals(Verdict.INCONCLUSIVE, presence.probe(CiArtifact.Type.NPM, "x", VERSION).verdict());
  }

  @Test
  public void anExplicitOriginWinsAndBothRootsHangOffIt() {
    HttpArtifactPresence presence = new HttpArtifactPresence();
    presence.artifactsUrl = Optional.of("http://127.0.0.1:" + port + "/");
    presence.artifactsMavenRegistryUrl = "http://elsewhere.invalid:1/artifacts/maven/maven";
    body = "{\"versions\":{\"" + VERSION + "\":{}}}";

    assertEquals(
        Verdict.PRESENT, presence.probe(CiArtifact.Type.MAVEN, "a.b:c", VERSION).verdict());
    assertEquals(Verdict.PRESENT, presence.probe(CiArtifact.Type.NPM, "c", VERSION).verdict());
    assertEquals(
        List.of(
            "/artifacts/maven/maven/a/b/c/" + VERSION + "/c-" + VERSION + ".pom",
            "/artifacts/npm/npm/c"),
        uris);
  }

  @Test
  public void aTypeTheStoreIsNotAskedAboutAndAMalformedCoordinateAreInconclusiveWithoutASocket() {
    assertEquals(
        Verdict.INCONCLUSIVE, presence().probe(CiArtifact.Type.DOCKER, "qits/x", VERSION).verdict());
    assertEquals(
        Verdict.INCONCLUSIVE,
        presence().probe(CiArtifact.Type.MAVEN, "no-colon-here", VERSION).verdict());
    assertEquals(List.of(), uris);
  }

  private HttpArtifactPresence presence() {
    HttpArtifactPresence presence = new HttpArtifactPresence();
    presence.artifactsUrl = Optional.empty();
    presence.artifactsMavenRegistryUrl = "http://127.0.0.1:" + port + "/artifacts/maven/maven";
    return presence;
  }
}
