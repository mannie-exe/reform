package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChoiceDisplayTest {
  private enum Mode {
    AUTO,
    ON,
    OFF
  }

  private static final ChoiceDisplay DISPLAY =
      new ChoiceDisplay(
          List.of(
              new ChoiceDisplay.Caption("AUTO", "example.mode.auto", "Auto"),
              new ChoiceDisplay.Caption("ON", "example.mode.on", "On"),
              new ChoiceDisplay.Caption("OFF", "example.mode.off", "Off")));

  @Test
  void decorationRetainsOwnerDecodeRefusalsBeyondTheUnderlyingCodec() {
    var base = ValueTypes.enumeration(Mode.class);
    var owner =
        new design.inherent.reform.value.ValueType<Mode>() {
          @Override
          public com.mojang.serialization.Codec<Mode> codec() {
            return base.codec();
          }

          @Override
          public ValueShape shape() {
            return base.shape();
          }

          @Override
          public design.inherent.reform.value.SnapshotPolicy<Mode> snapshotPolicy() {
            return base.snapshotPolicy();
          }

          @Override
          public <O> design.inherent.reform.validation.DecodedValue<Mode> decode(
              com.mojang.serialization.DynamicOps<O> ops, O input, FieldPath path) {
            return design.inherent.reform.validation.DecodedValue.failure(
                design.inherent.reform.validation.Issue.error(
                    "owner.choice", path, "Not admitted here"));
          }

          @Override
          public boolean equivalent(Mode first, Mode second) {
            return first == Mode.AUTO || first == second;
          }
        };
    var decorated = ValueTypes.choiceDisplay(owner, DISPLAY);
    var path = FieldPath.ROOT.field("mode");
    var result = decorated.decode(JsonOps.INSTANCE, new JsonPrimitive("ON"), path);
    assertEquals("owner.choice", result.report().issues().getFirst().code());
    assertEquals(path, result.report().issues().getFirst().path());
    assertTrue(decorated.equivalent(Mode.AUTO, Mode.OFF));
    assertFalse(decorated.equivalent(Mode.ON, Mode.OFF));
    assertSame(owner.codec(), decorated.codec());
  }

  @Test
  void captionsDoNotChangeCanonicalCodecValidationOrSnapshotPolicy() {
    var original = ValueTypes.enumeration(Mode.class);
    var labeled = ValueTypes.choiceDisplay(original, DISPLAY);
    assertSame(original.codec(), labeled.codec());
    assertSame(original.snapshotPolicy(), labeled.snapshotPolicy());
    assertEquals(
        new JsonPrimitive("AUTO"),
        labeled.codec().encodeStart(JsonOps.INSTANCE, Mode.AUTO).getOrThrow());
    assertEquals(
        Mode.AUTO,
        labeled
            .decode(JsonOps.INSTANCE, new JsonPrimitive("AUTO"), FieldPath.ROOT)
            .value()
            .orElseThrow());
    assertFalse(
        labeled
            .decode(JsonOps.INSTANCE, new JsonPrimitive("Auto"), FieldPath.ROOT)
            .report()
            .valid());
    assertEquals(
        ((ValueShape.Scalar) original.shape()).choices(),
        ((ValueShape.Scalar) labeled.shape()).choices());
    assertEquals(
        "Auto",
        ChoicePresentation.caption(labeled, new DraftNode.Scalar("AUTO")).orElseThrow().fallback());
  }

  @Test
  void unknownAndMalformedNativeInputRemainsUnlabeledAndUnchanged() {
    var type = ValueTypes.choiceDisplay(ValueTypes.enumeration(Mode.class), DISPLAY);
    for (String raw : List.of("", "Auto", "auto", " AUTO ", "not a choice", "\"ON\"")) {
      var node = new DraftNode.Scalar(raw);
      assertTrue(ChoicePresentation.caption(type, node).isEmpty());
      assertEquals(raw, node.raw());
    }
    assertTrue(
        ChoicePresentation.caption(
                type,
                new DraftNode.Scalar(
                    "ON", new DraftNode.UnitInput("ticks", "1", NumericDisplay.State.EXACT)))
            .isEmpty());
  }

  @Test
  void absentOptionalRetainedInputDoesNotMasqueradeAsASelectedChoice() {
    var scalar = ValueTypes.choiceDisplay(ValueTypes.enumeration(Mode.class), DISPLAY);
    var type = ValueTypes.optional(scalar, ValueShape.OptionalEncoding.NULL_OR_VALUE);
    assertTrue(
        ChoicePresentation.caption(
                type, new DraftNode.OptionalValue(false, new DraftNode.Scalar("ON")))
            .isEmpty());
    assertEquals(
        "On",
        ChoicePresentation.caption(
                type, new DraftNode.OptionalValue(true, new DraftNode.Scalar("ON")))
            .orElseThrow()
            .fallback());
    assertTrue(ChoicePresentation.caption(type, new DraftNode.Missing()).isEmpty());
  }

  @Test
  void declarationsAreCopiedBoundedAndCannotInventOwnerChoices() {
    var list = new ArrayList<>(DISPLAY.captions());
    var copied = new ChoiceDisplay(list);
    list.clear();
    assertEquals(3, copied.captions().size());
    assertThrows(UnsupportedOperationException.class, () -> copied.captions().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChoiceDisplay(
                List.of(DISPLAY.captions().getFirst(), DISPLAY.captions().getFirst())));
    assertThrows(IllegalArgumentException.class, () -> new ChoiceDisplay.Caption("ON", "", "On"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChoiceDisplay.Caption("ON", "example.on", "x".repeat(257)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ValueTypes.choiceDisplay(
                ValueTypes.enumeration(Mode.class),
                new ChoiceDisplay(
                    List.of(new ChoiceDisplay.Caption("MAYBE", "example.maybe", "Maybe")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.choiceDisplay(ValueTypes.text(32), DISPLAY));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.choiceDisplay(ValueTypes.integer(0, 2), DISPLAY));
  }

  @Test
  void existingScalarConstructorAndUnlabeledChoicesRetainTheirDefaults() {
    var scalar =
        new ValueShape.Scalar(
            ValueShape.ScalarKind.CHOICE,
            design.inherent.reform.value.NumericDomain.closed(null, null),
            -1,
            List.of("AUTO"),
            Optional.empty());
    assertTrue(scalar.choiceDisplay().isEmpty());
    assertTrue(
        ChoicePresentation.caption(ValueTypes.enumeration(Mode.class), new DraftNode.Scalar("AUTO"))
            .isEmpty());
  }
}
