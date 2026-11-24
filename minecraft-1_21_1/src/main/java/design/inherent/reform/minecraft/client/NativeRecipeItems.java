package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Exact native item lookup for an explicit recipe activation, never an inventory mutation. */
public final class NativeRecipeItems {
  private NativeRecipeItems() {}

  public static ItemStack item(DocumentId id) {
    var key = ResourceLocation.parse(id.toString());
    var item =
        BuiltInRegistries.ITEM
            .getOptional(key)
            .orElseThrow(
                () ->
                    new PolicyRefusal(
                        "recipe.item_missing", "The item for this recipe link is not present."));
    var stack = new ItemStack(item);
    if (stack.isEmpty())
      throw new PolicyRefusal("recipe.item_missing", "This recipe link needs a nonempty item.");
    return stack;
  }
}
