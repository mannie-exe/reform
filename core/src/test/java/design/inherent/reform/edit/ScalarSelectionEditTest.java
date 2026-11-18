package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScalarSelectionEditTest {
  private static final JsonLimits JSON = new JsonLimits(32768, 64, 4096, 8192, 128, 4096);
  private static final ValueType<Integer> NUMBER = ValueTypes.integer(0, 100);

  private static <T> EditSession<T> editor(ValueType<T> type, T defaults, DraftLimits limits) {
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:selection"), 1, type, () -> defaults);
    return EditSession.fromDraft(
        definition, UUID.randomUUID(), "one", DraftTree.from(type, defaults, limits), JSON, limits);
  }

  private static <T> EditSession<T> editor(ValueType<T> type, T defaults) {
    return editor(type, defaults, DraftLimits.CONFIGURATION);
  }

  private static DraftNode.Sequence rows(EditSession<?> editor) {
    return (DraftNode.Sequence) editor.snapshot().draft().root();
  }

  private static void change(EditSession<?> editor, List<String> append, Set<UUID> remove) {
    editor.execute(
        new DraftEdit(
            editor.snapshot().target(FieldPath.ROOT),
            new DraftEdit.ChangeScalarRows(append, remove)));
  }

  private static void refusedUnchanged(EditSession<?> editor, Runnable operation) {
    var before = editor.snapshot();
    int history = editor.historySize();
    boolean dirty = editor.dirty();
    assertThrows(IllegalArgumentException.class, operation::run);
    assertEquals(before.version(), editor.snapshot().version());
    assertSame(before.draft().root(), editor.snapshot().draft().root());
    assertEquals(history, editor.historySize());
    assertEquals(dirty, editor.dirty());
  }

  @Test
  void oneSelectionChangeRetainsUnrelatedInvalidRowsAndHasOneAtomicUndo() {
    try (var editor = editor(ValueTypes.list(NUMBER, 8), List.of(1, 2, 3))) {
      var initial = rows(editor).rows();
      editor.setRaw(FieldPath.ROOT.row(initial.get(1).id()), "-");
      var before = editor.snapshot();
      var unchanged = rows(editor).rows().get(1);
      int history = editor.historySize();
      change(editor, List.of("4", "5"), Set.of(initial.getFirst().id()));
      var after = editor.snapshot();
      var changed = rows(editor).rows();
      assertEquals(before.version() + 1, after.version());
      assertEquals(history + 1, editor.historySize());
      assertSame(unchanged, changed.getFirst());
      assertSame(initial.get(2), changed.get(1));
      assertEquals(
          List.of("-", "3", "4", "5"),
          changed.stream().map(r -> ((DraftNode.Scalar) r.value()).raw()).toList());
      assertEquals(4, changed.stream().map(DraftNode.Row::id).distinct().count());
      assertTrue(after.inspect().candidate().isEmpty());
      assertTrue(editor.undo());
      assertEquals(before.draft().root(), editor.snapshot().draft().root());
      assertTrue(editor.redo());
      assertEquals(after.draft().root(), editor.snapshot().draft().root());
    }
  }

  @Test
  void wholeBatchCapacityMissingIdentityAndWrongShapeRefuseWithoutPartialCredit() {
    try (var editor = editor(ValueTypes.list(NUMBER, 3), List.of(1, 2))) {
      refusedUnchanged(editor, () -> change(editor, List.of("3", "4"), Set.of()));
      refusedUnchanged(editor, () -> change(editor, List.of("3"), Set.of(UUID.randomUUID())));
      change(editor, List.of("3", "4"), Set.of(rows(editor).rows().getFirst().id()));
      assertEquals(List.of(2, 3, 4), editor.snapshot().inspect().candidate().orElseThrow().value());
    }
    try (var editor = editor(ValueTypes.list(ValueTypes.list(NUMBER, 4), 4), List.of(List.of(1)))) {
      refusedUnchanged(editor, () -> change(editor, List.of("2"), Set.of()));
    }
    try (var editor =
        editor(ValueTypes.objectMap(ValueTypes.text(16), NUMBER, 4), Map.of("home", 1))) {
      refusedUnchanged(editor, () -> change(editor, List.of("2"), Set.of()));
    }
  }

  @Test
  void completeDraftBudgetsIncludeRetainedContentBeforeAnyEditIsPublished() {
    var type = ValueTypes.list(ValueTypes.text(16), 8);
    var text = new DraftLimits(8, 32, 4, 16, 8192);
    try (var editor = editor(type, List.of("a"), text)) {
      refusedUnchanged(editor, () -> change(editor, List.of("bb", "cc"), Set.of()));
      change(editor, List.of("bbb"), Set.of());
      assertEquals(
          List.of("a", "bbb"), editor.snapshot().inspect().candidate().orElseThrow().value());
    }
    var nodes = new DraftLimits(8, 3, 64, 16, 8192);
    try (var editor = editor(type, List.of("a"), nodes)) {
      refusedUnchanged(editor, () -> change(editor, List.of("b"), Set.of()));
    }
  }

  @Test
  void listDuplicatesStayOrderedWhileSetDuplicatesStayRepairableDiagnostics() {
    var text = ValueTypes.text(16);
    try (var editor = editor(ValueTypes.list(text, 4), List.of("a", "b"))) {
      change(editor, List.of("a"), Set.of());
      assertEquals(
          List.of("a", "b", "a"), editor.snapshot().inspect().candidate().orElseThrow().value());
    }
    try (var editor = editor(ValueTypes.set(text, 4), Set.of("a"))) {
      change(editor, List.of("a"), Set.of());
      assertTrue(rows(editor).unordered());
      assertEquals(2, rows(editor).rows().size());
      assertTrue(editor.snapshot().inspect().candidate().isEmpty());
      assertTrue(editor.dirty());
      change(editor, List.of(), Set.of(rows(editor).rows().getLast().id()));
      assertFalse(editor.dirty());
      assertEquals(Set.of("a"), editor.snapshot().inspect().candidate().orElseThrow().value());
    }
  }

  @Test
  void oversizedImportedSelectionsRemainRemovableWithoutAdmittingNewOverflow() throws Exception {
    var type = ValueTypes.list(NUMBER, 2);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:oversized_selection"), 1, type, () -> List.of(1));
    try (var editor =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "one",
            JsonInput.read("[1,2,3,4]", JSON),
            JSON,
            DraftLimits.CONFIGURATION)) {
      var original = rows(editor).rows();
      change(editor, List.of(), Set.of(original.getFirst().id()));
      assertEquals(3, rows(editor).rows().size());
      assertTrue(editor.snapshot().inspect().candidate().isEmpty());
      refusedUnchanged(editor, () -> change(editor, List.of("5"), Set.of(original.get(1).id())));
      change(editor, List.of(), Set.of(original.get(1).id()));
      assertEquals(List.of(3, 4), editor.snapshot().inspect().candidate().orElseThrow().value());
      assertEquals(original.get(2).id(), rows(editor).rows().getFirst().id());
    }
  }

  @Test
  void structuralVersionLifetimeAndOwnedRequestSnapshotsStayStrict() {
    try (var editor = editor(ValueTypes.list(NUMBER, 8), List.of(1));
        var foreign = editor(ValueTypes.list(NUMBER, 8), List.of(1))) {
      var target = editor.snapshot().target(FieldPath.ROOT);
      var strings = new ArrayList<>(List.of("2"));
      var removed = new HashSet<UUID>();
      var action = new DraftEdit.ChangeScalarRows(strings, removed);
      strings.clear();
      removed.add(rows(editor).rows().getFirst().id());
      assertEquals(List.of("2"), action.append());
      assertTrue(action.remove().isEmpty());
      assertThrows(UnsupportedOperationException.class, () -> action.append().clear());
      assertThrows(IllegalArgumentException.class, () -> new DraftEdit(target.typing(), action));
      refusedUnchanged(foreign, () -> foreign.execute(new DraftEdit(target, action)));
      editor.execute(new DraftEdit(target, action));
      refusedUnchanged(editor, () -> editor.execute(new DraftEdit(target, action)));
      var fresh = editor.snapshot().target(FieldPath.ROOT);
      editor.reload(List.of(1), "two");
      refusedUnchanged(editor, () -> editor.execute(new DraftEdit(fresh, action)));
      long version = editor.snapshot().version();
      change(editor, List.of(), Set.of());
      assertEquals(version, editor.snapshot().version());
    }
  }

  @Test
  void boundedTypedRequestsRejectMalformedPartialAndUnqualifiedBulkData() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DraftEdit.ChangeScalarRows(java.util.Collections.nCopies(257, "a"), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DraftEdit.ChangeScalarRows(List.of("a".repeat(32769)), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DraftEdit.ChangeScalarRows(
                java.util.Collections.nCopies(5, "a".repeat(32768)), Set.of()));
    try (var editor = editor(ValueTypes.list(ValueTypes.text(16), 8), List.of("custom"))) {
      var row = rows(editor).rows().getFirst();
      var request =
          new DraftEdit(
              editor.snapshot().target(FieldPath.ROOT),
              new DraftEdit.ChangeScalarRows(List.of("known"), Set.of(row.id())));
      var codec = DraftEdits.type().codec();
      for (var ops : List.of(JsonOps.INSTANCE, JsonOps.COMPRESSED)) {
        var encoded = codec.encodeStart(ops, request).getOrThrow();
        assertEquals(request, codec.parse(ops, encoded).getOrThrow());
      }
      var encoded = codec.encodeStart(JsonOps.INSTANCE, request).getOrThrow();
      var malformed = encoded.deepCopy();
      malformed
          .getAsJsonObject()
          .getAsJsonObject("action")
          .getAsJsonObject("value")
          .getAsJsonArray("append")
          .add(7);
      assertTrue(codec.parse(JsonOps.INSTANCE, malformed).error().isPresent());
      var extra = encoded.deepCopy();
      extra
          .getAsJsonObject()
          .getAsJsonObject("action")
          .getAsJsonObject("value")
          .addProperty("truncate", true);
      assertTrue(codec.parse(JsonOps.INSTANCE, extra).error().isPresent());
      assertEquals(
          List.of("custom"), editor.snapshot().inspect().candidate().orElseThrow().value());
    }
  }
}
