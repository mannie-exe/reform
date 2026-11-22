package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;

/** Native horizontal text reflow without changing the caret, selection or draft. */
final class ControlSizing {
  static void resize(AbstractWidget widget, int width) {
    if (widget.getWidth() == width) return;
    widget.setWidth(width);
    if (widget instanceof EditBox input) {
      int cursor = input.getCursorPosition();
      input.setCursorPosition(0);
      input.setCursorPosition(cursor);
    }
  }

  private ControlSizing() {}
}
