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
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FieldInformationTest {
  private record Profile(int count, List<String> names) {}

  private static final FieldPath COUNT_PATH = FieldPath.ROOT.field("count");
  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  private static final FieldKey<List<String>> NAMES =
      FieldKey.of("names", ValueTypes.list(ValueTypes.text(8192), 8));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(values -> new Profile(values.get(COUNT), values.get(NAMES)))
          .field(COUNT, Profile::count)
          .field(NAMES, Profile::names)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:information"),
          1,
          TYPE,
          () -> new Profile(1, List.of("original")));
  private static final JsonLimits JSON = new JsonLimits(32768, 64, 1024, 16384, 128, 16384);
  private static final SourceInfo SOURCE =
      new SourceInfo("fixture", "Captured source", Optional.of("profile.json"));

  private static DraftTree before() {
    return DraftTree.from(TYPE, DEFINITION.defaults(), DraftLimits.CONFIGURATION);
  }

  @Test
  void captionsAccompanyEachActualObservationWithoutReplacingRawValuesOrUnknownState() {
    var off = new NumericDisplay.Caption(BigDecimal.ZERO, "fixture.off", "Off");
    var count =
        FieldKey.of(
            "count",
            ValueTypes.numericDisplay(
                ValueTypes.integer(0, 10),
                NumericDisplay.ticks(BigDecimal.valueOf(20)).withCaptions(List.of(off))));
    var type =
        RecordType.<Profile>builder(values -> new Profile(values.get(count), values.get(NAMES)))
            .field(count, Profile::count)
            .field(NAMES, Profile::names)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:numeric_information"),
            1,
            type,
            () -> new Profile(0, List.of()));
    var baseline = DraftTree.from(type, definition.defaults(), DraftLimits.CONFIGURATION);
    var changed = baseline.change(COUNT_PATH, ignored -> new DraftNode.Scalar("1"));
    var policy = new RuntimePolicy<>(definition, () -> {});
    try {
      policy
          .prepare(
              policy.handle().view().revision(),
              baseline.inspect(definition, JSON).candidate().orElseThrow(),
              () -> true)
          .publish(() -> true);
      var observed = input(definition, changed, baseline, Optional.of(policy.handle().view()));
      var info = FieldInformation.capture(observed, COUNT_PATH, DocumentReview.Limits.STANDARD);
      assertEquals(Optional.of(off), info.baseline().numericCaption());
      assertEquals(Optional.of(off), info.declaredDefault().numericCaption());
      assertEquals(Optional.of(off), info.effective().numericCaption());
      assertTrue(info.draft().numericCaption().isEmpty());
      assertEquals(Optional.of("\"0\""), info.baseline().text());
      assertEquals(Optional.of("\"1\""), info.draft().text());
      var comparison =
          new DocumentActions.Comparison(
              DraftRevision.of(observed.snapshot()), true, List.of(), ValidationReport.VALID);
      var review =
          DocumentReview.capture(
              observed, comparison, new FieldIndex(type, 32, 16), DocumentReview.Limits.STANDARD);
      var entry =
          review.entries().stream()
              .filter(value -> value.path().equals(COUNT_PATH))
              .findFirst()
              .orElseThrow();
      assertEquals(Optional.of(off), entry.numericValues().baseline());
      assertEquals(Optional.of(off), entry.numericValues().effective());
      assertTrue(entry.numericValues().draft().isEmpty());
      assertEquals("\"0\"", entry.baseline());
      assertEquals(Optional.of("\"0\""), entry.effective());
      var unknown =
          FieldInformation.capture(
              input(definition, baseline, baseline, Optional.empty()),
              COUNT_PATH,
              DocumentReview.Limits.STANDARD);
      assertTrue(unknown.effective().numericCaption().isEmpty());
      assertEquals(FieldInformation.State.UNAVAILABLE, unknown.effective().state());
      assertThrows(
          PolicyRefusal.class,
          () ->
              FieldInformation.capture(
                  observed, COUNT_PATH, new DocumentReview.Limits(32, 1024, 100)));
      assertEquals("1", ((DraftNode.Scalar) changed.at(COUNT_PATH)).raw());
    } finally {
      policy.close();
    }
  }

  private static EditorActor.Inspection<Profile> input(
      DocumentDefinition<Profile> definition,
      DraftTree draft,
      DraftTree baseline,
      Optional<SettingsHandle.View<Profile>> effective) {
    return new EditorActor.Inspection<>(
        new EditSession.Snapshot<>(
            definition, UUID.randomUUID(), UUID.randomUUID(), 2, 3, "observed-source", draft, JSON),
        baseline,
        SOURCE,
        effective,
        ValidationReport.VALID,
        Optional.empty(),
        ApplicationEffects.root(ApplicationEffects.Timing.GAME_RESTART, "Restart to activate"));
  }

  @Test
  void selectedInvalidInputIsDistinctFromBaselineDeclaredDefaultAndEffectiveValue() {
    var before = before();
    var draft = before.change(COUNT_PATH, ignored -> new DraftNode.Scalar("-"));
    var policy = new RuntimePolicy<>(DEFINITION, () -> {});
    try {
      var active =
          DraftTree.from(TYPE, new Profile(7, List.of("active")), DraftLimits.CONFIGURATION);
      policy
          .prepare(
              policy.handle().view().revision(),
              active.inspect(DEFINITION, JSON).candidate().orElseThrow(),
              () -> true)
          .publish(() -> true);
      var report =
          new ValidationReport(
              List.of(Issue.error("fixture.later_read", COUNT_PATH, "Later observation failed")),
              false);
      policy.observationFailed(report);
      var info =
          FieldInformation.capture(
              input(DEFINITION, draft, before, Optional.of(policy.handle().view())),
              COUNT_PATH,
              DocumentReview.Limits.STANDARD);
      assertEquals(Optional.of("\"-\""), info.draft().text());
      assertEquals(Optional.of("\"1\""), info.baseline().text());
      assertEquals(Optional.of("\"1\""), info.declaredDefault().text());
      assertEquals(Optional.of("\"7\""), info.effective().text());
      assertSame(report, info.effectiveObservation().orElseThrow().observationReport());
      assertEquals("observed-source", info.target().sourceRevision());
      assertSame(SOURCE, info.source());
      assertEquals(
          java.util.Set.of(ApplicationEffects.Timing.GAME_RESTART),
          info.application().orElseThrow().timings());
      assertEquals("-", ((DraftNode.Scalar) draft.at(COUNT_PATH)).raw());
    } finally {
      policy.close();
    }
  }

  @Test
  void editorRowIdentityNeverSelectsAnEffectiveOrDefaultRowByPosition() {
    var before = before();
    var row = ((DraftNode.Sequence) before.at(FieldPath.ROOT.field("names"))).rows().getFirst();
    var path = FieldPath.ROOT.field("names").row(row.id());
    var policy = new RuntimePolicy<>(DEFINITION, () -> {});
    try {
      var info =
          FieldInformation.capture(
              input(DEFINITION, before, before, Optional.of(policy.handle().view())),
              path,
              DocumentReview.Limits.STANDARD);
      assertEquals(Optional.of("\"original\""), info.draft().text());
      assertEquals(FieldInformation.State.UNAVAILABLE, info.declaredDefault().state());
      assertEquals(FieldInformation.State.UNAVAILABLE, info.effective().state());
      assertFalse(info.effectiveObservation().orElseThrow().valuesCaptured());
    } finally {
      policy.close();
    }
  }

  @Test
  void selectedFieldBudgetDoesNotWalkOtherFieldsAndDoesNotReturnPartialValue() {
    var before =
        DraftTree.from(
            TYPE,
            new Profile(1, List.of("long unrelated text".repeat(200))),
            DraftLimits.CONFIGURATION);
    var input = input(DEFINITION, before, before, Optional.empty());
    var bounds = new DocumentReview.Limits(1, 16, 256);
    assertEquals(
        Optional.of("\"1\""), FieldInformation.capture(input, COUNT_PATH, bounds).draft().text());
    var refused =
        assertThrows(
            PolicyRefusal.class,
            () -> FieldInformation.capture(input, FieldPath.ROOT.field("names"), bounds));
    assertEquals("information.limit", refused.report().issues().getFirst().code());
    assertEquals(
        FieldInformation.State.UNAVAILABLE,
        FieldInformation.capture(input, COUNT_PATH, bounds).effective().state());
  }

  @Test
  void unavailableDefaultAndMissingBaselineStayDistinctWithoutDiagnosticLeakage() {
    var failDefault = new java.util.concurrent.atomic.AtomicBoolean();
    var type =
        design.inherent.reform.value.ValueType.of(
            TYPE.codec(),
            TYPE.shape(),
            (Profile value, design.inherent.reform.value.SnapshotContext ignored) -> {
              if (failDefault.get()) throw new IllegalStateException("private default diagnostic");
              return value;
            });
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:no_default"), 1, type, DEFINITION::defaults);
    failDefault.set(true);
    var draft = before();
    var baseline =
        draft.change(FieldPath.ROOT, ignored -> new DraftNode.ObjectValue(java.util.Map.of()));
    var info =
        FieldInformation.capture(
            input(definition, draft, baseline, Optional.empty()),
            COUNT_PATH,
            DocumentReview.Limits.STANDARD);
    assertEquals(FieldInformation.State.ABSENT, info.baseline().state());
    assertEquals(FieldInformation.State.UNAVAILABLE, info.declaredDefault().state());
    assertFalse(info.declaredDefault().reason().orElseThrow().contains("private"));
    assertEquals(FieldInformation.State.PRESENT, info.draft().state());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FieldInformation.Value(
                FieldInformation.State.ABSENT, Optional.of("made up"), Optional.empty()));
  }

  @Test
  void absentOrUndeclaredSelectionRefusesRatherThanSubstitutingRoot() {
    var before = before();
    var input = input(DEFINITION, before, before, Optional.empty());
    for (var path :
        List.of(
            FieldPath.ROOT.field("unknown"),
            FieldPath.ROOT.field("names").row(UUID.randomUUID()))) {
      var refusal =
          assertThrows(
              PolicyRefusal.class,
              () -> FieldInformation.capture(input, path, DocumentReview.Limits.STANDARD));
      assertEquals("information.field", refusal.report().issues().getFirst().code());
    }
  }
}
