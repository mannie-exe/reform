package design.inherent.reform.minecraft.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Actual native dependency for a fresh renderer context; never replaces the resolver. */
@Mixin(Minecraft.class)
public interface GraphicBlockResolverAccessor {
  @Accessor("blockModelResolver")
  BlockModelResolver reform$blockModelResolver();
}
