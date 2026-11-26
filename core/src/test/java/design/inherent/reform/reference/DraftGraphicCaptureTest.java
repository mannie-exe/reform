package design.inherent.reform.reference;

import static design.inherent.reform.reference.ReferenceAuditionTest.*;
import static design.inherent.reform.reference.ReferenceSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.value.ValueType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftGraphicCaptureTest {
  @TempDir Path root;

  ReferenceAuditionTest fixture() {
    var fixture = new ReferenceAuditionTest();
    fixture.root = root;
    return fixture;
  }

  DraftGraphicCapture<Profile> capture(ReferenceSelectionTest.Harness h, GraphicLedger ledger)
      throws Exception {
    return h.nativeCall(
        () ->
            h.actions.captureGraphicDraft(
                EntryPoint.GUI,
                DraftRevision.of(h.actions.view().snapshot().orElseThrow()),
                ledger));
  }

  @Test
  void readOnlyDraftHasMetadataHistoryAndStaysChargedUntilLastEnteredUseDrains() throws Exception {
    try (var h = fixture().fixture().new Harness()) {
      var saved = Files.readAllBytes(h.file);
      var initial = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      h.write.set(false);
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, ledger);
      assertSame(lease, await(lease.completion()));
      var first = h.nativeCall(lease::borrow);
      var second = h.nativeCall(lease::borrow);
      var data = first.value();
      assertEquals(DEFINITION.defaults(), data.candidate().value());
      assertEquals(GraphicInputBasis.Content.of(data.candidate().authoredJson()), data.content());
      assertEquals(data.copiedBytes(), ledger.state().bytes());
      assertEquals(1, ledger.state().resources());
      var receipt = await(DocumentActions.result(lease.sourceOperation()));
      assertEquals(initial, receipt.base());
      assertEquals(data.content(), receipt.content());
      assertEquals(data.copiedBytes(), receipt.copiedBytes());
      assertTrue(
          h.nativeCall(
              () -> h.actions.operation(lease.sourceOperation().snapshot().id()).isPresent()));
      h.nativeCall(
          () -> {
            h.actions.close();
            return null;
          });
      assertEquals(data.copiedBytes(), ledger.state().bytes());
      first.close();
      first.close();
      assertEquals(data.copiedBytes(), ledger.state().bytes());
      second.close();
      assertEquals(0, ledger.state().bytes());
      assertEquals(0, ledger.state().resources());
      assertThrows(IllegalStateException.class, second::value);
      assertEquals(receipt, await(DocumentActions.result(lease.sourceOperation())));
      assertArrayEquals(saved, Files.readAllBytes(h.file));
    }
  }

  @Test
  void allowanceRefusesBeforeWholeCandidateDecodeOrActualBackendPreparation() throws Exception {
    var decodes = new AtomicInteger();
    var preparations = new AtomicInteger();
    var original = DEFINITION.type();
    var observed =
        ValueType.of(
            original
                .codec()
                .xmap(
                    value -> {
                      decodes.incrementAndGet();
                      return value;
                    },
                    value -> value),
            original.shape(),
            original.snapshotPolicy());
    var definition = new DocumentDefinition<>(DEFINITION.id(), 1, observed, DEFINITION::defaults);
    try (var h =
        fixture().fixture()
        .new Harness(
            actual ->
                new ObservedSession(
                    actual,
                    draft -> {
                      preparations.incrementAndGet();
                      return CompletableFuture.completedFuture(null);
                    }),
            definition)) {
      int before = decodes.get();
      var ledger = GraphicLedger.inputs(new GraphicLimits(1, 32768, 4096, 16, 65536));
      code(refusal(capture(h, ledger).completion()), "preview.budget");
      assertEquals(before, decodes.get());
      assertEquals(0, preparations.get());
      assertEquals(0, ledger.state().peakBytes());
      var accepted = capture(h, GraphicLedger.inputs(LIMITS));
      await(accepted.completion());
      assertTrue(decodes.get() > before);
      assertEquals(1, preparations.get());
      accepted.close();
    }
  }

  @Test
  void enteredBasePreparationIsChargedForDraftAndAuditionAndContextCloseWaitsForDrain()
      throws Exception {
    for (boolean audition : List.of(false, true)) {
      var entered = new CountDownLatch(1);
      var drain = new CompletableFuture<Void>();
      var helper = fixture();
      try (var h =
          helper.fixture()
          .new Harness(
              actual ->
                  new ObservedSession(
                      actual,
                      draft -> {
                        entered.countDown();
                        return drain;
                      }))) {
        var ledger = GraphicLedger.inputs(LIMITS);
        DraftGraphicCapture<Profile> draft = audition ? null : capture(h, ledger);
        AuditionCapture<Profile> alternate =
            audition
                ? helper.capture(h, helper.audition(h, helper.browser(h, "fixture:b")), ledger)
                : null;
        CompletionStage<?> completion = audition ? alternate.completion() : draft.completion();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long bytes = ledger.state().bytes();
        assertTrue(bytes > 0);
        assertEquals(1, ledger.state().resources());
        h.nativeCall(
            () -> {
              h.context.close();
              return null;
            });
        assertFalse(completion.toCompletableFuture().isDone());
        assertEquals(bytes, ledger.state().bytes());
        drain.complete(null);
        refusal(completion);
        assertEquals(0, ledger.state().bytes());
        assertEquals(0, ledger.state().resources());
        assertFalse(h.context.isOpen());
      } finally {
        drain.complete(null);
      }
    }
  }

  @Test
  void completeValidationAndRevisionReadGuardsApplyWhileLegacyInspectionStillReturnsCandidate()
      throws Exception {
    try (var h = fixture().fixture().new Harness()) {
      var ledger = GraphicLedger.inputs(LIMITS);
      h.structure(h.first(), new DraftEdit.SetRaw("x".repeat(129)));
      code(refusal(capture(h, ledger).completion()), "preview.input_invalid");
      assertEquals(0, ledger.state().peakBytes());
      h.structure(h.first(), new DraftEdit.SetRaw("fixture:missing"));
      var lease = capture(h, ledger);
      await(lease.completion());
      h.documentRead.set(false);
      var denied = assertThrows(ExecutionException.class, () -> h.nativeCall(lease::borrow));
      assertInstanceOf(PolicyRefusal.class, denied.getCause());
      h.documentRead.set(true);
      h.structure(h.first(), new DraftEdit.SetRaw("fixture:a"));
      code(
          assertInstanceOf(
              PolicyRefusal.class,
              assertThrows(ExecutionException.class, () -> h.nativeCall(lease::borrow)).getCause()),
          "preview.stale");
      lease.close();
      assertEquals(0, ledger.state().bytes());
      var inspected =
          h.nativeCall(
              () ->
                  h.actions.capturePreview(
                      EntryPoint.GUI, DraftRevision.of(h.actions.view().snapshot().orElseThrow())));
      assertTrue(await(DocumentActions.result(inspected)).candidate().isPresent());
    }
  }

  @Test
  void eightCaptureSlotsAndPayloadAllowanceAreSharedAcrossBothInputKinds() throws Exception {
    var helper = fixture();
    try (var h = helper.fixture().new Harness()) {
      var token = helper.audition(h, helper.browser(h, "fixture:b"));
      var ledger = GraphicLedger.inputs(LIMITS);
      var drafts = new ArrayList<DraftGraphicCapture<Profile>>();
      var auditions = new ArrayList<AuditionCapture<Profile>>();
      for (int i = 0; i < 4; i++) {
        var draft = capture(h, ledger);
        await(draft.completion());
        drafts.add(draft);
        var other = helper.capture(h, token, ledger);
        await(other.completion());
        auditions.add(other);
      }
      assertEquals(8, ledger.state().resources());
      code(
          assertInstanceOf(
              PolicyRefusal.class,
              assertThrows(ExecutionException.class, () -> capture(h, ledger)).getCause()),
          "preview.busy");
      var use = h.nativeCall(drafts.getFirst()::borrow);
      drafts.getFirst().close();
      code(
          assertInstanceOf(
              PolicyRefusal.class,
              assertThrows(ExecutionException.class, () -> helper.capture(h, token, ledger))
                  .getCause()),
          "preview.busy");
      use.close();
      var next = helper.capture(h, token, ledger);
      await(next.completion());
      for (var draft : drafts) draft.close();
      for (var other : auditions) other.close();
      next.close();
      assertEquals(0, ledger.state().bytes());
      assertEquals(0, ledger.state().resources());
    }
  }
}
