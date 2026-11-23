package design.inherent.reform.minecraft.mixin;

import com.mojang.blaze3d.platform.Window;
import design.inherent.reform.minecraft.client.NativeMeshGraphicRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
abstract class GraphicStateMixin {
  @Inject(method = "close", at = @At("RETURN"))
  private void reform$nativeContextDestroyed(CallbackInfo callback) {
    if ((Object) this == Minecraft.getInstance().getWindow())
      NativeMeshGraphicRuntime.contextDestroyed();
  }
}
