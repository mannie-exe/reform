package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.validation.DecodedValue;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NumericDisplayTest {
  private static final NumericDisplay TICKS = NumericDisplay.ticks(BigDecimal.valueOf(20));

  private static void exact(String expected, NumericDisplay.Conversion result) {
    assertEquals(NumericDisplay.State.EXACT, result.state(), result.reason());
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(result.text())));
  }

  @Test
  void durationDistanceAndProbabilityUseExplicitCanonicalFactors() {
    exact("2.5", TICKS.toStored("0.125", "seconds"));
    exact("100", TICKS.toStored("5000", "milliseconds"));
    exact("0.05", TICKS.fromStored("1", "seconds"));
    exact("50", TICKS.fromStored("1", "milliseconds"));
    assertEquals(Optional.of(BigDecimal.valueOf(20)), TICKS.referenceTicksPerSecond());
    exact("771.604875", NumericDisplay.blocks().fromStored("12345.678", "chunks"));
    exact("256", NumericDisplay.blocks().toStored("16", "chunks"));
    exact("80", NumericDisplay.probability().fromStored("0.8", "percent"));
    exact("0.35", NumericDisplay.probability().toStored("35", "percent"));
    exact("0.35", NumericDisplay.percentStored().fromStored("35", "probability"));
    exact("35", NumericDisplay.percentStored().toStored("0.35", "probability"));
    assertEquals("percent", NumericDisplay.percentStored().canonicalUnit());
    assertEquals("probability", NumericDisplay.probability().canonicalUnit());
  }

  @Test
  void canonicalDisplayPreservesExactSourceSpellingAndMalformedInput() {
    for (String raw : List.of("+001.0", " 1e2 ", "-0.000", "1.000e+2")) {
      var unchanged = TICKS.fromStored(raw, "ticks");
      assertEquals(NumericDisplay.State.EXACT, unchanged.state());
      assertEquals(raw, unchanged.text());
      assertEquals(raw, unchanged.sourceText());
    }
    for (String raw : List.of("", "  ", "-", "+", ".", " 1e+ ", "bad", "1/3")) {
      var result = TICKS.convert(raw, "ticks", "seconds");
      assertNotEquals(NumericDisplay.State.EXACT, result.state());
      assertEquals(raw, result.sourceText());
      assertEquals(raw, result.text());
    }
    assertEquals(NumericDisplay.State.EMPTY, TICKS.toStored(" ", "seconds").state());
    assertEquals(NumericDisplay.State.INCOMPLETE, TICKS.toStored(" - ", "seconds").state());
    assertEquals(NumericDisplay.State.INVALID, TICKS.toStored("1/3", "seconds").state());
  }

  @Test
  void repeatingConversionsRefuseWithoutRoundingOrDiscardingInput() {
    var thirds =
        new NumericDisplay(
            "base",
            List.of(
                new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                new NumericDisplay.Unit("triple", "triple", BigDecimal.valueOf(3))),
            BigDecimal.ONE,
            Optional.empty(),
            DecimalText.Limits.CONFIGURATION);
    var repeating = thirds.fromStored("1.0", "triple");
    assertEquals(NumericDisplay.State.UNREPRESENTABLE, repeating.state());
    assertEquals("1.0", repeating.text());
    exact("1", thirds.fromStored("3", "triple"));
    assertThrows(IllegalArgumentException.class, () -> thirds.fromStored("3", "unknown"));
  }

  @Test
  void fractionalStoredIntegersStillNeedTheOwnersCompleteCodec() {
    var original = ValueTypes.integer(0, 12000);
    var decorated = ValueTypes.numericDisplay(original, TICKS);
    assertSame(original.codec(), decorated.codec());
    assertSame(original.snapshotPolicy(), decorated.snapshotPolicy());
    var shape = (ValueShape.Scalar) decorated.shape();
    assertEquals(TICKS, shape.numericDisplay().orElseThrow());
    assertEquals(((ValueShape.Scalar) original.shape()).minimum(), shape.minimum());
    var fractional = TICKS.toStored("0.125", "seconds");
    exact("2.5", fractional);
    assertTrue(
        decorated
            .codec()
            .parse(JsonOps.INSTANCE, JsonParser.parseString(fractional.text()))
            .error()
            .isPresent());
    var integral = TICKS.toStored("0.15", "seconds");
    assertEquals(
        3,
        decorated
            .codec()
            .parse(JsonOps.INSTANCE, JsonParser.parseString(integral.text()))
            .getOrThrow());
    var outOfRange = TICKS.toStored("601", "seconds");
    assertTrue(
        decorated
            .codec()
            .parse(JsonOps.INSTANCE, JsonParser.parseString(outOfRange.text()))
            .error()
            .isPresent());
  }

  @Test
  void numericDecorationRetainsCustomDecodeDiagnosticsAndEquivalence() {
    var decodes = new AtomicInteger();
    var equivalent = new AtomicInteger();
    var original =
        new ValueType<Integer>() {
          @Override
          public Codec<Integer> codec() {
            return Codec.INT;
          }

          @Override
          public ValueShape shape() {
            return ValueTypes.integer(0, 10).shape();
          }

          @Override
          public SnapshotPolicy<Integer> snapshotPolicy() {
            return SnapshotPolicy.immutable();
          }

          @Override
          public boolean equivalent(Integer a, Integer b) {
            equivalent.incrementAndGet();
            return a / 2 == b / 2;
          }

          @Override
          public <O> DecodedValue<Integer> decode(DynamicOps<O> ops, O value, FieldPath path) {
            decodes.incrementAndGet();
            return DecodedValue.failure(
                Issue.error("fixture.original", path, "Original native adapter"));
          }
        };
    var decorated = ValueTypes.numericDisplay(original, TICKS);
    var path = FieldPath.ROOT.field("duration");
    var result = decorated.decode(JsonOps.INSTANCE, JsonParser.parseString("2"), path);
    assertEquals("fixture.original", result.report().issues().getFirst().code());
    assertEquals(path, result.report().issues().getFirst().path());
    assertEquals(1, decodes.get());
    assertTrue(decorated.equivalent(2, 3));
    assertEquals(1, equivalent.get());
    assertTrue(((ValueShape.Scalar) original.shape()).numericDisplay().isEmpty());
  }

  @Test
  void signedZeroSurvivesExactDisplayMappingAndNativeDoubleDecoding() {
    var forward = TICKS.fromStored("-0.000", "seconds");
    var reverse = TICKS.toStored(forward.text(), "seconds");
    assertTrue(forward.text().startsWith("-"));
    assertTrue(reverse.text().startsWith("-"));
    var value =
        ValueTypes.decimal(-1, 1)
            .codec()
            .parse(JsonOps.INSTANCE, JsonParser.parseString(reverse.text()))
            .getOrThrow();
    assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(value));
  }

  @Test
  void inputAndResultBudgetsRefuseHugeExponentsWithoutExpandingThem() {
    assertEquals(NumericDisplay.State.LIMIT, TICKS.toStored("1e2147483647", "seconds").state());
    assertEquals(NumericDisplay.State.LIMIT, TICKS.toStored("9".repeat(129), "seconds").state());
    var tiny = TICKS.toStored("1e-4096", "milliseconds");
    assertEquals(NumericDisplay.State.LIMIT, tiny.state());
    assertEquals("1e-4096", tiny.text());
    assertThrows(
        IllegalArgumentException.class,
        () -> new NumericDisplay.Unit("huge", "huge", new BigDecimal("1e2147483647")));
    assertThrows(IllegalArgumentException.class, () -> new DecimalText.Limits(128, 4097));
    exact("2E+4096", TICKS.toStored("1E+4095", "seconds"));
  }

  @Test
  void metadataRequiresUniqueUnitsOneCanonicalAndPositiveBoundedFactors() {
    var base = new NumericDisplay.Unit("base", "base", BigDecimal.ONE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NumericDisplay(
                "base",
                List.of(base, base),
                BigDecimal.ONE,
                Optional.empty(),
                DecimalText.Limits.CONFIGURATION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NumericDisplay(
                "other",
                List.of(base),
                BigDecimal.ONE,
                Optional.empty(),
                DecimalText.Limits.CONFIGURATION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NumericDisplay.Unit("base", "base", BigDecimal.ZERO));
    assertThrows(IllegalArgumentException.class, () -> NumericDisplay.ticks(BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> NumericDisplay.ticks(BigDecimal.valueOf(-20)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ValueShape.Scalar(
                ValueShape.ScalarKind.TEXT, null, null, 32, List.of(), Optional.of(TICKS)));
    assertThrows(UnsupportedOperationException.class, () -> TICKS.units().clear());
  }

  @Test
  void seededFiniteUnitRoundTripsRetainValueAcrossLargeSmallAndNegativeInputs() {
    var random = new Random(2026111806L);
    for (int i = 0; i < 200; i++) {
      var value = BigDecimal.valueOf(random.nextInt(2000001) - 1000000, random.nextInt(7));
      String raw = value.toPlainString();
      for (var display :
          List.of(
              TICKS,
              NumericDisplay.blocks(),
              NumericDisplay.probability(),
              NumericDisplay.percentStored()))
        for (var unit : display.units()) {
          var forward = display.fromStored(raw, unit.id());
          assertEquals(NumericDisplay.State.EXACT, forward.state());
          var reverse = display.toStored(forward.text(), unit.id());
          assertEquals(NumericDisplay.State.EXACT, reverse.state());
          assertEquals(0, value.compareTo(new BigDecimal(reverse.text())));
        }
    }
  }
}
