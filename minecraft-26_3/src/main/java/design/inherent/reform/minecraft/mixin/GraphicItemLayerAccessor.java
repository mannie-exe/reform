package design.inherent.reform.minecraft.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the actual prepared transform; camera edits affect only our owned layer copy. */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface GraphicItemLayerAccessor {
  @Accessor("localTransform")
  Matrix4f reform$graphicItemTransform();
}
