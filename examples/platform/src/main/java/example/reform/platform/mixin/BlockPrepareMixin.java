package example.reform.platform.mixin;

import example.reform.platform.PlatformLoadedBlockChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts actual native stage entry, distinct from worker wire admission and host refusal. */
@Mixin(targets = "design.inherent.reform.minecraft.client.NativeLoadedBlockAdapter", remap = false)
abstract class BlockPrepareMixin {
  @Inject(
      method =
          "prepare(Ldesign/inherent/reform/minecraft/client/ClientBlockSubject;Ldesign/inherent/reform/minecraft/client/NativeGraphicPreparationContext;)Ldesign/inherent/reform/minecraft/client/NativeGraphicAdapter$Prepared;",
      at = @At("HEAD"))
  private void entered(CallbackInfoReturnable<Object> result) {
    PlatformLoadedBlockChecks.nativePrepare();
  }
}
