package example.reform.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

final class NativeRecipeProbe {
  static void open() {
    var client = Minecraft.getInstance();
    var previous = client.gui.screen();
    CreateWorldScreen.openFresh(client, () -> client.gui.setScreen(previous));
  }

  static WorldCreationContext flat(CreateWorldScreen parent) {
    var preset =
        parent
            .getUiState()
            .getSettings()
            .worldgenLoadContext()
            .lookupOrThrow(Registries.WORLD_PRESET)
            .getOrThrow(WorldPresets.FLAT);
    parent
        .getUiState()
        .updateDimensions((registries, dimensions) -> preset.value().createWorldDimensions());
    return parent.getUiState().getSettings();
  }

  private NativeRecipeProbe() {}
}
