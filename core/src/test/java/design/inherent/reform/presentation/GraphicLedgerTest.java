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
}
