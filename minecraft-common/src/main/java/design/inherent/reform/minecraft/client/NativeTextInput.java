package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Native editing, selection, and IME; the owned binding records explicit color provenance. */
abstract class NativeTextInput extends EditBox {
  NativeTextInput(Font font, int x, int y, int width, int height, Component label) {
    super(font, x, y, width, height, label);
  }

  final void valueRole(ClientTheme.TextRole role) {
    var bound = (NativeControlStyle) this;
    if (bound.reform$theme() == null)
      throw new IllegalStateException("The input is not mounted in its owned scope");
    bound.reform$inputArgb(true);
    setTextColor(bound.reform$theme().color(role));
    setTextColorUneditable(bound.reform$theme().color(ClientTheme.TextRole.SUPPORT));
  }
}
