package design.inherent.reform.minecraft;

import design.inherent.reform.presentation.ChoiceDisplay;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** One localized owner label, with exact captured input retained in value details. */
public final class ChoiceComponents {
  public static Component label(Optional<ChoiceDisplay.Caption> caption, Component raw) {
    return caption
        .<Component>map(
            value -> Component.translatableWithFallback(value.translationKey(), value.fallback()))
        .orElse(raw);
  }

  public static Component value(Optional<ChoiceDisplay.Caption> caption, Component raw) {
    return caption
        .<Component>map(value -> label(caption, raw).copy().append(" (").append(raw).append(")"))
        .orElse(raw);
  }

  private ChoiceComponents() {}
}
