package design.inherent.reform.definition;

import design.inherent.reform.value.ValueType;
import java.util.Objects;

/** A record's serialized field ID and value type, independent of Java and display names. */
public record FieldKey<T>(String id, ValueType<T> type) {
  public FieldKey {
    FieldPath.requireName(id);
    Objects.requireNonNull(type);
  }

  public static <T> FieldKey<T> of(String id, ValueType<T> type) {
    return new FieldKey<>(id, type);
  }
}
