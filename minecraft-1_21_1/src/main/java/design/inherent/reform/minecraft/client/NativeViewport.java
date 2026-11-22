package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.narration.NarratableEntry;

/** Minecraft 1.21.1 render and pointer dispatch for a form's native child controls. */
public abstract class NativeViewport extends AbstractContainerEventHandler
    implements Renderable, NarratableEntry {
  @Override
  public final void render(GuiGraphics graphics, int x, int y, float delta) {
    render(new NativeCanvas(graphics), x, y, delta);
  }

  public abstract void render(FormCanvas canvas, int x, int y, float delta);

  protected final boolean dragFocused(double x, double y, int button, double dx, double dy) {
    return getFocused() != null && getFocused().mouseDragged(x, y, button, dx, dy);
  }
}
