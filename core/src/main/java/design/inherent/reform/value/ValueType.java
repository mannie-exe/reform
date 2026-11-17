package design.inherent.reform.value;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.DecodedValue;
import design.inherent.reform.validation.Issue;
import java.util.Objects;

/** Typed serialization, structural metadata and explicit owned snapshots. */
public interface ValueType<T> {
  Codec<T> codec();

  ValueShape shape();

  SnapshotPolicy<T> snapshotPolicy();

  default T freeze(T value) {
    return freeze(value, new SnapshotContext(SnapshotContext.Limits.CONFIGURATION));
  }

  default T freeze(T value, SnapshotContext context) {
    return context.copy(value, () -> snapshotPolicy().freeze(value, context));
  }

  default boolean equivalent(T first, T second) {
    return snapshotPolicy().equivalent(first, second);
  }

  default <O> DecodedValue<T> decode(DynamicOps<O> ops, O input, FieldPath path) {
    var decoded = codec().parse(ops, input);
    if (decoded.error().isPresent()) {
      return DecodedValue.failure(
          Issue.error("value.decode", path, decoded.error().orElseThrow().message()));
    }
    return DecodedValue.success(decoded.result().orElseThrow());
  }

  static <T> ValueType<T> of(Codec<T> codec, ValueShape shape, SnapshotPolicy<T> snapshots) {
    Objects.requireNonNull(codec);
    Objects.requireNonNull(shape);
    Objects.requireNonNull(snapshots);
    return new ValueType<>() {
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
    };
  }
}
