package design.inherent.reform.importing;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.ValueTypes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentImportsTest {
  @TempDir Path root;

  record Profile(int count, String name) {}

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  private static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(256));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(v -> new Profile(v.get(COUNT), v.get(NAME)))
          .field(COUNT, Profile::count)
          .field(NAME, Profile::name)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("example:imported"), 1, TYPE, () -> new Profile(1, "original"));
  private static final JsonLimits JSON = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
  private static final DocumentId FORMAT = DocumentId.parse("example:value_json");

  private static ImportInput input(String source) {
    return new ImportInput("Selected profile", source.getBytes(StandardCharsets.UTF_8), 8192);
  }

  private static ImportSource snapshot(String source) {
    return ImportSource.snapshot(DocumentId.parse("example:selected"), input(source));
  }

  private static JsonImportFormat<Profile> parser(ImportFormat.InvalidDraftPolicy policy) {
    return new JsonImportFormat<>(
        FORMAT,
        DEFINITION,
        JsonImportFormat.Mode.VALUE_JSON,
        JSON,
        DraftLimits.CONFIGURATION,
        policy);
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  @Test
  void activeReviewAndOperationLookupPreserveActualSourceAndFreshRead() throws Exception {
    try (var h = new Harness()) {
      assertTrue(h.owner(() -> h.actions.activeImports().isEmpty()));
      var captures = new AtomicInteger();
      var source =
          h.source(
              () -> {
                captures.incrementAndGet();
                return input("{\"count\":3,\"name\":\"lookup\"}");
              },
              () -> {});
      var review = h.imports(source);
      h.owner(
          () -> {
            assertSame(review, h.actions.activeImports().orElseThrow());
            assertSame(source, review.admittedSource());
            assertTrue(review.operation(java.util.UUID.randomUUID()).isEmpty());
            return null;
          });
      assertEquals(0, captures.get());
      var handle = h.owner(() -> review.preview(EntryPoint.COMMAND, FORMAT));
      await(handle.completion());
      assertSame(handle, h.owner(() -> review.operation(handle.snapshot().id()).orElseThrow()));
      h.read.set(false);
      h.owner(
          () -> {
            assertThrows(PolicyRefusal.class, review::admittedSource);
            assertThrows(PolicyRefusal.class, () -> review.operation(handle.snapshot().id()));
            assertThrows(PolicyRefusal.class, h.actions::activeImports);
            return null;
          });
      h.read.set(true);
      h.owner(
          () -> {
            review.close();
            assertTrue(h.actions.activeImports().isEmpty());
            return null;
          });
      assertFalse(review.isOpen());
      assertEquals(1, captures.get());
    }
  }

  @Test
  void coldCommandPreviewsCoalesceTheActualBaselineLoad() throws Exception {
    try (var h = new Harness(DraftLimits.CONFIGURATION, false)) {
      var review = h.imports(snapshot("{\"count\":3,\"name\":\"loaded once\"}"));
      var handles =
          h.owner(
              () ->
                  List.of(
                      review.preview(EntryPoint.COMMAND, FORMAT),
                      review.preview(EntryPoint.COMMAND, FORMAT)));
      for (var handle : handles)
        assertEquals(OperationSnapshot.State.COMPLETED, await(handle.completion()).state());
      assertEquals(
          1,
          h.owner(
              () ->
                  h.actions.operations().stream()
                      .filter(
                          operation ->
                              operation
                                  .operation()
                                  .equals(DocumentId.parse("reform:document/reload")))
                      .count()));
      assertEquals("original", h.raw("name"));
    }
  }

  @Test
  void actualPreviewStagesOnceWithSharedUndoAndNoFileOrRuntimeEffect() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.edit(EntryPoint.GUI, "name", "prior work"));
      String original = Files.readString(h.path);
      var effective = h.settings.handle().view();
      var imports = h.imports(snapshot("{\"count\":3,\"name\":\"imported\"}"));
      h.write.set(false);
      var preview = h.run(() -> imports.preview(EntryPoint.GUI, FORMAT));
      assertTrue(preview.stageable());
      assertTrue(preview.comparisonAvailable());
      assertTrue(preview.authoredChanges());
      assertTrue(preview.inputChanges());
      assertEquals(
          List.of(FieldPath.ROOT.field("count"), FieldPath.ROOT.field("name")),
          preview.changes().stream().map(DocumentActions.Change::path).toList());
      assertEquals("\"prior work\"", preview.changes().get(1).beforeJson());
      assertEquals(
          input("{\"count\":3,\"name\":\"imported\"}").sha256(), preview.source().sha256());
      assertTrue(preview.sourceSchemaVersion().isEmpty());
      assertEquals("prior work", h.raw("name"));
      assertEquals(original, Files.readString(h.path));
      h.run(() -> imports.stage(EntryPoint.COMMAND, preview));
      assertEquals("3", h.raw("count"));
      assertEquals("imported", h.raw("name"));
      assertEquals(original, Files.readString(h.path));
      assertSame(effective, h.settings.handle().view());
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      assertEquals("1", h.raw("count"));
      assertEquals("prior work", h.raw("name"));
      h.run(() -> h.actions.redo(EntryPoint.COMMAND));
      assertEquals("imported", h.raw("name"));
      assertEquals(original, Files.readString(h.path));
      assertSame(effective, h.settings.handle().view());
      h.owner(
          () -> {
            assertEquals(
                "import.expired",
                assertThrows(PolicyRefusal.class, () -> imports.stage(EntryPoint.GUI, preview))
                    .report()
                    .issues()
                    .getFirst()
                    .code());
            return null;
          });
      h.write.set(true);
      var saved = h.run(() -> h.actions.save(EntryPoint.GUI));
      assertEquals(CommitReceipt.Persistence.SAVED, saved.receipt().orElseThrow().persistence());
      assertTrue(Files.readString(h.path).contains("imported"));
      assertEquals(new Profile(3, "imported"), h.settings.handle().view().value().orElseThrow());
    }
  }

  @Test
  void coldPreviewUsesTheExistingDocumentLoadBeforeCapturingAnImport() throws Exception {
    try (var h = new Harness(DraftLimits.CONFIGURATION, false)) {
      assertEquals(EditorActor.Availability.LOADING, h.actions.view().availability());
      String file = Files.readString(h.path);
      var imports = h.imports(snapshot("{\"count\":3,\"name\":\"cold import\"}"));
      var preview = h.run(() -> imports.preview(EntryPoint.COMMAND, FORMAT));
      assertTrue(preview.stageable());
      assertEquals(EditorActor.Availability.READY, h.actions.view().availability());
      assertEquals("1", h.raw("count"));
      assertEquals(file, Files.readString(h.path));
      h.run(() -> imports.stage(EntryPoint.COMMAND, preview));
      assertEquals("cold import", h.raw("name"));
      assertEquals(file, Files.readString(h.path));
    }
  }

  @Test
  void coldPreviewNeverInitializesAMissingTargetOrEntersItsImportReader() throws Exception {
    try (var h = new Harness(DraftLimits.CONFIGURATION, false)) {
      Files.delete(h.path);
      var reads = new AtomicInteger();
      var imports =
          h.imports(
              h.source(
                  () -> {
                    reads.incrementAndGet();
                    return input("{\"count\":3,\"name\":\"not read\"}");
                  },
                  () -> {}));
      var result = await(h.owner(() -> imports.preview(EntryPoint.COMMAND, FORMAT)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, result.state());
      assertEquals(EditorActor.Availability.UNAVAILABLE, h.actions.view().availability());
      assertEquals(0, reads.get());
      assertFalse(Files.exists(h.path));
    }
  }

  @Test
  void differentNumberSpellingIsAuthoredButNotASemanticChange() throws Exception {
    try (var h = new Harness()) {
      var imports = h.imports(snapshot("{\"count\":1.00e0,\"name\":\"original\"}"));
      var preview = h.run(() -> imports.preview(EntryPoint.GUI, FORMAT));
      assertTrue(preview.authoredChanges());
      assertTrue(preview.comparisonAvailable());
      assertTrue(preview.changes().isEmpty());
      h.run(() -> imports.stage(EntryPoint.GUI, preview));
      assertEquals("1.00e0", h.raw("count"));
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      assertEquals("1", h.raw("count"));
    }
  }

  @Test
  void stageUsesTheReviewedFileSnapshotEvenIfTheSourceFileChanges() throws Exception {
    try (var h = new Harness()) {
      Path external = root.resolve("import source.json");
      Files.writeString(external, "{\"count\":3,\"name\":\"reviewed\"}");
      var imports =
          h.imports(new FileImportSource(DocumentId.parse("example:file"), external, 8192));
      var preview = h.run(() -> imports.preview(EntryPoint.GUI, FORMAT));
      Files.writeString(external, "{\"count\":7,\"name\":\"changed file\"}");
      String changed = Files.readString(external);
      h.run(() -> imports.stage(EntryPoint.GUI, preview));
      assertEquals("3", h.raw("count"));
      assertEquals("reviewed", h.raw("name"));
      assertEquals(changed, Files.readString(external));
    }
  }

  @Test
  void formatDraftLimitsCannotExpandTheActualEditorsAdmission() throws Exception {
    try (var h = new Harness(new DraftLimits(32, 1024, 20, 128, 1_048_576))) {
      var imports = h.imports(snapshot("{\"count\":3,\"name\":\"a longer imported name\"}"));
      var failed = await(h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT)).completion());
      assertEquals("import.draft_limits", failed.report().issues().getFirst().code());
      assertEquals("original", h.raw("name"));
    }
  }

  @Test
  void failedForeignThreadCloseDoesNotClaimToHaveClosedTheEditor() throws Exception {
    try (var h = new Harness()) {
      assertThrows(AssertionError.class, h.actions::close);
      h.run(() -> h.actions.edit(EntryPoint.GUI, "name", "still owned"));
      assertEquals("still owned", h.raw("name"));
    }
  }

  @Test
  void invalidDraftNeedsAnExplicitRepairableFormatAndNeverClaimsAnEmptyComparison()
      throws Exception {
    try (var h = new Harness()) {
      var source = snapshot("{\"count\":99,\"name\":\"repair me\"}");
      var strict =
          h.owner(
              () ->
                  h.actions.imports(
                      source, List.of(parser(ImportFormat.InvalidDraftPolicy.REFUSE))));
      var invalid = h.run(() -> strict.preview(EntryPoint.GUI, FORMAT));
      assertFalse(invalid.stageable());
      assertFalse(invalid.comparisonAvailable());
      assertFalse(invalid.importedValidation().valid());
      var refused = await(h.owner(() -> strict.stage(EntryPoint.COMMAND, invalid)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertEquals("1", h.raw("count"));
      var repair = h.imports(source);
      var preview = h.run(() -> repair.preview(EntryPoint.COMMAND, FORMAT));
      assertTrue(preview.stageable());
      assertFalse(preview.importedValidation().valid());
      h.owner(
          () -> {
            assertEquals(
                "99",
                ((DraftNode.Scalar) repair.draft(preview).at(FieldPath.ROOT.field("count"))).raw());
            return null;
          });
      h.run(() -> repair.stage(EntryPoint.GUI, preview));
      assertEquals("99", h.raw("count"));
      assertFalse(h.actions.view().validation().valid());
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      assertEquals("1", h.raw("count"));
    }
  }

  @Test
  void malformedReadAndVersionFailuresPreserveExistingDraftAndHistory() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.edit(EntryPoint.GUI, "name", "keep me"));
      var before = h.actions.view().snapshot().orElseThrow();
      String file = Files.readString(h.path);
      for (String malformed : List.of("{", "{\"count\":1,\"count\":2}", "{} {}")) {
        var imports = h.imports(snapshot(malformed));
        var failed = await(h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT)).completion());
        assertEquals(OperationSnapshot.State.REFUSED, failed.state());
        assertFalse(failed.report().valid());
        assertSame(before.draft().root(), h.actions.view().snapshot().orElseThrow().draft().root());
      }
      var versioned =
          new JsonImportFormat<>(
              FORMAT,
              DEFINITION,
              JsonImportFormat.Mode.SCHEMA_ENVELOPE,
              JSON,
              DraftLimits.CONFIGURATION,
              ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE);
      var imports =
          h.owner(
              () ->
                  h.actions.imports(
                      snapshot("{\"schemaVersion\":2,\"values\":{\"count\":3,\"name\":\"bad\"}}"),
                      List.of(versioned)));
      var failed = await(h.owner(() -> imports.preview(EntryPoint.COMMAND, FORMAT)).completion());
      assertEquals("json.schema", failed.report().issues().getFirst().code());
      assertEquals(
          "$.schemaVersion", failed.report().issues().getFirst().arguments().get("sourceLocation"));
      assertEquals(file, Files.readString(h.path));
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      assertEquals("original", h.raw("name"));
    }
  }

  @Test
  void sourceCaptureAndReadUseTheirActualExecutorsAndLaterTypingRefusesPreview() throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.release = release;
      var imports =
          h.imports(
              h.source(
                  () -> {
                    assertNotEquals(h.ownerThread, Thread.currentThread().threadId());
                    entered.countDown();
                    waitFor(release);
                    return input("{\"count\":3,\"name\":\"late\"}");
                  },
                  () -> {}));
      var request = h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT));
      waitFor(entered);
      h.run(() -> h.actions.edit(EntryPoint.GUI, "name", "new typing"));
      release.countDown();
      var failed = await(request.completion());
      assertEquals(OperationSnapshot.State.REFUSED, failed.state());
      assertEquals("import.stale_target", failed.report().issues().getFirst().code());
      assertEquals("new typing", h.raw("name"));
    }
  }

  @Test
  void reviewedTargetAndBothReadAuthoritiesAreRechecked() throws Exception {
    try (var h = new Harness()) {
      var current = new AtomicBoolean(true);
      var imports =
          h.imports(
              h.source(
                  () -> input("{\"count\":3,\"name\":\"imported\"}"),
                  () -> {
                    if (!current.get()) throw new PolicyRefusal("source.closed", "Source closed");
                  }));
      var preview = h.run(() -> imports.preview(EntryPoint.GUI, FORMAT));
      h.read.set(false);
      h.owner(
          () -> {
            assertEquals(
                "owner.permission",
                assertThrows(PolicyRefusal.class, () -> imports.stage(EntryPoint.GUI, preview))
                    .report()
                    .issues()
                    .getFirst()
                    .code());
            return null;
          });
      h.read.set(true);
      current.set(false);
      h.owner(
          () -> {
            assertEquals(
                "source.closed",
                assertThrows(PolicyRefusal.class, () -> imports.stage(EntryPoint.GUI, preview))
                    .report()
                    .issues()
                    .getFirst()
                    .code());
            return null;
          });
      current.set(true);
      h.run(() -> h.actions.edit(EntryPoint.COMMAND, "name", "later"));
      var failed = await(h.owner(() -> imports.stage(EntryPoint.GUI, preview)).completion());
      assertEquals("import.stale_target", failed.report().issues().getFirst().code());
      assertEquals("later", h.raw("name"));
      assertEquals("1", h.raw("count"));
    }
  }

  @Test
  void retainedTicketsAreBoundedAndOldSourceReviewsCannotBeReplayed() throws Exception {
    try (var h = new Harness()) {
      var limits =
          new DocumentImports.Limits(1, 1, 8192, 8, 8192, new OperationScope.Limits(2, 2, 2, JSON));
      var imports =
          h.owner(
              () ->
                  h.actions.imports(
                      snapshot("{\"count\":2,\"name\":\"new\"}"),
                      List.of(parser(ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE)),
                      limits));
      var first = h.run(() -> imports.preview(EntryPoint.GUI, FORMAT));
      var second = h.run(() -> imports.preview(EntryPoint.COMMAND, FORMAT));
      h.owner(
          () -> {
            assertTrue(imports.review(first.id()).isEmpty());
            assertSame(second, imports.review(second.id()).orElseThrow());
            assertThrows(PolicyRefusal.class, () -> imports.draft(first));
            assertThrows(PolicyRefusal.class, () -> imports.stage(EntryPoint.GUI, first));
            return null;
          });
      var replacement = h.imports(snapshot("{\"count\":4,\"name\":\"replacement\"}"));
      h.owner(
          () -> {
            assertTrue(replacement.review(second.id()).isEmpty());
            assertThrows(PolicyRefusal.class, () -> imports.stage(EntryPoint.GUI, second));
            assertThrows(PolicyRefusal.class, () -> replacement.stage(EntryPoint.GUI, second));
            return null;
          });
      assertEquals("1", h.raw("count"));
    }
  }

  @Test
  void comparisonBudgetNeverProducesAPartialStageablePreview() throws Exception {
    try (var h = new Harness()) {
      var limits =
          new DocumentImports.Limits(1, 1, 8192, 1, 8192, new OperationScope.Limits(2, 2, 2, JSON));
      var imports =
          h.owner(
              () ->
                  h.actions.imports(
                      snapshot("{\"count\":2,\"name\":\"new\"}"),
                      List.of(parser(ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE)),
                      limits));
      var failed = await(h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, failed.state());
      assertEquals("compare.limit", failed.report().issues().getFirst().code());
      assertTrue(failed.result().isEmpty());
      assertEquals("original", h.raw("name"));
    }
  }

  @Test
  void cancellationDrainsReadAndNeverPublishesAStageableTicket() throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.release = release;
      var imports =
          h.imports(
              h.source(
                  () -> {
                    entered.countDown();
                    waitFor(release);
                    return input("{\"count\":3,\"name\":\"cancelled\"}");
                  },
                  () -> {}));
      var request = h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT));
      waitFor(entered);
      h.owner(request::requestCancellation);
      release.countDown();
      var result = await(request.completion());
      assertEquals(OperationSnapshot.State.CANCELLED, result.state());
      assertTrue(result.result().isEmpty());
      assertEquals("1", h.raw("count"));
      assertEquals("original", h.raw("name"));
    }
  }

  @Test
  void closingOnlyTheImportReviewDrainsPendingWorkAndRetainsTheEditor() throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.release = release;
      var imports =
          h.imports(
              h.source(
                  () -> {
                    entered.countDown();
                    waitFor(release);
                    return input("{\"count\":3,\"name\":\"closed\"}");
                  },
                  () -> {}));
      var request = h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT));
      waitFor(entered);
      h.owner(
          () -> {
            imports.close();
            return null;
          });
      release.countDown();
      assertTrue(await(request.completion()).result().isEmpty());
      h.run(() -> h.actions.edit(EntryPoint.GUI, "name", "still open"));
      assertEquals("still open", h.raw("name"));
      assertEquals("1", h.raw("count"));
    }
  }

  @Test
  void customCaptureCannotExceedItsDeclaredSourceByteBudget() throws Exception {
    try (var h = new Harness()) {
      var source =
          new ImportSource() {
            @Override
            public DocumentId id() {
              return DocumentId.parse("example:small");
            }

            @Override
            public String label() {
              return "Small source";
            }

            @Override
            public int maximumBytes() {
              return 2;
            }

            @Override
            public void requireCurrent() {}

            @Override
            public Capture capture() {
              return () -> input("{\"count\":3,\"name\":\"too large\"}");
            }
          };
      var imports = h.imports(source);
      var failed = await(h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT)).completion());
      assertEquals("import.bytes", failed.report().issues().getFirst().code());
      assertEquals("original", h.raw("name"));
    }
  }

  @Test
  void sameNamedForeignDefinitionAndDuplicateFormatAreNotRegisteredCapabilities() throws Exception {
    try (var h = new Harness()) {
      var foreign = new DocumentDefinition<>(DEFINITION.id(), 1, TYPE, DEFINITION::defaults);
      var foreignParser =
          new JsonImportFormat<>(
              FORMAT,
              foreign,
              JsonImportFormat.Mode.VALUE_JSON,
              JSON,
              DraftLimits.CONFIGURATION,
              ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE);
      var parser = parser(ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE);
      h.owner(
          () -> {
            assertThrows(
                IllegalArgumentException.class,
                () -> h.actions.imports(snapshot("{}"), List.of(foreignParser)));
            assertThrows(
                IllegalArgumentException.class,
                () -> h.actions.imports(snapshot("{}"), List.of(parser, parser)));
            return null;
          });
      assertEquals("original", h.raw("name"));
    }
  }

  @Test
  void reentrantSourceAdmissionCannotReplaceANewerImportService() throws Exception {
    try (var h = new Harness()) {
      var replaced = new AtomicBoolean();
      var newer = new AtomicReference<DocumentImports<Profile>>();
      var source =
          new ImportSource() {
            @Override
            public DocumentId id() {
              return DocumentId.parse("example:old");
            }

            @Override
            public int maximumBytes() {
              return 8192;
            }

            @Override
            public String label() {
              if (replaced.compareAndSet(false, true))
                newer.set(
                    h.actions.imports(
                        snapshot("{\"count\":4,\"name\":\"newer\"}"),
                        List.of(parser(ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE))));
              return "Older source";
            }

            @Override
            public void requireCurrent() {}

            @Override
            public Capture capture() {
              return () -> input("{\"count\":2,\"name\":\"older\"}");
            }
          };
      h.owner(
          () -> {
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.actions.imports(
                        source, List.of(parser(ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE))));
            return null;
          });
      var preview = h.run(() -> newer.get().preview(EntryPoint.GUI, FORMAT));
      h.run(() -> newer.get().stage(EntryPoint.GUI, preview));
      assertEquals("newer", h.raw("name"));
    }
  }

  @Test
  void sourceGuardCannotRevokeDocumentReadAndStillEnterNativeCapture() throws Exception {
    try (var h = new Harness()) {
      var guards = new AtomicInteger();
      var captures = new AtomicInteger();
      var source =
          new ImportSource() {
            @Override
            public DocumentId id() {
              return DocumentId.parse("example:revoking");
            }

            @Override
            public String label() {
              return "Revoking source";
            }

            @Override
            public int maximumBytes() {
              return 8192;
            }

            @Override
            public void requireCurrent() {
              if (guards.incrementAndGet() == 3) h.read.set(false);
            }

            @Override
            public Capture capture() {
              captures.incrementAndGet();
              return () -> input("{\"count\":2,\"name\":\"denied\"}");
            }
          };
      var imports = h.imports(source);
      var failed = await(h.owner(() -> imports.preview(EntryPoint.GUI, FORMAT)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, failed.state());
      assertEquals("owner.permission", failed.report().issues().getFirst().code());
      assertEquals(0, captures.get());
      assertEquals("original", h.raw("name"));
    }
  }

  final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService workers = Executors.newFixedThreadPool(3);
    final AtomicBoolean read = new AtomicBoolean(true), write = new AtomicBoolean(true);
    final Path path = root.resolve("imported.json");
    final long ownerThread;
    final OwnerContext context;
    final FileOwner<Profile> settings;
    final DocumentActions<Profile> actions;
    CountDownLatch release;

    Harness() throws Exception {
      this(DraftLimits.CONFIGURATION);
    }

    Harness(DraftLimits draftLimits) throws Exception {
      this(draftLimits, true);
    }

    Harness(DraftLimits draftLimits, boolean load) throws Exception {
      ownerThread = owner(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(ownerThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      var store =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(StorageBinding.relative(root, "imported.json"), 8192),
              JsonStore.Format.NEW_ENVELOPE,
              JSON,
              draftLimits);
      settings = owner(() -> new FileOwner<>(context, store, 16));
      var access = owner(() -> context.access(read::get, write::get));
      var session = owner(() -> settings.open(access));
      assertEquals(CommitReceipt.Persistence.SAVED, await(session.initialize()).persistence());
      var actor =
          owner(() -> new EditorActor<>(context, session, DEFINITION, JSON, draftLimits, 32));
      actions = owner(() -> new DocumentActions<>(context, access, actor));
      if (load) run(() -> actions.load(EntryPoint.GUI));
    }

    ImportSource source(ImportSource.Capture reader, Runnable guard) {
      return new ImportSource() {
        @Override
        public DocumentId id() {
          return DocumentId.parse("example:source");
        }

        @Override
        public String label() {
          return "Admitted source";
        }

        @Override
        public int maximumBytes() {
          return 8192;
        }

        @Override
        public void requireCurrent() {
          assertEquals(ownerThread, Thread.currentThread().threadId());
          guard.run();
        }

        @Override
        public Capture capture() {
          assertEquals(ownerThread, Thread.currentThread().threadId());
          return reader;
        }
      };
    }

    DocumentImports<Profile> imports(ImportSource source) throws Exception {
      return owner(
          () ->
              actions.imports(
                  source, List.of(parser(ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE))));
    }

    <T> T owner(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    <T> T run(Supplier<OperationHandle<T>> work) throws Exception {
      return await(DocumentActions.result(owner(work::get)));
    }

    String raw(String field) {
      return ((DraftNode.Scalar)
              actions.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
          .raw();
    }

    @Override
    public void close() {
      if (release != null) release.countDown();
      try {
        owner(
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
}
