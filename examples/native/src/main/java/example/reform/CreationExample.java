package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.WorldRecipe;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Explicit public factory use, not a replacement editor registered for vanilla presets. */
@OnlyIn(Dist.CLIENT)
public final class CreationExample {
  public record Recipe(int soilDepth) {}

  private static final FieldKey<Integer> SOIL =
      FieldKey.of("soil_depth", ValueTypes.integer(1, 64));
  public static final DocumentDefinition<Recipe> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("reform_native_example:creation"),
          1,
          RecordType.<Recipe>builder(v -> new Recipe(v.get(SOIL)))
              .field(SOIL, Recipe::soilDepth)
              .build(),
          () -> new Recipe(2));

  private static FlatLevelSource flat(WorldCreationContext source) {
    if (!(source.selectedDimensions().overworld() instanceof FlatLevelSource flat)
        || flat.settings().getLayersInfo().size() != 3
        || !flat.settings().getLayersInfo().get(1).getBlockState().is(Blocks.DIRT))
      throw new PolicyRefusal(
          "example.foreign", "This example edits the three-layer flat preset only");
    return flat;
  }

  public static WorldRecipe<Recipe, Integer> editor(
      CreateWorldScreen parent, WorldCreationContext original) {
    flat(original);
    return WorldRecipe.worldRecipe(
        parent, original, DEFINITION, domain(), SnapshotPolicy.immutable());
  }

  /** Public domain policy reused by the isolated native callback/identity controls. */
  public static WorldRecipe.Domain<Recipe, Integer> domain() {
    return new WorldRecipe.Domain<Recipe, Integer>() {
      @Override
      public Recipe read(WorldCreationContext source) {
        return new Recipe(flat(source).settings().getLayersInfo().get(1).getHeight());
      }

      @Override
      public Integer compile(WorldCreationContext source, Recipe recipe) {
        return recipe.soilDepth();
      }

      @Override
      public WorldDimensions dimensions(
          WorldCreationContext source,
          RegistryAccess.Frozen registries,
          WorldDimensions dimensions,
          Integer soilDepth) {
        var settings = flat(source).settings();
        var layers = new ArrayList<>(settings.getLayersInfo());
        layers.set(1, new FlatLayerInfo(soilDepth, Blocks.DIRT));
        var replacement =
            settings.withBiomeAndLayers(layers, settings.structureOverrides(), settings.getBiome());
        return dimensions.replaceOverworldGenerator(registries, new FlatLevelSource(replacement));
      }
    };
  }

  private CreationExample() {}
}
