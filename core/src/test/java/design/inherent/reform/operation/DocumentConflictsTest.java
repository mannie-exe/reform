package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.ConflictReview;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.OwnerObservation;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.PreparedEdit;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentConflictsTest {
  @TempDir Path root;

  record Profile(int minimum, int maximum, List<String> roles) {}

  private static final FieldKey<Integer> MIN = FieldKey.of("minimum", ValueTypes.integer(0, 20));
  private static final FieldKey<Integer> MAX = FieldKey.of("maximum", ValueTypes.integer(0, 20));
  private static final FieldKey<List<String>> ROLES =
      FieldKey.of("roles", ValueTypes.list(ValueTypes.text(64), 8));
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("example:conflicts"),
          1,
          RecordType.<Profile>builder(v -> new Profile(v.get(MIN), v.get(MAX), v.get(ROLES)))
              .field(MIN, Profile::minimum)
              .field(MAX, Profile::maximum)
              .field(ROLES, Profile::roles)
              .build(),
          () -> new Profile(1, 10, List.of("base")),
          value ->
              value.minimum() <= value.maximum()
                  ? ValidationReport.VALID
                  : new ValidationReport(
                      List.of(
                          Issue.error(
                              "range.order",
                              FieldPath.ROOT.field("minimum"),
                              "Minimum exceeds maximum")),
                      true));
  private static final JsonLimits JSON = new JsonLimits(32768, 32, 1024, 1024, 64, 1024);
  private static final OperationDescriptor.EntryPoint GUI = OperationDescriptor.EntryPoint.GUI;

  private static <T> T await(CompletionStage<T> result) throws Exception {
    return result.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static Map<FieldPath, ConflictReview.Choice> all(
      DocumentConflicts.Preview preview, ConflictReview.Choice choice) {
    var result = new LinkedHashMap<FieldPath, ConflictReview.Choice>();
    preview.entries().forEach(entry -> result.put(entry.path(), choice));
    return result;
  }

  @Test
  void actualReviewResolvesExplicitFieldsValidatesAndSavesSeparately() throws Exception {
    try (var h = new Harness()) {
      h.conflict(new Profile(2, 12, List.of("remote")));
      String saved = Files.readString(h.path);
      var preview = h.run(() -> h.conflicts.preview(GUI));
      assertEquals(ConflictReview.Basis.OBSERVED_BASELINE, preview.basis());
      assertEquals(DEFINITION.id(), preview.target().document());
      assertNotEquals(preview.baselineRevision(), preview.observedRevision());
      assertEquals("json", preview.source().kind());
      assertEquals(3, preview.entries().size());
      assertTrue(
          preview.entries().stream()
              .filter(entry -> entry.path().equals(FieldPath.ROOT.field("roles")))
              .findFirst()
              .orElseThrow()
              .wholeValue());
      assertTrue(h.owner(() -> h.conflicts.isCurrent(preview)));
      var choices = all(preview, ConflictReview.Choice.BASELINE);
      choices.put(FieldPath.ROOT.field("minimum"), ConflictReview.Choice.DRAFT);
      choices.put(FieldPath.ROOT.field("maximum"), ConflictReview.Choice.OBSERVED);
      var resolved = h.run(() -> h.conflicts.resolve(GUI, preview, choices));
      assertTrue(resolved.report().valid());
      assertTrue(resolved.validation().isPresent());
      assertEquals(5, h.value().minimum());
      assertEquals(12, h.value().maximum());
      assertEquals(List.of("base"), h.value().roles());
      assertEquals(
          preview.observedRevision(),
          resolved.resolved().snapshot().orElseThrow().sourceRevision());
      assertTrue(resolved.resolved().review().isEmpty());
      assertFalse(resolved.resolved().canUndo());
      assertEquals(saved, Files.readString(h.path));
      assertEquals(DEFINITION.defaults(), h.settings.handle().view().value().orElseThrow());
      h.owner(
          () -> {
            assertThrows(PolicyRefusal.class, () -> h.conflicts.resolve(GUI, preview, choices));
            return null;
          });
      var committed = h.run(() -> h.actions.save(GUI));
      assertEquals(
          CommitReceipt.Persistence.SAVED, committed.receipt().orElseThrow().persistence());
      assertEquals(h.value(), h.settings.handle().view().value().orElseThrow());
    }
  }

  @Test
  void noImplicitChoicesAndIncompleteOrForeignPathsNeverChangeTheDraft() throws Exception {
    try (var h = new Harness()) {
      h.conflict(new Profile(2, 12, List.of("remote")));
      var preview = h.run(() -> h.conflicts.preview(GUI));
      var before = h.actions.view().snapshot().orElseThrow();
      h.owner(
          () -> {
            assertTrue(h.conflicts.choices(preview).isEmpty());
            assertThrows(PolicyRefusal.class, () -> h.conflicts.resolve(GUI, preview));
            var first = preview.entries().getFirst().path();
            h.conflicts.choose(preview, first, Optional.of(ConflictReview.Choice.DRAFT));
            assertEquals(1, h.conflicts.choices(preview).size());
            assertThrows(PolicyRefusal.class, () -> h.conflicts.resolve(GUI, preview));
            h.conflicts.choose(preview, first, Optional.empty());
            assertTrue(h.conflicts.choices(preview).isEmpty());
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.conflicts.choose(
                        preview,
                        FieldPath.ROOT.field("other"),
                        Optional.of(ConflictReview.Choice.DRAFT)));
            assertSame(preview, h.conflicts.latest().orElseThrow());
            return null;
          });
      assertEquals(before, h.actions.view().snapshot().orElseThrow());
    }
  }

  @Test
  void staleDraftAndFailedObservationRequireAFreshReviewWithoutLosingWork() throws Exception {
    try (var h = new Harness()) {
      h.conflict(new Profile(2, 12, List.of("remote")));
      var preview = h.run(() -> h.conflicts.preview(GUI));
      h.run(() -> h.actions.edit(GUI, "minimum", "6"));
      assertFalse(h.actions.view().conflictObservationAvailable());
      h.owner(
          () -> {
            assertFalse(h.conflicts.isCurrent(preview));
            assertThrows(
                PolicyRefusal.class,
                () -> h.conflicts.resolve(GUI, preview, all(preview, ConflictReview.Choice.DRAFT)));
            return null;
          });
      h.run(() -> h.actions.load(GUI));
      var refreshed = h.run(() -> h.conflicts.preview(GUI));
      assertNotEquals(preview.id(), refreshed.id());
      Files.writeString(h.path, "not json");
      h.run(() -> h.actions.load(GUI));
      var refused = await(h.owner(() -> h.conflicts.preview(GUI)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertFalse(h.owner(() -> h.conflicts.isCurrent(refreshed)));
      assertEquals("6", h.raw("minimum"));
      assertEquals("not json", Files.readString(h.path));
    }
  }

  @Test
  void crossFieldInvalidChoicesStayRepairableAndCannotSave() throws Exception {
    try (var h = new Harness()) {
      h.conflict(new Profile(2, 4, List.of("remote")));
      var preview = h.run(() -> h.conflicts.preview(GUI));
      var choices = all(preview, ConflictReview.Choice.OBSERVED);
      choices.put(FieldPath.ROOT.field("minimum"), ConflictReview.Choice.DRAFT);
      String saved = Files.readString(h.path);
      var resolved = h.run(() -> h.conflicts.resolve(GUI, preview, choices));
      assertTrue(resolved.report().complete());
      assertFalse(resolved.report().valid());
      assertTrue(
          resolved.report().issues().stream()
              .anyMatch(issue -> issue.code().equals("range.order")));
      assertEquals("5", h.raw("minimum"));
      assertEquals("4", h.raw("maximum"));
      var refused = await(h.owner(() -> h.actions.save(GUI)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertEquals(saved, Files.readString(h.path));
    }
  }

  @Test
  void permissionContextAndActualTicketIdentityRemainRequired() throws Exception {
    try (var h = new Harness();
        var other = new Harness()) {
      h.conflict(new Profile(2, 12, List.of("remote")));
      other.conflict(new Profile(2, 12, List.of("remote")));
      var preview = h.run(() -> h.conflicts.preview(GUI));
      other.owner(
          () -> {
            assertThrows(
                PolicyRefusal.class,
                () ->
                    other.conflicts.resolve(
                        GUI, preview, all(preview, ConflictReview.Choice.DRAFT)));
            assertTrue(other.conflicts.review(preview.id()).isEmpty());
            return null;
          });
      h.read.set(false);
      h.owner(
          () -> {
            assertThrows(PolicyRefusal.class, () -> h.conflicts.review(preview.id()));
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.conflicts.choose(
                        preview,
                        preview.entries().getFirst().path(),
                        Optional.of(ConflictReview.Choice.DRAFT)));
            return null;
          });
      h.read.set(true);
      h.write.set(false);
      String saved = Files.readString(h.path);
      var resolved =
          h.run(() -> h.conflicts.resolve(GUI, preview, all(preview, ConflictReview.Choice.DRAFT)));
      assertTrue(resolved.report().valid());
      h.owner(
          () -> {
            var refused = assertThrows(PolicyRefusal.class, () -> h.actions.save(GUI));
            assertEquals("owner.permission", refused.report().issues().getFirst().code());
            return null;
          });
      assertEquals(saved, Files.readString(h.path));
    }
  }

  @Test
  void completeBudgetRefusalAndRetentionNeverCreditPartialWork() throws Exception {
    try (var h = new Harness()) {
      h.conflict(new Profile(2, 12, List.of("remote")));
      String saved = Files.readString(h.path);
      var standard = DocumentConflicts.Limits.STANDARD;
      for (var limits :
          List.of(
              new DocumentConflicts.Limits(1, 16384, 131072, 1, standard.operations()),
              new DocumentConflicts.Limits(256, 1, 131072, 1, standard.operations()),
              new DocumentConflicts.Limits(256, 16384, 1, 1, standard.operations()))) {
        var service = h.owner(() -> h.actions.conflicts(limits));
        var refused = await(h.owner(() -> service.preview(GUI)).completion());
        assertEquals(OperationSnapshot.State.REFUSED, refused.state());
        assertTrue(refused.result().isEmpty());
        assertTrue(h.owner(service::latest).isEmpty());
        assertEquals("5", h.raw("minimum"));
        assertEquals(saved, Files.readString(h.path));
      }
      var service =
          h.owner(
              () ->
                  h.actions.conflicts(
                      new DocumentConflicts.Limits(256, 16384, 131072, 1, standard.operations())));
      var old = h.run(() -> service.preview(GUI));
      var current = h.run(() -> service.preview(GUI));
      h.owner(
          () -> {
            assertTrue(service.review(old.id()).isEmpty());
            assertFalse(service.isCurrent(old));
            assertSame(current, service.latest().orElseThrow());
            return null;
          });
    }
  }

  @Test
  void enteredResolutionRetainsItsEffectWhenLaterOwnerValidationFails() throws Exception {
    try (var h = new Harness()) {
      h.conflict(new Profile(2, 12, List.of("remote")));
      var preview = h.run(() -> h.conflicts.preview(GUI));
      String saved = Files.readString(h.path);
      h.holdValidation.set(true);
      var operation =
          h.owner(
              () -> h.conflicts.resolve(GUI, preview, all(preview, ConflictReview.Choice.DRAFT)));
      assertTrue(h.validationEntered.await(5, TimeUnit.SECONDS));
      assertTrue(h.actions.view().review().isEmpty());
      await(operation.requestCancellation());
      h.failValidation.set(true);
      h.validationRelease.complete(null);
      var result = await(operation.completion());
      assertEquals(OperationSnapshot.State.COMPLETED, result.state());
      assertTrue(result.cancellationRequested());
      var resolution = result.result().orElseThrow();
      assertFalse(resolution.report().valid());
      assertTrue(resolution.resolved().review().isEmpty());
      assertEquals("5", h.raw("minimum"));
      assertEquals(saved, Files.readString(h.path));
    }
  }

  @Test
  void reentrantRegistrationAndForeignClosePreserveTheNewerWorkflow() throws Exception {
    try (var h = new Harness()) {
      h.conflict(new Profile(2, 12, List.of("remote")));
      var newer = new AtomicReference<DocumentConflicts<Profile>>();
      h.owner(
          () -> {
            h.readHook.set(() -> newer.set(h.actions.conflicts(DocumentConflicts.Limits.STANDARD)));
            assertThrows(
                PolicyRefusal.class, () -> h.actions.conflicts(DocumentConflicts.Limits.STANDARD));
            assertSame(newer.get(), h.actions.conflicts());
            return null;
          });
      var service = newer.get();
      assertThrows(AssertionError.class, service::close);
      assertTrue(service.isOpen());
      var preview = h.run(() -> service.preview(GUI));
      assertTrue(h.owner(() -> service.isCurrent(preview)));
      h.owner(
          () -> {
            h.actions.close();
            assertFalse(service.isOpen());
            return null;
          });
    }
  }

  final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(3);
    final AtomicBoolean read = new AtomicBoolean(true),
        write = new AtomicBoolean(true),
        holdValidation = new AtomicBoolean(),
        failValidation = new AtomicBoolean();
    final AtomicReference<Runnable> readHook = new AtomicReference<>();
    final CountDownLatch validationEntered = new CountDownLatch(1);
    final CompletableFuture<Void> validationRelease = new CompletableFuture<>();
    final Path path = root.resolve(UUID.randomUUID() + ".json");
    final OwnerContext context;
    final FileOwner<Profile> settings;
    final EditorActor<Profile> actor;
    final DocumentActions<Profile> actions;
    final DocumentConflicts<Profile> conflicts;

    Harness() throws Exception {
      long ownerThread = owner(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(ownerThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      var store =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(
                  StorageBinding.relative(root, path.getFileName().toString()),
                  JSON.maximumBytes()),
              JsonStore.Format.NEW_ENVELOPE,
              JSON,
              DraftLimits.CONFIGURATION);
      settings = owner(() -> new FileOwner<>(context, store, 8));
      var access =
          owner(
              () ->
                  context.access(
                      () -> {
                        var hook = readHook.getAndSet(null);
                        if (hook != null) hook.run();
                        return read.get();
                      },
                      write::get));
      var session = owner(() -> settings.open(access));
      await(session.initialize());
      DocumentSession<Profile> wrapped =
          new DocumentSession<>() {
            public DocumentId document() {
              return session.document();
            }

            public DocumentDefinition<Profile> definition() {
              return session.definition();
            }

            public UUID context() {
              return session.context();
            }

            public UUID id() {
              return session.id();
            }

            public boolean isOpen() {
              return session.isOpen();
            }

            public Set<Operation> operations() {
              return session.operations();
            }

            public CompletionStage<? extends OwnerObservation<Profile>> read() {
              return session.read();
            }

            public CompletionStage<? extends PreparedEdit<Profile>> prepare(
                OwnerObservation<Profile> observed, DraftTree draft) {
              if (!holdValidation.getAndSet(false)) return session.prepare(observed, draft);
              validationEntered.countDown();
              return validationRelease.thenCompose(
                  ignored ->
                      failValidation.get()
                          ? CompletableFuture.failedStage(
                              new PolicyRefusal(
                                  "owner.validation_unavailable",
                                  "Owner validation is unavailable",
                                  false))
                          : session.prepare(observed, draft));
            }

            public CompletionStage<? extends CommitReceipt<Profile>> commit(
                PreparedEdit<Profile> prepared) {
              return session.commit(prepared);
            }

            public void close() {
              session.close();
            }
          };
      actor =
          owner(
              () ->
                  new EditorActor<>(
                      context, wrapped, DEFINITION, JSON, DraftLimits.CONFIGURATION, 32));
      actions = owner(() -> new DocumentActions<>(context, access, actor));
      run(() -> actions.load(GUI));
      conflicts = owner(actions::conflicts);
    }

    <T> T owner(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    <T> T run(Supplier<OperationHandle<T>> command) throws Exception {
      var observed = await(owner(command::get).completion());
      assertEquals(
          OperationSnapshot.State.COMPLETED, observed.state(), observed.report().toString());
      return observed.result().orElseThrow();
    }

    void conflict(Profile source) throws Exception {
      run(() -> actions.edit(GUI, "minimum", "5"));
      var json = DEFINITION.encode(source).getOrThrow();
      Files.writeString(path, "{\"schemaVersion\":1,\"values\":" + json + "}");
      var loaded = run(() -> actions.load(GUI));
      assertTrue(loaded.review().isPresent());
    }

    String raw(String field) {
      return ((DraftNode.Scalar)
              actions.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
          .raw();
    }

    Profile value() {
      return actions.view().snapshot().orElseThrow().inspect().candidate().orElseThrow().value();
    }

    @Override
    public void close() {
      validationRelease.complete(null);
      try {
        owner(
            () -> {
              actions.close();
              context.close();
              return null;
            });
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      } catch (Exception failed) {
        throw new AssertionError(failed);
      } finally {
        nativeExecutor.shutdownNow();
        workers.shutdownNow();
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
}
