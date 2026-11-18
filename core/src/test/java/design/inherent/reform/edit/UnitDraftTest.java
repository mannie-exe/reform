package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnitDraftTest {
  private static final JsonLimits JSON = new JsonLimits(32768, 64, 4096, 8192, 128, 4096);
  private static final NumericDisplay TICKS = NumericDisplay.ticks(BigDecimal.valueOf(20));
  private static final ValueType<Integer> DURATION =
      ValueTypes.numericDisplay(ValueTypes.integer(0, 12000), TICKS);

  private static NumericDisplay tiny() {
    return new NumericDisplay(
        "base",
        List.of(
            new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
            new NumericDisplay.Unit("tiny", "tiny", new BigDecimal("1e-4096"))),
        BigDecimal.ONE,
        Optional.empty(),
        DecimalText.Limits.CONFIGURATION);
  }

  private static <T> DocumentDefinition<T> definition(ValueType<T> type, T initial) {
    return new DocumentDefinition<>(DocumentId.parse("fixture:units"), 1, type, () -> initial);
  }

  private static <T> EditSession<T> session(ValueType<T> type, T initial, DraftLimits limits) {
    return EditSession.fromDraft(
        definition(type, initial),
        UUID.randomUUID(),
        "one",
        DraftTree.from(type, initial, limits),
        JSON,
        limits);
  }

  private static <T> EditSession<T> session(ValueType<T> type, T initial) {
    return session(type, initial, DraftLimits.CONFIGURATION);
  }

  private static void edit(EditSession<?> session, FieldPath path, DraftEdit.Action action) {
    session.execute(new DraftEdit(session.snapshot().target(path), action));
  }

  private static DraftNode.Scalar scalar(EditSession<?> session, FieldPath path) {
    return (DraftNode.Scalar) session.snapshot().draft().at(path);
  }

  @Test
  void failedConversionNeverBecomesAValidCanonicalNumberOrAnUnchangedDraft() {
    var type = ValueTypes.numericDisplay(ValueTypes.decimal(0, 1), tiny());
    try (var s = session(type, 0.1)) {
      edit(s, FieldPath.ROOT, new DraftEdit.SetUnitRaw("tiny", "0.1"));
      var input = scalar(s, FieldPath.ROOT);
      assertEquals("0.1", input.raw());
      assertEquals(NumericDisplay.State.LIMIT, input.unitInput().orElseThrow().state());
      assertTrue(
          type.codec()
              .parse(JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(0.1))
              .result()
              .isPresent());
      assertTrue(s.dirty(), "The failed display input must not compare equal to saved 0.1");
      var inspection = s.snapshot().inspect();
      assertTrue(inspection.candidate().isEmpty());
      assertEquals(DraftTree.InputState.INVALID, inspection.inputs().get(FieldPath.ROOT));
      assertEquals("input.unit_conversion", inspection.report().issues().getFirst().code());
      assertEquals(EditSession.PreparationState.INVALID, s.prepareSubmission().state());
      edit(s, FieldPath.ROOT, new DraftEdit.SetRaw("0.1"));
      assertTrue(scalar(s, FieldPath.ROOT).unitInput().isEmpty());
      assertFalse(s.dirty());
      s.undo();
      assertEquals(input, scalar(s, FieldPath.ROOT));
      assertTrue(s.snapshot().inspect().candidate().isEmpty());
    }
  }

  @Test
  void exactMappingStillRequiresTheOriginalIntegerAndRangeCodec() {
    try (var s = session(DURATION, 1)) {
      edit(s, FieldPath.ROOT, new DraftEdit.SetUnitRaw("seconds", "0.15"));
      var valid = s.snapshot().inspect().candidate().orElseThrow();
      assertEquals(3, valid.value());
      assertEquals("3", valid.authoredJson());
      assertFalse(valid.authoredJson().contains("seconds"));
      edit(s, FieldPath.ROOT, new DraftEdit.SetUnitRaw("seconds", "0.125"));
      assertEquals("2.5", scalar(s, FieldPath.ROOT).raw());
      assertEquals(
          NumericDisplay.State.EXACT, scalar(s, FieldPath.ROOT).unitInput().orElseThrow().state());
      assertTrue(s.snapshot().inspect().candidate().isEmpty());
      edit(s, FieldPath.ROOT, new DraftEdit.SetUnitRaw("seconds", "601"));
      assertTrue(s.snapshot().inspect().candidate().isEmpty());
      s.undo();
      assertEquals("0.125", scalar(s, FieldPath.ROOT).unitInput().orElseThrow().text());
      s.undo();
      assertEquals(3, s.snapshot().inspect().candidate().orElseThrow().value());
    }
  }

  @Test
  void exactPrivateRepresentationRetainsHistoryWithoutInventingAnAuthoredChange() {
    try (var s = session(DURATION, 3)) {
      var initial = s.snapshot().draft().root();
      edit(s, FieldPath.ROOT, new DraftEdit.SetUnitRaw("seconds", "0.150"));
      var mapped = scalar(s, FieldPath.ROOT);
      assertNotEquals(initial, mapped);
      assertTrue(DraftNode.sameValue(initial, mapped));
      assertFalse(s.dirty());
      assertEquals(1, s.historySize());
      assertTrue(s.undo());
      assertEquals(initial, s.snapshot().draft().root());
      assertTrue(s.redo());
      assertEquals(mapped, s.snapshot().draft().root());
    }
  }

  @Test
  void incompleteInvalidAndOverscaleInputsRemainExplicitAcrossInactiveMembershipAndUndo() {
    var type = ValueTypes.optional(DURATION, ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var content = FieldPath.ROOT.append(FieldPath.Part.VALUE);
    try (var s = session(type, Optional.of(1))) {
      for (String raw : List.of("", "-", "1e+", "1/3", "0.1e-4096")) {
        edit(s, content, new DraftEdit.SetUnitRaw("seconds", raw));
        var invalid = scalar(s, content);
        assertEquals(raw, invalid.unitInput().orElseThrow().text());
        assertTrue(s.snapshot().inspect().candidate().isEmpty());
        edit(s, FieldPath.ROOT, new DraftEdit.Presence(false));
        assertEquals(Optional.empty(), s.snapshot().inspect().candidate().orElseThrow().value());
        s.undo();
        assertEquals(invalid, scalar(s, content));
        assertTrue(s.snapshot().inspect().candidate().isEmpty());
      }
    }
  }

  @Test
  void forgedRemovedOrChangedMappingsStayUnavailableRatherThanBeingReinterpreted() {
    var wrong =
        new DraftNode.Scalar(
            "0.1", new DraftNode.UnitInput("seconds", "1", NumericDisplay.State.EXACT));
    var missing =
        new DraftNode.Scalar(
            "1", new DraftNode.UnitInput("removed", "1", NumericDisplay.State.EXACT));
    for (var input : List.of(wrong, missing)) {
      var tree = new DraftTree(input, DraftLimits.CONFIGURATION);
      var inspection = tree.inspect(definition(DURATION, 1), JSON);
      assertTrue(inspection.candidate().isEmpty());
      assertEquals(DraftTree.InputState.UNAVAILABLE, inspection.inputs().get(FieldPath.ROOT));
    }
    var undecorated =
        new DraftTree(wrong, DraftLimits.CONFIGURATION)
            .inspect(definition(ValueTypes.decimal(0, 1), 0.1), JSON);
    assertTrue(undecorated.candidate().isEmpty());
    assertEquals("input.unit_unavailable", undecorated.report().issues().getFirst().code());
    var forgedSuccess =
        new DraftNode.Scalar(
            "0.1", new DraftNode.UnitInput("tiny", "0.1", NumericDisplay.State.EXACT));
    assertTrue(
        new DraftTree(forgedSuccess, DraftLimits.CONFIGURATION)
            .inspect(
                definition(ValueTypes.numericDisplay(ValueTypes.decimal(0, 1), tiny()), 0.1), JSON)
            .candidate()
            .isEmpty());
  }

  @Test
  void unknownAndUndeclaredUnitsRefuseWithoutChangingHistoryAndRetainedInputIsBudgeted() {
    try (var s = session(DURATION, 1, new DraftLimits(4, 8, 8, 8, 1024))) {
      var before = s.snapshot();
      assertThrows(
          DraftEditRefusal.class,
          () -> edit(s, FieldPath.ROOT, new DraftEdit.SetUnitRaw("seconds", "-")));
      assertEquals(before.version(), s.snapshot().version());
      assertEquals(before.draft().root(), s.snapshot().draft().root());
      assertFalse(s.canUndo());
    }
    try (var s = session(DURATION, 1);
        var plain = session(ValueTypes.integer(0, 10), 1)) {
      for (var item : List.of(s, plain)) {
        assertThrows(
            DraftEditRefusal.class,
            () -> edit(item, FieldPath.ROOT, new DraftEdit.SetUnitRaw("unknown", "2")));
        assertFalse(item.canUndo());
        assertFalse(item.dirty());
      }
      var target = s.snapshot().target(FieldPath.ROOT).typing();
      s.reload(1, "replacement");
      assertThrows(
          DraftEditRefusal.class,
          () -> s.execute(new DraftEdit(target, new DraftEdit.SetUnitRaw("seconds", "0.15"))));
    }
  }

  @Test
  void laterFailedMappingSurvivesAcknowledgementEvenWhenItsRetainedTextMatchesSavedRaw() {
    var type = ValueTypes.numericDisplay(ValueTypes.decimal(0, 1), tiny());
    try (var s = session(type, 0.1)) {
      s.setRaw(FieldPath.ROOT, "0.2");
      var submission = s.prepareSubmission().submission().orElseThrow();
      edit(s, FieldPath.ROOT, new DraftEdit.SetUnitRaw("tiny", "0.2"));
      var later = s.snapshot().draft().root();
      assertEquals(EditSession.Acknowledgement.ACCEPTED, s.acknowledge(submission, 0.2, "two"));
      assertEquals(later, s.snapshot().draft().root());
      assertTrue(s.dirty());
      assertTrue(s.snapshot().inspect().candidate().isEmpty());
      assertTrue(s.undo());
      assertEquals(0.2, s.snapshot().inspect().candidate().orElseThrow().value());
      assertFalse(s.dirty());
      assertTrue(s.redo());
      assertEquals(later, s.snapshot().draft().root());
    }
  }
}
