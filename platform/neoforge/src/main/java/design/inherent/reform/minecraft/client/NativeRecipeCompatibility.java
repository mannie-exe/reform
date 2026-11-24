package design.inherent.reform.minecraft.client;

import net.neoforged.fml.ModList;

/** Loader presence only; never reads or overrides a viewer's configuration. */
final class NativeRecipeCompatibility {
  private NativeRecipeCompatibility() {}

  static boolean emiInstalled() {
    return ModList.get().isLoaded("emi");
  }
}
