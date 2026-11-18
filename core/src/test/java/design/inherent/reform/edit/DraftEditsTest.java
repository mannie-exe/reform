package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DraftEditsTest {
  private static final JsonLimits JSON = new JsonLimits(32768, 64, 4096, 8192, 128, 4096);
  private static final ValueType<Integer> NUMBER = ValueTypes.integer(0, 100);
  private static final UUID CONTEXT = UUID.randomUUID();

  private interface Mode {}

  private record Counter(int count) implements Mode {}

  private record Named(String name) implements Mode {}

  private record Limit(int count) {}

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", NUMBER);
  private static final ValueType<Counter> COUNTER =
      RecordType.<Counter>builder(v -> new Counter(v.get(COUNT)))
          .field(COUNT, Counter::count)
          .build();
  private static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(32));
  private static final ValueType<Named> NAMED =
      RecordType.<Named>builder(v -> new Named(v.get(NAME))).field(NAME, Named::name).build();
  private static final ValueType<Mode> MODE =
      TaggedType.<Mode>builder("kind", "value")
          .variant("counter", Counter.class, COUNTER)
          .variant("named", Named.class, NAMED)
          .build();
  private static final FieldKey<Integer> DEFAULT_COUNT =
      FieldKey.of("count", ValueTypes.defaultedField(NUMBER, 7));
  private static final ValueType<Limit> LIMIT =
      RecordType.<Limit>builder(v -> new Limit(v.get(DEFAULT_COUNT)))
          .field(DEFAULT_COUNT, Limit::count)
          .build();

  private static <T> EditSession<T> session(ValueType<T> type, T value) {
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:structured"), 1, type, () -> value);
    return EditSession.fromDraft(
        definition,
        CONTEXT,
        "saved",
        DraftTree.from(type, value, DraftLimits.CONFIGURATION),
        JSON,
        DraftLimits.CONFIGURATION);
  }

  private static <T> EditSession<T> source(ValueType<T> type, T value, String raw)
      throws Exception {
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:structured"), 1, type, () -> value);
    return EditSession.fromSource(
        definition, CONTEXT, "saved", JsonInput.read(raw, JSON), JSON, DraftLimits.CONFIGURATION);
  }

  private static void edit(EditSession<?> session, FieldPath path, DraftEdit.Action action) {
    session.execute(new DraftEdit(session.snapshot().target(path), action));
  }

  private static DraftNode.Sequence rows(EditSession<?> session) {
    return (DraftNode.Sequence) session.snapshot().draft().root();
  }

  @Test
  void nestedDuplicatesHaveFreshIdentitiesAndAnchoredMovesRetainOriginalRows() {
    var type = ValueTypes.list(ValueTypes.list(NUMBER, 4), 3);
    try (var s = session(type, List.of(List.of(1, 2)))) {
      var original = rows(s).rows().getFirst();
      edit(s, FieldPath.ROOT, new DraftEdit.DuplicateRow(original.id(), Optional.empty()));
      var duplicate = rows(s).rows().get(1);
      assertNotEquals(original.id(), duplicate.id());
      var a = (DraftNode.Sequence) original.value();
      var b = (DraftNode.Sequence) duplicate.value();
      assertNotEquals(a.rows().getFirst().id(), b.rows().getFirst().id());
      assertEquals(
          ((DraftNode.Scalar) a.rows().getFirst().value()).raw(),
          ((DraftNode.Scalar) b.rows().getFirst().value()).raw());
      edit(s, FieldPath.ROOT, new DraftEdit.MoveRow(duplicate.id(), Optional.of(original.id())));
      assertEquals(
          List.of(duplicate.id(), original.id()),
          rows(s).rows().stream().map(DraftNode.Row::id).toList());
      var nested = FieldPath.ROOT.row(original.id()).row(a.rows().getFirst().id());
      edit(s, nested, new DraftEdit.SetRaw("-"));
      assertFalse(s.snapshot().inspect().report().valid());
      edit(s, FieldPath.ROOT, new DraftEdit.RemoveRow(duplicate.id()));
      assertEquals("-", ((DraftNode.Scalar) s.snapshot().draft().at(nested)).raw());
      s.undo();
      assertEquals(duplicate.id(), rows(s).rows().getFirst().id());
      assertEquals("-", ((DraftNode.Scalar) s.snapshot().draft().at(nested)).raw());
    }
  }

  @Test
  void actionsRefuseForeignReloadedAndStaleVersionsWhileExplicitTypingStreamsStayOrdered() {
    try (var a = session(NUMBER, 1);
        var b = session(NUMBER, 1)) {
      var old = a.snapshot().target(FieldPath.ROOT);
      assertNotEquals(a.snapshot().session(), b.snapshot().session());
      assertThrows(
          IllegalArgumentException.class,
          () -> b.execute(new DraftEdit(old, new DraftEdit.SetRaw("2"))));
      a.execute(new DraftEdit(old, new DraftEdit.SetRaw("2")));
      assertThrows(
          IllegalArgumentException.class,
          () -> a.execute(new DraftEdit(old, new DraftEdit.SetRaw("3"))));
      a.execute(new DraftEdit(old.typing(), new DraftEdit.SetRaw("4")));
      assertEquals(4, a.snapshot().inspect().candidate().orElseThrow().value());
      a.reload(1, "new");
      assertThrows(
          IllegalArgumentException.class,
          () -> a.execute(new DraftEdit(old.typing(), new DraftEdit.SetRaw("5"))));
      assertEquals(1, a.snapshot().inspect().candidate().orElseThrow().value());
      assertThrows(
          IllegalArgumentException.class,
          () -> new DraftEdit(old.typing(), new DraftEdit.Repair()));
    }
  }

  @Test
  void blankRowsRemainRepairableAndCollectionBoundsNeverDiscardExistingRows() {
    var type = ValueTypes.list(NUMBER, 2);
    try (var s = session(type, List.of(1))) {
      edit(s, FieldPath.ROOT, new DraftEdit.InsertRow(Optional.empty()));
      var added = rows(s).rows().getLast();
      assertEquals("", ((DraftNode.Scalar) added.value()).raw());
      assertTrue(s.snapshot().inspect().candidate().isEmpty());
      var before = s.snapshot();
      assertThrows(
          IllegalArgumentException.class,
          () -> edit(s, FieldPath.ROOT, new DraftEdit.InsertRow(Optional.empty())));
      assertEquals(before.version(), s.snapshot().version());
      assertEquals(before.draft().root(), s.snapshot().draft().root());
      edit(s, FieldPath.ROOT.row(added.id()), new DraftEdit.SetRaw("2"));
      assertEquals(List.of(1, 2), s.snapshot().inspect().candidate().orElseThrow().value());
      assertThrows(
          IllegalArgumentException.class,
          () -> edit(s, FieldPath.ROOT, new DraftEdit.RemoveRow(UUID.randomUUID())));
    }
  }

  @Test
  void malformedCollectionsRequireExplicitUndoableRepairAndOversizedOnesRemainRemovable()
      throws Exception {
    var type = ValueTypes.list(NUMBER, 2);
    try (var s = source(type, List.of(), "42")) {
      var before = s.snapshot().draft().root();
      assertThrows(
          IllegalArgumentException.class,
          () -> edit(s, FieldPath.ROOT, new DraftEdit.InsertRow(Optional.empty())));
      assertEquals(before, s.snapshot().draft().root());
      edit(s, FieldPath.ROOT, new DraftEdit.Repair());
      assertEquals(List.of(), s.snapshot().inspect().candidate().orElseThrow().value());
      s.undo();
      assertEquals(before, s.snapshot().draft().root());
    }
    try (var s = source(type, List.of(), "[1,2,3]")) {
      assertFalse(s.snapshot().inspect().report().valid());
      edit(s, FieldPath.ROOT, new DraftEdit.RemoveRow(rows(s).rows().getLast().id()));
      assertEquals(List.of(1, 2), s.snapshot().inspect().candidate().orElseThrow().value());
    }
  }

  @Test
  void mapKeyChangesKeepRowIdentityAndDuplicateErrorsUntilExplicitRepair() {
    var type = ValueTypes.objectMap(ValueTypes.text(32), NUMBER, 3);
    try (var s = session(type, Map.of("one", 1))) {
      edit(s, FieldPath.ROOT, new DraftEdit.InsertRow(Optional.empty()));
      var rows = ((DraftNode.Mapping) s.snapshot().draft().root()).rows();
      var added = FieldPath.ROOT.row(rows.getLast().id());
      edit(s, added.append(FieldPath.Part.KEY), new DraftEdit.SetRaw("one"));
      edit(s, added.append(FieldPath.Part.VALUE), new DraftEdit.SetRaw("2"));
      assertFalse(s.snapshot().inspect().report().valid());
      assertEquals(2, ((DraftNode.Mapping) s.snapshot().draft().root()).rows().size());
      edit(s, FieldPath.ROOT, new DraftEdit.RemoveRow(rows.getFirst().id()));
      assertEquals(Map.of("one", 2), s.snapshot().inspect().candidate().orElseThrow().value());
      assertEquals(
          rows.getLast().id(),
          ((DraftNode.Mapping) s.snapshot().draft().root()).rows().getFirst().id());
    }
  }

  @Test
  void malformedMapRowsAndUnknownEntryFieldsCanBeRepairedWithoutReplacingOtherRows()
      throws Exception {
    var type = ValueTypes.entryMap(ValueTypes.text(32), NUMBER, 4);
    try (var s = source(type, Map.of(), "[{\"key\":\"one\",\"value\":1,\"extra\":false},42]")) {
      var rows = ((DraftNode.Mapping) s.snapshot().draft().root()).rows();
      var first = FieldPath.ROOT.row(rows.getFirst().id());
      var second = FieldPath.ROOT.row(rows.getLast().id());
      edit(s, first, new DraftEdit.RemoveUnknown("extra"));
      assertFalse(s.snapshot().inspect().report().valid());
      edit(s, second, new DraftEdit.Repair());
      assertFalse(s.snapshot().inspect().report().valid());
      edit(s, second.append(FieldPath.Part.KEY), new DraftEdit.SetRaw("two"));
      edit(s, second.append(FieldPath.Part.VALUE), new DraftEdit.SetRaw("2"));
      assertEquals(
          Map.of("one", 1, "two", 2), s.snapshot().inspect().candidate().orElseThrow().value());
      var current = ((DraftNode.Mapping) s.snapshot().draft().root()).rows();
      assertEquals(
          rows.stream().map(DraftNode.Row::id).toList(),
          current.stream().map(DraftNode.Row::id).toList());
      assertEquals(
          "1",
          ((DraftNode.Scalar) s.snapshot().draft().at(first.append(FieldPath.Part.VALUE))).raw());
      s.undo();
      s.undo();
      s.undo();
      assertInstanceOf(DraftNode.Opaque.class, s.snapshot().draft().at(second));
    }
  }

  @Test
  void optionalAndDefaultOverridesRetainInactiveInvalidInputAndRefuseHiddenEdits()
      throws Exception {
    var type = ValueTypes.optional(NUMBER, ValueShape.OptionalEncoding.SINGLETON_LIST);
    var value = FieldPath.ROOT.append(FieldPath.Part.VALUE);
    try (var s = session(type, Optional.empty())) {
      edit(s, FieldPath.ROOT, new DraftEdit.Presence(true));
      assertFalse(s.snapshot().inspect().report().valid());
      var typing = s.snapshot().target(value).typing();
      edit(s, value, new DraftEdit.SetRaw("-"));
      edit(s, FieldPath.ROOT, new DraftEdit.Presence(false));
      assertEquals(Optional.empty(), s.snapshot().inspect().candidate().orElseThrow().value());
      assertThrows(
          IllegalArgumentException.class,
          () -> s.execute(new DraftEdit(typing, new DraftEdit.SetRaw("3"))));
      edit(s, FieldPath.ROOT, new DraftEdit.Presence(true));
      assertEquals("-", ((DraftNode.Scalar) s.snapshot().draft().at(value)).raw());
    }
    var field = FieldPath.ROOT.field("count");
    try (var s = source(LIMIT, new Limit(7), "{}")) {
      edit(s, field, new DraftEdit.Override(true));
      assertEquals(new Limit(7), s.snapshot().inspect().candidate().orElseThrow().value());
      edit(s, field.append(FieldPath.Part.VALUE), new DraftEdit.SetRaw("-"));
      edit(s, field, new DraftEdit.Override(false));
      assertEquals(new Limit(7), s.snapshot().inspect().candidate().orElseThrow().value());
      edit(s, field, new DraftEdit.Override(true));
      assertEquals(
          "-",
          ((DraftNode.Scalar) s.snapshot().draft().at(field.append(FieldPath.Part.VALUE))).raw());
    }
  }

  @Test
  void selectingAndDiscardingBranchesIsExplicitAndRetainsInvalidInactiveFields() {
    try (var s = session(MODE, new Counter(2))) {
      var count = FieldPath.ROOT.variant("counter").field("count");
      edit(s, count, new DraftEdit.SetRaw("-"));
      edit(s, FieldPath.ROOT, new DraftEdit.SelectVariant("named"));
      var name = FieldPath.ROOT.variant("named").field("name");
      edit(s, name, new DraftEdit.SetRaw("review"));
      assertEquals(new Named("review"), s.snapshot().inspect().candidate().orElseThrow().value());
      assertThrows(
          IllegalArgumentException.class,
          () -> edit(s, FieldPath.ROOT, new DraftEdit.SelectVariant("missing")));
      assertThrows(
          IllegalArgumentException.class,
          () -> edit(s, FieldPath.ROOT, new DraftEdit.DiscardBranch("named")));
      edit(s, FieldPath.ROOT, new DraftEdit.SelectVariant("counter"));
      assertEquals("-", ((DraftNode.Scalar) s.snapshot().draft().at(count)).raw());
      assertThrows(IllegalArgumentException.class, () -> s.snapshot().target(name));
      edit(s, FieldPath.ROOT, new DraftEdit.DiscardBranch("named"));
      s.undo();
      edit(s, FieldPath.ROOT, new DraftEdit.SelectVariant("named"));
      assertEquals("review", ((DraftNode.Scalar) s.snapshot().draft().at(name)).raw());
    }
  }

  @Test
  void unknownFieldsAreVisibleUntilExplicitRemovalAndKnownFieldsCannotBeRemoved() throws Exception {
    try (var s = source(COUNTER, new Counter(1), "{\"count\":2,\"extra\":[true,4]}")) {
      assertFalse(s.snapshot().inspect().report().valid());
      assertThrows(
          IllegalArgumentException.class, () -> s.snapshot().target(FieldPath.ROOT.field("extra")));
      assertThrows(
          IllegalArgumentException.class,
          () -> edit(s, FieldPath.ROOT, new DraftEdit.RemoveUnknown("count")));
      edit(s, FieldPath.ROOT, new DraftEdit.RemoveUnknown("extra"));
      assertEquals(new Counter(2), s.snapshot().inspect().candidate().orElseThrow().value());
      s.undo();
      assertFalse(s.snapshot().inspect().report().valid());
      assertTrue(
          ((DraftNode.ObjectValue) s.snapshot().draft().root()).fields().containsKey("extra"));
    }
  }

  @Test
  void unorderedMovesDoNotDirtyMembershipAndDuplicatingStillExposesDuplicateErrors() {
    try (var s = session(ValueTypes.set(NUMBER, 3), Set.of(1, 2))) {
      var first = rows(s).rows().getFirst();
      edit(s, FieldPath.ROOT, new DraftEdit.MoveRow(first.id(), Optional.empty()));
      assertFalse(s.dirty());
      edit(s, FieldPath.ROOT, new DraftEdit.DuplicateRow(first.id(), Optional.empty()));
      assertTrue(s.snapshot().inspect().candidate().isEmpty());
      edit(s, FieldPath.ROOT, new DraftEdit.RemoveRow(rows(s).rows().getLast().id()));
      assertFalse(s.dirty());
    }
  }

  @Test
  void typedActionAdmissionRoundtripsPathsAndRefusesMalformedOrOversizedInput() throws Exception {
    var target =
        new DraftTarget(
            UUID.randomUUID(),
            3,
            Optional.of(9L),
            FieldPath.ROOT
                .field("dot.\\$ x")
                .row(UUID.randomUUID())
                .append(FieldPath.Part.KEY)
                .variant("chosen"));
    var id = UUID.randomUUID();
    for (var action :
        List.of(
            new DraftEdit.SetRaw("-"),
            new DraftEdit.InsertRow(Optional.of(id)),
            new DraftEdit.DuplicateRow(id, Optional.empty()),
            new DraftEdit.RemoveRow(id),
            new DraftEdit.MoveRow(id, Optional.empty()),
            new DraftEdit.Presence(true),
            new DraftEdit.Override(false),
            new DraftEdit.SelectVariant("named"),
            new DraftEdit.DiscardBranch("named"),
            new DraftEdit.RestoreOverride(id),
            new DraftEdit.DiscardOverride(id),
            new DraftEdit.RemoveUnknown("old"),
            new DraftEdit.Repair())) {
      var request = new DraftEdit(target, action);
      for (var ops : List.of(JsonOps.INSTANCE, JsonOps.COMPRESSED)) {
        var encoded = DraftEdits.type().codec().encodeStart(ops, request).getOrThrow();
        assertEquals(request, DraftEdits.type().codec().parse(ops, encoded).getOrThrow());
      }
    }
    var raw =
        DraftEdits.type()
            .codec()
            .encodeStart(JsonOps.INSTANCE, new DraftEdit(target, new DraftEdit.SetRaw("ok")))
            .getOrThrow();
    var bad = raw.deepCopy();
    bad.getAsJsonObject().getAsJsonObject("target").addProperty("session", "1-2-3-4-5");
    assertTrue(DraftEdits.type().codec().parse(JsonOps.INSTANCE, bad).error().isPresent());
    var large = new DraftEdit(target, new DraftEdit.SetRaw("x".repeat(32769)));
    assertTrue(DraftEdits.type().codec().encodeStart(JsonOps.INSTANCE, large).error().isPresent());
  }

  @Test
  void blankConstructionUsesActualDraftBoundsWithoutChargingReferenceMetadataAsAnotherNode() {
    var tiny = new DraftLimits(1, 1, 32, 0, 0);
    var reference = ValueTypes.reference(ValueTypes.text(32), DocumentId.parse("fixture:source"));
    assertEquals("", ((DraftNode.Scalar) DraftEdits.blank(reference, tiny)).raw());
    assertThrows(IllegalArgumentException.class, () -> DraftEdits.blank(COUNTER, tiny));
  }
}
