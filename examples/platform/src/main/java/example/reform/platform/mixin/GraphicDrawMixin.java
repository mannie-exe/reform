package example.reform.platform.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import design.inherent.reform.minecraft.client.ClientMeshGraphic;
import design.inherent.reform.minecraft.client.FormCanvas;
import design.inherent.reform.presentation.graphics.GraphicCamera;
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

  @WrapMethod(method = "draw")
  private ClientMeshGraphic.DrawOutcome observeFailure(
      FormCanvas canvas,
      int x,
      int y,
      int width,
      int height,
      GraphicCamera camera,
      Operation<ClientMeshGraphic.DrawOutcome> original) {
    try {
      return original.call(canvas, x, y, width, height, camera);
    } catch (RuntimeException | Error failed) {
      example.reform.platform.PlatformLoadedSpriteChecks.recordFailure(this, failed);
      example.reform.platform.PlatformLoadedItemChecks.recordFailure(this, failed);
      example.reform.platform.PlatformLoadedBlockChecks.failure(this, failed);
      example.reform.platform.PlatformChestChecks.failure(this, failed);
      throw failed;
    }
  }

  @Inject(method = "draw", at = @At("RETURN"))
  private void observe(CallbackInfoReturnable<Object> result) {
    example.reform.platform.PlatformNativeItemShutdownChecks.recordDraw(
        this, result.getReturnValue());
    example.reform.platform.PlatformLoadedBlockChecks.draw(this, result.getReturnValue());
    example.reform.platform.PlatformChestChecks.draw(this, result.getReturnValue());
    PlatformTaskChecks.recordDraw(this, result.getReturnValue());
    example.reform.platform.PlatformLoadedSpriteChecks.recordDraw(this, result.getReturnValue());
    example.reform.platform.PlatformLoadedItemChecks.recordDraw(this, result.getReturnValue());
    example.reform.platform.PlatformLoadedGraphicChecks.recordDraw(this, result.getReturnValue());
  }
}
