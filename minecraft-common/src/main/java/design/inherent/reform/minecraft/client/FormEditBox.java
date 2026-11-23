package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Observe the native selection setter without reflection or a second text/value authority. */
final class FormEditBox extends NativeTextInput {
  private int anchor;

  FormEditBox(Font font, int x, int y, int width, int height, Component label) {
    super(font, x, y, width, height, label);
  }

  @Override
  public void setHighlightPos(int value) {
    super.setHighlightPos(value);
    anchor = Math.clamp(value, 0, getValue().length());
  }

  int selection() {
    return anchor;
  }
}
