package design.inherent.reform.minecraft.client;

import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Minecraft 26.3 extraction and typed pointer dispatch for native child controls. */
public abstract class NativeViewport extends AbstractContainerEventHandler
    implements Renderable, NarratableEntry {
  private MouseButtonEvent pointer;
  private boolean doubleClick;

  @Override
  public final void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    render(new NativeCanvas(graphics), x, y, delta);
  }

  public abstract void render(FormCanvas canvas, int x, int y, float delta);

  @Override
  public final boolean mouseClicked(MouseButtonEvent event, boolean twice) {
    return dispatch(event, twice, () -> mouseClicked(event.x(), event.y(), event.button()));
  }

  public boolean mouseClicked(double x, double y, int button) {
    var event = pointer(x, y, button);
    return super.mouseClicked(event, event == pointer && doubleClick);
  }

  @Override
  public final boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    return dispatch(event, false, () -> mouseDragged(event.x(), event.y(), event.button(), dx, dy));
  }

  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    return super.mouseDragged(pointer(x, y, button), dx, dy);
  }

  protected final boolean dragFocused(double x, double y, int button, double dx, double dy) {
    return getFocused() != null && getFocused().mouseDragged(pointer(x, y, button), dx, dy);
  }

  @Override
  public final boolean mouseReleased(MouseButtonEvent event) {
    return dispatch(event, false, () -> mouseReleased(event.x(), event.y(), event.button()));
  }

  public boolean mouseReleased(double x, double y, int button) {
    return super.mouseReleased(pointer(x, y, button));
  }

  private boolean dispatch(MouseButtonEvent event, boolean twice, BooleanSupplier action) {
    var old = pointer;
    boolean oldTwice = doubleClick;
    pointer = event;
    doubleClick = twice;
    try {
      return action.getAsBoolean();
    } finally {
      pointer = old;
      doubleClick = oldTwice;
    }
  }

  private MouseButtonEvent pointer(double x, double y, int button) {
    return pointer != null && pointer.x() == x && pointer.y() == y && pointer.button() == button
        ? pointer
        : new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
  }
}
