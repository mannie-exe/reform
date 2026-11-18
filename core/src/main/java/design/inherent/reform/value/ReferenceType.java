package design.inherent.reform.value;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.DecodedValue;

/** Original codec/snapshot semantics, with an explicit separately admitted selector source. */
final class ReferenceType<T> implements ValueType<T> {
  private final ValueShape.Reference<T> shape;

  ReferenceType(ValueType<T> element, DocumentId source) {
    shape = new ValueShape.Reference<>(element, source);
  }

  @Override
  public Codec<T> codec() {
    return shape.element().codec();
  }

  @Override
  public ValueShape.Reference<T> shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<T> snapshotPolicy() {
    return shape.element().snapshotPolicy();
  }

  @Override
  public <O> DecodedValue<T> decode(DynamicOps<O> ops, O input, FieldPath path) {
    return shape.element().decode(ops, input, path);
  }
}
