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
  void atLeastOneBuildSlotIsRequired() {
    assertThrows(IllegalArgumentException.class, () -> CiRunService.createWorkerPool(0));
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
