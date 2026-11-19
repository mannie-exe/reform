package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.RuntimePolicy;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DocumentReviewTest {
  private record Profile(int count, List<String> names) {}

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  private static final FieldKey<List<String>> NAMES =
      FieldKey.of("names", ValueTypes.list(ValueTypes.text(64), 8));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(values -> new Profile(values.get(COUNT), values.get(NAMES)))
          .field(COUNT, Profile::count)
          .field(NAMES, Profile::names)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:review"),
          1,
          TYPE,
          () -> new Profile(1, List.of("first")),
          ignored -> ValidationReport.VALID);
  private static final SourceInfo SOURCE =
      new SourceInfo("fixture", "Observed source only", Optional.of("profile.json"));
  private static final JsonLimits JSON = new JsonLimits(8192, 64, 1024, 4096, 128, 4096);

  private static EditorActor.Inspection<Profile> inspection(
      DraftTree draft, DraftTree baseline, Optional<SettingsHandle.View<Profile>> effective) {
    return new EditorActor.Inspection<>(
        new EditSession.Snapshot<>(
            DEFINITION, UUID.randomUUID(), UUID.randomUUID(), 0, 1, "source-1", draft, JSON),
        baseline,
        SOURCE,
        effective,
        ValidationReport.VALID,
        Optional.empty(),
        ApplicationEffects.UNDECLARED);
  }

  private static DraftTree baseline() {
    return DraftTree.from(TYPE, DEFINITION.defaults(), DraftLimits.CONFIGURATION);
  }

  private static DocumentActions.Comparison comparison(EditorActor.Inspection<Profile> input) {
    return new DocumentActions.Comparison(
        DraftRevision.of(input.snapshot()),
        !input.snapshot().draft().sameValue(input.baseline()),
        List.of(),
        input.snapshot().inspect().report());
  }

  private static DocumentReview capture(EditorActor.Inspection<Profile> input) {
    return DocumentReview.capture(
        input, comparison(input), new FieldIndex(TYPE, 32, 8), DocumentReview.Limits.STANDARD);
  }

  @Test
  void sourceAndWholeCollectionDetailsRetainActualInvalidDraftWithoutRowAlignment() {
    var before = baseline();
    var draft = before.change(FieldPath.ROOT.field("count"), ignored -> new DraftNode.Scalar("-"));
    var input = inspection(draft, before, Optional.empty());
    var review = capture(input);
    assertEquals(DraftRevision.of(input.snapshot()), review.target());
    assertSame(SOURCE, review.source());
    assertTrue(review.effective().isEmpty());
    assertFalse(review.comparison().report().valid());
    assertFalse(review.report().valid());
    assertTrue(review.comparison().authoredChanges());
    assertEquals("\"1\"", review.entries().getFirst().baseline());
    assertEquals("\"-\"", review.entries().getFirst().draft());
    assertFalse(review.entries().getFirst().wholeValue());
    assertTrue(review.entries().stream().allMatch(entry -> entry.effective().isEmpty()));
    var names = (DraftNode.Sequence) draft.at(FieldPath.ROOT.field("names"));
    assertFalse(review.entries().get(1).draft().contains(names.rows().getFirst().id().toString()));
    assertEquals("names", review.entries().get(1).selector());
    assertTrue(review.entries().get(1).wholeValue());
    assertThrows(UnsupportedOperationException.class, () -> review.entries().clear());
    assertEquals("-", ((DraftNode.Scalar) draft.at(FieldPath.ROOT.field("count"))).raw());
  }

  @Test
  void declaredEffectsAreCapturedIndependentlyOfDraftValidityAndLastReceipts() {
    var before = baseline();
    var input =
        inspection(
            before.change(FieldPath.ROOT.field("count"), ignored -> new DraftNode.Scalar("-")),
            before,
            Optional.empty());
    var effects =
        ApplicationEffects.root(
            ApplicationEffects.Timing.GAME_RESTART, "This owner requires a game restart");
    var review =
        capture(
            new EditorActor.Inspection<>(
                input.snapshot(),
                input.baseline(),
                input.provenance(),
                input.effective(),
                input.observationReport(),
                Optional.empty(),
                effects));
    assertFalse(review.report().valid());
    assertSame(effects, review.applicationEffects());
    assertTrue(
        review.entries().stream()
            .allMatch(
                entry ->
                    entry
                        .application()
                        .orElseThrow()
                        .timings()
                        .equals(java.util.Set.of(ApplicationEffects.Timing.GAME_RESTART))));
    var refused =
        assertThrows(
            PolicyRefusal.class,
            () ->
                DocumentReview.capture(
                    new EditorActor.Inspection<>(
                        input.snapshot(),
                        input.baseline(),
                        input.provenance(),
                        input.effective(),
                        input.observationReport(),
                        Optional.empty(),
                        ApplicationEffects.root(
                            ApplicationEffects.Timing.GAME_RESTART, "x".repeat(4096))),
                    comparison(input),
                    new FieldIndex(TYPE, 32, 8),
                    new DocumentReview.Limits(10, 1000, 1024)));
    assertEquals("review.preview_limit", refused.report().issues().getFirst().code());
    assertTrue(input.lastReceipt().isEmpty());
  }

  @Test
  void sourceFailureDoesNotDisappearBehindAValidUnchangedComparison() {
    var before = baseline();
    var input = inspection(before, before, Optional.empty());
    var failed =
        new ValidationReport(
            List.of(Issue.error("fixture.source", FieldPath.ROOT, "Source observation failed")),
            false);
    var review =
        capture(
            new EditorActor.Inspection<>(
                input.snapshot(),
                input.baseline(),
                input.provenance(),
                input.effective(),
                failed,
                input.lastReceipt(),
                input.applicationEffects()));
    assertTrue(review.comparison().report().valid());
    assertFalse(review.comparison().authoredChanges());
    assertFalse(review.report().complete());
    assertEquals(failed.issues(), review.report().issues());
    assertSame(failed, review.sourceReport());
  }

  @Test
  void effectiveObservationFailureRetainsQualifiedValueAndClosedMeansNoValue() {
    var before = baseline();
    var policy = new RuntimePolicy<>(DEFINITION, () -> {});
    try {
      policy
          .prepare(
              policy.handle().view().revision(),
              before.inspect(DEFINITION, JSON).candidate().orElseThrow(),
              () -> true)
          .publish(() -> true);
      var failure =
          new ValidationReport(
              List.of(Issue.error("fixture.read", FieldPath.ROOT, "Later read failed")), false);
      policy.observationFailed(failure);
      var review = capture(inspection(before, before, Optional.of(policy.handle().view())));
      var observed = review.effective().orElseThrow();
      assertEquals(SettingsHandle.Availability.AVAILABLE, observed.availability());
      assertTrue(observed.valuesCaptured());
      assertSame(failure, observed.observationReport());
      assertEquals(failure.issues(), review.report().issues());
      assertFalse(review.report().complete());
      assertTrue(review.comparison().report().valid());
      assertEquals(Optional.of("\"1\""), review.entries().getFirst().effective());
      assertTrue(observed.captureReport().valid());
      policy.close();
      var closed = capture(inspection(before, before, Optional.of(policy.handle().view())));
      assertEquals(
          SettingsHandle.Availability.CLOSED, closed.effective().orElseThrow().availability());
      assertFalse(closed.effective().orElseThrow().valuesCaptured());
      assertTrue(closed.entries().stream().allMatch(entry -> entry.effective().isEmpty()));
    } finally {
      policy.close();
    }
  }

  @Test
  void failedEffectiveDetailsStayUnknownWithoutHidingSourceOrLeakingExceptionText() {
    var fail = new AtomicBoolean();
    var type =
        ValueType.of(
            TYPE.codec(),
            TYPE.shape(),
            (Profile value, design.inherent.reform.value.SnapshotContext ignored) -> {
              if (fail.get()) throw new IllegalStateException("private diagnostic details");
              return value;
            });
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:effective_review"),
            1,
            type,
            DEFINITION::defaults,
            ignored -> ValidationReport.VALID);
    var before = baseline();
    try (var policy = new RuntimePolicy<>(definition, () -> {})) {
      policy
          .prepare(
              policy.handle().view().revision(),
              before.inspect(definition, JSON).candidate().orElseThrow(),
              () -> true)
          .publish(() -> true);
      fail.set(true);
      var review = capture(inspection(before, before, Optional.of(policy.handle().view())));
      var effective = review.effective().orElseThrow();
      assertEquals(SettingsHandle.Availability.AVAILABLE, effective.availability());
      assertFalse(effective.valuesCaptured());
      assertFalse(effective.captureReport().complete());
      assertEquals(
          "review.effective_unavailable", effective.captureReport().issues().getFirst().code());
      assertFalse(effective.captureReport().toString().contains("private diagnostic"));
      assertSame(SOURCE, review.source());
      assertTrue(review.comparison().report().valid());
      assertEquals(effective.captureReport().issues(), review.report().issues());
      assertFalse(review.report().complete());
      assertTrue(review.entries().stream().allMatch(entry -> entry.effective().isEmpty()));
    }
  }

  @Test
  void completeAdmissionRefusesFieldsTextAndNodesBeforePublishingAnyPartialReview() {
    var before = baseline();
    var input = inspection(before, before, Optional.empty());
    for (var limits :
        List.of(
            new DocumentReview.Limits(1, 100, 10000),
            new DocumentReview.Limits(10, 100, 4),
            new DocumentReview.Limits(10, 1, 10000))) {
      var refused =
          assertThrows(
              PolicyRefusal.class,
              () ->
                  DocumentReview.capture(
                      input, comparison(input), new FieldIndex(TYPE, 32, 8), limits));
      assertTrue(refused.report().issues().getFirst().code().startsWith("review."));
      assertSame(before, input.snapshot().draft());
    }
    assertThrows(IllegalArgumentException.class, () -> new DocumentReview.Limits(0, 10, 10));
  }

  @Test
  void cannotJoinComparisonFromAnotherDraftRevision() {
    var before = baseline();
    var first = inspection(before, before, Optional.empty());
    var other = inspection(before, before, Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DocumentReview.capture(
                first,
                comparison(other),
                new FieldIndex(TYPE, 32, 8),
                DocumentReview.Limits.STANDARD));
  }

  @Test
  void malformedRootIsAvailableAsOneWholeValueInsteadOfInventedMissingFields() {
    var before = baseline();
    var malformed = new DraftTree(new DraftNode.Opaque("[1,2]"), DraftLimits.CONFIGURATION);
    var review = capture(inspection(malformed, before, Optional.empty()));
    assertEquals(1, review.entries().size());
    assertEquals(FieldPath.ROOT, review.entries().getFirst().path());
    assertEquals("$", review.entries().getFirst().selector());
    assertTrue(review.entries().getFirst().wholeValue());
    assertTrue(review.entries().getFirst().draft().contains("[1,2]"));
    assertFalse(review.comparison().report().valid());
  }
}
