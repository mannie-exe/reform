package design.inherent.reform.minecraft.mixin;

import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Actual native dependency for a fresh renderer context; never replaces the resolver. */
@Mixin(BlockEntityRenderDispatcher.class)
public interface GraphicBlockResolverAccessor {
  @Accessor("blockModelResolver")
  BlockModelResolver reform$blockModelResolver();
}
