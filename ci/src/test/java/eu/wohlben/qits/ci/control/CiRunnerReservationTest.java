package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A runner's {@code Reserve} is the claim: the same candidates, order and compare-and-swap the
 * local workers use, narrowed to what the runner may take, with {@code runner_id} written in the
 * claiming UPDATE — and a reserved run's steps, close and cancellation go through the runner seam.
 *
 * <p>The local worker is parked inside a blocking run for every case ({@link #occupyTheWorker}), so
 * what is accepted afterwards is genuinely {@code QUEUED} and the only thing claiming it is the
 * case's own reservation — the same staging {@code CiRunClaimOrderTest} uses.
 */
@QuarkusTest
public class CiRunnerReservationTest extends CiTestSupport {

  private static final String SHA = "c".repeat(40);

  private static final String PLAIN = "steps:\n  - image: alpine:3\n    script: echo plain\n";

  private static final String DOCKER =
      "steps:\n  - image: alpine:3\n    script: docker build .\n    docker: true\n";

  private static final String BUILD =
      "steps:\n  - image: alpine:3\n    script: buildctl build\n    build: true\n";

  @Inject CiRunService service;

  @Inject CiRunnerRepository runnerRows;

  @Inject FakeRunnerStepRunner runnerSteps;

  private final CountDownLatch release = new CountDownLatch(1);

  private String blockerRunId;

  @BeforeEach
  void noRunners() {
    runnerSteps.reset();
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  @AfterEach
  void releaseTheWorker() throws Exception {
    release.countDown();
    service.awaitIdle();
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  // --- the race -----------------------------------------------------------------------------------

  @Test
  public void aLocalClaimAndARunnersClaimRacingForOneRowCannotBothWin() throws Exception {
    occupyTheWorker();
    CiRunner runner = runner("racer", 4, true);
    String runId = accept("contested", PLAIN);

    // The runner's UPDATE lands first and its transaction stays open; the local claim is issued
    // against the same row while it does. Under a read-then-write both would see QUEUED and both
    // would write RUNNING; under the conditional UPDATE the second waits on the row and then finds
    // it no longer QUEUED.
    CountDownLatch runnerUpdated = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    CompletableFuture<Integer> runnerClaim =
        CompletableFuture.supplyAsync(
            () ->
                QuarkusTransaction.requiringNew()
                    .call(
                        () -> {
                          int changed = runs.claimQueuedForRunner(runId, Instant.now(), runner.id);
                          runnerUpdated.countDown();
                          commit.await(20, TimeUnit.SECONDS);
                          return changed;
                        }));
    assertTrue(runnerUpdated.await(20, TimeUnit.SECONDS));
    CompletableFuture<Integer> localClaim =
        CompletableFuture.supplyAsync(
            () -> QuarkusTransaction.requiringNew().call(() -> runs.claimQueued(runId, Instant.now())));
    Thread.sleep(300);
    assertFalse(localClaim.isDone(), "the second claim waits on the row the first one holds");
    commit.countDown();

    assertEquals(1, runnerClaim.get(20, TimeUnit.SECONDS));
    assertEquals(0, localClaim.get(20, TimeUnit.SECONDS));
    CiRun row = row(runId);
    assertEquals(CiRunStatus.RUNNING, row.status);
    assertEquals(runner.id, row.runnerId, "the winner's claim names the runner in the same UPDATE");
  }

  @Test
  public void twoRunnersReservingAtOnceNeverShareARun() throws Exception {
    occupyTheWorker();
    CiRunner left = runner("left", 20, true);
    CiRunner right = runner("right", 20, true);
    Set<String> accepted = new HashSet<>();
    for (int i = 0; i < 6; i++) {
      accepted.add(accept("shared-" + i, PLAIN));
    }

    List<String> reserved = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Void>> racers = new ArrayList<>();
    for (CiRunner runner : List.of(left, right)) {
      racers.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  start.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                Optional<CiRunService.Reservation> next;
                while ((next = service.reserveFor(runner)).isPresent()) {
                  reserved.add(next.get().run().id);
                }
              }));
    }
    start.countDown();
    CompletableFuture.allOf(racers.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);

    assertEquals(accepted.size(), reserved.size(), "every queued run was reserved exactly once");
    assertEquals(accepted, new HashSet<>(reserved));
  }

  // --- what a runner may take ---------------------------------------------------------------------

  @Test
  public void aRunnerIsHandedTheRunAndTheRowNamesIt() throws Exception {
    occupyTheWorker();
    CiRunner runner = runner("plain-host", 1, false);
    String runId = accept("handed", PLAIN);

    CiRunService.Reservation reservation = service.reserveFor(runner).orElseThrow();

    assertEquals(runId, reservation.run().id);
    assertEquals(runner.id, reservation.run().runnerId);
    assertEquals(CiRunStatus.RUNNING, row(runId).status);
    assertEquals(runner.id, row(runId).runnerId);
  }

  @Test
  public void aRunNeedingDockerIsNeverOfferedToARunnerWithoutIt() throws Exception {
    occupyTheWorker();
    CiRunner socketless = runner("socketless", 5, false);
    CiRunner docker = runner("with-docker", 5, true);
    String dockerRun = accept("needs-docker", DOCKER);
    String buildRun = accept("needs-builder", BUILD);
    String plainRun = accept("needs-nothing", PLAIN);

    assertEquals(plainRun, service.reserveFor(socketless).orElseThrow().run().id);
    assertTrue(
        service.reserveFor(socketless).isEmpty(),
        "what is left needs docker, and this runner said it has none");
    assertEquals(CiRunStatus.QUEUED, row(dockerRun).status);

    Set<String> taken = new HashSet<>();
    taken.add(service.reserveFor(docker).orElseThrow().run().id);
    taken.add(service.reserveFor(docker).orElseThrow().run().id);
    assertEquals(Set.of(dockerRun, buildRun), taken);
  }

  @Test
  public void aBuildIsNeverHandedToARunnerThatCannotMapTheSixteenBitIdSpaceButAnUnknownRangeIsAllowed()
      throws Exception {
    occupyTheWorker();
    CiRunner narrow = runner("narrow", 5, true, "65535");
    CiRunner unknown = runner("older-runner", 5, true);
    CiRunner lxc = runner("qits-ci-like", 5, true, "458752");
    CiRunner full = runner("full-host", 5, true, "4294967295");
    String buildRun = accept("needs-builder", BUILD);
    String dockerRun = accept("needs-socket", DOCKER);

    assertEquals(
        dockerRun,
        service.reserveFor(narrow).orElseThrow().run().id,
        "a narrow runner still takes what does not run in its builder");
    assertTrue(service.reserveFor(narrow).isEmpty(), "the build is left for a runner that can map it");
    assertEquals(CiRunStatus.QUEUED, row(buildRun).status);
    assertEquals(
        buildRun,
        service.reserveFor(lxc).orElseThrow().run().id,
        "an unprivileged LXC's range maps 0..65535 and so takes a build (qits-443)");

    String another = accept("needs-builder-too", BUILD);
    assertEquals(
        another,
        service.reserveFor(unknown).orElseThrow().run().id,
        "a runner that says nothing about its range is not refused");

    String third = accept("needs-builder-three", BUILD);
    assertEquals(third, service.reserveFor(full).orElseThrow().run().id);

    assertTrue(CiRunService.tooNarrowToBuild(runnerRow("{\"idRange\":65535}")));
    assertTrue(CiRunService.tooNarrowToBuild(runnerRow("{\"idRange\":0}")));
    assertFalse(CiRunService.tooNarrowToBuild(runnerRow("{\"idRange\":65536}")));
    assertFalse(CiRunService.tooNarrowToBuild(runnerRow("{\"idRange\":458752}")));
    assertFalse(CiRunService.tooNarrowToBuild(runnerRow("{\"idRange\":4294967295}")));
    assertFalse(CiRunService.tooNarrowToBuild(runnerRow("{\"idRange\":\"narrow\"}")));
    assertFalse(CiRunService.tooNarrowToBuild(runnerRow("{}")));
    assertFalse(CiRunService.tooNarrowToBuild(runnerRow(null)));
  }

  @Test
  public void aRowStillCarryingAnAvoidSetIsReservedByTheRunnerItNames() throws Exception {
    // ci_run.avoid_runner_ids outlived the feature that wrote it (qits-443): rows queued while it
    // lived still name a runner, and the column is read by nothing — so the named runner takes them.
    occupyTheWorker();
    CiRunner only = runner("qits-ci", 5, true);
    String runId = accept("once-avoiding", BUILD);
    int written =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    runs.getEntityManager()
                        .createNativeQuery(
                            "update ci_run set avoid_runner_ids = ?1 where id = ?2")
                        .setParameter(1, "[\"" + only.id + "\"]")
                        .setParameter(2, runId)
                        .executeUpdate());
    assertEquals(1, written, "the column is still in the schema, and the row carries a set");

    CiRunService.Reservation taken = service.reserveFor(only).orElseThrow();
    assertEquals(runId, taken.run().id);
    assertEquals(only.id, row(runId).runnerId);
  }

  @Test
  public void aRunnerHoldingItsSlotsIsRefusedAndADrainedOneTakesNothing() throws Exception {
    occupyTheWorker();
    CiRunner one = runner("one-slot", 1, true);
    CiRunner drained = runner("drained", 0, true);
    accept("first", PLAIN);
    String second = accept("second", PLAIN);

    assertTrue(service.reserveFor(one).isPresent());
    assertTrue(service.reserveFor(one).isEmpty(), "one slot, one run held");
    assertTrue(service.reserveFor(drained).isEmpty(), "zero slots is a drained runner");
    assertEquals(CiRunStatus.QUEUED, row(second).status);

    // Its slot back — the held run is over — and the next reserve is served again.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                runs.update(
                    "status = ?1 where runnerId = ?2",
                    CiRunStatus.SUCCESS,
                    one.id));
    assertEquals(second, service.reserveFor(one).orElseThrow().run().id);
  }

  // --- the seam a reserved run goes through -------------------------------------------------------

  @Test
  public void aReservedRunsStepsAndCloseGoThroughTheRunnerSeam() throws Exception {
    occupyTheWorker();
    CiRunner runner = runner("driver-host", 1, true);
    String runId = accept("driven", PLAIN);
    CiRunService.Reservation reservation = service.reserveFor(runner).orElseThrow();

    service.executeReserved(reservation);

    assertEquals(List.of(runId), runnerSteps.executed().stream().map(s -> s.runId()).toList());
    assertTrue(
        fakeRunner.executed().stream().noneMatch(s -> s.runId().equals(runId)),
        "the local seam never saw the runner's run");
    assertEquals(List.of(runId), runnerSteps.closed());
    assertEquals(CiRunStatus.SUCCESS, row(runId).status);
  }

  @Test
  public void cancellingARunnersRunReachesTheRunnerSeamAndALocalRunTheLocalOne() throws Exception {
    occupyTheWorker();
    CiRunner runner = runner("cancel-host", 1, true);
    String runId = accept("to-cancel", PLAIN);
    service.reserveFor(runner).orElseThrow();
    runnerSteps.hold(runId);

    service.cancel(runId);
    service.cancel(blockerRunId);

    assertEquals(List.of(runId), runnerSteps.cancelled());
    assertTrue(fakeRunner.cancelled().contains(blockerRunId));
    assertFalse(fakeRunner.cancelled().contains(runId));
    // Held and asked, not settled: the runner's driver writes the terminal row.
    assertEquals(CiRunStatus.RUNNING, row(runId).status);
  }

  @Test
  public void aRunnersRunTheBootSweepHandedBackIsClaimedLocallyWithoutItsRunner() throws Exception {
    occupyTheWorker();
    CiRunner runner = runner("interrupted-host", 1, true);
    String runId = accept("handed-back", PLAIN);
    service.reserveFor(runner).orElseThrow();
    // What sweepInterrupted does to a RUNNING event run: back to QUEUED, runner column untouched.
    QuarkusTransaction.requiringNew()
        .run(() -> runs.update("status = ?1 where id = ?2", CiRunStatus.QUEUED, runId));

    assertEquals(1, (int) QuarkusTransaction.requiringNew().call(() -> runs.claimQueued(runId, Instant.now())));
    assertNull(row(runId).runnerId, "a local claim does not come back up naming a runner");
    QuarkusTransaction.requiringNew()
        .run(() -> runs.update("status = ?1 where id = ?2", CiRunStatus.CANCELLED, runId));
  }

  // --- staging ------------------------------------------------------------------------------------

  private void occupyTheWorker() throws Exception {
    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    fakeRunner.during(
        0,
        spec -> {
          if (inStepZero.complete(spec.runId())) {
            try {
              release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    accept("blocker", PLAIN);
    blockerRunId = inStepZero.get(20, TimeUnit.SECONDS);
  }

  private String accept(String repoName, String stepsYaml) {
    return service.onEventTrigger(
        eventRun(
            CiRepoRef.of("reserve-" + UUID.randomUUID(), "qits", repoName), "main", SHA, stepsYaml));
  }

  private CiRunner runner(String name, int slots, boolean docker) {
    return runner(name, slots, docker, null);
  }

  private static CiRunner runnerRow(String capabilities) {
    CiRunner runner = new CiRunner();
    runner.capabilities = capabilities;
    return runner;
  }

  private CiRunner runner(String name, int slots, boolean docker, String idRange) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRunner runner = new CiRunner();
              runner.id = UUID.randomUUID();
              runner.name = name;
              runner.slots = slots;
              runner.plane = CiRunnerPlane.INTERNAL;
              runner.clientId = "client-" + name;
              runner.capabilities =
                  "{\"docker\":" + docker + ",\"arch\":\"amd64\""
                      + (idRange == null ? "" : ",\"idRange\":" + idRange) + "}";
              runner.registeredAt = Instant.now();
              runner.createdAt = Instant.now();
              runnerRows.persist(runner);
              return runner;
            });
  }

  private CiRun row(String runId) {
    return QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
  }
}
