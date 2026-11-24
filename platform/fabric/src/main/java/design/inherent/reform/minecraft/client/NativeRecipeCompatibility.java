package design.inherent.reform.minecraft.client;

import net.fabricmc.loader.api.FabricLoader;

/** Loader presence only; never reads or overrides a viewer's configuration. */
final class NativeRecipeCompatibility {
  private NativeRecipeCompatibility() {}

  static boolean emiInstalled() {
    return FabricLoader.getInstance().isModLoaded("emi");
  }
}
