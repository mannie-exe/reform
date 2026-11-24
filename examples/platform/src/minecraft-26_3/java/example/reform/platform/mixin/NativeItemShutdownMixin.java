package example.reform.platform.mixin;

import example.reform.platform.PlatformNativeItemShutdownChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Passive observation after actual game renderer shutdown; no native cleanup calls. */
@Mixin(targets = "com.mojang.blaze3d.systems.RenderSystem")
abstract class NativeItemShutdownMixin {
  @Inject(method = "shutdownRenderer", at = @At("RETURN"))
  private static void closed(CallbackInfo callback) {
    PlatformNativeItemShutdownChecks.rendererClosed();
  }
}
