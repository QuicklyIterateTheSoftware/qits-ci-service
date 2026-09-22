package eu.wohlben.qits.ci.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiStepImagePins.Pin;
import eu.wohlben.qits.ci.control.CiStepImagePins.Status;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpImagePins} on its own — plain JUnit against a real registry on a real socket, no
 * Quarkus, for {@code HttpGitHostRepoListingTest}'s reason: everything under test is one
 * hand-rolled client's url shape, its reading of one header and its failure arms.
 *
 * <p><b>The wire under test is the OCI distribution spec's</b>, which qits-platform-artifacts
 * serves at the literal {@code /v2} of its own root: {@code HEAD /v2/<name>/manifests/<tag>}
 * answering {@code Docker-Content-Digest}. The stub here answers exactly that, so the cases are
 * about what this client does with each answer rather than about what a registry is.
 *
 * <p>Every failing case answers {@link Status#UNRESOLVED} and never the reference it was given.
 * That is the assertion the whole feature rests on: a client that fell back to the tag on a blip
 * would have re-invented "whatever {@code :latest} is now" behind a method named {@code pin}.
 */
public class HttpImagePinsTest {

  private static final String REGISTRY = "qits-artifacts.test:8080";
  private static final String DIGEST = "sha256:" + "f".repeat(64);

  private Vertx vertx;
  private HttpServer server;
  private int port;

  private final AtomicInteger reads = new AtomicInteger();
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
  private final List<String> accepts = Collections.synchronizedList(new ArrayList<>());

  private volatile int status = 200;
  private volatile String digest = DIGEST;

  @BeforeEach
  void startStub() throws Exception {
    vertx = Vertx.vertx();
    server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          reads.incrementAndGet();
          paths.add(req.path());
          accepts.add(req.getHeader("Accept"));
          if (digest != null) {
            req.response().putHeader("Docker-Content-Digest", digest);
          }
          req.response().setStatusCode(status).end();
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
  public void aPlatformTagIsAskedAtTheV2ManifestRouteAndPinnedToWhatTheRegistryAnswers() {
    Pin pin = pins().pin(REGISTRY + "/qits/build-images/ci-base:latest");

    assertEquals(Status.PINNED, pin.status());
    assertEquals(
        REGISTRY + "/qits/build-images/ci-base@" + DIGEST,
        pin.reference(),
        "pinned at the host a PULL names — the digest is content-addressed, so resolving through"
            + " the in-network origin and pulling through the daemon's view is one image");
    assertEquals(List.of("/v2/qits/build-images/ci-base/manifests/latest"), paths);
    assertTrue(
        accepts.get(0).contains("application/vnd.oci.image.index.v1+json"),
        "an index is asked for first, or a multi-arch image pins one architecture's child: "
            + accepts);
  }

  @Test
  public void aReferenceWithNoTagIsAskedAboutLatest() {
    // What docker resolves a bare name to, and therefore what the run would really have pulled.
    pins().pin(REGISTRY + "/qits/build-images/ci-base");

    assertEquals(List.of("/v2/qits/build-images/ci-base/manifests/latest"), paths);
  }

  @Test
  public void anImageThisPlatformDoesNotPublishIsForeignAndNoSocketIsOpened() {
    assertEquals(Status.FOREIGN, pins().pin("alpine:3").status());
    assertEquals(Status.FOREIGN, pins().pin("docker:28-dind").status());
    assertEquals(Status.FOREIGN, pins().pin("ghcr.io/somebody/tool:1").status());
    assertEquals(
        0,
        reads.get(),
        "qits-ci holds no credential for another store and no address to it — asking would be a"
            + " second registry's availability added to every accept");
  }

  @Test
  public void aReferenceThatIsAlreadyADigestIsNeverReResolved() {
    Pin pin = pins().pin(REGISTRY + "/qits/build-images/ci-base@" + DIGEST);

    assertEquals(Status.ALREADY_PINNED, pin.status());
    assertEquals(REGISTRY + "/qits/build-images/ci-base@" + DIGEST, pin.reference());
    assertEquals(0, reads.get(), "re-resolving a deliberate pin is the defect wearing a fix's name");
  }

  @Test
  public void aRegistryThatHoldsNoSuchTagIsUnresolvedRatherThanTheTag() {
    status = 404;
    digest = null;

    Pin pin = pins().pin(REGISTRY + "/qits/build-images/ci-base:latest");
    assertEquals(Status.UNRESOLVED, pin.status());
    assertTrue(pin.detail().contains("404"), pin.detail());
  }

  @Test
  public void aTwoHundredWithNoUsableDigestIsUnresolved() {
    digest = "not-a-digest";

    assertEquals(
        Status.UNRESOLVED,
        pins().pin(REGISTRY + "/qits/build-images/ci-base:latest").status(),
        "a 200 that states no digest has stated nothing, and the tag is not an answer to it");
  }

  @Test
  public void anUnreachableRegistryIsUnresolvedRatherThanThrowing() throws Exception {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    } // closed immediately — nothing listens here now, so the connection is refused

    Pin pin =
        pins("http://127.0.0.1:" + deadPort).pin(REGISTRY + "/qits/build-images/ci-base:latest");
    assertEquals(
        Status.UNRESOLVED,
        pin.status(),
        "never throws: the caller is a trigger worker, and an infrastructure blip is an answer it"
            + " acts on by leaving the event owed");
  }

  @Test
  public void aDeploymentWithNoArtifactsOriginCannotAskAndSaysSo() {
    HttpImagePins pins = new HttpImagePins();
    pins.artifactsRegistryHost = REGISTRY;
    pins.buildkitRegistryHost = "";
    pins.artifactsUrl = Optional.empty();
    pins.artifactsMavenRegistryUrl = "";

    Pin pin = pins.pin(REGISTRY + "/qits/build-images/ci-base:latest");
    assertEquals(
        Status.UNRESOLVED,
        pin.status(),
        "OURS AND UNASKABLE is not FOREIGN: a question that could not be put must never read as"
            + " one that was answered");
  }

  @Test
  public void theBuildersSpellingOfTheSameRegistryIsAlsoOurs() {
    HttpImagePins pins = pins();
    pins.artifactsRegistryHost = "somewhere-else:8080";
    pins.buildkitRegistryHost = REGISTRY;

    assertEquals(
        Status.PINNED,
        pins.pin(REGISTRY + "/qits/build-images/ci-base:latest").status(),
        "one registry, two network positions — a recipe naming either is naming this store");
  }

  @Test
  public void anExplicitArtifactsUrlWinsOverTheDerivedOne() {
    HttpImagePins pins = pins("http://127.0.0.1:1");
    pins.artifactsUrl = Optional.of("http://127.0.0.1:" + port + "/");

    assertEquals(Status.PINNED, pins.pin(REGISTRY + "/qits/build-images/ci-base:latest").status());
    assertEquals(
        List.of("/v2/qits/build-images/ci-base/manifests/latest"),
        paths,
        "a trailing slash on the configured origin does not double the segment");
  }

  // --- fixture ---------------------------------------------------------------------------------

  private HttpImagePins pins() {
    return pins("http://127.0.0.1:" + port);
  }

  /**
   * The client wired by hand: the derivation under test is the maven root's origin, which is what a
   * live deployment sets and what {@code CiDaemonLauncher.resolvedArtifactsUrl} reads too.
   */
  private HttpImagePins pins(String origin) {
    HttpImagePins pins = new HttpImagePins();
    pins.artifactsRegistryHost = REGISTRY;
    pins.buildkitRegistryHost = "";
    pins.artifactsUrl = Optional.empty();
    pins.artifactsMavenRegistryUrl = origin + "/artifacts/maven/maven";
    return pins;
  }
}
