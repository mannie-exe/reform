package example.reform.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

final class NativeRecipeProbe {
  static void open() {
    var client = Minecraft.getInstance();
    CreateWorldScreen.openFresh(client, client.screen);
  }

  static WorldCreationContext flat(CreateWorldScreen parent) {
    var preset =
        parent
            .getUiState()
            .getSettings()
            .worldgenLoadContext()
            .registryOrThrow(Registries.WORLD_PRESET)
            .getHolderOrThrow(WorldPresets.FLAT);
    parent
        .getUiState()
        .updateDimensions((registries, dimensions) -> preset.value().createWorldDimensions());
    return parent.getUiState().getSettings();
  }

  private NativeRecipeProbe() {}
}
