package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldDefaultsOwnerTest {
  @TempDir Path root;

  record Recipe(int height) {}

  static final FieldKey<Integer> HEIGHT = FieldKey.of("height", ValueTypes.integer(1, 64));
  static final RecordType<Recipe> TYPE =
      RecordType.<Recipe>builder(v -> new Recipe(v.get(HEIGHT)))
          .field(HEIGHT, Recipe::height)
          .build();
  static final DocumentDefinition<Recipe> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:world_defaults"), 1, TYPE, () -> new Recipe(3));
  static final JsonLimits JSON = new JsonLimits(8192, 16, 256, 256, 64, 1024);

  static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(2);
    final AtomicInteger writes = new AtomicInteger();
    final Path file = root.resolve("optional/defaults.json");
    final OwnerContext context;
    final JsonStore<Recipe> store;
    final FileOwner<Recipe> owner;
    final FileOwner<Recipe>.Session source;
    final DocumentActions<Recipe> actions;

    Harness(FileOwner.Initialization initialization) throws Exception {
      this(initialization, Optional.empty());
    }

    Harness(FileOwner.Initialization initialization, Optional<ApplicationEffects> staged)
        throws Exception {
      long thread = nativeCall(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(thread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      store =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(
                  StorageBinding.relative(root, "optional", "defaults.json"),
                  8192,
                  1024,
                  () -> {
                    assertNotEquals(thread, Thread.currentThread().threadId());
                    writes.incrementAndGet();
                  }),
              JsonStore.Format.EXISTING_BARE,
              JSON,
              DraftLimits.CONFIGURATION);
      owner =
          nativeCall(
              () ->
                  staged.isPresent()
                      ? FileOwner.stagedFile(
                          context, store, 16, initialization, staged.orElseThrow())
                      : FileOwner.newWorldDefaults(context, store, 16, initialization));
      var access = nativeCall(() -> context.access(() -> true, () -> true));
      source = nativeCall(() -> owner.open(access));
      var editor = nativeCall(() -> owner.open(access));
      actions =
          nativeCall(
              () ->
                  new DocumentActions<>(
                      context,
                      access,
                      new EditorActor<>(
                          context, editor, DEFINITION, JSON, DraftLimits.CONFIGURATION, 64)));
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    <T> T run(Callable<OperationHandle<T>> operation) throws Exception {
      return await(DocumentActions.result(nativeCall(operation)));
    }

    @Override
    public void close() {
      try {
        nativeCall(
            () -> {
              context.close();
              return null;
            });
      } catch (Exception failed) {
        if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new AssertionError(failed);
      } finally {
        nativeExecutor.shutdown();
        workers.shutdown();
        try {
          assertTrue(nativeExecutor.awaitTermination(5, TimeUnit.SECONDS));
          assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException failed) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failed);
        }
      }
    }
  }

  @Test
  void stagedFileRetainsSourceWithoutPublishingOrInventingAnEffectiveOwner() throws Exception {
    var future =
        Optional.of(
            ApplicationEffects.root(
                ApplicationEffects.Timing.EXPLICIT_RELOAD,
                "The other service explicitly reloads this saved file"));
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING, future)) {
      var initialized = await(h.source.initialize());
      assertEquals(CommitReceipt.Application.STAGED, initialized.application());
      assertTrue(initialized.observation().orElseThrow().effectiveView().isEmpty());
      assertFalse(Files.exists(h.file));
      assertFalse(h.source.operations().contains(DocumentSession.Operation.APPLY_SAVED));
      assertThrows(IllegalStateException.class, h.owner::handle);
      h.run(() -> h.actions.edit(EntryPoint.GUI, "height", "17"));
      var actual = h.run(() -> h.actions.commit(EntryPoint.GUI)).receipt().orElseThrow();
      assertEquals(CommitReceipt.Persistence.SAVED, actual.persistence());
      assertEquals(CommitReceipt.Application.STAGED, actual.application());
      assertEquals(future.orElseThrow().rules().getFirst().requirements(), actual.requirements());
      assertTrue(actual.observation().orElseThrow().effectiveView().isEmpty());
      assertEquals(new Recipe(17), h.store.read().candidate().orElseThrow().value());
      assertEquals(1, h.writes.get());
    }
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING, future)) {
      var reopened = await(h.source.initialize());
      assertEquals(CommitReceipt.Application.STAGED, reopened.application());
      assertEquals(
          new Recipe(17),
          reopened.observation().orElseThrow().saved().candidate().orElseThrow().value());
      assertEquals(0, h.writes.get());
    }
  }

  @Test
  void absentDeferredDefaultsAreEditableWithoutFilesDirectoriesOrRuntimeFallback()
      throws Exception {
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING)) {
      assertFalse(Files.exists(h.file.getParent()));
      var initialized = await(h.source.initialize());
      assertEquals(CommitReceipt.Persistence.NOT_ATTEMPTED, initialized.persistence());
      assertEquals(CommitReceipt.Application.STAGED, initialized.application());
      assertTrue(initialized.savedRevision().isEmpty());
      var observed = initialized.observation().orElseThrow();
      assertEquals(TextStore.State.MISSING, observed.saved().state());
      assertFalse(observed.saved().source().present());
      assertEquals("unsaved-defaults", observed.provenance().kind());
      assertEquals(new Recipe(3), observed.saved().candidate().orElseThrow().value());
      assertTrue(observed.saved().report().valid());
      assertEquals(
          "storage.unsaved_defaults", observed.saved().report().issues().getFirst().code());
      assertTrue(h.owner.handle().view().value().isEmpty());
      assertEquals(
          SettingsHandle.Availability.UNINITIALIZED, h.owner.handle().view().availability());
      h.run(() -> h.actions.ensureLoaded(EntryPoint.GUI));
      h.run(() -> h.actions.edit(EntryPoint.GUI, "height", "7"));
      assertTrue(h.actions.view().dirty());
      h.run(() -> h.actions.validate(EntryPoint.COMMAND));
      h.run(() -> h.actions.revert(EntryPoint.GUI));
      h.run(() -> h.actions.load(EntryPoint.COMMAND));
      assertFalse(Files.exists(h.file.getParent()));
      assertEquals(0, h.writes.get());
      assertTrue(h.owner.handle().view().value().isEmpty());
      assertFalse(h.source.operations().contains(DocumentSession.Operation.APPLY_SAVED));
    }
  }

  @Test
  void savingUnchangedDeferredDefaultsStillCreatesTheAbsentSource() throws Exception {
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING)) {
      await(h.source.initialize());
      h.run(() -> h.actions.ensureLoaded(EntryPoint.GUI));
      assertFalse(h.actions.view().dirty());
      assertFalse(Files.exists(h.file));
      var saved = h.run(() -> h.actions.commit(EntryPoint.GUI));
      assertEquals(CommitReceipt.Persistence.SAVED, saved.receipt().orElseThrow().persistence());
      assertEquals(new Recipe(3), h.store.read().candidate().orElseThrow().value());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void explicitFirstSaveCreatesTheOptionalSourceAndStagesOnlyFutureWorldDefaults()
      throws Exception {
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING)) {
      await(h.source.initialize());
      h.run(() -> h.actions.edit(EntryPoint.GUI, "height", "7"));
      var preflight = h.run(() -> h.actions.preflight(EntryPoint.GUI));
      assertTrue(preflight.report().valid());
      assertEquals(new Recipe(7), preflight.candidate().orElseThrow().value());
      assertFalse(Files.exists(h.file));
      var saved = h.run(() -> h.actions.commit(EntryPoint.GUI, preflight.target()));
      var receipt = saved.receipt().orElseThrow();
      assertEquals(CommitReceipt.Persistence.SAVED, receipt.persistence());
      assertEquals(CommitReceipt.Application.STAGED, receipt.application());
      assertEquals(
          CommitReceipt.RequirementKind.NEW_WORLD, receipt.requirements().getFirst().kind());
      assertEquals(new Recipe(7), h.owner.handle().view().value().orElseThrow());
      assertTrue(receipt.acknowledgesBaseline());
      assertFalse(h.actions.view().dirty());
      assertEquals(1, h.writes.get());
      assertEquals(new Recipe(7), h.store.read().candidate().orElseThrow().value());
      var bytes = Files.readAllBytes(h.file);
      var time = Files.getLastModifiedTime(h.file);
      var same = h.run(() -> h.actions.commit(EntryPoint.COMMAND));
      assertEquals(CommitReceipt.Persistence.UNCHANGED, same.receipt().orElseThrow().persistence());
      assertArrayEquals(bytes, Files.readAllBytes(h.file));
      assertEquals(time, Files.getLastModifiedTime(h.file));
      assertEquals(1, h.writes.get());
      assertFalse(h.actions.supportedOperations().contains(DocumentSession.Operation.APPLY_SAVED));
    }
  }

  @Test
  void existingSavedDefaultsPublishTheirOwnValueWithoutRewritingOrClaimingCurrentWorldApplication()
      throws Exception {
    Files.createDirectories(root.resolve("optional"));
    var path = root.resolve("optional/defaults.json");
    Files.writeString(path, "{\"height\":9}\n");
    var original = Files.readAllBytes(path);
    var time = Files.getLastModifiedTime(path);
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING)) {
      var initialized = await(h.source.initialize());
      assertEquals(CommitReceipt.Persistence.NOT_ATTEMPTED, initialized.persistence());
      assertEquals(CommitReceipt.Application.STAGED, initialized.application());
      assertEquals(new Recipe(9), h.owner.handle().view().value().orElseThrow());
      assertEquals(
          ApplicationEffects.Timing.NEW_WORLD,
          initialized
              .observation()
              .orElseThrow()
              .applicationEffects()
              .rules()
              .getFirst()
              .timings()
              .iterator()
              .next());
      assertArrayEquals(original, Files.readAllBytes(path));
      assertEquals(time, Files.getLastModifiedTime(path));
      assertEquals(0, h.writes.get());
    }
  }

  @Test
  void invalidExistingDefaultsAreRetainedWithoutAFactoryDefaultSubstitution() throws Exception {
    Files.createDirectories(root.resolve("optional"));
    var path = root.resolve("optional/defaults.json");
    Files.writeString(path, "{invalid retained source");
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING)) {
      var initialized = await(h.source.initialize());
      assertEquals(CommitReceipt.Persistence.NOT_ATTEMPTED, initialized.persistence());
      assertFalse(initialized.applicationReport().valid());
      assertTrue(h.owner.handle().view().value().isEmpty());
      var observed = await(h.source.read());
      assertTrue(observed.saved().candidate().isEmpty());
      assertEquals("{invalid retained source", Files.readString(path));
      assertEquals(0, h.writes.get());
      Files.writeString(path, "{\"height\":5}");
      var repaired = await(h.source.initialize());
      assertEquals(CommitReceipt.Application.STAGED, repaired.application());
      assertEquals(new Recipe(5), h.owner.handle().view().value().orElseThrow());
      assertEquals(0, h.writes.get());
    }
  }

  @Test
  void anExternalCreatorAfterMissingObservationCannotBeOverwrittenByTheOldDraft() throws Exception {
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING)) {
      var observed = await(h.source.read());
      var proposed = DraftTree.from(TYPE, new Recipe(7), DraftLimits.CONFIGURATION);
      var prepared = await(h.source.prepare(observed, proposed));
      Files.createDirectories(h.file.getParent());
      Files.writeString(h.file, "{\"height\":11}");
      var refused = await(h.source.commit(prepared));
      assertEquals(CommitReceipt.Persistence.REFUSED, refused.persistence());
      assertEquals("{\"height\":11}", Files.readString(h.file));
      assertEquals(0, h.writes.get());
      assertTrue(h.owner.handle().view().value().isEmpty());
    }
  }

  @Test
  void eagerDefaultsCreateOnceAndRetainTheirNewWorldRequirement() throws Exception {
    try (var h = new Harness(FileOwner.Initialization.CREATE_DEFAULTS)) {
      assertFalse(Files.exists(h.file));
      var first = await(h.source.initialize());
      assertEquals(CommitReceipt.Persistence.SAVED, first.persistence());
      assertEquals(CommitReceipt.Application.STAGED, first.application());
      assertEquals(CommitReceipt.RequirementKind.NEW_WORLD, first.requirements().getFirst().kind());
      assertEquals(new Recipe(3), h.owner.handle().view().value().orElseThrow());
      var second = await(h.source.initialize());
      assertEquals(CommitReceipt.Persistence.UNCHANGED, second.persistence());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void missingDefaultProjectionRequiresTheExactStoreAndNeverRepairsInvalidData() throws Exception {
    try (var h = new Harness(FileOwner.Initialization.DEFER_MISSING)) {
      var other =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(StorageBinding.relative(root, "other.json"), 8192),
              JsonStore.Format.EXISTING_BARE,
              JSON,
              DraftLimits.CONFIGURATION);
      var missing = h.store.read();
      assertTrue(missing.candidate().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> other.withMissingDefaults(missing));
      var projected = h.store.withMissingDefaults(missing);
      assertEquals(missing.revision(), projected.revision());
      assertFalse(Files.exists(h.file));
      Files.createDirectories(h.file.getParent());
      Files.writeString(h.file, "{\"height\":-1}");
      var invalid = h.store.read();
      assertSame(invalid, h.store.withMissingDefaults(invalid));
      assertTrue(invalid.candidate().isEmpty());
    }
  }
}
