package design.inherent.reform.reference;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ReferenceActionsTest {
  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static void entered(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failed);
    }
  }

  private static class Resolver implements ReferenceResolver<String, List<String>> {
    final ReferenceStamp stamp;
    final Function<String, ReferenceResolution<List<String>>> resolve;

    Resolver(ReferenceStamp stamp, Function<String, ReferenceResolution<List<String>>> resolve) {
      this.stamp = stamp;
      this.resolve = resolve;
    }

    @Override
    public ReferenceStamp stamp() {
      return stamp;
    }

    @Override
    public ReferenceResolution<List<String>> resolve(String key) {
      return resolve.apply(key);
    }

    @Override
    public ReferencePage<String> search(ReferenceQuery query) {
      return ReferencePage.unavailable(
          stamp, ReferencePage.Availability.UNAVAILABLE_CONTEXT, "No search index");
    }
  }

  @Test
  void unsupportedKeyMatchingIsExplicitAndDoesNotInvokeAnOlderResolver() throws Exception {
    try (var h = new Harness()) {
      var calls = new AtomicInteger();
      h.nativeCall(
          () ->
              h.actions.bind(
                  "legacy",
                  stamp ->
                      new Resolver(
                          stamp, key -> ReferenceResolution.resolved(stamp, List.of(), Map.of())) {
                        public ReferencePage<String> search(ReferenceQuery query) {
                          calls.incrementAndGet();
                          return super.search(query);
                        }
                      }));
      var unsupported =
          await(
              h.nativeCall(
                      () ->
                          h.actions.search(
                              EntryPoint.GUI,
                              new ReferenceQuery("fixture:", 1, ReferenceQuery.Match.KEY_PREFIX)))
                  .completion());
      assertEquals(OperationSnapshot.State.COMPLETED, unsupported.state());
      assertEquals(
          ReferencePage.Availability.UNSUPPORTED,
          unsupported.result().orElseThrow().availability());
      assertEquals(0, calls.get());
      var text =
          await(
              h.nativeCall(() -> h.actions.search(EntryPoint.GUI, new ReferenceQuery("", 1)))
                  .completion());
      assertEquals(
          ReferencePage.Availability.UNAVAILABLE_CONTEXT,
          text.result().orElseThrow().availability());
      assertEquals(1, calls.get());
      assertEquals(0, h.writeChecks.get());
    }
  }

  @Test
  void prefixProviderCannotReturnAnotherKeyOrReuseTextCursors() throws Exception {
    try (var h = new Harness()) {
      for (boolean wrongKey : List.of(true, false)) {
        var assigned =
            h.nativeCall(
                () ->
                    h.actions.bind(
                        "prefix",
                        stamp ->
                            new Resolver(
                                stamp,
                                key -> ReferenceResolution.resolved(stamp, List.of(), Map.of())) {
                              public boolean supports(ReferenceQuery.Match match) {
                                return true;
                              }

                              public ReferencePage<String> search(ReferenceQuery query) {
                                return wrongKey
                                    ? new ReferencePage<>(
                                        stamp,
                                        ReferencePage.Availability.READY,
                                        List.of(
                                            new ReferencePage.Choice<>("fixture:b", "B", Map.of())),
                                        Optional.empty(),
                                        1,
                                        true,
                                        "")
                                    : new ReferencePage<>(
                                        stamp,
                                        ReferencePage.Availability.READY,
                                        List.of(),
                                        Optional.of(
                                            new ReferenceQuery.Cursor(
                                                stamp.snapshot(), query.filter(), 1)),
                                        1,
                                        false,
                                        "");
                              }
                            }));
        var bad =
            await(
                h.nativeCall(
                        () ->
                            h.actions.search(
                                EntryPoint.GUI,
                                new ReferenceQuery(
                                    "fixture:a", 1, ReferenceQuery.Match.KEY_PREFIX)))
                    .completion());
        assertEquals(OperationSnapshot.State.FAILED, bad.state());
        assertTrue(bad.result().isEmpty());
        var cursor =
            await(
                h.nativeCall(
                        () ->
                            h.actions.search(
                                EntryPoint.GUI,
                                new ReferenceQuery(
                                    "fixture:a",
                                    1,
                                    Optional.of(
                                        new ReferenceQuery.Cursor(
                                            assigned.snapshot(), "fixture:a", 0)),
                                    ReferenceQuery.Match.KEY_PREFIX)))
                    .completion());
        assertEquals(OperationSnapshot.State.REFUSED, cursor.state());
        assertEquals("reference.cursor", cursor.report().issues().getFirst().code());
      }
    }
  }

  private static final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(2);
    final AtomicBoolean allowed = new AtomicBoolean(true);
    final AtomicInteger writeChecks = new AtomicInteger();
    final OwnerContext context;
    final OwnerContext.Access access;
    final ReferenceActions<String, List<String>> actions;
    final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);

    Harness() throws Exception {
      var nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(nativeThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      access =
          nativeCall(
              () ->
                  context.access(
                      allowed::get,
                      () -> {
                        writeChecks.incrementAndGet();
                        return false;
                      }));
      actions =
          nativeCall(
              () ->
                  new ReferenceActions<>(
                      context,
                      access,
                      DocumentId.parse("fixture:references"),
                      ValueTypes.text(128),
                      ValueTypes.list(ValueTypes.text(128), 16).snapshotPolicy(),
                      ReferenceIndexTest.LIMITS));
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    ReferenceStamp bind() throws Exception {
      return nativeCall(
          () ->
              actions.bind(
                  "same-label",
                  stamp ->
                      ReferenceIndexTest.index(
                          stamp,
                          List.of(
                              ReferenceIndexTest.entry("fixture:a", "a"),
                              ReferenceIndexTest.entry("fixture:b", "b")))));
    }

    OperationHandle<ReferenceResolution<List<String>>> resolve(String key) throws Exception {
      return nativeCall(() -> actions.resolve(EntryPoint.GUI, key));
    }

    ReferenceResolution<List<String>> value(String key) throws Exception {
      return await(resolve(key).completion()).result().orElseThrow();
    }

    ReferencePage<String> page(ReferenceQuery query) throws Exception {
      return await(nativeCall(() -> actions.search(EntryPoint.COMMAND, query)).completion())
          .result()
          .orElseThrow();
    }

    void blocked() throws Exception {
      nativeCall(
          () ->
              actions.bind(
                  "blocked",
                  stamp ->
                      new Resolver(
                          stamp,
                          key -> {
                            reached.countDown();
                            entered(release);
                            return ReferenceResolution.resolved(
                                stamp, List.of("private"), Map.of());
                          })));
    }

    @Override
    public void close() {
      release.countDown();
      try {
        if (context.isOpen())
          nativeCall(
              () -> {
                context.close();
                return null;
              });
      } catch (Exception failed) {
        throw new AssertionError(failed);
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
  void admittedReadOnlyServicesRetainResolutionKindsAndActualContextStamps() throws Exception {
    try (var h = new Harness()) {
      assertEquals(ReferenceResolution.Status.UNAVAILABLE_CONTEXT, h.value("fixture:a").status());
      var stamp = h.bind();
      var value = h.value("fixture:a");
      assertEquals(stamp, value.stamp());
      assertEquals(h.context.id(), value.stamp().context());
      assertEquals(List.of("fixture:a"), value.value().orElseThrow());
      assertEquals(
          ReferenceResolution.Status.UNKNOWN_REFERENCE, h.value("fixture:missing").status());
      assertEquals(ReferenceResolution.Status.MALFORMED, h.value("bad syntax").status());
      assertEquals(
          List.of("fixture:a", "fixture:b"),
          h.page(new ReferenceQuery("", 2)).choices().stream()
              .map(ReferencePage.Choice::key)
              .toList());
      h.nativeCall(
          () ->
              h.actions.invalidate(
                  "missing",
                  ReferencePage.Availability.MISSING_DEPENDENCY,
                  "Optional dependency is absent"));
      assertEquals(ReferenceResolution.Status.MISSING_DEPENDENCY, h.value("fixture:a").status());
      assertEquals(0, h.writeChecks.get());
    }
  }

  @Test
  void sameLabelReloadRejectsOldChoicesAndCursorsBeforeAProviderCanUseThem() throws Exception {
    try (var h = new Harness()) {
      var before = h.bind();
      var page = h.page(new ReferenceQuery("", 1));
      var after = h.bind();
      assertEquals(before.revision(), after.revision());
      assertNotEquals(before.snapshot(), after.snapshot());
      var request = new ReferenceActions.Request<>("fixture:a", Optional.of(before.snapshot()));
      var refused =
          await(h.nativeCall(() -> h.actions.resolve(EntryPoint.GUI, request)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertEquals("reference.stale", refused.report().issues().getFirst().code());
      assertTrue(refused.result().isEmpty());
      var stalePage =
          await(
              h.nativeCall(
                      () ->
                          h.actions.search(EntryPoint.GUI, new ReferenceQuery("", 1, page.next())))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, stalePage.state());
      assertEquals("reference.stale", stalePage.report().issues().getFirst().code());
      assertEquals(after, h.value("fixture:a").stamp());
      var currentPage = h.page(new ReferenceQuery("", 1));
      var badFilter =
          await(
              h.nativeCall(
                      () ->
                          h.actions.search(
                              EntryPoint.GUI,
                              new ReferenceQuery("different", 1, currentPage.next())))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, badFilter.state());
      assertEquals("reference.cursor", badFilter.report().issues().getFirst().code());
    }
  }

  @Test
  void aRealRunningLookupCannotDeliverOldDataAfterReplacement() throws Exception {
    try (var h = new Harness()) {
      h.blocked();
      var handle = h.resolve("fixture:a");
      entered(h.reached);
      assertTrue(handle.snapshot().started());
      h.bind();
      h.release.countDown();
      var result = await(handle.completion());
      assertEquals(OperationSnapshot.State.REFUSED, result.state());
      assertEquals("reference.stale", result.report().issues().getFirst().code());
      assertTrue(result.result().isEmpty());
      assertEquals(List.of("fixture:a"), h.value("fixture:a").value().orElseThrow());
    }
  }

  @Test
  void revokedReadPermissionIsCheckedAgainBeforeResultDelivery() throws Exception {
    try (var h = new Harness()) {
      h.blocked();
      var handle = h.resolve("fixture:a");
      entered(h.reached);
      h.allowed.set(false);
      h.release.countDown();
      var result = await(handle.completion());
      assertEquals(OperationSnapshot.State.REFUSED, result.state());
      assertEquals("owner.permission", result.report().issues().getFirst().code());
      assertTrue(result.result().isEmpty());
      h.allowed.set(true);
      assertEquals(List.of("private"), h.value("fixture:a").value().orElseThrow());
    }
  }

  @Test
  void cancellationAndContextCloseDrainWithoutPublishingThePrivateResult() throws Exception {
    try (var h = new Harness()) {
      h.blocked();
      var handle = h.resolve("fixture:a");
      entered(h.reached);
      assertTrue(await(h.nativeCall(handle::requestCancellation)).cancellationRequested());
      h.release.countDown();
      var result = await(handle.completion());
      assertEquals(OperationSnapshot.State.CANCELLED, result.state());
      assertTrue(result.result().isEmpty());
      assertEquals(List.of("private"), h.value("fixture:a").value().orElseThrow());
    }
    try (var h = new Harness()) {
      h.blocked();
      var handle = h.resolve("fixture:a");
      entered(h.reached);
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      h.release.countDown();
      var result = await(handle.completion());
      assertTrue(result.terminal());
      assertTrue(result.result().isEmpty());
      assertTrue(result.cancellationRequested());
    }
  }

  @Test
  void failedAndReentrantProducersCannotResurrectAnOldReadyIndex() throws Exception {
    try (var h = new Harness()) {
      h.bind();
      h.nativeCall(
          () -> {
            assertThrows(
                IllegalStateException.class,
                () ->
                    h.actions.bind(
                        "broken",
                        stamp -> {
                          throw new IllegalStateException("producer exhausted");
                        }));
            return null;
          });
      assertEquals(ReferenceResolution.Status.UNAVAILABLE_CONTEXT, h.value("fixture:a").status());
      h.nativeCall(
          () -> {
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.actions.bind(
                        "outer",
                        stamp -> {
                          h.actions.invalidate(
                              "newer", ReferencePage.Availability.MISSING_DEPENDENCY, "Absent");
                          return ReferenceIndexTest.index(
                              stamp, List.of(ReferenceIndexTest.entry("fixture:a", "a")));
                        }));
            return null;
          });
      var result = h.value("fixture:a");
      assertEquals(ReferenceResolution.Status.MISSING_DEPENDENCY, result.status());
      assertEquals("newer", result.stamp().revision());
    }
  }

  @Test
  void foreignStampedOrOversizedProviderResultsFailWithoutRetainingTheirPayload() throws Exception {
    try (var h = new Harness()) {
      h.nativeCall(
          () ->
              h.actions.bind(
                  "foreign",
                  stamp ->
                      new Resolver(
                          stamp,
                          key ->
                              ReferenceResolution.resolved(
                                  ReferenceIndexTest.stamp(), List.of("secret"), Map.of()))));
      var foreign = await(h.resolve("fixture:a").completion());
      assertEquals(OperationSnapshot.State.FAILED, foreign.state());
      assertTrue(foreign.result().isEmpty());
      assertFalse(foreign.report().issues().getFirst().fallback().contains("secret"));
      h.nativeCall(
          () ->
              h.actions.bind(
                  "large",
                  stamp ->
                      new Resolver(
                          stamp,
                          key ->
                              ReferenceResolution.resolved(
                                  stamp, List.of("secret"), Map.of("large", "x".repeat(8193))))));
      var large = await(h.resolve("fixture:a").completion());
      assertEquals(OperationSnapshot.State.FAILED, large.state());
      assertTrue(large.result().isEmpty());
    }
  }

  @Test
  void providerPagesMustAccountForTheirActualCursorAndCompleteIndexBudget() throws Exception {
    try (var h = new Harness()) {
      for (boolean jumps : List.of(true, false)) {
        var assigned =
            h.nativeCall(
                () ->
                    h.actions.bind(
                        "bad-accounting",
                        stamp ->
                            new Resolver(
                                stamp,
                                key -> ReferenceResolution.resolved(stamp, List.of(), Map.of())) {
                              @Override
                              public ReferencePage<String> search(ReferenceQuery query) {
                                return new ReferencePage<>(
                                    stamp,
                                    ReferencePage.Availability.READY,
                                    List.of(),
                                    jumps
                                        ? Optional.of(
                                            new ReferenceQuery.Cursor(
                                                stamp.snapshot(), query.filter(), 2))
                                        : Optional.empty(),
                                    1,
                                    !jumps,
                                    "");
                              }
                            }));
        var result =
            await(
                h.nativeCall(
                        () ->
                            h.actions.search(
                                EntryPoint.GUI,
                                new ReferenceQuery(
                                    "",
                                    1,
                                    jumps
                                        ? Optional.empty()
                                        : Optional.of(
                                            new ReferenceQuery.Cursor(
                                                assigned.snapshot(),
                                                "",
                                                ReferenceIndexTest.LIMITS.maximumEntries())))))
                    .completion());
        assertEquals(OperationSnapshot.State.FAILED, result.state());
        assertTrue(result.result().isEmpty());
      }
    }
  }

  @Test
  void asynchronousCompilationStaysUnavailableUntilFreshOwnerAdmission() throws Exception {
    try (var h = new Harness()) {
      var ready = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      CompletionStage<ReferenceStamp> binding;
      try {
        binding =
            h.nativeCall(
                () ->
                    h.actions.bindAsync(
                        "compiled",
                        stamp ->
                            h.context.onWorker(
                                () -> {
                                  assertNotEquals(
                                      Thread.currentThread(),
                                      h.nativeExecutor
                                          .submit(Thread::currentThread)
                                          .get(5, TimeUnit.SECONDS));
                                  ready.countDown();
                                  entered(release);
                                  return ReferenceIndexTest.index(
                                      stamp, List.of(ReferenceIndexTest.entry("fixture:a", "a")));
                                })));
        entered(ready);
        assertEquals(ReferenceResolution.Status.UNAVAILABLE_CONTEXT, h.value("fixture:a").status());
      } finally {
        release.countDown();
      }
      var stamp = await(binding);
      assertEquals("compiled", stamp.revision());
      assertEquals(stamp, h.value("fixture:a").stamp());
    }
  }

  @Test
  void lateAsynchronousProducersCannotOverwriteANewerBindingOrAClosedContext() throws Exception {
    try (var h = new Harness()) {
      var produced = new CompletableFuture<ReferenceResolver<String, List<String>>>();
      var stamp = new java.util.concurrent.atomic.AtomicReference<ReferenceStamp>();
      var old =
          h.nativeCall(
              () ->
                  h.actions.bindAsync(
                      "old",
                      assigned -> {
                        stamp.set(assigned);
                        return produced;
                      }));
      var newer = h.bind();
      produced.complete(
          ReferenceIndexTest.index(
              stamp.get(), List.of(ReferenceIndexTest.entry("fixture:a", "a"))));
      var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> await(old));
      assertInstanceOf(PolicyRefusal.class, failure.getCause());
      assertEquals(newer, h.value("fixture:a").stamp());
      var pending = new CompletableFuture<ReferenceResolver<String, List<String>>>();
      var closing =
          h.nativeCall(
              () ->
                  h.actions.bindAsync(
                      "closing",
                      assigned -> {
                        stamp.set(assigned);
                        return pending;
                      }));
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      pending.complete(
          ReferenceIndexTest.index(
              stamp.get(), List.of(ReferenceIndexTest.entry("fixture:a", "a"))));
      assertThrows(java.util.concurrent.ExecutionException.class, () -> await(closing));
    }
  }

  @Test
  void malformedInputsAndOffsetsAreRefusedThroughTheActualOperationServices() throws Exception {
    try (var h = new Harness()) {
      var stamp = h.bind();
      var tooLong =
          await(
              h.nativeCall(
                      () ->
                          h.actions.search(
                              EntryPoint.COMMAND, new ReferenceQuery("x".repeat(33), 1)))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, tooLong.state());
      var offset =
          await(
              h.nativeCall(
                      () ->
                          h.actions.search(
                              EntryPoint.COMMAND,
                              new ReferenceQuery(
                                  "",
                                  1,
                                  Optional.of(new ReferenceQuery.Cursor(stamp.snapshot(), "", 3)))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, offset.state());
      assertEquals("reference.cursor", offset.report().issues().getFirst().code());
      assertEquals(0, h.writeChecks.get());
    }
  }
}
