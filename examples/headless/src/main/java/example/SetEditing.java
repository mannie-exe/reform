package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.Set;
import java.util.UUID;

/** A declared unordered selector; an actual resource resolver is a separate service. */
final class SetEditing {
  static void verify() throws Exception {
    var type = ValueTypes.set(ValueTypes.text(64), 16);
    var definition =
        new DocumentDefinition<>(DocumentId.parse("example:selection"), 1, type, Set::of);
    var json = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
    try (var session =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "one",
            JsonInput.read("[\"land\",\"water\",\"land\"]", json),
            json,
            DraftLimits.CONFIGURATION)) {
      var rows = ((DraftNode.Sequence) session.snapshot().draft().root()).rows();
      if (session.snapshot().inspect().candidate().isPresent())
        throw new AssertionError("Duplicate selection was silently discarded");
      session.setRaw(FieldPath.ROOT.row(rows.get(2).id()), "cavern");
      if (!session
          .snapshot()
          .inspect()
          .candidate()
          .orElseThrow()
          .value()
          .equals(Set.of("land", "water", "cavern")))
        throw new AssertionError("Selection repair lost a row");
      var submitted = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submitted);
      var observed = new java.util.LinkedHashSet<>(java.util.List.of("cavern", "water", "land"));
      if (session.acknowledge(submitted, observed, "two") != EditSession.Acknowledgement.ACCEPTED
          || !((DraftNode.Sequence) session.baseline().root())
              .rows()
              .getFirst()
              .id()
              .equals(rows.get(2).id())
          || session.dirty())
        throw new AssertionError("Selection acknowledgement matched positions rather than members");
    }
  }

  private SetEditing() {}
}
