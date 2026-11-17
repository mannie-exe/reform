package design.inherent.reform.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SerialLaneTest {
  @Test
  void boundsIncludeTheActiveOperationAndReleaseOnTerminalCompletion() {
    var lane = new SerialLane(2);
    var active = new CompletableFuture<Integer>();
    var started = new AtomicInteger();
    var first = lane.submit(() -> active);
    var next = lane.submit(() -> CompletableFuture.completedStage(started.incrementAndGet()));
    var full = lane.submit(() -> CompletableFuture.completedStage(100));
    assertThrows(CompletionException.class, () -> full.toCompletableFuture().join());
    assertEquals(0, started.get());
    active.complete(5);
    assertEquals(5, first.toCompletableFuture().join());
    assertEquals(1, next.toCompletableFuture().join());
    assertEquals(
        2, lane.submit(() -> CompletableFuture.completedStage(2)).toCompletableFuture().join());
    lane.close();
  }

  @Test
  void closeRefusesQueuedWorkButPreservesTheActualActiveResult() {
    var lane = new SerialLane(2);
    var active = new CompletableFuture<Integer>();
    var effects = new AtomicInteger();
    var first = lane.submit(() -> active);
    var next = lane.submit(() -> CompletableFuture.completedStage(effects.incrementAndGet()));
    lane.close();
    assertThrows(CompletionException.class, () -> next.toCompletableFuture().join());
    assertFalse(first.toCompletableFuture().isDone());
    active.complete(7);
    assertEquals(7, first.toCompletableFuture().join());
    assertEquals(0, effects.get());
  }

  @Test
  void terminalObserversMaySubmitTheNextOperationAtTheSmallestBound() {
    var lane = new SerialLane(1);
    var active = new CompletableFuture<Integer>();
    var next = new AtomicReference<CompletableFuture<Integer>>();
    lane.submit(() -> active)
        .thenRun(
            () ->
                next.set(
                    lane.submit(() -> CompletableFuture.completedStage(2)).toCompletableFuture()));
    active.complete(1);
    assertEquals(2, next.get().join());
    lane.close();
  }

  @Test
  void closureFromACompletionObserverRefusesTheSelectedButUnstartedNextOperation() {
    var lane = new SerialLane(2);
    var active = new CompletableFuture<Integer>();
    var first = lane.submit(() -> active);
    var effects = new AtomicInteger();
    var next = lane.submit(() -> CompletableFuture.completedStage(effects.incrementAndGet()));
    first.thenRun(lane::close);
    active.complete(1);
    assertThrows(CompletionException.class, () -> next.toCompletableFuture().join());
    assertEquals(0, effects.get());
  }

  @Test
  void anActionFailureReleasesAdmissionWithoutBecomingASuccessfulResult() {
    var lane = new SerialLane(1);
    var failed =
        lane.submit(
            () -> {
              throw new IllegalStateException("before dispatch");
            });
    assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join());
    assertEquals(
        1, lane.submit(() -> CompletableFuture.completedStage(1)).toCompletableFuture().join());
    lane.close();
  }
}
