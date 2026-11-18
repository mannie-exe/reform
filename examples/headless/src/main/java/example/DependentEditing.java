package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.presentation.FormDependencies;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A display gate preserves invalid data and never replaces complete owner validation. */
final class DependentEditing {
  record Profile(boolean terrain, int height) {}

  static void verify() {
    var enabled = FieldKey.of("terrain", ValueTypes.bool());
    var height = FieldKey.of("height", ValueTypes.integer(0, 100));
    var source = FieldPath.ROOT.field("terrain");
    var target = FieldPath.ROOT.field("height");
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(enabled), v.get(height)))
            .field(enabled, Profile::terrain)
            .field(height, Profile::height)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:dependencies"),
            1,
            type,
            () -> new Profile(true, 20),
            p ->
                p.height() != 90
                    ? ValidationReport.VALID
                    : new ValidationReport(
                        List.of(Issue.error("example.height", target, "Choose another height")),
                        true));
    var layout =
        new FormPresentation(
            List.of(),
            new FormDependencies(
                List.of(
                    new FormDependencies.Rule(
                        "terrain_height",
                        target,
                        source,
                        Set.of("true"),
                        "example.dependencies.terrain_height",
                        "Enable terrain to edit height"))));
    var dependencies = layout.bind(type);
    var json = new JsonLimits(8192, 32, 1024, 512, 128, 4096);
    try (var editor =
        EditSession.fromDraft(
            definition,
            UUID.randomUUID(),
            "one",
            DraftTree.from(type, definition.defaults(), DraftLimits.CONFIGURATION),
            json,
            DraftLimits.CONFIGURATION)) {
      editor.setRaw(target, "90");
      editor.setRaw(source, "false");
      var current = editor.snapshot();
      var observed = dependencies.evaluate(current.draft(), current.inspect().inputs()).at(target);
      if (observed.state() != FormDependencies.State.DISABLED
          || current.inspect().candidate().isPresent())
        throw new AssertionError("Inactive input bypassed complete validation or lost its gate");
      editor.undo();
      current = editor.snapshot();
      if (dependencies.evaluate(current.draft(), current.inspect().inputs()).at(target).state()
          != FormDependencies.State.AVAILABLE)
        throw new AssertionError("Undo failed to restore the controller without erasing input");
      editor.setRaw(target, "30");
      if (!editor
          .snapshot()
          .inspect()
          .candidate()
          .orElseThrow()
          .value()
          .equals(new Profile(true, 30)))
        throw new AssertionError("Dependency repair changed the stored schema or another field");
    }
  }

  private DependentEditing() {}
}
