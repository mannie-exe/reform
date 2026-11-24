package design.inherent.reform.minecraft.optional.jei;

import design.inherent.reform.owner.PolicyRefusal;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.Item;

/** Native JEI catalyst / crafting-station API seam. */
final class NativeJeiWorkstations {
  private NativeJeiWorkstations() {}

  static boolean matches(
      IRecipeManager manager, IRecipeCategory<?> category, Item item, int[] inspected) {
    try (var stream =
        manager.createCraftingStationLookup(category.getRecipeType()).getItemStack()) {
      var iterator = stream.iterator();
      boolean matched = false;
      while (iterator.hasNext()) {
        if (++inspected[0] > 65536)
          throw new PolicyRefusal(
              "recipe.lookup_budget",
              "This workstation lookup exceeds its bounded search allowance.");
        matched |= iterator.next().is(item);
      }
      return matched;
    }
  }
}
