package example.reform.platform.mixin;

import example.reform.platform.PlatformDecodedCloseFault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Verification-only marker around the actual shared decoder call. */
@Mixin(targets = "design.inherent.reform.minecraft.client.ClientGraphicImages", remap = false)
abstract class DecodedImageScopeMixin {
  @Inject(method = "decodeStills", at = @At("HEAD"))
  private static void enter(CallbackInfoReturnable<Object> callback) {
    PlatformDecodedCloseFault.enter();
  }

  @Inject(method = "decodeStills", at = @At("RETURN"))
  private static void leave(CallbackInfoReturnable<Object> callback) {
    PlatformDecodedCloseFault.leave();
  }
}
