package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormTokens;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Intrinsic native offers for custom controls; the actual parent still assigns their bounds. */
public final class ClientControls {
  /** The owner must retain its context, permission and lifetime guards in the callback. */
  public static Button.Builder action(Font font, Component label, Runnable selected) {
    Objects.requireNonNull(font);
    Objects.requireNonNull(selected);
    var caption = Objects.requireNonNull(label).copy();
    return Button.builder(caption, ignored -> selected.run())
        .bounds(
            0,
            0,
            Math.max(
                FormTokens.Control.MINIMUM_WIDTH,
                font.width(caption) + FormTokens.Control.CAPTION_PADDING),
            FormTokens.Control.HEIGHT);
  }

  private ClientControls() {}
}
