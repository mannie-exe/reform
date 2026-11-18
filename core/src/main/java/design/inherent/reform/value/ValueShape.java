package design.inherent.reform.value;

import design.inherent.reform.definition.FieldKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Editor structure is declared metadata, not reflection into a codec implementation. */
public sealed interface ValueShape
    permits ValueShape.Scalar,
        ValueShape.Sequence,
        ValueShape.ObjectValue,
        ValueShape.Mapping,
        ValueShape.Custom {
  enum ScalarKind {
    BOOLEAN,
    BYTE,
    SHORT,
    INTEGER,
    LONG,
    FLOAT,
    DOUBLE,
    TEXT,
    CHOICE
  }

  record Scalar(
      ScalarKind kind,
      BigDecimal minimum,
      BigDecimal maximum,
      int maximumLength,
      List<String> choices)
      implements ValueShape {
    public Scalar {
      Objects.requireNonNull(kind);
      choices = List.copyOf(choices);
    }
  }

  enum CollectionOrder {
    ORDERED,
    UNORDERED
  }

  record Sequence(ValueType<?> element, int maximumSize, boolean unique, CollectionOrder order)
      implements ValueShape {
    public Sequence(ValueType<?> element, int maximumSize, boolean unique) {
      this(element, maximumSize, unique, CollectionOrder.ORDERED);
    }

    public Sequence {
      Objects.requireNonNull(element);
      Objects.requireNonNull(order);
      if (maximumSize < 0) throw new IllegalArgumentException("Negative collection bound");
      if (order == CollectionOrder.UNORDERED && !unique)
        throw new IllegalArgumentException("Unordered membership requires a unique collection");
    }
  }

  record ObjectValue(List<FieldKey<?>> fields) implements ValueShape {
    public ObjectValue {
      fields = List.copyOf(fields);
    }
  }

  enum MapEncoding {
    OBJECT,
    ENTRY_LIST
  }

  record Mapping(ValueType<?> key, ValueType<?> value, int maximumSize, MapEncoding encoding)
      implements ValueShape {
    public Mapping {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
      Objects.requireNonNull(encoding);
      if (maximumSize < 0) throw new IllegalArgumentException("Negative map bound");
    }
  }

  record Custom(String editorCapability) implements ValueShape {
    public Custom {
      if (Objects.requireNonNull(editorCapability).isBlank())
        throw new IllegalArgumentException("Missing editor capability");
    }
  }
}
