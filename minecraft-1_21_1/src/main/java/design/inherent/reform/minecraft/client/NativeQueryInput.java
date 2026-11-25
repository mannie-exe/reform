package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

final class NativeQueryInput extends FormEditBox {
  Runnable submit = () -> {};
  Runnable changed = () -> {};

  NativeQueryInput(Font font, Component label) {
    super(font, 0, 0, 180, 20, label);
  }

  @Override
  public boolean keyPressed(int key, int nativeCode, int modifiers) {
    if (active && isFocused() && key == InputConstants.KEY_RETURN) {
      submit.run();
      return true;
    }
    boolean handled = super.keyPressed(key, nativeCode, modifiers);
    changed.run();
    return handled;
  }
}
