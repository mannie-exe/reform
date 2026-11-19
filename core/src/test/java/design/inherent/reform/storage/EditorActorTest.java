package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.ConflictReview;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorActorTest {
  @TempDir Path root;

  record Profile(int count) {}

  private static final FieldPath COUNT_PATH = FieldPath.ROOT.field("count");
  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 100));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(values -> new Profile(values.get(COUNT)))
          .field(COUNT, Profile::count)
          .build();
  private static final JsonLimits LIMITS = new JsonLimits(8192, 16, 512, 128, 64, 1024);

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

  private static int value(EditorActor.View<Profile> view) {
    return view.snapshot().orElseThrow().inspect().candidate().orElseThrow().value().count();
  }

  private final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService workers = Executors.newFixedThreadPool(2);
    final AtomicBoolean delayWrite = new AtomicBoolean(), delayRead = new AtomicBoolean();
    final AtomicBoolean readAllowed = new AtomicBoolean(true);
    final AtomicInteger nativeValidators = new AtomicInteger();
    final ConcurrentHashMap<Integer, Long> validationThreads = new ConcurrentHashMap<>();
    final CountDownLatch written = new CountDownLatch(1), releaseWrite = new CountDownLatch(1);
    final CountDownLatch reading = new CountDownLatch(1), releaseRead = new CountDownLatch(1);
    final OwnerContext context;
    final OwnerContext.Access access;
    final JsonOwner<Profile> owner;
    final DocumentDefinition<Profile> definition;
    final EditorActor<Profile> actor;
    final Path path = root.resolve("settings.json");

    Harness(boolean initialize) throws Exception {
      long nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      definition =
          new DocumentDefinition<>(
              DocumentId.parse("fixture:actor"),
              1,
              TYPE,
              () -> new Profile(1),
              profile -> {
                validationThreads.put(profile.count(), Thread.currentThread().threadId());
                if (Thread.currentThread().threadId() == nativeThread)
                  nativeValidators.incrementAndGet();
                return ValidationReport.VALID;
              });
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(
                  StorageBinding.relative(root, "settings.json"),
                  8192,
                  4096,
                  () -> {
                    if (delayWrite.get()) {
                      written.countDown();
                      waitFor(releaseWrite);
                    }
                  }),
              JsonStore.Format.NEW_ENVELOPE,
              LIMITS,
              DraftLimits.CONFIGURATION);
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(nativeThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      owner = nativeCall(() -> new JsonOwner<>(context, store, 8));
      access =
          nativeCall(
              () ->
                  context.access(
                      () -> {
                        if (delayRead.getAndSet(false)) {
                          reading.countDown();
                          waitFor(releaseRead);
                        }
                        return readAllowed.get();
                      },
                      () -> true));
      var source = nativeCall(() -> owner.open(access));
      if (initialize)
        assertEquals(CommitReceipt.Persistence.SAVED, await(source.initialize()).persistence());
      actor =
          nativeCall(
              () ->
                  new EditorActor<>(
                      context, source, definition, LIMITS, DraftLimits.CONFIGURATION, 16));
      await(actor.load());
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      releaseWrite.countDown();
      releaseRead.countDown();
      try {
        nativeCall(
            () -> {
              context.close();
              return null;
            });
      } catch (Exception failure) {
        throw new AssertionError(failure);
      } finally {
        workers.shutdown();
        nativeExecutor.shutdown();
        try {
          assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
          assertTrue(nativeExecutor.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
    }
  }

  @Test
  void theSameMutableSessionIsConfinedToTheActorAcrossDifferentPhysicalWorkers() throws Exception {
    try (var h = new Harness(true)) {
      var firstEntered = new CountDownLatch(1);
      var secondEntered = new CountDownLatch(1);
      var firstRelease = new CountDownLatch(1);
      var secondRelease = new CountDownLatch(1);
      var firstAgainEntered = new CountDownLatch(1);
      var firstAgainRelease = new CountDownLatch(1);
      h.workers.submit(
          () -> {
            firstEntered.countDown();
            waitFor(firstRelease);
          });
      waitFor(firstEntered);
      h.workers.submit(
          () -> {
            secondEntered.countDown();
            waitFor(secondRelease);
          });
      waitFor(secondEntered);
      try {
        firstRelease.countDown();
        assertTrue(await(h.actor.setRaw(COUNT_PATH, "8")).validation().valid());
        long first = h.validationThreads.get(8);
        h.workers.submit(
            () -> {
              firstAgainEntered.countDown();
              waitFor(firstAgainRelease);
            });
        waitFor(firstAgainEntered);
        secondRelease.countDown();
        assertTrue(await(h.actor.setRaw(COUNT_PATH, "9")).validation().valid());
        assertNotEquals(first, h.validationThreads.get(9));
      } finally {
        firstRelease.countDown();
        secondRelease.countDown();
        firstAgainRelease.countDown();
      }
    }
  }

  @Test
  void invalidTypingHistoryDefaultsAndSaveStayOffTheNativeThreadAcrossTwoWorkers()
      throws Exception {
    try (var h = new Harness(true)) {
      var invalid = await(h.actor.setRaw(COUNT_PATH, "-"));
      assertFalse(invalid.validation().valid());
      assertEquals(
          design.inherent.reform.edit.DraftTree.InputState.INCOMPLETE,
          invalid.inputs().get(COUNT_PATH));
      assertThrows(UnsupportedOperationException.class, () -> invalid.inputs().clear());
      assertEquals(1, value(await(h.actor.undo())));
      assertFalse(await(h.actor.redo()).validation().valid());
      assertEquals(1, value(await(h.actor.reset())));
      await(h.actor.setRaw(COUNT_PATH, "7"));
      var saved = await(h.actor.save());
      assertEquals(CommitReceipt.Persistence.SAVED, saved.receipt().orElseThrow().persistence());
      assertFalse(h.actor.view().dirty());
      assertEquals(
          design.inherent.reform.edit.DraftTree.InputState.VALID,
          h.actor.view().inputs().get(COUNT_PATH));
      await(h.actor.setRaw(COUNT_PATH, "8"));
      assertEquals(7, value(await(h.actor.revert())));
      assertEquals(0, h.nativeValidators.get());
    }
  }

  @Test
  void reviewedStageRetainsEarlierWorkAsOneUndoAndRefusesOldTypingWithoutWriting()
      throws Exception {
    try (var h = new Harness(true)) {
      String file = Files.readString(h.path);
      await(h.actor.setRaw(COUNT_PATH, "5"));
      var before = h.actor.view().snapshot().orElseThrow();
      var imported = DraftTree.from(TYPE, new Profile(9), DraftLimits.CONFIGURATION);
      var changed = await(h.actor.stage(before.target(FieldPath.ROOT), imported, "Import profile"));
      assertEquals(9, value(changed));
      var rejected =
          assertThrows(
              ExecutionException.class,
              () ->
                  await(
                      h.actor.edit(
                          new DraftEdit(
                              before.target(COUNT_PATH).typing(), new DraftEdit.SetRaw("6")))));
      assertInstanceOf(design.inherent.reform.owner.PolicyRefusal.class, rejected.getCause());
      assertEquals(9, value(h.actor.view()));
      assertEquals(5, value(await(h.actor.undo())));
      assertEquals(9, value(await(h.actor.redo())));
      assertEquals(file, Files.readString(h.path));
      assertEquals(0, h.nativeValidators.get());
    }
  }

  @Test
  void reviewedStageWaitsForActualSaveAndDoesNotDropItsReceipt() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "3"));
      h.delayWrite.set(true);
      var saving = h.actor.save();
      waitFor(h.written);
      var before = h.actor.view().snapshot().orElseThrow();
      var rejected =
          assertThrows(
              ExecutionException.class,
              () ->
                  await(
                      h.actor.stage(
                          before.target(FieldPath.ROOT),
                          DraftTree.from(TYPE, new Profile(9), DraftLimits.CONFIGURATION),
                          "Import")));
      assertInstanceOf(design.inherent.reform.owner.PolicyRefusal.class, rejected.getCause());
      assertEquals(3, value(h.actor.view()));
      h.releaseWrite.countDown();
      assertEquals(
          CommitReceipt.Persistence.SAVED, await(saving).receipt().orElseThrow().persistence());
      assertFalse(h.actor.view().dirty());
      assertEquals(3, h.owner.handle().view().value().orElseThrow().count());
    }
  }

  @Test
  void noopReviewedStageDoesNotRetireTheTypingStreamRetainedAcrossOwnSave() throws Exception {
    try (var h = new Harness(true)) {
      var typing = h.actor.view().snapshot().orElseThrow().target(COUNT_PATH).typing();
      await(h.actor.edit(new DraftEdit(typing, new DraftEdit.SetRaw("3"))));
      await(h.actor.save());
      var current = h.actor.view().snapshot().orElseThrow();
      await(h.actor.stage(current.target(FieldPath.ROOT), current.draft(), "No change"));
      assertEquals(4, value(await(h.actor.edit(new DraftEdit(typing, new DraftEdit.SetRaw("4"))))));
    }
  }

  @Test
  void editingContinuesWhileIoIsBlockedAndAcknowledgementPreservesTheLaterDraft() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "3"));
      h.delayWrite.set(true);
      var saving = h.actor.save();
      waitFor(h.written);
      assertEquals(8, value(await(h.actor.setRaw(COUNT_PATH, "8"))));
      assertThrows(ExecutionException.class, () -> await(h.actor.save()));
      h.releaseWrite.countDown();
      var result = await(saving);
      assertEquals(CommitReceipt.Persistence.SAVED, result.receipt().orElseThrow().persistence());
      assertEquals(CommitReceipt.Application.APPLIED, result.receipt().orElseThrow().application());
      assertEquals(8, value(h.actor.view()));
      assertTrue(h.actor.view().dirty());
      assertEquals(3, h.owner.handle().view().value().orElseThrow().count());
    }
  }

  @Test
  void closingTheEditorCannotEraseTheReceiptOfAnAlreadyReplacedFile() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "3"));
      h.delayWrite.set(true);
      var saving = h.actor.save();
      waitFor(h.written);
      h.actor.close();
      h.releaseWrite.countDown();
      assertEquals(
          CommitReceipt.Persistence.SAVED, await(saving).receipt().orElseThrow().persistence());
      assertEquals(EditorActor.Availability.CLOSED, h.actor.view().availability());
      assertTrue(h.actor.view().snapshot().isEmpty());
      assertTrue(Files.readString(h.path).contains("\"count\": 3"));
    }
  }

  @Test
  void dirtyReloadUsesExplicitConflictChoicesBeforeAnotherSave() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "3"));
      Files.writeString(h.path, "{\"schemaVersion\":1,\"values\":{\"count\":13}}");
      var conflict = await(h.actor.load()).review().orElseThrow();
      assertEquals(3, value(h.actor.view()));
      var choices =
          conflict.entries().stream()
              .collect(
                  Collectors.toMap(
                      ConflictReview.Entry::path, ignored -> ConflictReview.Choice.DRAFT));
      assertEquals(3, value(await(h.actor.resolve(conflict, choices))));
      assertEquals(
          CommitReceipt.Persistence.SAVED,
          await(h.actor.save()).receipt().orElseThrow().persistence());
      assertEquals(3, h.owner.handle().view().value().orElseThrow().count());
    }
  }

  @Test
  void malformedReloadPreservesTheDraftAndReportsTheFailedObservation() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "6"));
      Files.writeString(h.path, "not json");
      var failed = await(h.actor.load());
      assertEquals(6, value(failed));
      assertFalse(failed.observationReport().valid());
      assertTrue(failed.dirty());
      assertEquals("not json", Files.readString(h.path));
    }
  }

  @Test
  void malformedReloadCannotResolveAnOlderSavedObservation() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "3"));
      Files.writeString(h.path, "{\"schemaVersion\":1,\"values\":{\"count\":13}}");
      var review = await(h.actor.load()).review().orElseThrow();
      var choices =
          review.entries().stream()
              .collect(
                  Collectors.toMap(
                      ConflictReview.Entry::path, ignored -> ConflictReview.Choice.DRAFT));
      Files.writeString(h.path, "not json anymore");
      var failedRead = await(h.actor.load());
      assertSame(review, failedRead.review().orElseThrow());
      assertFalse(failedRead.observationReport().valid());
      assertFalse(failedRead.conflictObservationAvailable());
      assertThrows(ExecutionException.class, () -> await(h.actor.resolve(review, choices)));
      assertEquals(3, value(h.actor.view()));
      assertEquals("not json anymore", Files.readString(h.path));
      Files.writeString(h.path, "{\"schemaVersion\":1,\"values\":{\"count\":14}}");
      var refreshed = await(h.actor.load());
      assertTrue(refreshed.conflictObservationAvailable());
      var currentReview = refreshed.review().orElseThrow();
      assertNotSame(review, currentReview);
      assertThrows(ExecutionException.class, () -> await(h.actor.resolve(review, choices)));
      var freshChoices =
          currentReview.entries().stream()
              .collect(
                  Collectors.toMap(
                      ConflictReview.Entry::path, ignored -> ConflictReview.Choice.DRAFT));
      assertEquals(3, value(await(h.actor.resolve(currentReview, freshChoices))));
      assertTrue(Files.readString(h.path).contains("14"));
    }
  }

  @Test
  void aRefusedReloadCannotLeaveAnOlderSavedReviewQualified() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "3"));
      Files.writeString(h.path, "{\"schemaVersion\":1,\"values\":{\"count\":13}}");
      var review = await(h.actor.load()).review().orElseThrow();
      var choices =
          review.entries().stream()
              .collect(
                  Collectors.toMap(
                      ConflictReview.Entry::path, ignored -> ConflictReview.Choice.DRAFT));
      h.readAllowed.set(false);
      assertThrows(ExecutionException.class, () -> await(h.actor.load()));
      h.readAllowed.set(true);
      assertSame(review, h.actor.view().review().orElseThrow());
      assertThrows(ExecutionException.class, () -> await(h.actor.resolve(review, choices)));
      assertFalse(h.actor.view().conflictObservationAvailable());
      assertEquals(3, value(h.actor.view()));
      assertTrue(Files.readString(h.path).contains("13"));
      var refreshed = await(h.actor.load());
      assertTrue(refreshed.conflictObservationAvailable());
    }
  }

  @Test
  void anOutstandingReloadCannotResolveThePreviousObservation() throws Exception {
    try (var h = new Harness(true)) {
      await(h.actor.setRaw(COUNT_PATH, "3"));
      Files.writeString(h.path, "{\"schemaVersion\":1,\"values\":{\"count\":13}}");
      var review = await(h.actor.load()).review().orElseThrow();
      var choices =
          review.entries().stream()
              .collect(
                  Collectors.toMap(
                      ConflictReview.Entry::path, ignored -> ConflictReview.Choice.DRAFT));
      h.delayRead.set(true);
      var reading = h.actor.load();
      waitFor(h.reading);
      assertFalse(h.actor.view().conflictObservationAvailable());
      assertThrows(ExecutionException.class, () -> await(h.actor.resolve(review, choices)));
      assertEquals(3, value(h.actor.view()));
      h.releaseRead.countDown();
      assertTrue(await(reading).conflictObservationAvailable());
    }
  }

  @Test
  void aLateReadCannotReplaceTypingThatHappenedAfterLoadBegan() throws Exception {
    try (var h = new Harness(true)) {
      h.delayRead.set(true);
      var loading = h.actor.load();
      waitFor(h.reading);
      assertEquals(9, value(await(h.actor.setRaw(COUNT_PATH, "9"))));
      h.releaseRead.countDown();
      assertThrows(ExecutionException.class, () -> await(loading));
      assertEquals(9, value(h.actor.view()));
      assertTrue(h.actor.view().dirty());
    }
  }

  @Test
  void openingAnEditorDoesNotCreateMissingFactoryData() throws Exception {
    try (var h = new Harness(false)) {
      assertEquals(EditorActor.Availability.UNAVAILABLE, h.actor.view().availability());
      assertFalse(Files.exists(h.path));
    }
  }

  @Test
  void repeatedScreenLifetimesReleaseTheirContextCloseSubscriptions() throws Exception {
    try (var h = new Harness(true)) {
      h.actor.close();
      for (int i = 0; i < 1024; i++)
        h.nativeCall(
            () -> {
              var actor =
                  new EditorActor<>(
                      h.context,
                      h.owner.open(h.access),
                      h.definition,
                      LIMITS,
                      DraftLimits.CONFIGURATION,
                      4);
              actor.close();
              return null;
            });
      assertTrue(h.context.isOpen());
      assertEquals(0, h.context.outstandingCalls());
    }
  }
}
