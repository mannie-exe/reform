package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import org.junit.jupiter.api.Test;

class GraphicLedgerTest {
  private static GraphicLedger ledger() {
    return new GraphicLedger(new GraphicLimits(1024, 1024, 256, 2, 8));
  }

  @Test
  void closedViewKeepsAllocationAccountedUntilActualUseRetirement() {
    var ledger = ledger();
    var allocation = ledger.reserve(8, 1);
    var lifetime = new RenderLifetime(1, () -> {}, allocation::close);
    var use = lifetime.acquire();
    lifetime.close();
    assertEquals(8, ledger.state().bytes());
    assertThrows(PolicyRefusal.class, () -> ledger.reserve(1, 1));
    use.retire();
    assertEquals(0, ledger.state().bytes());
    assertEquals(0, ledger.state().resources());
    allocation.close();
    assertEquals(0, ledger.state().bytes());
    var next = ledger.reserve(8, 2);
    assertEquals(8, ledger.state().peakBytes());
    assertEquals(2, ledger.state().peakResources());
    next.close();
  }

  @Test
  void failedReleaseRetainsUnknownAllocationAndIndependentSiblingCanClose() {
    var ledger = ledger();
    var first = ledger.reserve(4, 1);
    var second = ledger.reserve(4, 1);
    var failure = new IllegalStateException("native close failed");
    var failed =
        new RenderLifetime(
            1,
            () -> {},
            () -> {
              throw failure;
            });
    assertSame(failure, assertThrows(IllegalStateException.class, failed::close));
    second.close();
    assertEquals(4, ledger.state().bytes());
    assertEquals(1, ledger.state().resources());
    assertFalse(failed.state().released());
    assertSame(failure, failed.state().releaseFailure().orElseThrow());
    // Only a separate actual teardown observation can now justify releasing that reservation.
    first.close();
    assertEquals(0, ledger.state().bytes());
  }

  @Test
  void objectsAndExtremePayloadsCannotOverflowAdmission() {
    var ledger = ledger();
    var a = ledger.reserve(0, 1);
    var b = ledger.reserve(0, 1);
    assertThrows(PolicyRefusal.class, () -> ledger.reserve(0, 1));
    a.close();
    b.close();
    assertThrows(PolicyRefusal.class, () -> ledger.reserve(Long.MAX_VALUE, 1));
    assertThrows(IllegalArgumentException.class, () -> ledger.reserve(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> ledger.reserve(0, 0));
    assertEquals(0, ledger.state().bytes());
  }

  @Test
  void growingOneBlockChecksEveryParentAtomicallyAndReleasesItsFinalSize() {
    var root = new GraphicLedger(new GraphicLimits(16, 16, 16, 4, 12));
    var child = root.child(new GraphicLimits(8, 8, 8, 2, 8));
    var block = child.reserve(3, 1);
    var sibling = root.reserve(5, 1);
    child.grow(block, 7);
    assertEquals(12, root.state().bytes());
    assertEquals(7, child.state().bytes());
    assertEquals(1, child.state().resources());
    assertThrows(PolicyRefusal.class, () -> child.grow(block, 8));
    assertEquals(7, child.state().bytes());
    assertEquals(12, root.state().bytes());
    sibling.close();
    assertThrows(PolicyRefusal.class, () -> child.grow(block, 9));
    assertThrows(IllegalArgumentException.class, () -> child.grow(block, 6));
    assertThrows(IllegalArgumentException.class, () -> root.grow(block, 8));
    child.grow(block, 8);
    assertEquals(8, root.state().bytes());
    assertEquals(1, root.state().resources());
    block.close();
    assertEquals(0, root.state().bytes());
    assertEquals(0, child.state().resources());
    assertThrows(IllegalStateException.class, () -> child.grow(block, 8));
  }

  @Test
  void siblingProvidersShareAtomicParentAdmissionAndRelease() throws Exception {
    var root = new GraphicLedger(new GraphicLimits(1024, 1024, 256, 4, 8));
    var childLimit = new GraphicLimits(512, 512, 128, 2, 6);
    var first = root.child(childLimit);
    var second = root.child(childLimit);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    var kept = new java.util.concurrent.atomic.AtomicReference<GraphicLedger.Allocation>();
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var jobs =
          java.util.stream.Stream.of(first, second)
              .map(
                  ledger ->
                      workers.submit(
                          () -> {
                            ready.countDown();
                            start.await();
                            try {
                              var owned = ledger.reserve(6, 1);
                              assertTrue(kept.compareAndSet(null, owned));
                              return true;
                            } catch (PolicyRefusal refused) {
                              return false;
                            }
                          }))
              .toList();
      assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      int admitted = 0;
      for (var job : jobs) if (job.get(5, java.util.concurrent.TimeUnit.SECONDS)) admitted++;
      assertEquals(1, admitted);
      assertEquals(6, root.state().bytes());
      assertEquals(1, root.state().resources());
      assertEquals(6, first.state().bytes() + second.state().bytes());
      kept.get().close();
      kept.get().close();
      assertEquals(0, root.state().bytes());
      assertEquals(0, first.state().bytes());
      assertEquals(0, second.state().bytes());
    } finally {
      start.countDown();
      if (kept.get() != null) kept.get().close();
    }
    assertThrows(
        IllegalArgumentException.class, () -> root.child(new GraphicLimits(1025, 512, 128, 2, 6)));
    assertThrows(
        IllegalArgumentException.class, () -> first.child(new GraphicLimits(512, 512, 128, 2, 7)));
  }
}
