package example.reform.platform.mixin;

import example.reform.platform.PlatformChestChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts actual native stage entry, distinct from worker wire admission and host refusal. */
@Mixin(targets = "design.inherent.reform.minecraft.client.NativeChestAdapter", remap = false)
abstract class ChestPrepareMixin {
  @Inject(
      method =
          "prepare(Ldesign/inherent/reform/minecraft/client/ClientChestSubject;Ldesign/inherent/reform/minecraft/client/NativeGraphicPreparationContext;)Ldesign/inherent/reform/minecraft/client/NativeGraphicAdapter$Prepared;",
      at = @At("HEAD"))
  private void entered(CallbackInfoReturnable<Object> result) {
    PlatformChestChecks.nativePrepare();
  }
}
