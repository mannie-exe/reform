package design.inherent.reform.reference;

import static design.inherent.reform.reference.ReferenceSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.serialization.JsonLimits;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceAuditionTest {
  @TempDir Path root;
  static final GraphicLimits LIMITS = new GraphicLimits(32768, 32768, 4096, 16, 65536);
  static final JsonLimits JSON = new JsonLimits(32768, 64, 4096, 1024, 128, 1024);

  ReferenceSelectionTest fixture() {
    var fixture = new ReferenceSelectionTest();
    fixture.root = root;
    return fixture;
  }

  DraftRevision revision(ReferenceSelectionTest.Harness h) {
    return DraftRevision.of(h.actions.view().snapshot().orElseThrow());
  }

  ReferenceBrowser<List<String>> browser(ReferenceSelectionTest.Harness h, String key)
      throws Exception {
    var browser = h.nativeCall(() -> new ReferenceBrowser<>(h.references));
    resolve(h, browser, key);
    return browser;
  }

  void resolve(ReferenceSelectionTest.Harness h, ReferenceBrowser<List<String>> browser, String key)
      throws Exception {
    var lookup = h.nativeCall(() -> browser.resolve(EntryPoint.GUI, key));
    await(lookup.operation().completion());
    assertTrue(h.nativeCall(() -> browser.accept(lookup)));
  }

  ReferenceAudition<Profile> audition(
      ReferenceSelectionTest.Harness h, ReferenceBrowser<List<String>> browser) throws Exception {
    return h.nativeCall(
        () -> ReferenceSelection.auditionFromBrowser("models", h.actions, h.first(), browser));
  }

  AuditionCapture<Profile> capture(
      ReferenceSelectionTest.Harness h, ReferenceAudition<Profile> ref, GraphicLedger ledger)
      throws Exception {
    return h.nativeCall(() -> h.actions.captureAudition(EntryPoint.GUI, revision(h), ref, ledger));
  }

  void close(ReferenceSelectionTest.Harness h, AuditionCapture<?> value) throws Exception {
    h.nativeCall(
        () -> {
          value.close();
          return null;
        });
  }

  static PolicyRefusal refusal(CompletionStage<?> operation) {
    var failed = assertThrows(ExecutionException.class, () -> await(operation));
    return assertInstanceOf(PolicyRefusal.class, failed.getCause());
  }

  static void code(PolicyRefusal failure, String code) {
    assertTrue(
        failure.report().issues().stream().anyMatch(issue -> issue.code().equals(code)),
        failure.report().toString());
  }

  @Test
  void actualReadOnlyCaptureOverlaysOneFieldAndRetainsOnlyMetadataInHistory() throws Exception {
    try (var h = fixture().new Harness()) {
      byte[] saved = Files.readAllBytes(h.file);
      var initial = revision(h);
      h.write.set(false);
      var browser = browser(h, "fixture:empty"); // A resolved empty profile is still valid.
      var token = audition(h, browser);
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, token, ledger);
      assertSame(lease, await(lease.completion()));
      var use = h.nativeCall(lease::borrow);
      var overlay = use.value();
      assertEquals(List.of("fixture:empty", "fixture:b"), overlay.candidate().value().donors());
      assertEquals(new Primary("fixture:a"), overlay.candidate().value().mode());
      assertEquals(
          List.of("fixture:missing", "fixture:b"),
          overlay.base().candidate().orElseThrow().value().donors());
      assertEquals(initial, revision(h));
      assertFalse(h.actions.view().dirty());
      assertArrayEquals(saved, Files.readAllBytes(h.file));
      assertEquals(overlay.copiedBytes(), ledger.state().bytes());
      assertEquals(1, ledger.state().resources());
      var receipt = await(DocumentActions.result(lease.sourceOperation()));
      assertEquals(initial, receipt.base());
      assertEquals(overlay.basis(), receipt.basis());
      assertEquals(GraphicSourceStamp.Kind.AUDITION, receipt.basis().kind());
      assertEquals(h.first(), receipt.basis().target().path());
      assertEquals(browser.stamp(), receipt.basis().reference());
      assertEquals(
          GraphicInputBasis.Content.of(overlay.candidate().authoredJson()),
          receipt.basis().content());
      var input = new AuditionPreviewInput<>(new PreviewInputStamp(initial, 4), overlay, Map.of());
      assertEquals(input.base().stamp(), input.stamp());
      assertSame(overlay.candidate(), input.candidate());
      assertEquals(browser.stamp(), input.observedReferences().get(SOURCE));
      var resources =
          new ReferenceStamp(
              UUID.randomUUID(),
              DocumentId.parse("fixture:resources"),
              UUID.randomUUID(),
              "resources");
      var stamp =
          new GraphicRequest.Stamp(
              DocumentId.parse("fixture:provider"),
              UUID.randomUUID(),
              UUID.randomUUID(),
              initial.context(),
              input.stamp(),
              resources,
              input.basis());
      assertEquals(
          GraphicSourceStamp.Kind.AUDITION,
          new GraphicRequest<>(stamp, input, "barrel", LIMITS).stamp().kind());
      var draftStamp =
          new GraphicRequest.Stamp(
              stamp.provider(),
              stamp.view(),
              stamp.request(),
              stamp.context(),
              stamp.input(),
              resources);
      assertThrows(
          IllegalArgumentException.class,
          () -> new GraphicRequest<>(draftStamp, input, "barrel", LIMITS));
      assertEquals(GraphicSourceStamp.Kind.DRAFT, draftStamp.kind());
      use.close();
      close(h, lease);
      assertEquals(0, ledger.state().bytes());
      assertEquals(0, ledger.state().resources());
      assertNotNull(
          await(DocumentActions.result(lease.sourceOperation()))); // Receipt survives release.
      var unavailable = assertThrows(ExecutionException.class, () -> h.nativeCall(lease::borrow));
      code(assertInstanceOf(PolicyRefusal.class, unavailable.getCause()), "preview.capture_closed");
    }
  }

  @Test
  void invalidCompleteBaseCannotBeRepairedByAnAuditionAndRefusesBeforeCopiedAllocation()
      throws Exception {
    try (var h = fixture().new Harness()) {
      var browser = browser(h, "fixture:a");
      h.structure(h.first(), new DraftEdit.SetRaw("x".repeat(129)));
      var initial = revision(h);
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, audition(h, browser), ledger);
      code(refusal(lease.completion()), "preview.input_invalid");
      assertEquals(initial, revision(h));
      assertEquals(0, ledger.state().peakBytes());
      assertEquals(0, ledger.state().resources());
    }
  }

  @Test
  void copiedInputRefusesBeforeProjectionAndAggregatesAcrossReadyLeases() throws Exception {
    try (var h = fixture().new Harness()) {
      var token = audition(h, browser(h, "fixture:b"));
      var tiny = GraphicLedger.inputs(new GraphicLimits(1, 32768, 4096, 4, 65536));
      code(refusal(capture(h, token, tiny).completion()), "preview.budget");
      assertEquals(0, tiny.state().peakBytes());
      var measuring = GraphicLedger.inputs(LIMITS);
      var first = capture(h, token, measuring);
      await(first.completion());
      long bytes = measuring.state().bytes();
      close(h, first);
      var one = GraphicLedger.inputs(new GraphicLimits(bytes, 32768, 4096, 4, 65536));
      var ready = capture(h, token, one);
      await(ready.completion());
      code(refusal(capture(h, token, one).completion()), "preview.budget");
      assertEquals(bytes, one.state().bytes());
      close(h, ready);
      var retry = capture(h, token, one);
      await(retry.completion());
      close(h, retry);
      assertEquals(0, one.state().resources());
    }
  }

  @Test
  void actualReadAndInspectionEpochAreRecheckedWithoutGrantingWrite() throws Exception {
    try (var h = fixture().new Harness()) {
      var browser = browser(h, "fixture:b");
      var token = audition(h, browser);
      var ledger = GraphicLedger.inputs(LIMITS);
      var ready = capture(h, token, ledger);
      await(ready.completion());
      h.sourceRead.set(false);
      assertInstanceOf(
          PolicyRefusal.class,
          assertThrows(ExecutionException.class, () -> h.nativeCall(ready::borrow)).getCause());
      h.sourceRead.set(true);
      h.documentRead.set(false);
      assertInstanceOf(
          PolicyRefusal.class,
          assertThrows(ExecutionException.class, () -> h.nativeCall(ready::borrow)).getCause());
      h.documentRead.set(true);
      resolve(h, browser, "fixture:b"); // Same key, distinct accepted inspection.
      code(
          assertInstanceOf(
              PolicyRefusal.class,
              assertThrows(ExecutionException.class, () -> h.nativeCall(ready::borrow)).getCause()),
          "reference.selection_stale");
      close(h, ready);
      assertEquals(0, ledger.state().bytes());
      var refreshed = audition(h, browser);
      h.bind(); // Same display revision string, new actual source generation.
      assertInstanceOf(
          PolicyRefusal.class,
          assertThrows(ExecutionException.class, () -> capture(h, refreshed, ledger)).getCause());
      assertEquals(0, ledger.state().bytes());
    }
  }

  /** Wrap actual owner reads/preparation; never replace persistence or the original file. */
  static class ObservedSession implements DocumentSession<Profile> {
    final DocumentSession<Profile> actual;
    final Function<DraftTree, CompletionStage<?>> before;

    ObservedSession(
        DocumentSession<Profile> actual, Function<DraftTree, CompletionStage<?>> before) {
      this.actual = actual;
      this.before = before;
    }

    public DocumentId document() {
      return actual.document();
    }

    public DocumentDefinition<Profile> definition() {
      return actual.definition();
    }

    public UUID context() {
      return actual.context();
    }

    public UUID id() {
      return actual.id();
    }

    public boolean isOpen() {
      return actual.isOpen();
    }

    public Set<Operation> operations() {
      return actual.operations();
    }

    public CompletionStage<? extends OwnerObservation<Profile>> read() {
      return actual.read();
    }

    public CompletionStage<? extends PreparedEdit<Profile>> prepare(
        OwnerObservation<Profile> observed, DraftTree draft) {
      return before.apply(draft).thenCompose(ignored -> actual.prepare(observed, draft));
    }

    public CompletionStage<? extends CommitReceipt<Profile>> commit(PreparedEdit<Profile> value) {
      return actual.commit(value);
    }

    public void close() {
      actual.close();
    }
  }

  boolean alternate(DraftTree draft) {
    return draft
        .inspect(DEFINITION, JSON)
        .candidate()
        .orElseThrow()
        .value()
        .donors()
        .getFirst()
        .equals("fixture:b");
  }

  @Test
  void actualBackendChecksAlternateCandidateAndItsRefusalReleasesChargeWithoutEditing()
      throws Exception {
    var alternateChecks = new AtomicInteger();
    try (var h =
        fixture()
        .new Harness(
            actual ->
                new ObservedSession(
                    actual,
                    draft -> {
                      if (!alternate(draft)) return CompletableFuture.completedFuture(null);
                      alternateChecks.incrementAndGet();
                      return CompletableFuture.failedFuture(
                          new PolicyRefusal(
                              "fixture.backend", "Owner rejected the alternate candidate"));
                    }))) {
      var initial = revision(h);
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, audition(h, browser(h, "fixture:b")), ledger);
      code(refusal(lease.completion()), "fixture.backend");
      assertEquals(1, alternateChecks.get());
      assertTrue(ledger.state().peakBytes() > 0);
      assertEquals(0, ledger.state().bytes());
      assertEquals(initial, revision(h));
    }
  }

  @Test
  void closeWhileActualBackendEnteredDoesNotRefundOrCompleteUntilDrain() throws Exception {
    var entered = new CountDownLatch(1);
    var drain = new CompletableFuture<Void>();
    try (var h =
        fixture()
        .new Harness(
            actual ->
                new ObservedSession(
                    actual,
                    draft -> {
                      if (!alternate(draft)) return CompletableFuture.completedFuture(null);
                      entered.countDown();
                      return drain;
                    }))) {
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, audition(h, browser(h, "fixture:b")), ledger);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      long charged = ledger.state().bytes();
      assertTrue(charged > 0);
      close(h, lease);
      assertFalse(lease.completion().toCompletableFuture().isDone());
      assertFalse(lease.sourceOperation().snapshot().terminal());
      assertEquals(charged, ledger.state().bytes());
      drain.complete(null);
      code(refusal(lease.completion()), "operation.cancelled");
      assertEquals(0, ledger.state().bytes());
      assertEquals(0, ledger.state().resources());
      assertFalse(h.actions.view().dirty());
    } finally {
      drain.complete(null);
    }
  }

  @Test
  void fullDefinitionCouplingCannotBeBypassedByReplacingJustAReference() throws Exception {
    var definition =
        new DocumentDefinition<>(
            DEFINITION.id(),
            1,
            DEFINITION.type(),
            DEFINITION::defaults,
            value ->
                value.donors().getFirst().equals("fixture:b")
                    ? new design.inherent.reform.validation.ValidationReport(
                        List.of(
                            design.inherent.reform.validation.Issue.error(
                                "fixture.coupled",
                                FieldPath.ROOT,
                                "This profile conflicts with the other fields")),
                        true)
                    : design.inherent.reform.validation.ValidationReport.VALID);
    try (var h = fixture().new Harness(UnaryOperator.identity(), definition)) {
      var initial = revision(h);
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, audition(h, browser(h, "fixture:b")), ledger);
      var refused = refusal(lease.completion());
      code(refused, "preview.audition_invalid");
      code(refused, "fixture.coupled");
      assertTrue(ledger.state().peakBytes() > 0);
      assertEquals(0, ledger.state().bytes());
      assertEquals(initial, revision(h));
    }
  }

  @Test
  void readyInputRemainsChargedAfterEditorCloseUntilLastEnteredConsumerDrains() throws Exception {
    try (var h = fixture().new Harness()) {
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, audition(h, browser(h, "fixture:b")), ledger);
      await(lease.completion());
      var first = h.nativeCall(lease::borrow);
      var second = h.nativeCall(lease::borrow);
      var bytes = ledger.state().bytes();
      h.nativeCall(
          () -> {
            h.actions.close();
            return null;
          });
      assertEquals(bytes, ledger.state().bytes());
      assertNotNull(first.value().candidate());
      first.close();
      first.close();
      assertEquals(bytes, ledger.state().bytes());
      second.close();
      assertEquals(0, ledger.state().bytes());
      assertEquals(0, ledger.state().resources());
      assertThrows(IllegalStateException.class, second::value);
    }
  }

  @Test
  void sourceChangeDuringEnteredBackendCannotDeliverTheAlternateCandidate() throws Exception {
    var entered = new CountDownLatch(1);
    var drain = new CompletableFuture<Void>();
    try (var h =
        fixture()
        .new Harness(
            actual ->
                new ObservedSession(
                    actual,
                    draft -> {
                      if (!alternate(draft)) return CompletableFuture.completedFuture(null);
                      entered.countDown();
                      return drain;
                    }))) {
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, audition(h, browser(h, "fixture:b")), ledger);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      h.bind();
      assertTrue(ledger.state().bytes() > 0);
      drain.complete(null);
      var refused = refusal(lease.completion());
      assertFalse(refused.report().valid());
      assertEquals(0, ledger.state().bytes());
      assertFalse(h.actions.view().dirty());
    } finally {
      drain.complete(null);
    }
  }

  @Test
  void contextCloseRetainsEnteredInputUntilBackendDrainWithoutOwnerResurrection() throws Exception {
    var entered = new CountDownLatch(1);
    var drain = new CompletableFuture<Void>();
    try (var h =
        fixture()
        .new Harness(
            actual ->
                new ObservedSession(
                    actual,
                    draft -> {
                      if (!alternate(draft)) return CompletableFuture.completedFuture(null);
                      entered.countDown();
                      return drain;
                    }))) {
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, audition(h, browser(h, "fixture:b")), ledger);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      assertTrue(ledger.state().bytes() > 0);
      assertFalse(lease.completion().toCompletableFuture().isDone());
      drain.complete(null);
      refusal(lease.completion());
      assertEquals(0, ledger.state().bytes());
      assertEquals(0, ledger.state().resources());
      assertFalse(h.context.isOpen());
    } finally {
      drain.complete(null);
    }
  }

  @Test
  void referenceOverlayUsesTheDeclaredCodecAndAccountsForEscapedUnicodeJson() throws Exception {
    var encodedKey =
        design.inherent.reform.value.ValueType.of(
            com.mojang.serialization.Codec.STRING.xmap(
                value -> value.toUpperCase(java.util.Locale.ROOT),
                value -> value.toLowerCase(java.util.Locale.ROOT)),
            design.inherent.reform.value.ValueTypes.text(128).shape(),
            design.inherent.reform.value.SnapshotPolicy.<String>immutable());
    var donors =
        FieldKey.of(
            "donors",
            design.inherent.reform.value.ValueTypes.list(
                design.inherent.reform.value.ValueTypes.reference(encodedKey, SOURCE), 4));
    var type =
        RecordType.<Profile>builder(
                values -> new Profile(values.get(donors), values.get(MODE_FIELD)))
            .field(donors, Profile::donors)
            .field(MODE_FIELD, Profile::mode)
            .build();
    var definition = new DocumentDefinition<>(DEFINITION.id(), 1, type, DEFINITION::defaults);
    try (var h = fixture().new Harness(UnaryOperator.identity(), definition)) {
      String key = "fixture:中文<☃>&é";
      h.nativeCall(
          () ->
              h.references.bind(
                  "unicode",
                  stamp ->
                      new ReferenceIndex<>(
                          stamp,
                          design.inherent.reform.value.ValueTypes.text(128),
                          design.inherent.reform.value.ValueTypes.list(
                                  design.inherent.reform.value.ValueTypes.text(128), 16)
                              .snapshotPolicy(),
                          ReferenceIndexTest.LIMITS,
                          List.of(ReferenceIndexTest.entry(key, "Unicode")))));
      var token = audition(h, browser(h, key));
      var ledger = GraphicLedger.inputs(LIMITS);
      var lease = capture(h, token, ledger);
      await(lease.completion());
      try (var use = h.nativeCall(lease::borrow)) {
        assertEquals(
            key.toUpperCase(java.util.Locale.ROOT),
            use.value().candidate().value().donors().getFirst());
        assertEquals(key, use.value().basis().key());
        assertTrue(use.value().candidate().authoredJson().contains("\\u003c"));
        assertEquals(
            GraphicInputBasis.Content.of(use.value().candidate().authoredJson()),
            use.value().basis().content());
      }
      close(h, lease);
      assertEquals(0, ledger.state().bytes());
    }
  }

  @Test
  void anInvalidSiblingRemainsRefusedEvenWhenTheTargetFieldIsValid() throws Exception {
    try (var h = fixture().new Harness()) {
      var sequence =
          (DraftNode.Sequence)
              h.actions.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field("donors"));
      h.structure(
          FieldPath.ROOT.field("donors").row(sequence.rows().get(1).id()),
          new DraftEdit.SetRaw("x".repeat(129)));
      var ledger = GraphicLedger.inputs(LIMITS);
      code(
          refusal(capture(h, audition(h, browser(h, "fixture:b")), ledger).completion()),
          "preview.input_invalid");
      assertEquals(0, ledger.state().peakBytes());
    }
  }

  @Test
  void captureSlotsAreBoundedAcrossCompletedAndRetiringOwnedInputs() throws Exception {
    try (var h = fixture().new Harness()) {
      var token = audition(h, browser(h, "fixture:b"));
      var ledger = GraphicLedger.inputs(LIMITS);
      var leases = new ArrayList<AuditionCapture<Profile>>();
      for (int i = 0; i < 8; i++) {
        var lease = capture(h, token, ledger);
        await(lease.completion());
        leases.add(lease);
      }
      code(
          assertInstanceOf(
              PolicyRefusal.class,
              assertThrows(ExecutionException.class, () -> capture(h, token, ledger)).getCause()),
          "preview.busy");
      var use = h.nativeCall(leases.getFirst()::borrow);
      close(h, leases.getFirst());
      code(
          assertInstanceOf(
              PolicyRefusal.class,
              assertThrows(ExecutionException.class, () -> capture(h, token, ledger)).getCause()),
          "preview.busy");
      use.close();
      var next = capture(h, token, ledger);
      await(next.completion());
      for (var lease : leases) close(h, lease);
      close(h, next);
      assertEquals(0, ledger.state().resources());
    }
  }
}
