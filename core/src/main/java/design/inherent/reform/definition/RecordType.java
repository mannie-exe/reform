package design.inherent.reform.definition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import design.inherent.reform.validation.DecodedValue;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.DefaultedType;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** One typed declaration supplies strict record codecs, snapshots and ordered editor structure. */
public final class RecordType<T> implements ValueType<T> {
  public static final class Values {
    private final Map<FieldKey<?>, Object> values;

    private Values(Map<FieldKey<?>, Object> values) {
      this.values = Map.copyOf(values);
    }

    @SuppressWarnings("unchecked") // Only the paired FieldKey's decoder inserts this entry.
    public <V> V get(FieldKey<V> field) {
      if (!values.containsKey(field))
        throw new IllegalArgumentException("Field is not declared in this record: " + field.id());
      return (V) values.get(field);
    }
  }

  public static final class Builder<T> {
    private final Function<Values, T> constructor;
    private final Map<String, Binding<T, ?>> fields = new LinkedHashMap<>();

    private Builder(Function<Values, T> constructor) {
      this.constructor = Objects.requireNonNull(constructor);
    }

    public <V> Builder<T> field(FieldKey<V> field, Function<T, V> getter) {
      var binding = new Binding<>(field, Objects.requireNonNull(getter));
      if (fields.putIfAbsent(field.id(), binding) != null)
        throw new IllegalArgumentException("Duplicate field ID: " + field.id());
      return this;
    }

    public RecordType<T> build() {
      return new RecordType<>(List.copyOf(fields.values()), constructor);
    }
  }

  private record Binding<T, V>(FieldKey<V> key, Function<T, V> getter) {
    private <O> ValidationReport decode(
        DynamicOps<O> ops,
        MapLike<O> input,
        FieldPath parent,
        Map<FieldKey<?>, Object> values,
        Set<String> encodedNames) {
      O node = input.get(key.id());
      if (node == null && (ops.compressMaps() || !encodedNames.contains(key.id()))) {
        if (key.type() instanceof DefaultedType<V> defaulted) {
          values.put(key, defaulted.fallback());
          return ValidationReport.VALID;
        }
        return new ValidationReport(
            List.of(
                Issue.error("value.required", parent.field(key.id()), "Required field is missing")),
            true);
      }
      if (node == null) node = ops.empty();
      var decoded = key.type().decode(ops, node, parent.field(key.id()));
      decoded.value().ifPresent(value -> values.put(key, value));
      return decoded.report();
    }

    private <O> RecordBuilder<O> encode(T value, DynamicOps<O> ops, RecordBuilder<O> prefix) {
      V field = getter.apply(value);
      var encoded = key.type().codec().encodeStart(ops, field);
      if (key.type() instanceof DefaultedType<V> defaulted && defaulted.isDefault(field))
        return prefix.withErrorsFrom(encoded);
      var valueShape =
          key.type().shape() instanceof ValueShape.Defaulted<?> defaults
              ? defaults.element().shape()
              : key.type().shape();
      if (ops.compressMaps()
          && valueShape instanceof ValueShape.OptionalValue<?> optional
          && optional.encoding() == ValueShape.OptionalEncoding.NULL_OR_VALUE
          && encoded
              .result()
              .filter(valueNode -> Objects.equals(valueNode, ops.empty()))
              .isPresent())
        return prefix.withErrorsFrom(
            DataResult.error(
                () ->
                    "Explicit null optional fields cannot be represented in this compressed record"));
      return prefix.add(key.id(), encoded);
    }

    private void freeze(T value, SnapshotContext context, Map<FieldKey<?>, Object> values) {
      values.put(key, key.type().freeze(getter.apply(value), context));
    }

    private boolean equivalent(T first, T second) {
      return key.type().equivalent(getter.apply(first), getter.apply(second));
    }
  }

  private final List<Binding<T, ?>> fields;
  private final Function<Values, T> constructor;
  private final Codec<T> codec;
  private final ValueShape.ObjectValue shape;
  private final SnapshotPolicy<T> snapshots;

