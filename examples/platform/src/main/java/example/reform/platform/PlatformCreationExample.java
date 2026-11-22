package example.reform.platform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.WorldRecipe;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueTypes;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** The existing flat recipe example, expressed through the portable public factory. */
final class PlatformCreationExample {
  record Recipe(int soilDepth) {}

  private static final FieldKey<Integer> SOIL =
      FieldKey.of("soil_depth", ValueTypes.integer(1, 64));
  static final DocumentDefinition<Recipe> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(PlatformServer.ID + ":creation"),
          1,
          RecordType.<Recipe>builder(values -> new Recipe(values.get(SOIL)))
              .field(SOIL, Recipe::soilDepth)
              .build(),
          () -> new Recipe(2));

  static WorldRecipe<Recipe, Integer> editor(
      CreateWorldScreen parent, WorldCreationContext original) {
    return WorldRecipe.worldRecipe(
        parent, original, DEFINITION, domain(), SnapshotPolicy.immutable());
  }

  static WorldRecipe.Domain<Recipe, Integer> domain() {
    return new WorldRecipe.Domain<>() {
      @Override
      public Recipe read(WorldCreationContext source) {
        return new Recipe(
            PlatformRecipeChecks.flat(source).settings().getLayersInfo().get(1).getHeight());
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
          Integer depth) {
        return PlatformRecipeChecks.dimensions(source, registries, dimensions, depth);
      }
    };
  }

  private PlatformCreationExample() {}
}
