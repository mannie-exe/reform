package example.reform.platform;

final class NativeFixtureSide {
  static boolean isClient() {
    return net.neoforged.fml.loading.FMLEnvironment.dist.isClient();
  }

  static net.neoforged.fml.config.ModConfig.Type serverConfigType() {
    return net.neoforged.fml.config.ModConfig.Type.SERVER;
  }

  static net.neoforged.fml.config.ModConfig.Type localConfigType() {
    return net.neoforged.fml.config.ModConfig.Type.COMMON;
  }

  private NativeFixtureSide() {}
}
