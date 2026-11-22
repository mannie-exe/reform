package example.reform.platform;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

final class NativeFabricKeys {
  static void register(KeyMapping mapping) {
    KeyMappingHelper.registerKeyMapping(mapping);
  }

  private NativeFabricKeys() {}
}
