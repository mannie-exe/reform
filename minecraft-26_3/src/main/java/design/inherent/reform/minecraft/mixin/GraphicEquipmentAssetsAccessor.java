package design.inherent.reform.minecraft.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Supplies the actual native dependency to a fresh renderer context, without replacement. */
@Mixin(EntityRenderDispatcher.class)
public interface GraphicEquipmentAssetsAccessor {
  @Accessor("equipmentAssets")
  EquipmentAssetManager reform$equipmentAssets();
}
