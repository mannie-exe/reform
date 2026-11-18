package design.inherent.reform.value;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.DecodedValue;
import design.inherent.reform.validation.Issue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit lossless optional encodings; malformed or partial children never become empty. */
final class OptionalType<E> implements ValueType<Optional<E>> {
  private final ValueShape.OptionalValue<E> shape;
  private final Codec<Optional<E>> codec;
  private final SnapshotPolicy<Optional<E>> snapshots;

  OptionalType(ValueType<E> element, ValueShape.OptionalEncoding encoding) {
    shape = new ValueShape.OptionalValue<>(element, encoding);
    codec =
        new Codec<>() {
          @Override
          public <O> DataResult<Pair<Optional<E>, O>> decode(DynamicOps<O> ops, O input) {
            var decoded = read(ops, input, FieldPath.ROOT);
            if (!decoded.report().valid())
              return DataResult.error(
                  () ->
                      decoded.report().issues().stream()
                          .map(Issue::fallback)
                          .reduce((a, b) -> a + "; " + b)
                          .orElse("Optional validation unavailable"));
            return DataResult.success(Pair.of(decoded.value().orElseThrow(), ops.empty()));
          }

          @Override
          public <O> DataResult<O> encode(Optional<E> input, DynamicOps<O> ops, O prefix) {
            Objects.requireNonNull(input);
            if (input.isEmpty())
              return encoding == ValueShape.OptionalEncoding.SINGLETON_LIST
                  ? ops.mergeToList(prefix, List.of())
                  : ops.mergeToPrimitive(prefix, ops.empty());
            var encoded = element.codec().encodeStart(ops, input.orElseThrow());
            if (encoded.error().isPresent() || encoded.result().isEmpty())
              return DataResult.error(() -> "Present optional value cannot encode completely");
            O value = encoded.result().orElseThrow();
            if (encoding == ValueShape.OptionalEncoding.NULL_OR_VALUE
                && Objects.equals(value, ops.empty()))
              return DataResult.error(() -> "A present optional cannot use its empty encoding");
            var checked = element.decode(ops, value, FieldPath.ROOT.append(FieldPath.Part.VALUE));
            if (!checked.report().valid() || checked.value().isEmpty())
              return DataResult.error(() -> "Present optional value cannot decode completely");
            return encoding == ValueShape.OptionalEncoding.SINGLETON_LIST
                ? ops.mergeToList(prefix, List.of(value))
                : element.codec().encode(input.orElseThrow(), ops, prefix);
          }
        };
    snapshots =
        new SnapshotPolicy<>() {
          @Override
          public Optional<E> freeze(Optional<E> input, SnapshotContext context) {
            return input.map(value -> element.freeze(value, context));
          }

          @Override
          public boolean equivalent(Optional<E> first, Optional<E> second) {
            return first.isPresent() == second.isPresent()
                && (first.isEmpty()
                    || element.equivalent(first.orElseThrow(), second.orElseThrow()));
          }
        };
  }

  private <O> DecodedValue<Optional<E>> read(DynamicOps<O> ops, O input, FieldPath path) {
    O value;
    if (shape.encoding() == ValueShape.OptionalEncoding.SINGLETON_LIST) {
      var stream = ops.getStream(input);
      if (stream.error().isPresent() || stream.result().isEmpty())
        return DecodedValue.failure(
            Issue.error("value.optional", path, "Expected zero or one value"));
      var values = stream.result().orElseThrow().limit(2).toList();
      if (values.size() > 1)
        return DecodedValue.failure(
            Issue.error("value.optional_size", path, "Expected zero or one value"));
      if (values.isEmpty()) return DecodedValue.success(Optional.empty());
      value = values.getFirst() == null ? ops.empty() : values.getFirst();
    } else {
      if (input == null || Objects.equals(input, ops.empty()))
        return DecodedValue.success(Optional.empty());
      value = input;
    }
    var decoded = shape.element().decode(ops, value, path.append(FieldPath.Part.VALUE));
    if (!decoded.report().valid()) return DecodedValue.failure(decoded.report());
    if (shape.encoding() == ValueShape.OptionalEncoding.NULL_OR_VALUE) {
      var encoded = shape.element().codec().encodeStart(ops, decoded.value().orElseThrow());
      if (encoded.error().isPresent()
          || encoded.result().isEmpty()
          || Objects.equals(encoded.result().orElseThrow(), ops.empty()))
        return DecodedValue.failure(
            Issue.error(
                "value.optional_ambiguous",
                path,
                "A present optional cannot use its empty encoding"));
    }
    return DecodedValue.success(Optional.of(decoded.value().orElseThrow()));
  }

  @Override
  public Codec<Optional<E>> codec() {
    return codec;
  }

  @Override
  public ValueShape.OptionalValue<E> shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<Optional<E>> snapshotPolicy() {
    return snapshots;
  }

  @Override
  public <O> DecodedValue<Optional<E>> decode(DynamicOps<O> ops, O input, FieldPath path) {
    return read(ops, input, path);
  }
}
