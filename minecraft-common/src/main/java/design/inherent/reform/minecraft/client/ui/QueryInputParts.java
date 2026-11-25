package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.*;
import java.util.Objects;

/** UI-local query buffer, not a second document field input. Enter flushes the declared search. */
public record QueryInputParts(
    UiText label,
    UiText help,
    UiRead<PickerState.Query> value,
    UiCommand<PickerState.Query> changes,
    UiCommand<Void> submit,
    int maximumCharacters) {
  public QueryInputParts {
    Objects.requireNonNull(label);
    Objects.requireNonNull(help);
    Objects.requireNonNull(value);
    Objects.requireNonNull(changes);
    Objects.requireNonNull(submit);
    if (maximumCharacters < 1 || maximumCharacters > 4096)
      throw new IllegalArgumentException("Invalid query allowance");
  }
}
