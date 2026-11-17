package design.inherent.reform.owner;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RuntimePolicyAsyncTest {
  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final AtomicBoolean block = new AtomicBoolean(), authorized = new AtomicBoolean(true);
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final OwnerContext context;
    final RuntimePolicy<Integer> policy;
    final Candidate<Integer> two, three;

    Harness() throws Exception {
      long nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      var definition =
          new DocumentDefinition<>(
              DocumentId.parse("fixture:async"),
              1,
              ValueTypes.integer(0, 100),
              () -> 1,
              value -> {
                if (value == 2 && block.get()) {
                  assertNotEquals(nativeThread, Thread.currentThread().threadId());
                  entered.countDown();
                  waitFor(release);
                }
                return ValidationReport.VALID;
              });
      two = RuntimePolicyTest.candidate(definition, 2);
      three = RuntimePolicyTest.candidate(definition, 3);
      var one = RuntimePolicyTest.candidate(definition, 1);
      context =
          new OwnerContext(
              nativeExecutor,
              worker,
              () -> assertEquals(nativeThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      policy = nativeCall(() -> new RuntimePolicy<>(definition, context::checkOwnerThread));
      nativeCall(
          () -> {
            context.onClose(policy::close);
            return null;
          });
      await(policy.publishAsync(context, policy.handle().view().revision(), one, authorized::get));
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    CompletionStage<SettingsHandle.View<Integer>> beginBlocked() {
      block.set(true);
      var pending =
          policy.publishAsync(context, policy.handle().view().revision(), two, authorized::get);
      waitFor(entered);
      return pending;
    }

    @Override
    public void close() {
      release.countDown();
      try {
        nativeCall(
            () -> {
              context.close();
              return null;
            });
      } catch (Exception failure) {
        throw new AssertionError("Fixture close failed", failure);
      } finally {
        worker.shutdown();
        nativeExecutor.shutdown();
        try {
          assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
          assertTrue(nativeExecutor.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
    }
  }

  private static String failureCode(CompletionStage<?> operation) {
    var failed = assertThrows(ExecutionException.class, () -> await(operation));
    return ((PolicyRefusal) failed.getCause()).report().issues().getFirst().code();
  }

  @Test
  void aNewFailedObservationCannotBeErasedByAnOlderWorkerPublication() throws Exception {
    try (var h = new Harness()) {
      var pending = h.beginBlocked();
      var report =
          new ValidationReport(
              List.of(Issue.error("load.failed", FieldPath.ROOT, "New saved data is invalid")),
              true);
      var before = h.policy.handle().view();
      h.nativeCall(
          () -> {
            h.policy.observationFailed(report);
            return null;
          });
      assertSame(before.revision(), h.policy.handle().view().revision());
      h.release.countDown();
      assertEquals("owner.observation_changed", failureCode(pending));
      assertEquals(1, h.policy.handle().view().value().orElseThrow());
      assertSame(report, h.policy.handle().view().observationReport());
    }
  }

  @Test
  void newerPublicationWinsOverAnOlderWorkerResult() throws Exception {
    try (var h = new Harness()) {
      var pending = h.beginBlocked();
      h.nativeCall(
          () -> {
            h.policy
                .prepare(h.policy.handle().view().revision(), h.three, h.authorized::get)
                .publish(h.authorized::get);
            return null;
          });
      h.release.countDown();
      assertEquals("owner.revision", failureCode(pending));
      assertEquals(3, h.policy.handle().view().value().orElseThrow());
    }
  }

  @Test
  void permissionIsCheckedAgainAfterWorkerPreparation() throws Exception {
    try (var h = new Harness()) {
      var pending = h.beginBlocked();
      h.authorized.set(false);
      h.release.countDown();
      assertEquals("owner.permission", failureCode(pending));
      assertEquals(1, h.policy.handle().view().value().orElseThrow());
    }
  }

  @Test
  void closeDuringWorkerPreparationDrainsWithoutRevivingTheRuntime() throws Exception {
    try (var h = new Harness()) {
      var pending = h.beginBlocked();
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      assertFalse(pending.toCompletableFuture().isDone());
      h.release.countDown();
      assertEquals("owner.closed", failureCode(pending));
      assertEquals(SettingsHandle.Availability.CLOSED, h.policy.handle().view().availability());
      assertTrue(h.policy.handle().view().value().isEmpty());
    }
  }
}
