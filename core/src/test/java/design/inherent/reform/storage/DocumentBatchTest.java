package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentBatchTest {
  @TempDir Path root;

  record Preferences(int count, String label) {}

  record Ceiling(int limit) {}

  static final DocumentId FIRST = DocumentId.parse("fixture:preferences"),
      SECOND = DocumentId.parse("fixture:ceiling");
  static final JsonLimits JSON = new JsonLimits(8192, 32, 1024, 512, 64, 1024);

  static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  static void held(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failed);
    }
  }

  @Test
  void actualOwnersPreflightCompleteTypedValuesBeforeOrderedSeparateWrites() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("8");
      var review = h.review();
      assertTrue(review.valid());
      assertEquals(List.of(FIRST, SECOND), review.values().documents());
      assertEquals(
          new Preferences(4, "original"), review.values().candidate(h.first).orElseThrow());
      assertEquals(new Ceiling(8), review.values().candidate(h.second).orElseThrow());
      assertEquals(
          new Preferences(4, "original"),
          review.values().candidate(h.a.definition()).orElseThrow());
      var foreignDefinition =
          new DocumentDefinition<>(
              FIRST, 1, h.a.definition().type(), () -> new Preferences(1, "original"));
      assertThrows(
          IllegalArgumentException.class, () -> review.values().candidate(foreignDefinition));
      assertTrue(h.nativeCall(() -> h.batch.submissionReadiness(review)).valid());
      assertThrows(
          IllegalArgumentException.class,
          () -> review.values().candidate(DocumentBatch.member(h.a, DocumentBatch.Intent.COMMIT)));
      assertNotEquals(
          review.entries().get(0).review().target().context(),
          review.entries().get(1).review().target().context());
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
      var ordered = h.nativeCall(() -> h.batch.commit(EntryPoint.GUI, review));
      var terminal = await(ordered.completion());
      var saved = terminal.result().orElseThrow();
      assertTrue(saved.completed());
      assertEquals(2, terminal.progress().completed());
      assertEquals(2, terminal.progress().total().orElseThrow());
      assertTrue(saved.report().valid());
      assertTrue(saved.currentPreflight().orElseThrow().valid());
      assertEquals(List.of("A", "B"), h.order);
      assertEquals(4, h.ownerA.handle().view().value().orElseThrow().count());
      assertEquals(8, h.ownerB.handle().view().value().orElseThrow().limit());
      assertEquals(2, h.crossChecks.get());
      for (var outcome : saved.outcomes()) {
        var op = outcome.operation().orElseThrow();
        assertEquals(OperationSnapshot.State.COMPLETED, op.state());
        var receipt = ((EditorActor.Commit<?>) op.result().orElseThrow()).receipt().orElseThrow();
        assertEquals(CommitReceipt.Persistence.SAVED, receipt.persistence());
        assertEquals(CommitReceipt.Application.APPLIED, receipt.application());
      }
      assertTrue(review.consumed());
      assertFalse(h.nativeCall(() -> h.batch.submissionReadiness(review)).valid());
      var repeated = h.nativeCall(() -> h.batch.commit(EntryPoint.GUI, review));
      var refused = await(repeated.completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertEquals("batch.review", refused.report().issues().getFirst().code());
      assertEquals(2, h.writesA.get());
      assertEquals(2, h.writesB.get());
      assertEquals(0, h.nativeValidators.get());
    }
  }

  @Test
  void invalidMemberAndCrossDocumentRulesCannotPartiallyCommit() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("-1");
      var invalid = h.review();
      assertFalse(invalid.valid());
      assertFalse(invalid.checksAttempted());
      assertTrue(invalid.values().candidate(h.second).isEmpty());
      assertFalse(invalid.entries().get(1).preflight().valid());
      var operation = h.nativeCall(() -> h.batch.commit(EntryPoint.GUI, invalid));
      assertEquals(OperationSnapshot.State.REFUSED, await(operation.completion()).state());
      assertEquals(0, h.crossChecks.get());
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
      h.editFirst("9");
      h.editSecond("5");
      var cross = h.review();
      assertTrue(cross.checksAttempted());
      assertFalse(cross.valid());
      assertTrue(cross.entries().stream().allMatch(entry -> entry.preflight().valid()));
      assertEquals(
          "fixture.order", cross.check().documents().get(FIRST).issues().getFirst().code());
      assertEquals(
          FieldPath.ROOT.field("count"),
          cross.check().documents().get(FIRST).issues().getFirst().path());
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
    }
  }

  @Test
  void changedDraftAndChangedSavedBytesAreRecheckedBeforeAnyEffect() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("8");
      var original = h.review();
      h.editSecond("9");
      var changed = h.commit(original);
      assertFalse(changed.completed());
      assertEquals("editor.review_changed", changed.report().issues().getFirst().code());
      assertEquals(
          SECOND.toString(), changed.report().issues().getFirst().arguments().get("document"));
      assertEquals(
          new Ceiling(9),
          changed.currentPreflight().orElseThrow().values().candidate(h.second).orElseThrow());
      assertTrue(changed.outcomes().stream().allMatch(outcome -> outcome.operation().isEmpty()));
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
      var observed = h.review();
      Files.writeString(h.pathB, "{\"schemaVersion\":1,\"values\":{\"limit\":12}}\n");
      var external = h.commit(observed);
      assertFalse(external.completed());
      assertFalse(external.currentPreflight().orElseThrow().entries().get(1).preflight().valid());
      assertEquals(
          "owner.revision",
          external
              .currentPreflight()
              .orElseThrow()
              .entries()
              .get(1)
              .preflight()
              .issues()
              .getFirst()
              .code());
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
      assertTrue(Files.readString(h.pathB).contains("12"));
    }
  }

  @Test
  void allWriteRightsAreCheckedBeforeFirstEffectAndAgainBetweenEffects() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("8");
      var before = h.review();
      h.writeB.set(false);
      var blocked = h.commit(before);
      assertFalse(blocked.completed());
      assertEquals("owner.permission", blocked.report().issues().getFirst().code());
      assertEquals(
          SECOND.toString(), blocked.report().issues().getFirst().arguments().get("document"));
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
      h.writeB.set(true);
      var after = h.review();
      h.afterA = () -> h.writeB.set(false);
      var partial = h.commit(after);
      assertFalse(partial.completed());
      var a = partial.outcomes().getFirst().operation().orElseThrow();
      assertEquals(
          CommitReceipt.Persistence.SAVED,
          ((EditorActor.Commit<?>) a.result().orElseThrow()).receipt().orElseThrow().persistence());
      assertTrue(partial.outcomes().get(1).operation().isEmpty());
      assertEquals(2, h.writesA.get());
      assertEquals(1, h.writesB.get());
      assertEquals(4, h.ownerA.handle().view().value().orElseThrow().count());
    }
  }

  @Test
  void cancellationDrainsEnteredWriteAndDoesNotStartTheNextOwner() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("8");
      var review = h.review();
      h.holdAfterA.set(true);
      var operation = h.nativeCall(() -> h.batch.commit(EntryPoint.GUI, review));
      held(h.entered);
      await(operation.requestCancellation());
      assertFalse(operation.completion().toCompletableFuture().isDone());
      h.release.countDown();
      var observed = await(operation.completion());
      assertTrue(observed.cancellationRequested());
      var result = observed.result().orElseThrow();
      assertFalse(result.completed());
      assertEquals("operation.cancelled", result.report().issues().getFirst().code());
      assertTrue(result.outcomes().getFirst().operation().isPresent());
      assertTrue(result.outcomes().get(1).operation().isEmpty());
      assertEquals(2, h.writesA.get());
      assertEquals(1, h.writesB.get());
    }
  }

  @Test
  void batchContextClosureKeepsActualReceiptAndDoesNotCloseBorrowedDocumentOwners()
      throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("8");
      var review = h.review();
      h.holdAfterA.set(true);
      var operation = h.nativeCall(() -> h.batch.commit(EntryPoint.GUI, review));
      held(h.entered);
      h.nativeCall(
          () -> {
            h.batchContext.close();
            return null;
          });
      h.release.countDown();
      var result = await(operation.completion()).result().orElseThrow();
      assertFalse(result.completed());
      assertTrue(h.contextA.isOpen());
      assertTrue(h.contextB.isOpen());
      assertTrue(h.a.isOpen());
      assertTrue(h.b.isOpen());
      assertEquals(
          CommitReceipt.Persistence.SAVED,
          ((EditorActor.Commit<?>)
                  result.outcomes().getFirst().operation().orElseThrow().result().orElseThrow())
              .receipt()
              .orElseThrow()
              .persistence());
      assertTrue(result.outcomes().get(1).operation().isEmpty());
      assertEquals(2, h.writesA.get());
      assertEquals(1, h.writesB.get());
    }
  }

  @Test
  void laterEditsSurviveAcknowledgementAndChangedFutureMemberStopsTheOrder() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("8");
      var review = h.review();
      h.holdAfterA.set(true);
      var operation = h.nativeCall(() -> h.batch.commit(EntryPoint.GUI, review));
      held(h.entered);
      h.editFirst("6");
      h.editSecond("7");
      h.release.countDown();
      var result = await(operation.completion()).result().orElseThrow();
      assertFalse(result.completed());
      assertEquals("editor.review_changed", result.report().issues().getFirst().code());
      assertEquals(
          SECOND.toString(), result.report().issues().getFirst().arguments().get("document"));
      assertEquals(
          "6",
          ((DraftNode.Scalar)
                  h.a.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field("count")))
              .raw());
      assertEquals(
          "7",
          ((DraftNode.Scalar)
                  h.b.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field("limit")))
              .raw());
      assertTrue(h.a.view().dirty());
      assertTrue(h.b.view().dirty());
      assertEquals(4, h.ownerA.handle().view().value().orElseThrow().count());
      assertEquals(10, h.ownerB.handle().view().value().orElseThrow().limit());
    }
  }

  @Test
  void unknownPersistenceRetainsTheActualOperationAndStopsFurtherWrites() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      h.editSecond("8");
      var review = h.review();
      h.damageAfterA.set(true);
      var result = h.commit(review);
      assertFalse(result.completed());
      var actual = result.outcomes().getFirst().operation().orElseThrow();
      var receipt = ((EditorActor.Commit<?>) actual.result().orElseThrow()).receipt().orElseThrow();
      assertEquals(CommitReceipt.Persistence.UNKNOWN, receipt.persistence());
      assertTrue(h.a.view().pending().isPresent());
      h.nativeCall(
          () -> {
            h.batch.requireRead();
            return null;
          });
      assertTrue(result.outcomes().get(1).operation().isEmpty());
      assertEquals(1, h.writesB.get());
      var repeated = h.nativeCall(() -> h.batch.commit(EntryPoint.GUI, review));
      assertEquals(OperationSnapshot.State.REFUSED, await(repeated.completion()).state());
      assertEquals(2, h.writesA.get());
    }
  }

  @Test
  void currentReadAuthorityIsRequiredForPreviouslyCapturedMaterial() throws Exception {
    try (var h = new Harness()) {
      h.editFirst("4");
      var review = h.review();
      h.nativeCall(
          () -> {
            h.batch.requireRead();
            return null;
          });
      h.readA.set(false);
      var denied =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () ->
                  h.nativeCall(
                      () -> {
                        h.batch.requireRead();
                        return null;
                      }));
      assertInstanceOf(PolicyRefusal.class, denied.getCause());
      var ready = h.nativeCall(() -> h.batch.submissionReadiness(review));
      assertFalse(ready.valid());
      assertEquals("owner.permission", ready.issues().getFirst().code());
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
    }
  }

  @Test
  void checkOnlyOwnersMustBeCleanAndNeverAcquireWriteAuthority() throws Exception {
    try (var h = new Harness()) {
      h.nativeCall(
          () -> {
            h.batch.close();
            h.batch =
                new DocumentBatch(
                    h.batchContext,
                    h.batchAccess,
                    DocumentId.parse("fixture:read_guard"),
                    List.of(h.first, DocumentBatch.member(h.b, DocumentBatch.Intent.CHECK)));
            return null;
          });
      h.writeB.set(false);
      h.editFirst("4");
      var result = h.commit(h.review());
      assertTrue(result.completed());
      assertEquals(DocumentBatch.Intent.CHECK, result.outcomes().get(1).intent());
      assertTrue(result.outcomes().get(1).operation().isEmpty());
      assertEquals(2, h.writesA.get());
      assertEquals(1, h.writesB.get());
      h.editSecond("9");
      var refused = await(h.nativeCall(() -> h.batch.review(EntryPoint.GUI)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertEquals("batch.guard_dirty", refused.report().issues().getFirst().code());
    }
  }

  @Test
  void externalEditToAnUnwrittenDependencyInvalidatesTheSavedPrefix() throws Exception {
    try (var h = new Harness()) {
      h.nativeCall(
          () -> {
            h.batch.close();
            h.batch =
                new DocumentBatch(
                    h.batchContext,
                    h.batchAccess,
                    DocumentId.parse("fixture:external_guard"),
                    List.of(h.first, DocumentBatch.member(h.b, DocumentBatch.Intent.CHECK)),
                    values -> {
                      assertEquals(DocumentBatch.Intent.COMMIT, values.intent(FIRST));
                      assertEquals(DocumentBatch.Intent.CHECK, values.intent(SECOND));
                      return DocumentBatch.Check.VALID;
                    },
                    DocumentBatch.Limits.STANDARD);
            return null;
          });
      h.editFirst("4");
      var review = h.review();
      h.afterA =
          () -> {
            try {
              Files.writeString(h.pathB, "{\"schemaVersion\":1,\"values\":{\"limit\":2}}");
            } catch (IOException failure) {
              throw new UncheckedIOException(failure);
            }
          };
      var result = h.commit(review);
      assertFalse(result.completed());
      var problem = result.report().issues().getFirst();
      assertEquals("owner.revision", problem.code());
      assertEquals(SECOND.toString(), problem.arguments().get("document"));
      assertEquals(2, h.writesA.get());
      assertEquals(1, h.writesB.get());
      assertTrue(result.outcomes().getFirst().operation().isPresent());
      assertTrue(result.outcomes().getLast().operation().isEmpty());
      assertEquals(
          "owner.revision", result.outcomes().getLast().report().issues().getFirst().code());
      assertEquals(4, h.ownerA.handle().view().value().orElseThrow().count());
    }
  }

  @Test
  void ownerChoosesSafeWriteOrderFromVerifiedBaselines() throws Exception {
    try (var h = new Harness()) {
      h.replaceValidator(
          values -> {
            var current = values.baseline(h.b.definition()).orElseThrow();
            var desired = values.candidate(h.first).orElseThrow();
            assertEquals(new Ceiling(10), current);
            assertEquals(DocumentBatch.Intent.COMMIT, values.intent(SECOND));
            assertEquals(15, desired.count());
            assertEquals(new Ceiling(17), values.candidate(h.second).orElseThrow());
            return DocumentBatch.Check.VALID.withPlan(
                new DocumentBatch.Plan(List.of(SECOND, FIRST), Set.of(FIRST, SECOND)));
          });
      h.editFirst("15");
      h.editSecond("17");
      var review = h.review();
      assertEquals(List.of(FIRST, SECOND), h.nativeCall(() -> h.batch.documents()));
      assertEquals(
          List.of(SECOND, FIRST),
          review.entries().stream().map(DocumentBatch.Entry::document).toList());
      assertTrue(h.commit(review).completed());
      assertEquals(List.of("B", "A"), h.order);
      assertEquals(15, h.ownerA.handle().view().value().orElseThrow().count());
      assertEquals(17, h.ownerB.handle().view().value().orElseThrow().limit());
    }
  }

  @Test
  void changedOrIncompleteWriteOrdersRequireFreshReviewWithoutSaving() throws Exception {
    try (var h = new Harness()) {
      var reverse = new AtomicBoolean();
      h.replaceValidator(
          values ->
              DocumentBatch.Check.VALID.withPlan(
                  new DocumentBatch.Plan(
                      reverse.get() ? List.of(SECOND, FIRST) : List.of(FIRST, SECOND),
                      Set.of(FIRST, SECOND))));
      h.editFirst("4");
      h.editSecond("8");
      var review = h.review();
      reverse.set(true);
      var result = h.commit(review);
      assertFalse(result.completed());
      assertEquals("batch.plan_changed", result.report().issues().getFirst().code());
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());

      h.replaceValidator(
          values ->
              DocumentBatch.Check.VALID.withPlan(
                  new DocumentBatch.Plan(List.of(FIRST, FIRST), Set.of(SECOND))));
      var refused = await(h.nativeCall(() -> h.batch.review(EntryPoint.GUI)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertEquals("batch.plan", refused.report().issues().getFirst().code());
    }
  }

  @Test
  void completeReviewBudgetsAndUnknownDiagnosticTargetsRefuseWithoutWrites() throws Exception {
    try (var h = new Harness()) {
      h.nativeCall(
          () -> {
            h.batch.close();
            h.batch =
                new DocumentBatch(
                    h.batchContext,
                    h.batchAccess,
                    DocumentId.parse("fixture:small"),
                    List.of(h.first, h.second),
                    ignored -> DocumentBatch.Check.VALID,
                    new DocumentBatch.Limits(2, 1, 1, 16));
            return null;
          });
      var tooLarge = await(h.nativeCall(() -> h.batch.review(EntryPoint.GUI)).completion());
      assertEquals("batch.size", tooLarge.report().issues().getFirst().code());
      h.nativeCall(
          () -> {
            h.batch.close();
            h.batch =
                new DocumentBatch(
                    h.batchContext,
                    h.batchAccess,
                    DocumentId.parse("fixture:unknown"),
                    List.of(h.first, h.second),
                    ignored ->
                        new DocumentBatch.Check(
                            Map.of(DocumentId.parse("foreign:other"), ValidationReport.VALID),
                            true),
                    DocumentBatch.Limits.STANDARD);
            return null;
          });
      var unknown = await(h.nativeCall(() -> h.batch.review(EntryPoint.GUI)).completion());
      assertEquals("batch.diagnostic_target", unknown.report().issues().getFirst().code());
      assertEquals(1, h.writesA.get());
      assertEquals(1, h.writesB.get());
    }
  }

  final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(4);
    final AtomicBoolean readA = new AtomicBoolean(true),
        writeA = new AtomicBoolean(true),
        writeB = new AtomicBoolean(true);
    final AtomicBoolean holdAfterA = new AtomicBoolean(), damageAfterA = new AtomicBoolean();
    final AtomicInteger writesA = new AtomicInteger(),
        writesB = new AtomicInteger(),
        crossChecks = new AtomicInteger(),
        nativeValidators = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
    final Path pathA = root.resolve("a.json"), pathB = root.resolve("b.json");
    final long nativeThread;
    final OwnerContext contextA, contextB, batchContext;
    final OwnerContext.Access batchAccess;
    final FileOwner<Preferences> ownerA;
    final FileOwner<Ceiling> ownerB;
    final DocumentActions<Preferences> a;
    final DocumentActions<Ceiling> b;
    final DocumentBatch.Member<Preferences> first;
    final DocumentBatch.Member<Ceiling> second;
    volatile Runnable afterA = () -> {};
    DocumentBatch batch;

    Harness() throws Exception {
      nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      contextA = context();
      contextB = context();
      batchContext = context();
      var typeA =
          RecordTypes.record(MethodHandles.lookup(), Preferences.class)
              .field("count", FieldKey.of("count", ValueTypes.integer(0, 100)))
              .build();
      var typeB =
          RecordTypes.record(MethodHandles.lookup(), Ceiling.class)
              .field("limit", FieldKey.of("limit", ValueTypes.integer(0, 100)))
              .build();
      var defA =
          new DocumentDefinition<>(
              FIRST, 1, typeA, () -> new Preferences(1, "original"), value -> validate());
      var defB =
          new DocumentDefinition<>(SECOND, 1, typeB, () -> new Ceiling(10), value -> validate());
      var storeA =
          new JsonStore<>(
              defA,
              new OwnedFile(
                  StorageBinding.relative(root, "a.json"),
                  8192,
                  4096,
                  () -> {
                    int count = writesA.incrementAndGet();
                    if (count > 1) {
                      order.add("A");
                      afterA.run();
                    }
                    if (holdAfterA.get()) {
                      entered.countDown();
                      held(release);
                    }
                    if (damageAfterA.get()) {
                      Files.writeString(pathA, "{foreign broken input");
                      throw new IOException("Foreign modification after replacement");
                    }
                  }),
              JsonStore.Format.NEW_ENVELOPE,
              JSON,
              DraftLimits.CONFIGURATION);
      var storeB =
          new JsonStore<>(
              defB,
              new OwnedFile(
                  StorageBinding.relative(root, "b.json"),
                  8192,
                  4096,
                  () -> {
                    if (writesB.incrementAndGet() > 1) order.add("B");
                  }),
              JsonStore.Format.NEW_ENVELOPE,
              JSON,
              DraftLimits.CONFIGURATION);
      ownerA = nativeCall(() -> new FileOwner<>(contextA, storeA, 16));
      ownerB = nativeCall(() -> new FileOwner<>(contextB, storeB, 16));
      var accessA = nativeCall(() -> contextA.access(readA::get, writeA::get));
      var accessB = nativeCall(() -> contextB.access(() -> true, writeB::get));
      var sessionA = nativeCall(() -> ownerA.open(accessA));
      var sessionB = nativeCall(() -> ownerB.open(accessB));
      await(sessionA.initialize());
      await(sessionB.initialize());
      a =
          nativeCall(
              () ->
                  new DocumentActions<>(
                      contextA,
                      accessA,
                      new EditorActor<>(
                          contextA, sessionA, defA, JSON, DraftLimits.CONFIGURATION, 64)));
      b =
          nativeCall(
              () ->
                  new DocumentActions<>(
                      contextB,
                      accessB,
                      new EditorActor<>(
                          contextB, sessionB, defB, JSON, DraftLimits.CONFIGURATION, 64)));
      first = DocumentBatch.member(a, DocumentBatch.Intent.COMMIT);
      second = DocumentBatch.member(b, DocumentBatch.Intent.COMMIT);
      batchAccess = nativeCall(() -> batchContext.access(() -> true, () -> true));
      batch =
          nativeCall(
              () ->
                  new DocumentBatch(
                      batchContext,
                      batchAccess,
                      DocumentId.parse("fixture:batch"),
                      List.of(first, second),
                      values -> {
                        crossChecks.incrementAndGet();
                        var preferences = values.candidate(first).orElseThrow();
                        var ceiling = values.candidate(second).orElseThrow();
                        return preferences.count() <= ceiling.limit()
                            ? DocumentBatch.Check.VALID
                            : new DocumentBatch.Check(
                                Map.of(
                                    FIRST,
                                    new ValidationReport(
                                        List.of(
                                            Issue.error(
                                                "fixture.order",
                                                FieldPath.ROOT.field("count"),
                                                "Count exceeds the other document's limit")),
                                        true)),
                                true);
                      },
                      DocumentBatch.Limits.STANDARD));
      run(() -> a.ensureLoaded(EntryPoint.GUI));
      run(() -> b.ensureLoaded(EntryPoint.GUI));
    }

    ValidationReport validate() {
      if (Thread.currentThread().threadId() == nativeThread) nativeValidators.incrementAndGet();
      return ValidationReport.VALID;
    }

    OwnerContext context() {
      return new OwnerContext(
          nativeExecutor,
          workers,
          () -> {
            if (Thread.currentThread().threadId() != nativeThread)
              throw new IllegalStateException("Wrong native thread");
          },
          OwnerContext.Limits.STANDARD);
    }

    <T> T nativeCall(Callable<T> call) throws Exception {
      return nativeExecutor.submit(call).get(5, TimeUnit.SECONDS);
    }

    <T> T run(Callable<OperationHandle<T>> call) throws Exception {
      return await(DocumentActions.result(nativeCall(call)));
    }

    void editFirst(String value) throws Exception {
      run(() -> a.edit(EntryPoint.GUI, "count", value));
    }

    void editSecond(String value) throws Exception {
      run(() -> b.edit(EntryPoint.GUI, "limit", value));
    }

    DocumentBatch.Review review() throws Exception {
      return run(() -> batch.review(EntryPoint.GUI));
    }

    void replaceValidator(DocumentBatch.Validator validator) throws Exception {
      nativeCall(
          () -> {
            batch.close();
            batch =
                new DocumentBatch(
                    batchContext,
                    batchAccess,
                    DocumentId.parse("fixture:batch"),
                    List.of(first, second),
                    validator,
                    DocumentBatch.Limits.STANDARD);
            return null;
          });
    }

    DocumentBatch.Result commit(DocumentBatch.Review review) throws Exception {
      return run(() -> batch.commit(EntryPoint.GUI, review));
    }

    @Override
    public void close() {
      release.countDown();
      try {
        nativeCall(
            () -> {
              if (batchContext.isOpen()) batchContext.close();
              if (contextA.isOpen()) contextA.close();
              if (contextB.isOpen()) contextB.close();
              return null;
            });
      } catch (Exception failed) {
        if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
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
}
