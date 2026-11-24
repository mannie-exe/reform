package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.components.Button;

/** Native button invocation signature; this invokes no crafting or transfer action. */
final class NativeRecipeBooks {
  private NativeRecipeBooks() {}

  static void reveal(Button button) {
    button.onPress();
  }
}
