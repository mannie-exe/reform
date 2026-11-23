package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

abstract class NativeGraphicWidget extends AbstractWidget {
  NativeGraphicWidget(Component description) {
    super(0, 0, 20, 20, description.copy());
    active = false;
  }

  @Override
  protected final void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    drawGraphic(new NativeCanvas(graphics));
  }

  protected abstract void drawGraphic(FormCanvas canvas);

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
