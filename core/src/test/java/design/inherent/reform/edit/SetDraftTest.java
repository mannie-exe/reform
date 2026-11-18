package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SetDraftTest {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);
  private static final ValueType<Set<Integer>> TYPE = ValueTypes.set(ValueTypes.integer(0, 100), 8);
  private static final DocumentDefinition<Set<Integer>> DEFINITION =
      new DocumentDefinition<>(DocumentId.parse("fixture:set"), 1, TYPE, Set::of);

  private static DraftTree draft(String source) throws Exception {
    return DraftTree.fromParsed(TYPE, JsonInput.read(source, JSON), DraftLimits.CONFIGURATION);
  }

  private static DraftNode.Sequence rows(DraftTree tree) {
    return (DraftNode.Sequence) tree.root();
  }

  @Test
  void uniqueOrderedListsRetainMeaningfulOrder() {
    var element = ValueTypes.integer(0, 100);
    var list = ValueTypes.list(element, 8);
    var type =
        ValueType.of(
            list.codec()
                .validate(
                    values ->
                        new java.util.HashSet<>(values).size() == values.size()
                            ? com.mojang.serialization.DataResult.success(values)
                            : com.mojang.serialization.DataResult.error(() -> "Duplicate value")),
            new ValueShape.Sequence(element, 8, true),
            list.snapshotPolicy());
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:unique_ordered"), 1, type, () -> List.of(1, 2));
    var tree = DraftTree.from(type, definition.defaults(), DraftLimits.CONFIGURATION);
    var first = rows(tree).rows().getFirst();
    var moved = tree.change(FieldPath.ROOT, n -> ((DraftNode.Sequence) n).move(first.id(), 1));
    assertFalse(tree.sameValue(moved), "Uniqueness must not erase an ordered list's semantics");
    assertEquals(List.of(2, 1), moved.inspect(definition, JSON).candidate().orElseThrow().value());
  }

  @Test
  void duplicateRawRowsStayEditableWithRelatedDiagnostics() throws Exception {
    var tree = draft("[1,1.00]");
    var rows = rows(tree).rows();
    var inspected = tree.inspect(DEFINITION, JSON);
    assertTrue(inspected.candidate().isEmpty());
    var duplicate =
        inspected.report().issues().stream()
            .filter(i -> i.code().equals("input.duplicate_member"))
            .findFirst()
            .orElseThrow();
    assertEquals(FieldPath.ROOT.row(rows.get(1).id()), duplicate.path());
    assertEquals(List.of(FieldPath.ROOT.row(rows.getFirst().id())), duplicate.related());
    var repaired = tree.change(duplicate.path(), n -> new DraftNode.Scalar("2"));
    assertEquals(
        Set.of(1, 2), repaired.inspect(DEFINITION, JSON).candidate().orElseThrow().value());
    assertEquals("1.00", ((DraftNode.Scalar) tree.at(duplicate.path())).raw());
  }

  @Test
  void reorderingIsNotAPersistedChangeAndDiagnosticsKeepMemberIdentity() throws Exception {
    try (var session =
        EditSession.fromSource(
            DEFINITION,
            UUID.randomUUID(),
            "one",
            JsonInput.read("[1,2]", JSON),
            JSON,
            DraftLimits.CONFIGURATION)) {
      var first = rows(session.snapshot().draft()).rows().getFirst();
      var submitted = session.prepareSubmission().submission().orElseThrow();
      session.edit(FieldPath.ROOT, n -> ((DraftNode.Sequence) n).move(first.id(), 1), "Reorder");
      assertFalse(session.dirty());
      assertEquals(
          FieldPath.ROOT.row(first.id()),
          session.locate(submitted, SourcePath.ROOT.index(0)).path());
      session.setRaw(FieldPath.ROOT.row(first.id()), "-");
      assertTrue(session.dirty());
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      assertEquals(
          EditSession.TargetState.STALE_VALUE,
          session.locate(submitted, SourcePath.ROOT.index(0)).state());
      session.undo();
      assertFalse(session.dirty());
      session.edit(FieldPath.ROOT, n -> ((DraftNode.Sequence) n).remove(first.id()), "Remove");
      assertEquals(
          EditSession.TargetState.REMOVED,
          session.locate(submitted, SourcePath.ROOT.index(0)).state());
    }
  }

  @Test
  void equalAcknowledgementMatchesMembersRatherThanPositionsAndKeepsLateInvalidInput()
      throws Exception {
    try (var session =
        EditSession.fromSource(
            DEFINITION,
            UUID.randomUUID(),
            "one",
            JsonInput.read("[1.00,2]", JSON),
            JSON,
            DraftLimits.CONFIGURATION)) {
      var before = rows(session.snapshot().draft()).rows();
      var submitted = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submitted);
      session.setRaw(FieldPath.ROOT.row(before.getFirst().id()), "-");
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submitted, draft("[2,1]"), "two"));
      assertEquals(
          List.of(before.get(1).id(), before.getFirst().id()),
          rows(session.baseline()).rows().stream().map(DraftNode.Row::id).toList());
      assertEquals(
          "-",
          ((DraftNode.Scalar)
                  session.snapshot().draft().at(FieldPath.ROOT.row(before.getFirst().id())))
              .raw());
      session.undo();
      assertFalse(session.dirty());
      assertEquals(
          "1",
          ((DraftNode.Scalar)
                  session.snapshot().draft().at(FieldPath.ROOT.row(before.getFirst().id())))
              .raw());
      session.redo();
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
    }
  }

  @Test
  void rawOrderingMetadataCannotMakeAnOrdinaryListBehaveAsASet() throws Exception {
    var tree = draft("[1,2]");
    var wrong = new DraftTree(new DraftNode.Sequence(rows(tree).rows()), DraftLimits.CONFIGURATION);
    assertFalse(wrong.sameValue(tree));
    assertTrue(wrong.inspect(DEFINITION, JSON).candidate().isEmpty());
    var listType = ValueTypes.list(ValueTypes.integer(0, 100), 8);
    var listDefinition =
        new DocumentDefinition<>(DocumentId.parse("fixture:list"), 1, listType, List::of);
    assertTrue(tree.inspect(listDefinition, JSON).candidate().isEmpty());
    var list =
        DraftTree.fromParsed(listType, JsonInput.read("[1,1]", JSON), DraftLimits.CONFIGURATION);
    assertEquals(
        List.of(1, 1), list.inspect(listDefinition, JSON).candidate().orElseThrow().value());
    var first = rows(list).rows().getFirst();
    assertFalse(
        list.sameValue(
            list.change(
                FieldPath.ROOT,
                n -> ((DraftNode.Sequence) n).replace(first.id(), new DraftNode.Scalar("2")))));
  }

  @Test
  void nestedSetMapKeysAlignBothEntryAndMemberIdentities() throws Exception {
    var type = ValueTypes.entryMap(TYPE, ValueTypes.text(16), 4);
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:set_map"), 1, type, Map::of);
    try (var session =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "one",
            JsonInput.read("[{\"key\":[1,2],\"value\":\"island\"}]", JSON),
            JSON,
            DraftLimits.CONFIGURATION)) {
      var entry = ((DraftNode.Mapping) session.snapshot().draft().root()).rows().getFirst();
      var path = FieldPath.ROOT.row(entry.id()).append(FieldPath.Part.KEY);
      var before = ((DraftNode.Sequence) session.snapshot().draft().at(path)).rows();
      var submitted = session.prepareSubmission().submission().orElseThrow();
      var observed =
          DraftTree.fromParsed(
              type,
              JsonInput.read("[{\"key\":[2,1.00],\"value\":\"island\"}]", JSON),
              DraftLimits.CONFIGURATION);
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submitted, observed, "two"));
      assertEquals(
          entry.id(), ((DraftNode.Mapping) session.baseline().root()).rows().getFirst().id());
      assertEquals(
          List.of(before.get(1).id(), before.getFirst().id()),
          ((DraftNode.Sequence) session.baseline().at(path))
              .rows().stream().map(DraftNode.Row::id).toList());
      assertEquals(
          Map.of(Set.of(1, 2), "island"),
          session.snapshot().inspect().candidate().orElseThrow().value());
      assertFalse(session.dirty());
    }
  }

  @Test
  void invalidDuplicateCountsAreNotErasedByUnorderedDraftEquality() throws Exception {
    var first = draft("[1,1,2]");
    var reordered = draft("[2,1,1]");
    assertTrue(first.sameValue(reordered));
    assertFalse(first.sameValue(draft("[1,2,2]")));
    var row = rows(first).rows().getFirst();
    var moved = rows(first).move(row.id(), 2);
    assertTrue(moved.unordered());
    assertTrue(moved.replace(row.id(), row.value()).unordered());
    assertTrue(moved.remove(row.id()).unordered());
  }
}
