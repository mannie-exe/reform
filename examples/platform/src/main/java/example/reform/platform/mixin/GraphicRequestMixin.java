package example.reform.platform.mixin;

import design.inherent.reform.minecraft.client.ClientGraphicHost;
import example.reform.platform.PlatformChestChecks;
import example.reform.platform.PlatformEntityChecks;
import example.reform.platform.PlatformLoadedBlockChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the public attempt returned by the actual mounted slot's own request. */
@Mixin(targets = "design.inherent.reform.minecraft.client.ClientGraphicHost$View", remap = false)
abstract class GraphicRequestMixin {
  @Inject(method = "request", at = @At("RETURN"))
  private void requested(Object subject, CallbackInfoReturnable<ClientGraphicHost.Attempt> result) {
    PlatformLoadedBlockChecks.request(subject, result.getReturnValue());
    PlatformChestChecks.request(subject, result.getReturnValue());
    PlatformEntityChecks.request(subject, result.getReturnValue());
  }
}
