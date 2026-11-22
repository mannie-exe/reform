package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.value.NumericDomain;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NumericCaptionTest {
  private static final NumericDisplay.Caption OFF =
      new NumericDisplay.Caption(BigDecimal.ZERO, "fixture.off", "Off");

  @Test
  void exactSemanticStoredValuesIgnoreSpellingAndUnitsButNeverRoundedNeighbors() {
    var display = NumericDisplay.ticks(BigDecimal.valueOf(20)).withCaptions(List.of(OFF));
    var type = ValueTypes.numericDisplay(ValueTypes.decimal(0, 100), display);
    for (String raw : List.of("0", "0.00", "-0", "0e4")) {
      var source = new DraftNode.Scalar(raw);
      assertEquals(Optional.of(OFF), NumericPresentation.caption(type, source));
      var input = NumericInput.open((ValueShape.Scalar) type.shape(), source);
      assertEquals(Optional.of(OFF), input.caption());
      var seconds = input.select("seconds").input();
      assertEquals(Optional.of(OFF), seconds.caption());
      assertSame(source, seconds.expected());
      assertTrue(seconds.action().isEmpty());
      assertEquals(raw, source.raw());
    }
    for (String raw : List.of("1e-300", "1", "-", "", "1e-400", "NaN", "-0.01"))
      assertTrue(NumericPresentation.caption(type, new DraftNode.Scalar(raw)).isEmpty(), raw);
    assertEquals("0.0", type.codec().encodeStart(JsonOps.INSTANCE, 0D).getOrThrow().toString());
    assertTrue(NumericDisplay.ticks(BigDecimal.valueOf(20)).caption(BigDecimal.ZERO).isEmpty());
  }

  @Test
  void failedOrStaleUnitMappingNeverAcquiresASpecialMeaning() {
    var type =
        ValueTypes.numericDisplay(
            ValueTypes.integer(0, 100),
            NumericDisplay.ticks(BigDecimal.valueOf(20)).withCaptions(List.of(OFF)));
    var exact =
        new DraftNode.Scalar(
            "0", new DraftNode.UnitInput("seconds", "0", NumericDisplay.State.EXACT));
    assertEquals(Optional.of(OFF), NumericPresentation.caption(type, exact));
    for (var input :
        List.of(
            new DraftNode.UnitInput("seconds", "0", NumericDisplay.State.INVALID),
            new DraftNode.UnitInput("seconds", "1", NumericDisplay.State.EXACT),
            new DraftNode.UnitInput("missing", "0", NumericDisplay.State.EXACT)))
      assertTrue(NumericPresentation.caption(type, new DraftNode.Scalar("0", input)).isEmpty());
    var omitted = ValueTypes.defaultedField(type, 0);
    assertTrue(
        NumericPresentation.caption(
                omitted, new DraftNode.Defaulted(false, new DraftNode.Scalar("0")))
            .isEmpty());
    assertEquals(
        Optional.of(OFF),
        NumericPresentation.caption(
            omitted, new DraftNode.Defaulted(true, new DraftNode.Scalar("0"))));
  }

  @Test
  void declarationsAreBoundedUniqueAndCheckedAgainstTheirActualTypeAndDomain() {
    var display = NumericDisplay.probability().withCaptions(List.of(OFF));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ValueTypes.numericDisplay(
                ValueTypes.decimal(NumericDomain.openClosed(BigDecimal.ZERO, BigDecimal.ONE)),
                display));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            display.withCaptions(
                List.of(
                    OFF,
                    new NumericDisplay.Caption(new BigDecimal("0.00"), "fixture.other", "Other"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NumericDisplay.Caption(BigDecimal.ZERO, "fixture.long", "x".repeat(257)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            display.withCaptions(
                java.util.stream.IntStream.range(0, 33)
                    .mapToObj(
                        i ->
                            new NumericDisplay.Caption(
                                BigDecimal.valueOf(i), "fixture." + i, "Value"))
                    .toList()));
    assertEquals(List.of(OFF), display.withSlider().captions());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ValueTypes.numericDisplay(
                ValueTypes.decimal(0, 1),
                display.withCaptions(
                    List.of(
                        new NumericDisplay.Caption(
                            new BigDecimal("0.10000000000000000001"),
                            "fixture.rounded",
                            "Rounded")))));
    var nativeInitial =
        new NumericDisplay.Caption(BigDecimal.ZERO, "fixture.native", "Native initial view");
    assertEquals(
        Optional.of(nativeInitial),
        display.withCaptions(List.of(nativeInitial)).caption(BigDecimal.ZERO));
  }
}
