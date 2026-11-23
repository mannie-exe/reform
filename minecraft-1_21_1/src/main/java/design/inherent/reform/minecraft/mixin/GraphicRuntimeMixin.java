package design.inherent.reform.minecraft.mixin;

import design.inherent.reform.minecraft.client.NativeMeshGraphicRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class GraphicRuntimeMixin {
  @Inject(method = "close", at = @At("HEAD"))
  private void reform$drainGraphics(CallbackInfo callback) {
    NativeMeshGraphicRuntime.shutdown();
  }
}
