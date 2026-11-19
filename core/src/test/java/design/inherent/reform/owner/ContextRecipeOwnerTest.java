package design.inherent.reform.owner;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ContextRecipeOwnerTest {
  record Recipe(int amount) {}

  record Creation(Recipe recipe, Object untouchedDimensions) {}

  record Inputs(List<Integer> values) {}

  static final FieldKey<Integer> AMOUNT = FieldKey.of("amount", ValueTypes.integer(0, 10));
  static final DocumentDefinition<Recipe> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:recipe"),
          1,
          RecordType.<Recipe>builder(v -> new Recipe(v.get(AMOUNT)))
              .field(AMOUNT, Recipe::amount)
              .build(),
          () -> new Recipe(1));
  static final JsonLimits JSON = new JsonLimits(8192, 32, 1024, 512, 64, 1024);

  static <T> T await(CompletionStage<T> value) throws Exception {
    return value.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  static void waitFor(CountDownLatch value) {
    try {
      assertTrue(value.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failed);
    }
  }

  final class Harness implements AutoCloseable {
    final java.util.concurrent.ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(2);
    final long nativeThread;
    final AtomicBoolean read = new AtomicBoolean(true),
        write = new AtomicBoolean(true),
        holdCompile = new AtomicBoolean();
    final AtomicReference<Runnable> atWriteCheck = new AtomicReference<>(() -> {}),
        afterAdmission = new AtomicReference<>(() -> {});
    final AtomicReference<Creation> current =
        new AtomicReference<>(new Creation(new Recipe(2), new Object()));
    final AtomicInteger compilations = new AtomicInteger(), admissions = new AtomicInteger();
    final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
    final OwnerContext context;
    final OwnerContext.Access access;
    final ContextRecipeOwner<Recipe, Creation, Inputs> owner;
    final ContextRecipeOwner<Recipe, Creation, Inputs>.Session session;
    final EditorActor<Recipe> actor;
    final DocumentActions<Recipe> actions;
    volatile List<Integer> lastCompiled;
    volatile Inputs runtime;

    Harness() throws Exception {
      nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(nativeThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      var domain =
          new ContextRecipeOwner.Domain<Recipe, Creation, Inputs>() {
            void nativeOnly() {
              assertEquals(nativeThread, Thread.currentThread().threadId());
            }

            @Override
            public Creation current() {
              nativeOnly();
              return current.get();
            }

            @Override
            public Recipe read(Creation original) {
              nativeOnly();
              return original.recipe();
            }

            @Override
            public Inputs compile(Creation original, Recipe recipe) {
              assertNotEquals(nativeThread, Thread.currentThread().threadId());
              compilations.incrementAndGet();
              assertNotNull(original.untouchedDimensions());
              if (holdCompile.get()) {
                reached.countDown();
                waitFor(release);
              }
              lastCompiled = new ArrayList<>(List.of(recipe.amount() * 2));
              return new Inputs(lastCompiled);
            }

            @Override
            public Creation admit(Creation original, Inputs prepared) {
              nativeOnly();
              assertSame(current.get(), original);
              admissions.incrementAndGet();
              runtime = prepared;
              var admitted =
                  new Creation(
                      new Recipe(prepared.values().getFirst() / 2), original.untouchedDimensions());
              current.set(admitted);
              afterAdmission.get().run();
              return admitted;
            }
          };
      owner =
          nativeCall(
              () ->
                  new ContextRecipeOwner<>(
                      context,
                      DEFINITION,
                      current.get(),
                      domain,
                      (inputs, budget) -> new Inputs(List.copyOf(inputs.values())),
                      JSON,
                      DraftLimits.CONFIGURATION,
                      16));
      access =
          nativeCall(
              () ->
                  context.access(
                      read::get,
                      () -> {
                        atWriteCheck.get().run();
                        return write.get();
                      }));
      session = nativeCall(() -> owner.open(access));
      actor =
          nativeCall(
              () ->
                  new EditorActor<>(
                      context, session, DEFINITION, JSON, DraftLimits.CONFIGURATION, 32));
      actions = nativeCall(() -> new DocumentActions<>(context, access, actor));
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    <T> T run(Supplier<OperationHandle<T>> operation) throws Exception {
      return await(DocumentActions.result(nativeCall(operation::get)));
    }

    void load() throws Exception {
      run(() -> actions.load(EntryPoint.GUI));
    }

    void edit(String value) throws Exception {
      run(() -> actions.edit(EntryPoint.GUI, "amount", value));
    }

    DraftTree draft(int value) {
      return DraftTree.from(DEFINITION.type(), new Recipe(value), DraftLimits.CONFIGURATION);
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

  @Test
  void reviewedContextCommitRefusesStaleDraftBeforeDomainCompilation() throws Exception {
    try (var h = new Harness()) {
      h.load();
      h.edit("4");
      var reviewed = h.run(() -> h.actions.review(EntryPoint.GUI));
      h.edit("5");
      int compilations = h.compilations.get();
      var stopped =
          await(
              h.nativeCall(() -> h.actions.commit(EntryPoint.GUI, reviewed.target())).completion());
      assertEquals(OperationSnapshot.State.REFUSED, stopped.state());
      assertEquals("editor.review_changed", stopped.report().issues().getFirst().code());
      assertEquals(compilations, h.compilations.get());
      assertEquals(0, h.admissions.get());
      assertTrue(h.actor.view().dirty());
      assertTrue(h.actor.view().pending().isEmpty());
      var fresh = h.run(() -> h.actions.review(EntryPoint.COMMAND));
      var result = h.run(() -> h.actions.commit(EntryPoint.COMMAND, fresh.target()));
      var receipt = result.receipt().orElseThrow();
      assertEquals(CommitReceipt.Persistence.NOT_ATTEMPTED, receipt.persistence());
      assertTrue(receipt.acknowledgesBaseline());
      assertEquals(1, h.admissions.get());
      assertEquals(5, h.current.get().recipe().amount());
      assertEquals(List.of(10), h.runtime.values());
      assertFalse(h.actor.view().dirty());
    }
  }

  @Test
  void contextAdmissionHasNoPersistenceClaimAndCompiledInputsAreOwned() throws Exception {
    try (var h = new Harness()) {
      var untouched = h.current.get().untouchedDimensions();
      h.load();
      h.edit("5");
      assertEquals(DocumentSession.CommitKind.CONTEXT, h.actions.commitKind());
      var explanation = h.run(() -> h.actions.explain(EntryPoint.COMMAND));
      assertEquals("creation-context", explanation.provenance().kind());
      assertTrue(explanation.provenance().location().isEmpty());
      assertTrue(explanation.effective().isEmpty());
      assertEquals(
          java.util.Set.of(ApplicationEffects.Timing.NEW_WORLD),
          explanation.applicationEffects().forField(FieldPath.ROOT).orElseThrow().timings());
      var source = await(h.session.read());
      var prepared = await(h.session.prepare(source, h.draft(5)));
      h.lastCompiled.set(0, 20);
      var applied = await(h.session.commit(prepared));
      assertEquals(CommitReceipt.Persistence.NOT_ATTEMPTED, applied.persistence());
      assertTrue(applied.savedRevision().isEmpty());
      assertEquals(CommitReceipt.Application.APPLIED, applied.application());
      assertTrue(applied.acknowledgesBaseline());
      assertFalse(applied.baselineOutcomeUnknown());
      assertEquals(5, h.current.get().recipe().amount());
      assertSame(untouched, h.current.get().untouchedDimensions());
      assertEquals(List.of(10), h.runtime.values());
      assertThrows(UnsupportedOperationException.class, () -> h.runtime.values().add(99));
      assertEquals(
          CommitReceipt.RequirementKind.NEW_WORLD, applied.requirements().getFirst().kind());
      assertNotEquals(
          source.document().revision(), applied.observation().orElseThrow().document().revision());
    }
  }

  @Test
  void sharedAdmitAcknowledgesAcceptedContextAndPreservesLaterTyping() throws Exception {
    try (var h = new Harness()) {
      h.load();
      h.edit("5");
      h.holdCompile.set(true);
      var operation = h.nativeCall(() -> h.actions.admit(EntryPoint.GUI));
      waitFor(h.reached);
      h.edit("7");
      h.release.countDown();
      var committed = await(DocumentActions.result(operation));
      var receipt = committed.receipt().orElseThrow();
      assertEquals(CommitReceipt.Persistence.NOT_ATTEMPTED, receipt.persistence());
      assertEquals(CommitReceipt.Application.APPLIED, receipt.application());
      assertTrue(committed.editorReport().valid());
      assertTrue(h.actions.view().pending().isEmpty());
      assertTrue(h.actions.view().dirty());
      assertEquals(
          "7",
          ((DraftNode.Scalar)
                  h.actions
                      .view()
                      .snapshot()
                      .orElseThrow()
                      .draft()
                      .at(FieldPath.ROOT.field("amount")))
              .raw());
      assertEquals(5, h.current.get().recipe().amount());
      assertEquals(List.of(10), h.runtime.values());
      assertEquals(
          OperationSnapshot.State.REFUSED,
          await(h.nativeCall(() -> h.actions.save(EntryPoint.COMMAND)).completion()).state());
      assertTrue(h.actions.view().dirty());
      assertEquals(1, h.admissions.get());
    }
  }

  @Test
  void readonlyValidationAndSameValueForeignContextNeverGrantAdmission() throws Exception {
    try (var h = new Harness()) {
      h.load();
      h.edit("4");
      h.write.set(false);
      assertTrue(h.run(() -> h.actions.validate(EntryPoint.COMMAND)).report().valid());
      assertEquals(1, h.compilations.get());
      assertEquals(0, h.admissions.get());
      h.nativeCall(
          () -> {
            assertThrows(PolicyRefusal.class, () -> h.actions.admit(EntryPoint.COMMAND));
            return null;
          });
      h.write.set(true);
      var source = await(h.session.read());
      var prepared = await(h.session.prepare(source, h.draft(4)));
      var original = h.current.get();
      var replacement = new Creation(original.recipe(), original.untouchedDimensions());
      assertEquals(original, replacement);
      assertNotSame(original, replacement);
      h.nativeCall(
          () -> {
            h.current.set(replacement);
            return null;
          });
      var refused = await(h.session.commit(prepared));
      assertEquals(CommitReceipt.Application.REFUSED, refused.application());
      assertFalse(refused.admissionEntered());
      assertFalse(refused.baselineOutcomeUnknown());
      assertEquals(0, h.admissions.get());
      assertTrue(
          refused.applicationReport().issues().stream()
              .anyMatch(i -> i.code().equals("owner.revision")));
    }
  }

  @Test
  void deniedProposalIsConsumedAndReentrantAuthorizationCannotReplaceOriginalContext()
      throws Exception {
    try (var h = new Harness()) {
      var source = await(h.session.read());
      var prepared = await(h.session.prepare(source, h.draft(4)));
      h.write.set(false);
      assertEquals(
          CommitReceipt.Application.REFUSED, await(h.session.commit(prepared)).application());
      h.write.set(true);
      var consumed =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> await(h.session.commit(prepared)));
      assertInstanceOf(PolicyRefusal.class, consumed.getCause());
      var fresh = await(h.session.prepare(source, h.draft(4)));
      h.atWriteCheck.set(
          () -> {
            var old = h.current.get();
            h.current.set(new Creation(old.recipe(), old.untouchedDimensions()));
          });
      var refused = await(h.session.commit(fresh));
      assertEquals(CommitReceipt.Application.REFUSED, refused.application());
      assertFalse(refused.admissionEntered());
      assertEquals(0, h.admissions.get());
    }
  }

  @Test
  void callbackFailureAfterMutationIsUnknownAndDoesNotInviteBlindRetry() throws Exception {
    try (var h = new Harness()) {
      h.load();
      h.edit("3");
      h.afterAdmission.set(
          () -> {
            throw new IllegalStateException("deliberate failure after native change");
          });
      var committed = h.run(() -> h.actions.admit(EntryPoint.GUI));
      var receipt = committed.receipt().orElseThrow();
      assertEquals(3, h.current.get().recipe().amount());
      assertEquals(CommitReceipt.Persistence.NOT_ATTEMPTED, receipt.persistence());
      assertEquals(CommitReceipt.Application.UNAVAILABLE, receipt.application());
      assertTrue(receipt.baselineOutcomeUnknown());
      assertEquals(EditSession.PendingState.UNKNOWN, h.actions.view().pending().orElseThrow());
      assertTrue(h.actions.view().dirty());
      var rejected = await(h.nativeCall(() -> h.actions.admit(EntryPoint.COMMAND)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, rejected.state());
      assertEquals(1, h.admissions.get());
      assertThrows(java.util.concurrent.ExecutionException.class, () -> await(h.session.read()));
    }
  }

  @Test
  void cancellationAfterCompilationStartsNeverAdmitsAndRetainsDraft() throws Exception {
    try (var h = new Harness()) {
      h.load();
      h.edit("5");
      h.holdCompile.set(true);
      var operation = h.nativeCall(() -> h.actions.admit(EntryPoint.GUI));
      waitFor(h.reached);
      await(operation.requestCancellation());
      h.release.countDown();
      var stopped = await(operation.completion());
      assertEquals(OperationSnapshot.State.CANCELLED, stopped.state());
      assertTrue(stopped.started());
      assertEquals(0, h.admissions.get());
      assertEquals(2, h.current.get().recipe().amount());
      assertTrue(h.actions.view().dirty());
      assertTrue(h.actions.view().pending().isEmpty());
    }
  }

  @Test
  void transferredContextClosesWithItsEditorWithoutShuttingBorrowedExecutors() throws Exception {
    try (var h = new Harness()) {
      var owned =
          h.nativeCall(
              () -> {
                var actor =
                    new EditorActor<>(
                        h.context,
                        h.owner.open(h.access),
                        DEFINITION,
                        JSON,
                        DraftLimits.CONFIGURATION,
                        16);
                return DocumentActions.owningContext(
                    h.context,
                    h.access,
                    actor,
                    design.inherent.reform.operation.OperationScope.Limits.STANDARD,
                    8192);
              });
      h.nativeCall(
          () -> {
            owned.close();
            return null;
          });
      assertFalse(h.context.isOpen());
      assertEquals(EditorActor.Availability.CLOSED, owned.view().availability());
      assertEquals(EditorActor.Availability.CLOSED, h.actions.view().availability());
      assertFalse(h.workers.isShutdown());
      assertFalse(h.nativeExecutor.isShutdown());
    }
  }

  @Test
  void closureAfterConfirmedAdmissionRetainsActualFactWithoutReadback() throws Exception {
    try (var h = new Harness()) {
      h.load();
      h.edit("6");
      h.afterAdmission.set(h.context::close);
      var operation = h.nativeCall(() -> h.actions.admit(EntryPoint.GUI));
      var committed = await(DocumentActions.result(operation));
      var receipt = committed.receipt().orElseThrow();
      assertEquals(6, h.current.get().recipe().amount());
      assertEquals(CommitReceipt.Application.APPLIED, receipt.application());
      assertTrue(receipt.acknowledgesBaseline());
      assertTrue(receipt.observation().isEmpty());
      assertFalse(receipt.applicationReport().valid());
      assertEquals(EditorActor.Availability.CLOSED, h.actions.view().availability());
    }
  }
}
