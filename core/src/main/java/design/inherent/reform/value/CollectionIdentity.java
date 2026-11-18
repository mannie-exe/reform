package design.inherent.reform.value;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.Objects;

/**
 * Map keys and set members must retain Java value identity across their declared representation.
 */
final class CollectionIdentity {
  static boolean same(Object first, Object second) {
    return Objects.equals(first, second) && Objects.hashCode(first) == Objects.hashCode(second);
  }

  static <T, O> DataResult<O> encode(ValueType<T> type, T input, DynamicOps<O> ops) {
    var encoded = type.codec().encodeStart(ops, input);
    if (encoded.error().isPresent() || encoded.result().isEmpty())
      return DataResult.error(() -> "Collection identity could not encode completely");
    var decoded = type.codec().parse(ops, encoded.result().orElseThrow());
    if (decoded.error().isPresent()
        || decoded.result().isEmpty()
        || !same(input, decoded.result().orElseThrow()))
      return DataResult.error(
          () -> "Collection identity must roundtrip with value equality/hashCode");
    return encoded;
  }

  private CollectionIdentity() {}
}
