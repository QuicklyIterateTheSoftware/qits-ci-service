package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CiRunWorkerPoolTest {

  @Test
  void configuredBuildSlotsCanExecuteAtTheSameTime() throws Exception {
    ExecutorService workers = CiRunService.createWorkerPool(2);
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try {
      for (int i = 0; i < 2; i++) {
        workers.submit(
            () -> {
              bothStarted.countDown();
              release.await();
              return null;
            });
      }

      assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "both configured build slots started");
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void aNegativeBuildSlotCountIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> CiRunService.createWorkerPool(-1));
  }

  /**
   * qits-503: {@code qits.ci.concurrent-builds=0} hands every run to the runners. The pool still
   * exists — shutdown has one shape whatever the size — but no claim loop is ever submitted to it,
   * so it starts no thread, and the census says zero of zero rather than the outage "zero of four".
   */
  @Test
  void aZeroPoolStartsNoClaimLoopAndReservesNothingLocally() throws Exception {
    CiRunService service = new CiRunService();
    service.inProcessExecutorEnabled = true;
    service.concurrentBuilds = 0;
    service.initializeWorkers();
    try {
      Thread.sleep(100);
      assertEquals(
          new CiRunService.WorkerCensus(0, 0, false),
          service.workerCensus(),
          "no claim loop is live, so nothing is ever claimed here");
    } finally {
      service.shutdown();
    }
    assertEquals(new CiRunService.WorkerCensus(0, 0, true), service.workerCensus());
  }

  /**
   * qits-443: the switch beats the size. The live deployment carries {@code
   * QITS_CI_CONCURRENT_BUILDS=1} and nobody will edit it, so {@code
   * qits.ci.in-process-executor.enabled=false} has to mean zero whatever that key says — no claim
   * loop, a census of zero of zero, and a queue that answers {@code concurrentBuilds: 0} with a
   * forecast that has no local slot in it.
   */
  @Test
  void aSwitchedOffExecutorRunsNoClaimLoopWhateverConcurrentBuildsSays() throws Exception {
    // The queue's two reads, stood in for: no rows, and (ciRunners being null here) no runners —
    // queueSnapshot answers an unreadable runner listing as an empty one.
    CiRunService service =
        new CiRunService() {
          @Override
          public java.util.List<eu.wohlben.qits.ci.entity.CiRun> activeRuns() {
            return java.util.List.of();
          }
        };
    service.inProcessExecutorEnabled = false;
    service.concurrentBuilds = 4;
    service.initializeWorkers();
    try {
      Thread.sleep(100);
      assertEquals(0, service.effectiveWorkers(), "the effective pool is 0, not the configured 4");
      assertEquals(
          new CiRunService.WorkerCensus(0, 0, false),
          service.workerCensus(),
          "no claim loop is live and none is configured, so readiness counts runners alone");
      CiRunService.Snapshot queue = service.queueSnapshot();
      assertEquals(0, queue.concurrentBuilds(), "the queue reports the pool it really has");
      assertTrue(queue.runners().isEmpty());
    } finally {
      service.shutdown();
    }
  }

  /** The same service with the switch on is the pool {@code concurrent-builds} sizes. */
  @Test
  void aSwitchedOnExecutorRunsTheConfiguredClaimLoops() {
    CiRunService service = new CiRunService();
    service.inProcessExecutorEnabled = true;
    service.concurrentBuilds = 4;
    assertEquals(4, service.effectiveWorkers());
    assertEquals(4, service.workerCensus().configured());
  }

  /**
   * The supervisor, on its own. A fixed pool never refills a thread that died of an {@code Error},
   * and the claim loops are the only tasks it ever holds — so a loop that ends is a build slot gone
   * for the life of the process unless something puts one back.
   */
  @Test
  void aClaimLoopThatDiesOfAnErrorIsReplacedOnTheSamePool() throws Exception {
    ExecutorService workers = CiRunService.createWorkerPool(1);
    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean wanted = new AtomicBoolean(true);
    CountDownLatch thirdRan = new CountDownLatch(1);
    try {
      workers.submit(
          CiRunService.supervised(
              workers,
              wanted::get,
              () -> {
                if (attempts.incrementAndGet() < 3) {
                  throw new NoClassDefFoundError("a claim loop died where nothing catches");
                }
                // Third time round, stop asking for a replacement the way `stopping` does.
                wanted.set(false);
                thirdRan.countDown();
              }));

      assertTrue(thirdRan.await(5, TimeUnit.SECONDS), "the loop was resubmitted after each Error");
      assertEquals(3, attempts.get());
    } finally {
      wanted.set(false);
      workers.shutdownNow();
    }
  }

  /**
   * And the other direction, which is what keeps a shutdown a shutdown: {@code stopping} is raised
   * before the pool is stopped, so a loop ending after that is not replaced.
   */
  @Test
  void aClaimLoopThatEndsWhileTheProcessIsStoppingIsNotReplaced() throws Exception {
    ExecutorService workers = CiRunService.createWorkerPool(1);
    AtomicInteger attempts = new AtomicInteger();
    try {
      workers.submit(CiRunService.supervised(workers, () -> false, attempts::incrementAndGet));

      workers.shutdown();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "the pool drained");
      assertEquals(1, attempts.get(), "one run and no replacement");
    } finally {
      workers.shutdownNow();
    }
  }
}
