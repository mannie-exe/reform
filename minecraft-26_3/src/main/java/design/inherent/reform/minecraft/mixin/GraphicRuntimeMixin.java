package design.inherent.reform.minecraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import design.inherent.reform.minecraft.client.NativeMeshGraphicRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
abstract class GraphicRuntimeMixin {
  @Inject(method = "shutdownRenderer", at = @At("HEAD"))
  private static void reform$drainGraphics(CallbackInfo callback) {
    NativeMeshGraphicRuntime.shutdown();
  }
}
