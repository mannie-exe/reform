package design.inherent.reform.edit;

import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;

/** Declared types at actual stable draft paths, including active collection/variant payloads. */
public final class DraftTypes {
  /** A structural map-row inspection value; authored maps retain their declared encoding. */
  public record MapRow<K, V>(K key, V value) {}

  public static ValueType<?> at(ValueType<?> root, DraftTree draft, FieldPath path) {
    if (!draft.activeAt(path))
      throw new IllegalArgumentException("Target is absent or inactive in this draft");
    return declaredAt(root, path);
  }

  /**
   * Type metadata only; this establishes neither row existence, active input nor edit authority.
   */
  public static ValueType<?> declaredAt(ValueType<?> root, FieldPath path) {
    ValueType<?> type = root;
    var segments = path.segments();
    for (int i = 0; i < segments.size(); i++) {
      var segment = segments.get(i);
      var shape = type.shape();
      if (segment instanceof FieldPath.Field field
          && shape instanceof ValueShape.ObjectValue object) {
        type =
            object.fields().stream()
                .filter(f -> f.id().equals(field.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Undeclared field"))
                .type();
      } else if (segment instanceof FieldPath.Row
          && shape instanceof ValueShape.Sequence sequence) {
        type = sequence.element();
      } else if (segment instanceof FieldPath.Row && shape instanceof ValueShape.Mapping mapping) {
        if (i + 1 == segments.size()) return mapRowType(mapping.key(), mapping.value());
        if (!(segments.get(++i) instanceof FieldPath.Part part))
          throw new IllegalArgumentException("A map row needs an explicit key/value part");
        type = part == FieldPath.Part.KEY ? mapping.key() : mapping.value();
      } else if (segment == FieldPath.Part.VALUE
          && shape instanceof ValueShape.Defaulted<?> defaults) {
        type = defaults.element();
      } else if (segment == FieldPath.Part.VALUE
          && shape instanceof ValueShape.OptionalValue<?> optional) {
        type = optional.element();
      } else if (segment instanceof FieldPath.Variant variant
          && shape instanceof ValueShape.Alternative alternatives) {
        type =
            alternatives
                .variant(variant.id())
                .orElseThrow(() -> new IllegalArgumentException("Undeclared variant"))
                .type();
      } else if (segment == FieldPath.Part.KEY && shape instanceof ValueShape.Alternative) {
        type = ValueTypes.text(4096);
      } else throw new IllegalArgumentException("Path is not part of the declared value shape");
    }
    return type;
  }

  public static <K, V> ValueType<MapRow<K, V>> mapRowType(ValueType<K> key, ValueType<V> value) {
    var keyField = FieldKey.of("key", key);
    var valueField = FieldKey.of("value", value);
    return RecordType.<MapRow<K, V>>builder(v -> new MapRow<>(v.get(keyField), v.get(valueField)))
        .field(keyField, MapRow::key)
        .field(valueField, MapRow::value)
        .build();
  }

  private DraftTypes() {}
}
