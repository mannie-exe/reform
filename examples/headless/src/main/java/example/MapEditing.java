package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A bounded role-count profile through the packaged API; no village/world dependency. */
final class MapEditing {
  static void verify() throws Exception {
    var json = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
    var type = ValueTypes.objectMap(ValueTypes.text(32), ValueTypes.integer(0, 64), 16);
    var definition = new DocumentDefinition<>(DocumentId.parse("example:roles"), 1, type, Map::of);
    try (var session =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "roles-1",
            JsonInput.read("{\"guards\":2,\"golems\":1}", json),
            json,
            DraftLimits.CONFIGURATION)) {
      var rows = ((DraftNode.Mapping) session.snapshot().draft().root()).rows();
      var guards = FieldPath.ROOT.row(rows.getFirst().id());
      var golems = FieldPath.ROOT.row(rows.get(1).id());
      session.setRaw(guards.append(FieldPath.Part.VALUE), "4");
      var submitted = session.prepareSubmission().submission().orElseThrow();
      if (!submitted
          .candidate()
          .addresses()
          .get(SourcePath.ROOT.key("guards"))
          .equals(guards.append(FieldPath.Part.KEY)))
        throw new AssertionError("Map name and value addresses were conflated");
      session.markInFlight(submitted);
      session.setRaw(golems.append(FieldPath.Part.KEY), "escorts");
      var observed =
          DraftTree.fromParsed(
              type, JsonInput.read("{\"golems\":1,\"guards\":4}", json), DraftLimits.CONFIGURATION);
      if (session.acknowledge(submitted, observed, "roles-2")
              != EditSession.Acknowledgement.ACCEPTED
          || !session
              .snapshot()
              .inspect()
              .candidate()
              .orElseThrow()
              .value()
              .equals(Map.of("guards", 4, "escorts", 1)))
        throw new AssertionError("Reordered acknowledgement redirected a later key edit");
      session.revert();
      if (session.dirty()
          || !((DraftNode.Mapping) session.snapshot().draft().root())
              .rows()
              .getFirst()
              .id()
              .equals(rows.get(1).id()))
        throw new AssertionError("Map row identity was aligned by position");
    }
    var coordinates =
        ValueTypes.entryMap(
            ValueTypes.list(ValueTypes.integer(-64, 64), 2), ValueTypes.text(32), 8);
    var parsed =
        JsonInput.read("[{\"key\":[2,-3],\"value\":\"landing\"}]", json)
            .decode(coordinates)
            .value()
            .orElseThrow();
    if (!parsed.equals(Map.of(List.of(2, -3), "landing")))
      throw new AssertionError("Explicit entry-list map lost its key");
  }

  private MapEditing() {}
}
