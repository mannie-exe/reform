package design.inherent.reform.neoforge.client;

import net.neoforged.fml.loading.FMLEnvironment;

final class NativeNeoForgeEnvironment {
  static boolean remoteConfig(net.neoforged.fml.config.ModConfig.Type type) {
    return type == net.neoforged.fml.config.ModConfig.Type.SYNCED;
  }

  static boolean isClient() {
    return FMLEnvironment.getDist().isClient();
  }

  private NativeNeoForgeEnvironment() {}
}
