package design.inherent.reform.neoforge.client;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Keep child focus on reselection and refuse containers retired by native click callbacks. */
abstract class FormScreen extends Screen {
  FormScreen(Component title) {
    super(title);
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    // Native dispatch focuses a clicked child after its callback may have rebuilt this screen.
    if (listener != null && !children().contains(listener)) return;
    if (getFocused() != listener) super.setFocused(listener);
  }
}
