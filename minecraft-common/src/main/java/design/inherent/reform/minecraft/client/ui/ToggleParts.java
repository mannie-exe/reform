package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.UiBinding;
import java.util.Objects;

/** A controlled native checkbox: state changes only through its declared command. */
public record ToggleParts(UiText label, UiText help, UiBinding<Boolean> binding) {
  public ToggleParts {
    Objects.requireNonNull(label);
    Objects.requireNonNull(help);
    Objects.requireNonNull(binding);
  }
}
