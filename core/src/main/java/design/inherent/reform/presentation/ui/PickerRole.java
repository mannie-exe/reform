package design.inherent.reform.presentation.ui;

import design.inherent.reform.definition.FieldPath;
import java.util.Objects;

/** One declared field of the retained document, independent of highlighted catalogue choices. */
public record PickerRole<T>(String id, FieldPath field) {
  public PickerRole {
    Objects.requireNonNull(id);
    Objects.requireNonNull(field);
    if (id.isBlank() || id.length() > 128)
      throw new IllegalArgumentException("A bounded role ID is required");
  }
}
