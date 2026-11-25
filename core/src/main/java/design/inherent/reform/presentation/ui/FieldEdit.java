package design.inherent.reform.presentation.ui;

import design.inherent.reform.edit.DraftEdit;
import java.util.Objects;

/** Explicit edits to one bound field. No variant saves, loads, or replaces another target. */
public sealed interface FieldEdit<V> {
  record Raw<V>(String text) implements FieldEdit<V> {
    public Raw {
      Objects.requireNonNull(text);
    }
  }

  record Typed<V>(V value) implements FieldEdit<V> {
    public Typed {
      Objects.requireNonNull(value);
    }
  }

  record Structure<V>(DraftEdit.Action action) implements FieldEdit<V> {
    public Structure {
      Objects.requireNonNull(action);
    }
  }
}
