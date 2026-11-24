package design.inherent.reform.minecraft.mixin;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only native action identity; it does not style, replace, or activate ordinary widgets. */
@Mixin(ImageButton.class)
public interface RecipeBookButtonSpritesAccessor {
  @Accessor("sprites")
  WidgetSprites reform$recipeBookSprites();
}
