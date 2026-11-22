package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.value.NumericDomain;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NumericOpenInputTest {
  private static ValueShape.Scalar shape(NumericDomain domain, boolean slider) {
    var display = NumericDisplay.probability();
    return (ValueShape.Scalar)
        ValueTypes.numericDisplay(
                ValueTypes.decimal(domain), slider ? display.withSlider() : display)
            .shape();
  }

  @Test
  void stepsRefuseExcludedBoundsWithoutChangingTheExactDraft() {
    var shape = shape(NumericDomain.open(BigDecimal.ZERO, BigDecimal.ONE), false);
    var lower = NumericInput.open(shape, new DraftNode.Scalar("0.01"));
    var refused = lower.step(-1);
    assertFalse(refused.accepted());
    assertSame(lower, refused.input());
    assertTrue(refused.reason().contains("value > 0"));
    assertEquals("0.02", lower.step(1).input().expected().raw());
    var upper = NumericInput.open(shape, new DraftNode.Scalar("0.99"));
    assertFalse(upper.step(1).accepted());
    assertTrue(upper.step(-1).accepted());
    var tiny = NumericInput.open(shape, new DraftNode.Scalar("1e-100"));
    assertEquals("1e-100", tiny.text());
    assertTrue(tiny.action().isEmpty());
    assertFalse(tiny.step(-1).accepted());
  }

  @Test
  void openSliderEndpointsSelectOnlyAdmittedGridPointsWithoutNarrowingExactEntry() {
    var shape = shape(NumericDomain.open(BigDecimal.ZERO, BigDecimal.ONE), true);
    var input = NumericInput.open(shape, new DraftNode.Scalar("0.0000000001"));
    assertEquals(1e-10, input.position().orElseThrow());
    assertEquals("0.0000000001", input.expected().raw());
    assertTrue(input.action().isEmpty());
    assertEquals("0.01", input.slide(0).input().expected().raw());
    assertEquals("0.99", input.slide(1).input().expected().raw());
    assertEquals("0.5", input.slide(0.5).input().expected().raw());
    assertEquals("0.0000000001", input.expected().raw());
    assertTrue(input.edit("0").position().isEmpty());
    assertTrue(input.edit("1").position().isEmpty());
  }

  @Test
  void unitChangesPreserveTheOpenCanonicalDomainAndDoNotStageAnEdit() {
    var shape = shape(NumericDomain.openClosed(BigDecimal.ZERO, BigDecimal.ONE), true);
    var source = new DraftNode.Scalar("0.00001");
    var input = NumericInput.open(shape, source).select("percent").input();
    assertEquals("0.001", input.text());
    assertSame(source, input.expected());
    assertTrue(input.action().isEmpty());
    assertEquals("1", input.slide(0).input().text());
    assertEquals("100", input.slide(1).input().text());
    assertFalse(input.step(-1).accepted());
    var invalid = input.edit("0");
    assertEquals("0", invalid.text());
    assertEquals("0", invalid.expected().raw());
    assertTrue(invalid.position().isEmpty());
  }

  @Test
  void emptyGridRefusesBeforeOpeningWhileOnePointAndOneSidedTypesStayDistinct() {
    assertThrows(
        IllegalArgumentException.class,
        () -> shape(NumericDomain.open(BigDecimal.ZERO, new BigDecimal("0.01")), true));
    var one = shape(NumericDomain.open(BigDecimal.ZERO, new BigDecimal("0.02")), true);
    var input = NumericInput.open(one, new DraftNode.Scalar("0.005"));
    assertEquals("0.01", input.slide(0).input().expected().raw());
    assertEquals("0.01", input.slide(1).input().expected().raw());
    assertDoesNotThrow(() -> shape(NumericDomain.openClosed(BigDecimal.ZERO, null), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> shape(NumericDomain.openClosed(BigDecimal.ZERO, null), true));
  }

  @Test
  void convenienceStepsRefuseValuesThatWouldRoundAcrossAnOpenBoundOrTruncateAnInteger() {
    var display =
        new NumericDisplay(
            "base",
            List.of(new NumericDisplay.Unit("base", "base", BigDecimal.ONE)),
            new BigDecimal("0.0999999999999999999"),
            Optional.empty(),
            DecimalText.Limits.CONFIGURATION);
    var shape =
        (ValueShape.Scalar)
            ValueTypes.numericDisplay(
                    ValueTypes.decimal(NumericDomain.closedOpen(BigDecimal.ZERO, BigDecimal.ONE)),
                    display)
                .shape();
    var input = NumericInput.open(shape, new DraftNode.Scalar("0.9"));
    assertFalse(input.step(1).accepted());
    assertSame(input, input.step(1).input());
    var integer =
        (ValueShape.Scalar)
            ValueTypes.numericDisplay(ValueTypes.integer(0, 3), NumericDisplay.probability())
                .shape();
    assertFalse(NumericInput.open(integer, new DraftNode.Scalar("1")).step(1).accepted());
  }

  @Test
  void sliderDeclarationRefusesAnEdgeThatNativeRoundingWouldMoveOutsideTheDomain() {
    var domain =
        NumericDomain.closed(
            new BigDecimal("0.100000000000000001"), new BigDecimal("0.200000000000000001"));
    assertDoesNotThrow(() -> ValueTypes.decimal(domain));
    assertThrows(IllegalArgumentException.class, () -> shape(domain, true));
  }
}
