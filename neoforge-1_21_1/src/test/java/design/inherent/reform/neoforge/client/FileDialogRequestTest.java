package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FileDialogRequestTest {
  @Test
  void abandonedQueuedWorkNeverEntersTheNativeSupplier() {
    var request = new FileDialogRequest();
    var queued = new AtomicReference<Runnable>();
    var calls = new AtomicInteger();
    var result =
        CompletableFuture.supplyAsync(
            () ->
                request.select(
                    () -> {
                      calls.incrementAndGet();
                      return "Chosen café.json";
                    }),
            queued::set);
    assertTrue(request.abandonQueued());
    queued.get().run();
    assertNull(result.join());
    assertEquals(0, calls.get());
    assertNull(
        request.select(
            () -> {
              fail("Retired request replayed");
              return null;
            }));
  }

  @Test
  void enteredNativeWorkDrainsWithoutClaimingCancellationOrDoubleEntry() throws Exception {
    var request = new FileDialogRequest();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var worker = Executors.newSingleThreadExecutor()) {
      var result =
          CompletableFuture.supplyAsync(
              () ->
                  request.select(
                      () -> {
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Native drain deadline");
                        } catch (InterruptedException failed) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(failed);
                        }
                        return "Chosen café.json";
                      }),
              worker);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertFalse(request.abandonQueued());
        assertFalse(result.isDone());
        assertNull(
            request.select(
                () -> {
                  fail("Native dialog entered twice");
                  return null;
                }));
      } finally {
        release.countDown();
      }
      assertEquals("Chosen café.json", result.get(5, TimeUnit.SECONDS));
      assertFalse(request.abandonQueued());
    }
  }

  @Test
  void cancelAndFailureKeepTheEntryClaimConsumed() {
    var cancelled = new FileDialogRequest();
    assertNull(cancelled.select(() -> null));
    assertNull(
        cancelled.select(
            () -> {
              fail("Cancel was replayed");
              return null;
            }));
    var failed = new FileDialogRequest();
    assertThrows(
        IllegalStateException.class,
        () ->
            failed.select(
                () -> {
                  throw new IllegalStateException("Native selection failed");
                }));
    assertNull(
        failed.select(
            () -> {
              fail("Failed UI was replayed");
              return null;
            }));
  }
}