  private RecordType(List<Binding<T, ?>> fields, Function<Values, T> constructor) {
    this.fields = fields;
    this.constructor = constructor;
    shape =
        new ValueShape.ObjectValue(
            fields.stream().<FieldKey<?>>map(binding -> binding.key()).toList());
    snapshots =
        new SnapshotPolicy<>() {
          @Override
          public T freeze(T value, SnapshotContext context) {
            var values = new HashMap<FieldKey<?>, Object>();
            for (var field : fields) field.freeze(value, context, values);
            return Objects.requireNonNull(constructor.apply(new Values(values)));
          }

          @Override
          public boolean equivalent(T first, T second) {
            return fields.stream().allMatch(field -> field.equivalent(first, second));
          }
        };
    codec =
        new MapCodec<T>() {
          @Override
          public <O> Stream<O> keys(DynamicOps<O> ops) {
            return fields.stream().map(field -> ops.createString(field.key().id()));
          }

          @Override
          public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
            var decoded = decodeFields(ops, input, FieldPath.ROOT);
            if (decoded.value().isPresent())
              return DataResult.success(decoded.value().orElseThrow());
            return DataResult.error(
                () ->
                    decoded.report().issues().stream()
                        .map(Issue::fallback)
                        .reduce((first, second) -> first + "; " + second)
                        .orElse("Record validation unavailable"));
          }

          @Override
          public <O> RecordBuilder<O> encode(T value, DynamicOps<O> ops, RecordBuilder<O> prefix) {
            var current = prefix;
            for (var field : fields) current = field.encode(value, ops, current);
            return current;
          }
        }.codec();
  }

  public static <T> Builder<T> builder(Function<Values, T> constructor) {
    return new Builder<>(constructor);
  }

  @Override
  public Codec<T> codec() {
    return codec;
  }

  @Override
  public ValueShape shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<T> snapshotPolicy() {
    return snapshots;
  }

  @Override
  public <O> DecodedValue<T> decode(DynamicOps<O> ops, O input, FieldPath path) {
    var object = ops.getMap(input);
    if (object.result().isEmpty())
      return DecodedValue.failure(Issue.error("value.object", path, "Expected an object"));
    return decodeFields(ops, object.result().orElseThrow(), path);
  }

  private <O> DecodedValue<T> decodeFields(DynamicOps<O> ops, MapLike<O> input, FieldPath path) {
    var issues = new ArrayList<Issue>();
    var names = new HashSet<String>();
    var declared = new HashSet<String>();
    fields.forEach(field -> declared.add(field.key().id()));
    var entries = input.entries().limit((long) fields.size() + 1).toList();
    if (entries.size() > fields.size())
      issues.add(Issue.error("value.fields", path, "Object has too many fields"));
    for (var entry : entries) {
      var name = ops.getStringValue(entry.getFirst()).result();
      if (name.isEmpty()) issues.add(Issue.error("value.key", path, "Expected a text field key"));
      else if (!names.add(name.orElseThrow()))
        issues.add(
            Issue.error(
                "value.duplicate_field",
                name.orElseThrow().isBlank() ? path : path.field(name.orElseThrow()),
                "Duplicate field"));
      else if (!declared.contains(name.orElseThrow()))
        issues.add(
            Issue.error(
                "value.unknown_field",
                name.orElseThrow().isBlank() ? path : path.field(name.orElseThrow()),
                "Unknown field"));
    }
    var values = new HashMap<FieldKey<?>, Object>();
    boolean complete = true;
    for (var field : fields) {
      var report = field.decode(ops, input, path, values, names);
      issues.addAll(report.issues());
      complete &= report.complete();
    }
    var report = new ValidationReport(issues, complete);
    if (!report.valid()) return DecodedValue.failure(report);
    try {
      return DecodedValue.success(Objects.requireNonNull(constructor.apply(new Values(values))));
    } catch (RuntimeException invalid) {
      return DecodedValue.failure(
          Issue.error(
              "record.constructor",
              path,
              invalid.getMessage() == null ? "Record construction failed" : invalid.getMessage()));
    }
  }
}
