package design.inherent.reform.value;

import design.inherent.reform.definition.FieldKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Editor structure is declared metadata, not reflection into a codec implementation. */
public sealed interface ValueShape
    permits ValueShape.Scalar, ValueShape.Sequence, ValueShape.ObjectValue, ValueShape.Custom {
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

  record Sequence(ValueType<?> element, int maximumSize, boolean unique) implements ValueShape {
    public Sequence {
      Objects.requireNonNull(element);
      if (maximumSize < 0) throw new IllegalArgumentException("Negative collection bound");
    }
  }

  record ObjectValue(List<FieldKey<?>> fields) implements ValueShape {
    public ObjectValue {
      fields = List.copyOf(fields);
    }
  }

  record Custom(String editorCapability) implements ValueShape {
    public Custom {
      if (Objects.requireNonNull(editorCapability).isBlank())
        throw new IllegalArgumentException("Missing editor capability");
    }
  }
}
