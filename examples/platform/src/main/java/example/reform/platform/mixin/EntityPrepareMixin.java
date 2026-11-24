package example.reform.platform.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import design.inherent.reform.minecraft.client.ClientEntitySubject;
import design.inherent.reform.minecraft.client.NativeGraphicAdapter;
import design.inherent.reform.minecraft.client.NativeGraphicPreparationContext;
import example.reform.platform.PlatformEntityChecks;
import org.spongepowered.asm.mixin.Mixin;

/** Scopes observations to actual native preparation, including failed entry. */
@Mixin(targets = "design.inherent.reform.minecraft.client.NativeEntityAdapter", remap = false)
abstract class EntityPrepareMixin {
  @WrapMethod(
      method =
          "prepare(Ldesign/inherent/reform/minecraft/client/ClientEntitySubject;Ldesign/inherent/reform/minecraft/client/NativeGraphicPreparationContext;)Ldesign/inherent/reform/minecraft/client/NativeGraphicAdapter$Prepared;")
  private NativeGraphicAdapter.Prepared prepare(
      ClientEntitySubject input,
      NativeGraphicPreparationContext context,
      Operation<NativeGraphicAdapter.Prepared> original) {
    PlatformEntityChecks.nativePrepare();
    try {
      return original.call(input, context);
    } finally {
      PlatformEntityChecks.nativePrepared();
    }
  }
}
