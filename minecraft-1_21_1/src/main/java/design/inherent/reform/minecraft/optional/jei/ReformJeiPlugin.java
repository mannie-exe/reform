package design.inherent.reform.minecraft.optional.jei;

import mezz.jei.api.JeiPlugin;
import net.minecraft.resources.ResourceLocation;

/** Version-specific public JEI identity; JEI alone admits this optional client class. */
@JeiPlugin
public final class ReformJeiPlugin extends ReformJeiPluginBase {
  @Override
  public ResourceLocation getPluginUid() {
    return ResourceLocation.fromNamespaceAndPath("reform", "recipe_links");
  }
}
