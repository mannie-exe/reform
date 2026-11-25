package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChoiceObservationTest {
  enum Mode {
    AUTO,
    ON,
    OFF
  }

  record Profile(Mode mode, int count) {}

  private static final FieldPath MODE_PATH = FieldPath.ROOT.field("mode");
  private static final FieldKey<Mode> MODE =
      FieldKey.of(
          "mode",
          ValueTypes.choiceDisplay(
              ValueTypes.enumeration(Mode.class),
              new ChoiceDisplay(
                  List.of(
                      new ChoiceDisplay.Caption("AUTO", "fixture.mode.auto", "Auto"),
                      new ChoiceDisplay.Caption("ON", "fixture.mode.on", "On"),
                      new ChoiceDisplay.Caption("OFF", "fixture.mode.off", "Off")))));
  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(v -> new Profile(v.get(MODE), v.get(COUNT)))
          .field(MODE, Profile::mode)
          .field(COUNT, Profile::count)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:choice_observations"),
          1,
          TYPE,
          () -> new Profile(Mode.AUTO, 1));
  private static final JsonLimits JSON = new JsonLimits(8192, 64, 1024, 4096, 128, 4096);

  private static EditorActor.Inspection<Profile> input(
      DraftTree draft, DraftTree baseline, Optional<SettingsHandle.View<Profile>> effective) {
    return new EditorActor.Inspection<>(
        new EditSession.Snapshot<>(
            DEFINITION, UUID.randomUUID(), UUID.randomUUID(), 0, 1, "source-1", draft, JSON),
        baseline,
        new SourceInfo("fixture", "Captured source", Optional.empty()),
        effective,
        ValidationReport.VALID,
        Optional.empty(),
        ApplicationEffects.UNDECLARED);
  }

  private static DocumentActions.Comparison comparison(EditorActor.Inspection<Profile> input) {
    return new DocumentActions.Comparison(
        DraftRevision.of(input.snapshot()), true, List.of(), input.snapshot().inspect().report());
  }

  @Test
  void knownLabelsFollowEachTypedObservationWithoutReplacingRawReports() {
    var baseline = DraftTree.from(TYPE, DEFINITION.defaults(), DraftLimits.CONFIGURATION);
    var draft = baseline.change(MODE_PATH, ignored -> new DraftNode.Scalar("ON"));
    var policy = new RuntimePolicy<>(DEFINITION, () -> {});
    try {
      var effective = DraftTree.from(TYPE, new Profile(Mode.OFF, 1), DraftLimits.CONFIGURATION);
      policy
          .prepare(
              policy.handle().view().revision(),
              effective.inspect(DEFINITION, JSON).candidate().orElseThrow(),
              () -> true)
          .publish(() -> true);
      var observed = input(draft, baseline, Optional.of(policy.handle().view()));
      var review =
          DocumentReview.capture(
              observed,
              comparison(observed),
              new FieldIndex(TYPE, 32, 8),
              DocumentReview.Limits.STANDARD);
      var field =
          review.entries().stream()
              .filter(e -> e.path().equals(MODE_PATH))
              .findFirst()
              .orElseThrow();
      assertEquals("\"AUTO\"", field.baseline());
      assertEquals("\"ON\"", field.draft());
      assertEquals(Optional.of("\"OFF\""), field.effective());
      assertEquals("Auto", field.choiceValues().baseline().orElseThrow().fallback());
      assertEquals("On", field.choiceValues().draft().orElseThrow().fallback());
      assertEquals("Off", field.choiceValues().effective().orElseThrow().fallback());
      var info = FieldInformation.capture(observed, MODE_PATH, DocumentReview.Limits.STANDARD);
      assertEquals("AUTO", info.baseline().choiceCaption().orElseThrow().value());
      assertEquals("ON", info.draft().choiceCaption().orElseThrow().value());
      assertEquals("OFF", info.effective().choiceCaption().orElseThrow().value());
      assertEquals("AUTO", info.declaredDefault().choiceCaption().orElseThrow().value());
      assertEquals(Optional.of("\"ON\""), info.draft().text());
      assertTrue(info.draft().numericCaption().isEmpty());
    } finally {
      policy.close();
    }
  }

  @Test
  void invalidRawAndValidSiblingRemainInCompleteCaptureWithoutInventedMeaning() {
    var baseline = DraftTree.from(TYPE, DEFINITION.defaults(), DraftLimits.CONFIGURATION);
    var draft =
        baseline
            .change(MODE_PATH, ignored -> new DraftNode.Scalar("not a mode"))
            .change(FieldPath.ROOT.field("count"), ignored -> new DraftNode.Scalar("7"));
    var observed = input(draft, baseline, Optional.empty());
    var review =
        DocumentReview.capture(
            observed,
            comparison(observed),
            new FieldIndex(TYPE, 32, 8),
            DocumentReview.Limits.STANDARD);
    assertEquals("\"not a mode\"", review.entries().getFirst().draft());
    assertTrue(review.entries().getFirst().choiceValues().draft().isEmpty());
    assertTrue(review.entries().getFirst().choiceValues().effective().isEmpty());
    assertEquals("\"7\"", review.entries().get(1).draft());
    assertFalse(review.report().valid());
    var info = FieldInformation.capture(observed, MODE_PATH, DocumentReview.Limits.STANDARD);
    assertTrue(info.draft().choiceCaption().isEmpty());
    assertEquals(Optional.of("\"not a mode\""), info.draft().text());
    assertEquals(FieldInformation.State.UNAVAILABLE, info.effective().state());
  }

  @Test
  void typedComparisonRetainsExactJsonAndAccountsCaptionPayloadInItsBound() {
    var changes =
        TypedDifferences.between(
            TYPE, new Profile(Mode.AUTO, 1), new Profile(Mode.OFF, 1), 8, 8192);
    assertEquals(1, changes.size());
    var change = changes.getFirst();
    assertEquals("\"AUTO\"", change.beforeJson());
    assertEquals("\"OFF\"", change.afterJson());
    assertEquals("Auto", change.beforeChoiceCaption().orElseThrow().fallback());
    assertEquals("Off", change.afterChoiceCaption().orElseThrow().fallback());
    int rawOnlyCost =
        change.path().toString().length()
            + change.beforeJson().length()
            + change.afterJson().length();
    var refused =
        assertThrows(
            PolicyRefusal.class,
            () ->
                TypedDifferences.between(
                    TYPE, new Profile(Mode.AUTO, 1), new Profile(Mode.OFF, 1), 8, rawOnlyCost));
    assertEquals("compare.limit", refused.report().issues().getFirst().code());
    var legacy = new DocumentActions.Change(change.path(), change.beforeJson(), change.afterJson());
    assertTrue(legacy.beforeChoiceCaption().isEmpty());
    assertTrue(legacy.afterChoiceCaption().isEmpty());
  }
}
