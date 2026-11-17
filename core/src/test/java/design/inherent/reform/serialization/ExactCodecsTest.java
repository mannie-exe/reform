package design.inherent.reform.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ExactCodecsTest {
  @Property(tries = 300)
  void signedLongRoundTrip(@ForAll long value) {
    var encoded = ExactCodecs.LONG.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
    assertEquals(value, ExactCodecs.LONG.parse(JsonOps.INSTANCE, encoded).getOrThrow());
  }

  @Property(tries = 300)
  void integralDecimalSpellingRetainsValue(
      @ForAll int value, @ForAll @IntRange(min = 1, max = 8) int zeroes) {
    var input = JsonParser.parseString(value + "." + "0".repeat(zeroes));
    assertEquals(value, ExactCodecs.INT.parse(JsonOps.INSTANCE, input).getOrThrow());
  }

  @Property(tries = 200)
  void fractionNeverBecomesAnInteger(@ForAll int value) {
    assertTrue(
        ExactCodecs.INT.parse(JsonOps.INSTANCE, JsonParser.parseString(value + ".5")).isError());
  }

  @Test
  void rejectsCoercionsAndOverflowForEachIntegerKind() {
    refuses(ExactCodecs.BYTE, "128", "-129", "0.5");
    refuses(ExactCodecs.SHORT, "32768", "-32769", "0.5");
    refuses(ExactCodecs.INT, "2147483648", "-2147483649", "1.9");
    refuses(ExactCodecs.LONG, "9223372036854775808", "-9223372036854775809", "1.9");
    for (var codec :
        new Codec<?>[] {ExactCodecs.INT, ExactCodecs.LONG, ExactCodecs.FLOAT, ExactCodecs.DOUBLE}) {
      assertTrue(codec.parse(JsonOps.COMPRESSED, new JsonPrimitive("1")).isError());
      assertTrue(codec.parse(JsonOps.INSTANCE, new JsonPrimitive(true)).isError());
    }
  }

  @Test
  void extremeCompactExponentsFinishWithoutExpandingPowers() {
    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          refuses(ExactCodecs.LONG, "1e2147483647", "1e-2147483647");
          assertEquals(
              0,
              ExactCodecs.INT
                  .parse(JsonOps.INSTANCE, JsonParser.parseString("0e2147483647"))
                  .getOrThrow());
        });
  }

  @Test
  void finiteFloatingPointHasExplicitRoundingUnderflowAndZeroPolicy() {
    assertEquals(
        0.1d,
        ExactCodecs.DOUBLE.parse(JsonOps.INSTANCE, JsonParser.parseString("0.1")).getOrThrow());
    assertEquals(
        Double.doubleToRawLongBits(-0.0d),
        Double.doubleToRawLongBits(
            ExactCodecs.DOUBLE
                .parse(JsonOps.INSTANCE, JsonParser.parseString("-0.0"))
                .getOrThrow()));
    refuses(ExactCodecs.DOUBLE, "1e309", "1e-400");
    refuses(ExactCodecs.FLOAT, "1e39", "1e-60");
    assertTrue(ExactCodecs.DOUBLE.encodeStart(JsonOps.INSTANCE, Double.NaN).isError());
    assertTrue(
        ExactCodecs.DOUBLE.encodeStart(JsonOps.INSTANCE, Double.POSITIVE_INFINITY).isError());
    assertTrue(ExactCodecs.FLOAT.encodeStart(JsonOps.INSTANCE, Float.NEGATIVE_INFINITY).isError());
  }

  private static void refuses(Codec<?> codec, String... values) {
    for (String value : values) {
      var result = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(value));
      assertTrue(result.isError(), value);
      assertTrue(result.resultOrPartial().isEmpty(), value);
    }
  }
}
