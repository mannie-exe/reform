package design.inherent.reform.minecraft.mixin;

import design.inherent.reform.minecraft.client.NativeMeshGraphicRuntime;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
abstract class GraphicStateMixin {
  @Inject(method = "reset", at = @At("RETURN"))
  private void reform$discardUnsubmittedGraphics(CallbackInfo callback) {
    NativeMeshGraphicRuntime.reset((GuiRenderState) (Object) this);
  }
}
