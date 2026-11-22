package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native rendering boundary; form behavior retains the same canvas and native children. */
public abstract class NativeFormScreen extends Screen {
  protected NativeFormScreen(Component title) {
    super(title);
  }

  @Override
  public final void resize(net.minecraft.client.Minecraft client, int width, int height) {
    beforeResize();
    super.resize(client, width, height);
  }

  protected void beforeResize() {}

  @Override
  public final void render(GuiGraphics graphics, int x, int y, float delta) {
    render(new NativeCanvas(graphics), x, y, delta);
  }

  public void render(FormCanvas canvas, int x, int y, float delta) {
    super.render(((NativeCanvas) canvas).graphics(), x, y, delta);
  }

  protected void renderBackground(FormCanvas canvas, int x, int y, float delta) {
    super.renderBackground(((NativeCanvas) canvas).graphics(), x, y, delta);
  }

  @Override
  public final boolean charTyped(char character, int modifiers) {
    return charTyped((int) character, modifiers);
  }

  public boolean charTyped(int codepoint, int modifiers) {
    return codepoint >= Character.MIN_VALUE
        && codepoint <= Character.MAX_VALUE
        && super.charTyped((char) codepoint, modifiers);
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    if (listener != null && !children().contains(listener)) return;
    if (getFocused() != listener) super.setFocused(listener);
  }
}
