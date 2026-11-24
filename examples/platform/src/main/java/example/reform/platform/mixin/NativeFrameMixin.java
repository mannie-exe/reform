package example.reform.platform.mixin;

import example.reform.platform.PlatformFrameObservations;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Passive frame-loop completion, after attempted rendering; not a paint or presentation fence. */
@Mixin(Minecraft.class)
abstract class NativeFrameMixin {
  @Inject(method = "runTick", at = @At("RETURN"))
  private void reform$completedFrame(boolean advanceGameTime, CallbackInfo callback) {
    PlatformFrameObservations.completedFrame();
  }
}
