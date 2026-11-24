package design.inherent.reform.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only borrowed pixels; never close, mutate, or retain the game's image after capture. */
@Mixin(SpriteContents.class)
public interface GraphicSpriteContentsAccessor {
  @Accessor("originalImage")
  NativeImage reform$originalImage();
}
