package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Native button activation/narration with form-owned navigation presentation. */
public abstract class NativeNavigationButton extends Button {
  protected NativeNavigationButton(Component label, int width, Runnable action) {
    super(0, 0, width, 20, label, button -> action.run(), DEFAULT_NARRATION);
  }

  @Override
  protected final void extractContents(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    renderNavigation(new NativeCanvas(graphics), x, y, delta);
  }

  protected abstract void renderNavigation(FormCanvas canvas, int x, int y, float delta);
}
