package design.inherent.reform.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;

final class NativeItemRegistries {
  static RegistryAccess actual(boolean empty) {
    var level = Minecraft.getInstance().level;
    if (level != null) return level.registryAccess();
    // Native item defaults are registry components supplied by the actual joined world. Never
    // bind synthetic components into the game's built-in holders to make a title preview work.
    if (!empty)
      throw new NativeGraphicUnavailable(
          "preview.item_context", "This item needs the native registry context of a joined world");
    return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private NativeItemRegistries() {}
}
