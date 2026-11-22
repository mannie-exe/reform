package example.reform.platform;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

final class NativeFabricKeys {
  static void register(KeyMapping mapping) {
    KeyBindingHelper.registerKeyBinding(mapping);
  }

  private NativeFabricKeys() {}
}
