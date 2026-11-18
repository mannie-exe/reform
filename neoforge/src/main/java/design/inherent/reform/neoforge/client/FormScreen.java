package design.inherent.reform.neoforge.client;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Keep captured child focus when native dispatch reselects its current containing panel. */
abstract class FormScreen extends Screen {
  FormScreen(Component title) {
    super(title);
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    if (getFocused() != listener) super.setFocused(listener);
  }
}
