package design.inherent.reform.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Plain contextual information action with native button input/focus and subject narration. */
final class InformationButton extends NativeNavigationButton {
  private final Component subject;
  private ClientTheme theme = ClientTheme.standard();

  void theme(ClientTheme theme) {
    this.theme = theme;
    setMessage(theme.text(ClientTheme.TextRole.VALUE, getMessage()));
    setWidth(
        theme
            .values()
            .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_INFORMATION));
    setHeight(
        theme.values().value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT));
  }

  InformationButton(Component subject, Runnable action) {
    super(
        Component.literal("i").withStyle(style -> NativeFonts.uniform(style).withBold(true)),
        20,
        action);
    this.subject = subject.copy();
    setTooltip(Tooltip.create(caption()));
  }

  private Component caption() {
    return Component.translatableWithFallback("reform.information.about", "About %s", subject);
  }

  @Override
  protected void renderNavigation(FormCanvas canvas, int x, int y, float delta) {
    int color =
        !active
            ? theme.color(ClientTheme.TextRole.SUPPORT)
            : theme.color(
                isHoveredOrFocused() ? ClientTheme.TextRole.ACTIVE : ClientTheme.TextRole.VALUE);
    if (isFocused()) {
      canvas.fill(
          getX(),
          getY() + height - 2,
          getX() + width,
          getY() + height,
          theme.values().value(design.inherent.reform.presentation.ThemeValues.Role.FOCUS));
    }
    canvas.drawArgb(
        Minecraft.getInstance().font,
        getMessage().getVisualOrderText(),
        getX() + (width - Minecraft.getInstance().font.width(getMessage())) / 2,
        getY() + (height - Minecraft.getInstance().font.lineHeight) / 2,
        color,
        true);
  }

  @Override
  public void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.button", caption()));
    if (isFocused())
      output.add(
          NarratedElementType.USAGE, Component.translatable("narration.button.usage.focused"));
  }
}
