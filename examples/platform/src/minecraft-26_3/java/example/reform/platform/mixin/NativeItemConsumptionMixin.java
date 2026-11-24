package example.reform.platform.mixin;

import example.reform.platform.PlatformLoadedItemChecks;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Actual reset frontdoor before this extracted state's feature callback is consumed. */
@Mixin(targets = "design.inherent.reform.minecraft.client.NativeMeshGraphicRuntime", remap = false)
abstract class NativeItemConsumptionMixin {
  @Inject(method = "begin", at = @At("HEAD"))
  private static void resetPending(GuiRenderState state, CallbackInfo callback) {
    PlatformLoadedItemChecks.beforeNativeConsumption(state::reset);
  }

  @Inject(method = "begin", at = @At("RETURN"))
  private static void resetReturned(GuiRenderState state, CallbackInfo callback) {
    PlatformLoadedItemChecks.afterNativeConsumption();
  }
}
