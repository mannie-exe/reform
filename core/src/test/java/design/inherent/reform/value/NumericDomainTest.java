package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.presentation.NumericDisplay;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class NumericDomainTest {
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final BigDecimal ONE = BigDecimal.ONE;

  @Test
  void endpointsAreIndependentAndCompareSemanticValuesRatherThanSpelling() {
    for (boolean lower : List.of(false, true)) {
      for (boolean upper : List.of(false, true)) {
        var domain = new NumericDomain(ZERO, lower, ONE, upper);
        var type = ValueTypes.decimal(domain);
        for (String zero : List.of("0", "-0.0", "0e100"))
          assertEquals(lower, parses(type, zero), zero);
        for (String one : List.of("1", "1.000", "1e0")) assertEquals(upper, parses(type, one), one);
        assertTrue(parses(type, "1e-300"));
        assertTrue(parses(type, "0.9999999999999999"));
        assertFalse(parses(type, "-1e-300"));
        assertFalse(parses(type, "1.00000000000000000001"));
      }
    }
    assertTrue(parses(ValueTypes.decimal(0, 1), "0"));
    assertTrue(parses(ValueTypes.decimal(0, 1), "1"));
  }

  @Test
  void everyNumericFactoryEnforcesBothAuthoredAndEncodedBounds() {
    var domain = NumericDomain.open(ZERO, BigDecimal.valueOf(3));
    verifyFactory(ValueTypes.byteInteger(domain), (byte) 1, (byte) 0);
    verifyFactory(ValueTypes.shortInteger(domain), (short) 1, (short) 0);
    verifyFactory(ValueTypes.integer(domain), 1, 0);
    verifyFactory(ValueTypes.longInteger(domain), 1L, 0L);
    verifyFactory(ValueTypes.floating(domain), 1F, 0F);
    verifyFactory(ValueTypes.decimal(domain), 1D, 0D);
  }

  @Test
  void oneSidedAndFixedDomainsRemainHonestAboutTypeRepresentation() {
    assertTrue(parses(ValueTypes.decimal(NumericDomain.openClosed(ZERO, null)), "1e100"));
    assertFalse(parses(ValueTypes.decimal(NumericDomain.openClosed(ZERO, null)), "0"));
    assertTrue(parses(ValueTypes.integer(NumericDomain.closedOpen(null, ONE)), "-2147483648"));
    assertFalse(parses(ValueTypes.integer(NumericDomain.closedOpen(null, ONE)), "1"));
    assertFalse(parses(ValueTypes.integer(NumericDomain.closed(null, null)), "2147483648"));
    assertTrue(parses(ValueTypes.decimal(NumericDomain.closed(ONE, ONE)), "1.0"));
    assertThrows(IllegalArgumentException.class, () -> NumericDomain.open(ONE, ONE));
    assertThrows(IllegalArgumentException.class, () -> NumericDomain.closed(ONE, ZERO));
    assertThrows(IllegalArgumentException.class, () -> new NumericDomain(null, false, ONE, true));
    assertThrows(
        IllegalArgumentException.class, () -> ValueTypes.integer(NumericDomain.open(ZERO, ONE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.byteInteger(NumericDomain.closed(BigDecimal.valueOf(128), null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.decimal(NumericDomain.open(ZERO, new BigDecimal("1e-400"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.floating(NumericDomain.open(ZERO, new BigDecimal("1e-50"))));
    assertDoesNotThrow(
        () -> ValueTypes.integer(NumericDomain.closed(new BigDecimal("1e-2147483647"), ONE)));
  }

  @Test
  void tinyValuesSurviveWhileNativeRoundingCannotCrossAnOpenBound() {
    var positive = ValueTypes.decimal(NumericDomain.openClosed(ZERO, ONE));
    assertEquals(
        1e-300,
        positive.codec().parse(JsonOps.INSTANCE, JsonParser.parseString("1e-300")).getOrThrow());
    assertFalse(parses(positive, "1e-400"));
    var belowOne = ValueTypes.decimal(NumericDomain.closedOpen(ZERO, ONE));
    assertFalse(parses(belowOne, "0.99999999999999999999999999999"));
    assertFalse(belowOne.codec().encodeStart(JsonOps.INSTANCE, Double.NaN).isSuccess());
    assertFalse(
        belowOne.codec().encodeStart(JsonOps.INSTANCE, Double.POSITIVE_INFINITY).isSuccess());
    var floatPositive = ValueTypes.floating(NumericDomain.openClosed(ZERO, ONE));
    assertTrue(parses(floatPositive, "1e-40"));
    assertFalse(parses(floatPositive, "1e-50"));
    assertFalse(parses(ValueTypes.floating(NumericDomain.closedOpen(ZERO, ONE)), "0.9999999999"));
  }

  @Test
  void presentationWrappersPreserveTheSameDomainAndLegacyConstructorsRemainClosed() {
    var domain = NumericDomain.openClosed(ZERO, ONE);
    var type = ValueTypes.numericDisplay(ValueTypes.decimal(domain), NumericDisplay.probability());
    var shape = (ValueShape.Scalar) type.shape();
    assertSame(domain, shape.domain());
    assertFalse(shape.domain().minimumInclusive());
    assertTrue(shape.domain().maximumInclusive());
    assertFalse(parses(type, "0"));
    var wrapped = ValueTypes.numericDisplay(type, NumericDisplay.probability());
    assertSame(domain, ((ValueShape.Scalar) wrapped.shape()).domain());
    var old = new ValueShape.Scalar(ValueShape.ScalarKind.DOUBLE, ZERO, ONE, -1, List.of());
    assertTrue(old.domain().minimumInclusive());
    assertTrue(old.domain().maximumInclusive());
  }

  @Test
  void finitePrimitiveExtremaRoundtripThroughTheirOwnDeclaredTypes() {
    var floats = ValueTypes.floating(-Float.MAX_VALUE, Float.MAX_VALUE);
    for (float value :
        new float[] {-Float.MAX_VALUE, -Float.MIN_VALUE, Float.MIN_VALUE, Float.MAX_VALUE}) {
      var encoded = floats.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
      assertEquals(value, floats.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
    assertFalse(parses(floats, "3.40282350000000000001e38"));
    var doubles = ValueTypes.decimal(-Double.MAX_VALUE, Double.MAX_VALUE);
    for (double value :
        new double[] {-Double.MAX_VALUE, -Double.MIN_VALUE, Double.MIN_VALUE, Double.MAX_VALUE}) {
      var encoded = doubles.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
      assertEquals(value, doubles.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
  }

  private static boolean parses(ValueType<?> type, String raw) {
    return type.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(raw)).isSuccess();
  }

  private static <N extends Number> void verifyFactory(ValueType<N> type, N valid, N excluded) {
    assertTrue(parses(type, "1"));
    assertFalse(parses(type, "0"));
    assertFalse(parses(type, "3"));
    assertEquals(
        valid, type.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(valid)).getOrThrow());
    assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, valid).isSuccess());
    assertFalse(type.codec().encodeStart(JsonOps.INSTANCE, excluded).isSuccess());
  }
}
