package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperationScopeTest {
  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failed);
    }
  }

  private static OperationDescriptor<Integer, String> descriptor(
      String name, ValueType<Integer> type) {
    return new OperationDescriptor<>(
        DocumentId.parse("fixture:" + name),
        type,
        OwnerContext.Right.WRITE,
        Set.of(OperationDescriptor.Effect.PERSIST),
        Set.of(OperationDescriptor.EntryPoint.GUI, OperationDescriptor.EntryPoint.COMMAND));
  }

  private static OperationDescriptor<Integer, String> descriptor(String name) {
    return descriptor(name, ValueTypes.integer(0, 100));
  }

  private final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService workers;
    final AtomicBoolean allowed = new AtomicBoolean(true);
    final OwnerContext context;
    final OwnerContext.Access access;
    final OperationScope scope;
    final long nativeThread;

    Harness() throws Exception {
      this(1, 1);
    }

    Harness(int workerCount, int active) throws Exception {
      workers = Executors.newFixedThreadPool(workerCount);
      nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(nativeThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      access = nativeCall(() -> context.access(allowed::get, allowed::get));
      scope =
          nativeCall(
              () ->
                  new OperationScope(
                      context,
                      access,
                      new OperationScope.Limits(
                          4, active, 2, new JsonLimits(1024, 16, 128, 128, 32, 64))));
    }

    <T> T nativeCall(Callable<T> action) throws Exception {
      return nativeExecutor.submit(action).get(5, TimeUnit.SECONDS);
    }

    <I, O> OperationHandle<O> submit(OperationDescriptor<I, O> descriptor, I input)
        throws Exception {
      return nativeCall(
          () -> scope.submit(descriptor, OperationDescriptor.EntryPoint.COMMAND, input));
    }

    void ready(
        OperationDescriptor<Integer, String> descriptor,
        OperationScope.Handler<Integer, String> handler)
        throws Exception {
      nativeCall(
          () -> {
            scope.register(descriptor, handler);
            scope.freeze();
            return null;
          });
    }

    @Override
    public void close() {
      try {
        if (context.isOpen())
          nativeCall(
              () -> {
                context.close();
                return null;
              });
      } catch (Exception failed) {
        if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new AssertionError("Could not close native fixture context", failed);
      } finally {
        workers.shutdownNow();
        nativeExecutor.shutdownNow();
        try {
          assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
          assertTrue(nativeExecutor.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException failed) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failed);
        }
      }
    }
  }

  @Test
  void registrationInputAuthorityActiveAndRetentionLimitsAreReal() throws Exception {
    try (var h = new Harness()) {
      var descriptor = descriptor("save");
      var provider = new CompletableFuture<String>();
      h.nativeCall(
          () -> {
            assertThrows(
                PolicyRefusal.class,
                () -> h.scope.submit(descriptor, OperationDescriptor.EntryPoint.COMMAND, 1));
            h.scope.register(descriptor, (execution, value) -> provider);
            assertThrows(
                IllegalArgumentException.class,
                () -> h.scope.register(descriptor, (e, v) -> provider));
            h.scope.freeze();
            assertThrows(
                IllegalStateException.class,
                () -> h.scope.register(descriptor("late"), (e, v) -> provider));
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.scope.submit(descriptor("save"), OperationDescriptor.EntryPoint.COMMAND, 1));
            assertThrows(
                PolicyRefusal.class,
                () -> h.scope.submit(descriptor, OperationDescriptor.EntryPoint.INTERNAL, 1));
            return null;
          });
      var first = h.submit(descriptor, 1);
      h.nativeCall(
          () -> {
            assertThrows(
                PolicyRefusal.class,
                () -> h.scope.submit(descriptor, OperationDescriptor.EntryPoint.COMMAND, 2));
            return null;
          });
      provider.complete("saved but not applied");
      assertEquals(OperationSnapshot.State.COMPLETED, await(first.completion()).state());
      var second = h.submit(descriptor, 2);
      await(second.completion());
      var third = h.submit(descriptor, 3);
      await(third.completion());
      assertEquals(2, h.nativeCall(() -> h.scope.snapshots().size()));
      assertFalse(
          h.nativeCall(
              () ->
                  h.scope.snapshots().stream()
                      .anyMatch(s -> s.id().equals(first.snapshot().id()))));
      assertEquals("saved but not applied", first.snapshot().result().orElseThrow());
      h.allowed.set(false);
      h.nativeCall(
          () -> {
            assertThrows(
                PolicyRefusal.class,
                () -> h.scope.submit(descriptor, OperationDescriptor.EntryPoint.COMMAND, 4));
            return null;
          });
    }
  }

  @Test
  void cancellingWhileQueuedNeverInvokesTheProvider() throws Exception {
    try (var h = new Harness()) {
      var descriptor = descriptor("queued");
      var calls = new AtomicInteger();
      h.ready(
          descriptor,
          (execution, value) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedStage("unexpected");
          });
      var blocked = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.workers.execute(
          () -> {
            blocked.countDown();
            waitFor(release);
          });
      waitFor(blocked);
      try {
        var handle = h.submit(descriptor, 1);
        var requested = await(handle.requestCancellation());
        assertEquals(OperationSnapshot.State.CANCELLATION_REQUESTED, requested.state());
        assertFalse(requested.started());
        assertFalse(handle.completion().toCompletableFuture().isDone());
        release.countDown();
        var stopped = await(handle.completion());
        assertEquals(OperationSnapshot.State.CANCELLED, stopped.state());
        assertFalse(stopped.started());
        assertTrue(stopped.result().isEmpty());
        assertEquals(0, calls.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void runningCancellationAndScopeCloseDrainTheActualResult() throws Exception {
    try (var h = new Harness()) {
      var descriptor = descriptor("drain");
      var entered = new CountDownLatch(1);
      var provider = new CompletableFuture<String>();
      h.ready(
          descriptor,
          (execution, value) -> {
            entered.countDown();
            return provider;
          });
      var handle = h.submit(descriptor, 1);
      waitFor(entered);
      assertTrue(handle.completion().toCompletableFuture().cancel(false));
      assertFalse(handle.snapshot().cancellationRequested());
      await(handle.requestCancellation());
      h.nativeCall(
          () -> {
            h.scope.close();
            return null;
          });
      assertTrue(h.context.isOpen());
      assertTrue(h.access.isOpen());
      assertFalse(handle.completion().toCompletableFuture().isDone());
      provider.complete("actual saved receipt after cancellation");
      var result = await(handle.completion());
      assertEquals(OperationSnapshot.State.COMPLETED, result.state());
      assertTrue(result.started());
      assertTrue(result.cancellationRequested());
      assertEquals("actual saved receipt after cancellation", result.result().orElseThrow());
      assertEquals(0, h.nativeCall(() -> h.scope.snapshots().size()));
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      assertSame(result, await(handle.requestCancellation()));
    }
  }

  @Test
  void workerPreparationAndFreshNativePermissionAreSeparate() throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var nativeCopies = new AtomicInteger();
      var calls = new AtomicInteger();
      var base = ValueTypes.integer(0, 100);
      var type =
          ValueType.of(
              base.codec(),
              base.shape(),
              new SnapshotPolicy<Integer>() {
                @Override
                public Integer freeze(Integer value, SnapshotContext context) {
                  if (Thread.currentThread().threadId() == h.nativeThread)
                    nativeCopies.incrementAndGet();
                  entered.countDown();
                  waitFor(release);
                  return value;
                }

                @Override
                public boolean equivalent(Integer first, Integer second) {
                  return first.equals(second);
                }
              });
      var descriptor = descriptor("prepare", type);
      h.ready(
          descriptor,
          (execution, value) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedStage("effect");
          });
      try {
        var handle = h.submit(descriptor, 2);
        waitFor(entered);
        h.allowed.set(false);
        release.countDown();
        var refused = await(handle.completion());
        assertEquals(OperationSnapshot.State.REFUSED, refused.state());
        assertFalse(refused.started());
        assertTrue(
            refused.report().issues().stream()
                .anyMatch(issue -> issue.code().equals("owner.permission")));
        assertEquals(0, nativeCopies.get());
        assertEquals(0, calls.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void invalidInputProgressAndTerminalSnapshotsDoNotInventResults() throws Exception {
    try (var h = new Harness()) {
      var descriptor = descriptor("progress");
      var entered = new CountDownLatch(1);
      var execution = new AtomicReference<OperationScope.Execution>();
      var provider = new CompletableFuture<String>();
      h.ready(
          descriptor,
          (running, value) -> {
            execution.set(running);
            entered.countDown();
            return provider;
          });
      var bad = h.submit(descriptor, 101);
      assertEquals(OperationSnapshot.State.REFUSED, await(bad.completion()).state());
      assertFalse(bad.snapshot().started());
      assertTrue(bad.snapshot().result().isEmpty());
      var handle = h.submit(descriptor, 5);
      waitFor(entered);
      var before = handle.snapshot();
      assertTrue(
          execution
              .get()
              .progress(new OperationSnapshot.Progress("scan", 1, OptionalLong.of(3), "items")));
      assertEquals(0, before.progress().completed());
      assertTrue(handle.snapshot().revision() > before.revision());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              execution
                  .get()
                  .progress(
                      new OperationSnapshot.Progress("scan", 0, OptionalLong.of(3), "items")));
      assertThrows(
          IllegalArgumentException.class,
          () -> new OperationSnapshot.Progress("scan", 4, OptionalLong.of(3), "items"));
      provider.complete("partial domain result");
      var result = await(handle.completion());
      assertFalse(
          execution
              .get()
              .progress(new OperationSnapshot.Progress("late", 1, OptionalLong.empty(), "items")));
      assertSame(result, handle.snapshot());
    }
  }

  @Test
  void closedContextRetainsRunningReceiptsAndRefusesUnstartedCalls() throws Exception {
    try (var h = new Harness()) {
      var descriptor = descriptor("shutdown");
      var entered = new CountDownLatch(1);
      var provider = new CompletableFuture<String>();
      h.ready(
          descriptor,
          (execution, value) -> {
            entered.countDown();
            return provider;
          });
      var handle = h.submit(descriptor, 1);
      waitFor(entered);
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      assertFalse(handle.completion().toCompletableFuture().isDone());
      assertTrue(handle.snapshot().cancellationRequested());
      provider.complete("known persisted state; application unavailable");
      assertEquals(
          "known persisted state; application unavailable",
          await(handle.completion()).result().orElseThrow());
    }
    try (var h = new Harness()) {
      var descriptor = descriptor("unstarted");
      var invoked = new AtomicBoolean();
      h.ready(
          descriptor,
          (execution, value) -> {
            invoked.set(true);
            return CompletableFuture.completedStage("effect");
          });
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.workers.execute(
          () -> {
            entered.countDown();
            waitFor(release);
          });
      waitFor(entered);
      try {
        var handle = h.submit(descriptor, 1);
        h.nativeCall(
            () -> {
              h.context.close();
              return null;
            });
        assertEquals(OperationSnapshot.State.REFUSED, await(handle.completion()).state());
        assertFalse(handle.snapshot().started());
        assertFalse(invoked.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void parallelPreparationPreservesInvocationOrderWithoutWaitingForProviderCompletion()
      throws Exception {
    try (var h = new Harness(2, 2)) {
      var firstPreparing = new CountDownLatch(1);
      var secondPrepared = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var firstProvider = new CompletableFuture<String>();
      var order = new java.util.ArrayList<Integer>();
      var base = ValueTypes.integer(0, 100);
      var type =
          ValueType.of(
              base.codec(),
              base.shape(),
              new SnapshotPolicy<Integer>() {
                @Override
                public Integer freeze(Integer value, SnapshotContext context) {
                  if (value == 1) {
                    firstPreparing.countDown();
                    waitFor(release);
                  } else secondPrepared.countDown();
                  return value;
                }

                @Override
                public boolean equivalent(Integer first, Integer second) {
                  return first.equals(second);
                }
              });
      var descriptor = descriptor("ordered", type);
      h.ready(
          descriptor,
          (execution, value) -> {
            order.add(value);
            return value == 1 ? firstProvider : CompletableFuture.completedStage("second");
          });
      try {
        var first = h.submit(descriptor, 1);
        waitFor(firstPreparing);
        var second = h.submit(descriptor, 2);
        waitFor(secondPrepared);
        // Worker2's FIFO canary follows its complete preparation/continuation. The native FIFO
        // barrier then follows any invocation it scheduled; no timing-based absence assertion.
        h.workers.submit(() -> {}).get(5, TimeUnit.SECONDS);
        h.nativeCall(
            () -> {
              assertTrue(order.isEmpty(), "second provider overtook first input");
              return null;
            });
        release.countDown();
        assertEquals("second", await(second.completion()).result().orElseThrow());
        assertFalse(first.completion().toCompletableFuture().isDone());
        h.nativeCall(
            () -> {
              assertEquals(java.util.List.of(1, 2), order);
              return null;
            });
        firstProvider.complete("first drained");
        assertEquals("first drained", await(first.completion()).result().orElseThrow());
      } finally {
        release.countDown();
        firstProvider.complete("fixture cleanup");
      }
    }
  }

  @Test
  void reentrantAuthorizationCannotRestartAClosedScope() throws Exception {
    try (var h = new Harness()) {
      var close = new AtomicBoolean();
      var calls = new AtomicInteger();
      var actual = new AtomicReference<OperationScope>();
      var access =
          h.nativeCall(
              () ->
                  h.context.access(
                      () -> true,
                      () -> {
                        if (close.getAndSet(false)) actual.get().close();
                        return true;
                      }));
      var scope =
          h.nativeCall(() -> new OperationScope(h.context, access, OperationScope.Limits.STANDARD));
      actual.set(scope);
      var descriptor = descriptor("reentrant");
      h.nativeCall(
          () -> {
            scope.register(
                descriptor,
                (e, v) -> {
                  calls.incrementAndGet();
                  return CompletableFuture.completedStage("effect");
                });
            scope.freeze();
            return null;
          });
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.workers.execute(
          () -> {
            entered.countDown();
            waitFor(release);
          });
      waitFor(entered);
      try {
        var handle =
            h.nativeCall(() -> scope.submit(descriptor, OperationDescriptor.EntryPoint.COMMAND, 1));
        close.set(true);
        release.countDown();
        var stopped = await(handle.completion());
        assertEquals(OperationSnapshot.State.CANCELLED, stopped.state());
        assertFalse(stopped.started());
        assertEquals(0, calls.get());
      } finally {
        release.countDown();
      }
    }
  }
}
