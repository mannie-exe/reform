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
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentReview;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentActionsTest {
  @TempDir Path root;

  record Profile(int count, double scale, String label) {}

  static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  static final FieldKey<Double> SCALE = FieldKey.of("scale", ValueTypes.decimal(0, 10));
  static final FieldKey<String> LABEL = FieldKey.of("label", ValueTypes.text(128));
  static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(v -> new Profile(v.get(COUNT), v.get(SCALE), v.get(LABEL)))
          .field(COUNT, Profile::count)
          .field(SCALE, Profile::scale)
          .field(LABEL, Profile::label)
          .build();
  static final JsonLimits JSON = new JsonLimits(8192, 32, 1024, 512, 64, 1024);

  static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  static void waitFor(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failed);
    }
  }

  @Test
  void semanticFieldsUseActualOwnerEditsAndPreserveInvalidSiblingDraftsWithoutSaving()
      throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.ensureLoaded(EntryPoint.GUI));
      var before = Files.readAllBytes(h.path);
      int writes = h.writes.get();
      var scope =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.UiScope.borrow(
                      h.context, design.inherent.reform.presentation.ui.UiLimits.STANDARD));
      var count =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.FieldBinding.bind(
                      scope, h.actions, FieldPath.ROOT.field("count"), COUNT.type()));
      var label =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.FieldBinding.bind(
                      scope, h.actions, FieldPath.ROOT.field("label"), LABEL.type()));
      assertSame(h.actions.definition(), count.identity().definition());
      assertEquals(h.actions.context(), count.identity().context());
      var edit =
          h.nativeCall(
              () ->
                  count
                      .edits()
                      .invoke(new design.inherent.reform.presentation.ui.FieldEdit.Raw<>("bad")));
      var first =
          assertInstanceOf(
              design.inherent.reform.presentation.ui.UiActionResult.Pending.class, edit);
      await(DocumentActions.result(first.operation()));
      var sibling =
          h.nativeCall(
              () ->
                  label
                      .edits()
                      .invoke(
                          new design.inherent.reform.presentation.ui.FieldEdit.Typed<>(
                              "unsaved sibling")));
      await(
          DocumentActions.result(
              assertInstanceOf(
                      design.inherent.reform.presentation.ui.UiActionResult.Pending.class, sibling)
                  .operation()));
      h.nativeCall(
          () -> {
            scope.refreshObservations();
            return null;
          });
      var bad = h.nativeCall(() -> count.state().snapshot().value());
      var good = h.nativeCall(() -> label.state().snapshot().value());
      assertEquals("bad", ((DraftNode.Scalar) bad.draft()).raw());
      assertTrue(bad.value().isEmpty());
      assertFalse(bad.validation().valid());
      assertEquals("unsaved sibling", good.value().orElseThrow());
      assertEquals(
          h.actions.view().snapshot().orElseThrow().version(),
          good.target().version().orElseThrow());
      var repair =
          h.nativeCall(
              () ->
                  count
                      .edits()
                      .invoke(new design.inherent.reform.presentation.ui.FieldEdit.Typed<>(2)));
      await(
          DocumentActions.result(
              assertInstanceOf(
                      design.inherent.reform.presentation.ui.UiActionResult.Pending.class, repair)
                  .operation()));
      h.nativeCall(
          () -> {
            scope.refreshObservations();
            count.close();
            return null;
          });
      assertTrue(h.actions.isOpen());
      assertEquals(
          "unsaved sibling",
          h.nativeCall(() -> label.state().snapshot().value().value().orElseThrow()));
      assertArrayEquals(before, Files.readAllBytes(h.path));
      assertEquals(writes, h.writes.get());
      h.nativeCall(
          () -> {
            scope.close();
            return null;
          });
    }
  }

  @Test
  void semanticFrontendCannotGrantWriteOrEraseRejectedInputAndReadLossClearsFieldData()
      throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.ensureLoaded(EntryPoint.GUI));
      var before = Files.readAllBytes(h.path);
      var input =
          new java.util.concurrent.atomic.AtomicReference<>(
              new design.inherent.reform.presentation.ui.FieldFrontend.Input(
                  "rejected native text", 1, 3, 2, false, false));
      var allowFrontend = new AtomicBoolean();
      var frontend =
          new design.inherent.reform.presentation.ui.FieldFrontend() {
            public java.util.Optional<Input> input() {
              return java.util.Optional.of(input.get());
            }

            public design.inherent.reform.presentation.ui.UiAvailability editing() {
              return allowFrontend.get()
                  ? design.inherent.reform.presentation.ui.UiAvailability.READY
                  : design.inherent.reform.presentation.ui.UiAvailability.blocked(
                      "fixture.input_unaccepted", "Repair the input first.");
            }

            public design.inherent.reform.presentation.ui.UiAvailability submission() {
              return editing();
            }
          };
      var scope =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.UiScope.borrow(
                      h.context, design.inherent.reform.presentation.ui.UiLimits.STANDARD));
      var binding =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.FieldBinding.bind(
                      scope, h.actions, FieldPath.ROOT.field("label"), LABEL.type(), frontend));
      var state = h.nativeCall(() -> binding.state().snapshot().value());
      assertEquals("original", state.value().orElseThrow());
      assertEquals("rejected native text", state.input().orElseThrow().raw());
      assertFalse(state.input().orElseThrow().accepted());
      assertInstanceOf(
          design.inherent.reform.presentation.ui.UiActionResult.Refused.class,
          h.nativeCall(
              () ->
                  binding
                      .edits()
                      .invoke(
                          new design.inherent.reform.presentation.ui.FieldEdit.Typed<>(
                              "must not enter"))));
      allowFrontend.set(true);
      h.write.set(false);
      assertInstanceOf(
          design.inherent.reform.presentation.ui.UiActionResult.Refused.class,
          h.nativeCall(
              () ->
                  binding
                      .edits()
                      .invoke(
                          new design.inherent.reform.presentation.ui.FieldEdit.Typed<>(
                              "still not allowed"))));
      h.read.set(false);
      h.nativeCall(
          () -> {
            scope.refreshObservations();
            return null;
          });
      assertInstanceOf(
          design.inherent.reform.presentation.ui.UiObservation.Unavailable.class,
          h.nativeCall(() -> binding.state().snapshot()));
      assertEquals(0, h.nativeCall(() -> scope.usage().stateBytes()));
      h.read.set(true);
      h.nativeCall(
          () -> {
            scope.refreshObservations();
            return null;
          });
      assertEquals(
          "original", h.nativeCall(() -> binding.state().snapshot().value().value().orElseThrow()));
      assertArrayEquals(before, Files.readAllBytes(h.path));
      h.nativeCall(
          () -> {
            scope.close();
            return null;
          });
    }
  }

  @Test
  void semanticFieldKeepsLongAdmittedRawInputWhenTheSourceBudgetRefusesItsCandidate()
      throws Exception {
    var longLabel = FieldKey.of("label", ValueTypes.text(32768));
    var record =
        RecordType.<Profile>builder(v -> new Profile(v.get(COUNT), v.get(SCALE), v.get(longLabel)))
            .field(COUNT, Profile::count)
            .field(SCALE, Profile::scale)
            .field(longLabel, Profile::label)
            .build();
    try (var h = new Harness(record)) {
      h.run(() -> h.actions.ensureLoaded(EntryPoint.GUI));
      var before = Files.readAllBytes(h.path);
      String raw = "x".repeat(32768);
      h.edit("label", raw, EntryPoint.GUI);
      var scope =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.UiScope.borrow(
                      h.context, design.inherent.reform.presentation.ui.UiLimits.STANDARD));
      var binding =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.FieldBinding.bind(
                      scope, h.actions, FieldPath.ROOT.field("label"), longLabel.type()));
      var observed = h.nativeCall(() -> binding.state().snapshot().value());
      assertEquals(raw, ((DraftNode.Scalar) observed.draft()).raw());
      assertFalse(
          observed.validation().valid(),
          "The explicit backend JSON allowance remains authoritative");
      assertTrue(h.nativeCall(() -> scope.usage().stateBytes()) >= 65536);
      assertArrayEquals(before, Files.readAllBytes(h.path));
      h.nativeCall(
          () -> {
            scope.close();
            return null;
          });
    }
  }

  @Test
  void semanticFieldsCacheCodecProjectionAndCannotDispatchInsideAPureComputation()
      throws Exception {
    var base = ValueTypes.text(128);
    var decodeCalls = new AtomicInteger();
    var counted =
        ValueType.of(
            base.codec()
                .xmap(
                    s -> {
                      decodeCalls.incrementAndGet();
                      return s;
                    },
                    java.util.function.Function.identity()),
            base.shape(),
            base.snapshotPolicy());
    var field = FieldKey.of("label", counted);
    var record =
        RecordType.<Profile>builder(v -> new Profile(v.get(COUNT), v.get(SCALE), v.get(field)))
            .field(COUNT, Profile::count)
            .field(SCALE, Profile::scale)
            .field(field, Profile::label)
            .build();
    try (var h = new Harness(record)) {
      h.run(() -> h.actions.ensureLoaded(EntryPoint.GUI));
      var scope =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.UiScope.borrow(
                      h.context, design.inherent.reform.presentation.ui.UiLimits.STANDARD));
      var nativeInput =
          new java.util.concurrent.atomic.AtomicReference<>(
              new design.inherent.reform.presentation.ui.FieldFrontend.Input(
                  "original", 1, 0, 0, true, false));
      var frontend =
          new design.inherent.reform.presentation.ui.FieldFrontend() {
            public java.util.Optional<Input> input() {
              return java.util.Optional.of(nativeInput.get());
            }

            public design.inherent.reform.presentation.ui.UiAvailability editing() {
              return design.inherent.reform.presentation.ui.UiAvailability.READY;
            }

            public design.inherent.reform.presentation.ui.UiAvailability submission() {
              return editing();
            }
          };
      var binding =
          h.nativeCall(
              () ->
                  design.inherent.reform.presentation.ui.FieldBinding.bind(
                      scope, h.actions, FieldPath.ROOT.field("label"), counted, frontend));
      int before = decodeCalls.get();
      h.nativeCall(
          () -> {
            for (int i = 0; i < 10; i++) scope.refreshObservations();
            return null;
          });
      assertEquals(before, decodeCalls.get());
      h.nativeCall(
          () -> {
            for (int i = 1; i <= 7; i++) {
              nativeInput.set(
                  new design.inherent.reform.presentation.ui.FieldFrontend.Input(
                      "original", 1, i, 0, true, false));
              scope.refreshObservations();
              assertEquals(i, binding.state().snapshot().value().input().orElseThrow().caret());
            }
            return null;
          });
      assertEquals(
          before, decodeCalls.get(), "Caret-only updates must not decode the actual field again");

      int operations = h.nativeCall(() -> h.actions.operations().size());
      var refusal =
          h.nativeCall(
              () -> {
                var result =
                    new java.util.concurrent.atomic.AtomicReference<
                        design.inherent.reform.presentation.ui.UiActionResult>();
                scope.derive(
                    new design.inherent.reform.presentation.ViewStateKey<>(
                        DocumentId.parse("fixture:pure_field"), ValueTypes.text(128)),
                    java.util.List.of(binding.state()),
                    in -> {
                      result.set(
                          binding
                              .edits()
                              .invoke(
                                  new design.inherent.reform.presentation.ui.FieldEdit.Typed<>(
                                      "illegal")));
                      return in.value(binding.state()).value().orElseThrow();
                    });
                return result.get();
              });
      var blocked =
          assertInstanceOf(
              design.inherent.reform.presentation.ui.UiActionResult.Refused.class, refusal);
      assertEquals("ui.write_in_pure_phase", blocked.report().issues().getFirst().code());
      assertEquals(operations, h.nativeCall(() -> h.actions.operations().size()));
      h.nativeCall(
          () ->
              assertThrows(
                  PolicyRefusal.class,
                  () ->
                      design.inherent.reform.presentation.ui.FieldBinding.bind(
                          scope, h.actions, FieldPath.ROOT.field("label"), base)));
      h.nativeCall(
          () -> {
            scope.close();
            return null;
          });
    }
  }

  @Test
  void previewCaptureUsesReadAuthorityAndDoesNotSaveOrApply() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "3", EntryPoint.GUI);
      h.write.set(false);
      var before = Files.readAllBytes(h.path);
      var revision = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      var captured = h.run(() -> h.actions.capturePreview(EntryPoint.GUI, revision));
      assertEquals(revision, captured.target());
      assertEquals(3, captured.candidate().orElseThrow().value().count());
      assertTrue(captured.report().valid());
      assertTrue(h.actions.view().dirty());
      assertArrayEquals(before, Files.readAllBytes(h.path));
      assertEquals(1, h.writes.get());
      h.read.set(false);
      h.nativeCall(
          () -> {
            assertThrows(
                PolicyRefusal.class, () -> h.actions.capturePreview(EntryPoint.GUI, revision));
            return null;
          });
    }
  }

  @Test
  void previewCaptureRefusesInvalidInputAndAnOlderRequestedRevision() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var old = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      h.edit("count", "-", EntryPoint.GUI);
      var current = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      var invalid = h.nativeCall(() -> h.actions.capturePreview(EntryPoint.GUI, current));
      var invalidResult = await(invalid.completion());
      assertEquals(OperationSnapshot.State.REFUSED, invalidResult.state());
      assertEquals("preview.input_invalid", invalidResult.report().issues().getFirst().code());
      assertTrue(invalidResult.report().issues().size() > 1);
      assertTrue(invalidResult.result().isEmpty());
      var stale = h.nativeCall(() -> h.actions.capturePreview(EntryPoint.GUI, old));
      var staleResult = await(stale.completion());
      assertEquals(OperationSnapshot.State.REFUSED, staleResult.state());
      assertEquals("preview.stale", staleResult.report().issues().getFirst().code());
      assertTrue(staleResult.result().isEmpty());
      assertEquals(current, DraftRevision.of(h.actions.view().snapshot().orElseThrow()));
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void previewCaptureRefusesReadRevocationWhileBackendValidationIsPending() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "3", EntryPoint.GUI);
      var revision = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      h.pauseValidation.set(true);
      var capture = h.nativeCall(() -> h.actions.capturePreview(EntryPoint.GUI, revision));
      waitFor(h.reached);
      h.read.set(false);
      h.release.countDown();
      var result = await(capture.completion());
      assertEquals(OperationSnapshot.State.REFUSED, result.state());
      assertTrue(result.result().isEmpty());
      assertEquals("owner.permission", result.report().issues().getFirst().code());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void previewCaptureDoesNotReturnAnEarlierDraftAfterEditingDuringValidation() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "3", EntryPoint.GUI);
      var revision = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      // Let the serialized draft inspection finish, then hold backend preparation. Holding
      // inspection itself would correctly serialize the edit behind it rather than test a race.
      h.pauseValidationAt.set(h.validationCalls.get() + 2);
      h.pauseValidation.set(true);
      var capture = h.nativeCall(() -> h.actions.capturePreview(EntryPoint.GUI, revision));
      waitFor(h.reached);
      h.edit("count", "4", EntryPoint.GUI);
      h.release.countDown();
      var result = await(capture.completion());
      assertEquals(OperationSnapshot.State.REFUSED, result.state());
      assertEquals("preview.stale", result.report().issues().getFirst().code());
      assertTrue(result.result().isEmpty());
      assertTrue(h.actions.view().dirty());
      assertNotEquals(revision, DraftRevision.of(h.actions.view().snapshot().orElseThrow()));
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void fieldInformationSharesActualReadPolicyAndRetainsInvalidDraftWithoutSaving()
      throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "-", EntryPoint.GUI);
      var snapshot = h.actions.view().snapshot().orElseThrow();
      var target = snapshot.target(FieldPath.ROOT.field("count"));
      var bytes = Files.readString(h.path);
      var information = h.run(() -> h.actions.information(EntryPoint.GUI, target));
      assertEquals(java.util.Optional.of("\"-\""), information.draft().text());
      assertEquals(java.util.Optional.of("\"1\""), information.baseline().text());
      assertEquals(java.util.Optional.of("\"1\""), information.declaredDefault().text());
      assertEquals(DraftRevision.of(snapshot), information.target());
      assertTrue(h.nativeCall(h.actions::mayInspect));
      var beforeGuardOperations = h.nativeCall(() -> h.actions.operations().size());
      h.nativeCall(
          () -> {
            h.actions.requireInspection();
            return null;
          });
      assertEquals(beforeGuardOperations, h.nativeCall(() -> h.actions.operations().size()));
      h.read.set(false);
      assertFalse(h.nativeCall(h.actions::mayInspect));
      h.nativeCall(
          () -> {
            var refused = assertThrows(PolicyRefusal.class, h.actions::requireInspection);
            assertEquals("owner.permission", refused.report().issues().getFirst().code());
            return null;
          });
      h.nativeCall(
          () -> {
            assertThrows(
                PolicyRefusal.class, () -> h.actions.information(EntryPoint.COMMAND, target));
            return null;
          });
      assertEquals(bytes, Files.readString(h.path));
      assertEquals(1, h.writes.get());
      assertSame(snapshot, h.actions.view().snapshot().orElseThrow());
    }
  }

  @Test
  void staleAndTypingTargetsCannotBePresentedAsCurrentFieldInformation() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var target = h.actions.view().snapshot().orElseThrow().target(FieldPath.ROOT.field("count"));
      h.edit("count", "4", EntryPoint.GUI);
      for (var stale : java.util.List.of(target, target.typing())) {
        var result =
            await(h.nativeCall(() -> h.actions.information(EntryPoint.GUI, stale)).completion());
        assertEquals(OperationSnapshot.State.REFUSED, result.state());
        assertEquals("information.stale", result.report().issues().getFirst().code());
        assertTrue(result.result().isEmpty());
      }
      var fresh = h.actions.view().snapshot().orElseThrow().target(FieldPath.ROOT.field("count"));
      assertEquals(
          java.util.Optional.of("\"4\""),
          h.run(() -> h.actions.information(EntryPoint.COMMAND, fresh)).draft().text());
      assertTrue(h.actions.view().dirty());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void queuedFieldInformationDoesNotDiscloseValuesAfterReadRevocation() throws Exception {
    var pause = new AtomicBoolean();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var type =
        ValueType.of(
            TYPE.codec(),
            TYPE.shape(),
            (Profile value, design.inherent.reform.value.SnapshotContext ignored) -> {
              if (pause.compareAndSet(true, false)) {
                entered.countDown();
                waitFor(release);
              }
              return value;
            });
    try (var h = new Harness(type)) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var target = h.actions.view().snapshot().orElseThrow().target(FieldPath.ROOT.field("count"));
      pause.set(true);
      var operation = h.nativeCall(() -> h.actions.information(EntryPoint.GUI, target));
      waitFor(entered);
      h.read.set(false);
      release.countDown();
      var outcome = await(operation.completion());
      assertEquals(OperationSnapshot.State.REFUSED, outcome.state());
      assertEquals("owner.permission", outcome.report().issues().getFirst().code());
      assertTrue(outcome.result().isEmpty());
      assertFalse(h.nativeCall(h.actions::mayInspect));
      assertEquals(1, h.writes.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void closedSourceRefusesFreshInspectionWithoutClosingItsSharedOwnerContext() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "4", EntryPoint.GUI);
      var retained = h.actions.view().snapshot().orElseThrow();
      var original = Files.readString(h.path);
      h.nativeCall(
          () -> {
            h.session.close();
            return null;
          });
      assertFalse(h.session.isOpen());
      var direct =
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> await(h.session.read()));
      assertEquals(
          "owner.closed",
          assertInstanceOf(PolicyRefusal.class, direct.getCause())
              .report()
              .issues()
              .getFirst()
              .code());
      var inspection = await(h.nativeCall(() -> h.actions.review(EntryPoint.GUI)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, inspection.state());
      assertEquals("owner.closed", inspection.report().issues().getFirst().code());
      assertEquals(EditorActor.Availability.UNAVAILABLE, h.actions.view().availability());
      assertTrue(h.actions.view().snapshot().isEmpty());
      assertEquals(
          "4", ((DraftNode.Scalar) retained.draft().at(FieldPath.ROOT.field("count"))).raw());
      assertTrue(h.context.isOpen());
      assertEquals(original, Files.readString(h.path));
      assertEquals(1, h.writes.get());
      var independent = h.nativeCall(() -> h.owner.open(h.access));
      try {
        assertTrue(await(independent.read()).document().report().valid());
      } finally {
        independent.close();
      }
    }
  }

  @Test
  void sourceClosingDuringReviewWithholdsItsLateMaterialAtTheOwnerDisclosureGate()
      throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "3", EntryPoint.GUI);
      var original = Files.readString(h.path);
      h.pauseValidation.set(true);
      var request = h.nativeCall(() -> h.actions.review(EntryPoint.GUI));
      waitFor(h.reached);
      h.nativeCall(
          () -> {
            h.session.close();
            return null;
          });
      h.release.countDown();
      var refused = await(request.completion());
      assertEquals(OperationSnapshot.State.REFUSED, refused.state());
      assertEquals("owner.closed", refused.report().issues().getFirst().code());
      assertTrue(refused.result().isEmpty());
      assertEquals(EditorActor.Availability.UNAVAILABLE, h.actions.view().availability());
      assertEquals(original, Files.readString(h.path));
      assertEquals(1, h.writes.get());
      assertTrue(h.context.isOpen());
    }
  }

  @Test
  void reviewedCommitChecksAfterQueuedTypingInsteadOfItsPublishedCallerTimeView() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "2", EntryPoint.GUI);
      var reviewed = h.run(() -> h.actions.review(EntryPoint.GUI)).target();
      var original = Files.readString(h.path);
      h.pauseValidation.set(true);
      var typing = h.nativeCall(() -> h.actions.edit(EntryPoint.GUI, "count", "3"));
      waitFor(h.reached);
      assertTrue(reviewed.matches(h.actions.view().snapshot().orElseThrow()));
      var saving = h.nativeCall(() -> h.actions.commit(EntryPoint.GUI, reviewed));
      h.pauseValidation.set(false);
      h.release.countDown();
      await(DocumentActions.result(typing));
      var stopped = await(saving.completion());
      assertEquals(OperationSnapshot.State.REFUSED, stopped.state());
      assertEquals("editor.review_changed", stopped.report().issues().getFirst().code());
      assertTrue(h.actions.view().dirty());
      assertTrue(h.actions.view().pending().isEmpty());
      assertEquals(original, Files.readString(h.path));
      assertEquals(1, h.writes.get());
      var fresh = h.run(() -> h.actions.review(EntryPoint.COMMAND));
      var saved = h.run(() -> h.actions.commit(EntryPoint.COMMAND, fresh.target()));
      assertEquals(CommitReceipt.Persistence.SAVED, saved.receipt().orElseThrow().persistence());
      assertEquals(3, h.owner.handle().view().value().orElseThrow().count());
      assertFalse(h.actions.view().dirty());
    }
  }

  @Test
  void reviewedCommitStillRequiresWriteAndNeverTreatsReviewAsAuthority() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "4", EntryPoint.GUI);
      var reviewed = h.run(() -> h.actions.review(EntryPoint.GUI));
      var original = Files.readString(h.path);
      h.write.set(false);
      assertTrue(
          h.actions
              .supportedOperations()
              .contains(design.inherent.reform.owner.DocumentSession.Operation.COMMIT));
      assertThrows(
          UnsupportedOperationException.class, () -> h.actions.supportedOperations().clear());
      var denied =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> h.nativeCall(() -> h.actions.commit(EntryPoint.COMMAND, reviewed.target())));
      assertInstanceOf(PolicyRefusal.class, denied.getCause());
      assertEquals(original, Files.readString(h.path));
      assertTrue(h.actions.view().dirty());
      assertTrue(h.actions.view().pending().isEmpty());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void reviewKeepsOneCapturedRevisionWhileLaterEditingContinuesWithoutPersistence()
      throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "3", EntryPoint.GUI);
      var original = Files.readString(h.path);
      var captured = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      h.pauseValidation.set(true);
      var request = h.nativeCall(() -> h.actions.review(EntryPoint.GUI));
      assertTrue(h.reached.await(5, TimeUnit.SECONDS));
      h.edit("count", "4", EntryPoint.COMMAND);
      h.release.countDown();
      var review = await(DocumentActions.result(request));
      assertEquals(captured, review.target());
      assertEquals(captured, review.comparison().draft());
      var count =
          review.entries().stream()
              .filter(entry -> entry.selector().equals("count"))
              .findFirst()
              .orElseThrow();
      assertEquals("\"3\"", count.draft());
      assertEquals("\"1\"", count.baseline());
      assertEquals(java.util.Optional.of("\"1\""), count.effective());
      assertEquals("json", review.source().kind());
      assertEquals(h.path.toString(), review.source().location().orElseThrow());
      assertEquals(
          "4",
          ((DraftNode.Scalar)
                  h.actions
                      .view()
                      .snapshot()
                      .orElseThrow()
                      .draft()
                      .at(FieldPath.ROOT.field("count")))
              .raw());
      assertNotEquals(review.target(), DraftRevision.of(h.actions.view().snapshot().orElseThrow()));
      assertEquals(original, Files.readString(h.path));
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void lateReviewDisclosureRequiresCurrentReadAuthority() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "3", EntryPoint.GUI);
      h.pauseValidation.set(true);
      var request = h.nativeCall(() -> h.actions.review(EntryPoint.COMMAND));
      assertTrue(h.reached.await(5, TimeUnit.SECONDS));
      h.read.set(false);
      h.release.countDown();
      var result = await(request.completion());
      assertEquals(OperationSnapshot.State.REFUSED, result.state());
      assertEquals("owner.permission", result.report().issues().getFirst().code());
      assertTrue(result.result().isEmpty());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void reviewCancellationStopsProjectionWithoutClaimingAnEffect() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      h.edit("count", "3", EntryPoint.GUI);
      var before = Files.readString(h.path);
      h.pauseValidation.set(true);
      var request = h.nativeCall(() -> h.actions.review(EntryPoint.GUI));
      assertTrue(h.reached.await(5, TimeUnit.SECONDS));
      await(request.requestCancellation());
      h.release.countDown();
      var result = await(request.completion());
      assertTrue(result.terminal());
      assertTrue(result.cancellationRequested());
      assertTrue(result.result().isEmpty());
      assertEquals("operation.cancelled", result.report().issues().getFirst().code());
      assertEquals(before, Files.readString(h.path));
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void reviewFieldBudgetIsAnExplicitRefusalAndDoesNotChangeItsEditor() throws Exception {
    try (var h = new Harness(TYPE, new DocumentReview.Limits(2, 100, 4096))) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var before = h.actions.view().snapshot().orElseThrow();
      int validations = h.validationCalls.get();
      var result = await(h.nativeCall(() -> h.actions.review(EntryPoint.GUI)).completion());
      assertEquals(OperationSnapshot.State.REFUSED, result.state());
      assertEquals("review.fields", result.report().issues().getFirst().code());
      assertTrue(result.result().isEmpty());
      assertSame(before, h.actions.view().snapshot().orElseThrow());
      assertEquals(validations, h.validationCalls.get());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void structuralServicesEditDynamicFieldsReadOnlyAndRefuseStaleTargetsWithFieldDiagnostics()
      throws Exception {
    var type =
        TaggedType.<Profile>builder("kind", "settings")
            .variant("profile", Profile.class, TYPE)
            .build();
    try (var h = new Harness(type)) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var path = FieldPath.ROOT.variant("profile").field("count");
      var target = h.actions.view().snapshot().orElseThrow().target(path);
      String before = Files.readString(h.path);
      h.write.set(false);
      h.run(() -> h.actions.edit(EntryPoint.GUI, new DraftEdit(target, new DraftEdit.SetRaw("2"))));
      var stale =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.COMMAND, new DraftEdit(target, new DraftEdit.SetRaw("3"))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, stale.state());
      assertEquals("edit.stale_target", stale.report().issues().getFirst().code());
      assertEquals(path, stale.report().issues().getFirst().path());
      assertEquals(
          "2",
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path)).raw());
      assertEquals(before, Files.readString(h.path));
      var actual = h.actions.view().snapshot().orElseThrow().target(path);
      var invalid =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.GUI, new DraftEdit(actual, new DraftEdit.Presence(false))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, invalid.state());
      assertEquals("edit.invalid_action", invalid.report().issues().getFirst().code());
      assertEquals(path, invalid.report().issues().getFirst().path());
      h.read.set(false);
      h.nativeCall(
          () -> {
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.actions.edit(
                        EntryPoint.GUI, new DraftEdit(actual, new DraftEdit.SetRaw("7"))));
            return null;
          });
      assertEquals(before, Files.readString(h.path));
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void typedTypingContinuesAcrossAcknowledgementButExactTargetsRemainStale() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var path = FieldPath.ROOT.field("count");
      var original = h.actions.view().snapshot().orElseThrow().target(path);
      h.run(
          () ->
              h.actions.edit(
                  EntryPoint.GUI, new DraftEdit(original.typing(), new DraftEdit.SetRaw("2"))));
      h.run(() -> h.actions.save(EntryPoint.GUI));
      var saved = Files.readString(h.path);
      var acknowledged = h.actions.view().snapshot().orElseThrow();
      assertEquals(original.session(), acknowledged.session());
      assertTrue(acknowledged.generation() > original.generation());
      var typing =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.GUI,
                              new DraftEdit(original.typing(), new DraftEdit.SetRaw("4"))))
                  .completion());
      assertEquals(OperationSnapshot.State.COMPLETED, typing.state());
      assertEquals(
          "4",
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path)).raw());
      assertEquals(saved, Files.readString(h.path));
      var stale =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.GUI, new DraftEdit(original, new DraftEdit.SetRaw("6"))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, stale.state());
      assertEquals("edit.stale_target", stale.report().issues().getFirst().code());
      assertEquals(saved, Files.readString(h.path));
    }
  }

  @Test
  void typedTypingCoalescesUndoAndCannotResumeAfterReload() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var path = FieldPath.ROOT.field("count");
      var typing = h.actions.view().snapshot().orElseThrow().target(path).typing();
      for (var text : java.util.List.of("2", "4", "6"))
        h.run(
            () ->
                h.actions.edit(EntryPoint.GUI, new DraftEdit(typing, new DraftEdit.SetRaw(text))));
      assertEquals(
          "6",
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path)).raw());
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      assertFalse(h.actions.view().dirty());
      h.run(() -> h.actions.load(EntryPoint.COMMAND));
      var stale =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.GUI, new DraftEdit(typing, new DraftEdit.SetRaw("8"))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, stale.state());
      assertEquals("edit.stale_target", stale.report().issues().getFirst().code());
      assertFalse(h.actions.view().dirty());
    }
  }

  @Test
  void mappedTypingRetainsItsUnitAcrossUndoAndOwnAcknowledgementWithoutCrossUnitCoalescing()
      throws Exception {
    var count =
        FieldKey.of(
            "count",
            ValueTypes.numericDisplay(COUNT.type(), NumericDisplay.ticks(BigDecimal.valueOf(20))));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(count), v.get(SCALE), v.get(LABEL)))
            .field(count, Profile::count)
            .field(SCALE, Profile::scale)
            .field(LABEL, Profile::label)
            .build();
    try (var h = new Harness(type)) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var path = FieldPath.ROOT.field("count");
      var target = h.actions.view().snapshot().orElseThrow().target(path).typing();
      for (var text : java.util.List.of("0.1", "0.2"))
        h.run(
            () ->
                h.actions.edit(
                    EntryPoint.GUI,
                    new DraftEdit(target, new DraftEdit.SetUnitRaw("seconds", text))));
      h.run(
          () ->
              h.actions.edit(
                  EntryPoint.GUI,
                  new DraftEdit(target, new DraftEdit.SetUnitRaw("milliseconds", "250"))));
      await(h.actor.setRaw(path, "6"));
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      var milli = (DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path);
      assertEquals("5", milli.raw());
      assertEquals("milliseconds", milli.unitInput().orElseThrow().unit());
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      var seconds = (DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path);
      assertEquals("4", seconds.raw());
      assertEquals("seconds", seconds.unitInput().orElseThrow().unit());
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      assertFalse(h.actions.view().dirty());
      h.run(() -> h.actions.redo(EntryPoint.GUI));
      h.run(() -> h.actions.save(EntryPoint.GUI));
      var saved = Files.readString(h.path);
      h.run(
          () ->
              h.actions.edit(
                  EntryPoint.GUI,
                  new DraftEdit(target, new DraftEdit.SetUnitRaw("seconds", "0.3"))));
      assertEquals(
          "6",
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path)).raw());
      assertEquals(saved, Files.readString(h.path));
      h.run(() -> h.actions.revert(EntryPoint.GUI));
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var stale =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.GUI,
                              new DraftEdit(target, new DraftEdit.SetUnitRaw("seconds", "0.4"))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, stale.state());
      assertEquals("edit.stale_target", stale.report().issues().getFirst().code());
      assertEquals(saved, Files.readString(h.path));
    }
  }

  @Test
  void failedMappedInputBlocksSharedValidationExportAndRealFileSaveUntilExplicitlyRepaired()
      throws Exception {
    var display =
        new NumericDisplay(
            "base",
            java.util.List.of(
                new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                new NumericDisplay.Unit("tiny", "tiny", new BigDecimal("1e-4096"))),
            BigDecimal.ONE,
            java.util.Optional.empty(),
            DecimalText.Limits.CONFIGURATION);
    var scale = FieldKey.of("scale", ValueTypes.numericDisplay(SCALE.type(), display));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(COUNT), v.get(scale), v.get(LABEL)))
            .field(COUNT, Profile::count)
            .field(scale, Profile::scale)
            .field(LABEL, Profile::label)
            .build();
    try (var h = new Harness(type)) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var path = FieldPath.ROOT.field("scale");
      var target = h.actions.view().snapshot().orElseThrow().target(path);
      String before = Files.readString(h.path);
      h.run(
          () ->
              h.actions.edit(
                  EntryPoint.GUI, new DraftEdit(target, new DraftEdit.SetUnitRaw("tiny", "0.1"))));
      assertFalse(h.run(() -> h.actions.validate(EntryPoint.COMMAND)).report().valid());
      for (var operation :
          java.util.List.of(
              h.nativeCall(() -> h.actions.export(EntryPoint.COMMAND)),
              h.nativeCall(() -> h.actions.save(EntryPoint.GUI))))
        assertEquals(OperationSnapshot.State.REFUSED, await(operation.completion()).state());
      assertEquals(before, Files.readString(h.path));
      assertEquals(1, h.writes.get());
      assertEquals(
          "0.1",
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path))
              .unitInput()
              .orElseThrow()
              .text());
      h.edit("scale", "0.1", EntryPoint.COMMAND);
      assertTrue(h.run(() -> h.actions.validate(EntryPoint.GUI)).report().valid());
      h.run(() -> h.actions.save(EntryPoint.GUI));
      assertEquals(0.1, h.owner.handle().view().value().orElseThrow().scale());
      assertFalse(Files.readString(h.path).contains("tiny"));
    }
  }

  @Test
  void typingDoesNotInventFutureBaselines() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var path = FieldPath.ROOT.field("count");
      var original = h.actions.view().snapshot().orElseThrow().target(path).typing();
      var future =
          new design.inherent.reform.edit.DraftTarget(
              original.session(), original.generation() + 1, original.version(), path);
      var invented =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.GUI, new DraftEdit(future, new DraftEdit.SetRaw("8"))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, invented.state());
      assertFalse(h.actions.view().dirty());
    }
  }

  @Test
  void typingCannotCrossExplicitConflictResolution() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var path = FieldPath.ROOT.field("count");
      var original = h.actions.view().snapshot().orElseThrow().target(path).typing();
      h.run(
          () -> h.actions.edit(EntryPoint.GUI, new DraftEdit(original, new DraftEdit.SetRaw("2"))));
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var review = h.actions.view().review().orElseThrow();
      await(
          h.actor.resolve(
              review,
              java.util.Map.of(path, design.inherent.reform.edit.ConflictReview.Choice.OBSERVED)));
      assertFalse(h.actions.view().dirty());
      var obsolete =
          await(
              h.nativeCall(
                      () ->
                          h.actions.edit(
                              EntryPoint.GUI, new DraftEdit(original, new DraftEdit.SetRaw("9"))))
                  .completion());
      assertEquals(OperationSnapshot.State.REFUSED, obsolete.state());
      assertFalse(h.actions.view().dirty());
      var fresh = h.actions.view().snapshot().orElseThrow().target(path).typing();
      h.run(() -> h.actions.edit(EntryPoint.GUI, new DraftEdit(fresh, new DraftEdit.SetRaw("3"))));
      assertEquals(
          "3",
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(path)).raw());
    }
  }

  @Test
  void sameVariantComparisonReportsTheActualNestedFieldWithoutWriting() throws Exception {
    var type =
        TaggedType.<Profile>builder("kind", "settings")
            .variant("profile", Profile.class, TYPE)
            .build();
    try (var h = new Harness(type)) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var original = Files.readString(h.path);
      var path = FieldPath.ROOT.variant("profile").field("count");
      await(h.nativeCall(() -> h.actor.setRaw(path, "4")));
      var comparison = h.run(() -> h.actions.compare(EntryPoint.COMMAND));
      assertEquals(
          java.util.List.of(new DocumentActions.Change(path, "1", "4")), comparison.changes());
      assertTrue(comparison.report().valid());
      assertEquals(original, Files.readString(h.path));
    }
  }

  @Test
  void comparisonAndSaveResolveCanonicalOmissionsWithoutDroppingExplicitInput() throws Exception {
    var count = FieldKey.of("count", ValueTypes.defaultedField(ValueTypes.integer(0, 10), 1));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(count), v.get(SCALE), v.get(LABEL)))
            .field(count, Profile::count)
            .field(SCALE, Profile::scale)
            .field(LABEL, Profile::label)
            .build();
    try (var h = new Harness(type)) {
      var initial =
          com.google.gson.JsonParser.parseString(Files.readString(h.path)).getAsJsonObject();
      assertFalse(initial.getAsJsonObject("values").has("count"));
      initial.getAsJsonObject("values").addProperty("count", 1);
      Files.writeString(h.path, initial.toString());
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var bytes = Files.readString(h.path);
      await(
          h.nativeCall(
              () ->
                  h.actor.setRaw(FieldPath.ROOT.field("count").append(FieldPath.Part.VALUE), "7")));
      var comparison = h.run(() -> h.actions.compare(EntryPoint.COMMAND));
      assertTrue(comparison.report().valid());
      assertEquals(
          java.util.List.of(new DocumentActions.Change(FieldPath.ROOT.field("count"), "1", "7")),
          comparison.changes());
      assertEquals(bytes, Files.readString(h.path));
      var committed = h.run(() -> h.actions.save(EntryPoint.GUI));
      assertEquals(
          CommitReceipt.Persistence.SAVED, committed.receipt().orElseThrow().persistence());
      assertEquals(
          7,
          h.actions
              .view()
              .snapshot()
              .orElseThrow()
              .inspect()
              .candidate()
              .orElseThrow()
              .value()
              .count());
      var persisted =
          design.inherent.reform.serialization.JsonInput.read(Files.readString(h.path), JSON)
              .copyTree()
              .getAsJsonObject()
              .getAsJsonObject("values");
      assertEquals(
          7,
          design.inherent.reform.serialization.ExactCodecs.INT
              .parse(com.mojang.serialization.JsonOps.INSTANCE, persisted.get("count"))
              .getOrThrow());
      assertFalse(h.actions.view().dirty());
      assertEquals(
          "1",
          h.run(() -> h.actions.describe(EntryPoint.COMMAND)).fields().stream()
              .filter(field -> field.selector().equals("count"))
              .findFirst()
              .orElseThrow()
              .defaultText()
              .orElseThrow());
    }
  }

  final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(2);
    final AtomicBoolean read = new AtomicBoolean(true),
        write = new AtomicBoolean(true),
        revokeAfterWrite = new AtomicBoolean(),
        pauseValidation = new AtomicBoolean();
    final AtomicInteger writes = new AtomicInteger(),
        nativeValidators = new AtomicInteger(),
        validationCalls = new AtomicInteger(),
        pauseValidationAt = new AtomicInteger();
    final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
    final Path path = root.resolve("actions.json");
    final OwnerContext context;
    final OwnerContext.Access access;
    final FileOwner<Profile> owner;
    final FileOwner<Profile>.Session session;
    final EditorActor<Profile> actor;
    final DocumentActions<Profile> actions;

    Harness() throws Exception {
      this(TYPE);
    }

    Harness(ValueType<Profile> rootType) throws Exception {
      this(rootType, DocumentReview.Limits.STANDARD);
    }

    Harness(ValueType<Profile> rootType, DocumentReview.Limits reviewLimits) throws Exception {
      var nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      var definition =
          new DocumentDefinition<>(
              DocumentId.parse("fixture:actions"),
              1,
              rootType,
              () -> new Profile(1, 1.0, "original"),
              profile -> {
                int call = validationCalls.incrementAndGet();
                if (Thread.currentThread().threadId() == nativeThread)
                  nativeValidators.incrementAndGet();
                if (pauseValidation.get()
                    && profile.count() == 3
                    && call >= pauseValidationAt.get()) {
                  reached.countDown();
                  waitFor(release);
                }
                return ValidationReport.VALID;
              });
      context =
          new OwnerContext(
              nativeExecutor,
              workers,
              () -> assertEquals(nativeThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(
                  StorageBinding.relative(root, "actions.json"),
                  8192,
                  4096,
                  () -> {
                    writes.incrementAndGet();
                    if (revokeAfterWrite.getAndSet(false)) write.set(false);
                  }),
              JsonStore.Format.NEW_ENVELOPE,
              JSON,
              DraftLimits.CONFIGURATION);
      owner = nativeCall(() -> new FileOwner<>(context, store, 16));
      access = nativeCall(() -> context.access(read::get, write::get));
      session = nativeCall(() -> owner.open(access));
      assertEquals(CommitReceipt.Persistence.SAVED, await(session.initialize()).persistence());
      actor =
          nativeCall(
              () ->
                  new EditorActor<>(
                      context, session, definition, JSON, DraftLimits.CONFIGURATION, 32));
      actions =
          nativeCall(
              () ->
                  new DocumentActions<>(
                      context,
                      access,
                      actor,
                      OperationScope.Limits.STANDARD,
                      262144,
                      reviewLimits));
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    <T> T run(Supplier<OperationHandle<T>> start) throws Exception {
      return await(DocumentActions.result(nativeCall(start::get)));
    }

    void edit(String field, String value, EntryPoint entry) throws Exception {
      run(() -> actions.edit(entry, field, value));
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
  void guiAndCommandActionsShareDraftValidationComparisonSourceAndExport() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.GUI));
      var original = Files.readString(h.path);
      var time = Files.getLastModifiedTime(h.path);
      var description = h.run(() -> h.actions.describe(EntryPoint.COMMAND));
      assertEquals(3, description.fields().size());
      assertEquals(
          "1",
          description.fields().stream()
              .filter(f -> f.selector().equals("count"))
              .findFirst()
              .orElseThrow()
              .defaultText()
              .orElseThrow());
      h.edit("count", "2", EntryPoint.GUI);
      var validation = h.run(() -> h.actions.validate(EntryPoint.COMMAND));
      assertTrue(validation.report().valid());
      assertEquals(h.actor.view().snapshot().orElseThrow().version(), validation.version());
      var comparison = h.run(() -> h.actions.compare(EntryPoint.COMMAND));
      assertEquals(1, comparison.changes().size());
      assertTrue(comparison.authoredChanges());
      assertEquals(
          new DocumentActions.Change(FieldPath.ROOT.field("count"), "1", "2"),
          comparison.changes().getFirst());
      var explanation = h.run(() -> h.actions.explain(EntryPoint.GUI));
      assertEquals("json", explanation.provenance().kind());
      assertEquals(h.path.toString(), explanation.provenance().location().orElseThrow());
      assertEquals(
          java.util.Set.of(
              design.inherent.reform.owner.ApplicationEffects.Timing.OWNER_PUBLICATION),
          explanation.applicationEffects().forField(FieldPath.ROOT).orElseThrow().timings());
      assertEquals(1, explanation.effective().orElseThrow().value().orElseThrow().count());
      var artifact = h.run(() -> h.actions.export(EntryPoint.COMMAND));
      var guiArtifact = h.run(() -> h.actions.export(EntryPoint.GUI, artifact.draft()));
      assertEquals(artifact.draft(), guiArtifact.draft());
      assertArrayEquals(artifact.bytes(), guiArtifact.bytes());
      assertEquals("document-value", artifact.representation());
      assertTrue(artifact.text().contains("\"count\":2"));
      assertFalse(artifact.text().contains("schemaVersion"));
      assertEquals(
          artifact.sha256(),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact.bytes())));
      var bytes = artifact.bytes();
      bytes[0] = 0;
      assertNotEquals(0, artifact.bytes()[0]);
      assertEquals(original, Files.readString(h.path));
      assertEquals(time, Files.getLastModifiedTime(h.path));
      assertEquals(1, h.writes.get());
      h.edit("count", "-", EntryPoint.COMMAND);
      var stale = h.nativeCall(() -> h.actions.export(EntryPoint.GUI, artifact.draft()));
      var staleResult = await(stale.completion());
      assertEquals(OperationSnapshot.State.REFUSED, staleResult.state());
      assertEquals("editor.review_changed", staleResult.report().issues().getFirst().code());
      assertFalse(h.run(() -> h.actions.validate(EntryPoint.GUI)).report().valid());
      assertFalse(h.run(() -> h.actions.compare(EntryPoint.COMMAND)).report().valid());
      var refused = h.nativeCall(() -> h.actions.export(EntryPoint.COMMAND));
      assertEquals(OperationSnapshot.State.REFUSED, await(refused.completion()).state());
      assertEquals(
          "-",
          ((DraftNode.Scalar)
                  h.actions
                      .view()
                      .snapshot()
                      .orElseThrow()
                      .draft()
                      .at(FieldPath.ROOT.field("count")))
              .raw());
      assertEquals(original, Files.readString(h.path));
      h.edit("count", "2", EntryPoint.COMMAND);
      var saved = h.run(() -> h.actions.save(EntryPoint.GUI));
      assertEquals(CommitReceipt.Persistence.SAVED, saved.receipt().orElseThrow().persistence());
      assertFalse(h.actions.view().dirty());
      h.edit("scale", "1", EntryPoint.GUI);
      var spelling = h.run(() -> h.actions.compare(EntryPoint.COMMAND));
      assertTrue(spelling.authoredChanges());
      assertTrue(spelling.changes().isEmpty());
      assertTrue(spelling.report().valid());
      assertEquals(0, h.nativeValidators.get());
    }
  }

  @Test
  void readOnlyValidationDoesNotGrantWriteAndSavedApplicationPreservesLaterDrafts()
      throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.COMMAND));
      h.write.set(false);
      h.edit("count", "2", EntryPoint.GUI);
      assertTrue(h.run(() -> h.actions.validate(EntryPoint.COMMAND)).report().valid());
      var observed = await(h.session.read());
      var prepared =
          await(h.session.prepare(observed, h.actor.view().snapshot().orElseThrow().draft()));
      assertEquals(
          CommitReceipt.Persistence.NOT_ATTEMPTED, await(h.session.commit(prepared)).persistence());
      h.nativeCall(
          () -> {
            assertThrows(PolicyRefusal.class, () -> h.actions.save(EntryPoint.GUI));
            return null;
          });
      assertEquals(1, h.writes.get());
      h.write.set(true);
      h.revokeAfterWrite.set(true);
      var saved = h.run(() -> h.actions.save(EntryPoint.COMMAND));
      assertEquals(CommitReceipt.Persistence.SAVED, saved.receipt().orElseThrow().persistence());
      assertEquals(CommitReceipt.Application.REFUSED, saved.receipt().orElseThrow().application());
      var actualBytes = Files.readString(h.path);
      var time = Files.getLastModifiedTime(h.path);
      h.edit("count", "4", EntryPoint.GUI);
      var version = h.actor.view().snapshot().orElseThrow().version();
      var undo = h.actor.view().canUndo();
      h.write.set(true);
      var applied = h.run(() -> h.actions.applySaved(EntryPoint.COMMAND));
      assertEquals(
          CommitReceipt.Persistence.NOT_ATTEMPTED, applied.receipt().orElseThrow().persistence());
      assertEquals(
          CommitReceipt.Application.APPLIED, applied.receipt().orElseThrow().application());
      assertEquals(2, h.owner.handle().view().value().orElseThrow().count());
      assertEquals(
          "4",
          ((DraftNode.Scalar)
                  h.actor.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field("count")))
              .raw());
      assertEquals(version, h.actor.view().snapshot().orElseThrow().version());
      assertEquals(undo, h.actor.view().canUndo());
      assertTrue(h.actor.view().dirty());
      assertEquals(actualBytes, Files.readString(h.path));
      assertEquals(time, Files.getLastModifiedTime(h.path));
    }
  }

  @Test
  void coldBurstKeepsEditingAndSaveOrderWhilePreservingTheLaterDraft() throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(2);
      var release = new CountDownLatch(1);
      try {
        for (int worker = 0; worker < 2; worker++)
          h.workers.submit(
              () -> {
                entered.countDown();
                waitFor(release);
              });
        waitFor(entered);
        var handles =
            h.nativeCall(
                () -> {
                  assertEquals(EditorActor.Availability.LOADING, h.actions.view().availability());
                  var first = h.actions.edit(EntryPoint.COMMAND, "count", "2");
                  var second = h.actions.edit(EntryPoint.COMMAND, "count", "4");
                  var saving = h.actions.save(EntryPoint.COMMAND);
                  var later = h.actions.edit(EntryPoint.GUI, "count", "6");
                  return java.util.List.of(first, second, saving, later);
                });
        release.countDown();
        for (var handle : handles)
          assertEquals(OperationSnapshot.State.COMPLETED, await(handle.completion()).state());
        var saving = (EditorActor.Commit<?>) handles.get(2).snapshot().result().orElseThrow();
        assertEquals(CommitReceipt.Persistence.SAVED, saving.receipt().orElseThrow().persistence());
        assertEquals(4, h.owner.handle().view().value().orElseThrow().count());
        assertEquals(
            4,
            com.google.gson.JsonParser.parseString(Files.readString(h.path))
                .getAsJsonObject()
                .getAsJsonObject("values")
                .get("count")
                .getAsInt());
        assertEquals(
            "6",
            ((DraftNode.Scalar)
                    h.actions
                        .view()
                        .snapshot()
                        .orElseThrow()
                        .draft()
                        .at(FieldPath.ROOT.field("count")))
                .raw());
        assertTrue(h.actions.view().dirty());
        assertEquals(2, h.writes.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void cancellationDuringPreparationStopsBeforeCommitWithoutLosingTheDraft() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.load(EntryPoint.COMMAND));
      h.edit("count", "3", EntryPoint.GUI);
      var original = Files.readString(h.path);
      h.pauseValidation.set(true);
      var saving = h.nativeCall(() -> h.actions.save(EntryPoint.COMMAND));
      waitFor(h.reached);
      await(saving.requestCancellation());
      h.release.countDown();
      var stopped = await(saving.completion());
      assertEquals(OperationSnapshot.State.CANCELLED, stopped.state());
      assertTrue(stopped.started());
      assertTrue(stopped.cancellationRequested());
      assertTrue(h.actor.view().pending().isEmpty());
      assertTrue(h.actor.view().dirty());
      assertEquals(original, Files.readString(h.path));
      assertEquals(1, h.writes.get());
      assertEquals(1, h.owner.handle().view().value().orElseThrow().count());
    }
  }
}
