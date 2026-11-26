package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.ReferenceSuggestions;
import java.util.Objects;

/** A field-owned ID suggestion offer; input and edit semantics remain the original field's. */
public record CompletionParts(ReferenceSuggestions<?> suggestions, UiText label, int visibleRows) {
  public CompletionParts {
    Objects.requireNonNull(suggestions);
    Objects.requireNonNull(label);
    if (visibleRows < 1 || visibleRows > 12)
      throw new IllegalArgumentException("Declare 1–12 offered suggestion rows");
  }
}
