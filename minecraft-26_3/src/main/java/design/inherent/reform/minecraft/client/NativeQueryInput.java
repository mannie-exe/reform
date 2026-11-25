package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

final class NativeQueryInput extends FormEditBox {
  Runnable submit = () -> {};
  Runnable changed = () -> {};

  NativeQueryInput(Font font, Component label) {
    super(font, 0, 0, 180, 20, label);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (active && isFocused() && event.key() == InputConstants.KEY_RETURN) {
      submit.run();
      return true;
    }
    boolean handled = super.keyPressed(event);
    changed.run();
    return handled;
  }
}
