package design.inherent.reform.neoforge.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

final class NativeNeoForgeEnvironment {
  static boolean remoteConfig(net.neoforged.fml.config.ModConfig.Type type) {
    return type == net.neoforged.fml.config.ModConfig.Type.SERVER;
  }

  static boolean isClient() {
    return FMLEnvironment.dist == Dist.CLIENT;
  }

  private NativeNeoForgeEnvironment() {}
}
