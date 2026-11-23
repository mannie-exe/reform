package design.inherent.reform.minecraft.mixin;

import design.inherent.reform.minecraft.client.NativeMeshGraphicRuntime;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
abstract class GraphicGuiRendererMixin {
  @Shadow @Final private GuiRenderState renderState;

  @Inject(method = "render", at = @At("HEAD"))
  private void reform$beginGraphicConsumption(CallbackInfo callback) {
    NativeMeshGraphicRuntime.begin(renderState);
  }

  @Inject(method = "render", at = @At("RETURN"))
  private void reform$finishGraphicConsumption(CallbackInfo callback) {
    NativeMeshGraphicRuntime.consumed(renderState);
  }
}
