package example.reform.platform.mixin;

import example.reform.platform.PlatformTaskChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Read-only fixture observation of this prepared lease's actual native submission. */
@Mixin(targets = "design.inherent.reform.minecraft.client.PreparedGraphic", remap = false)
abstract class GraphicDrawMixin {
  @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
  private void suppressForIndependentPixelControl(CallbackInfoReturnable<Object> result) {
    if (PlatformTaskChecks.suppressDraw(this))
      result.setReturnValue(
          design.inherent.reform.minecraft.client.ClientMeshGraphic.DrawOutcome.WAITING);
  }

  @Inject(method = "draw", at = @At("RETURN"))
  private void observe(CallbackInfoReturnable<Object> result) {
    PlatformTaskChecks.recordDraw(this, result.getReturnValue());
    example.reform.platform.PlatformLoadedSpriteChecks.recordDraw(this, result.getReturnValue());
    example.reform.platform.PlatformLoadedGraphicChecks.recordDraw(this, result.getReturnValue());
  }
}
