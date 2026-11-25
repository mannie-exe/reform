package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.*;
import org.junit.jupiter.api.Test;

class BooleanObservationTest {
  private record Profile(boolean enabled, String text) {}

  private static final FieldPath ENABLED_PATH = FieldPath.ROOT.field("enabled");
  private static final FieldKey<Boolean> ENABLED = FieldKey.of("enabled", ValueTypes.bool());
  private static final FieldKey<String> TEXT = FieldKey.of("text", ValueTypes.text(32));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(v -> new Profile(v.get(ENABLED), v.get(TEXT)))
          .field(ENABLED, Profile::enabled)
          .field(TEXT, Profile::text)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:boolean_observations"),
          1,
          TYPE,
          () -> new Profile(true, "true"));

  @Test
  void reviewAndInformationLabelOnlyTypedBooleansAndPreserveRawEvidence() {
    var baseline = DraftTree.from(TYPE, DEFINITION.defaults(), DraftLimits.CONFIGURATION);
    var draft = baseline.change(ENABLED_PATH, ignored -> new DraftNode.Scalar("false"));
    var input = input(draft, baseline);
    var review =
        DocumentReview.capture(
            input,
            new DocumentActions.Comparison(
                DraftRevision.of(input.snapshot()), true, List.of(), ValidationReport.VALID),
            new FieldIndex(TYPE, 32, 8),
            DocumentReview.Limits.STANDARD);
    var field = review.entries().getFirst();
    assertEquals("\"true\"", field.baseline());
    assertEquals("\"false\"", field.draft());
    assertEquals("On", field.choiceValues().baseline().orElseThrow().fallback());
    assertEquals("Off", field.choiceValues().draft().orElseThrow().fallback());
    assertTrue(field.choiceValues().effective().isEmpty());
    var info = FieldInformation.capture(input, ENABLED_PATH, DocumentReview.Limits.STANDARD);
    assertEquals("true", info.declaredDefault().choiceCaption().orElseThrow().value());
    assertEquals("false", info.draft().choiceCaption().orElseThrow().value());
    assertEquals(Optional.of("\"false\""), info.draft().text());
    assertEquals(FieldInformation.State.UNAVAILABLE, info.effective().state());
    assertTrue(info.effective().choiceCaption().isEmpty());
    var text =
        FieldInformation.capture(
            input, FieldPath.ROOT.field("text"), DocumentReview.Limits.STANDARD);
    assertEquals(Optional.of("\"true\""), text.draft().text());
    assertTrue(text.draft().choiceCaption().isEmpty());
    var invalid =
        FieldInformation.capture(
            input(
                baseline.change(ENABLED_PATH, ignored -> new DraftNode.Scalar("not a boolean")),
                baseline),
            ENABLED_PATH,
            DocumentReview.Limits.STANDARD);
    assertEquals(Optional.of("\"not a boolean\""), invalid.draft().text());
    assertTrue(invalid.draft().choiceCaption().isEmpty());
  }

  @Test
  void importAndConflictComparisonsKeepBooleanJsonAndAccountForLabelFacts() {
    var before = new Profile(true, "true");
    var after = new Profile(false, "false");
    var changes = TypedDifferences.between(TYPE, before, after, 8, 8192);
    var bool = changes.getFirst();
    assertEquals("true", bool.beforeJson());
    assertEquals("false", bool.afterJson());
    assertEquals("On", bool.beforeChoiceCaption().orElseThrow().fallback());
    assertEquals("Off", bool.afterChoiceCaption().orElseThrow().fallback());
    assertTrue(changes.get(1).beforeChoiceCaption().isEmpty());
    assertTrue(changes.get(1).afterChoiceCaption().isEmpty());
    int rawOnlyCost =
        changes.stream()
            .mapToInt(
                c ->
                    c.path().toString().length() + c.beforeJson().length() + c.afterJson().length())
            .sum();
    var refused =
        assertThrows(
            PolicyRefusal.class,
            () -> TypedDifferences.between(TYPE, before, after, 8, rawOnlyCost));
    assertEquals("compare.limit", refused.report().issues().getFirst().code());
  }

  private static EditorActor.Inspection<Profile> input(DraftTree draft, DraftTree baseline) {
    return new EditorActor.Inspection<>(
        new EditSession.Snapshot<>(
            DEFINITION,
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            1,
            "source-1",
            draft,
            new JsonLimits(8192, 64, 1024, 4096, 128, 4096)),
        baseline,
        new SourceInfo("fixture", "Captured source", Optional.empty()),
        Optional.empty(),
        ValidationReport.VALID,
        Optional.empty(),
        ApplicationEffects.UNDECLARED);
  }
}
