package eu.wohlben.qits.ci.stories.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.api.CiPackagedSurfaceIT;
import eu.wohlben.qits.ci.runnerhost.CiRunnerRegistry;
import eu.wohlben.qits.ci.runnerhost.CiRunnerSocket;
import eu.wohlben.qits.ci.runnerhost.FakeCiRunner;
import eu.wohlben.qits.ci.runnerhost.RunnerAddresses;
import eu.wohlben.qits.ci.testdb.EmbeddedPg;
import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.Nothing;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import eu.wohlben.qits.cirunner.protocol.Released;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import eu.wohlben.qits.cirunner.protocol.Take;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import io.restassured.RestAssured;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * <b>The runner a story's run executes on</b> — and the tap for its plane. Since qits-506 qits-ci
 * starts no container and asks no orchestrator for one: every run is reserved by a connected runner
 * over {@code /ci/runners/socket}, and that runner's host starts the step's container ({@code docker
 * run}) when qits-ci sends it a {@code Launch}, and removes it ({@code docker rm}) on a {@code Reap}.
 * This plays that runner, so the workload spec a story's daemon reads its credentials out of is the
 * one qits-ci really sent in a {@code Launch} — the job {@code MockContainers} did for the
 * orchestrator this replaced.
 *
 * <h2>A real client of the real socket</h2>
 *
 * <p>{@link FakeCiRunner} is a real Vert.x WebSocket framing the real protocol records exactly as the
 * {@code ci-runner} binary does; here it dials with a real machine bearer, minted by {@link MockIdp}
 * against the JWKS the launched process fetched — {@code aud=qits-platform}, the runner role, and a
 * {@code sub} naming the client a registered runner row carries, which is how the socket admits it.
 *
 * <p><b>The registration is seeded, and that is the one shortcut.</b> The register door commissions
 * an idp client for the runner, which is qits-idp's story and not a door this launched process can be
 * walked through against the stand-in idp. So {@link #register} writes the row the door would have
 * written — its name, its slot, its client id — straight into the launched process's database, once
 * per JVM. Everything after it is the socket's own.
 *
 * <p><b>It never self-updates.</b> Its {@code Hello} carries the {@code qits.ci.runner.self-update=
 * false} label a deployer-managed runner does (qits-443), so no {@code Upgrade} can intervene
 * whatever version this build pins.
 *
 * <h2>The tap</h2>
 *
 * <p>{@code StoryDaemon}'s: {@link NetworkCapture#observe} at the call sites, synchronously on the
 * story thread. The dial is a {@code socket} edge from the runner; each frame is an {@code event}
 * edge in the direction it travelled, labelled by the protocol's own type name.
 */
public final class StoryRunner implements AutoCloseable {

  /** How the diagram names this plane's initiator. */
  public static final String ACTOR = "a runner";

  /** The seeded row's name. */
  public static final String NAME = "story-runner";

  /** The client its bearer names, and its row carries. */
  static final String CLIENT_ID = "story-runner-client";

  private static final UUID ID = UUID.fromString("5a0e1b7e-7e57-4000-8000-000000000506");

  private static final Duration SOON = Duration.ofSeconds(30);

  private static boolean registered;

  /** One step's container, as a {@code Launch} asked for it. */
  public record Launch(
      String runId, int stepIndex, String containerName, Map<String, String> environment) {}

  private final FakeCiRunner socket;

  private StoryRunner(FakeCiRunner socket) {
    this.socket = socket;
  }

  /**
   * Seeds the runner's row in the launched process's database, once — see the class javadoc. One
   * slot, INTERNAL, docker-capable, not quarantined.
   */
  public static synchronized void register() throws Exception {
    if (registered) {
      return;
    }
    String url = System.getProperty(CiPackagedSurfaceIT.PackagedUnderTarget.CI_URL_PROPERTY);
    assertNotNull(url, "the packaged profile parks its database url before the launch");
    try (Connection db = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement insert =
            db.prepareStatement(
                "insert into ci_runner (id, name, slots, plane, client_id, capabilities,"
                    + " registered_at, created_at) values (?, ?, 1, 'INTERNAL', ?, cast(? as jsonb), ?, ?)"
                    + " on conflict do nothing")) {
      Timestamp now = Timestamp.from(Instant.now());
      insert.setObject(1, ID);
      insert.setString(2, NAME);
      insert.setString(3, CLIENT_ID);
      insert.setString(4, "{\"docker\":true,\"arch\":\"amd64\"}");
      insert.setTimestamp(5, now);
      insert.setTimestamp(6, now);
      insert.executeUpdate();
    }
    registered = true;
  }

  /** Registers if need be, dials the socket with the runner's bearer, and says {@code Hello}. */
  public static StoryRunner connect() throws Exception {
    register();
    String bearer =
        MockIdp.attach()
            .token()
            .subject(CLIENT_ID)
            .audience(StoryIdentities.AUDIENCE)
            .groups(CiRunnerSocket.RUNNER_ROLE)
            .mint();
    FakeCiRunner socket =
        FakeCiRunner.dial(
            URI.create("http://localhost:" + RestAssured.port + RunnerAddresses.SOCKET_PATH),
            Map.of("Authorization", "Bearer " + bearer));
    NetworkCapture.observe(
        NetworkEdge.SOCKET, ACTOR, StoryTarget.SERVICE, "CONNECT " + RunnerAddresses.SOCKET_PATH);
    StoryRunner runner = new StoryRunner(socket);
    socket.send(
        new Hello(
            CiRunnerBinary.VERSION,
            CiRunnerProtocol.CAPABILITY_VERSION,
            1,
            new Capabilities(
                true, "amd64", "linux", Map.of(CiRunnerRegistry.SELF_UPDATE_LABEL, "false"))));
    fromRunner("hello");
    Ack ack = socket.next(Ack.class, SOON);
    assertNotNull(ack, "the host must acknowledge a runner's Hello");
    fromHost("ack slots " + ack.slots());
    return runner;
  }

  /** {@code Reserve}, answered with this run's {@code Take} — the claim, and the only one there is. */
  public void take(String runId) throws Exception {
    socket.send(new Reserve());
    fromRunner("reserve");
    CiRunnerMessage answer =
        socket.nextMatching(m -> m instanceof Take || m instanceof Nothing, SOON);
    Take take = assertInstanceOf(Take.class, answer, "the queued run must be handed to the runner");
    assertEquals(runId, take.runId(), "the runner is handed the run the trigger accepted");
    fromHost("take");
  }

  /** The {@code Launch} for the next step: what the runner's host would {@code docker run}. */
  public Launch awaitLaunch(Duration patience) throws Exception {
    eu.wohlben.qits.cirunner.protocol.Launch launch =
        socket.next(eu.wohlben.qits.cirunner.protocol.Launch.class, patience);
    assertNotNull(launch, "qits-ci sent the runner no Launch within " + patience);
    fromHost("launch");
    return new Launch(
        launch.runId(),
        launch.stepIndex(),
        launch.workloadSpec().name(),
        launch.workloadSpec().env());
  }

  /** {@code Launched}: the container is up. */
  public void launched(Launch launch) throws Exception {
    socket.send(new Launched(launch.runId(), launch.stepIndex(), launch.containerName()));
    fromRunner("launched");
  }

  /** The {@code Reap} of a step's container, answered: what the runner's host would {@code docker rm}. */
  public void awaitReapAndConfirm(Launch launch, Duration patience) throws Exception {
    Reap reap = socket.next(Reap.class, patience);
    assertNotNull(reap, "qits-ci asked the runner to remove nothing within " + patience);
    assertEquals(launch.containerName(), reap.containerName(), "the container the Launch named");
    fromHost("reap");
    socket.send(new Reaped(reap.runId(), reap.stepIndex()));
    fromRunner("reaped");
  }

  /** {@code Released}: the run is over and the runner's slot is free again. */
  public void awaitReleased(String runId, Duration patience) throws Exception {
    CiRunnerMessage released =
        socket.nextMatching(m -> m instanceof Released r && runId.equals(r.runId()), patience);
    assertNotNull(released, "the run was never released to the runner within " + patience);
    fromHost("released");
  }

  @Override
  public void close() {
    socket.close();
  }

  private static void fromRunner(String label) {
    NetworkCapture.observe(NetworkEdge.EVENT, ACTOR, StoryTarget.SERVICE, label);
  }

  private static void fromHost(String label) {
    NetworkCapture.observe(NetworkEdge.EVENT, StoryTarget.SERVICE, ACTOR, label);
  }
}
