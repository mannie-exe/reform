package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import java.util.Objects;

/** A target within one admitted form; a presentation section never invents a data path. */
public sealed interface FormTarget permits FormTarget.Field, FormTarget.Section {
  record Field(FieldPath path) implements FormTarget {
    public Field {
      Objects.requireNonNull(path);
    }
  }

  record Section(String id) implements FormTarget {
    public Section {
      if (!Objects.requireNonNull(id).matches("(?:group|range):[a-z][a-z0-9._/-]{0,127}"))
        throw new IllegalArgumentException("A section target needs an actual layout identity");
    }
  }
}
