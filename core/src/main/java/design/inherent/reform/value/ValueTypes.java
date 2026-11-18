package design.inherent.reform.value;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import design.inherent.reform.serialization.ExactCodecs;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Common codec/snapshot/editor definitions. No consumer implements a writer or snapshot for these.
 */
public final class ValueTypes {
  public static ValueType<Boolean> bool() {
    return scalar(ExactCodecs.BOOL, ValueShape.ScalarKind.BOOLEAN, null, null, -1, List.of());
  }

  public static ValueType<Byte> byteInteger(byte minimum, byte maximum) {
    return number(
        ExactCodecs.BYTE,
        ValueShape.ScalarKind.BYTE,
        BigDecimal.valueOf(minimum),
        BigDecimal.valueOf(maximum));
  }

  public static ValueType<Short> shortInteger(short minimum, short maximum) {
    return number(
        ExactCodecs.SHORT,
        ValueShape.ScalarKind.SHORT,
        BigDecimal.valueOf(minimum),
        BigDecimal.valueOf(maximum));
  }

  public static ValueType<Float> floating(float minimum, float maximum) {
    return number(
        ExactCodecs.FLOAT,
        ValueShape.ScalarKind.FLOAT,
        new BigDecimal(Float.toString(minimum)),
        new BigDecimal(Float.toString(maximum)));
  }

  public static ValueType<Integer> integer(int minimum, int maximum) {
    return number(
        ExactCodecs.INT,
        ValueShape.ScalarKind.INTEGER,
        BigDecimal.valueOf(minimum),
        BigDecimal.valueOf(maximum));
  }

  public static ValueType<Long> longInteger(long minimum, long maximum) {
    return number(
        ExactCodecs.LONG,
        ValueShape.ScalarKind.LONG,
        BigDecimal.valueOf(minimum),
        BigDecimal.valueOf(maximum));
  }

  public static ValueType<Double> decimal(BigDecimal minimum, BigDecimal maximum) {
    return number(ExactCodecs.DOUBLE, ValueShape.ScalarKind.DOUBLE, minimum, maximum);
  }

  public static ValueType<Double> decimal(double minimum, double maximum) {
    return decimal(BigDecimal.valueOf(minimum), BigDecimal.valueOf(maximum));
  }

  public static ValueType<String> text(int maximumLength) {
    if (maximumLength < 0) throw new IllegalArgumentException("Negative text bound");
    return scalar(
        ExactCodecs.STRING.validate(
            value ->
                value.length() <= maximumLength
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Text exceeds its declared length")),
        ValueShape.ScalarKind.TEXT,
        null,
        null,
        maximumLength,
        List.of());
  }

  public static <E extends Enum<E>> ValueType<E> enumeration(Class<E> type) {
    var choices = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    var codec =
        ExactCodecs.STRING.flatXmap(
            text -> {
              try {
                return DataResult.success(Enum.valueOf(type, text));
              } catch (IllegalArgumentException invalid) {
                return DataResult.error(() -> "Unknown declared choice");
              }
            },
            value -> DataResult.success(value.name()));
    return scalar(codec, ValueShape.ScalarKind.CHOICE, null, null, -1, choices);
  }

  public static <E> ValueType<List<E>> list(ValueType<E> element, int maximumSize) {
    Objects.requireNonNull(element);
    if (maximumSize < 0) throw new IllegalArgumentException("Negative list bound");
    var codec =
        element
            .codec()
            .listOf(0, maximumSize)
            .validate(
                values ->
                    values.size() <= maximumSize
                        ? DataResult.success(values)
                        : DataResult.error(() -> "List exceeds its declared size"));
    return ValueType.of(
        codec,
        new ValueShape.Sequence(element, maximumSize, false),
        new SnapshotPolicy<>() {
          @Override
          public List<E> freeze(List<E> values, SnapshotContext context) {
            if (values.size() > maximumSize)
              throw new IllegalArgumentException("List exceeds its declared size");
            var copy = new ArrayList<E>(values.size());
            for (E value : values) copy.add(element.freeze(value, context));
            return List.copyOf(copy);
          }

          @Override
          public boolean equivalent(List<E> first, List<E> second) {
            if (first.size() != second.size()) return false;
            for (int i = 0; i < first.size(); i++) {
              if (!element.equivalent(first.get(i), second.get(i))) return false;
            }
            return true;
          }
        });
  }

  public static <K, V> ValueType<Map<K, V>> objectMap(
      ValueType<K> key, ValueType<V> value, int maximumSize) {
    return MappingType.object(key, value, maximumSize);
  }

  public static <K, V> ValueType<Map<K, V>> entryMap(
      ValueType<K> key, ValueType<V> value, int maximumSize) {
    return MappingType.entries(key, value, maximumSize);
  }

  private static <N extends Number> ValueType<N> number(
      Codec<N> codec, ValueShape.ScalarKind kind, BigDecimal minimum, BigDecimal maximum) {
    return scalar(
        ExactCodecs.bounded(codec, minimum, maximum), kind, minimum, maximum, -1, List.of());
  }

  private static <T> ValueType<T> scalar(
      Codec<T> codec,
      ValueShape.ScalarKind kind,
      BigDecimal minimum,
      BigDecimal maximum,
      int maximumLength,
      List<String> choices) {
    return ValueType.of(
        codec,
        new ValueShape.Scalar(kind, minimum, maximum, maximumLength, choices),
        SnapshotPolicy.immutable());
  }

  private ValueTypes() {}
}
