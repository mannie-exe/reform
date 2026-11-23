package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RenderLifetimeTest {
  @Test
  void closingTheViewDoesNotReleaseResourcesStillUsedByNativeConsumers() {
    var released = new AtomicInteger();
    var lifetime = new RenderLifetime(2, () -> {}, released::incrementAndGet);
    var first = lifetime.acquire();
    var second = lifetime.acquire();
    lifetime.close();
    assertThrows(PolicyRefusal.class, lifetime::acquire);
    assertEquals(0, released.get());
    first.retire();
    assertFalse(lifetime.state().released());
    assertEquals(1, lifetime.state().outstandingUses());
    second.retire();
    assertTrue(lifetime.state().released());
    first.retire();
    second.retire();
    lifetime.close();
    assertEquals(1, released.get());
  }

  @Test
  void wrongThreadAndBudgetRefusalsPreserveActualOutstandingOwnership() {
    var nativeThread = new AtomicBoolean(true);
    var released = new AtomicInteger();
    var lifetime =
        new RenderLifetime(
            1,
            () -> {
              if (!nativeThread.get()) throw new IllegalStateException("Wrong native thread");
            },
            released::incrementAndGet);
    var use = lifetime.acquire();
    assertThrows(PolicyRefusal.class, lifetime::acquire);
    nativeThread.set(false);
    assertThrows(IllegalStateException.class, use::retire);
    assertThrows(IllegalStateException.class, lifetime::close);
    assertFalse(lifetime.state().closing());
    assertEquals(1, lifetime.state().outstandingUses());
    nativeThread.set(true);
    lifetime.close();
    assertEquals(0, released.get());
    use.retire();
    assertEquals(1, released.get());
  }

  @Test
  void enteringNativeReleaseIsNotACompletedReleaseReceipt() {
    var holder = new java.util.concurrent.atomic.AtomicReference<RenderLifetime>();
    var lifetime =
        new RenderLifetime(
            1,
            () -> {},
            () -> {
              assertTrue(holder.get().state().releaseAttempted());
              assertFalse(holder.get().state().releaseCompleted());
              assertFalse(holder.get().state().released());
            });
    holder.set(lifetime);
    lifetime.close();
    assertTrue(lifetime.state().releaseCompleted());
    assertTrue(lifetime.state().released());
  }

  @Test
  void partialCleanupFailureIsRetainedAndIsNeverRetriedAsIfNothingHappened() {
    var attempts = new AtomicInteger();
    var failed = new IllegalStateException("Native teardown failed after entering release");
    var lifetime =
        new RenderLifetime(
            1,
            () -> {},
            () -> {
              attempts.incrementAndGet();
              throw failed;
            });
    var use = lifetime.acquire();
    lifetime.close();
    assertSame(failed, assertThrows(IllegalStateException.class, use::retire));
    assertTrue(lifetime.state().releaseAttempted());
    assertFalse(lifetime.state().released());
    assertSame(failed, lifetime.state().releaseFailure().orElseThrow());
    use.retire();
    lifetime.close();
    assertEquals(1, attempts.get());
  }
}
