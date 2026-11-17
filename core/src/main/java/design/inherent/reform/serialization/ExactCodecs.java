package design.inherent.reform.serialization;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.math.BigDecimal;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** Numeric integrity guards around DFU. Resource admission still belongs to the input/backend. */
public final class ExactCodecs {
  public static final Codec<Boolean> BOOL =
      jsonKind(Codec.BOOL, com.google.gson.JsonPrimitive::isBoolean);
  public static final Codec<String> STRING =
      jsonKind(Codec.STRING, com.google.gson.JsonPrimitive::isString);
  public static final Codec<Byte> BYTE =
      integral(
          Codec.BYTE,
          BigDecimal.valueOf(Byte.MIN_VALUE),
          BigDecimal.valueOf(Byte.MAX_VALUE),
          BigDecimal::byteValueExact);
  public static final Codec<Short> SHORT =
      integral(
          Codec.SHORT,
          BigDecimal.valueOf(Short.MIN_VALUE),
          BigDecimal.valueOf(Short.MAX_VALUE),
          BigDecimal::shortValueExact);
  public static final Codec<Integer> INT =
      integral(
          Codec.INT,
          BigDecimal.valueOf(Integer.MIN_VALUE),
          BigDecimal.valueOf(Integer.MAX_VALUE),
          BigDecimal::intValueExact);
  public static final Codec<Long> LONG =
      integral(
          Codec.LONG,
          BigDecimal.valueOf(Long.MIN_VALUE),
          BigDecimal.valueOf(Long.MAX_VALUE),
          BigDecimal::longValueExact);
  public static final Codec<Float> FLOAT =
      numeric(
              Codec.FLOAT,
              new BigDecimal(-Float.MAX_VALUE),
              new BigDecimal(Float.MAX_VALUE),
              (decimal, text) -> {
                float value = Float.parseFloat(text);
                if (!Float.isFinite(value) || value == 0 && decimal.signum() != 0) {
                  throw new ArithmeticException("Floating-point overflow/underflow");
                }
                return value;
              })
          .validate(value -> Float.isFinite(value) ? DataResult.success(value) : invalidNumber());
  public static final Codec<Double> DOUBLE =
      numeric(
              Codec.DOUBLE,
              new BigDecimal(-Double.MAX_VALUE),
              new BigDecimal(Double.MAX_VALUE),
              (decimal, text) -> {
                double value = Double.parseDouble(text);
                if (!Double.isFinite(value) || value == 0 && decimal.signum() != 0) {
                  throw new ArithmeticException("Floating-point overflow/underflow");
                }
                return value;
              })
          .validate(value -> Double.isFinite(value) ? DataResult.success(value) : invalidNumber());

  private static <N extends Number> Codec<N> integral(
      Codec<N> encoder,
      BigDecimal minimum,
      BigDecimal maximum,
      Function<BigDecimal, N> conversion) {
    return numeric(encoder, minimum, maximum, (decimal, text) -> conversion.apply(decimal));
  }

  /** Check authored numeric bounds before native floating-point rounding loses that information. */
  public static <N extends Number> Codec<N> bounded(
      Codec<N> codec, BigDecimal minimum, BigDecimal maximum) {
    if (minimum.compareTo(maximum) > 0)
      throw new IllegalArgumentException("Reversed numeric bounds");
    Codec<N> validated =
        codec.validate(
            value ->
                inRange(value.toString(), minimum, maximum)
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Number is outside the declared range"));
    return Codec.of(
        validated,
        new Decoder<N>() {
          @Override
          public <O> DataResult<Pair<N, O>> decode(DynamicOps<O> ops, O input) {
            var number = ops.getNumberValue(input).result();
            if (number.isEmpty() || !inRange(number.orElseThrow().toString(), minimum, maximum)) {
              return DataResult.error(() -> "Number is outside the declared range");
            }
            return validated.decode(ops, input);
          }
        });
  }

  private static boolean inRange(String text, BigDecimal minimum, BigDecimal maximum) {
    try {
      var value = new BigDecimal(text);
      return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static <T> Codec<T> jsonKind(
      Codec<T> codec, Predicate<com.google.gson.JsonPrimitive> kind) {
    return Codec.of(
        codec,
        new Decoder<T>() {
          @Override
          public <O> DataResult<Pair<T, O>> decode(DynamicOps<O> ops, O input) {
            if (ops instanceof JsonOps
                && input instanceof JsonElement json
                && (!json.isJsonPrimitive() || !kind.test(json.getAsJsonPrimitive()))) {
              return DataResult.error(() -> "Expected the declared JSON scalar kind");
            }
            return codec.decode(ops, input);
          }
        });
  }

  private static <N extends Number> Codec<N> numeric(
      Codec<N> encoder,
      BigDecimal minimum,
      BigDecimal maximum,
      BiFunction<BigDecimal, String, N> conversion) {
    return Codec.of(
        encoder,
        new Decoder<N>() {
          @Override
          public <O> DataResult<Pair<N, O>> decode(DynamicOps<O> ops, O input) {
            // Compressed JsonOps admits numeric strings. Authored number fields do not.
            if (ops instanceof JsonOps
                && input instanceof JsonElement json
                && (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber())) {
              return invalidNumber();
            }
            var number = ops.getNumberValue(input);
            if (number.result().isEmpty()) {
              return invalidNumber();
            }
            try {
              String text = number.result().orElseThrow().toString();
              var decimal = new BigDecimal(text);
              // Compare magnitudes before exact integer conversion can request powers of ten.
              if (decimal.compareTo(minimum) < 0 || decimal.compareTo(maximum) > 0) {
                return invalidNumber();
              }
              return DataResult.success(Pair.of(conversion.apply(decimal, text), ops.empty()));
            } catch (ArithmeticException | NumberFormatException invalid) {
              return invalidNumber();
            }
          }
        });
  }

  private static <T> DataResult<T> invalidNumber() {
    return DataResult.error(
        () -> "Expected a number within the declared type's range and precision");
  }

  private ExactCodecs() {}
}
