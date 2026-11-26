package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Native, resource-pack-aware history glyphs; captions remain the accessible action names. */
final class NativeHistoryControls {
  static Button create(boolean forward, Component label, Runnable action, ClientTheme theme) {
    var button =
        SpriteIconButton.builder(label.copy(), ignored -> action.run(), true)
            .size(
                31,
                theme
                    .values()
                    .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT))
            .sprite(
                Identifier.withDefaultNamespace(
                    forward ? "widget/page_forward" : "widget/page_backward"),
                23,
                13)
            .build();
    return ClientControls.theme(button, theme);
  }

  private NativeHistoryControls() {}
}
