package design.inherent.reform.value;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.ChoiceDisplay;
import design.inherent.reform.validation.DecodedValue;
import java.util.Objects;
import java.util.Optional;

/** Presentation metadata over the actual choice codec, diagnostics, and snapshot policy. */
final class ChoiceType<T> implements ValueType<T> {
  private final ValueType<T> element;
  private final ValueShape.Scalar shape;

  ChoiceType(ValueType<T> element, ChoiceDisplay display) {
    this.element = Objects.requireNonNull(element);
    if (!(element.shape() instanceof ValueShape.Scalar scalar))
      throw new IllegalArgumentException("Choice display requires a declared scalar type");
    shape =
        new ValueShape.Scalar(
            scalar.kind(),
            scalar.domain(),
            scalar.maximumLength(),
            scalar.choices(),
            scalar.numericDisplay(),
            Optional.of(Objects.requireNonNull(display)));
  }

  @Override
  public Codec<T> codec() {
    return element.codec();
  }

  @Override
  public ValueShape.Scalar shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<T> snapshotPolicy() {
    return element.snapshotPolicy();
  }

  @Override
  public boolean equivalent(T first, T second) {
    return element.equivalent(first, second);
  }

  @Override
  public <O> DecodedValue<T> decode(DynamicOps<O> ops, O input, FieldPath path) {
    return element.decode(ops, input, path);
  }
}
