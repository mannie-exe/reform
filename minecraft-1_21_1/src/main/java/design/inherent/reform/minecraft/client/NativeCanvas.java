package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

record NativeCanvas(GuiGraphics graphics) implements FormCanvas {
  @Override
  public void enableScissor(int left, int top, int right, int bottom) {
    graphics.enableScissor(left, top, right, bottom);
  }

  @Override
  public void disableScissor() {
    graphics.disableScissor();
  }

  @Override
  public void widget(
      net.minecraft.client.gui.components.AbstractWidget widget, int x, int y, float delta) {
    widget.render(graphics, x, y, delta);
  }

  @Override
  public void fill(int left, int top, int right, int bottom, int color) {
    graphics.fill(left, top, right, bottom, color);
  }

  @Override
  public void hLine(int left, int right, int y, int color) {
    graphics.hLine(left, right, y, color);
  }

  @Override
  public void vLine(int x, int top, int bottom, int color) {
    graphics.vLine(x, top, bottom, color);
  }

  @Override
  public void drawString(Font font, Component text, int x, int y, int color) {
    graphics.drawString(font, text, x, y, FormCanvas.textColor(color));
  }

  @Override
  public void drawString(Font font, String text, int x, int y, int color) {
    graphics.drawString(font, text, x, y, FormCanvas.textColor(color));
  }

  @Override
  public void drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
    graphics.drawString(font, text, x, y, FormCanvas.textColor(color));
  }

  @Override
  public void drawCenteredString(Font font, Component text, int x, int y, int color) {
    graphics.drawCenteredString(font, text, x, y, FormCanvas.textColor(color));
  }

  @Override
  public void drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
    graphics.drawString(font, text, x, y, FormCanvas.textColor(color), shadow);
  }

  @Override
  public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
    graphics.drawString(font, text, x, y, FormCanvas.textColor(color), shadow);
  }

  @Override
  public void drawString(
      Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
    graphics.drawString(font, text, x, y, FormCanvas.textColor(color), shadow);
  }

  @Override
  public void drawWordWrap(Font font, FormattedText text, int x, int y, int width, int color) {
    graphics.drawWordWrap(font, text, x, y, width, FormCanvas.textColor(color));
  }
}
