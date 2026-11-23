package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormTokens;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Intrinsic native offers for custom controls; the actual parent still assigns their bounds. */
public final class ClientControls {
  /** Bind one native control; this grants no edit, source, or application authority. */
  public static <W extends net.minecraft.client.gui.components.AbstractWidget> W theme(
      W widget, ClientTheme theme) {
    design.inherent.reform.minecraft.internal.ClientSettingsRuntime.requireClientThread();
    if (!(widget instanceof NativeControlStyle bound))
      throw new design.inherent.reform.owner.PolicyRefusal(
          "presentation.native_adapter_missing",
          "The native control styling adapter is unavailable");
    bound.reform$theme(Objects.requireNonNull(theme));
    widget.setMessage(theme.text(ClientTheme.TextRole.VALUE, widget.getMessage()));
    if (widget instanceof net.minecraft.client.gui.components.EditBox input
        && (theme.explicitColor(ClientTheme.TextRole.VALUE)
            || theme.explicitColor(ClientTheme.TextRole.SUPPORT))) {
      bound.reform$inputArgb(true);
      input.setTextColor(theme.color(ClientTheme.TextRole.VALUE));
      input.setTextColorUneditable(theme.color(ClientTheme.TextRole.SUPPORT));
    }
    if (widget instanceof net.minecraft.client.gui.components.AbstractButton
        || widget instanceof net.minecraft.client.gui.components.AbstractSliderButton
        || widget instanceof net.minecraft.client.gui.components.EditBox)
      widget.setHeight(
          Math.max(
              widget.getHeight(),
              theme
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT)));
    return widget;
  }

  /** The same native action with explicitly scoped typography and intrinsic geometry. */
  public static ClientTheme.ButtonOffer action(
      Font font, Component label, Runnable selected, ClientTheme theme) {
    Objects.requireNonNull(selected);
    var caption = theme.text(ClientTheme.TextRole.VALUE, Objects.requireNonNull(label));
    var values = theme.values();
    return theme
        .buttonBuilder(caption, ignored -> selected.run())
        .bounds(
            0,
            0,
            Math.max(
                values.value(
                    design.inherent.reform.presentation.ThemeValues.Role.CONTROL_MINIMUM_WIDTH),
                Math.addExact(
                    font.width(caption),
                    values.value(
                        design.inherent.reform.presentation.ThemeValues.Role
                            .CONTROL_CAPTION_PADDING))),
            values.value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT));
  }

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
