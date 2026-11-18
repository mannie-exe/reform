package design.inherent.reform.reference;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceSelectionTest {
  @TempDir Path root;

  interface Mode {}

  record Primary(String donor) implements Mode {}

  record Other(String name) implements Mode {}

  record Profile(List<String> donors, Mode mode) {}

  static final DocumentId SOURCE = DocumentId.parse("fixture:references");
  static final ValueType<String> REFERENCE = ValueTypes.reference(ValueTypes.text(128), SOURCE);
  static final FieldKey<String> DONOR = FieldKey.of("donor", REFERENCE);
  static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(64));
  static final ValueType<Mode> MODE =
      TaggedType.<Mode>builder("kind", "settings")
          .variant(
              "primary",
              Primary.class,
              RecordType.<Primary>builder(v -> new Primary(v.get(DONOR)))
                  .field(DONOR, Primary::donor)
                  .build())
          .variant(
              "other",
              Other.class,
              RecordType.<Other>builder(v -> new Other(v.get(NAME)))
                  .field(NAME, Other::name)
                  .build())
          .build();
  static final FieldKey<List<String>> DONORS = FieldKey.of("donors", ValueTypes.list(REFERENCE, 4));
  static final FieldKey<Mode> MODE_FIELD = FieldKey.of("mode", MODE);
  static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:selection"),
          1,
          RecordType.<Profile>builder(v -> new Profile(v.get(DONORS), v.get(MODE_FIELD)))
              .field(DONORS, Profile::donors)
              .field(MODE_FIELD, Profile::mode)
              .build(),
          () -> new Profile(List.of("fixture:missing", "fixture:b"), new Primary("fixture:a")));

  static <T> T await(CompletionStage<T> value) throws Exception {
    return value.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(2);
    final AtomicBoolean sourceRead = new AtomicBoolean(true),
        documentRead = new AtomicBoolean(true),
        write = new AtomicBoolean(true);
    final OwnerContext context;
    final JsonOwner<Profile> owner;
    final DocumentActions<Profile> actions;
    final ReferenceActions<String, List<String>> references;
    final Path file = root.resolve("profile.json");

    Harness() throws Exception {
      long thread = nativeCall(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(thread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      var json = new JsonLimits(32768, 64, 4096, 1024, 128, 1024);
      var store =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(StorageBinding.relative(root, "profile.json"), 32768, 4096),
              JsonStore.Format.NEW_ENVELOPE,
              json,
              DraftLimits.CONFIGURATION);
      owner = nativeCall(() -> new JsonOwner<>(context, store, 16));
      var access = nativeCall(() -> context.access(documentRead::get, write::get));
      var session = nativeCall(() -> owner.open(access));
      await(session.initialize());
      var actor =
          nativeCall(
              () ->
                  new EditorActor<>(
                      context, session, DEFINITION, json, DraftLimits.CONFIGURATION, 32));
      actions = nativeCall(() -> new DocumentActions<>(context, access, actor));
      run(() -> actions.load(EntryPoint.GUI));
      references =
          nativeCall(
              () ->
                  new ReferenceActions<>(
                      context,
                      context.access(sourceRead::get, () -> false),
                      SOURCE,
                      ValueTypes.text(128),
                      ValueTypes.list(ValueTypes.text(128), 16).snapshotPolicy(),
                      ReferenceIndexTest.LIMITS));
      bind();
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    <T> T run(Supplier<OperationHandle<T>> work) throws Exception {
      return await(DocumentActions.result(nativeCall(work::get)));
    }

    void bind() throws Exception {
      nativeCall(
          () ->
              references.bind(
                  "same-label",
                  stamp ->
                      ReferenceIndexTest.index(
                          stamp,
                          List.of(
                              ReferenceIndexTest.entry("fixture:a", "Alpha"),
                              ReferenceIndexTest.entry("fixture:b", "Beta"),
                              new ReferenceIndex.Entry<>(
                                  "fixture:empty",
                                  List.of(),
                                  "Empty",
                                  "Empty",
                                  Map.of("kind", "tag"))))));
    }

    FieldPath first() {
      var sequence =
          (DraftNode.Sequence)
              actions.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field("donors"));
      return FieldPath.ROOT.field("donors").row(sequence.rows().getFirst().id());
    }

    ReferenceSelection<Profile, List<String>> selection(FieldPath path) throws Exception {
      return nativeCall(() -> new ReferenceSelection<>(actions, path, references));
    }

    ReferenceSelection.Lookup<List<String>> resolve(
        ReferenceSelection<Profile, List<String>> selection, String raw) throws Exception {
      var request = nativeCall(() -> selection.resolve(EntryPoint.GUI, raw));
      await(request.operation().completion());
      assertTrue(nativeCall(() -> selection.accept(request)));
      return request;
    }

    void structure(FieldPath path, DraftEdit.Action action) throws Exception {
      run(
          () ->
              actions.edit(
                  EntryPoint.GUI,
                  new DraftEdit(actions.view().snapshot().orElseThrow().target(path), action)));
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
  void lookupRetainsAuthoredUnknownTextAndChoosingStagesWithoutPersistence() throws Exception {
    try (var h = new Harness()) {
      String bytes = Files.readString(h.file);
      var selector = h.selection(h.first());
      assertEquals("fixture:missing", selector.raw());
      h.resolve(selector, selector.raw());
      assertEquals(
          ReferenceResolution.Status.UNKNOWN_REFERENCE,
          h.nativeCall(() -> selector.resolution().orElseThrow().status()));
      var unresolved =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> h.nativeCall(() -> selector.choose(EntryPoint.GUI)));
      assertInstanceOf(PolicyRefusal.class, unresolved.getCause());
      assertEquals(bytes, Files.readString(h.file));
      h.resolve(selector, "fixture:a");
      var operation = h.nativeCall(() -> selector.choose(EntryPoint.GUI));
      await(DocumentActions.result(operation));
      assertEquals(
          "fixture:a",
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(h.first()))
              .raw());
      assertEquals(bytes, Files.readString(h.file));
      assertTrue(h.actions.view().dirty());
      assertEquals(
          "fixture:missing", h.owner.handle().view().value().orElseThrow().donors().getFirst());
      assertFalse(h.nativeCall(selector::current));
      h.nativeCall(
          () -> {
            selector.close();
            return null;
          });
      assertTrue(h.references.isOpen());
      assertEquals(EditorActor.Availability.READY, h.actions.view().availability());
    }
  }

  @Test
  void onlyLatestActualQueryAndLookupCanPublishAndEmptyResolutionIsUsable() throws Exception {
    try (var h = new Harness()) {
      var selector = h.selection(h.first());
      var first =
          h.nativeCall(() -> selector.search(EntryPoint.GUI, new ReferenceQuery("Alpha", 2)));
      await(first.operation().completion());
      var second =
          h.nativeCall(() -> selector.search(EntryPoint.GUI, new ReferenceQuery("Beta", 2)));
      await(second.operation().completion());
      assertFalse(h.nativeCall(() -> selector.accept(first)));
      assertFalse(
          h.nativeCall(
              () ->
                  selector.accept(
                      new ReferenceSelection.Search(first.query(), second.operation()))));
      assertTrue(h.nativeCall(() -> selector.accept(second)));
      assertEquals(
          List.of("fixture:b"),
          h.nativeCall(
              () ->
                  selector.page().orElseThrow().choices().stream()
                      .map(ReferencePage.Choice::key)
                      .toList()));
      var old = h.resolve(selector, "fixture:a");
      h.resolve(selector, "fixture:empty");
      assertFalse(h.nativeCall(() -> selector.accept(old)));
      assertEquals(
          List.of(), h.nativeCall(() -> selector.resolution().orElseThrow().value().orElseThrow()));
      assertEquals("fixture:empty", h.nativeCall(() -> selector.selectedKey().orElseThrow()));
      h.nativeCall(
          () -> {
            selector.close();
            return null;
          });
      assertFalse(h.nativeCall(() -> selector.accept(second)));
      assertFalse(h.nativeCall(() -> selector.accept(old)));
    }
  }

  @Test
  void rowReorderingRemovalReplacementAndBranchRoundTripInvalidateExactDraft() throws Exception {
    try (var h = new Harness()) {
      var original = h.first();
      var selector = h.selection(original);
      h.resolve(selector, "fixture:a");
      var row = ((FieldPath.Row) original.segments().getLast()).id();
      h.structure(
          FieldPath.ROOT.field("donors"), new DraftEdit.MoveRow(row, java.util.Optional.empty()));
      assertFalse(h.nativeCall(selector::current));
      assertEquals(original, selector.target().path());
      h.structure(FieldPath.ROOT.field("donors"), new DraftEdit.RemoveRow(row));
      h.structure(
          FieldPath.ROOT.field("donors"), new DraftEdit.InsertRow(java.util.Optional.empty()));
      assertFalse(h.nativeCall(selector::current));
      var branch = h.selection(FieldPath.ROOT.field("mode").variant("primary").field("donor"));
      h.resolve(branch, "fixture:a");
      h.structure(FieldPath.ROOT.field("mode"), new DraftEdit.SelectVariant("other"));
      h.structure(FieldPath.ROOT.field("mode"), new DraftEdit.SelectVariant("primary"));
      assertFalse(h.nativeCall(branch::current));
      assertTrue(h.nativeCall(branch::resolution).isEmpty());
    }
  }

  @Test
  void equalRevisionRebindingClosedContextAndExplicitReloadRejectOldDelivery() throws Exception {
    try (var h = new Harness()) {
      var selector = h.selection(h.first());
      var request = h.resolve(selector, "fixture:a");
      h.bind();
      assertEquals(
          selector.stamp().revision(), h.references.observedStamp().orElseThrow().revision());
      assertNotEquals(
          selector.stamp().snapshot(), h.references.observedStamp().orElseThrow().snapshot());
      assertFalse(h.nativeCall(() -> selector.accept(request)));
      assertTrue(h.nativeCall(selector::resolution).isEmpty());
      var reload = h.selection(h.first());
      h.run(() -> h.actions.load(EntryPoint.COMMAND));
      assertFalse(h.nativeCall(reload::current));
      var closed = h.selection(h.first());
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      assertFalse(h.nativeCall(closed::current));
      assertTrue(h.references.observedStamp().isEmpty());
      h.nativeCall(
          () -> {
            closed.close();
            return null;
          });
    }
  }

  @Test
  void missingDependencyIsDistinctAndRevocationIsRecheckedBeforeStaging() throws Exception {
    try (var h = new Harness()) {
      h.nativeCall(
          () ->
              h.references.invalidate(
                  "missing",
                  ReferencePage.Availability.MISSING_DEPENDENCY,
                  "Optional donor mod is absent"));
      var missing = h.selection(h.first());
      h.resolve(missing, "fixture:a");
      assertEquals(
          ReferenceResolution.Status.MISSING_DEPENDENCY,
          h.nativeCall(() -> missing.resolution().orElseThrow().status()));
      assertTrue(h.nativeCall(missing::selectedKey).isEmpty());
      h.bind();
      var selection = h.selection(h.first());
      h.resolve(selection, "fixture:a");
      h.sourceRead.set(false);
      var refusal =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> h.nativeCall(() -> selection.choose(EntryPoint.GUI)));
      assertInstanceOf(PolicyRefusal.class, refusal.getCause());
      assertFalse(h.actions.view().dirty());
      h.sourceRead.set(true);
      h.documentRead.set(false);
      var denied =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> h.nativeCall(() -> selection.choose(EntryPoint.GUI)));
      assertInstanceOf(PolicyRefusal.class, denied.getCause());
      assertFalse(h.actions.view().dirty());
    }
  }

  @Test
  void failedNewRequestCannotResurrectAnOlderCompletedResponse() throws Exception {
    try (var h = new Harness()) {
      var selection = h.selection(h.first());
      var oldLookup = h.resolve(selection, "fixture:a");
      h.sourceRead.set(false);
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> h.nativeCall(() -> selection.resolve(EntryPoint.GUI, "fixture:b")));
      h.sourceRead.set(true);
      assertFalse(h.nativeCall(() -> selection.accept(oldLookup)));
      assertTrue(h.nativeCall(selection::selectedKey).isEmpty());
      var oldSearch =
          h.nativeCall(() -> selection.search(EntryPoint.GUI, new ReferenceQuery("Alpha", 2)));
      await(oldSearch.operation().completion());
      h.sourceRead.set(false);
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () ->
              h.nativeCall(() -> selection.search(EntryPoint.GUI, new ReferenceQuery("Beta", 2))));
      h.sourceRead.set(true);
      assertFalse(h.nativeCall(() -> selection.accept(oldSearch)));
      assertTrue(h.nativeCall(selection::page).isEmpty());
      h.resolve(selection, "fixture:b");
      assertEquals("fixture:b", h.nativeCall(() -> selection.selectedKey().orElseThrow()));
      assertFalse(h.actions.view().dirty());
    }
  }

  @Test
  void sourceMustMatchTheDeclaredActiveReferenceField() throws Exception {
    try (var h = new Harness()) {
      var foreign =
          h.nativeCall(
              () ->
                  new ReferenceActions<>(
                      h.context,
                      h.context.access(() -> true, () -> false),
                      DocumentId.parse("fixture:other_source"),
                      ValueTypes.text(128),
                      ValueTypes.list(ValueTypes.text(128), 16).snapshotPolicy(),
                      ReferenceIndexTest.LIMITS));
      var wrongSource =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> h.nativeCall(() -> new ReferenceSelection<>(h.actions, h.first(), foreign)));
      assertInstanceOf(IllegalArgumentException.class, wrongSource.getCause());
      var ordinary =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> h.selection(FieldPath.ROOT.field("donors")));
      assertInstanceOf(IllegalArgumentException.class, ordinary.getCause());
      h.structure(FieldPath.ROOT.field("mode"), new DraftEdit.SelectVariant("other"));
      var inactive =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> h.selection(FieldPath.ROOT.field("mode").variant("primary").field("donor")));
      assertInstanceOf(IllegalArgumentException.class, inactive.getCause());
      assertEquals(EditorActor.Availability.READY, h.actions.view().availability());
    }
  }

  @Test
  void captureFailureInvalidatesOldSourceAndDoesNotInvokeCompiler() throws Exception {
    try (var h = new Harness()) {
      var selection = h.selection(h.first());
      h.resolve(selection, "fixture:a");
      var compiled = new AtomicBoolean();
      var failed =
          h.nativeCall(
              () ->
                  h.references.bindCaptured(
                      "failed-capture",
                      (owner, stamp) -> {
                        throw new IllegalStateException("controlled capture failure");
                      },
                      input -> {
                        compiled.set(true);
                        throw new AssertionError("No captured input");
                      }));
      assertThrows(java.util.concurrent.ExecutionException.class, () -> await(failed));
      assertFalse(compiled.get());
      assertFalse(h.nativeCall(selection::current));
      var replacement = h.selection(h.first());
      h.resolve(replacement, "fixture:a");
      assertEquals(
          ReferenceResolution.Status.UNAVAILABLE_CONTEXT,
          h.nativeCall(() -> replacement.resolution().orElseThrow().status()));
      assertTrue(h.nativeCall(replacement::selectedKey).isEmpty());
    }
  }

  @Test
  void capturedBindingUsesRealOwnerThenWorkerAndSelectionRejectsWrongThread() throws Exception {
    try (var h = new Harness()) {
      long ownerThread = h.nativeCall(() -> Thread.currentThread().threadId());
      var compilerThread = new AtomicLong();
      var binding =
          h.nativeCall(
              () ->
                  h.references.bindCaptured(
                      "captured",
                      (context, stamp) -> {
                        assertEquals(ownerThread, Thread.currentThread().threadId());
                        assertEquals(h.context, context);
                        return stamp;
                      },
                      stamp -> {
                        compilerThread.set(Thread.currentThread().threadId());
                        return ReferenceIndexTest.index(
                            stamp, List.of(ReferenceIndexTest.entry("fixture:a", "Alpha")));
                      }));
      assertEquals("captured", await(binding).revision());
      assertNotEquals(0, compilerThread.get());
      assertNotEquals(ownerThread, compilerThread.get());
      var selection = h.selection(h.first());
      assertThrows(IllegalStateException.class, selection::current);
      assertThrows(IllegalStateException.class, selection::close);
      h.nativeCall(
          () -> {
            selection.close();
            return null;
          });
    }
  }
}
