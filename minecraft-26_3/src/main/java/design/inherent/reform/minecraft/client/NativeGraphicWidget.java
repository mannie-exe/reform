package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

abstract class NativeGraphicWidget extends AbstractWidget {
  private ClientTheme theme = ClientTheme.standard();

  void theme(ClientTheme theme) {
    this.theme = theme;
  }

  NativeGraphicWidget(Component description) {
    super(0, 0, 20, 20, description.copy());
  }

  @Override
  protected final void extractWidgetRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    var canvas = new NativeCanvas(graphics);
    drawGraphic(canvas);
    if (isFocused()) {
      int color = theme.values().value(design.inherent.reform.presentation.ThemeValues.Role.FOCUS);
      int edge = Math.min(2, Math.min(getWidth(), getHeight()));
      canvas.fill(getX(), getY(), getRight(), getY() + edge, color);
      canvas.fill(getX(), getBottom() - edge, getRight(), getBottom(), color);
      canvas.fill(getX(), getY(), getX() + edge, getBottom(), color);
      canvas.fill(getRight() - edge, getY(), getRight(), getBottom(), color);
    }
  }

  protected abstract void drawGraphic(FormCanvas canvas);

  protected Component graphicNarration() {
    return getMessage().copy();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, graphicNarration());
    output.add(
        NarratedElementType.HINT,
        Component.translatableWithFallback("reform.preview.read_only", "Read-only preview."));
  }
}
