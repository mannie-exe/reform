package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NumericSliderTest {
  private static ValueShape.Scalar probability() {
    return (ValueShape.Scalar)
        ValueTypes.numericDisplay(
                ValueTypes.decimal(0, 1), NumericDisplay.probability().withSlider())
            .shape();
  }

  @Test
  void onlyAnExplicitSliderQuantizesPointerInputAndTypedInputRemainsExact() {
    var source = new DraftNode.Scalar("0.355");
    var input = NumericInput.open(probability(), source);
    assertEquals(0.355, input.position().orElseThrow());
    assertSame(source, input.expected());
    var selected = input.slide(0.355);
    assertTrue(selected.accepted());
    assertEquals("0.36", selected.input().expected().raw());
    assertEquals("0.355", input.edit("0.355").expected().raw());
    assertEquals("0.35", input.slide(0.354).input().expected().raw());
    assertEquals("0", input.slide(0).input().expected().raw());
    assertEquals("1", input.slide(1).input().expected().raw());
    var plain =
        NumericInput.open(
            (ValueShape.Scalar)
                ValueTypes.numericDisplay(ValueTypes.decimal(0, 1), NumericDisplay.probability())
                    .shape(),
            source);
    assertTrue(plain.position().isEmpty());
    assertFalse(plain.slide(0.5).accepted());
  }

  @Test
  void boundedIntegerSlidersAndPercentDisplaysStillStageCanonicalValues() {
    var shape =
        (ValueShape.Scalar)
            ValueTypes.numericDisplay(
                    ValueTypes.integer(0, 16), NumericDisplay.blocks().withSlider())
                .shape();
    var distance = NumericInput.open(shape, new DraftNode.Scalar("0"));
    assertEquals("6", distance.slide(0.4).input().expected().raw());
    var input =
        NumericInput.open(probability(), new DraftNode.Scalar("0.35")).select("percent").input();
    var next = input.slide(0.427);
    assertEquals("43", next.input().text());
    assertEquals("0.43", next.input().expected().raw());
    assertEquals("percent", next.input().action().orElseThrow().unit());
    assertEquals(0.43, next.input().position().orElseThrow());
  }

  @Test
  void invalidPointerCoordinatesAndNonNumericInputRetainPriorDraft() {
    var input = NumericInput.open(probability(), new DraftNode.Scalar("0.35"));
    for (double coordinate : new double[] {-0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY}) {
      var refused = input.slide(coordinate);
      assertFalse(refused.accepted());
      assertSame(input, refused.input());
    }
    assertTrue(input.edit("-").position().isEmpty());
    assertTrue(input.edit("1.1").position().isEmpty());
    assertSame(input.expected(), input.select("percent").input().expected());
  }

  @Test
  void unrepresentablePointerValueKeepsItsPriorDisplayUnitAndDraft() {
    var mapping =
        new NumericDisplay(
                "base",
                List.of(
                    new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                    new NumericDisplay.Unit("triple", "triple", BigDecimal.valueOf(3))),
                BigDecimal.ONE,
                Optional.empty(),
                DecimalText.Limits.CONFIGURATION)
            .withSlider();
    var shape =
        (ValueShape.Scalar) ValueTypes.numericDisplay(ValueTypes.integer(0, 9), mapping).shape();
    var input = NumericInput.open(shape, new DraftNode.Scalar("3")).select("triple").input();
    var refused = input.slide(0.5);
    assertFalse(refused.accepted());
    assertSame(input, refused.input());
    assertEquals("triple", refused.input().unit());
    var admitted = input.slide(2.0 / 3.0);
    assertTrue(admitted.accepted());
    assertEquals("6", admitted.input().expected().raw());
    assertEquals("2", admitted.input().text());
  }

  @Test
  void missingUnalignedOrFractionalIntegerSliderBoundsRefuseAtScalarDeclaration() {
    var slider = NumericDisplay.blocks().withSlider();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ValueShape.Scalar(
                ValueShape.ScalarKind.DOUBLE,
                null,
                BigDecimal.ONE,
                0,
                List.of(),
                Optional.of(slider)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.numericDisplay(ValueTypes.integer(1, 1), slider));
    var fractional =
        new NumericDisplay(
                "base",
                List.of(new NumericDisplay.Unit("base", "base", BigDecimal.ONE)),
                new BigDecimal("0.3"),
                Optional.empty(),
                DecimalText.Limits.CONFIGURATION)
            .withSlider();
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.numericDisplay(ValueTypes.decimal(0, 1), fractional));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.numericDisplay(ValueTypes.integer(0, 3), fractional));
    assertDoesNotThrow(() -> ValueTypes.numericDisplay(ValueTypes.decimal(0, 3), fractional));
  }

  @Test
  void decimalFormattingNeverExpandsLargeExponentsOrEmitsAnUnadmittedScale() {
    var limits = DecimalText.Limits.CONFIGURATION;
    assertEquals("2000", limits.format(new BigDecimal("2e3")));
    assertEquals("0.00001", limits.format(new BigDecimal("1e-5")));
    for (String raw : List.of("1e4096", "10e4096", "1e-4096", "0e-4096")) {
      String text = limits.format(limits.parse(raw));
      assertTrue(text.length() <= 128);
      assertEquals(0, limits.parse(raw).compareTo(limits.parse(text)));
    }
    var mapping =
        new NumericDisplay(
            "base",
            List.of(
                new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                new NumericDisplay.Unit("ten", "ten", BigDecimal.TEN)),
            BigDecimal.ONE,
            Optional.empty(),
            limits);
    var converted = mapping.toStored("1e4096", "ten");
    assertEquals(NumericDisplay.State.EXACT, converted.state());
    assertDoesNotThrow(() -> limits.parse(converted.convertedText().orElseThrow()));
  }
}
