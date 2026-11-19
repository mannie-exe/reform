package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupOwnerTest {
  @TempDir Path root;

  record Switches(boolean terrain, boolean client) {}

  private static final FieldKey<Boolean> TERRAIN =
      FieldKey.of("terrain", ValueTypes.defaultedField(ValueTypes.bool(), true));
  private static final FieldKey<Boolean> CLIENT =
      FieldKey.of("client", ValueTypes.defaultedField(ValueTypes.bool(), false));
  private static final RecordType<Switches> TYPE =
      RecordType.<Switches>builder(v -> new Switches(v.get(TERRAIN), v.get(CLIENT)))
          .field(TERRAIN, Switches::terrain)
          .field(CLIENT, Switches::client)
          .build();
  private static final DocumentDefinition<Switches> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:startup"), 1, TYPE, () -> new Switches(true, false));
  private static final JsonLimits JSON = new JsonLimits(8192, 16, 256, 256, 64, 1024);
  private static final FieldPath TERRAIN_PATH = FieldPath.ROOT.field("terrain");

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService worker = Executors.newFixedThreadPool(2);
    final AtomicBoolean read = new AtomicBoolean(true), write = new AtomicBoolean(true);
    final AtomicBoolean hideAfterWrite = new AtomicBoolean();
    final AtomicInteger writes = new AtomicInteger();
    final OwnerContext context;
    final PropertiesStore<Switches> source;
    final FileOwner<Switches> settings;
    final OwnerContext.Access access;
    final FileOwner<Switches>.Session session;

    Harness(String name, Optional<Switches> active) throws Exception {
      long thread = nativeCall(() -> Thread.currentThread().threadId());
      source =
          new PropertiesStore<>(
              DEFINITION,
              new OwnedFile(
                  StorageBinding.relative(root, name),
                  8192,
                  1024,
                  () -> {
                    assertNotEquals(thread, Thread.currentThread().threadId());
                    writes.incrementAndGet();
                    if (hideAfterWrite.get()) read.set(false);
                  }),
              JSON,
              DraftLimits.CONFIGURATION);
      context =
          new OwnerContext(
              nativeExecutor,
              worker,
              () -> assertEquals(thread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      var candidate =
          active.map(
              value ->
                  DraftTree.from(TYPE, value, DraftLimits.CONFIGURATION)
                      .inspect(DEFINITION, JSON)
                      .candidate()
                      .orElseThrow());
      settings = await(nativeCall(() -> FileOwner.startup(context, source, 8, candidate)));
      access = nativeCall(() -> context.access(read::get, write::get));
      session = nativeCall(() -> settings.open(access));
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      try {
        nativeCall(
            () -> {
              context.close();
              return null;
            });
      } catch (Exception failure) {
        throw new AssertionError("Startup fixture owner did not close", failure);
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

  @Test
  void startupRegistrationDoesNotReadCreateOrSubstituteAConfigurationDefault() throws Exception {
    try (var h = new Harness("missing.properties", Optional.empty())) {
      assertFalse(Files.exists(h.source.binding().path()));
      assertEquals(0, h.writes.get());
      var observed = await(h.session.read());
      assertEquals(TextStore.State.MISSING, observed.saved().state());
      assertEquals(SettingsHandle.Availability.UNINITIALIZED, observed.effective().availability());
      assertTrue(observed.effective().value().isEmpty());
      var initialized = await(h.session.initialize());
      assertEquals(CommitReceipt.Persistence.SAVED, initialized.persistence());
      assertEquals(CommitReceipt.Application.STAGED, initialized.application());
      assertEquals(1, h.writes.get());
      assertTrue(h.settings.handle().view().value().isEmpty());
      assertEquals("", Files.readString(h.source.binding().path()));
    }
  }

  @Test
  void editorSaveAcknowledgesFutureFlagsWithoutChangingActualActiveSelection() throws Exception {
    var active = new Switches(true, true);
    try (var h = new Harness("active.properties", Optional.of(active))) {
      var path = h.source.binding().path();
      String source = "# retained\rclient=false\r";
      Files.writeString(path, source);
      var revision = h.settings.handle().view().revision();
      var editor =
          h.nativeCall(
              () ->
                  new EditorActor<>(
                      h.context, h.session, DEFINITION, JSON, DraftLimits.CONFIGURATION, 16));
      var actions = h.nativeCall(() -> new DocumentActions<>(h.context, h.access, editor));
      try {
        await(editor.load());
        var review =
            await(DocumentActions.result(h.nativeCall(() -> actions.review(EntryPoint.COMMAND))));
        assertEquals("properties", review.source().kind());
        assertEquals(2, review.entries().size());
        var client =
            review.entries().stream()
                .filter(e -> e.path().equals(FieldPath.ROOT.field("client")))
                .findFirst()
                .orElseThrow();
        assertEquals("override (\"false\")", client.draft());
        assertEquals("override (\"true\")", client.effective().orElseThrow());
        assertEquals(
            ApplicationEffects.Timing.GAME_RESTART,
            review
                .applicationEffects()
                .forField(TERRAIN_PATH)
                .orElseThrow()
                .timings()
                .iterator()
                .next());
        await(
            editor.edit(
                new DraftEdit(
                    editor.view().snapshot().orElseThrow().target(TERRAIN_PATH),
                    new DraftEdit.Override(true))));
        await(editor.setRaw(TERRAIN_PATH.append(FieldPath.Part.VALUE), "false"));
        var saved = await(editor.save());
        var receipt = saved.receipt().orElseThrow();
        assertEquals(CommitReceipt.Persistence.SAVED, receipt.persistence());
        assertEquals(CommitReceipt.Application.STAGED, receipt.application());
        assertTrue(receipt.acknowledgesBaseline());
        assertEquals(1, receipt.requirements().size());
        assertEquals(
            CommitReceipt.RequirementKind.GAME_RESTART, receipt.requirements().getFirst().kind());
        assertFalse(editor.view().dirty());
        assertEquals(source + "terrain=false\r", Files.readString(path));
        assertEquals(active, h.settings.handle().view().value().orElseThrow());
        assertSame(revision, h.settings.handle().view().revision());
        assertEquals(new Switches(false, false), h.source.read().candidate().orElseThrow().value());
        assertEquals(1, h.writes.get());
      } finally {
        h.nativeCall(
            () -> {
              actions.close();
              return null;
            });
        editor.close();
      }
    }
  }

  @Test
  void applicationOnlyIsUnsupportedAndDoesNotReadOrRewriteChangedSource() throws Exception {
    try (var h = new Harness("no-apply.properties", Optional.of(new Switches(true, false)))) {
      await(h.session.initialize());
      var observed = await(h.session.read());
      assertEquals(
          java.util.Set.of(DocumentSession.Operation.READ, DocumentSession.Operation.COMMIT),
          h.session.operations());
      h.nativeCall(
          () -> {
            var editor =
                new EditorActor<>(
                    h.context,
                    h.settings.open(h.access),
                    DEFINITION,
                    JSON,
                    DraftLimits.CONFIGURATION,
                    8);
            var actions = new DocumentActions<>(h.context, h.access, editor);
            assertEquals(h.session.operations(), actions.supportedOperations());
            assertFalse(
                actions.supportedOperations().contains(DocumentSession.Operation.APPLY_SAVED));
            actions.close();
            return null;
          });
      String malformed = "terrain=NOT_A_BOOLEAN\n";
      Files.writeString(h.source.binding().path(), malformed);
      var failed =
          assertThrows(ExecutionException.class, () -> await(h.session.applySaved(observed)));
      assertInstanceOf(PolicyRefusal.class, failed.getCause());
      assertEquals(
          "owner.unsupported",
          ((PolicyRefusal) failed.getCause()).report().issues().getFirst().code());
      assertEquals(malformed, Files.readString(h.source.binding().path()));
      assertEquals(1, h.writes.get());
      assertEquals(new Switches(true, false), h.settings.handle().view().value().orElseThrow());
    }
  }

  @Test
  void invalidSavedFlagsCannotReplaceOrPoisonTheActualStartupObservation() throws Exception {
    var active = new Switches(false, true);
    try (var h = new Harness("invalid.properties", Optional.of(active))) {
      String source = "terrain=true\nterrain=false\n";
      Files.writeString(h.source.binding().path(), source);
      var receipt = await(h.session.initialize());
      assertEquals(CommitReceipt.Persistence.UNCHANGED, receipt.persistence());
      assertNotEquals(CommitReceipt.Application.STAGED, receipt.application());
      assertFalse(receipt.applicationReport().valid());
      assertEquals(active, h.settings.handle().view().value().orElseThrow());
      assertTrue(h.settings.handle().view().observationReport().valid());
      assertEquals(source, Files.readString(h.source.binding().path()));
      assertEquals(0, h.writes.get());
    }
  }

  @Test
  void savedStartupReceiptSurvivesReadRevocationWithoutDisclosingNewContents() throws Exception {
    var active = new Switches(false, true);
    try (var h = new Harness("revoked.properties", Optional.of(active))) {
      h.hideAfterWrite.set(true);
      var receipt = await(h.session.initialize());
      assertEquals(CommitReceipt.Persistence.SAVED, receipt.persistence());
      assertEquals(CommitReceipt.Application.STAGED, receipt.application());
      assertTrue(receipt.observation().isEmpty());
      assertEquals(active, h.settings.handle().view().value().orElseThrow());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void competingStartupSessionsStillRequireTheExactSavedBytesAndCurrentWriteAuthority()
      throws Exception {
    var active = new Switches(true, false);
    try (var h = new Harness("competing.properties", Optional.of(active))) {
      await(h.session.initialize());
      var second = h.nativeCall(() -> h.settings.open(h.access));
      var firstObserved = await(h.session.read());
      var secondObserved = await(second.read());
      var firstDraft =
          firstObserved
              .saved()
              .draft()
              .orElseThrow()
              .change(
                  TERRAIN_PATH,
                  ignored -> new DraftNode.Defaulted(true, new DraftNode.Scalar("false")));
      var secondDraft =
          secondObserved
              .saved()
              .draft()
              .orElseThrow()
              .change(
                  FieldPath.ROOT.field("client"),
                  ignored -> new DraftNode.Defaulted(true, new DraftNode.Scalar("true")));
      var firstPrepared = await(h.session.prepare(firstObserved, firstDraft));
      var secondPrepared = await(second.prepare(secondObserved, secondDraft));
      assertEquals(
          CommitReceipt.Persistence.SAVED, await(h.session.commit(firstPrepared)).persistence());
      assertEquals(
          CommitReceipt.Persistence.REFUSED, await(second.commit(secondPrepared)).persistence());
      String after = Files.readString(h.source.binding().path());
      var fresh = await(second.read());
      var next = await(second.prepare(fresh, secondDraft));
      h.write.set(false);
      assertEquals(
          CommitReceipt.Persistence.NOT_ATTEMPTED, await(second.commit(next)).persistence());
      assertEquals(after, Files.readString(h.source.binding().path()));
      assertEquals(2, h.writes.get());
      assertEquals(active, h.settings.handle().view().value().orElseThrow());
      second.close();
    }
  }

  @Test
  void aNewProcessContextCanCaptureSavedFlagsOnlyWhenItsLoaderActuallySuppliesThem()
      throws Exception {
    Switches next;
    SettingsHandle<Switches> retired;
    try (var first = new Harness("restart.properties", Optional.of(new Switches(true, false)))) {
      await(first.session.initialize());
      var observed = await(first.session.read());
      var draft =
          observed
              .saved()
              .draft()
              .orElseThrow()
              .change(
                  TERRAIN_PATH,
                  ignored -> new DraftNode.Defaulted(true, new DraftNode.Scalar("false")));
      await(first.session.commit(await(first.session.prepare(observed, draft))));
      next = first.source.read().candidate().orElseThrow().value();
      retired = first.settings.handle();
      assertTrue(retired.view().value().orElseThrow().terrain());
    }
    assertEquals(SettingsHandle.Availability.CLOSED, retired.view().availability());
    try (var second = new Harness("restart.properties", Optional.of(next))) {
      assertFalse(second.settings.handle().view().value().orElseThrow().terrain());
      assertEquals(0, second.writes.get());
      assertEquals(next, await(second.session.read()).saved().candidate().orElseThrow().value());
    }
  }
}
