package example.reform.platform.mixin;

import design.inherent.reform.storage.OwnedFile;
import example.reform.platform.PlatformBatchWriteFault;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only fault inside the real replacement/readback interval, with no actor mutation. */
@Mixin(value = OwnedFile.class, remap = false)
abstract class OwnedFileWriteMixin {
  @Inject(
      method = "*",
      at =
          @At(
              value = "INVOKE",
              target = "Ldesign/inherent/reform/storage/OwnedFile$WriteProbe;run()V",
              shift = At.Shift.AFTER))
  private void replaced(CallbackInfoReturnable<OwnedFile.Result> callback) throws IOException {
    PlatformBatchWriteFault.replaced((OwnedFile) (Object) this);
  }
}
