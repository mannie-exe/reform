package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;

/** Native horizontal text reflow without changing the caret, selection or draft. */
final class ControlSizing {
  // Native views may retain a widget through reflow. Assigned bounds are not its intrinsic offer.
  // Values do not retain keys; abandoned widgets and their measurements can be collected together.
  private static final Map<AbstractWidget, FormGeometry.Size> PREFERRED = new WeakHashMap<>();

  static FormGeometry.Size preferred(AbstractWidget widget) {
    return PREFERRED.computeIfAbsent(
        widget, key -> new FormGeometry.Size(key.getWidth(), key.getHeight()));
  }

  static void offer(AbstractWidget widget, int width, int height) {
    PREFERRED.put(widget, new FormGeometry.Size(width, height));
  }

  static void resize(AbstractWidget widget, int width) {
    preferred(widget);
    if (widget.getWidth() == width) return;
    widget.setWidth(width);
    if (widget instanceof EditBox input) {
      int cursor = input.getCursorPosition();
      int selection = input instanceof FormEditBox form ? form.selection() : cursor;
      input.setCursorPosition(0);
      input.setCursorPosition(cursor);
      input.setHighlightPos(selection);
    }
  }

  private ControlSizing() {}
}
