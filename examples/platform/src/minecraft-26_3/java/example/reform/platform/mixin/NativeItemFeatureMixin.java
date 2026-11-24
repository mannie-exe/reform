package example.reform.platform.mixin;

import example.reform.platform.PlatformLoadedItemChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts actual native feature entry and injects one explicitly armed causal failure. */
@Mixin(targets = "design.inherent.reform.minecraft.client.NativeLoadedItemAdapter", remap = false)
abstract class NativeItemFeatureMixin {
  @Inject(method = "lambda$prepare$0", at = @At("HEAD"))
  private static void enter(CallbackInfo callback) {
    PlatformLoadedItemChecks.nativeFeatureEntered();
  }
}
