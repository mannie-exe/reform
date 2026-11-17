package design.inherent.reform.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OwnerContextTest {
  private static final class Manual implements Executor {
    private final ArrayDeque<Runnable> calls = new ArrayDeque<>();

    @Override
    public void execute(Runnable work) {
      calls.addLast(work);
    }

    void drain() {
      while (!calls.isEmpty()) calls.removeFirst().run();
    }
  }

  private static OwnerContext context(Executor owner, Executor worker, OwnerContext.Limits limits) {
    long thread = Thread.currentThread().threadId();
    return new OwnerContext(
        owner,
        worker,
        () -> {
          if (Thread.currentThread().threadId() != thread)
            throw new IllegalStateException("Wrong native owner thread");
        },
        limits);
  }

  private static String code(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    return ((PolicyRefusal) failure).report().issues().getFirst().code();
  }

  @Test
  void explicitExecutorsRunNativeAndIoCallsOnTheirDeclaredThreads() throws Exception {
    try (var nativeExecutor = Executors.newSingleThreadExecutor();
        var worker = Executors.newSingleThreadExecutor()) {
      long nativeThread = nativeExecutor.submit(() -> Thread.currentThread().threadId()).get();
      var context =
          new OwnerContext(
              nativeExecutor,
              worker,
              () -> {
                if (Thread.currentThread().threadId() != nativeThread)
                  throw new IllegalStateException("Wrong owner");
              },
              OwnerContext.Limits.STANDARD);
      assertEquals(
          nativeThread,
          context.onOwner(() -> Thread.currentThread().threadId()).toCompletableFuture().get());
      assertNotEquals(
          nativeThread,
          context.onWorker(() -> Thread.currentThread().threadId()).toCompletableFuture().get());
      assertEquals(0, context.outstandingCalls());
      nativeExecutor.submit(context::close).get();
      assertFalse(context.isOpen());
    }
  }

  @Test
  void closeRefusesQueuedWorkEvenWhenTheNativeExecutorWillNeverDrain() {
    var owner = new Manual();
    var worker = new Manual();
    var context = context(owner, worker, OwnerContext.Limits.STANDARD);
    var effects = new AtomicInteger();
    var first = context.onOwner(effects::incrementAndGet);
    var second = context.onWorker(effects::incrementAndGet);
    assertEquals(2, context.outstandingCalls());
    context.close();
    assertEquals(0, context.outstandingCalls());
    assertEquals(
        "owner.closed",
        code(assertThrows(CompletionException.class, () -> first.toCompletableFuture().join())));
    assertEquals(
        "owner.closed",
        code(assertThrows(CompletionException.class, () -> second.toCompletableFuture().join())));
    owner.drain();
    worker.drain();
    assertEquals(0, effects.get());
  }

  @Test
  void runningWorkerDrainsToItsActualResultAfterContextClosure() throws Exception {
    try (var worker = Executors.newSingleThreadExecutor()) {
      var context = context(Runnable::run, worker, OwnerContext.Limits.STANDARD);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var result =
          context.onWorker(
              () -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Fixture release missing");
                return "actual completed operation";
              });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      context.close();
      assertFalse(result.toCompletableFuture().isDone());
      assertEquals(1, context.outstandingCalls());
      release.countDown();
      assertEquals(
          "actual completed operation", result.toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertEquals(0, context.outstandingCalls());
    }
  }

  @Test
  void returnedFutureCancellationDoesNotPretendToCancelTheUnderlyingEffect() {
    var worker = new Manual();
    var context = context(Runnable::run, worker, OwnerContext.Limits.STANDARD);
    var effects = new AtomicInteger();
    var stage = context.onWorker(effects::incrementAndGet);
    assertTrue(stage.toCompletableFuture().cancel(true));
    worker.drain();
    assertEquals(1, stage.toCompletableFuture().join());
    assertEquals(1, effects.get());
    context.close();
  }

  @Test
  void admissionBoundsAreReleasedAfterCompletionAndAccessClosure() {
    var owner = new Manual();
    var worker = new Manual();
    var context = context(owner, worker, new OwnerContext.Limits(1, 1, 1));
    var first = context.onWorker(() -> 1);
    var refused = context.onWorker(() -> 2);
    assertEquals(
        "owner.busy",
        code(assertThrows(CompletionException.class, () -> refused.toCompletableFuture().join())));
    worker.drain();
    assertEquals(1, first.toCompletableFuture().join());
    var second = context.onWorker(() -> 2);
    worker.drain();
    assertEquals(2, second.toCompletableFuture().join());
    var access = context.access(() -> true, () -> false);
    assertEquals(
        "owner.busy",
        code(assertThrows(PolicyRefusal.class, () -> context.access(() -> true, () -> true))));
    access.close();
    assertTrue(context.access(() -> true, () -> true).isOpen());
    var closed = new AtomicInteger();
    context.onClose(closed::incrementAndGet);
    assertEquals(
        "owner.busy", code(assertThrows(PolicyRefusal.class, () -> context.onClose(() -> {}))));
    context.close();
    assertEquals(1, closed.get());
  }

  @Test
  void accessChecksTheCurrentRightAndRejectsReplacementOrClosure() {
    var context = context(Runnable::run, Runnable::run, OwnerContext.Limits.STANDARD);
    var read = new AtomicBoolean(true);
    var write = new AtomicBoolean(false);
    var access = context.access(read::get, write::get);
    access.require(OwnerContext.Right.READ);
    assertEquals(
        "owner.permission",
        code(assertThrows(PolicyRefusal.class, () -> access.require(OwnerContext.Right.WRITE))));
    write.set(true);
    access.require(OwnerContext.Right.WRITE);
    read.set(false);
    assertEquals(
        "owner.permission",
        code(assertThrows(PolicyRefusal.class, () -> access.require(OwnerContext.Right.READ))));
    access.close();
    assertEquals("owner.closed", code(assertThrows(PolicyRefusal.class, access::requireCurrent)));
    var calls = new AtomicInteger();
    var closing =
        context.access(
            () -> {
              calls.incrementAndGet();
              context.close();
              return true;
            },
            () -> true);
    assertEquals(
        "owner.closed",
        code(assertThrows(PolicyRefusal.class, () -> closing.require(OwnerContext.Right.READ))));
    assertEquals(1, calls.get());
    assertFalse(closing.isOpen());
  }

  @Test
  void rejectedOrDuplicatedExecutorDeliveryCannotLeakSlotsOrRunTwice() {
    var worker = new Manual();
    Executor rejected =
        work -> {
          worker.execute(work);
          throw new RejectedExecutionException("deliberate refusal");
        };
    var context = context(Runnable::run, rejected, OwnerContext.Limits.STANDARD);
    var effects = new AtomicInteger();
    var refused = context.onWorker(effects::incrementAndGet);
    assertThrows(CompletionException.class, () -> refused.toCompletableFuture().join());
    worker.drain();
    assertEquals(0, effects.get());
    assertEquals(0, context.outstandingCalls());
    context.close();
    var duplicate =
        context(
            Runnable::run,
            work -> {
              work.run();
              work.run();
            },
            OwnerContext.Limits.STANDARD);
    assertEquals(1, duplicate.onWorker(effects::incrementAndGet).toCompletableFuture().join());
    assertEquals(1, effects.get());
    assertEquals(0, duplicate.outstandingCalls());
    duplicate.close();
  }

  @Test
  void closeHooksContinueAndReleaseTheContextEvenWhenAHookFails() {
    var context = context(Runnable::run, Runnable::run, OwnerContext.Limits.STANDARD);
    var shared = new IllegalStateException("same cleanup failure");
    context.onClose(
        () -> {
          throw shared;
        });
    context.onClose(
        () -> {
          throw shared;
        });
    var finalHook = new AtomicBoolean();
    context.onClose(
        () -> {
          context.checkOwnerThread();
          finalHook.set(true);
        });
    assertSame(shared, assertThrows(IllegalStateException.class, context::close));
    assertTrue(finalHook.get());
    assertFalse(context.isOpen());
    assertEquals(
        "owner.closed",
        code(
            assertThrows(
                CompletionException.class,
                () -> context.onOwner(() -> 1).toCompletableFuture().join())));
  }

  @Test
  void closeDetachesEveryQueuedCallBeforeCompletingAnyObservers() {
    var owner = new Manual();
    var worker = new Manual();
    var context = context(owner, worker, OwnerContext.Limits.STANDARD);
    var observedOutstanding = new AtomicInteger();
    var completions = new AtomicInteger();
    for (int i = 0; i < 4; i++)
      context
          .onWorker(() -> 1)
          .whenComplete(
              (value, failure) -> {
                observedOutstanding.accumulateAndGet(context.outstandingCalls(), Math::max);
                completions.incrementAndGet();
                worker.drain();
              });
    context.close();
    assertEquals(4, completions.get());
    assertEquals(0, observedOutstanding.get());
    assertEquals(0, context.outstandingCalls());
  }

  @Test
  void evenErrorCleanupReleasesRemainingHooksAndNativeBindings() {
    var context = context(Runnable::run, Runnable::run, OwnerContext.Limits.STANDARD);
    var failed = new AssertionError("deliberate cleanup failure");
    var cleaned = new AtomicBoolean();
    context.onClose(
        () -> {
          throw failed;
        });
    context.onClose(() -> cleaned.set(true));
    assertSame(failed, assertThrows(AssertionError.class, context::close));
    assertTrue(cleaned.get());
    assertFalse(context.isOpen());
    assertEquals(
        "owner.closed", code(assertThrows(PolicyRefusal.class, context::checkOwnerThread)));
  }

  @Test
  void accessRequiresAnExplicitRightAndReportsUnavailableAuthorization() {
    var context = context(Runnable::run, Runnable::run, OwnerContext.Limits.STANDARD);
    var writes = new AtomicInteger();
    var access =
        context.access(
            () -> {
              throw new IllegalStateException("actual actor unavailable");
            },
            () -> writes.incrementAndGet() > 0);
    assertThrows(NullPointerException.class, () -> access.require(null));
    assertEquals(0, writes.get());
    var refusal = assertThrows(PolicyRefusal.class, () -> access.require(OwnerContext.Right.READ));
    assertEquals("owner.authorization_unavailable", code(refusal));
    assertFalse(refusal.report().complete());
    context.close();
  }

  @Test
  void reentrantCloseKeepsAnAlreadyRunningNativeResult() {
    var context = context(Runnable::run, Runnable::run, OwnerContext.Limits.STANDARD);
    assertEquals(
        "finished",
        context
            .onOwner(
                () -> {
                  context.close();
                  return "finished";
                })
            .toCompletableFuture()
            .join());
    assertEquals(0, context.outstandingCalls());
    assertFalse(context.isOpen());
  }
}
