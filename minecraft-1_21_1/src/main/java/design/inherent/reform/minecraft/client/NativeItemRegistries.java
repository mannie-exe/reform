package design.inherent.reform.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;

final class NativeItemRegistries {
  static RegistryAccess actual(boolean empty) {
    var level = Minecraft.getInstance().level;
    return level == null
        ? RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        : level.registryAccess();
  }

  private NativeItemRegistries() {}
}
