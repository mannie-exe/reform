package example.reform.platform.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import example.reform.platform.PlatformDecodedCloseFault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only fault AFTER actual native free; never leaks a game or test image. */
@Mixin(NativeImage.class)
abstract class DecodedImageCloseMixin {
  @Shadow private long pixels;

  @Inject(method = "close", at = @At("RETURN"))
  private void closed(CallbackInfo callback) {
    PlatformDecodedCloseFault.closeReturned(pixels);
  }
}
