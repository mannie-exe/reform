package design.inherent.reform.value;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.DecodedValue;

/** A record field's explicit missing-value policy; ordinary values retain their original codec. */
public final class DefaultedType<T> implements ValueType<T> {
  private final ValueShape.Defaulted<T> shape;

  DefaultedType(ValueType<T> element, T fallback) {
    shape = new ValueShape.Defaulted<>(element, fallback);
    var owned = shape.fallback();
    var encoded = element.codec().encodeStart(JsonOps.INSTANCE, owned);
    if (encoded.error().isPresent() || encoded.result().isEmpty())
      throw new IllegalArgumentException("Field default cannot encode completely");
    var decoded = element.decode(JsonOps.INSTANCE, encoded.result().orElseThrow(), FieldPath.ROOT);
    if (!decoded.report().valid()
        || decoded.value().isEmpty()
        || !element.equivalent(owned, decoded.value().orElseThrow()))
      throw new IllegalArgumentException(
          "Field default must roundtrip as a complete admissible value");
  }

  public T fallback() {
    return shape.fallback();
  }

  public boolean isDefault(T value) {
    return shape.element().equivalent(value, fallback());
  }

  @Override
  public Codec<T> codec() {
    return shape.element().codec();
  }

  @Override
  public ValueShape.Defaulted<T> shape() {
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
