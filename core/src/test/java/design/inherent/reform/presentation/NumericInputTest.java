package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NumericInputTest {
  private static final NumericDisplay TICKS = NumericDisplay.ticks(BigDecimal.valueOf(20));

  private static ValueShape.Scalar shape(NumericDisplay mapping) {
    return (ValueShape.Scalar)
        ValueTypes.numericDisplay(ValueTypes.integer(0, 100), mapping).shape();
  }

  @Test
  void displaySwitchRetainsCanonicalSpellingAndRetryIdentityWithoutStaging() {
    var canonical = new DraftNode.Scalar("20.00");
    var input = NumericInput.open(shape(TICKS), canonical);
    var seconds = input.select("seconds");
    assertTrue(seconds.accepted());
    assertEquals("1", seconds.input().text());
    assertSame(canonical, seconds.input().expected());
    assertTrue(seconds.input().matches(canonical));
    assertTrue(seconds.input().action().isEmpty());
    var typed = seconds.input().edit("0.15");
    assertEquals(Optional.of(new DraftEdit.SetUnitRaw("seconds", "0.15")), typed.action());
    var millis = typed.select("milliseconds").input();
    assertEquals("1.5E+2", millis.text());
    assertSame(typed.expected(), millis.expected());
    assertEquals(typed.action(), millis.action(), "Retry the actual input, not a relabelled value");
    assertEquals("3", millis.expected().raw());
  }

  @Test
  void malformedTextCanChangeDisplayUnitsWithoutBecomingAValueOrAnAcceptedEdit() {
    for (var raw : List.of("", "-", "1e+", "1/3")) {
      var input =
          NumericInput.open(shape(TICKS), new DraftNode.Scalar("20"))
              .select("seconds")
              .input()
              .edit(raw);
      var switched = input.select("milliseconds");
      assertTrue(switched.accepted());
      assertEquals(raw, switched.input().text());
      assertSame(input.expected(), switched.input().expected());
      assertEquals(input.action(), switched.input().action());
      assertFalse(switched.input().step(1).accepted());
    }
  }

  @Test
  void nonterminatingDisplaySwitchAndStepRefuseWithoutChangingEitherRepresentation() {
    var thirds =
        new NumericDisplay(
            "base",
            List.of(
                new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                new NumericDisplay.Unit("triple", "triple", BigDecimal.valueOf(3))),
            BigDecimal.ONE,
            Optional.empty(),
            DecimalText.Limits.CONFIGURATION);
    var one = NumericInput.open(shape(thirds), new DraftNode.Scalar("1"));
    var refused = one.select("triple");
    assertFalse(refused.accepted());
    assertSame(one, refused.input());
    assertFalse(refused.reason().isBlank());
    var three =
        NumericInput.open(shape(thirds), new DraftNode.Scalar("3")).select("triple").input();
    var step = three.step(1);
    assertFalse(step.accepted());
    assertSame(three, step.input());
    var observed = three.synchronize(new DraftNode.Scalar("4"));
    assertEquals("base", observed.unit());
    assertEquals("4", observed.text());
    assertTrue(observed.action().isEmpty());
    assertFalse(observed.notice().isBlank());
  }

  @Test
  void explicitStepsUseCanonicalUnitsAndRefuseBoundsWithoutClamping() {
    var input =
        NumericInput.open(shape(TICKS), new DraftNode.Scalar("20")).select("seconds").input();
    var incremented = input.step(1);
    assertTrue(incremented.accepted());
    assertEquals("1.05", incremented.input().text());
    assertEquals("21", incremented.input().expected().raw());
    assertEquals(
        new DraftEdit.SetUnitRaw("seconds", "1.05"), incremented.input().action().orElseThrow());
    var minimum = NumericInput.open(shape(TICKS), new DraftNode.Scalar("0"));
    assertFalse(minimum.step(-1).accepted());
    assertSame(minimum, minimum.step(-1).input());
    var maximum = NumericInput.open(shape(TICKS), new DraftNode.Scalar("100"));
    assertFalse(maximum.step(1).accepted());
    assertSame(maximum, maximum.step(1).input());
    assertThrows(IllegalArgumentException.class, () -> maximum.step(0));
  }

  @Test
  void actorSynchronizationRetainsOriginalInvalidInputAndRecoversUnavailableUnitsExplicitly() {
    var canonical = new DraftNode.Scalar("20");
    var old = NumericInput.open(shape(TICKS), canonical).select("seconds").input();
    var invalid =
        new DraftNode.Scalar(
            "-", new DraftNode.UnitInput("milliseconds", "-", NumericDisplay.State.INCOMPLETE));
    var observed = old.synchronize(invalid);
    assertEquals("milliseconds", observed.unit());
    assertEquals("-", observed.text());
    assertSame(invalid, observed.expected());
    assertTrue(observed.action().isEmpty());
    var unknown =
        new DraftNode.Scalar(
            "1", new DraftNode.UnitInput("removed", "1", NumericDisplay.State.EXACT));
    var unavailable = observed.synchronize(unknown);
    assertEquals("removed", unavailable.unit());
    assertFalse(unavailable.select("ticks").accepted());
    assertSame(unknown, unavailable.expected());
    var recovered = unavailable.synchronize(canonical);
    assertEquals("ticks", recovered.unit());
    assertEquals("20", recovered.text());
    assertSame(canonical, recovered.expected());
  }

  @Test
  void savedCanonicalNormalizationKeepsExactDisplaySpellingButCannotAcceptAFailedMapping() {
    var input =
        NumericInput.open(shape(TICKS), new DraftNode.Scalar("20"))
            .select("seconds")
            .input()
            .edit("0.150");
    var saved = new DraftNode.Scalar("3.0");
    var observed = input.synchronize(saved);
    assertEquals("0.150", observed.text());
    assertTrue(observed.matches(saved));
    assertTrue(observed.action().isEmpty());
    assertEquals("0.2", observed.synchronize(new DraftNode.Scalar("4")).text());
    var tiny =
        new NumericDisplay(
            "base",
            List.of(
                new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                new NumericDisplay.Unit("tiny", "tiny", new BigDecimal("1e-4096"))),
            BigDecimal.ONE,
            Optional.empty(),
            DecimalText.Limits.CONFIGURATION);
    var failed =
        NumericInput.open(shape(tiny), new DraftNode.Scalar("0.1"))
            .select("tiny")
            .input()
            .edit("0.1");
    assertEquals(NumericDisplay.State.LIMIT, failed.expected().unitInput().orElseThrow().state());
    var replacement = new DraftNode.Scalar("0.1");
    var actual = failed.synchronize(replacement);
    assertTrue(actual.matches(replacement));
    assertNotEquals("0.1", actual.text());
  }

  @Test
  void synchronizationPreservesZeroSignFromTheActualOwnerObservation() {
    var shape =
        (ValueShape.Scalar) ValueTypes.numericDisplay(ValueTypes.decimal(-1, 1), TICKS).shape();
    var positive = NumericInput.open(shape, new DraftNode.Scalar("0.0")).edit("0.000");
    var negativeSource = new DraftNode.Scalar("-0.0");
    var negative = positive.synchronize(negativeSource);
    assertEquals("-0.0", negative.text());
    assertTrue(negative.matches(negativeSource));
    var negativeSpelling = negative.edit("-0.000");
    assertEquals("-0.000", negativeSpelling.synchronize(new DraftNode.Scalar("-0")).text());
    var positiveSource = new DraftNode.Scalar("0.0");
    assertEquals("0.0", negativeSpelling.synchronize(positiveSource).text());
  }
}
