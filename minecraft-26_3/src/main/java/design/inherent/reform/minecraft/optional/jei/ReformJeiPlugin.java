package design.inherent.reform.minecraft.optional.jei;

import mezz.jei.api.JeiPlugin;
import net.minecraft.resources.Identifier;

/** Version-specific public JEI identity; JEI alone admits this optional client class. */
@JeiPlugin
public final class ReformJeiPlugin extends ReformJeiPluginBase {
  @Override
  public Identifier getPluginUid() {
    return Identifier.fromNamespaceAndPath("reform", "recipe_links");
  }
}
