package design.inherent.reform.value;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.validation.DecodedValue;
import java.util.Objects;
import java.util.Optional;

/** Presentation-only declaration over the actual original numeric codec/snapshot policy. */
final class NumericType<N extends Number> implements ValueType<N> {
  private final ValueType<N> element;
  private final ValueShape.Scalar shape;

  NumericType(ValueType<N> element, NumericDisplay display) {
    this.element = Objects.requireNonNull(element);
    if (!(element.shape() instanceof ValueShape.Scalar scalar))
      throw new IllegalArgumentException("Numeric presentation requires its declared scalar type");
    shape =
        new ValueShape.Scalar(
            scalar.kind(),
            scalar.domain(),
            scalar.maximumLength(),
            scalar.choices(),
            Optional.of(Objects.requireNonNull(display)));
  }

  @Override
  public Codec<N> codec() {
    return element.codec();
  }

  @Override
  public ValueShape.Scalar shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<N> snapshotPolicy() {
    return element.snapshotPolicy();
  }

  @Override
  public boolean equivalent(N first, N second) {
    return element.equivalent(first, second);
  }

  @Override
  public <O> DecodedValue<N> decode(DynamicOps<O> ops, O input, FieldPath path) {
    return element.decode(ops, input, path);
  }
}
