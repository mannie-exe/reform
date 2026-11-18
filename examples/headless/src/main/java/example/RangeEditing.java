package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Layout metadata never replaces the owner's complete range validation or two stored fields. */
final class RangeEditing {
  record Bounds(int minimum, int maximum) {}

  static void verify() {
    var minimum = FieldKey.of("minimum", ValueTypes.integer(0, 100));
    var maximum = FieldKey.of("maximum", ValueTypes.integer(0, 100));
    var minPath = FieldPath.ROOT.field("minimum");
    var maxPath = FieldPath.ROOT.field("maximum");
    var type =
        RecordType.<Bounds>builder(v -> new Bounds(v.get(minimum), v.get(maximum)))
            .field(minimum, Bounds::minimum)
            .field(maximum, Bounds::maximum)
            .build();
    var layout =
        new FormPresentation(
            List.of(
                new FormPresentation.Range(
                    "limits",
                    "example.range.limits",
                    "Bounds",
                    "Minimum must not exceed maximum",
                    minPath,
                    maxPath)));
    layout.requireCompatible(type);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:ranges"),
            1,
            type,
            () -> new Bounds(10, 40),
            value ->
                value.minimum() <= value.maximum()
                    ? ValidationReport.VALID
                    : new ValidationReport(
                        List.of(
                            new Issue(
                                "example.range_order",
                                Issue.Severity.ERROR,
                                minPath,
                                List.of(maxPath),
                                Map.of(),
                                "Minimum must not exceed maximum")),
                        true));
    var json = new JsonLimits(8192, 32, 1024, 512, 128, 4096);
    try (var session =
        EditSession.fromDraft(
            definition,
            UUID.randomUUID(),
            "one",
            DraftTree.from(type, definition.defaults(), DraftLimits.CONFIGURATION),
            json,
            DraftLimits.CONFIGURATION)) {
      session.setRaw(minPath, "50");
      var invalid = session.snapshot().inspect();
      if (invalid.candidate().isPresent()
          || invalid.report().issues().stream()
              .noneMatch(
                  issue -> issue.path().equals(minPath) && issue.related().contains(maxPath)))
        throw new AssertionError("The owner's range error lost either field identity");
      session.setRaw(maxPath, "60");
      if (!session
          .snapshot()
          .inspect()
          .candidate()
          .orElseThrow()
          .value()
          .equals(new Bounds(50, 60)))
        throw new AssertionError("Repairing maximum changed minimum or its original codec");
      session.undo();
      if (session.snapshot().inspect().candidate().isPresent())
        throw new AssertionError("Range undo lost the repairable invalid draft");
    }
  }

  private RangeEditing() {}
}
