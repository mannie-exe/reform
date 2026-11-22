package design.inherent.reform.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Plain contextual information action with native button input/focus and subject narration. */
final class InformationButton extends NativeNavigationButton {
  private final Component subject;

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
            ? FormPanel.DESCRIPTION_TEXT
            : isHoveredOrFocused() ? FormPanel.ACTIVE_TEXT : FormPanel.TITLE_TEXT;
    if (isFocused()) {
      canvas.fill(getX(), getY() + height - 2, getX() + width, getY() + height, 0xffffffff);
    }
    canvas.drawCenteredString(
        Minecraft.getInstance().font,
        getMessage(),
        getX() + width / 2,
        getY() + (height - Minecraft.getInstance().font.lineHeight) / 2,
        color);
  }

  @Override
  public void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.button", caption()));
    if (isFocused())
      output.add(
          NarratedElementType.USAGE, Component.translatable("narration.button.usage.focused"));
  }
}
