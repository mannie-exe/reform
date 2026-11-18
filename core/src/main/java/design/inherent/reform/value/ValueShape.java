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
        ValueShape.Defaulted,
        ValueShape.OptionalValue,
        ValueShape.Alternative,
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

  /** An omitted record field resolves to its declared owned fallback, never an ambient source. */
  record Defaulted<T>(ValueType<T> element, T fallback) implements ValueShape {
    public Defaulted {
      Objects.requireNonNull(element);
      if (element.shape() instanceof Defaulted<?>)
        throw new IllegalArgumentException("Nested field-default wrappers are ambiguous");
      fallback = element.freeze(fallback);
    }

    @Override
    public T fallback() {
      return element.freeze(fallback);
    }
  }

  enum MapEncoding {
    OBJECT,
    ENTRY_LIST
  }

  enum OptionalEncoding {
    NULL_OR_VALUE,
    SINGLETON_LIST
  }

  /** Empty membership is distinct from field omission and from a declared fallback. */
  record OptionalValue<T>(ValueType<T> element, OptionalEncoding encoding) implements ValueShape {
    public OptionalValue {
      Objects.requireNonNull(element);
      Objects.requireNonNull(encoding);
    }
  }

  record Variant(String id, ValueType<?> type) {
    public Variant {
      if (Objects.requireNonNull(id).isBlank())
        throw new IllegalArgumentException("Missing variant ID");
      Objects.requireNonNull(type);
    }
  }

  /** An explicit discriminator/payload envelope; existing flattened native codecs stay owned. */
  record Alternative(String discriminator, String payload, List<Variant> variants)
      implements ValueShape {
    public Alternative {
      if (Objects.requireNonNull(discriminator).isBlank()
          || Objects.requireNonNull(payload).isBlank()
          || discriminator.equals(payload))
        throw new IllegalArgumentException("Alternative fields must be distinct nonblank names");
      variants = List.copyOf(variants);
      if (variants.isEmpty())
        throw new IllegalArgumentException("An alternative needs declared variants");
      var ids = new java.util.HashSet<String>();
      for (var variant : variants)
        if (!ids.add(variant.id())) throw new IllegalArgumentException("Duplicate variant ID");
    }

    public java.util.Optional<Variant> variant(String id) {
      return variants.stream().filter(variant -> variant.id().equals(id)).findFirst();
    }
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
